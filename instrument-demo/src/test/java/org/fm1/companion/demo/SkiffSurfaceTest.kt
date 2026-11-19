/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import org.fm1.companion.protocol.NativeUi
import org.fm1.companion.protocol.Wire6
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkiffSurfaceTest {
    private class Fake : SkiffPort {
        var context = 11
        val inputs = mutableListOf<Triple<Int, Int, List<Int>>>()
        val gains = mutableListOf<Int>()
        var exits = 0
        var edit = false
        var value = 10
        override fun boot(storage: ByteArray?) {}
        override fun render(out: ShortArray) {}
        override fun masterGain(q12: Int) { gains.add(q12) }
        override fun input(buttons: Int, keys: Int, turns: IntArray) {
            inputs.add(Triple(buttons, keys, turns.toList()))
            if (edit) value = (value + turns[3]).coerceIn(0, 100)
        }
        override fun snapshot() = ByteArray(NativeUi.HEADER).also {
            it[0] = NativeUi.KIND.toByte()
            Wire6.put28(it, NativeUi.CONTEXT, context)
            if (edit) {
                it[NativeUi.SCREEN] = NativeUi.EDIT1.toByte()
                it[NativeUi.FAMILY] = NativeUi.EDIT.toByte()
                it[NativeUi.CONTROLS + 30] = value.toByte()
            }
        }
        override fun midiIn(packet: Int, source: Int) = true
        override fun editContext() = context
        override fun midiOut() = intArrayOf()
        override fun midiOutTimed() = longArrayOf()
        override fun exitDeviceMenu() { exits++ }
        override fun stop() {}
        override fun storage(lastRevision: Int): ByteArray? = null
        override fun storageRevision() = 0
    }
    private fun scan(buttons: Int = 0, keys: Int = 0, context: Int = 11,
                     turn: Int = 0, menu: Boolean = false, master: Int = 2048,
                     turnIndex: Int = 0) = ByteArray(26).also {
        Wire6.put14(it, 0, buttons)
        Wire6.put28(it, 2, keys)
        for (i in 0..6) Wire6.put14(it, 6 + 2 * i, 8192 + if (i == turnIndex) turn else 0)
        Wire6.put28(it, 20, context)
        Wire6.put14(it, 24, master)
        if (menu) it[5] = (it[5].toInt() or 64).toByte()
    }

    @Test fun firstAndStaleScansSuppressNewHoldsAndTurnsButReleaseOldHolds() {
        val native = Fake()
        val surface = SkiffSurface(Any(), native)
        assertTrue(surface.input(1, scan(keys = 1, turn = 3)))
        assertEquals(0, native.inputs.last().second)
        surface.input(2, scan())
        surface.input(3, scan(keys = 1))
        assertEquals(1, native.inputs.last().second)
        native.context = 12
        surface.input(4, scan(keys = 3, context = 11, turn = 2))
        assertEquals(1, native.inputs.last().second)
        assertEquals(0, native.inputs.last().third[0])
        surface.input(5, scan(keys = 3, context = 12))
        assertEquals(1, native.inputs.last().second)
        surface.input(6, scan(keys = 2, context = 12))
        assertEquals(0, native.inputs.last().second)
        surface.input(7, scan(context = 12))
        surface.input(8, scan(keys = 2, context = 12))
        assertEquals(2, native.inputs.last().second)
    }

    @Test fun deviceMenuOwnsInputAndExitsOnce() {
        val native = Fake()
        val surface = SkiffSurface(Any(), native)
        surface.input(1, scan())
        surface.input(2, scan(keys = 1))
        surface.input(3, scan(keys = 1, menu = true))
        assertEquals(0, native.inputs.last().second)
        surface.input(4, scan(keys = 1, menu = true))
        surface.input(5, scan(keys = 1))
        assertEquals(1, native.exits)
        assertEquals(0, native.inputs.last().second)
        surface.input(6, scan())
        surface.input(7, scan(keys = 1))
        assertEquals(1, native.inputs.last().second)
    }

    @Test fun masterFollowsEveryPhysicalScanAndResetsWithTheSurface() {
        val native = Fake()
        val surface = SkiffSurface(Any(), native)
        assertTrue(surface.input(1, scan(master = 0)))
        assertTrue(surface.input(2, scan(context = 99, master = 4096)))
        assertTrue(surface.input(3, scan(menu = true, master = 512)))
        assertEquals(listOf(0, 4096, 512), native.gains)
        surface.reset()
        assertEquals(-1, native.gains.last())
    }
}
