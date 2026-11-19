/* SPDX-License-Identifier: GPL-3.0-only */
import org.fm1.companion.protocol.OpenSession;
import org.fm1.companion.protocol.Wire6;
import org.fm1.companion.protocol.NativeUi;
import org.fm1.companion.legacy.Protocol;
import java.io.*;
import java.util.*;

/** Cross-language contract against production firmware USB assembly and surface hooks. */
public final class OpenProtocolLinkTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final class App implements OpenSession.Host {
        final List<byte[]> outbound = new ArrayList<>();
        final List<byte[]> scans = new ArrayList<>();
        final List<Integer> displayed = new ArrayList<>();
        int ready, resets;
        boolean transport, parts;
        String stopped;
        byte[] nativeView;
        public void send(byte[] frame) { outbound.add(frame); }
        public byte[] snapshot() {
            if (nativeView != null) return nativeView;
            if (parts) return mixer();
            if (transport) {
                byte[] b = Wire6.viewPrefix(2, 0, 0x123456, "Transport", "Real overview");
                b[34] = 3; Wire6.put14(b, 35, 1234);
                Wire6.put28(b, 37, 2500); b[41] = 1;
                return b;
            }
            byte[] b = Wire6.viewPrefix(1, 0, 0x123456, "Independent app", "Sound controls");
            Wire6.put14(b, 34, 2); Wire6.put14(b, 36, 3);
            Wire6.put14(b, 38, 1); Wire6.put14(b, 40, 2);
            for (int i = 42; i < 138; i += 24) {
                Wire6.text(b, i, 8, ""); Wire6.text(b, i + 8, 12, "");
            }
            Wire6.text(b, 42, 8, "Gain"); Wire6.text(b, 50, 12, "-6 dB");
            Wire6.put14(b, 62, 8000); b[64] = 1; b[65] = 1;
            Wire6.text(b, 90, 8, "Wave"); Wire6.text(b, 98, 12, "Sine");
            b[112] = 2; b[113] = 1;
            return b;
        }
        public boolean input(int serial, byte[] scan) { scans.add(scan); return true; }
        public void reset() { resets++; }
        public void capabilitiesAccepted(Set<Integer> accepted) {
            transport = accepted.contains(Wire6.TRANSPORT);
            parts = accepted.contains(Wire6.PARTS);
        }
        public void synchronizedDisplay(int mode, boolean acceptedTransport) { ready++; }
        public void displayAcknowledged(int context) { displayed.add(context); }
        public void stopped(String reason) { stopped = reason; }
    }
    private static byte[] mixer() {
        byte[] b = Wire6.viewPrefix(4, 0, 0x123456, "Bass", "9 / 10");
        Wire6.put14(b, 34, 8); Wire6.put14(b, 36, 10);
        Wire6.put14(b, 38, 8); b[40] = 2; b[41] = 0;
        for (int i = 0; i < 8; i++) Wire6.text(b, 42 + 8 * i, 5, "");
        Wire6.text(b, 42, 5, "Bass"); Wire6.put14(b, 47, 10000); b[49] = 60;
        Wire6.text(b, 50, 5, "Lead"); Wire6.put14(b, 55, 4000); b[57] = 44;
        Wire6.text(b, 106, 12, "1.2 x"); Wire6.text(b, 118, 12, "50%");
        Wire6.put14(b, 130, 8192); b[132] = 15;
        return b;
    }
    private static final class Peer implements AutoCloseable {
        final Process process;
        final DataOutputStream to;
        final DataInputStream from;
        int legacy, phase, revision, audio;
        Peer(String executable) throws IOException {
            process = new ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            to = new DataOutputStream(process.getOutputStream());
            from = new DataInputStream(process.getInputStream());
        }
        List<Wire6.Frame> step(int now, int keys, List<byte[]> frames, OpenSession session) throws IOException {
            return step(now, keys, 0, 0, frames, session);
        }
        List<Wire6.Frame> step(int now, int keys, int buttons, int selectTurn,
                               List<byte[]> frames, OpenSession session) throws IOException {
            to.writeInt(now); to.writeInt(1); to.writeInt(keys); to.writeInt(buttons); to.writeInt(selectTurn);
            to.writeInt(frames.size());
            for (byte[] frame : frames) { to.writeInt(frame.length); to.write(frame); }
            to.flush(); frames.clear();
            legacy = from.readInt(); phase = from.readInt(); revision = from.readInt(); audio = from.readInt();
            int count = from.readInt(); check(count <= 192, "bounded firmware reply");
            byte[] bytes = new byte[count]; from.readFully(bytes);
            List<Wire6.Frame> replies = new ArrayList<>();
            Wire6.Decoder decoder = new Wire6.Decoder(frame -> {
                replies.add(frame);
                if (session != null) session.receive(frame, now);
            });
            // Split the byte stream through MIDI packet boundaries to exercise the host decoder too.
            for (int i = 0; i < bytes.length; i += 3) decoder.feed(bytes, i, Math.min(3, bytes.length - i));
            return replies;
        }
        public void close() throws Exception {
            to.close();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            check(process.exitValue() == 0, "firmware peer exited cleanly");
            from.close();
        }
    }
    private static void lifecycle(String executable, int mode, boolean requestParts) throws Exception {
        App app = new App();
        Wire6.Capability instrument = new Wire6.Capability(1, 1, 0, true);
        // An unsupported optional version and a future capability must not activate by ID alone.
        OpenSession session = new OpenSession(app, "example.instrument", mode, instrument,
                new Wire6.Capability(2, mode == Wire6.PHONE ? 2 : 1, 0, false),
                new Wire6.Capability(3, requestParts ? 1 : 2, 0, false));
        int id = 1100 + mode + (requestParts ? 10 : 0);
        try (Peer peer = new Peer(executable)) {
            session.begin(id, 0);
            peer.step(0, 0, app.outbound, session);
            check(peer.phase == 0 && peer.audio == 0 && app.ready == 0, "discovery does not take over");
            session.tick(34);
            peer.step(34, 0, app.outbound, session);
            check(peer.phase == 1 && peer.audio == 0 && app.ready == 0, "HELLO reserves without audio");
            check(app.transport == (mode == Wire6.USB_RETURN), "capability handoff precedes STATE");
            check(app.parts == requestParts, "Parts acceptance precedes STATE");
            session.tick(68);
            peer.step(68, 0, app.outbound, session);
            check(peer.phase == 2 && peer.audio == 1 && app.ready == 1, "STATE ACK synchronizes actual firmware");
            check(app.transport == (mode == Wire6.USB_RETURN), "exact optional version negotiation");
            check(app.parts == requestParts, "exact Parts version negotiation");
            if (!requestParts) {
                List<byte[]> outsider = new ArrayList<>();
                outsider.add(Wire6.frame(Wire6.STATE, id, 2, mixer()));
                List<Wire6.Frame> denied = peer.step(76, 0, outsider, null);
                check(denied.stream().anyMatch(f -> f.command == Wire6.ACK && f.serial == 2 &&
                        f.payload[0] == Wire6.STATE && f.payload[1] == 2),
                        "firmware rejects unnegotiated mixer");
                check(peer.revision == 1, "unnegotiated mixer preserves display");
            }
            if (mode == Wire6.PHONE) {
                byte[] invalid = Wire6.viewPrefix(2, 0, 0x123456, "Transport", "Unnegotiated");
                invalid[34] = 1; Wire6.put14(invalid, 35, 1200);
                List<byte[]> outsider = new ArrayList<>();
                outsider.add(Wire6.frame(Wire6.STATE, id, 2, invalid));
                List<Wire6.Frame> denied = peer.step(85, 0, outsider, null);
                check(denied.stream().anyMatch(f -> f.command == Wire6.ACK && f.serial == 2 &&
                        f.payload[0] == Wire6.STATE && f.payload[1] == 2),
                        "firmware rejects nonzero unnegotiated transport body");
                check(peer.revision == 1 && peer.phase == 2, "invalid body does not replace accepted display");
            }
            for (int t = 102; t <= 2040; t += 34) {
                session.tick(t);
                check(app.outbound.size() <= 1, "one outbound frame per tick");
                peer.step(t, t == 136 ? 4 : 0,
                        mode == Wire6.USB_RETURN && t == 170 ? (1 << 10) | (1 << 5) : 0,
                        mode == Wire6.USB_RETURN && t == 170 ? 3 : 0,
                        app.outbound, session);
                check(peer.phase == 2 && peer.legacy == 0 && session.isSynchronized(), "static display retains progress");
            }
            boolean pressed = false, released = false;
            for (byte[] scan : app.scans) {
                if (Wire6.u28(scan, 2) == 4) {
                    pressed = true;
                    check(Wire6.u28(scan, 20) == 0x123456, "INPUT carries actual drawn 28-bit context");
                } else if (pressed && Wire6.u28(scan, 2) == 0) released = true;
            }
            check(pressed && released, "physical press and release cross real USB framing");
            if (mode == Wire6.USB_RETURN) check(app.scans.stream().anyMatch(scan ->
                    (Wire6.u14(scan, 0) & ((1 << 10) | (1 << 5))) == ((1 << 10) | (1 << 5)) &&
                    Wire6.u14(scan, 6) == 8195), "PLAY, GLO and SELECT turn cross physical scan");
            List<byte[]> outsider = new ArrayList<>();
            outsider.add(Wire6.frame(Wire6.HELLO, id + 100, 0, Wire6.hello("another.app", mode, instrument)));
            List<Wire6.Frame> busy = peer.step(2074, 0, outsider, session);
            check(busy.stream().anyMatch(f -> f.command == Wire6.ACK && f.payload[0] == Wire6.HELLO && f.payload[1] == 3),
                    "second owner receives BUSY");
            outsider.add(Protocol.frame(Protocol.HELLO, 901, 0, new byte[0]));
            peer.step(2108, 0, outsider, session);
            check(peer.phase == 2 && peer.legacy == 0, "legacy cannot steal open session");
            session.close();
            peer.step(2142, 0, app.outbound, session);
            check(peer.phase == 0 && peer.audio == 0 && app.stopped == null, "BYE restores standalone stopped");
            outsider.add(Protocol.frame(Protocol.HELLO, 901, 0, new byte[0]));
            peer.step(2176, 0, outsider, null);
            check(peer.legacy == 1 && peer.phase == 0, "legacy remains available after open session");
            outsider.add(Wire6.frame(Wire6.HELLO, id, 0, Wire6.hello("example.instrument", mode, instrument)));
            busy = peer.step(2210, 0, outsider, null);
            check(peer.legacy == 1 && peer.phase == 0 && busy.stream().anyMatch(f ->
                    f.command == Wire6.ACK && f.payload[1] == 3), "open session cannot steal legacy owner");
        }
    }
    /** Near-capacity score: 21 USB-sized parts must publish as one native frame. */
    private static byte[] nativeScore(int context) {
        byte[] b = new byte[3054];
        b[0] = NativeUi.KIND; Wire6.put28(b, 2, context);
        b[6] = NativeUi.NOTES_PAGE; b[8] = NativeUi.SEQ;
        Wire6.put14(b, 11, 1); Wire6.put14(b, 15, 8); b[19] = 16;
        Wire6.put14(b, 22, 16383); Wire6.put14(b, 24, 16383);
        Wire6.text(b, 33, 24, "Native score"); Wire6.text(b, 57, 24, "256 notes");
        Wire6.text(b, 81, 31, "SELECT: PAGE");
        for (int at = 112; at < 256; at += 36) Wire6.text(b, at, 30, "");
        b[256] = NativeUi.NOTES; Wire6.put14(b, 257, 1794); Wire6.put14(b, 259, 256);
        for (int i = 0; i < 256; i++) {
            int at = 261 + 7 * i;
            b[at] = (byte)(48 + i % 24); Wire6.put14(b, at + 1, i / 24 * 128);
            Wire6.put14(b, at + 3, 96); b[at + 6] = 100;
        }
        b[2053] = NativeUi.CELLS; Wire6.put14(b, 2054, 898); Wire6.put14(b, 2056, 128);
        for (int i = 0; i < 128; i++) Wire6.text(b, 2058 + 7 * i + 3, 4, "");
        b[2954] = NativeUi.ROWS; Wire6.put14(b, 2955, 97); b[2957] = 2;
        for (int i = 0; i < 2; i++) Wire6.text(b, 2958 + 48 * i, 40, "");
        NativeUi.validate(b);
        return b;
    }
    private static boolean completesView(List<byte[]> frames, int revision) {
        for (byte[] f : frames)
            if (f[5] == Wire6.VIEW_PART && Wire6.u14(f, 8) == revision &&
                    Wire6.u14(f, 12) + f.length - 15 == Wire6.u14(f, 10)) return true;
        return false;
    }
    private static void nativeLifecycle(String executable) throws Exception {
        App app = new App(); app.nativeView = nativeScore(0x123456);
        OpenSession session = new OpenSession(app, "example.native", Wire6.USB_RETURN,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true));
        try (Peer peer = new Peer(executable)) {
            session.begin(1500, 0); peer.step(0, 0, app.outbound, session);
            session.tick(34); peer.step(34, 0, app.outbound, session);
            for (int t = 68; t <= 748; t += 34) {
                session.tick(t); check(app.outbound.size() == 1, "one native part per tick");
                peer.step(t, 0, app.outbound, session);
                check(peer.revision == (t == 748 ? 1 : 0), "partial score cannot replace display");
                check(peer.audio == (t == 748 ? 1 : 0), "initial audio waits for complete native score");
            }
            check(session.isSynchronized() && app.ready == 1, "native final ACK activates session");
            app.nativeView = nativeScore(0x234567);
            for (int t = 782; t <= 1564; t += 34) {
                session.tick(t);
                int expected = completesView(app.outbound, 2) ? 2 : peer.revision;
                peer.step(t, t >= 884 && t < 1530 ? 4 : 0, app.outbound, session);
                check(peer.revision == expected, "old complete score stays published until the final fragment");
            }
            check(peer.revision == 2 && app.stopped == null, "second large score completes within lease");
            check(app.scans.stream().anyMatch(scan -> Wire6.u28(scan, 2) == 4 &&
                    Wire6.u28(scan, 20) == 0x123456), "input during transfer targets old drawn context");
            app.nativeView = nativeScore(0x345678);
            boolean dropped = false;
            for (int t = 1598; t <= 3264; t += 34) {
                session.tick(t);
                if (!app.outbound.isEmpty()) {
                    byte[] frame = app.outbound.get(0);
                    if (frame[5] == Wire6.VIEW_PART && Wire6.u14(frame, 12) == 152) {
                        app.outbound.clear(); dropped = true;
                    }
                }
                peer.step(t, 0, app.outbound, session);
                check(peer.revision <= 2, "lost parts never publish a partial new score");
                if (!session.isOpen()) break;
            }
            check(dropped && app.stopped != null && !session.isOpen(), "lost required part fails closed");
            check(peer.phase == 0 && peer.audio == 0, "lost transfer releases device and audio ownership");
        }
    }
    private static void nativeIdleLifecycle(String executable) throws Exception {
        App app = new App(); app.nativeView = nativeScore(0x123456);
        OpenSession session = new OpenSession(app, "example.native", Wire6.USB_RETURN,
                new Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                new Wire6.Capability(Wire6.NATIVE_IDLE_KEEPALIVE, 1, 0, false));
        try (Peer peer = new Peer(executable)) {
            session.begin(1600, 0); peer.step(0, 0, app.outbound, session);
            session.tick(34); peer.step(34, 0, app.outbound, session);
            for (int t = 68; t <= 748; t += 34) {
                session.tick(t); peer.step(t, 0, app.outbound, session);
            }
            check(peer.revision == 1 && session.isSynchronized() &&
                    session.hasCapability(Wire6.NATIVE_IDLE_KEEPALIVE), "native idle capability negotiated");
            check(!session.hasCapability(Wire6.NATIVE_DELTA), "older native peer remains full-view only");
            int heartbeats = 0;
            final int idleEnd = 3230; // Stay idle beyond the 1500 ms lease.
            for (int t = 782; t <= idleEnd; t += 34) {
                session.tick(t);
                for (byte[] frame : app.outbound) {
                    check(frame[5] == Wire6.HEARTBEAT, "confirmed maximum view sends no idle parts");
                    heartbeats++;
                }
                peer.step(t, 0, app.outbound, session);
                check(peer.revision == 1 && session.isSynchronized(), "idle heartbeat retains display lease");
            }
            check(heartbeats >= 4 && heartbeats <= 7, "small, infrequent idle keepalives");
            app.nativeView = nativeScore(0x234567);
            final int changedAt = idleEnd + 34;
            final int changedAck = changedAt + 748;
            boolean olderFull=false;
            for (int t = changedAt; t <= changedAck; t += 34) {
                session.tick(t);
                for(byte[] frame:app.outbound) {
                    check(frame[5]==Wire6.VIEW_PART || frame[5]==Wire6.HEARTBEAT,
                            "older native peer receives no VIEW_DELTA");
                    if(frame[5]==Wire6.VIEW_PART)olderFull=true;
                }
                int expected = completesView(app.outbound, 2) ? 2 : peer.revision;
                peer.step(t, 0, app.outbound, session);
                check(peer.revision == expected, "changed maximum view stays atomic");
            }
            check(olderFull && peer.revision == 2 && app.stopped == null,
                    "first post-idle maximum change completes well within lease");
            app.nativeView = nativeScore(0x345678);
            boolean dropped = false;
            for (int t = changedAck + 34; t <= changedAck + 1530; t += 34) {
                session.tick(t);
                if (!app.outbound.isEmpty() && app.outbound.get(0)[5] == Wire6.VIEW_PART &&
                        Wire6.u14(app.outbound.get(0), 12) == 152) {
                    app.outbound.clear(); dropped = true;
                }
                peer.step(t, 0, app.outbound, session);
                if (!session.isOpen()) break;
                check(peer.phase == 2 && peer.revision == 2,
                        "previous view stays active until the lost transfer expires");
            }
            check(dropped && !session.isOpen() && peer.phase == 0 && peer.revision == 0 && peer.audio == 0,
                    "lost native part expires safely after idle mode");
        }
    }
    private static byte[] nativePlain(int context) {
        byte[] b=Arrays.copyOf(nativeScore(context), NativeUi.HEADER);
        b[6]=NativeUi.ENV_PAGE; b[8]=NativeUi.ENV; b[19]=0;
        Wire6.put14(b, 20, 16383); Wire6.put14(b, 22, 16383);
        NativeUi.validate(b);
        return b;
    }
    private static void nativeDeltaLifecycle(String executable) throws Exception {
        App app=new App();app.nativeView=nativeScore(0x123456);
        OpenSession session=new OpenSession(app,"example.delta",Wire6.USB_RETURN,
                new Wire6.Capability(Wire6.INSTRUMENT,1,0,true),
                new Wire6.Capability(Wire6.NATIVE_UI,1,0,true),
                new Wire6.Capability(Wire6.NATIVE_IDLE_KEEPALIVE,1,0,true),
                new Wire6.Capability(Wire6.NATIVE_DELTA,1,0,true));
        try(Peer peer=new Peer(executable)) {
            session.begin(1700,0);peer.step(0,0,app.outbound,session);
            session.tick(34);peer.step(34,0,app.outbound,session);
            check(session.hasCapability(Wire6.NATIVE_DELTA)==false,"delta waits for first state ACK");
            for(int t=68;t<=748;t+=34) {
                session.tick(t);
                check(app.outbound.size()==1 && app.outbound.get(0)[5]==Wire6.VIEW_PART,
                        "initial native page uses complete VIEW_PART transfer");
                peer.step(t,0,app.outbound,session);
            }
            check(peer.revision==1 && session.isSynchronized() &&
                    session.hasCapability(Wire6.NATIVE_DELTA),"delta accepted only with native UI and keepalive");
            byte[] changed=app.nativeView.clone();Wire6.put14(changed,22,1);app.nativeView=changed;
            session.tick(782);
            check(app.outbound.size()==1 && app.outbound.get(0)[5]==Wire6.VIEW_DELTA,
                    "playhead change uses one delta message");
            byte[] delta=app.outbound.get(0);
            check(delta.length<32 && Wire6.u14(delta,10)==1 && Wire6.u14(delta,12)==22 &&
                    (delta[14]&127)==2,"delta names confirmed base and only changed playhead bytes");
            peer.step(782,0,app.outbound,session);
            check(peer.revision==2 && app.displayed.get(app.displayed.size()-1)==0x123456,
                    "delta ACK confirms the existing drawn target context");
            int heartbeats=0;
            for(int t=816;t<=1292;t+=34) {
                session.tick(t);
                for(byte[] frame:app.outbound) {
                    check(frame[5]==Wire6.HEARTBEAT,"ACKed delta returns to idle heartbeat");heartbeats++;
                }
                peer.step(t,0,app.outbound,session);
                check(peer.revision==2 && session.isSynchronized(),"delta display retained during idle");
            }
            check(heartbeats>=1,"idle heartbeat follows delta ACK");
            /* Drop one small update, then change the desired view again. Recovery
             * must publish the latest complete view before the lease expires. */
            changed=app.nativeView.clone();Wire6.put14(changed,22,2);app.nativeView=changed;
            int lostAt=0;
            for(int t=1326;t<=1462;t+=34) {
                session.tick(t);
                if(!app.outbound.isEmpty() && app.outbound.get(0)[5]==Wire6.VIEW_DELTA) {
                    app.outbound.clear();lostAt=t;
                }
                peer.step(t,0,app.outbound,session);
                if(lostAt!=0)break;
            }
            check(lostAt!=0,"next playhead update is a delta");
            changed=app.nativeView.clone();Wire6.put28(changed,2,0x456789);
            Wire6.put14(changed,22,3);app.nativeView=changed;
            boolean recovery=false;
            for(int t=lostAt+34;t<=lostAt+884;t+=34) {
                session.tick(t);
                for(byte[] frame:app.outbound) {
                    check(frame[5]!=Wire6.HELLO && frame[5]!=Wire6.PROBE,
                            "recovery never renegotiates capabilities");
                    if(frame[5]==Wire6.VIEW_PART)recovery=true;
                }
                peer.step(t,0,app.outbound,session);
                if(recovery && peer.revision>=4)break;
            }
            check(recovery && peer.phase==2 && session.isSynchronized() &&
                    app.displayed.get(app.displayed.size()-1)==0x456789,
                    "lost delta falls back to newer full current snapshot within lease");
            int now=lostAt+918;
            peer.step(now,4,new ArrayList<>(),session);
            session.tick(now+34);peer.step(now+34,4,app.outbound,session);
            check(app.scans.stream().anyMatch(scan -> Wire6.u28(scan,2)==4 &&
                    Wire6.u28(scan,20)==0x456789),"input uses recovered last-drawn context");
            /* A different layout and a broad score change must use complete views. */
            app.nativeView=nativePlain(0x56789a);
            boolean layoutFull=false;
            for(int t=now+68;t<=now+800;t+=34) {
                session.tick(t);
                for(byte[] frame:app.outbound)if(frame[5]==Wire6.VIEW_PART)layoutFull=true;
                peer.step(t,0,app.outbound,session);
                if(layoutFull && app.displayed.get(app.displayed.size()-1)==0x56789a)break;
            }
            check(layoutFull && peer.phase==2,"layout and length change use complete view");
            app.nativeView=nativeScore(0x6789ab);
            boolean largeFull=false;
            for(int t=now+834;t<=now+1650;t+=34) {
                session.tick(t);
                for(byte[] frame:app.outbound)if(frame[5]==Wire6.VIEW_PART)largeFull=true;
                peer.step(t,0,app.outbound,session);
                if(largeFull && app.displayed.get(app.displayed.size()-1)==0x6789ab)break;
            }
            check(largeFull && peer.phase==2,"oversized change uses complete view");
            changed=app.nativeView.clone();
            for(int i=0;i<80;i++)changed[261+7*i]=(byte)(60+i%12);
            app.nativeView=changed;
            boolean oversizedFull=false;
            for(int t=now+1684;t<=now+2500;t+=34) {
                session.tick(t);
                for(byte[] frame:app.outbound)if(frame[5]==Wire6.VIEW_PART)oversizedFull=true;
                peer.step(t,0,app.outbound,session);
                if(oversizedFull && peer.revision>=7)break;
            }
            check(oversizedFull && session.isSynchronized(),
                    "change exceeding one 156-byte delta uses complete view");
            changed=app.nativeView.clone();Wire6.put14(changed,22,5);app.nativeView=changed;
            int rejectedAt=now+2534;
            session.tick(rejectedAt);
            check(app.outbound.size()==1 && app.outbound.get(0)[5]==Wire6.VIEW_DELTA,
                    "small post-full update requests delta");
            byte[] proposed=app.outbound.remove(0);
            byte[] bad=Arrays.copyOfRange(proposed,10,proposed.length-1);
            Wire6.put14(bad,0,1); /* wrong confirmed base, valid frame shape */
            List<byte[]> outsider=new ArrayList<>();
            outsider.add(Wire6.frame(Wire6.VIEW_DELTA,1700,Wire6.u14(proposed,8),bad));
            List<Wire6.Frame> denied=peer.step(rejectedAt,0,outsider,session);
            check(denied.stream().anyMatch(f -> f.command==Wire6.ACK &&
                    f.payload[0]==Wire6.VIEW_DELTA && f.payload[1]==2),
                    "firmware rejects mismatched delta base without publishing it");
            boolean rejectedFull=false;
            for(int t=rejectedAt+34;t<=rejectedAt+850;t+=34) {
                session.tick(t);
                for(byte[] frame:app.outbound) {
                    check(frame[5]!=Wire6.HELLO && frame[5]!=Wire6.PROBE,
                            "rejected delta recovery retains negotiated capabilities");
                    if(frame[5]==Wire6.VIEW_PART)rejectedFull=true;
                }
                peer.step(t,0,app.outbound,session);
                if(rejectedFull && peer.revision>=9)break;
            }
            check(rejectedFull && peer.phase==2 && session.isSynchronized(),
                    "rejected mismatched delta recovers with newer full current view");
            session.close();peer.step(rejectedAt+884,0,app.outbound,session);
        }
    }
    public static void main(String[] args) throws Exception {
        lifecycle(args[0], Wire6.PHONE, false);
        lifecycle(args[0], Wire6.PHONE, true);
        lifecycle(args[0], Wire6.USB_RETURN, true);
        nativeLifecycle(args[0]);
        nativeIdleLifecycle(args[0]);
        nativeDeltaLifecycle(args[0]);
        System.out.println("Open wire6 Java/C mailbox: " + checks + " checks passed");
    }
}
