/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ParameterBanksTest {
    private val gain = ParameterDescriptor("gain", "Gain", fallback = -100f,
        minimum = -80f, maximum = 20f, step = 2f, unit = "dB")

    @Test fun descriptorHandlesRealBoundsAndFormatting() {
        assertEquals(-80f, gain.value(Float.NaN), 0f)
        assertEquals(-80f, gain.value(null), 0f) // Out-of-range fallback is clamped.
        assertEquals(16383, gain.normalized(20f))
        assertEquals(8192, gain.normalized(-30f))
        assertEquals("-30.0dB", gain.display(-30f))
        assertEquals(20f, gain.adjusted(-30f, Int.MAX_VALUE), 0f)
        assertEquals(-80f, gain.adjusted(-30f, Int.MIN_VALUE), 0f)

        val mode = ParameterDescriptor("mode", "Mode", minimum = 10f, maximum = 30f,
            choices = listOf("A", "B", "C"))
        assertEquals("B", mode.display(21f))
        assertEquals("55%", ParameterDescriptor("mix", "Mix", minimum = 10f, maximum = 30f).display(21f))
        assertThrows(IllegalArgumentException::class.java) { ParameterDescriptor(" ", "Blank") }
    }

    @Test fun pagesKeepSlotsAndClampWideNavigation() {
        val duplicate = ParameterBanks.Control(gain, -30f)
        val adapter = FakeAdapter(ParameterBanks.State("instrument-a", 3,
            listOf(duplicate, null, duplicate, null, ParameterBanks.Control(gain, 0f))))
        val banks = ParameterBanks(adapter)
        val first = banks.page()
        assertEquals(2, first.bankCount)
        assertEquals("gain", first.controls[0]?.parameter?.id)
        assertEquals(-30f, first.controls[0]!!.value!!, 0f)
        assertNull(first.controls[1])
        assertEquals("gain", first.controls[2]?.parameter?.id)
        assertEquals(-30f, first.controls[2]!!.value!!, 0f)
        assertNull(first.controls[3])
        assertEquals(1, banks.selectBank(Int.MAX_VALUE).context.bank)
        assertEquals(4, banks.page().controls.size)
        assertNull(banks.page().controls[1])
        assertEquals(0, banks.selectBank(Int.MIN_VALUE).context.bank)
        banks.selectBank(1)
        banks.resetBank()
        assertEquals(0, banks.page().context.bank)
        adapter.state = adapter.state.copy(revision = 4, controls = List(20) { duplicate })
        banks.selectBank(Int.MAX_VALUE)
        adapter.state = adapter.state.copy(revision = 5, controls = List(5) { duplicate })
        assertEquals(0, banks.selectBank(-1).context.bank)
    }

    @Test fun staleContextStopsEditsBeforeApply() {
        val adapter = FakeAdapter(ParameterBanks.State("instrument-a", 3, listOf(ParameterBanks.Control(gain, -30f))))
        val banks = ParameterBanks(adapter)
        val context = banks.page().context
        for (state in listOf(
            adapter.state.copy(targetId = "instrument-b"),
            adapter.state.copy(revision = 4)
        )) {
            adapter.state = state
            assertEquals(ParameterBanks.Result.STALE, banks.adjust(0, 1, context))
            assertEquals(0, adapter.applies)
        }
        adapter.state = adapter.state.copy(targetId = context.targetId, revision = context.revision)
        assertEquals(ParameterBanks.Result.INVALID, banks.adjust(4, 1, context))
        assertEquals(ParameterBanks.Result.UNSUPPORTED, banks.adjust(1, 1, context))
        assertEquals(ParameterBanks.Result.COMPLETED, banks.adjust(0, 0, context))
        assertEquals(0, adapter.applies)
    }

    @Test fun adapterResultIsReturnedWithoutOptimisticValueChange() {
        val adapter = FakeAdapter(ParameterBanks.State("instrument-a", 3, listOf(ParameterBanks.Control(gain, -30f))))
        val banks = ParameterBanks(adapter)
        val context = banks.page().context
        adapter.result = ParameterBanks.Result.FAILED
        assertEquals(ParameterBanks.Result.FAILED, banks.adjust(0, 2, context))
        assertEquals(ParameterBanks.Edit("instrument-a", 3, "gain", -26f), adapter.lastEdit)
        assertEquals(-30f, banks.page().controls[0]!!.value!!, 0f)
    }

    @Test fun invalidApplicationSnapshotsAreRejected() {
        val adapter = FakeAdapter(ParameterBanks.State("instrument-a", 0, emptyList()))
        val banks = ParameterBanks(adapter)
        for (state in listOf(
            adapter.state.copy(targetId = " "),
            adapter.state.copy(revision = -1),
            adapter.state.copy(controls = List(65533) { null })
        )) {
            adapter.state = state
            assertThrows(IllegalArgumentException::class.java) { banks.page() }
        }
    }

    private class FakeAdapter(var state: ParameterBanks.State) : ParameterBanks.Adapter {
        var result = ParameterBanks.Result.COMPLETED
        var applies = 0
        var lastEdit: ParameterBanks.Edit? = null
        override fun read() = state
        override fun apply(edit: ParameterBanks.Edit): ParameterBanks.Result {
            applies++
            lastEdit = edit
            return result
        }
    }
}
