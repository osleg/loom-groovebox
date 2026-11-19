/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.parts

import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode

/** Shared part navigation and four mixer controls, using the owner's operation dispatcher. */
class PartsControls(
    private val adapter: PartsAdapter,
    private val perform: (ObjectRef, Long, PartsIntent) -> OperationResult
) {
    fun state(): PartsState = adapter.read()

    fun select(delta: Int, expected: PartsState.Context): OperationResult {
        val current = state()
        if (current.context != expected) return OperationResult(ResultCode.STALE)
        val index = current.parts.indexOfFirst { it.ref == current.selected }
        val next = (index.toLong() + delta.toLong()).coerceIn(0L, current.parts.lastIndex.toLong()).toInt()
        if (next == index) return OperationResult(ResultCode.COMPLETED, current.revision)
        return perform(current.selected, current.revision, PartsIntent.SelectPart(current.parts[next].ref))
    }

    fun adjust(slot: Int, delta: Int, expected: PartsState.Context): OperationResult {
        val current = state()
        if (current.context != expected) return OperationResult(ResultCode.STALE)
        if (slot !in 0..3) return OperationResult(ResultCode.INVALID)
        if (delta == 0) return OperationResult(ResultCode.COMPLETED, current.revision)
        val part = current.current
        val intent = when (slot) {
            0 -> part.level?.let { PartsIntent.SetLevel(it.parameter.adjusted(it.value, delta)) }
            1 -> part.pan?.let { PartsIntent.SetPan(it.parameter.adjusted(it.value, delta)) }
            2 -> part.muted?.let { PartsIntent.SetMute(delta > 0) }
            else -> part.soloed?.let { PartsIntent.SetSolo(delta > 0) }
        } ?: return OperationResult(ResultCode.UNSUPPORTED, message = "Mix control unavailable")
        return perform(current.selected, current.revision, intent)
    }
}
