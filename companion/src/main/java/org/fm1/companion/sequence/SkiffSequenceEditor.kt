/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.sequence

import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode
import kotlin.math.roundToInt

enum class SequencePage { NOTES, FEEL }

enum class SequenceValueFormat { NUMBER, PITCH, PERCENT, GATE_STEPS, STEP_POSITION, BOOLEAN }

data class SequenceDisplayControl(
    val label: String,
    val value: String,
    val available: Boolean = true,
    val rawValue: Float? = null,
    val format: SequenceValueFormat = SequenceValueFormat.NUMBER
)

data class SequenceView(
    val context: Long,
    val target: ObjectRef,
    val revision: Long,
    val page: SequencePage,
    val cursor: Int,
    val bank: Int,
    val title: String,
    val selectionText: String,
    val controls: List<SequenceDisplayControl>,
    val stepEntry: Boolean,
    val holdingSteps: Boolean,
    val message: String = "",
    val selectedNotes: Set<NoteAddress> = emptySet(),
    val selectedSteps: Set<Int> = emptySet()
)

data class SequenceScan(
    val keysDown: Long = 0,
    val keysUp: Long = 0,
    val keysHeld: Long = 0,
    val knobs: IntArray = IntArray(4),
    val presetsDelta: Int = 0,
    val selectDelta: Int = 0,
    val shiftHeld: Boolean = false,
    val recPressed: Boolean = false,
    val editPressed: Boolean = false,
    val octave: Int = 0,
    val expectedContext: Long
)

/** Owner-thread Skiff style cursor, note selection, step entry and parameter-lock input. */
class SkiffSequenceEditor(
    private val adapter: SequenceAdapter,
    private val submit: (ObjectRef, Long, SequenceIntent) -> OperationResult,
    private val audition: (Int, Int) -> (() -> Unit)
) {
    private var known: SequenceState? = null
    private var context = 1L
    private var page = SequencePage.NOTES
    private var cursor = 0
    private var anchor: Int? = null
    private val picked = LinkedHashSet<NoteAddress>()
    private var armed = false
    private val voices = HashMap<Int, () -> Unit>()
    private val heldDrum = HashMap<Int, Int>()
    private val toggleEligible = HashSet<Int>()
    private val retiredDrumKeys = HashSet<Int>()
    private val entryKeys = HashSet<Int>()
    private val entryPitches = HashMap<Int, Int>()
    private var entryLength = 1
    private var message = ""

    /** Retire local gestures on a page change or disconnect; the cursor remains available. */
    fun cancel() = cancelGestures()

    fun cancelGestures() {
        for (release in voices.values.toList()) release()
        voices.clear()
        retireDrumHolds()
        entryKeys.clear()
        entryPitches.clear()
        armed = false
        entryLength = 1
        context++
    }

    private fun selectedDrumLane() {
        retireDrumHolds()
        entryKeys.clear(); entryPitches.clear()
        armed = false; entryLength = 1
        context++
    }

    fun view(): SequenceView {
        val state = refresh()
        val heldSteps = heldDrum.values.toList().distinct()
        val controls = when {
            heldSteps.isNotEmpty() || entryKeys.isNotEmpty() && armed ->
                state.lockControls.mapIndexed { slot, control -> lockDisplay(state, slot, control, heldSteps) }
            page == SequencePage.FEEL -> feelControls(state)
            state.mode == SequenceMode.DRUM -> drumControls(state)
            else -> noteControls(state)
        }
        val end = anchor?.let { maxOf(it, cursor) } ?: cursor
        val start = anchor?.let { minOf(it, cursor) } ?: cursor
        val selected = if (start == end) "STEP ${cursor + 1}/${state.length}"
            else "STEPS ${start + 1}-${end + 1}/${state.length}"
        return SequenceView(context, state.target, state.revision, page, cursor, cursor / 16,
            if (page == SequencePage.NOTES) "NOTES" else "FEEL", selected, controls,
            armed, heldDrum.isNotEmpty() || entryKeys.isNotEmpty(), message,
            selectedNotes(state).toSet(), selectedSteps(state).toSet())
    }

    fun scan(input: SequenceScan): SequenceView {
        require(input.knobs.size == 4)
        val navigation = input.presetsDelta != 0 || input.selectDelta != 0
        refresh()
        releaseKeys(input.keysUp, !navigation)
        val state = refresh()
        if (input.expectedContext != context) return view()
        if (navigation) retireDrumHolds()
        message = ""
        if (input.selectDelta != 0) {
            page = if (page == SequencePage.NOTES) SequencePage.FEEL else SequencePage.NOTES
            cancelGestures()
            return view()
        }
        if (input.presetsDelta != 0) {
            if (armed && entryKeys.isNotEmpty() && state.mode == SequenceMode.MELODIC) {
                entryLength = (entryLength.toLong() + input.presetsDelta)
                    .coerceIn(1L, maxOf(1L, state.maximumNoteGate.toLong())).toInt()
                val edits = state.steps[cursor].notes.mapIndexedNotNull { index, note ->
                    if (note.pitch in entryPitches.values)
                        NoteEdit(NoteAddress(cursor, index), gate = entryLength.toFloat()) else null
                }
                if (edits.isNotEmpty()) apply(state, SequenceIntent.EditNotes(edits))
            } else {
                val previousCursor = cursor
                cursor = if (input.shiftHeld) (cursor.toLong() + input.presetsDelta)
                    .coerceIn(0, state.length - 1L).toInt()
                else Math.floorMod(cursor.toLong() + input.presetsDelta, state.length.toLong()).toInt()
                if (input.shiftHeld) {
                    if (anchor == null) anchor = previousCursor
                } else {
                    anchor = null
                    picked.clear()
                }
                retireDrumHolds()
                context++
            }
            return view()
        }
        if (input.recPressed) {
            if (state.playing || state.recording) message = "STOP TO EDIT"
            else if (state.mode == SequenceMode.DRUM) message = "DRUM GRID"
            else armed = !armed
            if (!armed) { entryKeys.clear(); entryPitches.clear() }
            context++
            return view()
        }
        val heldSteps = heldDrumSteps(input.keysHeld)
        if (input.editPressed) {
            if (state.playing || state.recording) message = "STOP TO EDIT"
            else if (heldSteps.isNotEmpty() || armed && entryKeys.isNotEmpty()) {
                val indices = if (heldSteps.isNotEmpty()) heldSteps else listOf(cursor)
                apply(state, SequenceIntent.ClearLocks(indices))
                toggleEligible.removeAll(heldDrum.keys)
            } else if (armed || state.mode == SequenceMode.DRUM) {
                apply(state, SequenceIntent.ClearSteps(selectedSteps(state)))
                picked.clear()
            } else message = "ARM STEP ENTRY"
            return view()
        }
        if (state.mode == SequenceMode.DRUM) {
            if (drumKeys(state, input.keysDown)) return view()
        } else melodicKeys(state, input.keysDown, input.octave)
        val current = refresh()
        if (retiredDrumKeys.any { input.keysHeld and (1L shl it) != 0L }) return view()
        val currentHeldSteps = heldDrumSteps(input.keysHeld)
        if (input.knobs.any { it != 0 }) {
            if (current.playing || current.recording) message = "STOP TO EDIT"
            else if (currentHeldSteps.isNotEmpty() || armed && entryKeys.isNotEmpty()) {
                val indices = if (currentHeldSteps.isNotEmpty()) currentHeldSteps else listOf(cursor)
                for (slot in 0..3) if (input.knobs[slot] != 0) {
                    lockTurn(refresh(), indices, slot, input.knobs[slot])
                    if (currentHeldSteps.isNotEmpty()) toggleEligible.removeAll(heldDrum.keys)
                }
            } else for (slot in 0..3) if (input.knobs[slot] != 0) {
                val now = refresh()
                if (page == SequencePage.FEEL) feelTurn(now, slot, input.knobs[slot])
                else if (now.mode == SequenceMode.DRUM) drumTurn(now, slot, input.knobs[slot])
                else noteTurn(now, slot, input.knobs[slot])
            }
        }
        return view()
    }

    private fun refresh(): SequenceState {
        val state = adapter.read()
        val old = known
        val liveRecordedSteps = old != null && old.playing && old.recording &&
            state.playing && state.recording && sameLayout(old, state)
        if (old != null && old != state && !liveRecordedSteps) {
            cancelGestures()
            if (old.target != state.target || old.mode != state.mode) {
                cursor = 0; anchor = null; picked.clear(); page = SequencePage.NOTES
            }
        }
        known = state
        cursor = cursor.coerceIn(0, state.length - 1)
        anchor = anchor?.coerceIn(0, state.length - 1)
        picked.removeAll { it.step >= state.length || it.index >= state.steps[it.step].notes.size }
        return state
    }

    private fun sameLayout(a: SequenceState, b: SequenceState): Boolean =
        a.target == b.target && a.mode == b.mode && a.length == b.length &&
        a.steps.size == b.steps.size && a.lane == b.lane && a.laneCount == b.laneCount &&
            a.playing == b.playing && a.recording == b.recording &&
        a.scalePitches == b.scalePitches && a.maximumNoteGate == b.maximumNoteGate &&
            a.maximumRatchet == b.maximumRatchet &&
            a.lockControls.map { it?.parameter } == b.lockControls.map { it?.parameter }

    private fun apply(before: SequenceState, intent: SequenceIntent): Boolean {
        val liveDrumGrid = before.mode == SequenceMode.DRUM && before.playing && !before.recording &&
            (intent is SequenceIntent.SetStepsActive || intent is SequenceIntent.ReplaceNotes)
        if ((before.playing || before.recording) && intent !is SequenceIntent.SelectLane && !liveDrumGrid) {
            message = "STOP TO EDIT"; return false
        }
        val result = submit(before.target, before.revision, intent)
        val after = adapter.read()
        if (intent is SequenceIntent.SelectLane && result.code == ResultCode.COMPLETED &&
            result.revision == after.revision && after.mode == SequenceMode.DRUM &&
            after.lane == intent.index) {
            selectedDrumLane()
            known = after
            return true
        }
        if (result.code == ResultCode.COMPLETED && result.revision == after.revision &&
            sameLayout(before, after)) {
            known = after
            return true
        }
        if (result.code == ResultCode.UNSUPPORTED) message = "UNAVAILABLE"
        else if (result.code == ResultCode.STALE) message = "STATE CHANGED"
        else if (result.code != ResultCode.COMPLETED) message = result.message.ifBlank { result.code.name }
        refresh()
        return false
    }

    private fun releaseKeys(bits: Long, allowDrumToggle: Boolean) {
        val hadEntry = entryKeys.isNotEmpty()
        for (key in 0..26) if (bits and (1L shl key) != 0L) {
            voices.remove(key)?.invoke()
            entryKeys.remove(key)
            entryPitches.remove(key)
            val step = heldDrum.remove(key)
            val eligible = toggleEligible.remove(key)
            retiredDrumKeys.remove(key)
            if (allowDrumToggle && eligible && step != null) {
                val state = refresh()
                if (!state.recording && step < state.length) {
                    val existing = state.steps[step]
                    val pitch = 60 + state.lane
                    if (existing.notes.any { it.pitch == pitch }) {
                        apply(state, SequenceIntent.SetStepsActive(listOf(step), !existing.active))
                    } else {
                        apply(state, SequenceIntent.ReplaceNotes(step,
                            existing.notes + SequenceNote(pitch, 100f / 127f, 0f, 1f)))
                    }
                }
            }
        }
        if (hadEntry && entryKeys.isEmpty() && armed && allowDrumToggle) {
            val state = refresh()
            cursor = Math.floorMod(cursor + entryLength, state.length)
            entryLength = 1
            context++
        }
    }

    private fun melodicKeys(state: SequenceState, down: Long, octave: Int) {
        for (key in 0..26) if (down and (1L shl key) != 0L) {
            val pitch = 53 + key + octave * 12
            if (pitch !in 0..127) continue
            voices[key] = audition(pitch, 100)
            if (armed) {
                if (state.playing || state.recording) { message = "STOP TO EDIT"; continue }
                val now = refresh()
                val existing = now.steps[cursor].notes
                val withoutPitch = existing.filterNot { it.pitch == pitch }
                if (withoutPitch.size < 16) {
                    val note = SequenceNote(pitch, 100f / 127f, 0f, entryLength.toFloat())
                    apply(now, SequenceIntent.ReplaceNotes(cursor, withoutPitch + note))
                }
                entryKeys += key
                entryPitches[key] = pitch
            } else {
                val matching = selectedSteps(state).flatMap { step ->
                    state.steps[step].notes.mapIndexedNotNull { index, note ->
                        if (note.pitch == pitch) NoteAddress(step, index) else null
                    }
                }
                if (matching.isNotEmpty()) {
                    if (picked.containsAll(matching)) picked.removeAll(matching.toSet())
                    else picked.addAll(matching)
                }
            }
        }
    }

    private fun drumKeys(state: SequenceState, down: Long): Boolean {
        for (key in 0..26) if (down and (1L shl key) != 0L) {
            val place = keyPlace(key)
            if (black(key)) {
                if (place < state.laneCount) {
                    if (apply(refresh(), SequenceIntent.SelectLane(place)))
                        voices[key] = audition(60 + place, 100)
                }
                else message = "UNAVAILABLE"
                return true
            } else {
                val step = cursor / 16 * 16 + place
                if (step >= state.length) continue
                cursor = step
                anchor = null; picked.clear(); context++
                if (state.recording) message = "STOP TO EDIT"
                else {
                    retiredDrumKeys.remove(key)
                    heldDrum[key] = step
                    toggleEligible += key
                }
            }
        }
        return false
    }

    private fun heldDrumSteps(keys: Long): List<Int> = heldDrum.entries
        .filter { keys and (1L shl it.key) != 0L }
        .map { it.value }.distinct()

    private fun retireDrumHolds() {
        retiredDrumKeys.addAll(heldDrum.keys)
        heldDrum.clear()
        toggleEligible.clear()
    }

    private fun selectedSteps(state: SequenceState): List<Int> {
        val low = minOf(anchor ?: cursor, cursor).coerceIn(0, state.length - 1)
        val high = maxOf(anchor ?: cursor, cursor).coerceIn(0, state.length - 1)
        return (low..high).toList()
    }

    private fun selectedNotes(state: SequenceState): List<NoteAddress> {
        val range = selectedSteps(state)
        val inRange = picked.filter { it.step in range && it.index < state.steps[it.step].notes.size }
        return if (inRange.isNotEmpty()) inRange else range.flatMap { step ->
            state.steps[step].notes.indices.map { NoteAddress(step, it) }
        }
    }

    private fun noteTurn(state: SequenceState, slot: Int, delta: Int) {
        val addresses = selectedNotes(state)
        if (addresses.isEmpty()) { message = "NO NOTES"; return }
        when (slot) {
            0 -> {
                val scale = state.scalePitches
                val edits = addresses.map { address ->
                    val pitch = state.steps[address.step].notes[address.index].pitch
                    val nearest = scale.indices.minByOrNull { kotlin.math.abs(scale[it] - pitch) } ?: 0
                    NoteEdit(address, pitch = scale[(nearest + delta).coerceIn(0, scale.lastIndex)])
                }
                apply(state, SequenceIntent.EditNotes(edits))
            }
            1 -> apply(state, SequenceIntent.MoveNotes(addresses, delta))
            2 -> {
                val edits = addresses.map { address ->
                    val old = state.steps[address.step].notes[address.index]
                    NoteEdit(address, gate = adjustedGate(old.gate, delta, state.maximumNoteGate))
                }
                apply(state, SequenceIntent.EditNotes(edits))
            }
            3 -> apply(state, SequenceIntent.EditNotes(addresses.map { address ->
                val old = state.steps[address.step].notes[address.index]
                NoteEdit(address, velocity = (old.velocity + delta / 127f).coerceIn(1f / 127f, 1f))
            }))
        }
    }

    private fun adjustedGate(gate: Float, delta: Int, maximum: Float): Float {
        val position = if (gate <= 1f) (gate * 100).roundToInt()
            else 100 + (gate - 1f).roundToInt()
        val limit = if (maximum <= 1f) (maximum * 100).roundToInt()
            else 100 + (maximum - 1f).toInt()
        val next = (position.toLong() + delta).coerceIn(1L, maxOf(1, limit).toLong()).toInt()
        return if (next <= 100) next / 100f else 1f + (next - 100)
    }

    private fun feelTurn(state: SequenceState, slot: Int, delta: Int) {
        val first = state.steps[selectedSteps(state).first()]
        val indices = selectedSteps(state)
        val intent = when (slot) {
            0 -> SequenceIntent.EditSteps(indices, probability = (first.probability + delta / 100f).coerceIn(0f, 1f))
            1 -> SequenceIntent.EditSteps(indices, ratchet = (first.ratchet + delta)
                .coerceIn(1, state.maximumRatchet))
            2 -> SequenceIntent.EditSteps(indices, nudge = (first.subStepOffset + delta / 100f).coerceIn(0f, 1f))
            else -> SequenceIntent.EditSteps(indices, skipped = delta > 0)
        }
        apply(state, intent)
    }

    private fun drumTurn(state: SequenceState, slot: Int, delta: Int) {
        if (slot == 0) {
            apply(state, SequenceIntent.SelectLane((state.lane + delta).coerceIn(0, state.laneCount - 1)))
            return
        }
        val indices = selectedSteps(state)
        when (slot) {
            1 -> for (index in indices) {
                val now = refresh()
                val pitch = 60 + now.lane
                val existing = now.steps[index].notes
                if (existing.any { it.pitch == pitch }) {
                    if (now.steps[index].active != (delta > 0))
                        apply(now, SequenceIntent.SetStepsActive(listOf(index), delta > 0))
                } else if (delta > 0) {
                    apply(now, SequenceIntent.ReplaceNotes(index,
                        existing + SequenceNote(pitch, 100f / 127f, 0f, 1f)))
                }
            }
            2 -> apply(state, SequenceIntent.EditSteps(indices, punch = delta > 0))
            3 -> noteTurn(state, 3, delta)
        }
    }

    private fun lockTurn(state: SequenceState, indices: List<Int>, slot: Int, delta: Int) {
        val control = state.lockControls[slot]
        if (control == null) { message = "NO LOCK CONTROL"; return }
        val id = control.parameter.id
        apply(state, SequenceIntent.AdjustLocks(indices, id, delta))
    }

    private fun lockDisplay(state: SequenceState, slot: Int, control: ParameterBanks.Control?,
                            held: List<Int>): SequenceDisplayControl {
        if (control == null) return SequenceDisplayControl("LOCK ${slot + 1}", "--", false)
        val locked = (held.firstOrNull() ?: cursor).let { state.steps[it].locks[control.parameter.id] }
        val raw = locked ?: control.value ?: control.parameter.fallback
        return SequenceDisplayControl(control.parameter.label, control.parameter.display(raw),
            rawValue = raw, format = if (control.parameter.unit == "%")
                SequenceValueFormat.PERCENT else SequenceValueFormat.NUMBER)
    }

    private fun noteControls(state: SequenceState): List<SequenceDisplayControl> {
        val first = selectedNotes(state).firstOrNull()?.let { state.steps[it.step].notes[it.index] }
        return listOf(
            SequenceDisplayControl("PITCH", first?.pitch?.toString() ?: "--", first != null,
                first?.pitch?.toFloat(), SequenceValueFormat.PITCH),
            SequenceDisplayControl("START", cursor.plus(1).toString(), rawValue = cursor + 1f + (first?.offset ?: 0f),
                format = SequenceValueFormat.STEP_POSITION),
            SequenceDisplayControl("LENGTH", first?.gate?.toString() ?: "--", first != null,
                first?.gate, SequenceValueFormat.GATE_STEPS),
            SequenceDisplayControl("VELOCITY", first?.velocity?.let { (it * 127).roundToInt().toString() } ?: "--", first != null,
                first?.velocity?.times(127f), SequenceValueFormat.NUMBER)
        )
    }

    private fun drumControls(state: SequenceState): List<SequenceDisplayControl> {
        val step = state.steps[cursor]
        val hit = step.active && step.notes.any { it.pitch == 60 + state.lane }
        return listOf(
            SequenceDisplayControl("LANE", "${state.lane + 1}/${state.laneCount}", rawValue = state.lane + 1f),
            SequenceDisplayControl("HIT", if (hit) "ON" else "OFF", rawValue = if (hit) 1f else 0f,
                format = SequenceValueFormat.BOOLEAN),
            SequenceDisplayControl("ACC", if (step.punch) "ON" else "OFF", rawValue = if (step.punch) 1f else 0f,
                format = SequenceValueFormat.BOOLEAN),
            SequenceDisplayControl("VELOCITY", step.notes.firstOrNull { it.pitch == 60 + state.lane }
                ?.velocity?.let { (it * 127).roundToInt().toString() } ?: "--", hit,
                step.notes.firstOrNull { it.pitch == 60 + state.lane }?.velocity?.times(127f))
        )
    }

    private fun feelControls(state: SequenceState): List<SequenceDisplayControl> {
        val step = state.steps[cursor]
        return listOf(
            SequenceDisplayControl("CHANCE", "${(step.probability * 100).roundToInt()}%",
                rawValue = step.probability, format = SequenceValueFormat.PERCENT),
            SequenceDisplayControl("RATCHET", step.ratchet.toString(), rawValue = step.ratchet.toFloat()),
            SequenceDisplayControl("NUDGE", "${(step.subStepOffset * 100).roundToInt()}%",
                rawValue = step.subStepOffset, format = SequenceValueFormat.PERCENT),
            SequenceDisplayControl("SKIP", if (step.skipped) "YES" else "NO",
                rawValue = if (step.skipped) 1f else 0f, format = SequenceValueFormat.BOOLEAN)
        )
    }

    private fun black(key: Int): Boolean = 0x54A shr ((key + 5) % 12) and 1 != 0
    private fun keyPlace(key: Int): Int = (0 until key).count { black(it) == black(key) }
}
