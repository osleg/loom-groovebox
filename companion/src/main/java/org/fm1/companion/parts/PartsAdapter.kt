/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.parts

import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.OperationAdapter
import org.fm1.companion.instrument.OperationIntent

/** Whether enabling solo preserves other solos or clears them. Mute still takes precedence. */
enum class SoloMode { ADDITIVE, EXCLUSIVE }

/** Null controls are unsupported. Disabled parts remain selectable/editable, but do not sound. */
data class PartDescriptor(
    val ref: ObjectRef,
    val name: String,
    val level: ParameterBanks.Control?,
    val pan: ParameterBanks.Control?,
    val muted: Boolean?,
    val soloed: Boolean?,
    val enabled: Boolean = true
) {
    init {
        require(ref.kind == ObjectRef.Kind.PART && name.isNotBlank())
        for (control in listOfNotNull(level, pan)) {
            require(control.parameter.choices.isEmpty())
            val value = requireNotNull(control.value)
            require(value.isFinite() && value in control.parameter.minimum..control.parameter.maximum)
        }
    }
}

/** Revision covers selection, identities, descriptors and all mix values, never level meters.
 * IDs describe persistent application parts, not their display ordering.
 */
data class PartsState(
    val parts: List<PartDescriptor>,
    val selected: ObjectRef,
    val revision: Long,
    val soloMode: SoloMode = SoloMode.ADDITIVE
) {
    init {
        require(revision >= 0 && parts.size in 1..16383)
        require(parts.map { it.ref }.distinct().size == parts.size)
        require(parts.all { it.ref.namespace == selected.namespace })
        require(parts.any { it.ref == selected })
    }
    val current: PartDescriptor get() = parts.first { it.ref == selected }
    val context: Context get() = Context(selected, revision)
    data class Context(val selected: ObjectRef, val revision: Long)
}

sealed class PartsIntent : OperationIntent {
    data class SelectPart(val part: ObjectRef) : PartsIntent()
    data class SetLevel(val value: Float) : PartsIntent()
    data class SetPan(val value: Float) : PartsIntent()
    data class SetMute(val value: Boolean) : PartsIntent()
    data class SetSolo(val value: Boolean) : PartsIntent()
}

/** All operations target the selected part and require its current PartsState revision.
 * Selection and mix changes complete synchronously on the model-owner thread. Selection
 * also selects the instrument part exposed by InstrumentAdapter; voice IDs must still
 * release their original part. The controller releases held voices before selection.
 * Cancelling a session cancels gestures, not saved mix values or the current selection.
 */
interface PartsAdapter : OperationAdapter {
    fun read(): PartsState
}
