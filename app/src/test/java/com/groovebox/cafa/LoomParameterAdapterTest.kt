/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.GrooveboxState
import org.fm1.companion.control.ParameterBanks
import org.junit.Assert.assertEquals
import org.junit.Test

class LoomParameterAdapterTest {
    @Test fun phoneEditsAndTrackChangesInvalidatePendingParameterEdits() {
        var state = GrooveboxState()
        var nativeWrites = 0
        val adapter = LoomParameterAdapter({ state }, { state = it },
            { _, _ -> listOf(CafaCatalog.Control(112, "CUTOFF", 1f)) }, { 1 },
            { _, _, _ -> nativeWrites++ })
        val original = adapter.read()
        val edit = ParameterBanks.Edit(original.targetId, original.revision, "112", .3f)

        state = state.copy(tracks = state.tracks.mapIndexed { i, track ->
            if (i == 0) track.copy(parameters = track.parameters + (112 to .7f)) else track
        })
        assertEquals(ParameterBanks.Result.STALE, adapter.apply(edit))
        assertEquals(.7f, state.tracks[0].parameters[112]!!, 0f)

        val refreshed = adapter.read()
        state = state.copy(selectedTrackIndex = 1)
        assertEquals(ParameterBanks.Result.STALE,
            adapter.apply(edit.copy(targetId = refreshed.targetId, expectedRevision = refreshed.revision)))
        assertEquals(0, nativeWrites)
    }

    @Test fun invalidOrUnexposedParametersNeverReachTheEngine() {
        var state = GrooveboxState()
        val original = state
        var nativeWrites = 0
        val adapter = LoomParameterAdapter({ state }, { state = it },
            { _, _ -> listOf(CafaCatalog.Control(112, "CUTOFF", 1f)) }, { 1 },
            { _, _, _ -> nativeWrites++ })
        val current = adapter.read()
        val edit = ParameterBanks.Edit(current.targetId, current.revision, "112", .3f)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 2f))
            assertEquals(ParameterBanks.Result.INVALID, adapter.apply(edit.copy(value = invalid)))
        assertEquals(ParameterBanks.Result.UNSUPPORTED, adapter.apply(edit.copy(parameterId = "unexposed")))
        assertEquals(original, state)
        assertEquals(0, nativeWrites)
    }
}
