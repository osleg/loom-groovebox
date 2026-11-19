/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Strict CAFA wire-6 framing and first-profile payload validation. */
public final class Wire6 {
    private Wire6() {}
    public static final int HELLO = 1, ACK = 2, STATE = 3, HEARTBEAT = 4,
            BYE = 5, INPUT = 6, PROBE = 7, OFFER = 8, VIEW_PART = 9, VIEW_DELTA = 10,
            DIAGNOSTICS = 11, PART_READY = 12;
    public static final int PHONE = 0, USB_RETURN = 1;
    public static final int INSTRUMENT = 1, TRANSPORT = 2, PARTS = 3, NATIVE_UI = 9,
            NATIVE_IDLE_KEEPALIVE = 11, NATIVE_DELTA = 12, NATIVE_PANEL = 13, PANEL_MIDI = 14, NATIVE_CHROME = 15;
    public static final int MAX_PAYLOAD = 156, MAX_FRAME = 167;

    public static int u14(byte[] b, int at) { return (b[at] & 127) | ((b[at + 1] & 127) << 7); }
    public static int u28(byte[] b, int at) { return u14(b, at) | (u14(b, at + 2) << 14); }
    public static void put14(byte[] b, int at, int n) {
        if (n < 0 || n > 16383) throw new IllegalArgumentException("u14");
        b[at] = (byte)(n & 127); b[at + 1] = (byte)(n >> 7);
    }
    public static void put28(byte[] b, int at, int n) {
        if (n < 0 || n > 0x0fffffff) throw new IllegalArgumentException("u28");
        put14(b, at, n & 16383); put14(b, at + 2, n >> 14);
    }
    public static void text(byte[] b, int at, int length, String value) {
        Arrays.fill(b, at, at + length, (byte)' ');
        for (int i = 0; i < Math.min(length, value.length()); i++) {
            char c = value.charAt(i);
            b[at + i] = (byte)(c >= 32 && c <= 126 ? c : '?');
        }
    }
    public static byte[] frame(int command, int session, int serial, byte[] payload) {
        if (command < HELLO || command > PART_READY || session <= 0 || session > 16383 ||
                serial < 0 || serial > 16383 || payload == null || payload.length > MAX_PAYLOAD)
            throw new IllegalArgumentException("frame range");
        byte[] b = new byte[11 + payload.length];
        b[0] = (byte)0xf0; b[1] = 0x7d; b[2] = 0x43; b[3] = 0x41; b[4] = 6; b[5] = (byte)command;
        put14(b, 6, session); put14(b, 8, serial);
        for (int i = 0; i < payload.length; i++) {
            if ((payload[i] & 128) != 0) throw new IllegalArgumentException("non-seven-bit payload");
            b[10 + i] = payload[i];
        }
        b[b.length - 1] = (byte)0xf7;
        validate(command, serial, payload);
        return b;
    }
    public static final class Frame {
        public final int command, session, serial;
        public final byte[] payload;
        private Frame(byte[] b, int size) {
            command = b[5] & 127; session = u14(b, 6); serial = u14(b, 8);
            payload = Arrays.copyOfRange(b, 10, size - 1);
        }
    }
    public interface Receiver { void receive(Frame frame); }
    /** Streaming decoder drops malformed or unrelated SysEx; real-time MIDI may interrupt a frame. */
    public static final class Decoder {
        private final byte[] b = new byte[MAX_FRAME];
        private final Receiver receiver;
        private int count;
        public Decoder(Receiver receiver) { this.receiver = receiver; }
        public void reset() { count = 0; }
        public void feed(byte[] data, int offset, int length) {
            if (data == null || offset < 0 || length < 0 || offset > data.length - length)
                throw new IllegalArgumentException("range");
            for (int i = offset; i < offset + length; i++) {
                int v = data[i] & 255;
                if (v >= 0xf8) continue;
                if (v == 0xf0) { count = 1; b[0] = data[i]; continue; }
                if (count == 0) continue;
                if (v == 0xf7) {
                    if (count >= 10 && count < MAX_FRAME && b[1] == 0x7d && b[2] == 0x43 &&
                            b[3] == 0x41 && b[4] == 6 && u14(b, 6) != 0) {
                        b[count++] = data[i];
                        int cmd = b[5] & 127, serial = u14(b, 8);
                        byte[] payload = Arrays.copyOfRange(b, 10, count - 1);
                        boolean valid;
                        try { validate(cmd, serial, payload); valid = true; }
                        catch (IllegalArgumentException ignored) { valid = false; }
                        Frame frame = valid ? new Frame(b, count) : null;
                        count = 0;
                        if (frame != null) receiver.receive(frame);
                    }
                    count = 0;
                } else if (v >= 128 || count >= MAX_FRAME - 1) count = 0;
                else b[count++] = data[i];
            }
        }
    }
    public static final class Capability {
        public final int id, major, minor;
        public final boolean required;
        public Capability(int id, int major, int minor, boolean required) {
            if (id < 1 || id > 127 || major < 0 || major > 127 || minor < 0 || minor > 127)
                throw new IllegalArgumentException("capability");
            this.id = id; this.major = major; this.minor = minor; this.required = required;
        }
    }
    public static byte[] hello(String namespace, int audioMode, Capability... requested) {
        if (namespace == null || namespace.length() < 1 || namespace.length() > 64 ||
                audioMode < 0 || audioMode > 1 || requested == null || requested.length > 8)
            throw new IllegalArgumentException("hello");
        for (int i = 0; i < namespace.length(); i++) if (namespace.charAt(i) < 33 || namespace.charAt(i) > 126)
            throw new IllegalArgumentException("namespace");
        byte[] b = new byte[5 + namespace.length() + 4 * requested.length];
        b[0] = 1; b[1] = 0; b[2] = (byte)audioMode; b[3] = (byte)namespace.length();
        byte[] name = namespace.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, b, 4, name.length);
        b[4 + name.length] = (byte)requested.length;
        int at = 5 + name.length;
        for (Capability c : requested) {
            b[at++] = (byte)c.id; b[at++] = (byte)c.major;
            b[at++] = (byte)c.minor; b[at++] = (byte)(c.required ? 1 : 0);
        }
        validate(HELLO, 0, b);
        return b;
    }
    public static void validate(int command, int serial, byte[] p) {
        if (p == null || p.length > MAX_PAYLOAD || command < HELLO || command > PART_READY)
            throw new IllegalArgumentException("command/payload");
        for (byte value : p) if ((value & 128) != 0) throw new IllegalArgumentException("non-seven-bit");
        switch (command) {
            case PART_READY:
                if (p.length != 2 || u14(p, 0) == 0 || u14(p, 0) >= NativeUi.MAX_LENGTH)
                    throw new IllegalArgumentException("part ready");
                return;
            case DIAGNOSTICS:
                if (p.length == 1 && p[0] >= 1 && p[0] <= 3) return;
                if (p.length == 38 && p[0] >= 1 && p[0] <= 3 && p[1] == 3) return;
                if (p.length == 46 && p[0] >= 1 && p[0] <= 3 && p[1] == 4) return;
                throw new IllegalArgumentException("diagnostics");
            case HELLO: {
                if (serial != 0 || p.length < 9 || p[1] > 1 || p[3] < 1 || p[3] > 64 || p[2] > 1)
                    throw new IllegalArgumentException("hello shape");
                int name = p[3], at = 4 + name;
                if (at >= p.length) throw new IllegalArgumentException("hello length");
                for (int i = 4; i < at; i++) if (p[i] < 33 || p[i] > 126) throw new IllegalArgumentException("namespace");
                validateCapabilities(p, at + 1, p[at], true, false);
                if (p.length != at + 1 + 4 * p[at]) throw new IllegalArgumentException("hello length");
                if (p[0] != 1) throw new IllegalArgumentException("core major");
                boolean instrument = false;
                for (int i = at + 1; i < p.length; i += 4) {
                    if (p[i] == 1) {
                        if (p[i + 1] != 1 || p[i + 2] != 0 || p[i + 3] != 1)
                            throw new IllegalArgumentException("instrument required 1.0");
                        instrument = true;
                    }
                }
                if (!instrument) throw new IllegalArgumentException("instrument missing");
                return;
            }
            case ACK: {
                if (p.length < 2 || p[0] < HELLO || p[0] > DIAGNOSTICS || p[1] > 3)
                    throw new IllegalArgumentException("ack");
                if (p[0] == HELLO && p[1] == 0) {
                    if (serial != 0 || p.length < 3) throw new IllegalArgumentException("hello ack");
                    validateCapabilities(p, 3, p[2], false, false);
                    if (p.length != 3 + 4 * p[2]) throw new IllegalArgumentException("ack length");
                } else if (p.length != 2) throw new IllegalArgumentException("ack length");
                return;
            }
            case STATE: validateView(p); return;
            case HEARTBEAT: case BYE: if (p.length != 0) throw new IllegalArgumentException("empty payload"); return;
            case INPUT:
                if (p.length != 24 && p.length != 26 || p.length == 26 && u14(p,24) > 4096) throw new IllegalArgumentException("input");
                return;
            case VIEW_DELTA:
                if (p.length < 6) throw new IllegalArgumentException("empty native delta");
                int previousEnd = 0;
                for (int at = 2; at < p.length;) {
                    if (p.length - at < 3) throw new IllegalArgumentException("short native delta");
                    int offset = u14(p, at), count = p[at + 2];
                    at += 3;
                    if (count == 0 || at + count > p.length || offset < previousEnd ||
                            offset + count > NativeUi.MAX_LENGTH)
                        throw new IllegalArgumentException("native delta range");
                    previousEnd = offset + count;
                    at += count;
                }
                return;
            case VIEW_PART:
                if (p.length < 5 || p.length > 156 || u14(p, 0) < NativeUi.HEADER ||
                        u14(p, 0) > NativeUi.MAX_LENGTH || u14(p, 2) >= u14(p, 0) ||
                        u14(p, 2) + p.length - 4 > u14(p, 0))
                    throw new IllegalArgumentException("view part");
                return;
            case PROBE: if (serial > 1 || p.length != 0) throw new IllegalArgumentException("probe"); return;
            case OFFER:
                if (serial > 1 || p.length < 6 || (serial == 1 && p[1] < 2) || p[0] != 1 || (p[2] & ~3) != 0 ||
                        u14(p, 3) > MAX_PAYLOAD) throw new IllegalArgumentException("offer");
                validateCapabilities(p, 6, p[5], false, true);
                if (p.length != 6 + 4 * p[5]) throw new IllegalArgumentException("offer length");
                return;
            default: throw new IllegalArgumentException("command");
        }
    }
    private static void validateCapabilities(byte[] p, int at, int count, boolean hello, boolean offer) {
        if (count < 0 || count > 8 || at + 4 * count > p.length) throw new IllegalArgumentException("capability count");
        Set<Integer> ids = new HashSet<>();
        for (int i = at; i < at + 4 * count; i += 4) {
            int id = p[i] & 127;
            if (id == 0 || !ids.add(id) || (p[i + 3] & (hello ? ~1 : ~0)) != 0)
                throw new IllegalArgumentException("capability record");
            if (!hello && !offer && (p[i + 1] != 1 || p[i + 2] != 0 ||
                    (id != INSTRUMENT && id != TRANSPORT && id != PARTS && id != NATIVE_UI &&
                            id != NATIVE_IDLE_KEEPALIVE && id != NATIVE_DELTA && id != NATIVE_PANEL && id != PANEL_MIDI && id != NATIVE_CHROME)))
                throw new IllegalArgumentException("capability version");
        }
    }
    /** Build a one-frame update against the acknowledged view. Null selects complete publication. */
    static byte[] nativeDelta(int revision, byte[] base, byte[] view) {
        if (base == null || base.length != view.length || base[NativeUi.SCREEN] != view[NativeUi.SCREEN] ||
                base[NativeUi.VARIANT] != view[NativeUi.VARIANT]) return null;
        byte[] patch = new byte[MAX_PAYLOAD];
        put14(patch, 0, revision);
        int used = 2;
        for (int i = 0; i < view.length;) {
            if (base[i] == view[i]) { i++; continue; }
            int start = i++;
            int end = i;
            // Spanning up to three unchanged bytes costs no more than another span header.
            while (i < view.length && i - start < 127 && i - end <= 3) {
                if (base[i] != view[i]) end = i + 1;
                i++;
            }
            int count = end - start;
            if (used + 3 + count > patch.length) return null;
            put14(patch, used, start);
            patch[used + 2] = (byte)count;
            System.arraycopy(view, start, patch, used + 3, count);
            used += 3 + count;
        }
        return used == 2 ? null : Arrays.copyOf(patch, used);
    }
    public static void validateView(byte[] p) {
        if (p.length < 34 || (p[1] & ~3) != 0 || p[0] < 1 || p[0] > 4)
            throw new IllegalArgumentException("view prefix");
        for (int i = 6; i < 34; i++) if (p[i] < 32 || p[i] > 126) throw new IllegalArgumentException("view text");
        if (p[0] == 1) {
            if (p.length != 138) throw new IllegalArgumentException("bank length");
            if (u14(p, 36) == 0 || u14(p, 40) == 0 || u14(p, 34) >= u14(p, 36) || u14(p, 38) >= u14(p, 40))
                throw new IllegalArgumentException("bank count");
            for (int at = 42; at < 138; at += 24) {
                for (int i = at; i < at + 20; i++) if (p[i] < 32 || p[i] > 126) throw new IllegalArgumentException("control text");
                int kind = p[at + 22], flags = p[at + 23];
                if (kind > 4 || (flags & ~1) != 0) throw new IllegalArgumentException("control kind/flags");
                if (kind == 0 && (u14(p, at + 20) != 0 || flags != 0 || !blank(p, at, 20)))
                    throw new IllegalArgumentException("absent control");
            }
        } else if (p[0] == 2) {
            if (p.length != 42 || (p[34] & ~3) != 0 || p[41] > 2 ||
                    (p[41] == 0 && u28(p, 37) != 0)) throw new IllegalArgumentException("overview");
        } else if (p[0] == 3) {
            if (p.length != 35 || p[34] > 7) throw new IllegalArgumentException("status");
        } else {
            if (p.length != 138) throw new IllegalArgumentException("mixer length");
            int selected = u14(p, 34), total = u14(p, 36), first = u14(p, 38);
            int visible = p[40], mode = p[41], controls = p[132];
            if (total == 0 || selected >= total || first >= total || (first & 7) != 0 ||
                    visible != Math.min(8, total - first) || selected < first || selected >= first + visible ||
                    mode > 1 || (controls & ~15) != 0)
                throw new IllegalArgumentException("mixer window");
            for (int i = 0; i < 8; i++) {
                int at = 42 + 8 * i, flags = p[at + 7];
                for (int j = at; j < at + 5; j++)
                    if (p[j] < 32 || p[j] > 126) throw new IllegalArgumentException("part name");
                if ((flags & ~63) != 0 || ((flags & 1) != 0 && (flags & 16) == 0) ||
                        ((flags & 2) != 0 && (flags & 32) == 0) ||
                        ((flags & 8) == 0 && u14(p, at + 5) != 0))
                    throw new IllegalArgumentException("part flags");
                if (i >= visible && (!blank(p, at, 5) || u14(p, at + 5) != 0 || flags != 0))
                    throw new IllegalArgumentException("unused part");
            }
            int selectedFlags = p[42 + 8 * (selected - first) + 7];
            if (((controls & 1) != 0) != ((selectedFlags & 8) != 0) ||
                    ((controls & 4) != 0) != ((selectedFlags & 16) != 0) ||
                    ((controls & 8) != 0) != ((selectedFlags & 32) != 0))
                throw new IllegalArgumentException("selected controls");
            for (int i = 106; i < 130; i++)
                if (p[i] < 32 || p[i] > 126) throw new IllegalArgumentException("mixer value text");
            if ((controls & 1) == 0 && !blank(p, 106, 12) ||
                    (controls & 2) == 0 && (!blank(p, 118, 12) || u14(p, 130) != 0))
                throw new IllegalArgumentException("unsupported selected control");
            for (int i = 133; i < 138; i++) if (p[i] != 0) throw new IllegalArgumentException("mixer reserved");
        }
    }
    private static boolean blank(byte[] p, int at, int len) {
        for (int i = at; i < at + len; i++) if (p[i] != ' ') return false;
        return true;
    }
    public static byte[] viewPrefix(int kind, int flags, int context, String title, String subtitle) {
        int length = kind == 1 || kind == 4 ? 138 : kind == 2 ? 42 : kind == 3 ? 35 : -1;
        if (length < 0 || (flags & ~3) != 0) throw new IllegalArgumentException("view");
        byte[] b = new byte[length];
        b[0] = (byte)kind; b[1] = (byte)flags; put28(b, 2, context);
        text(b, 6, 16, title); text(b, 22, 12, subtitle);
        return b;
    }
}
