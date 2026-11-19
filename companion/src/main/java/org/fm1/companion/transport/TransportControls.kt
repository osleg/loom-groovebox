/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.transport

import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode

/** Shared transport decisions; the owning session supplies its single operation dispatcher. */
class TransportControls(
    private val adapter: TransportAdapter,
    private val perform: (ObjectRef, Long, TransportIntent) -> OperationResult
) {
    fun state(): TransportState = adapter.read()

    fun start(expected: TransportState.Context): OperationResult = guarded(expected, TransportIntent.Start)

    fun stop(): OperationResult {
        val current = state()
        return perform(current.target, current.revision, TransportIntent.Stop)
    }

    fun resume(expected: TransportState.Context): OperationResult {
        val current = state()
        if (current.context != expected) return OperationResult(ResultCode.STALE)
        if (!current.supportsContinue) return OperationResult(ResultCode.UNSUPPORTED, message = "Continue unavailable")
        return perform(current.target, current.revision, TransportIntent.Continue)
    }

    fun adjustTempo(delta: Int, expected: TransportState.Context): OperationResult {
        val current = state()
        if (current.context != expected) return OperationResult(ResultCode.STALE)
        if (delta == 0) return OperationResult(ResultCode.COMPLETED, current.revision)
        val value = (current.tempo.toDouble() + delta.toDouble() * current.tempoStep)
            .coerceIn(current.minimumTempo.toDouble(), current.maximumTempo.toDouble()).toFloat()
        return perform(current.target, current.revision, TransportIntent.SetTempo(value))
    }

    private fun guarded(expected: TransportState.Context, intent: TransportIntent): OperationResult {
        val current = state()
        if (current.context != expected) return OperationResult(ResultCode.STALE)
        return perform(current.target, current.revision, intent)
    }
}
