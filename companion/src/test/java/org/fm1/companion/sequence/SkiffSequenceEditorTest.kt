/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.sequence

import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.control.ParameterDescriptor
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.Operation
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode
import org.junit.Assert.*
import org.junit.Test

class SkiffSequenceEditorTest {
    private class App(mode: SequenceMode = SequenceMode.MELODIC) : SequenceAdapter {
        override val namespace = "test.sequence"
        val ref = ObjectRef(namespace, ObjectRef.Kind.PATTERN, "pattern-1")
        val base = ParameterBanks.Control(ParameterDescriptor("cutoff", "CUTOFF"), .5f)
        var state = SequenceState(ref, 0, mode, List(32) { SequenceStep() }, 32, 0, 4,
            (0..127).toList(), 64f, false, false, listOf(base, null, null, null))
        val intents = mutableListOf<SequenceIntent>()
        val played = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        override fun read() = state
        override fun cancelSession(generation: Long) {}
        override fun execute(operation: Operation, complete: (OperationResult) -> Unit) =
            edit(operation.target, operation.expectedRevision, operation.intent as SequenceIntent)
        fun edit(target: ObjectRef, revision: Long, intent: SequenceIntent): OperationResult {
            if (target != state.target || revision != state.revision) return OperationResult(ResultCode.STALE)
            intents += intent
            state = when (intent) {
                is SequenceIntent.ReplaceNotes -> state.copy(steps = state.steps.mapIndexed { i, step ->
                    if (i == intent.step) step.copy(active = intent.notes.isNotEmpty(), notes = intent.notes) else step
                })
                is SequenceIntent.EditNotes -> state.copy(steps = state.steps.mapIndexed { i, step ->
                    val edits = intent.edits.filter { it.address.step == i }
                    step.copy(notes = step.notes.mapIndexed { index, note ->
                        val edit = edits.firstOrNull { it.address.index == index }
                        if (edit == null) note else note.copy(pitch = edit.pitch ?: note.pitch,
                            velocity = edit.velocity ?: note.velocity, offset = edit.offset ?: note.offset,
                            gate = edit.gate ?: note.gate)
                    })
                })
                is SequenceIntent.SetLock -> state.copy(steps = state.steps.mapIndexed { i, step ->
                    if (i in intent.indices) step.copy(locks = step.locks + (intent.parameterId to intent.value)) else step
                })
                is SequenceIntent.AdjustLocks -> state.copy(steps = state.steps.mapIndexed { i, step ->
                    if (i in intent.indices) {
                        val old = step.locks[intent.parameterId] ?: base.value
                        step.copy(locks = step.locks + (intent.parameterId to
                            base.parameter.adjusted(old, intent.delta)))
                    } else step
                })
                is SequenceIntent.ClearLocks -> state.copy(steps = state.steps.mapIndexed { i, step ->
                    if (i in intent.indices) step.copy(locks = emptyMap()) else step
                })
                is SequenceIntent.ClearSteps -> state.copy(steps = state.steps.mapIndexed { i, step ->
                    if (i in intent.indices) SequenceStep() else step
                })
                is SequenceIntent.SetStepsActive -> state.copy(steps = state.steps.mapIndexed { i, step ->
                    if (i in intent.indices) step.copy(active = intent.value) else step
                })
                is SequenceIntent.SelectLane -> state.copy(lane = intent.index)
                else -> state
            }.copy(revision = state.revision + 1)
            return OperationResult(ResultCode.COMPLETED, state.revision)
        }
        fun editor() = SkiffSequenceEditor(this, ::edit) { pitch, _ ->
            played += pitch
            val release: () -> Unit = { released += pitch }
            release
        }
    }

    @Test fun cursorEntryChordAndSelectedNoteEditFollowSeparateGestures() {
        val app = App()
        val editor = app.editor()
        var context = editor.view().context
        var view = editor.scan(SequenceScan(presetsDelta = 2, expectedContext = context))
        assertEquals(2, view.cursor)
        context = view.context
        view = editor.scan(SequenceScan(recPressed = true, expectedContext = context))
        assertTrue(view.stepEntry)
        context = view.context
        view = editor.scan(SequenceScan(keysDown = 3, keysHeld = 3, expectedContext = context))
        assertEquals(listOf(53, 54), app.state.steps[2].notes.map { it.pitch })
        assertEquals(2, view.cursor)
        context = view.context
        view = editor.scan(SequenceScan(keysUp = 3, expectedContext = context))
        assertEquals(3, view.cursor)
        assertEquals(listOf(53, 54), app.released)
        context = view.context
        view = editor.scan(SequenceScan(recPressed = true, expectedContext = context))
        assertFalse(view.stepEntry)
        context = view.context
        view = editor.scan(SequenceScan(presetsDelta = -1, expectedContext = context))
        context = view.context
        editor.scan(SequenceScan(knobs = intArrayOf(1, 0, 0, 0), expectedContext = context))
        assertEquals(listOf(54, 55), app.state.steps[2].notes.map { it.pitch })
    }

    @Test fun stalePhoneEditReleasesOriginalAuditionAndRetiresHeldGesture() {
        val app = App()
        val editor = app.editor()
        val context = editor.view().context
        editor.scan(SequenceScan(keysDown = 1, keysHeld = 1, expectedContext = context))
        app.state = app.state.copy(revision = 1, steps = app.state.steps.mapIndexed { i, step ->
            if (i == 0) step.copy(notes = listOf(SequenceNote(60, .8f, 0f, 1f))) else step
        })
        val view = editor.scan(SequenceScan(keysUp = 1, knobs = intArrayOf(1, 0, 0, 0), expectedContext = context))
        assertEquals(listOf(53), app.released)
        assertTrue(view.context > context)
        assertEquals(60, app.state.steps[0].notes.single().pitch)
        assertTrue(app.intents.isEmpty())
    }

    @Test fun navigationSuppressesSameScanEditAndArmedClearIsExplicit() {
        val app = App()
        val editor = app.editor()
        val first = editor.view()
        val after = editor.scan(SequenceScan(presetsDelta = 1, keysDown = 1,
            knobs = intArrayOf(1, 0, 0, 0), expectedContext = first.context))
        assertEquals(1, after.cursor)
        assertTrue(app.intents.isEmpty())
        assertTrue(app.released.isEmpty())
        val armed = editor.scan(SequenceScan(recPressed = true, expectedContext = after.context))
        editor.scan(SequenceScan(editPressed = true, expectedContext = armed.context))
        assertEquals(SequenceIntent.ClearSteps(listOf(1)), app.intents.single())
    }

    @Test fun heldDrumLockDoesNotToggleHitOrChangeBaseAndUnsupportedSlotsStayEmpty() {
        val app = App(SequenceMode.DRUM)
        val editor = app.editor()
        val context = editor.view().context
        val held = editor.scan(SequenceScan(keysDown = 1, keysHeld = 1, expectedContext = context))
        assertTrue(held.holdingSteps)
        assertFalse(held.controls[1].available)
        val turned = editor.scan(SequenceScan(keysHeld = 1, knobs = intArrayOf(1, 0, 0, 0),
            expectedContext = held.context))
        editor.scan(SequenceScan(keysUp = 1, expectedContext = turned.context))
        assertEquals(.5f, app.base.value!!, 0f)
        assertEquals(.51f, app.state.steps[0].locks["cutoff"]!!, .0001f)
        assertTrue(app.state.steps[0].notes.isEmpty())
        assertEquals(1, app.intents.size)
    }

    @Test fun playingRefusesEntryAndStepEditsButAllowsPreview() {
        val app = App()
        app.state = app.state.copy(playing = true)
        val editor = app.editor()
        val context = editor.view().context
        val view = editor.scan(SequenceScan(recPressed = true, expectedContext = context))
        assertFalse(view.stepEntry)
        assertEquals("STOP TO EDIT", view.message)
        editor.scan(SequenceScan(keysDown = 1, keysHeld = 1, expectedContext = view.context))
        assertTrue(app.intents.isEmpty())
    }

    @Test fun lengthCrossesBetweenFullStepAndTwoStepsWithoutFractionalOvershoot() {
        val app = App()
        app.state = app.state.copy(steps = app.state.steps.mapIndexed { i, step ->
            if (i == 0) step.copy(active = true, notes = listOf(SequenceNote(60, 1f, 0f, 1f))) else step
        })
        val editor = app.editor()
        var context = editor.view().context
        context = editor.scan(SequenceScan(knobs = intArrayOf(0, 0, 1, 0),
            expectedContext = context)).context
        assertEquals(2f, app.state.steps[0].notes.single().gate, 0f)
        context = editor.scan(SequenceScan(knobs = intArrayOf(0, 0, -1, 0),
            expectedContext = context)).context
        assertEquals(1f, app.state.steps[0].notes.single().gate, 0f)
        app.state = app.state.copy(revision = app.state.revision + 1,
            steps = app.state.steps.mapIndexed { i, step ->
                if (i == 0) step.copy(notes = listOf(step.notes.single().copy(gate = .99f))) else step
            })
        context = editor.view().context
        editor.scan(SequenceScan(knobs = intArrayOf(0, 0, 1, 0), expectedContext = context))
        assertEquals(1f, app.state.steps[0].notes.single().gate, 0f)
    }

    @Test fun inactiveDrumNoteDisplaysOffAndHitControlsActivateWithoutLosingNoteFields() {
        val app = App(SequenceMode.DRUM)
        val note = SequenceNote(60, .7f, .2f, 2f)
        app.state = app.state.copy(steps = app.state.steps.mapIndexed { i, step ->
            if (i == 0) step.copy(active = false, notes = listOf(note),
                probability = .4f, locks = mapOf("cutoff" to .3f)) else step
        })
        val editor = app.editor()
        val initial = editor.view()
        assertEquals("OFF", initial.controls[1].value)
        editor.scan(SequenceScan(knobs = intArrayOf(0, 1, 0, 0), expectedContext = initial.context))
        val step = app.state.steps[0]
        assertTrue(step.active)
        assertEquals(listOf(note), step.notes)
        assertEquals(.4f, step.probability, 0f)
        assertEquals(mapOf("cutoff" to .3f), step.locks)
    }

    @Test fun whiteDrumKeyTogglesActualActiveStateWhenAStoredNoteIsInactive() {
        val app = App(SequenceMode.DRUM)
        val note = SequenceNote(60, .7f, .2f, 2f)
        app.state = app.state.copy(steps = app.state.steps.mapIndexed { i, step ->
            if (i == 0) step.copy(active = false, notes = listOf(note)) else step
        })
        val editor = app.editor()
        val initial = editor.view()
        val pressed = editor.scan(SequenceScan(keysDown = 1, keysHeld = 1,
            expectedContext = initial.context))
        editor.scan(SequenceScan(keysUp = 1, expectedContext = pressed.context))
        assertTrue(app.state.steps[0].active)
        assertEquals(listOf(note), app.state.steps[0].notes)
    }

    @Test fun heldLockTurnAdjustsEachStepFromItsOwnPreviousValue() {
        val app = App(SequenceMode.DRUM)
        app.state = app.state.copy(steps = app.state.steps.mapIndexed { i, step ->
            when (i) {
                0 -> step.copy(locks = mapOf("cutoff" to .2f))
                1 -> step.copy(locks = mapOf("cutoff" to .6f))
                else -> step
            }
        })
        val editor = app.editor()
        val initial = editor.view()
        val held = editor.scan(SequenceScan(keysDown = 5, keysHeld = 5,
            expectedContext = initial.context))
        editor.scan(SequenceScan(keysHeld = 5, knobs = intArrayOf(1, 0, 0, 0),
            expectedContext = held.context))
        assertEquals(.21f, app.state.steps[0].locks["cutoff"]!!, .0001f)
        assertEquals(.61f, app.state.steps[1].locks["cutoff"]!!, .0001f)
    }

    @Test fun blackLaneKeySuppressesSameScanKnobEditOnTheNewLane() {
        val app = App(SequenceMode.DRUM)
        val editor = app.editor()
        val context = editor.view().context
        editor.scan(SequenceScan(keysDown = 1L shl 3, keysHeld = 1L shl 3,
            knobs = intArrayOf(0, 1, 0, 0), expectedContext = context))
        assertEquals(1, app.state.lane)
        assertTrue(app.state.steps[0].notes.isEmpty())
        assertEquals(listOf(SequenceIntent.SelectLane(1)), app.intents)
    }

    @Test fun blackDrumKeyAuditionsSelectedLaneAndReleasesOriginalVoice() {
        val app = App(SequenceMode.DRUM)
        val editor = app.editor()
        val initial = editor.view()
        val pressed = editor.scan(SequenceScan(keysDown = 1L shl 3, keysHeld = 1L shl 3,
            expectedContext = initial.context))
        assertEquals(1, app.state.lane)
        assertEquals(listOf(61), app.played)
        app.state = app.state.copy(lane = 2, revision = app.state.revision + 1)
        editor.scan(SequenceScan(keysUp = 1L shl 3, expectedContext = pressed.context))
        assertEquals(listOf(61), app.released)
    }

    @Test fun whiteDrumKeyTogglesWhilePlayingWithoutRecording() {
        val app = App(SequenceMode.DRUM)
        app.state = app.state.copy(playing = true)
        val editor = app.editor()
        val initial = editor.view()
        val pressed = editor.scan(SequenceScan(keysDown = 1, keysHeld = 1,
            expectedContext = initial.context))
        editor.scan(SequenceScan(keysUp = 1, expectedContext = pressed.context))
        assertTrue(app.state.steps[0].active)
    }

    @Test fun heldLockTurnKeepsLockCardsAndEditClearWithoutTogglingTheHit() {
        val app = App(SequenceMode.DRUM)
        val note = SequenceNote(60, .8f, 0f, 1f)
        app.state = app.state.copy(steps = app.state.steps.mapIndexed { i, step ->
            if (i == 0) step.copy(active = true, notes = listOf(note),
                locks = mapOf("cutoff" to .2f)) else step
        })
        val editor = app.editor()
        val initial = editor.view()
        val pressed = editor.scan(SequenceScan(keysDown = 1, keysHeld = 1,
            expectedContext = initial.context))
        val turned = editor.scan(SequenceScan(keysHeld = 1, knobs = intArrayOf(1, 0, 0, 0),
            expectedContext = pressed.context))
        assertTrue(turned.holdingSteps)
        assertEquals("CUTOFF", turned.controls[0].label)
        val cleared = editor.scan(SequenceScan(keysHeld = 1, editPressed = true,
            expectedContext = turned.context))
        editor.scan(SequenceScan(keysUp = 1, expectedContext = cleared.context))
        assertEquals(emptyMap<String, Float>(), app.state.steps[0].locks)
        assertTrue(app.state.steps[0].active)
        assertEquals(listOf(note), app.state.steps[0].notes)
    }

    @Test fun heldWhiteKeyCannotRetargetLockAfterCursorMovesToAnotherBank() {
        val app = App(SequenceMode.DRUM)
        val editor = app.editor()
        val initial = editor.view()
        val pressed = editor.scan(SequenceScan(keysDown = 1, keysHeld = 1,
            expectedContext = initial.context))
        val moved = editor.scan(SequenceScan(keysHeld = 1, presetsDelta = 16,
            expectedContext = pressed.context))
        assertEquals(16, moved.cursor)
        editor.scan(SequenceScan(keysHeld = 1, knobs = intArrayOf(1, 0, 0, 0),
            expectedContext = moved.context))
        assertTrue(app.state.steps[0].locks.isEmpty())
        assertTrue(app.state.steps[16].locks.isEmpty())
        assertTrue(app.intents.isEmpty())
    }
}
