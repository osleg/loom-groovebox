/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.GrooveboxState
import org.fm1.companion.instrument.OperationDispatcher
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.transport.TransportControls
import org.fm1.companion.transport.TransportIntent
import org.junit.Assert.*
import org.junit.Test

class LoomTransportAdapterTest {
    private class Rig {
        var model = GrooveboxState()
        val playback = mutableListOf<Boolean>()
        val recording = mutableListOf<Boolean>()
        val tempos = mutableListOf<Float>()
        val adapter = LoomTransportAdapter({ model }, { model = it }, { playback += it },
            { recording += it }, { tempos += it })
        val dispatcher = OperationDispatcher(adapter)
        val controls = TransportControls(adapter) { target, revision, intent ->
            dispatcher.submit(dispatcher.request(target, revision, intent))
        }
        init { dispatcher.begin() }
    }

    @Test fun startRestartsAtBeginningAndPreservesArmedRecordUntilStop() {
        val r = Rig()
        assertEquals(ResultCode.COMPLETED, r.controls.start(r.controls.state().context).code)
        assertEquals(listOf(true), r.playback)
        r.model = r.model.copy(isRecording = true, selectedTrackIndex = 2)
        assertEquals(ResultCode.COMPLETED, r.controls.start(r.controls.state().context).code)
        assertEquals(listOf(true, false, true), r.playback)
        assertEquals(listOf(true), r.recording)
        assertTrue(r.model.isPlaying); assertTrue(r.model.isRecording)
        val beforeStop = r.controls.state()
        r.model = r.model.copy(selectedTrackIndex = 3)
        val staleStop = r.dispatcher.request(beforeStop.target, beforeStop.revision - 1, TransportIntent.Stop)
        assertEquals(ResultCode.COMPLETED, r.dispatcher.submit(staleStop).code)
        assertEquals(listOf(true, false, true, false), r.playback)
        assertEquals(3, r.model.selectedTrackIndex)
        assertFalse(r.model.isPlaying); assertFalse(r.model.isRecording)
        assertEquals(listOf(true, false), r.recording)
        val armed = Rig()
        armed.model = armed.model.copy(isRecording = true)
        assertEquals(ResultCode.COMPLETED, armed.controls.start(armed.controls.state().context).code)
        assertTrue(armed.model.isPlaying); assertTrue(armed.model.isRecording)
        assertEquals(listOf(true), armed.recording)
    }

    @Test fun staleTempoAndUnsupportedContinueCannotMutateNativePlayback() {
        val r = Rig()
        val context = r.controls.state().context
        r.model = r.model.copy(tempo = 130f)
        assertEquals(ResultCode.STALE, r.controls.adjustTempo(2, context).code)
        assertEquals(ResultCode.UNSUPPORTED, r.controls.resume(r.controls.state().context).code)
        val current = r.controls.state()
        assertEquals(ResultCode.INVALID,
            r.dispatcher.submit(r.dispatcher.request(current.target, current.revision, TransportIntent.SetTempo(241f))).code)
        assertTrue(r.tempos.isEmpty()); assertTrue(r.playback.isEmpty())
    }

    @Test fun externalTempoIsReportedButHardwareEditsRemainBounded() {
        val r = Rig()
        r.model = r.model.copy(tempo = 300f)
        assertEquals(300f, r.controls.state().tempo, 0f)
        assertEquals(ResultCode.COMPLETED, r.controls.adjustTempo(1, r.controls.state().context).code)
        assertEquals(240f, r.model.tempo, 0f)
        assertEquals(listOf(240f), r.tempos)
        assertNull(r.controls.state().position)
    }

    @Test fun endingSessionStopsOwnedPlaybackOnceAndRetiresOldOperations() {
        val r = Rig()
        val state = r.controls.state()
        val old = r.dispatcher.request(state.target, state.revision, TransportIntent.Start)
        assertEquals(ResultCode.COMPLETED, r.dispatcher.submit(old).code)
        r.dispatcher.end()
        assertEquals(listOf(true, false), r.playback)
        assertFalse(r.model.isPlaying)
        assertEquals(ResultCode.STALE, r.dispatcher.submit(old).code)
        r.dispatcher.end()
        assertEquals(listOf(true, false), r.playback)
    }

    @Test fun acceptedSessionCancellationStopsPhoneStartedPlaybackWithoutPriorTransportIntent() {
        val r = Rig()
        r.model = r.model.copy(isPlaying = true)
        r.dispatcher.end()
        assertEquals(listOf(false), r.playback)
        assertFalse(r.model.isPlaying)
        r.dispatcher.begin()
        assertEquals(ResultCode.COMPLETED, r.controls.start(r.controls.state().context).code)
        r.adapter.cancelSession(1)
        assertTrue(r.model.isPlaying)
        r.dispatcher.end()
        assertEquals(listOf(false, true, false), r.playback)
    }

    @Test fun cancellationStopsPlaybackRestartedFromPhoneAfterCompanionStop() {
        val r = Rig()
        assertEquals(ResultCode.COMPLETED, r.controls.stop().code)
        r.model = r.model.copy(isPlaying = true, isRecording = true)
        r.dispatcher.end()
        assertEquals(listOf(false, false), r.playback)
        assertEquals(listOf(false, false), r.recording)
        assertFalse(r.model.isPlaying); assertFalse(r.model.isRecording)
        r.dispatcher.end()
        assertEquals(listOf(false, false), r.playback)
    }

    @Test fun stopAndCancellationWorkWhenTempoCannotBeReported() {
        val r = Rig()
        r.model = r.model.copy(isPlaying = true, tempo = Float.NaN)
        try { r.adapter.read(); fail("Invalid model tempo must not be represented") }
        catch (_: IllegalArgumentException) { }
        val stop = r.dispatcher.request(r.adapter.target, 0, TransportIntent.Stop)
        assertEquals(ResultCode.COMPLETED, r.dispatcher.submit(stop).code)
        assertFalse(r.model.isPlaying)
        r.model = r.model.copy(isPlaying = true)
        r.dispatcher.end()
        assertFalse(r.model.isPlaying)
        assertEquals(listOf(false, false), r.playback)
    }
}
