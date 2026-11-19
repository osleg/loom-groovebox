/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.transport

import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.OperationAdapter
import org.fm1.companion.instrument.OperationIntent

enum class PositionUnit { MILLISECONDS, TICKS }

data class TransportPosition(val value: Int, val unit: PositionUnit, val ticksPerQuarter: Int = 0) {
    init {
        require(value in 0..0x0fffffff)
        require(if (unit == PositionUnit.TICKS) ticksPerQuarter in 1..16383 else ticksPerQuarter == 0)
    }
}

/** Revision covers editable state and limits; position advances without retiring an edit context.
 * Reported tempo may exceed the edit bounds, for example after loading an external project.
 */
data class TransportState(
    val target: ObjectRef,
    val revision: Long,
    val playing: Boolean,
    val recording: Boolean,
    val tempo: Float,
    val minimumTempo: Float,
    val maximumTempo: Float,
    val tempoStep: Float = 1f,
    val supportsContinue: Boolean = false,
    val position: TransportPosition? = null
) {
    init {
        require(target.kind == ObjectRef.Kind.TRANSPORT && revision >= 0)
        require(tempo.isFinite() && tempo in .1f..1638.3f)
        require(minimumTempo.isFinite() && maximumTempo.isFinite() &&
            minimumTempo in .1f..1638.3f && maximumTempo in minimumTempo..1638.3f)
        require(tempoStep.isFinite() && tempoStep > 0f)
    }
    val context: Context get() = Context(target, revision)
    data class Context(val target: ObjectRef, val revision: Long)
}

sealed class TransportIntent : OperationIntent {
    /** Start at the beginning; distinct from resuming a paused location. */
    object Start : TransportIntent()
    /** Stop playback even when the editable revision is stale. */
    object Stop : TransportIntent()
    object Continue : TransportIntent()
    data class SetTempo(val value: Float) : TransportIntent()
}

/** Synchronous transport operations on the application model-owner thread.
 * Start restarts at the beginning. Stop ignores stale revisions for its own target.
 * Continue is only available when advertised; never implement it as Start.
 * Accepting this profile gives the companion session playback ownership, including changes
 * made on the phone. cancelSession stops it even if no hardware transport operation was sent.
 * The owner must not call it for an unaccepted profile. Reconnection never replays playback.
 */
interface TransportAdapter : OperationAdapter {
    fun read(): TransportState
}
