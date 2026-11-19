/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.protocol;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Public wire/session boundary: a fake application and real framed bytes. */
public class OpenSessionTest {
    private static final int SESSION = 317;
    private static final byte[] OFFER = {1, 0, 1, 28, 1, 1, 1, 1, 0, 0};
    private static final byte[] ACCEPT = {1, 0, 1, 1, 1, 0, 0};
    private static final byte[] INPUT = new byte[24];

    private static final class App implements OpenSession.Host {
        final List<byte[]> sent = new ArrayList<>();
        final List<Integer> scans = new ArrayList<>();
        final List<Integer> acknowledgedContexts = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int context = 7;
        int resets, ready;
        boolean closeOnAcceptance;
        Set<Integer> accepted;
        byte[] view;
        OpenSession link;
        String stopped;
        final List<Integer> diagnosticOps = new ArrayList<>();
        final List<int[]> diagnosticValues = new ArrayList<>();
        boolean fastNative;
        long clock;
        byte[] onInputView;
        @Override public void send(byte[] b) { sent.add(b); }
        @Override public byte[] snapshot() { events.add("snapshot"); return view != null ? view : Wire6.viewPrefix(2, 0, context, "Instrument", "Ready"); }
        @Override public boolean input(int serial, byte[] scan) {
            scans.add(serial);
            if (fastNative) {
                if (onInputView != null) view = onInputView;
                else Wire6.put28(view, NativeUi.CONTEXT, Wire6.u28(view, NativeUi.CONTEXT) + 1);
            }
            return true;
        }
        @Override public void nativeKnobApplied() { if (fastNative) link.tick(clock); }
        @Override public void nativeViewAcknowledged() { if (fastNative) link.tick(clock); }
        @Override public void reset() { resets++; events.add("reset"); }
        @Override public void capabilitiesAccepted(Set<Integer> capabilities) {
            accepted = capabilities;
            events.add(capabilities.contains(Wire6.TRANSPORT) ? "accepted transport" : "accepted instrument");
            if (closeOnAcceptance) link.close();
        }
        @Override public void synchronizedDisplay(int audioMode, boolean transport) { ready++; events.add("audio ready"); }
        @Override public void displayAcknowledged(int context) { acknowledgedContexts.add(context); }
        @Override public void stopped(String reason) { stopped = reason; }
        @Override public void diagnostics(int op, int[] values) {
            diagnosticOps.add(op); diagnosticValues.add(values);
        }
    }
    private static OpenSession session(App app) {
        return new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true));
    }
    private static void inbound(OpenSession s, int command, int serial, byte[] payload, long now) {
        Wire6.Decoder decoder = new Wire6.Decoder(frame -> s.receive(frame, now));
        byte[] bytes = Wire6.frame(command, SESSION, serial, payload);
        decoder.feed(bytes, 0, bytes.length);
    }
    private static void negotiate(OpenSession s) {
        s.begin(SESSION, 0);
        inbound(s, Wire6.OFFER, 0, OFFER, 10);
        s.tick(34);
        inbound(s, Wire6.ACK, 0, ACCEPT, 40);
        s.tick(68);
    }
    @Test public void negotiationRequiresExplicitAcceptedCapabilitiesAndSentStateAck() {
        App app = new App(); OpenSession s = session(app);
        s.begin(SESSION, 0);
        assertEquals(Wire6.PROBE, app.sent.get(0)[5]);
        inbound(s, Wire6.OFFER, 0, OFFER, 10);
        s.tick(34);
        assertEquals(Wire6.HELLO, app.sent.get(1)[5]);
        inbound(s, Wire6.ACK, 0, new byte[]{1, 0, 0}, 40);
        assertFalse(s.isOpen());
        assertEquals(0, app.ready);
        assertFalse(app.events.contains("accepted instrument"));
        assertNotNull(app.stopped);

        app = new App(); s = session(app); negotiate(s);
        assertEquals(Arrays.asList("reset", "accepted instrument", "snapshot"), app.events);
        assertEquals(java.util.Collections.singleton(Wire6.INSTRUMENT), app.accepted);
        Set<Integer> handedOff = app.accepted;
        assertThrows(UnsupportedOperationException.class, () -> handedOff.add(Wire6.PARTS));
        assertFalse(s.isSynchronized());
        assertFalse(s.hasCapability(Wire6.INSTRUMENT));
        inbound(s, Wire6.ACK, 3, new byte[]{3, 0}, 70); // A valid but unsent state serial.
        assertEquals(0, app.ready);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 80);
        assertTrue(s.isSynchronized());
        assertTrue(s.hasCapability(Wire6.INSTRUMENT));
        assertEquals(1, app.ready);
        assertEquals("audio ready", app.events.get(app.events.size() - 1));
    }
    @Test public void transportAcceptancePrecedesSnapshotAndCloseDuringCallbackStopsHandoff() {
        byte[] offer = {1, 0, 1, 28, 1, 2, 1, 1, 0, 0, 2, 1, 0, 0};
        byte[] accept = {1, 0, 2, 1, 1, 0, 0, 2, 1, 0, 0};
        App app = new App();
        OpenSession s = new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.TRANSPORT, 1, 0, false));
        app.link = s;
        s.begin(SESSION, 0);
        inbound(s, Wire6.OFFER, 0, offer, 10);
        s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40);
        assertEquals(Arrays.asList("reset", "accepted transport"), app.events);
        assertEquals(0, app.ready);
        s.tick(68);
        assertEquals("snapshot", app.events.get(2));
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 70);
        assertEquals(1, app.ready);
        s.close();
        assertEquals("reset", app.events.get(app.events.size() - 1));
        s.begin(SESSION, 100);
        assertEquals(1, app.ready);
        assertEquals(1, java.util.Collections.frequency(app.events, "accepted transport"));

        app = new App();
        s = new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.TRANSPORT, 1, 0, false));
        app.link = s; app.closeOnAcceptance = true;
        s.begin(SESSION, 0);
        inbound(s, Wire6.OFFER, 0, offer, 10);
        s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40);
        s.tick(68);
        assertFalse(s.isOpen());
        assertEquals(Arrays.asList("reset", "accepted transport", "reset"), app.events);
        assertEquals(0, app.ready);
        assertEquals(Wire6.BYE, app.sent.get(app.sent.size() - 1)[5]);
    }
    @Test public void optionalUnsupportedVersionIsExcludedAndForgedAcceptanceFails() {
        byte[] offerWithTransport = {1, 0, 1, 28, 1, 2, 1, 1, 0, 0, 2, 1, 0, 0};
        App app = new App();
        OpenSession s = new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.TRANSPORT, 2, 0, false));
        s.begin(SESSION, 0);
        inbound(s, Wire6.OFFER, 0, offerWithTransport, 10);
        s.tick(34);
        inbound(s, Wire6.ACK, 0, ACCEPT, 40);
        s.tick(68);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 70);
        assertTrue(s.isSynchronized());
        assertFalse(s.hasCapability(Wire6.TRANSPORT));

        app = new App();
        s = new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.TRANSPORT, 2, 0, false));
        s.begin(SESSION, 0);
        inbound(s, Wire6.OFFER, 0, offerWithTransport, 10);
        s.tick(34);
        inbound(s, Wire6.ACK, 0, new byte[]{1, 0, 2, 1, 1, 0, 0, 2, 1, 0, 0}, 40);
        assertFalse(s.isOpen());
        assertEquals(0, app.ready);
    }
    @Test public void rejectedNegotiationNeverSendsByeButOwnedCloseDoes() {
        App app = new App(); OpenSession s = session(app);
        s.begin(SESSION, 0);
        s.close();
        assertEquals(1, app.sent.size());
        assertEquals(Wire6.PROBE, app.sent.get(0)[5]);

        app = new App(); s = session(app);
        s.begin(SESSION, 0);
        inbound(s, Wire6.OFFER, 0, OFFER, 10);
        s.tick(34);
        inbound(s, Wire6.ACK, 0, new byte[]{1, 3}, 40);
        assertFalse(s.isOpen());
        assertEquals(2, app.sent.size());
        assertEquals(Wire6.HELLO, app.sent.get(1)[5]);

        app = new App(); s = session(app); negotiate(s);
        s.close();
        assertEquals(Wire6.BYE, app.sent.get(app.sent.size() - 1)[5]);
    }
    @Test public void replayedStateAckCannotExtendProgressDeadline() {
        App app = new App(); OpenSession s = session(app); negotiate(s);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 80);
        for (long t = 102; t <= 1496; t += 34) {
            s.tick(t);
            inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, t);
        }
        s.tick(1580);
        assertFalse(s.isOpen());
        assertTrue(app.stopped.contains("acknowledging"));
    }
    @Test public void stateReceiptNamesTheSentContextAndIgnoresReplayOrSupersededAck() {
        App app = new App(); OpenSession s = session(app); negotiate(s);
        app.context = 8;
        s.tick(170);
        app.context = 9; // A phone edit after sending must not rewrite the receipt's context.
        inbound(s, Wire6.ACK, 2, new byte[]{3, 0}, 175);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 176);
        inbound(s, Wire6.ACK, 2, new byte[]{3, 0}, 177);
        inbound(s, Wire6.ACK, 3, new byte[]{3, 0}, 178); // Unsent revision.
        assertEquals(Arrays.asList(8), app.acknowledgedContexts);
        s.tick(272);
        inbound(s, Wire6.ACK, 3, new byte[]{3, 0}, 280);
        assertEquals(Arrays.asList(8, 9), app.acknowledgedContexts);
    }
    @Test public void inputDuplicatesAreIgnoredAndGapOrOverflowStopsPlayback() {
        App app = new App(); OpenSession s = session(app); negotiate(s);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 69);
        inbound(s, Wire6.INPUT, 9, INPUT, 69);
        inbound(s, Wire6.INPUT, 9, INPUT, 69);
        inbound(s, Wire6.INPUT, 10, INPUT, 69);
        s.tick(70);
        assertEquals(Arrays.asList(9, 10), app.scans);
        inbound(s, Wire6.INPUT, 12, INPUT, 71);
        s.tick(72);
        assertFalse(s.isOpen());
        assertTrue(app.stopped.contains("gap"));

        app = new App(); s = session(app); negotiate(s);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 69);
        for (int i = 0; i < 33; i++) inbound(s, Wire6.INPUT, i, INPUT, 69);
        assertFalse(s.isOpen());
        assertTrue(app.stopped.contains("overflow"));
    }
    @Test public void deviceOwnedInputRequiresNegotiatedNativeUi() {
        App app = new App(); OpenSession s = session(app); negotiate(s);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 69);
        byte[] owned = INPUT.clone(); owned[5] = 64;
        inbound(s, Wire6.INPUT, 1, owned, 70);
        s.tick(71);
        assertFalse(s.isOpen());
        assertTrue(app.stopped.contains("device-owned input"));
        assertTrue(app.scans.isEmpty());
    }
    @Test public void malformedFramesAndViewsNeverReachReceiver() {
        List<Wire6.Frame> received = new ArrayList<>();
        Wire6.Decoder decoder = new Wire6.Decoder(received::add);
        byte[] valid = Wire6.frame(Wire6.INPUT, SESSION, 1, INPUT);
        byte[] oversized = new byte[168];
        oversized[0] = (byte)0xf0;
        decoder.feed(oversized, 0, oversized.length);
        byte[] corrupt = valid.clone(); corrupt[4] = 5;
        decoder.feed(corrupt, 0, corrupt.length);
        decoder.feed(valid, 0, valid.length);
        assertEquals(1, received.size());
        byte[] reserved = Wire6.viewPrefix(2, 0, 7, "Instrument", "Ready");
        reserved[34] = 4;
        assertThrows(IllegalArgumentException.class, () -> Wire6.validateView(reserved));
        assertThrows(IllegalArgumentException.class, () -> Wire6.frame(Wire6.STATE, SESSION, 1, new byte[34]));
        byte[] deviceScan = INPUT.clone(); deviceScan[5] = 64;
        assertEquals(Wire6.INPUT, Wire6.frame(Wire6.INPUT, SESSION, 1, deviceScan)[5]);
    }
    @Test public void partsMixerRequiresExactAcceptanceAndValidShape() {
        byte[] offer = {1, 0, 1, 28, 1, 3, 1, 1, 0, 0, 3, 1, 0, 0, 99, 4, 2, 0};
        byte[] accept = {1, 0, 2, 1, 1, 0, 0, 3, 1, 0, 0};
        App app = new App();
        OpenSession s = new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.PARTS, 1, 0, false));
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40);
        assertEquals(Set.of(Wire6.INSTRUMENT, Wire6.PARTS), app.accepted);
        assertFalse(app.accepted.contains(Wire6.TRANSPORT));
        byte[] mixer = mixer();
        Wire6.validateView(mixer);
        app.view = mixer;
        s.tick(68);
        assertEquals(Wire6.STATE, app.sent.get(2)[5]);
        assertEquals(4, app.sent.get(2)[10]);
        assertEquals(OpenSession.Phase.SYNCHRONIZING, s.phase());

        byte[] invalidWindow = mixer.clone(); invalidWindow[40] = 3;
        assertThrows(IllegalArgumentException.class, () -> Wire6.validateView(invalidWindow));
        byte[] invalid = mixer.clone(); invalid[57] = 1;
        byte[] unsupportedMute = invalid;
        assertThrows(IllegalArgumentException.class, () -> Wire6.validateView(unsupportedMute));
        invalid = mixer.clone(); invalid[98] = 'X';
        byte[] usedExtra = invalid;
        assertThrows(IllegalArgumentException.class, () -> Wire6.validateView(usedExtra));
        invalid = mixer.clone(); invalid[133] = 1;
        byte[] reserved = invalid;
        assertThrows(IllegalArgumentException.class, () -> Wire6.validateView(reserved));

        app = new App(); s = session(app); negotiate(s);
        app.view = mixer;
        s.tick(170);
        assertFalse(s.isOpen());
        assertTrue(app.stopped.contains("Invalid FM1 display snapshot"));
    }
    private static byte[] mixer() {
        byte[] b = Wire6.viewPrefix(4, 0, 7, "Bass", "9 / 10");
        Wire6.put14(b, 34, 8); Wire6.put14(b, 36, 10); Wire6.put14(b, 38, 8);
        b[40] = 2;
        for (int i = 0; i < 8; i++) Wire6.text(b, 42 + i * 8, 5, "");
        Wire6.text(b, 42, 5, "Bass"); Wire6.put14(b, 47, 10000); b[49] = 60;
        Wire6.text(b, 50, 5, "Lead"); Wire6.put14(b, 55, 4000); b[57] = 44;
        Wire6.text(b, 106, 12, "1.2 x"); Wire6.text(b, 118, 12, "50%");
        Wire6.put14(b, 130, 8192); b[132] = 15;
        return b;
    }
    private static byte[] nativeView(int context) {
        byte[] b = new byte[NativeUi.HEADER];
        b[0] = NativeUi.KIND;
        Wire6.put28(b, 2, context);
        b[NativeUi.SCREEN] = NativeUi.HOME_PAGE;
        b[NativeUi.FAMILY] = NativeUi.HOME;
        Wire6.put14(b, NativeUi.PAGE_COUNT, 1);
        Wire6.put14(b, NativeUi.PART_COUNT, 1);
        Wire6.put14(b, NativeUi.SELECTED_ROW, 16383);
        Wire6.text(b, NativeUi.TITLE, 24, "Home");
        Wire6.text(b, NativeUi.SUBTITLE, 24, "Connected");
        Wire6.text(b, NativeUi.FOOTER, 31, "Ready");
        for (int at = NativeUi.CONTROLS; at < NativeUi.HEADER; at += 36)
            Wire6.text(b, at, 30, "");
        NativeUi.validate(b);
        return b;
    }
    private static byte[] knobFixture(String name) throws Exception {
        try (InputStream input = OpenSessionTest.class.getResourceAsStream("knob/" + name + ".bin")) {
            assertNotNull(input);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            for (int count; (count = input.read(chunk)) >= 0;) bytes.write(chunk, 0, count);
            return bytes.toByteArray();
        }
    }
    @Test public void keyTimingCaptureRequiresPrivateNamespaceAndMatchingVersionedReply() {
        byte[] offer = {1, 0, 3, 28, 1, 3, 1, 1, 0, 0, 9, 1, 0, 0, 13, 1, 0, 0};
        byte[] accept = {1, 0, 3, 1, 1, 0, 0, 9, 1, 0, 0, 13, 1, 0, 0};
        App app = new App(); app.view = nativeView(42);
        OpenSession old = new OpenSession(app, "org.fm1.skiff.diag2", Wire6.USB_RETURN,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_PANEL, 1, 0, true));
        old.begin(SESSION, 0); inbound(old, Wire6.OFFER, 0, offer, 10); old.tick(34);
        inbound(old, Wire6.ACK, 0, accept, 40); old.tick(68); old.tick(102);
        inbound(old, Wire6.ACK, 1, new byte[]{9, 0}, 103);
        assertFalse("retired diagnostic namespace cannot acquire capture", old.requestDiagnostics(1, 104));
        old.close();

        app = new App(); app.view = nativeView(42);
        OpenSession s = new OpenSession(app, "org.fm1.skiff.keytime", Wire6.USB_RETURN,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_PANEL, 1, 0, true));
        assertFalse(s.requestDiagnostics(1, 0));
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40); s.tick(68); s.tick(102);
        inbound(s, Wire6.ACK, 1, new byte[]{9, 0}, 103);
        assertTrue(s.requestDiagnostics(1, 104));
        s.flushDiagnostics(104);
        byte[] request = app.sent.get(app.sent.size() - 1);
        int serial = Wire6.u14(request, 8);
        assertEquals(Wire6.DIAGNOSTICS, request[5]);
        assertEquals(1, request[10]);
        byte[] reply = new byte[38]; reply[0] = 1; reply[1] = 3;
        Wire6.put28(reply, 2, 123);
        inbound(s, Wire6.DIAGNOSTICS, serial + 1, reply, 105);
        reply[0] = 2;
        inbound(s, Wire6.DIAGNOSTICS, serial, reply, 106);
        assertTrue(app.diagnosticOps.isEmpty());
        byte[] wrongVersion = new byte[46]; wrongVersion[0] = 1; wrongVersion[1] = 4;
        inbound(s, Wire6.DIAGNOSTICS, serial, wrongVersion, 106);
        assertTrue(app.diagnosticOps.isEmpty());
        reply[0] = 1;
        inbound(s, Wire6.DIAGNOSTICS, serial, reply, 107);
        inbound(s, Wire6.DIAGNOSTICS, serial, reply, 108);
        assertEquals(Arrays.asList(1), app.diagnosticOps);
        assertEquals(9, app.diagnosticValues.get(0).length);
        assertEquals(123, app.diagnosticValues.get(0)[0]);
        assertFalse("START cannot be repeated", s.requestDiagnostics(1, 109));
        assertTrue(s.requestDiagnostics(2, 110));
        s.flushDiagnostics(110);
        int pollAt = app.sent.size() - 1;
        s.flushDiagnostics(359);
        assertEquals(pollAt + 1, app.sent.size());
        s.flushDiagnostics(360);
        assertArrayEquals("a lost poll retries the same serial and operation",
                app.sent.get(pollAt), app.sent.get(pollAt + 1));
        byte[] poll = new byte[38]; poll[0] = 2; poll[1] = 3;
        inbound(s, Wire6.DIAGNOSTICS, Wire6.u14(app.sent.get(pollAt), 8), poll, 361);
        assertTrue(s.requestDiagnostics(3, 362));
        s.flushDiagnostics(362);
        int stopAt = app.sent.size() - 1;
        s.flushDiagnostics(612);
        assertArrayEquals("STOP is idempotent under retry", app.sent.get(stopAt), app.sent.get(stopAt + 1));
        byte[] stop = new byte[38]; stop[0] = 3; stop[1] = 3;
        inbound(s, Wire6.DIAGNOSTICS, Wire6.u14(app.sent.get(stopAt), 8), stop, 613);
        assertEquals(Arrays.asList(1, 2, 3), app.diagnosticOps);
    }
    @Test public void extendedKeyTimingAcceptsOnlyItsOwnShapeAndSerial() {
        byte[] offer = {1, 0, 3, 28, 1, 3, 1, 1, 0, 0, 9, 1, 0, 0, 13, 1, 0, 0};
        byte[] accept = {1, 0, 3, 1, 1, 0, 0, 9, 1, 0, 0, 13, 1, 0, 0};
        App app = new App(); app.view = nativeView(42);
        OpenSession s = new OpenSession(app, "org.fm1.skiff.keytime2", Wire6.USB_RETURN,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_PANEL, 1, 0, true));
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40); s.tick(68); s.tick(102);
        inbound(s, Wire6.ACK, 1, new byte[]{9, 0}, 103);
        assertTrue(s.requestDiagnostics(1, 104)); s.flushDiagnostics(104);
        int serial = Wire6.u14(app.sent.get(app.sent.size() - 1), 8);
        byte[] legacy = new byte[38]; legacy[0] = 1; legacy[1] = 3;
        inbound(s, Wire6.DIAGNOSTICS, serial, legacy, 105);
        byte[] extended = new byte[46]; extended[0] = 1; extended[1] = 4;
        extended[1] = 3;
        try { Wire6.frame(Wire6.DIAGNOSTICS, SESSION, serial, extended); fail("wrong shape accepted"); }
        catch (IllegalArgumentException expected) { }
        extended[1] = 4;
        Wire6.put28(extended, 2 + 9 * 4, 1234);
        Wire6.put28(extended, 2 + 10 * 4, 5678);
        inbound(s, Wire6.DIAGNOSTICS, serial + 1, extended, 106);
        assertTrue(app.diagnosticOps.isEmpty());
        inbound(s, Wire6.DIAGNOSTICS, serial, extended, 107);
        assertEquals(Arrays.asList(1), app.diagnosticOps);
        assertEquals(11, app.diagnosticValues.get(0).length);
        assertEquals(1234, app.diagnosticValues.get(0)[9]);
        assertEquals(5678, app.diagnosticValues.get(0)[10]);
    }
    @Test public void nativeViewPublishesOnlyAfterFinalPartAckAndServicesInputBetweenParts() {
        byte[] offer = {1, 0, 1, 28, 1, 2, 1, 1, 0, 0, 9, 1, 0, 0};
        byte[] accept = {1, 0, 2, 1, 1, 0, 0, 9, 1, 0, 0};
        App app = new App(); app.view = nativeView(42);
        OpenSession s = new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true));
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40);
        s.tick(68);
        assertEquals(Wire6.VIEW_PART, app.sent.get(2)[5]);
        assertEquals(0, Wire6.u14(app.sent.get(2), 12));
        assertEquals(0, app.ready);
        inbound(s, Wire6.PART_READY, 1, new byte[]{24, 1}, 69);
        assertEquals("core 1.0 cannot grant early part credit", 3, app.sent.size());
        s.tick(102);
        assertEquals(Wire6.VIEW_PART, app.sent.get(3)[5]);
        assertEquals(152, Wire6.u14(app.sent.get(3), 12));
        assertEquals(0, app.ready);
        inbound(s, Wire6.ACK, 1, new byte[]{3, 0}, 103);
        assertEquals(0, app.ready);
        inbound(s, Wire6.ACK, 1, new byte[]{9, 0}, 105);
        assertEquals(1, app.ready);
        assertEquals(Arrays.asList(42), app.acknowledgedContexts);
        byte[] owned = INPUT.clone(); owned[5] = 64;
        inbound(s, Wire6.INPUT, 1, owned, 106);
        s.tick(107);
        assertEquals(Arrays.asList(1), app.scans);
    }
    @Test public void acceptedNativePanelKeyReachesApplicationDuringAnUnfinishedView() {
        byte[] offer = {1, 0, 3, 28, 1, 3, 1, 1, 0, 0, 9, 1, 0, 0, 13, 1, 0, 0};
        byte[] accept = {1, 0, 3, 1, 1, 0, 0, 9, 1, 0, 0, 13, 1, 0, 0};
        App app = new App(); app.view = nativeView(42);
        OpenSession s = new OpenSession(app, "test.native", Wire6.USB_RETURN,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_PANEL, 1, 0, true));
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40); s.tick(68); s.tick(102);
        inbound(s, Wire6.ACK, 1, new byte[]{9, 0}, 103);
        app.view = nativeView(43);
        s.tick(204); // First part only; display still names context 42.
        int sent = app.sent.size();
        byte[] key = new byte[26];
        Wire6.put28(key, 2, 1);
        Wire6.put28(key, 20, 42);
        for (int at = 6; at <= 18; at += 2) Wire6.put14(key, at, 8192);
        inbound(s, Wire6.INPUT, 1, key, 205);
        assertEquals(Arrays.asList(1), app.scans);
        assertEquals("key input must not wait for the display part timer", sent, app.sent.size());
        assertTrue(s.isSynchronized());
    }
    @Test public void acceptedKnobPublishesAtReceiveAndQueuedKnobUsesAcknowledgedMailboxSlot() {
        byte[] offer = {1, 0, 1, 28, 1, 5, 1, 1, 0, 0, 9, 1, 0, 0,
                11, 1, 0, 0, 12, 1, 0, 0, 13, 1, 0, 0};
        byte[] accept = {1, 0, 5, 1, 1, 0, 0, 9, 1, 0, 0,
                11, 1, 0, 0, 12, 1, 0, 0, 13, 1, 0, 0};
        App app = new App(); app.view = nativeView(42); app.fastNative = true;
        OpenSession s = new OpenSession(app, "test.native", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_IDLE_KEEPALIVE, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_DELTA, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_PANEL, 1, 0, true));
        app.link = s;
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40); s.tick(68); s.tick(102);
        inbound(s, Wire6.ACK, 1, new byte[]{9, 0}, 103);
        assertTrue(s.isSynchronized());
        s.tick(500);
        int idleCount = app.sent.size();
        byte[] scan = new byte[26];
        for (int at = 6; at <= 18; at += 2) Wire6.put14(scan, at, 8192);
        Wire6.put14(scan, 12, 8193);
        app.clock = 501;
        inbound(s, Wire6.INPUT, 1, scan, 501);
        assertEquals(idleCount + 1, app.sent.size());
        assertEquals(Wire6.VIEW_DELTA, app.sent.get(idleCount)[5]);
        int firstRevision = Wire6.u14(app.sent.get(idleCount), 8);
        app.clock = 505;
        inbound(s, Wire6.INPUT, 2, scan, 505);
        assertEquals("unacknowledged view retains the single mailbox slot", idleCount + 1, app.sent.size());
        app.clock = 510;
        inbound(s, Wire6.ACK, firstRevision, new byte[]{10, 0}, 510);
        assertEquals(idleCount + 2, app.sent.size());
        assertEquals(Wire6.VIEW_DELTA, app.sent.get(idleCount + 1)[5]);
        assertEquals(Arrays.asList(1, 2), app.scans);
    }
    @Test public void minorOnePartFeedbackAcceleratesRealEditCurveWithoutPublishingEarly() throws Exception {
        byte[] before = knobFixture("before"), after = knobFixture("after");
        assertEquals(590, before.length);
        assertEquals(590, after.length);
        NativeUi.validate(before); NativeUi.validate(after);
        assertNull("the actual knob curve exceeds one delta frame", Wire6.nativeDelta(1, before, after));
        byte[] offer = {1, 1, 1, 28, 1, 5, 1, 1, 0, 0, 9, 1, 0, 0,
                11, 1, 0, 0, 12, 1, 0, 0, 13, 1, 0, 0};
        byte[] accept = {1, 0, 5, 1, 1, 0, 0, 9, 1, 0, 0,
                11, 1, 0, 0, 12, 1, 0, 0, 13, 1, 0, 0};
        App app = new App(); app.view = before; app.onInputView = after; app.fastNative = true;
        OpenSession s = new OpenSession(app, "test.native", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_IDLE_KEEPALIVE, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_DELTA, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_PANEL, 1, 0, true));
        app.link = s;
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        assertEquals(1, app.sent.get(1)[11]); // HELLO core minor is opt-in only after OFFER 1.1.
        inbound(s, Wire6.ACK, 0, accept, 40);
        s.tick(68);
        int firstPart = app.sent.size() - 1;
        s.tick(102);
        assertEquals("a delayed credit leaves the one receive slot alone", firstPart + 1, app.sent.size());
        s.tick(170);
        assertArrayEquals("lost credit retries the same bytes", app.sent.get(firstPart), app.sent.get(firstPart + 1));
        for (int i = 1; i <= 3; i++) {
            byte[] ready = new byte[2]; Wire6.put14(ready, 0, i * 152);
            inbound(s, Wire6.PART_READY, 1, ready, 170 + i);
        }
        inbound(s, Wire6.ACK, 1, new byte[]{9, 0}, 174);
        assertTrue(s.isSynchronized());
        s.tick(500);
        int baseline = app.sent.size();
        byte[] scan = new byte[26];
        for (int at = 6; at <= 18; at += 2) Wire6.put14(scan, at, 8192);
        Wire6.put14(scan, 12, 8193);
        app.clock = 501;
        inbound(s, Wire6.INPUT, 1, scan, 501);
        assertEquals(Wire6.VIEW_PART, app.sent.get(baseline)[5]);
        int changedRevision = Wire6.u14(app.sent.get(baseline), 8);
        assertEquals(0, Wire6.u14(app.sent.get(baseline), 12));
        assertEquals(1, app.ready);
        inbound(s, Wire6.PART_READY, changedRevision, new byte[]{25, 1}, 502);
        assertEquals(baseline + 1, app.sent.size());
        for (int i = 1; i <= 3; i++) {
            byte[] ready = new byte[2]; Wire6.put14(ready, 0, i * 152);
            inbound(s, Wire6.PART_READY, changedRevision, ready, 501 + i);
            assertEquals(baseline + 1 + i, app.sent.size());
            assertEquals(i * 152, Wire6.u14(app.sent.get(baseline + i), 12));
            inbound(s, Wire6.PART_READY, changedRevision, ready, 501 + i);
            assertEquals("duplicate feedback cannot send another part", baseline + 1 + i, app.sent.size());
        }
        inbound(s, Wire6.ACK, changedRevision, new byte[]{9, 0}, 505);
        assertEquals(Arrays.asList(Wire6.u28(before, 2), Wire6.u28(after, 2)), app.acknowledgedContexts);
    }
    @Test public void negotiatedNativeIdleUsesHeartbeatAndAcceptsDelayedViewReceiptDuringRetry() {
        byte[] offer = {1, 0, 1, 28, 1, 3, 1, 1, 0, 0, 9, 1, 0, 0, 11, 1, 0, 0};
        byte[] accept = {1, 0, 3, 1, 1, 0, 0, 9, 1, 0, 0, 11, 1, 0, 0};
        App app = new App(); app.view = nativeView(42);
        OpenSession s = new OpenSession(app, "test.app", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_IDLE_KEEPALIVE, 1, 0, false));
        s.begin(SESSION, 0); inbound(s, Wire6.OFFER, 0, offer, 10); s.tick(34);
        inbound(s, Wire6.ACK, 0, accept, 40);
        s.tick(68); s.tick(102);
        inbound(s, Wire6.ACK, 1, new byte[]{9, 0}, 103);
        assertEquals(1, app.ready);
        int afterView = app.sent.size();
        for (long t = 136; t <= 476; t += 34) s.tick(t);
        assertEquals("confirmed static native view stays quiet", afterView, app.sent.size());
        s.tick(578);
        byte[] heartbeat = app.sent.get(app.sent.size() - 1);
        assertEquals(Wire6.HEARTBEAT, heartbeat[5]);
        int heartbeatSerial = Wire6.u14(heartbeat, 8);
        assertNotEquals(1, heartbeatSerial);
        assertEquals(1, app.ready);
        assertEquals(Arrays.asList(42), app.acknowledgedContexts);

        app.view = nativeView(43);
        s.tick(612); s.tick(646); s.tick(680);
        assertEquals(Wire6.VIEW_PART, app.sent.get(app.sent.size() - 1)[5]);
        int changedSerial = Wire6.u14(app.sent.get(app.sent.size() - 1), 8);
        inbound(s, Wire6.ACK, heartbeatSerial, new byte[]{4, 0}, 681);
        s.tick(714);
        inbound(s, Wire6.ACK, heartbeatSerial, new byte[]{4, 0}, 715);
        assertEquals(Arrays.asList(42), app.acknowledgedContexts);
        s.tick(748); s.tick(782);
        s.tick(816); // The final VIEW_PART ACK was delayed. Start a complete recovery view.
        assertEquals(Wire6.VIEW_PART, app.sent.get(app.sent.size() - 1)[5]);
        assertEquals(0, Wire6.u14(app.sent.get(app.sent.size() - 1), 12));
        int recoverySerial = Wire6.u14(app.sent.get(app.sent.size() - 1), 8);
        assertNotEquals(changedSerial, recoverySerial);
        inbound(s, Wire6.ACK, changedSerial, new byte[]{9, 0}, 817);
        s.tick(850);
        inbound(s, Wire6.ACK, recoverySerial, new byte[]{9, 0}, 851);
        assertEquals(Arrays.asList(42, 43), app.acknowledgedContexts);
        assertTrue(s.isSynchronized());
        app.view = nativeView(44);
        s.tick(884); s.tick(918); s.tick(952);
        int middleSerial = Wire6.u14(app.sent.get(app.sent.size() - 1), 8);
        app.view = nativeView(43);
        s.tick(986); s.tick(1020); s.tick(1054);
        int revertSerial = Wire6.u14(app.sent.get(app.sent.size() - 1), 8);
        assertNotEquals(middleSerial, revertSerial);
        inbound(s, Wire6.ACK, middleSerial, new byte[]{9, 0}, 1055);
        s.tick(1088); s.tick(1122); s.tick(1156);
        byte[] revertRetry = app.sent.get(app.sent.size() - 1);
        assertEquals("delayed middle receipt cannot skip the revert", Wire6.VIEW_PART, revertRetry[5]);
        assertEquals(0, Wire6.u14(revertRetry, 12));
        s.tick(1190);
        inbound(s, Wire6.ACK, Wire6.u14(revertRetry, 8), new byte[]{9, 0}, 1191);
        assertEquals(Arrays.asList(42, 43, 44, 43), app.acknowledgedContexts);
        s.tick(1598);
        byte[] fresh = app.sent.get(app.sent.size() - 1);
        assertEquals(Wire6.HEARTBEAT, fresh[5]);
        int freshSerial = Wire6.u14(fresh, 8);
        inbound(s, Wire6.ACK, freshSerial, new byte[]{4, 0}, 1599);
        inbound(s, Wire6.ACK, freshSerial, new byte[]{4, 0}, 1600);
        assertEquals(1, app.ready);
        assertEquals(Arrays.asList(42, 43, 44, 43), app.acknowledgedContexts);
        for (long t = 1632; t < 3099 && s.isOpen(); t += 34) {
            s.tick(t);
            inbound(s, Wire6.ACK, freshSerial, new byte[]{4, 0}, t);
        }
        s.tick(3100);
        assertFalse("replayed heartbeat cannot renew the lease", s.isOpen());
    }
    @Test public void nativeOperatorTopologyRejectsDuplicateSeriesAndOtherFamilies() {
        byte[] view = Arrays.copyOf(nativeView(88), 256 + 3 + 10 + 3 + 6);
        view[NativeUi.SCREEN] = NativeUi.OPERATOR;
        view[NativeUi.FAMILY] = NativeUi.EDIT;
        view[NativeUi.VARIANT] = NativeUi.FM_OPERATOR;
        view[256] = 8; Wire6.put14(view, 257, 10);
        view[259] = 1; view[260] = 0; view[261] = 3; view[262] = 1;
        Wire6.put14(view, 263, 4000); Wire6.put14(view, 265, 8000);
        Wire6.put14(view, 267, 9000);
        view[269] = 9; Wire6.put14(view, 270, 6);
        view[272] = 1; view[273] = 0; view[274] = 1; view[275] = 1;
        Wire6.put14(view, 276, 9000);
        NativeUi.validate(view);
        byte[] wrongScreen = view.clone(); wrongScreen[NativeUi.SCREEN] = NativeUi.HOME_PAGE;
        wrongScreen[NativeUi.FAMILY] = NativeUi.HOME; wrongScreen[NativeUi.VARIANT] = 0;
        assertThrows(IllegalArgumentException.class, () -> NativeUi.validate(wrongScreen));
        byte[] duplicate = Arrays.copyOf(view, view.length + 3);
        Wire6.put14(duplicate, 257, 13); duplicate[259] = 2;
        duplicate[269] = 0; duplicate[270] = 0; duplicate[271] = 0;
        duplicate[272] = 9; Wire6.put14(duplicate, 273, 6);
        System.arraycopy(view, 272, duplicate, 275, 6);
        assertThrows(IllegalArgumentException.class, () -> NativeUi.validate(duplicate));
    }
    @Test public void requiredNativeUiCannotPublishLegacyState() {
        App app = new App();
        OpenSession s = new OpenSession(app, "test.native", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true));
        s.begin(SESSION, 0);
        inbound(s, Wire6.OFFER, 0, new byte[]{1,0,1,28,1,2,1,1,0,0,9,1,0,0}, 10);
        s.tick(34);
        inbound(s, Wire6.ACK, 0, new byte[]{1,0,2,1,1,0,0,9,1,0,0}, 40);
        s.tick(68);
        assertFalse("Required native UI must fail closed instead of publishing generic STATE", s.isOpen());
        assertEquals(0, app.ready);
        for (byte[] sent : app.sent) assertNotEquals(Wire6.STATE, sent[5]);
    }
    @Test public void missingOptionalDiscoveryPageStillOpensTheBaseProfile() {
        App app = new App();
        OpenSession s = new OpenSession(app, "test.discovery", Wire6.PHONE,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_CHROME, 1, 0, false));
        s.begin(SESSION, 0);
        byte[] base = OFFER.clone();
        base[1] = 2;
        inbound(s, Wire6.OFFER, 0, base, 10);
        assertEquals(1, Wire6.u14(app.sent.get(1), 8));
        s.tick(280);
        assertEquals(Wire6.PROBE, app.sent.get(2)[5]);
        s.tick(800);
        byte[] hello = app.sent.get(app.sent.size() - 1);
        assertEquals(Wire6.HELLO, hello[5]);
        assertEquals(1, hello[14 + "test.discovery".length()]);
        inbound(s, Wire6.ACK, 0, ACCEPT, 801);
        s.tick(835);
        inbound(s, Wire6.ACK, 1, new byte[]{Wire6.STATE, 0}, 840);
        assertTrue(s.isSynchronized());
        assertFalse(s.hasCapability(Wire6.NATIVE_CHROME));
    }
}
