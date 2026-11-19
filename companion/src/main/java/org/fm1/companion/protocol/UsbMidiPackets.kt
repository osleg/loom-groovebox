/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.protocol

/** Converts ordered MIDI bytes into the USB-MIDI packet form used by Skiff. */
class UsbMidiPackets(private val accept: (Int) -> Unit) {
    private var runningStatus = 0
    private var status = 0
    private var first = -1

    fun reset() {
        status = 0
        runningStatus = 0
        first = -1
    }

    fun feed(data: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val byte = data[i].toInt() and 255
            if (byte >= 0xf8) { accept(packet(15, byte)); continue }
            if (byte >= 0x80) {
                first = -1
                status = byte
                if (byte < 0xf0) runningStatus = byte else runningStatus = 0
                if (byte == 0xf6) accept(packet(5, byte))
                continue
            }
            if (status == 0) status = runningStatus
            if (status == 0) continue
            val needed = when {
                status == 0xf2 -> 2
                status == 0xf1 || status == 0xf3 -> 1
                status in 0x80..0xef -> if ((status and 0xf0) == 0xc0 || (status and 0xf0) == 0xd0) 1 else 2
                else -> 0
            }
            if (needed == 0) continue
            if (needed == 2 && first < 0) { first = byte; continue }
            val cin = when {
                status == 0xf2 -> 3
                status >= 0xf0 -> 2
                else -> status ushr 4
            }
            accept(packet(cin, status, if (needed == 2) first else byte,
                if (needed == 2) byte else 0))
            first = -1
            status = runningStatus
        }
    }

    companion object {
        fun packet(cin: Int, a: Int, b: Int = 0, c: Int = 0): Int =
            cin or (a shl 8) or (b shl 16) or (c shl 24)
        fun bytes(packet: Int): ByteArray {
            val cin = packet and 15
            val count = when (cin) {
                2, 6, 12, 13 -> 2
                5, 15 -> 1
                else -> 3
            }
            return ByteArray(count) { ((packet ushr (8 * (it + 1))) and 255).toByte() }
        }
    }
}
