/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.protocol;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Main-thread host session for the negotiated first generic profile. Times are elapsed milliseconds. */
public final class OpenSession {
    public interface Host {
        void send(byte[] frame) throws Exception;
        /** Complete validated legacy or native view. Called when a fresh wire revision is due. */
        byte[] snapshot();
        /** Return false if the application cannot safely process this ordered physical scan. */
        boolean input(int serial, byte[] scan);
        /** Release held voices and clear session-bound state. */
        void reset();
        /** Accepted capabilities are known, but audio and physical input remain disabled until STATE ACK. */
        default void capabilitiesAccepted(Set<Integer> accepted) {}
        /** Only after a newly sent STATE revision is acknowledged. */
        void synchronizedDisplay(int audioMode, boolean transportAccepted);
        /** Context of each newly acknowledged STATE, for retaining operation feedback until delivery. */
        default void displayAcknowledged(int context) {}
        default void diagnostics(int op, int[] values) {}
        /** A native knob scan was applied; the connection may run its guarded display tick. */
        default void nativeKnobApplied() {}
        /** A pending native view was acknowledged; the connection may flush a queued edit. */
        default void nativeViewAcknowledged() {}
        void stopped(String reason);
    }
    public enum Phase { CLOSED, PROBING, HELLO, SYNCHRONIZING, ACTIVE }
    private final Host host;
    private byte[] hello;
    private final String namespace;
    private final int audioMode;
    private final boolean diagnosticProfile;
    private final boolean diagnosticExtended;
    private final Map<Integer, Wire6.Capability> requested = new LinkedHashMap<>();
    private final Set<Integer> required = new HashSet<>(), accepted = new HashSet<>();
    private final ArrayDeque<Wire6.Frame> inputs = new ArrayDeque<>();
    private static final class SentState {
        final long sequence;
        final int context;
        final int command;
        final byte[] view;
        SentState(long sequence, int context, int command, byte[] view) {
            this.sequence = sequence; this.context = context; this.command = command; this.view = view;
        }
    }
    private final Map<Integer, SentState> pendingStates = new HashMap<>();
    private Phase phase = Phase.CLOSED;
    private int session, revision, lastInput = -1;
    private long sentSequence, acknowledgedSequence, lastSend, lastHello, lastState, lastProgress, begun;
    private byte[] offered;
    private byte[] transfer;
    private byte[] lastNativeView;
    private byte[] confirmedNativeView;
    private int confirmedRevision;
    private boolean recoverFullView, inputSinceSample, ackCredit;
    private boolean fastParts;
    private int lastPartOffset, sendingPartEnd = -1;
    private int deferredReadyOffset = -1;
    private int transferOffset;
    private int heartbeatSerial = -1, nextHeartbeatSerial = 8192;
    private long lastHeartbeat, lastSample;
    private boolean owned;
    private int diagnosticSerial = -1, diagnosticOp, nextDiagnosticSerial = 12000;
    private int queuedDiagnosticOp;
    private long diagnosticSentAt;
    private boolean diagnosticStarted, diagnosticStartAttempted;

    public OpenSession(Host host, String namespace, int audioMode, Wire6.Capability... capabilities) {
        this.host = host;
        this.namespace = namespace;
        this.audioMode = audioMode;
        this.diagnosticExtended = "org.fm1.skiff.keytime2".equals(namespace) && audioMode == Wire6.USB_RETURN;
        this.diagnosticProfile = ("org.fm1.skiff.keytime".equals(namespace) &&
                audioMode == Wire6.USB_RETURN) || diagnosticExtended;
        this.hello = Wire6.hello(namespace, audioMode, capabilities);
        for (Wire6.Capability c : capabilities) {
            requested.put(c.id, c);
            if (c.required) required.add(c.id);
        }
    }
    public Phase phase() { return phase; }
    public boolean isOpen() { return phase != Phase.CLOSED; }
    public boolean isSynchronized() { return phase == Phase.ACTIVE; }
    public boolean hasCapability(int id) { return phase == Phase.ACTIVE && accepted.contains(id); }
    public int audioMode() { return audioMode; }
    public void begin(int id, long now) {
        if (id <= 0 || id > 16383) throw new IllegalArgumentException("session id");
        close();
        host.reset();
        session = id; revision = 0; sentSequence = acknowledgedSequence = 0;
        lastInput = -1; inputs.clear(); pendingStates.clear(); accepted.clear(); offered = null; owned = false;
        hello[1] = 0;
        transfer = null; transferOffset = 0; lastNativeView = confirmedNativeView = null;
        recoverFullView = inputSinceSample = ackCredit = fastParts = false;
        sendingPartEnd = -1;
        deferredReadyOffset = -1;
        heartbeatSerial = -1; nextHeartbeatSerial = 8192;
        diagnosticSerial = -1; diagnosticOp = queuedDiagnosticOp = 0;
        diagnosticSentAt = 0;
        diagnosticStarted = diagnosticStartAttempted = false;
        lastHeartbeat = now; lastSample = now - 100;
        begun = lastProgress = now; lastSend = now - 34; lastState = now - 100; lastHello = Long.MIN_VALUE;
        phase = Phase.PROBING;
        transmit(Wire6.PROBE, 0, new byte[0], now);
    }
    public void receive(Wire6.Frame frame, long now) {
        if (phase == Phase.CLOSED || frame.session != session) return;
        if (phase == Phase.PROBING) {
            if (frame.command != Wire6.OFFER || frame.serial != (offered == null ? 0 : 1)) return;
            if (frame.serial == 0 && frame.payload[1] >= 2 && requested.containsKey(Wire6.NATIVE_CHROME)) {
                offered = frame.payload.clone();
                lastProgress = now;
                transmit(Wire6.PROBE, 1, new byte[0], now);
                return;
            }
            byte[] offer = frame.payload;
            if (frame.serial == 1) {
                for (int i = 0; i < 5; i++) if (offered[i] != offer[i]) {
                    fail("FM1 discovery changed between capability pages."); return;
                }
                Set<Byte> ids = new HashSet<>();
                for (int at = 6; at < offered.length; at += 4) ids.add(offered[at]);
                for (int at = 6; at < offer.length; at += 4) if (!ids.add(offer[at])) {
                    fail("FM1 returned a duplicate capability."); return;
                }
                byte[] combined = Arrays.copyOf(offered, offered.length + offer.length - 6);
                System.arraycopy(offer, 6, combined, offered.length, offer.length - 6);
                combined[5] += offer[5];
                offer = combined;
            }
            finishDiscovery(offer, now);
            return;
        }
        if (frame.command == Wire6.DIAGNOSTICS) {
            if (!diagnosticProfile || !accepted.contains(Wire6.NATIVE_PANEL) || phase != Phase.ACTIVE ||
                    frame.serial != diagnosticSerial ||
                    frame.payload.length != (diagnosticExtended ? 46 : 38) ||
                    frame.payload[0] != diagnosticOp || frame.payload[1] != (diagnosticExtended ? 4 : 3)) return;
            int[] values = new int[(frame.payload.length - 2) / 4];
            for (int i = 0; i < values.length; i++) values[i] = Wire6.u28(frame.payload, 2 + 4 * i);
            int op = diagnosticOp;
            diagnosticSerial = -1; diagnosticOp = 0;
            if (op == 1) diagnosticStarted = true;
            if (op == 3) diagnosticStarted = false;
            host.diagnostics(op, values);
        } else if (frame.command == Wire6.ACK) {
            byte[] p = frame.payload;
            if (phase == Phase.HELLO && lastHello != Long.MIN_VALUE && p[0] == Wire6.HELLO && frame.serial == 0) {
                if (p[1] != 0) { fail("FM1 rejected wire-6 HELLO (" + p[1] + ")."); return; }
                if (!checkAccepted(p)) { fail("FM1 accepted an invalid capability set."); return; }
                owned = true;
                phase = Phase.SYNCHRONIZING;
                fastParts = hello[1] == 1 && accepted.contains(Wire6.NATIVE_UI);
                lastProgress = now;
                lastState = now - 100;
                host.capabilitiesAccepted(Collections.unmodifiableSet(new HashSet<>(accepted)));
                if (phase != Phase.SYNCHRONIZING) return;
            } else if ((phase == Phase.SYNCHRONIZING || phase == Phase.ACTIVE) &&
                    (p[0] == Wire6.STATE || p[0] == Wire6.VIEW_PART || p[0] == Wire6.VIEW_DELTA)) {
                SentState sent = pendingStates.get(frame.serial);
                if (sent == null || sent.command != p[0] || sent.sequence <= acknowledgedSequence) return;
                if (p[1] != 0) {
                    if (sent.command == Wire6.VIEW_DELTA && sent.sequence == sentSequence) {
                        recoverFullView = true;
                        lastSample = now - 100;
                    }
                    return;
                }
                pendingStates.remove(frame.serial);
                acknowledgedSequence = sent.sequence;
                pendingStates.entrySet().removeIf(entry -> entry.getValue().sequence <= sent.sequence);
                lastProgress = now;
                if (nativeIdle()) {
                    confirmedNativeView = sent.view;
                    confirmedRevision = frame.serial;
                    if (!nativeDelta() && sent.view != null &&
                            pendingStates.values().stream().allMatch(entry -> Arrays.equals(entry.view, sent.view)))
                        pendingStates.clear();
                }
                if (phase == Phase.SYNCHRONIZING) {
                    phase = Phase.ACTIVE;
                    host.synchronizedDisplay(audioMode, accepted.contains(Wire6.TRANSPORT));
                }
                if (phase == Phase.ACTIVE) host.displayAcknowledged(sent.context);
                if (phase == Phase.ACTIVE && nativeDelta() && inputSinceSample && transfer == null && pendingStates.isEmpty()) {
                    // The matching ACK proves the firmware consumed the prior view. One queued
                    // knob update may use this released mailbox slot without waiting 34 ms.
                    ackCredit = true;
                    host.nativeViewAcknowledged();
                }
            } else if (phase == Phase.ACTIVE && nativeIdle() && p[0] == Wire6.HEARTBEAT &&
                    p[1] == 0 && frame.serial == heartbeatSerial && transfer == null &&
                    pendingStates.isEmpty()) {
                heartbeatSerial = -1;
                lastProgress = now;
            }
        } else if (frame.command == Wire6.PART_READY && fastParts && transfer != null &&
                (phase == Phase.SYNCHRONIZING || phase == Phase.ACTIVE) && frame.serial == revision) {
            int offset = Wire6.u14(frame.payload, 0);
            if (sendingPartEnd >= 0) {
                if (offset == sendingPartEnd && offset < transfer.length) deferredReadyOffset = offset;
            } else if (offset == transferOffset && transferOffset > 0 && transferOffset < transfer.length) {
                sendPart(offset, now);
            }
        } else if (frame.command == Wire6.INPUT && (phase == Phase.SYNCHRONIZING || phase == Phase.ACTIVE)) {
            if (frame.payload.length != (accepted.contains(Wire6.NATIVE_PANEL) ? 26 : 24)) {
                fail("Unexpected panel input format."); return;
            }
            if (inputs.size() == 32) { fail("FM1 input queue overflow; reconnect required."); return; }
            inputs.addLast(frame);
            if (accepted.contains(Wire6.NATIVE_PANEL)) drainInputs();
        } else if (frame.command == Wire6.BYE) fail("FM1 closed the session.");
    }
    private void drainInputs() {
        while (!inputs.isEmpty() && phase == Phase.ACTIVE) {
            Wire6.Frame frame = inputs.removeFirst();
            if (lastInput >= 0) {
                int delta = (frame.serial - lastInput) & 16383;
                if (delta == 0) continue;
                if (delta != 1) { fail("FM1 input ordering gap; reconnect required."); return; }
            }
            lastInput = frame.serial;
            if ((frame.payload[5] & 64) != 0 && !accepted.contains(Wire6.NATIVE_UI)) {
                fail("FM1 sent device-owned input without native UI capability."); return;
            }
            if (!host.input(frame.serial, frame.payload.clone())) {
                fail("FM1 input could not be applied safely."); return;
            }
            inputSinceSample = true;
            if (nativeDelta() && accepted.contains(Wire6.NATIVE_PANEL) && knobTurn(frame.payload))
                host.nativeKnobApplied();
        }
    }
    private boolean knobTurn(byte[] scan) {
        for (int at = 12; at <= 18; at += 2) if (Wire6.u14(scan, at) != 8192) return true;
        return false;
    }
    public void tick(long now) {
        if (phase == Phase.CLOSED) return;
        if (phase == Phase.PROBING && offered != null && now - lastProgress >= 750 &&
                !required.contains(Wire6.NATIVE_CHROME)) finishDiscovery(offered, now);
        if (phase == Phase.CLOSED) return;
        if (now - lastProgress >= 1500) {
            fail(phase == Phase.PROBING ? "No wire-6 OFFER from FM1." :
                    phase == Phase.HELLO ? "No wire-6 HELLO acceptance from FM1." :
                    "FM1 stopped acknowledging new display state.");
            return;
        }
        drainInputs();
        if (phase == Phase.CLOSED) return;
        if (now - lastSend < 34 && !ackCredit) return;
        ackCredit = false;
        if (phase == Phase.PROBING && offered != null && now - lastSend >= 250) {
            transmit(Wire6.PROBE, 1, new byte[0], now);
        } else if (phase == Phase.HELLO) {
            if (lastHello == Long.MIN_VALUE || now - lastHello >= 250) {
                if (transmit(Wire6.HELLO, 0, hello, now)) lastHello = now;
            }
        } else if ((phase == Phase.SYNCHRONIZING || phase == Phase.ACTIVE) && transfer != null) {
            if (!fastParts) sendPart(transferOffset, now);
            else if (now - lastSend >= 100) sendPart(lastPartOffset, now);
        } else if ((phase == Phase.SYNCHRONIZING || phase == Phase.ACTIVE) &&
                (nativeIdle() ? now - lastSample >= (accepted.contains(Wire6.NATIVE_PANEL) ? 34 : 100) || (nativeDelta() && inputSinceSample) ||
                        (!Arrays.equals(lastNativeView, confirmedNativeView) && now - lastState >= 100) :
                        now - lastState >= 100)) {
            if (nativeDelta() && !pendingStates.isEmpty() && !recoverFullView && now - lastState < 100)
                return;
            if (nativeIdle()) lastSample = now;
            inputSinceSample = false;
            byte[] view;
            try {
                view = host.snapshot();
                if (view != null && view.length > 0 && view[0] == NativeUi.KIND) {
                    if (!accepted.contains(Wire6.NATIVE_UI)) throw new IllegalArgumentException("native not negotiated");
                    NativeUi.validate(view, accepted.contains(Wire6.NATIVE_PANEL));
                    if (!accepted.contains(Wire6.NATIVE_CHROME)) view = NativeUi.withoutChrome(view);
                } else {
                    if (required.contains(Wire6.NATIVE_UI))
                        throw new IllegalArgumentException("native UI view required");
                    Wire6.validateView(view);
                }
                if (view[0] == 2 && !accepted.contains(Wire6.TRANSPORT)) {
                    for (int i = 34; i < 42; i++)
                        if (view[i] != 0) throw new IllegalArgumentException("transport not negotiated");
                }
                if (view[0] == 4 && !accepted.contains(Wire6.PARTS))
                    throw new IllegalArgumentException("parts not negotiated");
            }
            catch (Exception error) { fail("Invalid FM1 display snapshot."); return; }
            if (nativeIdle() && view[0] != NativeUi.KIND && now - lastState < 100) return;
            if (!recoverFullView && nativeIdle() && view[0] == NativeUi.KIND &&
                    Arrays.equals(view, confirmedNativeView) && pendingStates.isEmpty()) {
                if (phase == Phase.ACTIVE && now - Math.max(lastState, lastHeartbeat) >= 408) {
                    int next = nextHeartbeatSerial;
                    do { next = (next + 1) & 16383; }
                    while (next == revision || pendingStates.containsKey(next));
                    if (transmit(Wire6.HEARTBEAT, next, new byte[0], now)) {
                        nextHeartbeatSerial = next; heartbeatSerial = next; lastHeartbeat = now;
                    }
                }
                return;
            }
            if (!recoverFullView && nativeIdle() && view[0] == NativeUi.KIND &&
                    Arrays.equals(view, lastNativeView) && now - lastState < 100) return;
            if (nativeIdle()) heartbeatSerial = -1;
            // One wrap spans over nine minutes at this cadence; pending revisions are bounded by lease.
            revision = (revision + 1) & 16383;
            if (pendingStates.containsKey(revision)) { fail("FM1 state serial exhausted."); return; }
            if (view[0] == NativeUi.KIND) {
                byte[] delta = nativeDelta() && !recoverFullView && pendingStates.isEmpty() ?
                        Wire6.nativeDelta(confirmedRevision, confirmedNativeView, view) : null;
                if (delta != null) {
                    if (transmit(Wire6.VIEW_DELTA, revision, delta, now)) {
                        lastNativeView = view.clone();
                        pendingStates.put(revision, new SentState(++sentSequence, Wire6.u28(view, 2),
                                Wire6.VIEW_DELTA, lastNativeView));
                        lastState = now;
                    }
                    return;
                }
                recoverFullView = false;
                transfer = view.clone(); transferOffset = 0;
                sendPart(0, now);
            } else if (transmit(Wire6.STATE, revision, view, now)) {
                sentSequence++;
                pendingStates.put(revision, new SentState(sentSequence, Wire6.u28(view, 2), Wire6.STATE, null));
                lastState = now;
            }
        }
    }
    public void close() {
        if (phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        inputs.clear(); pendingStates.clear(); accepted.clear();transfer=null;transferOffset=0;
        lastNativeView=confirmedNativeView=null; heartbeatSerial=-1;
        fastParts = false; sendingPartEnd = deferredReadyOffset = -1;
        diagnosticSerial=-1; diagnosticOp=queuedDiagnosticOp=0; diagnosticStarted=false;
        if (owned) {
            owned = false;
            try { host.send(Wire6.frame(Wire6.BYE, session, revision, new byte[0])); }
            catch (Exception ignored) { /* firmware lease also expires */ }
        }
        host.reset();
    }
    private void fail(String reason) { close(); host.stopped(reason); }
    private void sendPart(int offset, long now) {
        int count = Math.min(152, transfer.length - offset);
        byte[] part = new byte[4 + count];
        Wire6.put14(part, 0, transfer.length);
        Wire6.put14(part, 2, offset);
        System.arraycopy(transfer, offset, part, 4, count);
        sendingPartEnd = offset + count;
        try {
            if (!transmit(Wire6.VIEW_PART, revision, part, now)) return;
        } finally { sendingPartEnd = -1; }
        lastPartOffset = offset;
        transferOffset = offset + count;
        if (transferOffset == transfer.length) {
            sentSequence++;
            if (nativeDelta() || !nativeIdle() || !Arrays.equals(transfer, confirmedNativeView) || !pendingStates.isEmpty())
                pendingStates.put(revision, new SentState(sentSequence, Wire6.u28(transfer, 2),
                        Wire6.VIEW_PART, nativeIdle() ? transfer : null));
            if (nativeIdle()) lastNativeView = transfer;
            lastState = now;
            transfer = null; transferOffset = 0;
        }
        if (transfer != null && deferredReadyOffset == transferOffset) {
            deferredReadyOffset = -1;
            sendPart(transferOffset, now);
        }
    }
    private boolean nativeIdle() {
        return accepted.contains(Wire6.NATIVE_UI) && accepted.contains(Wire6.NATIVE_IDLE_KEEPALIVE);
    }
    /** One outstanding diagnostic request. A timed-out capture is retired by closing the session. */
    public boolean requestDiagnostics(int op, long now) {
        if (!diagnosticProfile || phase != Phase.ACTIVE || !accepted.contains(Wire6.NATIVE_PANEL) ||
                op < 1 || op > 3 || diagnosticOp != 0 ||
                (op == 1 ? diagnosticStartAttempted : !diagnosticStarted)) return false;
        if (op == 1) diagnosticStartAttempted = true;
        diagnosticOp = queuedDiagnosticOp = op;
        return true;
    }
    /** Called after the normal display tick so counter sampling cannot delay a view. */
    public void flushDiagnostics(long now) {
        if (phase != Phase.ACTIVE) return;
        if (queuedDiagnosticOp == 0) {
            if (diagnosticOp != 2 && diagnosticOp != 3 || diagnosticSerial < 0 ||
                    now - diagnosticSentAt < 250 || now - lastSend < 34) return;
            if (transmitDiagnostic(diagnosticSerial, new byte[]{(byte)diagnosticOp})) diagnosticSentAt = now;
            return;
        }
        int next = nextDiagnosticSerial;
        do { next = (next + 1) & 16383; }
        while (next == revision || next == heartbeatSerial || pendingStates.containsKey(next));
        int op = queuedDiagnosticOp;
        queuedDiagnosticOp = 0;
        if (!transmitDiagnostic(next, new byte[]{(byte)op})) return;
        nextDiagnosticSerial = next;
        diagnosticSerial = next;
        diagnosticSentAt = now;
    }
    private boolean transmitDiagnostic(int serial, byte[] payload) {
        if (phase == Phase.CLOSED) return false;
        try { host.send(Wire6.frame(Wire6.DIAGNOSTICS, session, serial, payload)); ackCredit = false; return true; }
        catch (Exception error) { fail("FM1 USB MIDI send failed: " + error.getClass().getSimpleName()); return false; }
    }
    private boolean nativeDelta() {
        return accepted.contains(Wire6.NATIVE_DELTA);
    }
    private boolean transmit(int command, int serial, byte[] payload, long now) {
        if (phase == Phase.CLOSED) return false;
        try { host.send(Wire6.frame(command, session, serial, payload)); lastSend = now; ackCredit = false; return true; }
        catch (Exception error) { fail("FM1 USB MIDI send failed: " + error.getClass().getSimpleName()); return false; }
    }
    private void finishDiscovery(byte[] offer, long now) {
        Map<Integer, Wire6.Capability> available = new LinkedHashMap<>();
        for (int i = 6; i < offer.length; i += 4) {
            Wire6.Capability selected = requested.get((int)offer[i]);
            if (selected != null && selected.major == offer[i + 1] && selected.minor == offer[i + 2])
                available.put(selected.id, selected);
        }
        if (offer[0] != 1 || (offer[2] & (1 << audioMode)) == 0 ||
                Wire6.u14(offer, 3) < 138 || !available.keySet().containsAll(required)) {
            fail("FM1 does not offer the requested wire-6 profile or audio route."); return;
        }
        offered = offer.clone();
        hello = Wire6.hello(namespace, audioMode, available.values().toArray(new Wire6.Capability[0]));
        if (offered[1] >= 1 && available.containsKey(Wire6.NATIVE_UI)) hello[1] = 1;
        phase = Phase.HELLO; lastProgress = now;
    }
    private boolean checkAccepted(byte[] ack) {
        Set<Integer> helloIds = new HashSet<>();
        for (int i = 5 + hello[3]; i < hello.length; i += 4) helloIds.add((int)hello[i]);
        Set<Integer> selected = new HashSet<>();
        for (int i = 3; i < ack.length; i += 4) {
            int id = ack[i] & 127;
            Wire6.Capability asked = requested.get(id);
            if (asked == null || !helloIds.contains(id) || ack[i + 1] != asked.major ||
                    ack[i + 2] != asked.minor || ack[i + 3] != 0) return false;
            selected.add(id);
        }
        if (!selected.containsAll(required) || selected.contains(Wire6.NATIVE_CHROME) && !selected.contains(Wire6.NATIVE_PANEL) ||
                selected.contains(Wire6.NATIVE_PANEL) && !selected.contains(Wire6.NATIVE_UI) || (selected.contains(Wire6.NATIVE_DELTA) &&
                !(selected.contains(Wire6.NATIVE_UI) && selected.contains(Wire6.NATIVE_IDLE_KEEPALIVE)))) return false;
        accepted.addAll(selected);
        return true;
    }
}
