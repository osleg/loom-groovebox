/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.ScaleType
import com.groovebox.StepState
import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.control.ParameterDescriptor
import org.fm1.companion.instrument.OperationDispatcher
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.sequence.*
import org.junit.Assert.*
import org.junit.Test

class LoomSequenceAdapterTest {
    private class Rig {
        var model = GrooveboxState()
        val writes = mutableListOf<Triple<Int, Int, Int>>()
        var modelWrites = 0
        var selection = -1
        var controls: List<ParameterBanks.Control?> = listOf(
            ParameterBanks.Control(ParameterDescriptor("112", "RES"), .2f), null, null, null)
        val adapter = LoomSequenceAdapter({ model }, { model = it; modelWrites++ },
            { track, lane, step, _ -> writes += Triple(track, lane, step) },
            { lane -> selection = lane; model = model.copy(tracks = model.tracks.mapIndexed { index, track ->
                if (index == model.selectedTrackIndex) track.copy(selectedFmDrumInstrument = lane) else track
            }) }, { _, _ -> controls })
        val dispatcher = OperationDispatcher(adapter).also { it.begin() }
        fun submit(intent: SequenceIntent, snapshot: SequenceState = adapter.read()) =
            dispatcher.submit(dispatcher.request(snapshot.target, snapshot.revision, intent))
        fun step(value: StepState, index: Int = 0) {
            model = model.copy(tracks = model.tracks.mapIndexed { i, track -> if (i == 0)
                track.copy(steps = track.steps.mapIndexed { s, prior -> if (s == index) value else prior }) else track })
        }
    }

    @Test fun melodicAndDrumWritesUseActualSelectedLane() {
        val rig = Rig()
        assertEquals(SequenceMode.MELODIC, rig.adapter.read().mode)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.ReplaceNotes(0,
            listOf(SequenceNote(64, .6f, .1f, 2f)))).code)
        assertEquals(Triple(0, -1, 0), rig.writes.last())
        assertEquals(listOf(64), rig.model.tracks[0].steps[0].notes)
        rig.model = rig.model.copy(tracks = rig.model.tracks.mapIndexed { i, track ->
            if (i == 0) track.copy(engineType = EngineType.FM_DRUM) else track })
        val drum = rig.adapter.read()
        assertEquals(SequenceMode.DRUM, drum.mode)
        assertEquals(8, drum.laneCount)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.SelectLane(3), drum).code)
        assertEquals(3, rig.selection)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.SetStepsActive(listOf(1), true)).code)
        assertEquals(Triple(0, 3, 1), rig.writes.last())
        assertEquals(listOf(63), rig.adapter.read().steps[1].notes.map { it.pitch })
        assertTrue(rig.model.tracks[0].drumSteps[3][1].notes.isEmpty())
    }

    @Test fun independentChordNoteEditsAndMovesPreserveOtherNotesAndStepLocks() {
        val rig = Rig()
        val chord = StepState(active = true, notes = listOf(60, 64), noteVelocities = listOf(.2f, .8f),
            noteOffsets = listOf(.1f, .2f), noteGates = listOf(2f, 3f),
            parameterLocks = mapOf(112 to .4f), probability = .7f)
        rig.step(chord)
        rig.step(StepState(active = true, notes = listOf(67), parameterLocks = mapOf(113 to .3f)), 1)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.EditNotes(listOf(
            NoteEdit(NoteAddress(0, 1), gate = 4f)))).code)
        assertEquals(listOf(2f, 4f), rig.model.tracks[0].steps[0].noteGates)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.MoveNotes(listOf(NoteAddress(0, 1)), 1)).code)
        val source = rig.model.tracks[0].steps[0]
        val destination = rig.model.tracks[0].steps[1]
        assertEquals(listOf(60), source.notes)
        assertEquals(listOf(67, 64), destination.notes)
        assertEquals(listOf(.8f, .8f), destination.noteVelocities)
        assertEquals(listOf(1f, 4f), destination.noteGates)
        assertEquals(mapOf(112 to .4f), source.parameterLocks)
        assertEquals(mapOf(113 to .3f), destination.parameterLocks)
        assertEquals(.7f, source.probability)
        rig.step(StepState(active = true, notes = listOf(60)), 2)
        val writesBeforeCollision = rig.writes.size
        assertEquals(ResultCode.INVALID, rig.submit(SequenceIntent.MoveNotes(listOf(NoteAddress(0, 0)), 2)).code)
        assertEquals(writesBeforeCollision, rig.writes.size)
        assertEquals(listOf(60), rig.model.tracks[0].steps[0].notes)
    }

    @Test fun heldStepLocksDoNotChangeBaseParameterAndOnlyClearSelectedSteps() {
        val rig = Rig()
        val base = rig.model.tracks[0].parameters
        rig.step(StepState(active = true, notes = listOf(60), parameterLocks = mapOf(114 to .5f)), 1)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.SetLock(listOf(0), "112", .9f)).code)
        assertEquals(base, rig.model.tracks[0].parameters)
        assertEquals(mapOf(112 to .9f), rig.model.tracks[0].steps[0].parameterLocks)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.ClearLocks(listOf(0))).code)
        assertTrue(rig.model.tracks[0].steps[0].parameterLocks.isEmpty())
        assertEquals(mapOf(114 to .5f), rig.model.tracks[0].steps[1].parameterLocks)
    }

    @Test fun editingNotesOnInactiveChordPreservesItsInactiveStateAndFeel() {
        val rig = Rig()
        val inactive = StepState(active = false, notes = listOf(60, 64),
            noteVelocities = listOf(.3f, .6f), noteGates = listOf(1f, 2f),
            probability = .45f, ratchet = 3, parameterLocks = mapOf(112 to .7f))
        rig.step(inactive)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.EditNotes(listOf(
            NoteEdit(NoteAddress(0, 0), pitch = 61, gate = 1.5f),
            NoteEdit(NoteAddress(0, 1), velocity = .8f)))).code)
        val edited = rig.model.tracks[0].steps[0]
        assertFalse(edited.active)
        assertEquals(listOf(61, 64), edited.notes)
        assertEquals(listOf(.3f, .8f), edited.noteVelocities)
        assertEquals(listOf(1.5f, 2f), edited.noteGates)
        assertEquals(inactive.probability, edited.probability)
        assertEquals(inactive.ratchet, edited.ratchet)
        assertEquals(inactive.parameterLocks, edited.parameterLocks)
    }

    @Test fun relativeHeldLockBatchUsesEachStepsValueAndRejectsFullSecondStepAtomically() {
        val rig = Rig()
        rig.step(StepState(parameterLocks = mapOf(112 to .2f)))
        rig.step(StepState(parameterLocks = mapOf(112 to .7f)), 1)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.AdjustLocks(listOf(0, 1), "112", 10)).code)
        assertEquals(.3f, rig.model.tracks[0].steps[0].parameterLocks[112]!!, .0001f)
        assertEquals(.8f, rig.model.tracks[0].steps[1].parameterLocks[112]!!, .0001f)
        rig.step(StepState(parameterLocks = mapOf(113 to .1f, 114 to .2f, 115 to .3f, 116 to .4f)), 1)
        val before = rig.model.tracks[0].steps.take(2)
        val writes = rig.writes.size
        val modelWrites = rig.modelWrites
        assertEquals(ResultCode.INVALID, rig.submit(SequenceIntent.AdjustLocks(listOf(0, 1), "112", 1)).code)
        assertEquals(before, rig.model.tracks[0].steps.take(2))
        assertEquals(writes, rig.writes.size)
        assertEquals(modelWrites, rig.modelWrites)
    }

    @Test fun movingInactiveNotesPreservesStepStatesAndRejectsMixedActivation() {
        val rig = Rig()
        rig.step(StepState(active = false, notes = listOf(60, 64),
            noteVelocities = listOf(.3f, .7f), parameterLocks = mapOf(112 to .4f)))
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.MoveNotes(listOf(NoteAddress(0, 1)), 1)).code)
        assertFalse(rig.model.tracks[0].steps[0].active)
        assertFalse(rig.model.tracks[0].steps[1].active)
        assertEquals(listOf(60), rig.model.tracks[0].steps[0].notes)
        assertEquals(listOf(64), rig.model.tracks[0].steps[1].notes)
        assertEquals(listOf(.7f), rig.model.tracks[0].steps[1].noteVelocities)
        assertEquals(mapOf(112 to .4f), rig.model.tracks[0].steps[0].parameterLocks)
        rig.step(StepState(active = true, notes = listOf(67)), 2)
        val before = rig.model.tracks[0].steps.take(3)
        val writes = rig.writes.size
        assertEquals(ResultCode.INVALID, rig.submit(SequenceIntent.MoveNotes(listOf(NoteAddress(1, 0)), 1)).code)
        assertEquals(before, rig.model.tracks[0].steps.take(3))
        assertEquals(writes, rig.writes.size)
    }

    @Test fun exportedMidiScaleTracksRootAndModeAndRetiresOldEdits() {
        val rig = Rig()
        val major = rig.adapter.read()
        val cMajor = setOf(0, 2, 4, 5, 7, 9, 11)
        assertEquals((0..127).filter { it % 12 in cMajor }, major.scalePitches)
        rig.model = rig.model.copy(rootNote = 50, scaleType = ScaleType.NATURAL_MINOR)
        val minor = rig.adapter.read()
        val dMinor = setOf(0, 2, 4, 5, 7, 9, 10)
        assertEquals((0..127).filter { it % 12 in dMinor }, minor.scalePitches)
        assertNotEquals(major.revision, minor.revision)
        assertEquals(ResultCode.STALE, rig.submit(SequenceIntent.SetStepsActive(listOf(0), true), major).code)
        assertTrue(rig.writes.isEmpty())
        assertEquals(0, rig.modelWrites)
    }

    @Test fun feelNudgeMovesPopulatedNoteOffsetsWithoutChangingGatesOrLocks() {
        val rig = Rig()
        val step = StepState(active = true, notes = listOf(60, 64), subStepOffset = .1f,
            noteOffsets = listOf(.1f, .2f), noteGates = listOf(1.5f, 2f),
            parameterLocks = mapOf(112 to .7f))
        rig.step(step)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.EditSteps(listOf(0), nudge = .2f)).code)
        val changed = rig.model.tracks[0].steps[0]
        assertEquals(.2f, changed.subStepOffset, .0001f)
        assertEquals(.2f, changed.noteOffsets[0], .0001f)
        assertEquals(.3f, changed.noteOffsets[1], .0001f)
        assertEquals(step.noteGates, changed.noteGates)
        assertEquals(step.parameterLocks, changed.parameterLocks)
    }

    @Test fun absentOrOutOfRangeLockIdsNeverReachTheNativeStepWriter() {
        val rig = Rig()
        rig.controls = listOf(ParameterBanks.Control(ParameterDescriptor("2500", "BAD"), .5f), null, null, null)
        assertEquals(ResultCode.UNSUPPORTED, rig.submit(SequenceIntent.SetLock(listOf(0), "2500", .5f)).code)
        assertEquals(ResultCode.UNSUPPORTED, rig.submit(SequenceIntent.AdjustLocks(listOf(0), "2500", 1)).code)
        rig.controls = listOf(null, null, null, null)
        assertEquals(ResultCode.UNSUPPORTED, rig.submit(SequenceIntent.SetLock(listOf(0), "999", .5f)).code)
        assertEquals(0, rig.modelWrites)
        assertTrue(rig.writes.isEmpty())
    }

    @Test fun staleInvalidUnsupportedAndCancelledEditsHaveNoEffects() {
        val rig = Rig()
        val old = rig.adapter.read()
        rig.step(StepState(active = true, notes = listOf(60)))
        assertEquals(ResultCode.STALE, rig.submit(SequenceIntent.ClearSteps(listOf(0)), old).code)
        val current = rig.adapter.read()
        assertEquals(ResultCode.INVALID, rig.submit(SequenceIntent.MoveNotes(
            listOf(NoteAddress(0, 0)), 999), current).code)
        assertEquals(ResultCode.UNSUPPORTED, rig.submit(SequenceIntent.SetLock(listOf(0), "999", .5f)).code)
        rig.controls = listOf(null, null, null, null)
        assertEquals(ResultCode.STALE, rig.submit(SequenceIntent.SetLock(listOf(0), "112", .5f), current).code)
        assertEquals(ResultCode.UNSUPPORTED, rig.submit(SequenceIntent.SetLock(listOf(0), "112", .5f)).code)
        assertEquals(0, rig.modelWrites)
        assertTrue(rig.writes.isEmpty())
        val request = rig.dispatcher.request(rig.adapter.read().target, rig.adapter.read().revision,
            SequenceIntent.ClearSteps(listOf(0)))
        rig.dispatcher.end()
        assertEquals(ResultCode.STALE, rig.dispatcher.submit(request).code)
        assertEquals(0, rig.modelWrites)
    }

    @Test fun playbackRejectsEditsButAllowsLaneSelection() {
        val rig = Rig()
        rig.model = rig.model.copy(isPlaying = true, tracks = rig.model.tracks.mapIndexed { i, track ->
            if (i == 0) track.copy(engineType = EngineType.SAMPLER, parameters = track.parameters + (320 to .6f)) else track })
        assertEquals(16, rig.adapter.read().laneCount)
        assertEquals(ResultCode.BUSY, rig.submit(SequenceIntent.ClearSteps(listOf(0))).code)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.SelectLane(10)).code)
        assertEquals(10, rig.selection)
        assertTrue(rig.writes.isEmpty())
    }

    @Test fun liveDrumGridWritesSelectedLaneWhilePlaybackContinuesAndRejectsRecordingEdits() {
        val rig = Rig()
        rig.model = rig.model.copy(isPlaying = true, tracks = rig.model.tracks.mapIndexed { i, track ->
            if (i == 0) track.copy(engineType = EngineType.FM_DRUM) else track })
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.SelectLane(2)).code)
        assertEquals(ResultCode.COMPLETED, rig.submit(SequenceIntent.ReplaceNotes(1,
            listOf(SequenceNote(62, .8f, 0f, 1f)))).code)
        assertEquals(Triple(0, 2, 1), rig.writes.single())
        assertTrue(rig.model.tracks[0].drumSteps[2][1].active)
        assertTrue(rig.model.isPlaying)
        rig.model = rig.model.copy(isRecording = true)
        assertEquals(ResultCode.BUSY, rig.submit(SequenceIntent.SetStepsActive(listOf(1), false)).code)
        assertTrue(rig.model.tracks[0].drumSteps[2][1].active)
    }
}
