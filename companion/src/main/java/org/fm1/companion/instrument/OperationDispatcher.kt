/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.instrument

import org.fm1.companion.transport.TransportIntent
import org.fm1.companion.parts.PartsIntent
import org.fm1.companion.sequence.SequenceIntent

/** Owner-thread operation admission and session-local retry receipts. No musical state is cached here. */
class OperationDispatcher(
    private val adapter: OperationAdapter,
    private val onResult: (Operation, OperationResult) -> Unit = { _, _ -> }
) {
    private class Entry(val operation: Operation) {
        var result: OperationResult? = null
        var earlyCompletion: OperationResult? = null
        var invoking = true
    }

    var generation = 0L
        private set
    private var active = false
    val isActive: Boolean get() = active
    private var allocated = 0L
    private var highWater = 0L
    private val pending = LinkedHashMap<Long, Entry>()
    private val receipts = LinkedHashMap<Long, Entry>()

    fun begin(): Long {
        end()
        check(generation < Long.MAX_VALUE) { "Session generation exhausted" }
        generation++
        allocated = 0L
        highWater = 0L
        active = true
        return generation
    }

    fun end() {
        if (!active) return
        active = false
        pending.clear()
        receipts.clear()
        adapter.cancelSession(generation)
    }

    fun request(target: ObjectRef, expectedRevision: Long, intent: OperationIntent): Operation {
        check(active && allocated < Long.MAX_VALUE) { "No active session or operation IDs exhausted" }
        return Operation(generation, ++allocated, target, expectedRevision, intent)
    }

    fun submit(operation: Operation): OperationResult {
        if (!active || operation.generation != generation) return OperationResult(ResultCode.STALE)
        if (operation.id <= 0) return OperationResult(ResultCode.INVALID)
        val existing = pending[operation.id] ?: receipts[operation.id]
        if (existing != null) {
            if (existing.operation != operation) return OperationResult(ResultCode.INVALID)
            return existing.result ?: OperationResult(ResultCode.ACCEPTED_PENDING)
        }
        if (operation.id <= highWater) return OperationResult(ResultCode.STALE)
        highWater = operation.id
        allocated = maxOf(allocated, operation.id)
        val entry = Entry(operation)
        val invalid = invalid(operation)
        val termination = operation.intent is InstrumentIntent.NoteOff || operation.intent is TransportIntent.Stop
        val performance = termination || operation.intent is InstrumentIntent.NoteOn || operation.intent is TransportIntent
        if (invalid != null || (!termination && pending.size >= 8)) {
            entry.invoking = false
            return publish(entry, invalid ?: OperationResult(ResultCode.BUSY))
        }
        // Register before calling the adapter so an inline callback/retry cannot execute twice.
        pending[operation.id] = entry
        val initial = try {
            adapter.execute(operation) { complete(entry, it) }
        } catch (_: Exception) {
            OperationResult(ResultCode.FAILED, message = "Operation failed")
        }
        entry.invoking = false
        if (!active || operation.generation != generation || pending[operation.id] !== entry) {
            return OperationResult(ResultCode.CANCELLED)
        }
        if ((performance || operation.intent is PartsIntent || operation.intent is SequenceIntent) &&
            initial.code == ResultCode.ACCEPTED_PENDING) {
            // Delayed notes or transport starts could outlive a release, Stop or reconnect.
            end()
            val result = OperationResult(ResultCode.FAILED, message = "Synchronous operation did not complete")
            onResult(operation, result)
            return result
        }
        val result = when {
            !validResult(initial) -> OperationResult(ResultCode.FAILED, message = "Invalid operation result")
            entry.earlyCompletion != null && initial.code != ResultCode.ACCEPTED_PENDING ->
                OperationResult(ResultCode.FAILED, message = "Unexpected completion")
            initial.code == ResultCode.ACCEPTED_PENDING -> entry.earlyCompletion ?: initial
            else -> initial
        }
        return publish(entry, result)
    }

    private fun complete(entry: Entry, completion: OperationResult) {
        if (!active || entry.operation.generation != generation || pending[entry.operation.id] !== entry) return
        val terminal = if (validResult(completion) && completion.code != ResultCode.ACCEPTED_PENDING)
            completion else OperationResult(ResultCode.FAILED, message = "Invalid completion")
        if (entry.invoking) {
            if (entry.earlyCompletion == null) entry.earlyCompletion = terminal
        } else if (entry.result?.code == ResultCode.ACCEPTED_PENDING) {
            publish(entry, terminal)
        }
    }

    private fun publish(entry: Entry, result: OperationResult): OperationResult {
        entry.result = result
        if (result.code != ResultCode.ACCEPTED_PENDING) {
            pending.remove(entry.operation.id)
            receipts[entry.operation.id] = entry
            if (receipts.size > 64) receipts.remove(receipts.keys.first())
        }
        onResult(entry.operation, result)
        return result
    }

    private fun validResult(result: OperationResult): Boolean =
        (result.revision == null || result.revision >= 0) &&
            (result.code != ResultCode.COMPLETED || result.revision != null)

    private fun invalid(operation: Operation): OperationResult? {
        if (operation.expectedRevision < 0 || operation.target.namespace != adapter.namespace)
            return OperationResult(ResultCode.INVALID)
        val kind = when (operation.intent) {
            is InstrumentIntent -> ObjectRef.Kind.INSTRUMENT
            is TransportIntent -> ObjectRef.Kind.TRANSPORT
            is PartsIntent -> ObjectRef.Kind.PART
            is SequenceIntent -> ObjectRef.Kind.PATTERN
            else -> return OperationResult(ResultCode.INVALID)
        }
        if (operation.target.kind != kind) return OperationResult(ResultCode.INVALID)
        val valid = when (val intent = operation.intent) {
            is InstrumentIntent.SetParameter -> intent.parameterId.isNotEmpty() &&
                intent.parameterId.toByteArray(Charsets.UTF_8).size <= 128 && intent.value.isFinite()
            is InstrumentIntent.SelectInstrument -> intent.instrument.kind == ObjectRef.Kind.INSTRUMENT &&
                intent.instrument.namespace == adapter.namespace
            is InstrumentIntent.NoteOn -> intent.voiceId > 0 && intent.note in 0..127 && intent.velocity in 1..127
            is InstrumentIntent.NoteOff -> intent.voiceId > 0
            is TransportIntent.SetTempo -> intent.value.isFinite() && intent.value in .1f..1638.3f
            TransportIntent.Start, TransportIntent.Stop, TransportIntent.Continue -> true
            is PartsIntent.SelectPart -> intent.part.kind == ObjectRef.Kind.PART &&
                intent.part.namespace == adapter.namespace
            is PartsIntent.SetLevel -> intent.value.isFinite()
            is PartsIntent.SetPan -> intent.value.isFinite()
            is PartsIntent.SetMute, is PartsIntent.SetSolo -> true
            is SequenceIntent.ReplaceNotes -> intent.step >= 0 && intent.notes.size <= 16 &&
                intent.notes.all { it.gate.isFinite() && it.gate > 0f }
            is SequenceIntent.EditNotes -> intent.edits.isNotEmpty() && intent.edits.size <= 1024 &&
                intent.edits.map { it.address }.distinct().size == intent.edits.size &&
                intent.edits.all { edit -> validAddress(edit.address) &&
                    (edit.pitch != null || edit.velocity != null || edit.offset != null || edit.gate != null) &&
                    (edit.pitch == null || edit.pitch in 0..127) &&
                    (edit.velocity == null || edit.velocity.isFinite() && edit.velocity in 0f..1f) &&
                    (edit.offset == null || edit.offset.isFinite() && edit.offset in 0f..1f) &&
                    (edit.gate == null || edit.gate.isFinite() && edit.gate > 0f) }
            is SequenceIntent.MoveNotes -> intent.addresses.isNotEmpty() && intent.addresses.size <= 1024 &&
                intent.addresses.distinct().size == intent.addresses.size &&
                intent.addresses.all(::validAddress) && intent.stepDelta in -1023..1023 && intent.stepDelta != 0
            is SequenceIntent.ClearSteps -> validIndices(intent.indices)
            is SequenceIntent.SetStepsActive -> validIndices(intent.indices)
            is SequenceIntent.EditSteps -> validIndices(intent.indices) &&
                (intent.probability != null || intent.ratchet != null || intent.nudge != null ||
                    intent.skipped != null || intent.punch != null) &&
                (intent.probability == null || intent.probability.isFinite() && intent.probability in 0f..1f) &&
                (intent.ratchet == null || intent.ratchet in 1..16) &&
                (intent.nudge == null || intent.nudge.isFinite() && intent.nudge in 0f..1f)
            is SequenceIntent.SetLock -> validIndices(intent.indices) && intent.parameterId.isNotBlank() &&
                intent.parameterId.toByteArray(Charsets.UTF_8).size <= 128 && intent.value.isFinite()
            is SequenceIntent.AdjustLocks -> validIndices(intent.indices) && intent.parameterId.isNotBlank() &&
                intent.parameterId.toByteArray(Charsets.UTF_8).size <= 128 &&
                intent.delta in -16383..16383 && intent.delta != 0
            is SequenceIntent.ClearLocks -> validIndices(intent.indices)
            is SequenceIntent.SelectLane -> intent.index in 0..127
            else -> false
        }
        return if (valid) null else OperationResult(ResultCode.INVALID)
    }

    private fun validIndices(indices: List<Int>): Boolean =
        indices.isNotEmpty() && indices.size <= 1024 && indices.distinct().size == indices.size &&
            indices.all { it in 0..1023 }

    private fun validAddress(address: org.fm1.companion.sequence.NoteAddress): Boolean =
        address.step in 0..1023 && address.index in 0..15
}
