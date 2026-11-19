/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.ScaleType
import com.groovebox.StepState
import com.groovebox.TrackState
import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.Operation
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.sequence.*

/** Synchronous sequence edits on LOOM's model owner thread. Lane -1 is the melodic sequence. */
class LoomSequenceAdapter(
    private val readModel: () -> GrooveboxState,
    private val writeModel: (GrooveboxState) -> Unit,
    private val writeStep: (trackIndex: Int, lane: Int, step: Int, value: StepState) -> Unit,
    private val selectLane: (Int) -> Unit,
    private val lockControls: (GrooveboxState, TrackState) -> List<ParameterBanks.Control?>
) : SequenceAdapter {
    override val namespace = "com.groovebox"
    private data class SnapshotKey(
        val target: ObjectRef, val engine: EngineType, val catalogEntryId: String?,
        val mode: SequenceMode, val steps: List<StepState>,
        val length: Int, val lane: Int, val controls: List<ParameterBanks.Control?>,
        val scaleType: ScaleType, val rootNote: Int,
        val playing: Boolean, val recording: Boolean
    )
    private var previous: SnapshotKey? = null
    private var revision = 0L
    private var latestGeneration = -1L
    private var cancelledGeneration = -1L

    private fun isDrum(track: TrackState): Boolean =
        track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM ||
            track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) >= .5f

    private fun laneCount(track: TrackState): Int = if (track.engineType == EngineType.SAMPLER) 16 else 8

    private fun source(track: TrackState, drum: Boolean, lane: Int): List<StepState> =
        if (drum) track.drumSteps.getOrNull(lane).orEmpty() else track.steps

    private fun note(step: StepState, index: Int, pitch: Int): SequenceNote = SequenceNote(
        pitch.coerceIn(0, 127),
        (step.noteVelocities.getOrNull(index) ?: step.velocity).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .8f,
        (step.noteOffsets.getOrNull(index) ?: step.subStepOffset).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
        (step.noteGates.getOrNull(index) ?: step.gate).takeIf { it.isFinite() }?.coerceIn(.01f, 64f) ?: 1f
    )

    private fun view(step: StepState, drumPitch: Int?): SequenceStep {
        val pitches = if (drumPitch != null && step.active && step.notes.isEmpty()) listOf(drumPitch) else step.notes
        return SequenceStep(
            active = step.active, skipped = step.isSkipped,
            notes = pitches.take(16).mapIndexed { index, pitch -> note(step, index, pitch) },
            probability = step.probability.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
            ratchet = step.ratchet.coerceIn(1, 9), punch = step.punch,
            subStepOffset = step.subStepOffset.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
            locks = step.parameterLocks.mapKeys { it.key.toString() }
        )
    }

    private fun state(model: GrooveboxState): SequenceState {
        require(model.selectedTrackIndex in model.tracks.indices)
        val track = model.tracks[model.selectedTrackIndex]
        val drum = isDrum(track)
        val count = if (drum) laneCount(track) else 1
        val lane = if (drum) track.selectedFmDrumInstrument.coerceIn(0, count - 1) else 0
        val steps = source(track, drum, lane).ifEmpty { listOf(StepState()) }
        val target = ObjectRef(namespace, ObjectRef.Kind.PATTERN,
            "${track.id}/${if (drum) "lane/$lane" else "main"}")
        val controls = lockControls(model, track).let { List(4) { index -> it.getOrNull(index) } }
        val length = track.patternLength.coerceIn(1, steps.size)
        val key = SnapshotKey(target, track.engineType, track.catalogEntryId,
            if (drum) SequenceMode.DRUM else SequenceMode.MELODIC,
            steps, length, lane, controls, model.scaleType, model.rootNote,
            model.isPlaying, model.isRecording)
        if (key != previous) {
            check(revision < Long.MAX_VALUE) { "Sequence revision exhausted; recreate the adapter" }
            revision++
            previous = key
        }
        val scalePitches = (0..127).filter { pitch ->
            Math.floorMod(pitch - model.rootNote, 12) in model.scaleType.intervals
        }
        return SequenceState(target, revision, key.mode,
            steps.map { view(it, if (drum) 60 + lane else null) }, length, lane, count,
            scalePitches, 64f, model.isPlaying, model.isRecording, controls,
            maximumRatchet = 9)
    }

    override fun read(): SequenceState = state(readModel())

    private fun validNotes(notes: List<SequenceNote>, drumPitch: Int?): Boolean =
        notes.size <= 16 && notes.map { it.pitch }.distinct().size == notes.size &&
            (drumPitch == null || notes.all { it.pitch == drumPitch }) &&
            notes.all { it.pitch in 0..127 && it.velocity.isFinite() && it.velocity in 0f..1f &&
                it.offset.isFinite() && it.offset in 0f..1f && it.gate.isFinite() && it.gate in .01f..64f }

    private fun withNotes(step: StepState, notes: List<SequenceNote>, active: Boolean = notes.isNotEmpty()): StepState = step.copy(
        active = active, notes = notes.map { it.pitch },
        noteVelocities = notes.map { it.velocity }, noteOffsets = notes.map { it.offset },
        noteGates = notes.map { it.gate }
    )

    override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
        if (operation.generation <= cancelledGeneration || operation.generation < latestGeneration)
            return OperationResult(ResultCode.STALE)
        val intent = operation.intent as? SequenceIntent ?: return OperationResult(ResultCode.UNSUPPORTED)
        val model = readModel()
        val current = state(model)
        if (operation.target != current.target || operation.expectedRevision != current.revision)
            return OperationResult(ResultCode.STALE)
        latestGeneration = operation.generation
        val trackIndex = model.selectedTrackIndex
        val track = model.tracks[trackIndex]
        val drum = current.mode == SequenceMode.DRUM
        val lane = if (drum) current.lane else -1
        if (intent is SequenceIntent.SelectLane) {
            if (!drum) return OperationResult(ResultCode.UNSUPPORTED)
            if (intent.index !in 0 until current.laneCount) return OperationResult(ResultCode.INVALID)
            if (intent.index != current.lane) selectLane(intent.index)
            return OperationResult(ResultCode.COMPLETED, read().revision)
        }
        val liveDrumGrid = drum && model.isPlaying && !model.isRecording &&
            (intent is SequenceIntent.SetStepsActive || intent is SequenceIntent.ReplaceNotes)
        if ((model.isPlaying || model.isRecording) && !liveDrumGrid) return OperationResult(ResultCode.BUSY)
        val source = source(track, drum, current.lane)
        val changed = source.toMutableList()
        val drumPitch = if (drum) 60 + current.lane else null
        fun validIndices(indices: List<Int>) = indices.isNotEmpty() && indices.distinct().size == indices.size &&
            indices.all { it in 0 until current.length }
        fun update(index: Int, value: StepState) { changed[index] = value }
        val result = when (intent) {
            is SequenceIntent.ReplaceNotes -> {
                if (intent.step !in 0 until current.length || !validNotes(intent.notes, drumPitch)) ResultCode.INVALID
                else { update(intent.step, withNotes(source[intent.step], intent.notes)); ResultCode.COMPLETED }
            }
            is SequenceIntent.EditNotes -> {
                if (intent.edits.isEmpty() || intent.edits.map { it.address }.distinct().size != intent.edits.size ||
                    intent.edits.any { it.address.step !in 0 until current.length ||
                        it.address.index !in current.steps[it.address.step].notes.indices }) ResultCode.INVALID
                else {
                    var valid = true
                    for ((stepIndex, edits) in intent.edits.groupBy { it.address.step }) {
                        val notes = current.steps[stepIndex].notes.toMutableList()
                        for (edit in edits) {
                            val old = notes[edit.address.index]
                            val pitch = edit.pitch ?: old.pitch
                            val velocity = edit.velocity ?: old.velocity
                            val offset = edit.offset ?: old.offset
                            val gate = edit.gate ?: old.gate
                            if (pitch !in 0..127 || velocity.isNaN() || velocity !in 0f..1f ||
                                offset.isNaN() || offset !in 0f..1f || gate.isNaN() || gate !in .01f..64f ||
                                (drumPitch != null && pitch != drumPitch)) {
                                valid = false; break
                            }
                            notes[edit.address.index] = SequenceNote(pitch, velocity, offset, gate)
                        }
                        if (!valid || !validNotes(notes, drumPitch)) { valid = false; break }
                        update(stepIndex, withNotes(source[stepIndex], notes, source[stepIndex].active))
                    }
                    if (valid) ResultCode.COMPLETED else ResultCode.INVALID
                }
            }
            is SequenceIntent.MoveNotes -> {
                if (intent.addresses.isEmpty() || intent.addresses.distinct().size != intent.addresses.size ||
                    intent.addresses.any { it.step !in 0 until current.length ||
                        it.index !in current.steps[it.step].notes.indices ||
                        it.step.toLong() + intent.stepDelta !in 0L until current.length.toLong() }) ResultCode.INVALID
                else {
                    val bySource = intent.addresses.groupBy { it.step }
                    val byDestination = intent.addresses.groupBy { it.step + intent.stepDelta }
                    val affected = (bySource.keys + byDestination.keys).toSet()
                    var valid = true
                    for (index in affected) {
                        val original = current.steps[index].notes
                        val remaining = original.filterIndexed { noteIndex, _ -> bySource[index].orEmpty().none { it.index == noteIndex } }
                        val incoming = byDestination[index].orEmpty().map { current.steps[it.step].notes[it.index] }
                        val notes = remaining + incoming
                        val arrivingActive = byDestination[index].orEmpty().map { source[it.step].active }.distinct()
                        if (!validNotes(notes, drumPitch) || arrivingActive.size > 1 ||
                            remaining.isNotEmpty() && arrivingActive.any { it != source[index].active }) {
                            valid = false; break
                        }
                        val active = if (notes.isEmpty()) false else arrivingActive.firstOrNull() ?: source[index].active
                        update(index, withNotes(source[index], notes, active))
                    }
                    if (valid) ResultCode.COMPLETED else ResultCode.INVALID
                }
            }
            is SequenceIntent.ClearSteps -> if (!validIndices(intent.indices)) ResultCode.INVALID else {
                intent.indices.forEach { update(it, StepState()) }; ResultCode.COMPLETED
            }
            is SequenceIntent.SetStepsActive -> if (!validIndices(intent.indices)) ResultCode.INVALID else {
                intent.indices.forEach { update(it, source[it].copy(active = intent.value)) }; ResultCode.COMPLETED
            }
            is SequenceIntent.EditSteps -> {
                if (!validIndices(intent.indices) ||
                    intent.probability?.let { !it.isFinite() || it !in 0f..1f } == true ||
                    intent.ratchet?.let { it !in 1..9 } == true ||
                    intent.nudge?.let { !it.isFinite() || it !in 0f..1f } == true) ResultCode.INVALID
                else {
                    intent.indices.forEach { index ->
                        val old = source[index]
                        val nudge = intent.nudge ?: old.subStepOffset
                        val offsetDelta = nudge - old.subStepOffset
                        update(index, old.copy(probability = intent.probability ?: old.probability,
                            ratchet = intent.ratchet ?: old.ratchet,
                            subStepOffset = nudge,
                            noteOffsets = if (intent.nudge == null) old.noteOffsets else old.noteOffsets.map {
                                (it + offsetDelta).coerceIn(0f, 1f)
                            },
                            isSkipped = intent.skipped ?: old.isSkipped,
                            punch = intent.punch ?: old.punch))
                    }
                    ResultCode.COMPLETED
                }
            }
            is SequenceIntent.SetLock -> {
                val control = current.lockControls.filterNotNull().firstOrNull { it.parameter.id == intent.parameterId }
                val id = intent.parameterId.toIntOrNull()
                if (!validIndices(intent.indices)) ResultCode.INVALID
                else if (control == null || id == null || id !in 0..2499) ResultCode.UNSUPPORTED
                else if (!intent.value.isFinite() || intent.value !in control.parameter.minimum..control.parameter.maximum ||
                    intent.indices.any { id !in source[it].parameterLocks && source[it].parameterLocks.size >= 4 }) ResultCode.INVALID
                else {
                    intent.indices.forEach { update(it, source[it].copy(parameterLocks = source[it].parameterLocks + (id to intent.value))) }
                    ResultCode.COMPLETED
                }
            }
            is SequenceIntent.AdjustLocks -> {
                val control = current.lockControls.filterNotNull().firstOrNull { it.parameter.id == intent.parameterId }
                val id = intent.parameterId.toIntOrNull()
                if (!validIndices(intent.indices)) ResultCode.INVALID
                else if (control == null || id == null || id !in 0..2499) ResultCode.UNSUPPORTED
                else if (intent.indices.any { id !in source[it].parameterLocks && source[it].parameterLocks.size >= 4 }) ResultCode.INVALID
                else {
                    val values = intent.indices.associateWith { index ->
                        control.parameter.adjusted(source[index].parameterLocks[id] ?: control.value, intent.delta)
                    }
                    values.forEach { (index, value) ->
                        update(index, source[index].copy(parameterLocks = source[index].parameterLocks + (id to value)))
                    }
                    ResultCode.COMPLETED
                }
            }
            is SequenceIntent.ClearLocks -> if (!validIndices(intent.indices)) ResultCode.INVALID else {
                intent.indices.forEach { update(it, source[it].copy(parameterLocks = emptyMap())) }; ResultCode.COMPLETED
            }
            is SequenceIntent.SelectLane -> error("handled above")
        }
        if (result != ResultCode.COMPLETED) return OperationResult(result)
        if (changed.indices.any { changed[it] != source[it] && changed[it].parameterLocks.keys.any { id -> id !in 0..2499 } })
            return OperationResult(ResultCode.UNSUPPORTED)
        val updatedTrack = if (drum) track.copy(drumSteps = track.drumSteps.mapIndexed { index, old ->
            if (index == current.lane) changed else old
        }) else track.copy(steps = changed)
        changed.indices.filter { changed[it] != source[it] }.forEach { writeStep(trackIndex, lane, it, changed[it]) }
        if (updatedTrack != track) writeModel(model.copy(tracks = model.tracks.mapIndexed { index, old ->
            if (index == trackIndex) updatedTrack else old
        }))
        return OperationResult(ResultCode.COMPLETED, read().revision)
    }

    override fun cancelSession(generation: Long) {
        if (generation > cancelledGeneration) cancelledGeneration = generation
    }
}
