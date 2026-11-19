/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.sequence

import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.OperationAdapter
import org.fm1.companion.instrument.OperationIntent

enum class SequenceMode { MELODIC, DRUM }

data class SequenceNote(val pitch: Int, val velocity: Float, val offset: Float, val gate: Float) {
    init {
        require(pitch in 0..127 && velocity.isFinite() && velocity in 0f..1f)
        require(offset.isFinite() && offset in 0f..1f && gate.isFinite() && gate > 0f)
    }
}

data class SequenceStep(
    val active: Boolean = false,
    val skipped: Boolean = false,
    val notes: List<SequenceNote> = emptyList(),
    val probability: Float = 1f,
    val ratchet: Int = 1,
    val punch: Boolean = false,
    val subStepOffset: Float = 0f,
    val locks: Map<String, Float> = emptyMap()
) {
    init {
        require(notes.size <= 16 && probability.isFinite() && probability in 0f..1f)
        require(ratchet in 1..16)
        require(subStepOffset.isFinite() && subStepOffset in 0f..1f)
        require(locks.all { it.key.isNotBlank() && it.value.isFinite() })
    }
}

/** Revision covers the entire selected pattern, selection, effective lock controls and transport flags. */
data class SequenceState(
    val target: ObjectRef,
    val revision: Long,
    val mode: SequenceMode,
    val steps: List<SequenceStep>,
    val length: Int,
    val lane: Int,
    val laneCount: Int,
    val scalePitches: List<Int>,
    val maximumNoteGate: Float,
    val playing: Boolean,
    val recording: Boolean,
    val lockControls: List<ParameterBanks.Control?>,
    val maximumRatchet: Int = 16
) {
    init {
        require(target.kind == ObjectRef.Kind.PATTERN && revision >= 0)
        require(steps.size in 1..1024 && length in 1..steps.size)
        require(laneCount in 1..128 && lane in 0 until laneCount)
        require(scalePitches.isNotEmpty() && scalePitches.distinct().size == scalePitches.size)
        require(scalePitches.all { it in 0..127 })
        require(maximumNoteGate.isFinite() && maximumNoteGate > 0f)
        require(maximumRatchet in 1..16)
        require(steps.all { step -> step.notes.all { it.gate <= maximumNoteGate } })
        require(steps.all { it.ratchet <= maximumRatchet })
        require(lockControls.size == 4)
    }
}

/** An index is only meaningful with the SequenceState revision that supplied it. */
data class NoteAddress(val step: Int, val index: Int)

data class NoteEdit(
    val address: NoteAddress,
    val pitch: Int? = null,
    val velocity: Float? = null,
    val offset: Float? = null,
    val gate: Float? = null
)

sealed class SequenceIntent : OperationIntent {
    data class ReplaceNotes(val step: Int, val notes: List<SequenceNote>) : SequenceIntent()
    data class EditNotes(val edits: List<NoteEdit>) : SequenceIntent()
    data class MoveNotes(val addresses: List<NoteAddress>, val stepDelta: Int) : SequenceIntent()
    data class ClearSteps(val indices: List<Int>) : SequenceIntent()
    data class SetStepsActive(val indices: List<Int>, val value: Boolean) : SequenceIntent()
    data class EditSteps(
        val indices: List<Int>, val probability: Float? = null, val ratchet: Int? = null,
        val nudge: Float? = null, val skipped: Boolean? = null, val punch: Boolean? = null
    ) : SequenceIntent()
    data class SetLock(val indices: List<Int>, val parameterId: String, val value: Float) : SequenceIntent()
    /** Adjust each step's current lock, or its effective base control, in one atomic edit. */
    data class AdjustLocks(val indices: List<Int>, val parameterId: String, val delta: Int) : SequenceIntent()
    data class ClearLocks(val indices: List<Int>) : SequenceIntent()
    data class SelectLane(val index: Int) : SequenceIntent()
}

/** Mutations are synchronous and atomic; reject stale or invalid multi-item edits before changing any item. */
interface SequenceAdapter : OperationAdapter {
    fun read(): SequenceState
}
