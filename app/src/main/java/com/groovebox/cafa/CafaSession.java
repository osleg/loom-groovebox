/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa;

/** Bounded CAFA v3 lease. Port-open never implies firmware takeover or audible output. */
public final class CafaSession {
    public interface Host {
        void send(byte[] bytes) throws Exception;
        byte[] snapshot();
        boolean input(int serial, byte[] payload);
        void reset();
        void applied();
        void stopped(String reason);
    }
    private final Host host;
    private int session, revision;
    private long sentStates, acknowledgedState, lastAck, lastFrame;
    private boolean waiting, open, applied;
    public CafaSession(Host host) { this.host = host; }
    public boolean isOpen() { return open; }
    public boolean isApplied() { return applied; }
    public void begin(int id, long now) {
        close(); host.reset();
        session = id; revision = 0; sentStates = acknowledgedState = 0;
        lastAck = lastFrame = now; waiting = open = true; applied = false;
        send(Protocol.HELLO, 0, new byte[0]);
    }
    public void receive(Protocol.Frame frame, long now) {
        if (!open || frame.session != session || frame.version != Protocol.VERSION) return;
        if (frame.command == Protocol.ACK && frame.payload.length == 1 && frame.payload[0] == 1) {
            if (waiting) {
                if (frame.serial != 0) return;
                waiting = false; lastAck = now;
            } else {
                int behind = (revision - frame.serial) & 16383;
                if (behind >= 8192) return;
                long candidate = sentStates - behind;
                if (candidate <= acknowledgedState || candidate <= 0) return;
                acknowledgedState = candidate; lastAck = now;
                if (!applied) { applied = true; host.applied(); }
            }
        } else if (frame.command == Protocol.INPUT && !waiting) {
            if (!host.input(frame.serial, frame.payload)) fail("FM1 input was interrupted. Reconnect to resynchronize.");
        }
    }
    public void tick(long now) {
        if (!open) return;
        if (now - lastAck >= 1500) {
            fail(waiting ? "No CAFA v3 firmware reply. Select matching FM1 MIDI/USB audio endpoints."
                         : "FM1 stopped acknowledging state. Output muted; reconnect to continue.");
            return;
        }
        // Exactly one SysEx per tick: the firmware has one receive mailbox.
        if (waiting) {
            if (now - lastFrame >= 250) { send(Protocol.HELLO, 0, new byte[0]); lastFrame = now; }
        } else if (now - lastFrame >= 34) {
            byte[] state = host.snapshot();
            if (state.length != 92) { fail("Invalid surface snapshot; output stopped."); return; }
            sentStates++; revision = (int)(sentStates & 16383);
            send(Protocol.STATE, revision, state); lastFrame = now;
        }
        // Valid STATE frames renew the firmware lease. No competing heartbeat is needed.
    }
    public void close() {
        if (!open) return;
        open = applied = false;
        try { host.send(Protocol.frame(Protocol.BYE, session, revision, new byte[0])); }
        catch (Exception ignored) { /* firmware lease expires independently */ }
        host.reset();
    }
    private void send(int command, int serial, byte[] payload) {
        if (!open) return;
        try { host.send(Protocol.frame(command, session, serial, payload)); }
        catch (Exception error) { fail("USB MIDI send failed: " + error.getClass().getSimpleName()); }
    }
    private void fail(String reason) { close(); host.stopped(reason); }
}
