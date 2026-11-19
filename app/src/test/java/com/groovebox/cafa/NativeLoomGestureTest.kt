/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import org.fm1.companion.protocol.Wire6
import org.junit.Assert.*
import org.junit.Test

class NativeLoomGestureTest {
    private class Rig {
        var state = GrooveboxState().let { model ->
            model.copy(tracks = model.tracks.mapIndexed { index, track ->
                if (index == 0) track.copy(engineType = EngineType.FM_DRUM) else track
            })
        }
        var now = 1_000_000_000L
        val playback = mutableListOf<Boolean>()
        val records = mutableListOf<Boolean>()
        val engine = object : NativeLoomSurfaceTest.Engine() {
            override fun playing(value: Boolean) { playback += value }
            override fun recording(value: Boolean) { records += value }
        }
        val surface = NativeLoomSurface({ state }, { state = it }, engine,
            { state = state.copy(selectedTrackIndex = it) }, { true }, { state = it }, {},
            storage = NativeLoomSurfaceTest.EmptyStorage, nowNanos = { now })
        var serial = 0
        var drawn = 0

        init {
            surface.capabilitiesAccepted(setOf(1, 2, 3, 9, 11))
            acknowledge()
            send()
        }

        fun acknowledge() {
            drawn = Wire6.u28(surface.snapshot(), 2)
            surface.acknowledged(drawn)
        }

        fun send(buttons: Int = 0, keys: Int = 0) {
            val bytes = ByteArray(24)
            Wire6.put14(bytes, 0, buttons)
            Wire6.put28(bytes, 2, keys)
            for (slot in 0..6) Wire6.put14(bytes, 6 + slot * 2, 8192)
            Wire6.put28(bytes, 20, drawn)
            assertTrue(surface.input(serial++, bytes))
        }

        fun notes() {
            send(buttons = 1 shl 9)
            acknowledge()
            send()
        }
    }

    @Test fun drumRecTapStartsPlaybackAndRecordingThenDisarmsWithoutStopping() {
        val rig = Rig()
        rig.notes()
        rig.send(buttons = 1 shl 11)
        rig.send()
        assertTrue(rig.state.isPlaying)
        assertTrue(rig.state.isRecording)
        assertEquals(listOf(true), rig.playback)
        assertEquals(listOf(true), rig.records)
        rig.acknowledge()
        rig.send(buttons = 1 shl 11)
        rig.send()
        assertTrue(rig.state.isPlaying)
        assertFalse(rig.state.isRecording)
        assertEquals(listOf(true, false), rig.records)
    }

    @Test fun drumTapReleaseBeforeDisplayAckTogglesOnlyTheOriginalStep() {
        val rig = Rig()
        rig.notes()
        rig.send(keys = 1)
        rig.send()
        assertTrue(rig.state.tracks[0].drumSteps[0][0].active)
        rig.acknowledge()
        rig.send(keys = 1)
        rig.state = rig.state.copy(selectedTrackIndex = 1)
        rig.send()
        assertFalse(rig.state.tracks[1].steps[0].active)
    }

    @Test fun ordinaryEditOnSequenceOpensEditor() {
        val rig = Rig()
        rig.notes()
        rig.send(buttons = 1 shl 4)
        assertEquals(15, rig.surface.snapshot()[6].toInt())
    }
}
