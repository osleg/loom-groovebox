/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import org.fm1.companion.protocol.UsbMidiPackets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class UsbMidiPacketsTest {
    @Test fun runningStatusAndRealtimeInterleaveWithoutChangingMessageOrder() {
        val packets = mutableListOf<Int>()
        val parser = UsbMidiPackets(packets::add)
        parser.feed(byteArrayOf(0x90.toByte(), 60, 100, 61, 0xf8.toByte(), 110, 0xf2.toByte(), 4), 0, 8)
        parser.feed(byteArrayOf(2, 0xfa.toByte(), 0xfc.toByte()), 0, 3)
        assertEquals(listOf(
            UsbMidiPackets.packet(9, 0x90, 60, 100),
            UsbMidiPackets.packet(15, 0xf8),
            UsbMidiPackets.packet(9, 0x90, 61, 110),
            UsbMidiPackets.packet(3, 0xf2, 4, 2),
            UsbMidiPackets.packet(15, 0xfa),
            UsbMidiPackets.packet(15, 0xfc)
        ), packets)
        assertArrayEquals(byteArrayOf(0xf2.toByte(), 4, 2), UsbMidiPackets.bytes(packets[3]))
    }
}
