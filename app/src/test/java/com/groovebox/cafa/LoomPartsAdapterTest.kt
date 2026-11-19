/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.GrooveboxState
import org.fm1.companion.instrument.OperationDispatcher
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.parts.PartsIntent
import org.junit.Assert.*
import org.junit.Test

class LoomPartsAdapterTest {
    @Test fun enabledMeansNotDisabledEvenWhenTrackIsIdle() {
        var model = GrooveboxState()
        val adapter = LoomPartsAdapter({ model }, { model = it },
            { model = model.copy(selectedTrackIndex = it) },
            { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> })
        model = model.copy(tracks = model.tracks.mapIndexed { index, track ->
            when (index) {
                0 -> track.copy(isActive = false, isDisabled = false)
                1 -> track.copy(isActive = true, isDisabled = true)
                else -> track
            }
        })
        val parts = adapter.read().parts
        assertTrue(parts[0].enabled)
        assertFalse(parts[1].enabled)
    }

    @Test fun stablePartSelectionAndMixEditsPreserveOtherTrackFields() {
        var model = GrooveboxState()
        val calls = mutableListOf<String>()
        val adapter = LoomPartsAdapter({ model }, { model = it },
            { index -> calls += "select:$index"; model = model.copy(selectedTrackIndex = index) },
            { index, value -> calls += "level:$index:$value" },
            { index, value -> calls += "pan:$index:$value" },
            { index, value -> calls += "mute:$index:$value" },
            { index, value -> calls += "solo:$index:$value" })
        val dispatcher = OperationDispatcher(adapter)
        dispatcher.begin()
        val initial = adapter.read()
        val destination = initial.parts[2].ref
        model = model.copy(tracks = model.tracks.toMutableList().also {
            val prior = it[1]; it[1] = it[2]; it[2] = prior
        })
        val reordered = adapter.read()
        assertNotEquals(initial.revision, reordered.revision)
        assertEquals(ResultCode.STALE, dispatcher.submit(dispatcher.request(initial.selected, initial.revision,
            PartsIntent.SelectPart(destination))).code)
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(dispatcher.request(reordered.selected, reordered.revision,
            PartsIntent.SelectPart(destination))).code)
        assertEquals(1, model.selectedTrackIndex)
        assertEquals("select:1", calls.last())
        val before = model.tracks[1]
        var current = adapter.read()
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(dispatcher.request(current.selected, current.revision,
            PartsIntent.SetLevel(1.5f))).code)
        current = adapter.read()
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(dispatcher.request(current.selected, current.revision,
            PartsIntent.SetPan(.2f))).code)
        current = adapter.read()
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(dispatcher.request(current.selected, current.revision,
            PartsIntent.SetMute(true))).code)
        current = adapter.read()
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(dispatcher.request(current.selected, current.revision,
            PartsIntent.SetSolo(true))).code)
        assertEquals(before.copy(volume = 1.5f, pan = .2f, isMuted = true, isSoloed = true), model.tracks[1])
        assertFalse(model.tracks[2].isSoloed)
        assertTrue(calls.contains("solo:1:true"))
        assertEquals(2f, adapter.read().parts[1].level!!.parameter.maximum)
        dispatcher.end()
        assertTrue(model.tracks[1].isSoloed)
    }

    @Test fun invalidMixValuesAndOldSelectionCannotMutateModel() {
        var model = GrooveboxState()
        var nativeCalls = 0
        val adapter = LoomPartsAdapter({ model }, { model = it },
            { model = model.copy(selectedTrackIndex = it) },
            { _, _ -> nativeCalls++ }, { _, _ -> nativeCalls++ },
            { _, _ -> nativeCalls++ }, { _, _ -> nativeCalls++ })
        val dispatcher = OperationDispatcher(adapter)
        dispatcher.begin()
        val old = adapter.read()
        model = model.copy(selectedTrackIndex = 1)
        assertEquals(ResultCode.STALE, dispatcher.submit(dispatcher.request(old.selected, old.revision,
            PartsIntent.SetMute(true))).code)
        val current = adapter.read()
        for (value in listOf(-.01f, 2.01f, Float.NaN)) {
            assertEquals(ResultCode.INVALID, dispatcher.submit(dispatcher.request(current.selected, current.revision,
                PartsIntent.SetLevel(value))).code)
        }
        assertEquals(0, nativeCalls)
        assertEquals(.25f, model.tracks[1].volume)
    }
}
