import org.fm1.companion.protocol.NativeUi;
import org.fm1.companion.protocol.OpenSession;
import org.fm1.companion.protocol.Wire6;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Set;

/** Stock C runtime and production Java OpenSession across a framed USB mailbox. */
public final class PairedSessionCheck {
    private static final int SESSION = 317;
    private static final int[] CAPS = {Wire6.INSTRUMENT, Wire6.NATIVE_UI,
            Wire6.NATIVE_IDLE_KEEPALIVE, Wire6.NATIVE_DELTA, Wire6.NATIVE_PANEL, Wire6.PANEL_MIDI};
    private static final class Peer implements AutoCloseable {
        final Process process;
        final DataInputStream from;
        final DataOutputStream to;
        int legacy, phase, revision, audio, partsReady;
        int droppedCredits, drawnContext, pixelHash, homeLabelHash;
        byte[] committed;
        boolean readDisplay;
        private final boolean dropFirstCredit;
        Peer(String executable, boolean dropFirstCredit) throws Exception {
            this.dropFirstCredit = dropFirstCredit;
            process = new ProcessBuilder(executable, "master").redirectError(ProcessBuilder.Redirect.INHERIT).start();
            to = new DataOutputStream(process.getOutputStream());
            from = new DataInputStream(process.getInputStream());
        }
        void step(int now, int keys, int buttons, int selectTurn,
                  ArrayDeque<byte[]> frames, OpenSession session) throws Exception {
            to.writeInt(now); to.writeInt(readDisplay ? 3 : 1); to.writeInt(keys); to.writeInt(buttons); to.writeInt(selectTurn);
            to.writeInt(frames.size());
            while (!frames.isEmpty()) { byte[] f = frames.remove(); to.writeInt(f.length); to.write(f); }
            to.flush();
            legacy = from.readInt(); phase = from.readInt(); revision = from.readInt(); audio = from.readInt();
            int count = from.readInt(); ok(count >= 0 && count <= 192, "bounded controller reply");
            byte[] bytes = new byte[count]; from.readFully(bytes);
            if (readDisplay) {
                drawnContext = from.readInt();
                int length = from.readInt();
                ok(length >= 0 && length <= NativeUi.MAX_LENGTH, "bounded display readback");
                committed = new byte[length];
                from.readFully(committed);
                pixelHash = from.readInt();
                homeLabelHash = from.readInt();
            }
            Wire6.Decoder decoder = new Wire6.Decoder(frame -> {
                if (frame.command == Wire6.PART_READY) {
                    partsReady++;
                    if (dropFirstCredit && droppedCredits == 0) {
                        droppedCredits++;
                        return;
                    }
                }
                session.receive(frame, now);
            });
            for (int i = 0; i < count; i += 3) decoder.feed(bytes, i, Math.min(3, count - i));
        }
        @Override public void close() throws Exception {
            to.close(); ok(process.waitFor() == 0, "controller peer exits cleanly"); from.close();
        }
    }
    private final Peer controller;
    private final Process nativeProcess;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ArrayDeque<byte[]> usb = new ArrayDeque<>();
    private final OpenSession link;
    private byte[] shown;
    private byte[] lastPublished;
    private int accepted, ready, gain, notes, lastContext, scans, partsSent;
    private boolean firstScan = true;
    private final boolean keyTiming;
    private final boolean immediateMailbox;
    private int lastDiagnosticOp, elapsed, diagnosticLength;

    private static void ok(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private PairedSessionCheck(String executable, String peerExecutable, boolean keyTiming) throws Exception {
        this(executable, peerExecutable, keyTiming, true, false);
    }
    private PairedSessionCheck(String executable, String peerExecutable, boolean keyTiming,
                               boolean immediateMailbox, boolean dropFirstCredit) throws Exception {
        this(executable, peerExecutable, keyTiming, immediateMailbox, dropFirstCredit, false);
    }
    private PairedSessionCheck(String executable, String peerExecutable, boolean keyTiming,
                               boolean immediateMailbox, boolean dropFirstCredit, boolean chrome) throws Exception {
        this.keyTiming = keyTiming;
        this.immediateMailbox = immediateMailbox;
        nativeProcess = new ProcessBuilder(executable).inheritIO().redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE).start();
        in = new DataInputStream(nativeProcess.getInputStream());
        out = new DataOutputStream(nativeProcess.getOutputStream());
        controller = new Peer(peerExecutable, dropFirstCredit);
        java.util.ArrayList<Wire6.Capability> caps = new java.util.ArrayList<>();
        for (int id : CAPS) caps.add(new Wire6.Capability(id, 1, 0,
                id != Wire6.NATIVE_IDLE_KEEPALIVE && id != Wire6.NATIVE_DELTA));
        if (chrome) caps.add(new Wire6.Capability(Wire6.NATIVE_CHROME, 1, 0, false));
        link = new OpenSession(new OpenSession.Host() {
            @Override public void send(byte[] bytes) {
                if (bytes[5] == Wire6.VIEW_PART) partsSent++;
                usb.add(bytes);
            }
            @Override public byte[] snapshot() { return lastPublished = snapshotNative(); }
            @Override public boolean input(int serial, byte[] scan) {
                if (scan.length != 26) return false;
                gain = nativeGain(Wire6.u14(scan, 24));
                byte[] current = snapshotNative();
                int context = Wire6.u28(current, NativeUi.CONTEXT);
                int wireContext = Wire6.u28(scan, 20);
                if (firstScan) ok(wireContext == 0, "initial MASTER scan precedes drawn context");
                int buttons = Wire6.u14(scan, 0), keys = Wire6.u28(scan, 2) & ((1 << 27) - 1);
                boolean fresh = !firstScan && context == wireContext && (scan[5] & 64) == 0;
                notes = nativeInput(fresh ? buttons : 0, fresh ? keys : 0,
                        fresh ? Wire6.u14(scan, 6) - 8192 : 0);
                firstScan = false;
                lastContext = wireContext;
                scans++;
                return true;
            }
            @Override public void reset() { nativeGain(-1); firstScan = true; }
            @Override public void capabilitiesAccepted(Set<Integer> caps) {
                for (int id : CAPS) ok(caps.contains(id), "required panel capability " + id);
                accepted++;
            }
            @Override public void synchronizedDisplay(int route, boolean transport) {
                ok(route == Wire6.USB_RETURN && !transport, "USB audio return ownership");
                ready++;
            }
            @Override public void diagnostics(int op, int[] counters) {
                ok(counters.length == 9 && counters[0] >= elapsed,
                        "key timing counters and measurement window");
                lastDiagnosticOp = op;
                elapsed = counters[0];
                diagnosticLength = counters.length;
            }
            @Override public void stopped(String reason) { throw new AssertionError(reason); }
        }, keyTiming ? "org.fm1.skiff.keytime" : "org.fm1.skiff", Wire6.USB_RETURN,
                caps.toArray(new Wire6.Capability[0]));
    }
    private void writeInt(int x) throws Exception {
        out.writeByte(x); out.writeByte(x >> 8); out.writeByte(x >> 16); out.writeByte(x >> 24);
    }
    private int readInt() throws Exception {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte(), c = in.readUnsignedByte(), d = in.readUnsignedByte();
        return a | b << 8 | c << 16 | d << 24;
    }
    private byte[] snapshotNative() {
        try {
            out.writeByte('S'); out.flush();
            byte[] view = in.readNBytes(readInt());
            NativeUi.validate(view);
            return view;
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private int nativeGain(int q12) {
        try { out.writeByte('G'); writeInt(q12); out.flush(); return readInt(); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private int nativeInput(int buttons, int keys, int turn) {
        try {
            out.writeByte('I'); writeInt(buttons); writeInt(keys);
            for (int i = 0; i < 7; i++) writeInt(i == 0 ? turn : 0);
            out.flush(); return readInt();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private int render(int frames) throws Exception {
        out.writeByte('R'); writeInt(frames); out.flush(); return readInt();
    }
    private void step(int now, int keys, int buttons, int selectTurn) throws Exception {
        link.tick(now); link.flushDiagnostics(now); controller.step(now, keys, buttons, selectTurn, usb, link);
        // Android transmits a fragment queued by PART_READY immediately. Drain
        // these mailbox round trips before the next 34 ms host timer tick.
        for (int retry = 0; immediateMailbox && !usb.isEmpty(); retry++) {
            ok(retry < 32, "bounded immediate USB mailbox round trips");
            controller.step(now + retry + 1, keys, buttons, 0, usb, link);
        }
    }
    private void pump(int from, int through) throws Exception {
        for (int now = from; now <= through; now += 34) step(now, 0, 0, 0);
    }
    private static boolean section(byte[] b, int tag) {
        for (int at = NativeUi.HEADER; at + 3 <= b.length;) {
            int n = Wire6.u14(b, at + 1);
            if (b[at] == tag) return true;
            at += 3 + n;
        }
        return false;
    }
    private static int prefs(byte[] b) {
        for (int at = NativeUi.HEADER; at + 3 <= b.length;) {
            int n = Wire6.u14(b, at + 1);
            if (b[at] == NativeUi.PREFS) return b[at + 4] & 127;
            at += 3 + n;
        }
        return 0;
    }
    private void run() throws Exception {
        controller.readDisplay = true;
        link.begin(SESSION, 0);
        ok(render(512) == 0, "audio gated before first physical scan");
        controller.step(0, 0, 0, 0, usb, link);
        pump(34, 748);
        shown = snapshotNative();
        ok(ready == 1 && accepted == 1 && link.isSynchronized() && controller.phase == 2 &&
                controller.audio == 1 && controller.revision >= 1,
                "real controller acknowledged first HOME and owns USB return: ready=" + ready +
                        " accepted=" + accepted + " phase=" + controller.phase +
                        " audio=" + controller.audio + " revision=" + controller.revision +
                        " sent=" + partsSent + " credits=" + controller.partsReady);
        ok(partsSent > 1 && controller.partsReady > 0,
                "fragmented native view advanced on real controller PART_READY feedback: sent=" +
                        partsSent + " ready=" + controller.partsReady + " view=" + shown.length);
        ok(shown[NativeUi.SCREEN] == NativeUi.HOME_PAGE && section(shown, NativeUi.LEDS) &&
                (prefs(shown) & 8) != 0, "stock HOME, LEDs and MASTER ownership");
        ok(section(shown, NativeUi.CHROME) && !section(controller.committed, NativeUi.CHROME) &&
                section(controller.committed, NativeUi.ROWS) && section(controller.committed, NativeUi.LEDS),
                "unnegotiated chrome omitted without losing HOME rows or panel LEDs");
        int context = Wire6.u28(shown, NativeUi.CONTEXT);
        ok(scans > 0 && gain == 4096, "real 26-byte first MASTER scan");
        step(782, 1 << 7, 0, 0);
        ok(lastContext == context, "key scan uses drawn stock context");
        int audible = render(1024) + render(512);
        ok(audible > 0, "key press produces audible stock PCM");
        step(816, 0, 0, 0); render(512);
        ok(nativeInput(0, 0, 0) == 0, "key release reaches stock engine");
        byte[] home = shown.clone();
        step(850, 0, 1 << 4, 0); step(884, 0, 0, 0);
        pump(918, 1394);
        shown = snapshotNative();
        ok(controller.revision >= 2 && !Arrays.equals(home, shown) &&
                shown[NativeUi.SCREEN] == NativeUi.EDIT1 &&
                section(shown, NativeUi.LEDS) && (prefs(shown) & 8) != 0,
                "physical EDIT opens stock edit view with LEDs and MASTER ownership");
        step(1428, 0, 1 << 10, 0); step(1462, 0, 0, 0);
        render(512); pump(1496, 2006);
        shown = snapshotNative();
        ok(controller.revision >= 3 && (shown[32] & 1) != 0 &&
                section(shown, NativeUi.LEDS) && (prefs(shown) & 8) != 0,
                "playing view with LEDs and MASTER ownership");
        int after = 2040;
        if (keyTiming) {
            for (int op = 1; op <= 3; op++) {
                ok(link.requestDiagnostics(op, after), "diagnostic request admitted");
                pump(after, after + 170);
                ok(lastDiagnosticOp == op, "controller answered diagnostic command " + op);
                after += 204;
            }
            ok(elapsed >= 204 && elapsed <= 600, "elapsed capture time crossed the USB mailbox");
            ok(diagnosticLength == 9,
                    "exact diagnostic version reply crossed the real mailbox");
        } else {
            ok(!link.requestDiagnostics(1, after), "normal session has no diagnostic acquisition");
        }
        link.close();
        ok(nativeProcess.isAlive(), "native peer remains available until explicit quit");
        controller.step(after, 0, 0, 0, usb, link);
        ok(controller.phase == 0 && controller.audio == 1,
                "BYE restores controller standalone audio phase=" + controller.phase + " audio=" + controller.audio);
        out.writeByte('Q'); out.flush();
        ok(nativeProcess.waitFor() == 0, "native peer exit");
        controller.close();
        System.out.println("paired real controller/native HOME/edit/play, MASTER/input, USB return, key timing=" +
                keyTiming + ": OK");
    }
    private void runDelayedCreditRecovery() throws Exception {
        link.begin(SESSION, 0);
        controller.step(0, 0, 0, 0, usb, link);
        pump(34, 2992);
        ok(link.isSynchronized() && ready == 1 && controller.phase == 2 &&
                partsSent > 9 && controller.partsReady >= 9 && controller.droppedCredits == 1,
                "slow mailbox and one lost PART_READY recover the native view: sent=" +
                        partsSent + " credits=" + controller.partsReady +
                        " phase=" + controller.phase);
        link.close();
        controller.step(3026, 0, 0, 0, usb, link);
        out.writeByte('Q'); out.flush();
        ok(nativeProcess.waitFor() == 0, "native peer exit after slow mailbox");
        controller.close();
        System.out.println("paired delayed mailbox and dropped fragment credit recovery: OK");
    }
    private void runDisplayMatrix() throws Exception {
        link.begin(SESSION, 0);
        controller.readDisplay = true;
        pump(0, 680);
        ok(link.hasCapability(Wire6.NATIVE_CHROME), "display fields negotiated");
        int now = 714;
        Set<Integer> frames = new java.util.HashSet<>();
        for (int screen = 0; screen <= NativeUi.APP_MENU; screen++) {
            out.writeByte('P'); writeInt(screen); out.flush();
            byte[] expected = snapshotNative();
            boolean box = screen == NativeUi.TAKE || screen == NativeUi.UNDO;
            ok(expected[NativeUi.SCREEN] == (box ? NativeUi.HOME_PAGE : screen),
                    "stock screen source " + screen);
            if (box) ok(section(expected, NativeUi.TRANSIENT_BOX), "stock overlay " + screen);
            if (screen <= NativeUi.SCOPE) ok(section(expected, NativeUi.CHROME), "chrome on page " + screen);
            lastPublished = null;
            for (int attempt = 0; attempt < 32; attempt++) {
                step(now, 0, 0, 0); now += 34;
                if (lastPublished != null && Arrays.equals(lastPublished, controller.committed)) break;
            }
            // Some stock previews are populated progressively while snapshots are sampled.
            ok(lastPublished != null && lastPublished[NativeUi.SCREEN] == expected[NativeUi.SCREEN],
                    "page publication " + screen);
            expected = lastPublished;
            ok(Arrays.equals(expected, controller.committed), "all fields survived wire on screen " + screen +
                    " (sent " + expected.length + ", received " + controller.committed.length +
                    ", first difference " + Arrays.mismatch(expected, controller.committed) + ")");
            ok(Wire6.u28(expected, NativeUi.CONTEXT) == controller.drawnContext,
                    "firmware finished drawing screen " + screen);
            frames.add(controller.pixelHash);
            if (screen == NativeUi.HOME_PAGE) {
                out.writeByte('H'); out.flush();
                ok(readInt() == controller.homeLabelHash, "six HOME sound and engine labels match stock pixels");
                int original = controller.homeLabelHash;
                out.writeByte('+'); out.flush();
                pump(now, now + 340); now += 374;
                out.writeByte('H'); out.flush();
                ok(original != controller.homeLabelHash && readInt() == controller.homeLabelHash,
                        "changed preset reaches its HOME label on the display");
            }
        }
        ok(frames.size() > 47, "distinct painted screens across regular, modal and device views");
        link.close();
        controller.step(now, 0, 0, 0, usb, link);
        out.writeByte('Q'); out.flush();
        ok(nativeProcess.waitFor() == 0, "stock screen peer exit");
        controller.close();
        System.out.println("73 stock screen/overlay cases across the MIDI protocol; " +
                frames.size() + " distinct firmware framebuffers.");
    }
    public static void main(String[] args) throws Exception {
        new PairedSessionCheck(args[0], args[1], false, true, false, true).runDisplayMatrix();
        new PairedSessionCheck(args[0], args[1], false).run();
        new PairedSessionCheck(args[0], args[1], true).run();
        new PairedSessionCheck(args[0], args[1], false, false, true).runDelayedCreditRecovery();
        System.out.println("paired key-only acquisition/start/poll/stop: OK");
    }
}
