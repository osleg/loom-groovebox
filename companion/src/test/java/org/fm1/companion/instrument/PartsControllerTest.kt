/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.instrument

import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.control.ParameterDescriptor
import org.fm1.companion.parts.PartDescriptor
import org.fm1.companion.parts.PartsAdapter
import org.fm1.companion.parts.PartsIntent
import org.fm1.companion.parts.PartsState
import org.fm1.companion.protocol.Wire6
import org.junit.Assert.*
import org.junit.Test

class PartsControllerTest {
    private class App {
        val namespace = "test"
        val a = ObjectRef(namespace, ObjectRef.Kind.PART, "a")
        val b = ObjectRef(namespace, ObjectRef.Kind.PART, "b")
        val ia = ObjectRef(namespace, ObjectRef.Kind.INSTRUMENT, "a")
        val ib = ObjectRef(namespace, ObjectRef.Kind.INSTRUMENT, "b")
        val ic = ObjectRef(namespace, ObjectRef.Kind.INSTRUMENT, "c")
        private val level = ParameterDescriptor("level", "Level", step = .1f)
        private val pan = ParameterDescriptor("pan", "Pan", step = .1f)
        var parts = PartsState(listOf(
            PartDescriptor(a, "Alpha", ParameterBanks.Control(level, .2f),
                ParameterBanks.Control(pan, .5f), false, false),
            PartDescriptor(b, "Beta", null, null, null, null)), a, 1)
        var instrument = InstrumentState(listOf(InstrumentDescriptor(ia, "Alpha"),
            InstrumentDescriptor(ib, "Beta")), ia, 1, emptyList())
        val calls = mutableListOf<Operation>()
        val activeVoices = mutableMapOf<Long, ObjectRef>()
        var partsCancellations = 0
        val instrumentAdapter = object : InstrumentAdapter {
            override val namespace = this@App.namespace
            override fun read() = instrument
            override fun cancelSession(generation: Long) { activeVoices.clear() }
            override fun execute(operation: Operation, complete: (OperationResult) -> Unit) =
                this@App.execute(operation)
        }
        val partsAdapter = object : PartsAdapter {
            override val namespace = this@App.namespace
            override fun read() = parts
            override fun cancelSession(generation: Long) { partsCancellations++ }
            override fun execute(operation: Operation, complete: (OperationResult) -> Unit) =
                this@App.execute(operation)
        }
        private fun execute(operation: Operation): OperationResult {
            calls += operation
            when (val intent = operation.intent) {
                is InstrumentIntent.NoteOn -> activeVoices[intent.voiceId] = operation.target
                is InstrumentIntent.NoteOff -> {
                    if (activeVoices.remove(intent.voiceId) != operation.target)
                        return OperationResult(ResultCode.FAILED)
                }
                is InstrumentIntent.SelectInstrument -> {
                    if (operation.target != instrument.selected ||
                        operation.expectedRevision != instrument.revision)
                        return OperationResult(ResultCode.STALE)
                    instrument = instrument.copy(selected = intent.instrument,
                        revision = instrument.revision + 1)
                }
                is PartsIntent -> {
                    if (operation.target != parts.selected || operation.expectedRevision != parts.revision)
                        return OperationResult(ResultCode.STALE)
                    when (intent) {
                        is PartsIntent.SelectPart -> {
                            parts = parts.copy(selected = intent.part, revision = parts.revision + 1)
                            instrument = instrument.copy(selected = if (intent.part == a) ia else ib,
                                revision = instrument.revision + 1)
                        }
                        else -> {
                            parts = parts.copy(revision = parts.revision + 1, parts = parts.parts.map { part ->
                                if (part.ref != parts.selected) part else when (intent) {
                                    is PartsIntent.SetLevel -> part.copy(level = part.level!!.copy(value = intent.value))
                                    is PartsIntent.SetPan -> part.copy(pan = part.pan!!.copy(value = intent.value))
                                    is PartsIntent.SetMute -> part.copy(muted = intent.value)
                                    is PartsIntent.SetSolo -> part.copy(soloed = intent.value)
                                    else -> part
                                }
                            })
                        }
                    }
                }
                else -> Unit
            }
            return OperationResult(ResultCode.COMPLETED,
                if (operation.intent is PartsIntent) parts.revision else instrument.revision)
        }
    }

    private class Rig {
        val app = App()
        val controller = InstrumentController(app.instrumentAdapter, parts = app.partsAdapter)
        var serial = 0
        fun context() = Wire6.u28(controller.snapshot(), 2)
        fun scan(buttons: Int = 0, keys: Int = 0, delta: Map<Int, Int> = emptyMap(),
                 drawn: Int = context()): Boolean {
            val scan = ByteArray(24)
            Wire6.put14(scan, 0, buttons)
            Wire6.put28(scan, 2, keys)
            for (i in 0..6) Wire6.put14(scan, 6 + 2 * i, 8192 + (delta[i] ?: 0))
            Wire6.put28(scan, 20, drawn)
            return controller.input(++serial, scan)
        }
        fun accept() = controller.capabilitiesAccepted(setOf(Wire6.INSTRUMENT, Wire6.PARTS))
        fun mix() { scan(); scan(buttons = 1 shl 5); scan() }
    }

    @Test fun optionalPartsAreInertUntilAcceptedAndMissingAdapterFailsClosed() {
        val r = Rig()
        assertEquals(2, r.controller.snapshot()[0].toInt())
        r.scan()
        r.scan(delta = mapOf(1 to 1))
        assertEquals(0, r.app.calls.count { it.intent is PartsIntent })
        assertEquals(0, r.app.partsCancellations)
        r.controller.reset()
        assertEquals(0, r.app.partsCancellations)
        val bare = InstrumentController(r.app.instrumentAdapter)
        assertThrows(IllegalStateException::class.java) {
            bare.capabilitiesAccepted(setOf(Wire6.INSTRUMENT, Wire6.PARTS))
        }
        assertThrows(IllegalStateException::class.java) { bare.snapshot() }
    }

    @Test fun mixTurnsAccumulateAndExternalChangeRetiresDrawnContext() {
        val r = Rig(); r.accept(); r.mix()
        val drawn = r.context()
        assertEquals(4, r.controller.snapshot()[0].toInt())
        assertTrue(r.scan(delta = mapOf(3 to 1, 4 to -1, 5 to 1, 6 to 1), drawn = drawn))
        assertEquals(drawn, r.context())
        assertTrue(r.scan(delta = mapOf(3 to 1), drawn = drawn))
        assertEquals(.4f, r.app.parts.current.level!!.value!!, .0001f)
        assertEquals(.4f, r.app.parts.current.pan!!.value!!, .0001f)
        assertTrue(r.app.parts.current.muted!!)
        assertTrue(r.app.parts.current.soloed!!)
        r.app.parts = r.app.parts.copy(revision = r.app.parts.revision + 1,
            parts = r.app.parts.parts.map { if (it.ref == r.app.a) it.copy(muted = false) else it })
        val edits = r.app.calls.count { it.intent is PartsIntent }
        r.scan(delta = mapOf(3 to 1), drawn = drawn)
        assertEquals(edits, r.app.calls.count { it.intent is PartsIntent })
    }

    @Test fun partNavigationReleasesOriginalVoiceBeforeSelectionAndSuppressesSameScanNotes() {
        val r = Rig(); r.accept(); r.scan(); r.scan(keys = 1)
        val voice = r.app.calls.last { it.intent is InstrumentIntent.NoteOn }
        assertEquals(r.app.ia, voice.target)
        r.scan(keys = 3, delta = mapOf(1 to 1, 3 to 1))
        val off = r.app.calls.first { it.intent is InstrumentIntent.NoteOff }
        val select = r.app.calls.first { it.intent is PartsIntent.SelectPart }
        assertEquals(voice.intent.let { (it as InstrumentIntent.NoteOn).voiceId },
            (off.intent as InstrumentIntent.NoteOff).voiceId)
        assertEquals(r.app.ia, off.target)
        assertTrue(off.id < select.id)
        assertEquals(r.app.b, r.app.parts.selected)
        assertEquals(1, r.app.calls.count { it.intent is InstrumentIntent.NoteOn })
        assertEquals(0, r.app.calls.count { it.intent is PartsIntent.SetLevel })
        assertTrue(r.app.activeVoices.isEmpty())
    }

    @Test fun phonePartSelectionRetiresContextAndReleasesOriginalVoice() {
        val r = Rig(); r.accept(); r.mix()
        r.scan(keys = 1)
        val drawn = r.context()
        r.app.parts = r.app.parts.copy(selected = r.app.b, revision = r.app.parts.revision + 1)
        r.app.instrument = r.app.instrument.copy(selected = r.app.ib,
            revision = r.app.instrument.revision + 1)
        assertTrue(r.scan(keys = 3, delta = mapOf(3 to 1), drawn = drawn))
        assertEquals(1, r.app.calls.count { it.intent is InstrumentIntent.NoteOff })
        assertEquals(0, r.app.calls.count { it.intent is PartsIntent.SetLevel })
        assertEquals(1, r.app.calls.count { it.intent is InstrumentIntent.NoteOn })
        assertTrue(r.app.activeVoices.isEmpty())
        assertNotEquals(drawn, r.context())
    }

    @Test fun simultaneousPartAndBankTurnsKeepTheNewPartsBankAtZero() {
        val r = Rig(); r.accept()
        val control = ParameterBanks.Control(ParameterDescriptor("tone", "Tone"), .5f)
        r.app.instrument = r.app.instrument.copy(controls = List(8) { control })
        r.scan()
        r.scan(buttons = 1 shl 4)
        r.scan()
        r.scan(delta = mapOf(0 to 1))
        assertEquals(1, Wire6.u14(r.controller.snapshot(), 34))
        assertTrue(r.scan(delta = mapOf(0 to 1, 1 to 1)))
        assertEquals(r.app.b, r.app.parts.selected)
        assertEquals(0, Wire6.u14(r.controller.snapshot(), 34))
    }

    @Test fun simultaneousPartAndPresetTurnsDoNotSelectFromTheOldInstrumentList() {
        val r = Rig(); r.accept()
        r.app.instrument = r.app.instrument.copy(instruments = listOf(
            InstrumentDescriptor(r.app.ia, "Alpha"),
            InstrumentDescriptor(r.app.ic, "Gamma"),
            InstrumentDescriptor(r.app.ib, "Beta")))
        r.scan()
        assertTrue(r.scan(delta = mapOf(1 to 1, 2 to 1)))
        assertEquals(r.app.b, r.app.parts.selected)
        assertEquals(r.app.ib, r.app.instrument.selected)
        assertTrue(r.app.calls.none { it.intent is InstrumentIntent.SelectInstrument })
    }
}
