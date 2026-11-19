/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import org.fm1.companion.instrument.InstrumentIntent
import org.fm1.companion.instrument.Operation
import org.fm1.companion.instrument.ResultCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoInstrumentTest {
    @Test fun a4RendersAtConcertPitchForOneSecond() {
        val synth = Synthesizer()
        synth.setParameter("attack_ms", 0f)
        assertTrue(synth.noteOn(1, 1, 69, 127))
        var previous = 0.toShort()
        var crossings = 0
        var remaining = 44100
        while (remaining > 0) {
            val frames = minOf(remaining, 4096)
            val pcm = synth.render(frames)
            for (frame in 0 until frames) {
                val sample = pcm[frame * 2]
                if (previous <= 0 && sample > 0) crossings++
                previous = sample
            }
            remaining -= frames
        }
        assertTrue("A4 should complete about 440 cycles in one second: $crossings", crossings in 439..441)
    }

    @Test fun rendersThreeWaveformsAndSilencesReleasedOrCancelledVoices() {
        val synth = Synthesizer()
        synth.setParameter("attack_ms", 0f)
        synth.setParameter("release_ms", 5f)
        for (wave in 0..2) {
            assertTrue(synth.setParameter("waveform", wave.toFloat()))
            assertTrue(synth.noteOn(1, wave.toLong() + 1, 69, 100))
            assertTrue(synth.render(512).any { it != 0.toShort() })
            assertTrue(synth.noteOff(1, wave.toLong() + 1))
            synth.render(512)
            assertEquals(0, synth.voiceCount())
            assertTrue(synth.render(256).all { it == 0.toShort() })
        }
        assertTrue(synth.noteOn(2, 10, 60, 100))
        assertTrue(synth.render(256).any { it != 0.toShort() })
        synth.cancel(2)
        assertTrue(synth.render(256).all { it == 0.toShort() })
    }

    @Test fun rejectsStaleEditAndReleasesOriginalVoiceAfterRevisionChanges() {
        val adapter = DemoInstrumentAdapter()
        val initial = adapter.read()
        val target = initial.selected
        val edit = Operation(4, 1, target, initial.revision,
            InstrumentIntent.SetParameter("level", .25f))
        assertEquals(ResultCode.COMPLETED, adapter.execute(edit) {}.code)
        val revision = adapter.read().revision
        assertTrue(revision > initial.revision)
        assertEquals(ResultCode.STALE, adapter.execute(edit.copy(id = 2)) {}.code)
        assertEquals(.25f, adapter.value("level"))
        assertEquals(revision, adapter.read().revision)
        assertEquals(ResultCode.COMPLETED, adapter.execute(Operation(4, 3, target, revision,
            InstrumentIntent.NoteOn(7, 69, 100))) {}.code)
        assertTrue(adapter.synth.render(1024).any { it != 0.toShort() })
        assertEquals(ResultCode.COMPLETED, adapter.execute(Operation(4, 4, target, revision,
            InstrumentIntent.SetParameter("release_ms", 5f))) {}.code)
        assertTrue(adapter.read().revision > revision)
        assertEquals(ResultCode.COMPLETED, adapter.execute(Operation(4, 5, target, revision,
            InstrumentIntent.NoteOff(7))) {}.code)
        adapter.synth.render(512)
        assertEquals(0, adapter.synth.voiceCount())
        assertTrue(adapter.synth.render(256).all { it == 0.toShort() })
    }

    @Test fun boundedAdmissionReportsFailureAndCancelClearsSession() {
        val adapter = DemoInstrumentAdapter()
        val state = adapter.read()
        for (id in 1L..Synthesizer.MAX_VOICES.toLong())
            assertEquals(ResultCode.COMPLETED, adapter.execute(Operation(9, id, state.selected,
                state.revision, InstrumentIntent.NoteOn(id, 60, 100))) {}.code)
        assertEquals(ResultCode.BUSY, adapter.execute(Operation(9, 99, state.selected,
            state.revision, InstrumentIntent.NoteOn(99, 60, 100))) {}.code)
        assertEquals(Synthesizer.MAX_VOICES, adapter.synth.voiceCount())
        adapter.cancelSession(9)
        assertEquals(0, adapter.synth.voiceCount())
        assertFalse(adapter.synth.render(256).any { it != 0.toShort() })
    }
}
