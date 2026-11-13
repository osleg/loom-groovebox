/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa;

import java.util.Arrays;

/** CAFA session-versioned SysEx framing. Payload, session and serial are all seven-bit safe. */
public final class Protocol {
    public static final int HELLO = 1, ACK = 2, STATE = 3, HEARTBEAT = 4, BYE = 5, INPUT = 6;
    public static final int NONE = 16383;
    public static final int VERSION = 4; // Expanded surface; Loom envelopes keep their own timing curves.
    private Protocol() {}

    public static int u14(byte[] bytes, int at) {
        return (bytes[at] & 127) | ((bytes[at + 1] & 127) << 7);
    }
    public static void put14(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value & 127);
        bytes[at + 1] = (byte) ((value >>> 7) & 127);
    }
    public static long u28(byte[] bytes, int at) {
        long value = 0;
        for (int i = 0; i < 4; i++) value |= (long) (bytes[at + i] & 127) << (7 * i);
        return value;
    }
    public static void put28(byte[] bytes, int at, long value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) ((value >>> (7 * i)) & 127);
    }
    public static boolean newer(int candidate, int previous) {
        int delta = (candidate - previous) & 16383;
        return delta > 0 && delta < 8192;
    }
    public static byte[] frame(int command, int session, int serial, byte[] payload) {
        return frame(VERSION, command, session, serial, payload);
    }
    public static byte[] frame(int version, int command, int session, int serial, byte[] payload) {
        if (version < 1 || version > 4) throw new IllegalArgumentException("Unsupported protocol version");
        if (session <= 0 || session > 16383 || serial < 0 || serial > 16383) {
            throw new IllegalArgumentException("Invalid session or serial");
        }
        if (payload.length > 128) throw new IllegalArgumentException("Payload too large");
        byte[] bytes = new byte[11 + payload.length];
        bytes[0] = (byte) 0xf0;
        bytes[1] = 0x7d; bytes[2] = 0x43; bytes[3] = 0x41; bytes[4] = (byte) version;
        bytes[5] = (byte) command;
        put14(bytes, 6, session); put14(bytes, 8, serial);
        for (int i = 0; i < payload.length; i++) {
            if ((payload[i] & 128) != 0) throw new IllegalArgumentException("Non-seven-bit payload");
            bytes[10 + i] = payload[i];
        }
        bytes[bytes.length - 1] = (byte) 0xf7;
        return bytes;
    }
    public static void text(byte[] bytes, int at, int size, String value) {
        Arrays.fill(bytes, at, at + size, (byte) 32);
        for (int i = 0; i < size && i < value.length(); i++) {
            char c = value.charAt(i);
            bytes[at + i] = (byte) (c >= 32 && c < 127 ? c : '?');
        }
    }
    public static final class Frame {
        public final int version, command, session, serial;
        public final byte[] payload;
        Frame(byte[] b, int count) {
            version = b[4] & 127; command = b[5] & 127; session = u14(b, 6); serial = u14(b, 8);
            payload = Arrays.copyOfRange(b, 10, count - 1);
        }
    }
    public interface Receiver { void receive(Frame frame); }
    public static final class Decoder {
        private final byte[] buffer = new byte[140];
        private int count;
        private final Receiver receiver;
        public Decoder(Receiver receiver) { this.receiver = receiver; }
        public void reset() { count = 0; }
        public void feed(byte[] bytes, int offset, int length) {
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IllegalArgumentException("Invalid input range");
            }
            for (int i = offset; i < offset + length; i++) accept(bytes[i] & 255);
        }
        private void accept(int value) {
            if (value >= 0xf8) return; // MIDI real-time may interrupt SysEx.
            if (value == 0xf0) { count = 1; buffer[0] = (byte) value; return; }
            if (count == 0) return;
            if (value == 0xf7) {
                if (count >= 10 && buffer[1] == 0x7d && buffer[2] == 0x43 &&
                        buffer[3] == 0x41 && (buffer[4] >= 1 && buffer[4] <= 4) && u14(buffer, 6) != 0) {
                    buffer[count++] = (byte) value;
                    Frame frame = new Frame(buffer, count);
                    count = 0;
                    receiver.receive(frame);
                } else count = 0;
                return;
            }
            if (value >= 0x80 || count >= buffer.length - 1) { count = 0; return; }
            buffer[count++] = (byte) value;
        }
    }
}
