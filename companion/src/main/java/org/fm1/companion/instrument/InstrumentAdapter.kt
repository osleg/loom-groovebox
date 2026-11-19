/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.instrument

import org.fm1.companion.control.ParameterBanks

/** Stable application identity; neither a display name nor a catalog offset. */
data class ObjectRef(val namespace: String, val kind: Kind, val id: String) {
    enum class Kind { INSTRUMENT, PARAMETER, TRANSPORT, PART, PATTERN }
    init {
        require(namespace.length in 1..64 && namespace.all { it.code in 33..126 })
        require(id.isNotEmpty() && id.toByteArray(Charsets.UTF_8).size <= 128)
    }
}

data class InstrumentDescriptor(val ref: ObjectRef, val name: String)

/** An immutable snapshot of the currently selected instrument, read on its model-owner thread. */
data class InstrumentState(
    val instruments: List<InstrumentDescriptor>,
    val selected: ObjectRef,
    val revision: Long,
    val controls: List<ParameterBanks.Control?>,
    val minimumNote: Int = 0,
    val maximumNote: Int = 127,
    val velocity: Int = 100
) {
    init {
        require(instruments.size in 1..16383 && instruments.map { it.ref }.distinct().size == instruments.size)
        require(instruments.all { it.ref.kind == ObjectRef.Kind.INSTRUMENT && it.ref.namespace == selected.namespace })
        require(instruments.any { it.ref == selected } && revision >= 0)
        require(controls.size <= 65532)
        require(minimumNote in 0..127 && maximumNote in minimumNote..127 && velocity in 1..127)
    }
}

/** Intent families are validated by the dispatcher before reaching an application. */
interface OperationIntent

sealed class InstrumentIntent : OperationIntent {
    data class SetParameter(val parameterId: String, val value: Float) : InstrumentIntent()
    data class SelectInstrument(val instrument: ObjectRef) : InstrumentIntent()
    data class NoteOn(val voiceId: Long, val note: Int, val velocity: Int) : InstrumentIntent()
    data class NoteOff(val voiceId: Long) : InstrumentIntent()
}

data class Operation(
    val generation: Long,
    val id: Long,
    val target: ObjectRef,
    val expectedRevision: Long,
    val intent: OperationIntent
)

enum class ResultCode { COMPLETED, ACCEPTED_PENDING, UNSUPPORTED, INVALID, STALE, BUSY, FAILED, CANCELLED }

/** COMPLETED revisions describe the state available through read(), never optimistic UI state. */
data class OperationResult(val code: ResultCode, val revision: Long? = null, val message: String = "")

/** All methods and completion callbacks run on one model-owner thread (Android main thread). */
interface OperationAdapter {
    val namespace: String
    fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult
    fun cancelSession(generation: Long)
}

interface InstrumentAdapter : OperationAdapter {
    fun read(): InstrumentState

    /** Atomically check target/revision and apply. NoteOn/NoteOff must complete synchronously;
     * NoteOff must release its original voice even after selection/revision changes.
     * A pending selection/parameter operation calls complete exactly once with a
     * terminal result, after publishing its authoritative state; other results need no callback.
     */
    override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult

    /** Release every voice/gesture belonging to this session. Idempotent; no automatic replay. */
    override fun cancelSession(generation: Long)
}
