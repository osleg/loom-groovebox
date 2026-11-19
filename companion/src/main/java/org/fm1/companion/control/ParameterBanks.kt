/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.control

/** Synchronous owner-thread view of application-owned parameter state. */
class ParameterBanks(private val adapter: Adapter) {
    interface Adapter {
        /** Revision changes whenever target, descriptors, or values change. */
        fun read(): State

        /** Recheck expectedRevision before applying and report the application result. */
        fun apply(edit: Edit): Result
    }

    data class Control(val parameter: ParameterDescriptor, val value: Float?)
    data class State(val targetId: String, val revision: Long, val controls: List<Control?>)
    data class Context(val targetId: String, val revision: Long, val bank: Int)
    data class Page(val context: Context, val bankCount: Int, val controls: List<Control?>)
    data class Edit(val targetId: String, val expectedRevision: Long, val parameterId: String, val value: Float)
    enum class Result { COMPLETED, UNSUPPORTED, INVALID, STALE, BUSY, FAILED }

    private var bank = 0

    fun page(): Page = pageFrom(read())

    fun selectBank(delta: Int): Page {
        val state = read()
        val count = bankCount(state)
        bank = (bank.coerceIn(0, count - 1).toLong() + delta.toLong())
            .coerceIn(0L, (count - 1).toLong()).toInt()
        return pageFrom(state)
    }

    fun resetBank() {
        bank = 0
    }

    fun adjust(slot: Int, delta: Int, expected: Context): Result {
        val current = page()
        if (current.context != expected) return Result.STALE
        if (slot !in 0..3) return Result.INVALID
        val control = current.controls[slot] ?: return Result.UNSUPPORTED
        if (delta == 0) return Result.COMPLETED
        return adapter.apply(Edit(
            expected.targetId,
            expected.revision,
            control.parameter.id,
            control.parameter.adjusted(control.value, delta)
        ))
    }

    private fun read(): State = adapter.read().also {
        require(it.targetId.isNotBlank())
        require(it.revision >= 0L)
        require(it.controls.size <= 65532)
    }

    private fun bankCount(state: State): Int = maxOf(1, (state.controls.size + 3) / 4)

    private fun pageFrom(state: State): Page {
        val count = bankCount(state)
        bank = bank.coerceIn(0, count - 1)
        val start = bank * 4
        return Page(
            Context(state.targetId, state.revision, bank),
            count,
            List(4) { offset -> state.controls.getOrNull(start + offset) }
        )
    }
}
