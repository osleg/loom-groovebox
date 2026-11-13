/* SPDX-License-Identifier: GPL-3.0-only */
import com.groovebox.cafa.*;
import java.io.*;
import java.util.*;

/** Runs the real Java session against the firmware USB-MIDI parser/mailbox.
 * Does not simulate Android drivers, USB electrical timing or actual LCD DMA. */
public final class FirmwareLinkTests {
    private static int checks;
    private static void check(boolean value, String text) {
        checks++; if (!value) throw new AssertionError(text);
    }
    private static final class Peer implements AutoCloseable {
        private final Process process;
        private final DataOutputStream out;
        private final DataInputStream in;
        final List<byte[]> pending = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final CafaSession link;
        int phase, revision, mailbox;
        Peer(String executable) throws IOException {
            process = new ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            out = new DataOutputStream(process.getOutputStream());
            in = new DataInputStream(process.getInputStream());
            link = new CafaSession(new CafaSession.Host() {
                public void send(byte[] bytes) { pending.add(bytes); }
                public byte[] snapshot() { return CafaProtocolTest.state(7,3); }
                public boolean input(int serial, byte[] data) { return data.length == 20; }
                public void reset() { }
                public void applied() { }
                public void stopped(String text) { failures.add(text); }
            });
        }
        void exchange(long now, boolean service, boolean dropReplies) throws IOException {
            out.writeInt((int) now); out.writeInt(service ? 1 : 0); out.writeInt(pending.size());
            for (byte[] bytes : pending) { out.writeInt(bytes.length); out.write(bytes); }
            pending.clear(); out.flush();
            phase = in.readInt(); revision = in.readInt(); mailbox = in.readInt();
            byte[] reply = new byte[in.readInt()]; in.readFully(reply);
            if (!dropReplies) new Protocol.Decoder(frame -> link.receive(frame, now)).feed(reply, 0, reply.length);
        }
        public void close() throws IOException { out.close(); process.destroy(); in.close(); }
    }
    private static void sustained(String executable, int serviceTicks) throws Exception {
        try (Peer peer = new Peer(executable)) {
            peer.link.begin(42, 0); peer.exchange(0, true, false);
            for (int tick = 1; tick <= 600; tick++) {
                long now = tick * 34L;
                peer.link.tick(now);
                peer.exchange(now, tick % serviceTicks == 0, false);
                check(peer.link.isOpen(), "Session closed at " + now + " ms, service every " +
                    serviceTicks * 34 + " ms: " + peer.failures);
            }
            check(peer.phase == 2 && peer.link.isApplied(), "Firmware and Android remain synchronized");
            peer.link.close(); peer.exchange(20434, true, false);
            // A pending STATE can precede BYE in the mailbox. Its lease still expires safely.
            peer.exchange(22000, true, false);
            check(peer.phase == 0, "Explicit close or lease releases hardware");
            System.out.println("PASS: 20.4 s session, firmware service every " + serviceTicks * 34 + " ms");
        }
    }
    private static void lostHandshake(String executable, boolean dropHello) throws Exception {
        try (Peer peer = new Peer(executable)) {
            peer.link.begin(43, 0);
            if (dropHello) peer.pending.clear();
            peer.exchange(0, true, true); // Alternatively, discard only the first HELLO ACK.
            for (int tick = 1; tick <= 60; tick++) {
                long now = tick * 34L;
                peer.link.tick(now); peer.exchange(now, true, false);
                check(peer.link.isOpen(), "A lost first HELLO/ACK is recoverable");
            }
            check(peer.link.isApplied() && peer.phase == 2, "Retried negotiation reaches applied state");
        }
        System.out.println("PASS: lost first " + (dropHello ? "HELLO" : "HELLO ACK") + " recovers");
    }
    private static void lostReturnPath(String executable) throws Exception {
        try (Peer peer = new Peer(executable)) {
            peer.link.begin(44, 0); peer.exchange(0, true, false);
            peer.link.tick(34); peer.exchange(34, true, false);
            check(peer.link.isApplied(), "Applied state is confirmed before return-path loss");
            for (int tick = 2; tick <= 46; tick++) {
                long now = tick * 34L;
                peer.link.tick(now); peer.exchange(now, true, true);
                if (now - 34 < 1500) check(peer.link.isOpen(), "No early timeout before lease");
            }
            check(!peer.link.isOpen() && peer.failures.size() == 1, "Return-path loss fails closed once");
            peer.exchange(3100, true, false);
            check(peer.phase == 0, "FM1 releases after return-path failure");
            peer.link.begin(45, 3200); peer.exchange(3200, true, false);
            peer.link.tick(3234); peer.exchange(3234, true, false);
            check(peer.link.isApplied() && peer.phase == 2, "Fresh session reconnects after failure");
        }
        System.out.println("PASS: return-path loss stops safely and a fresh session reconnects");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass firmware peer executable");
        sustained(args[0], 1); sustained(args[0], 3); sustained(args[0], 5);
        lostHandshake(args[0], true); lostHandshake(args[0], false); lostReturnPath(args[0]);
        System.out.println("PASS: " + checks + " real Java/C mailbox checks");
    }
}
