/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.instrument

import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.control.ParameterDescriptor
import org.fm1.companion.protocol.Wire6
import org.fm1.companion.transport.PositionUnit
import org.fm1.companion.transport.TransportAdapter
import org.fm1.companion.transport.TransportIntent
import org.fm1.companion.transport.TransportPosition
import org.fm1.companion.transport.TransportState
import org.junit.Assert.*
import org.junit.Test

class InstrumentControllerTest {
    private class Rig {
        val a = ObjectRef("test", ObjectRef.Kind.INSTRUMENT, "a")
        val b = ObjectRef("test", ObjectRef.Kind.INSTRUMENT, "b")
        val knob = ParameterBanks.Control(ParameterDescriptor("gain", "Gain", step = .1f), .2f)
        var state = InstrumentState(listOf(InstrumentDescriptor(a, "Alpha"), InstrumentDescriptor(b, "Beta")),
            a, 1, listOf(knob, null, null, null, knob))
        val operations = mutableListOf<Operation>()
        var completion: ((OperationResult) -> Unit)? = null
        var pending = false
        var failNotes = false
        var afterParameter: (() -> Unit)? = null
        val adapter = object : InstrumentAdapter {
            override val namespace = "test"
            override fun read() = state
            override fun cancelSession(generation: Long) { completion = null }
            override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
                operations += operation
                if (failNotes && (operation.intent is InstrumentIntent.NoteOn ||
                        operation.intent is InstrumentIntent.NoteOff)) return OperationResult(ResultCode.FAILED)
                if (pending && operation.intent is InstrumentIntent.SetParameter) {
                    completion = complete
                    return OperationResult(ResultCode.ACCEPTED_PENDING)
                }
                when (val intent = operation.intent) {
                    is InstrumentIntent.SetParameter -> {
                        if (operation.expectedRevision != state.revision || operation.target != state.selected)
                            return OperationResult(ResultCode.STALE)
                        state = state.copy(revision = state.revision + 1, controls = state.controls.map {
                            if (it?.parameter?.id == intent.parameterId) it.copy(value = intent.value) else it
                        })
                        afterParameter?.invoke()
                    }
                    is InstrumentIntent.SelectInstrument -> state = state.copy(selected = intent.instrument,
                        revision = state.revision + 1)
                    else -> Unit
                }
                return OperationResult(ResultCode.COMPLETED, state.revision)
            }
        }
        val controller = InstrumentController(adapter)
        var serial = 0
        fun context() = Wire6.u28(controller.snapshot(), 2)
        fun scan(buttons: Int = 0, keys: Long = 0, deltas: Map<Int, Int> = emptyMap(),
                 context: Int = context()): Boolean {
            val p = ByteArray(24)
            Wire6.put14(p, 0, buttons)
            Wire6.put28(p, 2, keys.toInt())
            for (i in 0..6) Wire6.put14(p, 6 + i * 2, 8192 + (deltas[i] ?: 0))
            Wire6.put28(p, 20, context)
            return controller.input(++serial, p)
        }
        fun sound() {
            scan()
            scan(buttons = 1 shl 4)
            scan()
        }
    }

    private class TransportRig {
        val instrument = Rig()
        val target = ObjectRef("test", ObjectRef.Kind.TRANSPORT, "main")
        var state = TransportState(target, 1, false, false, 120f, 40f, 240f)
        val operations = mutableListOf<Operation>()
        var cancellations = 0
        var stopFailure = false
        var startPending = false
        val transport = object : TransportAdapter {
            override val namespace = "test"
            override fun read() = state
            override fun cancelSession(generation: Long) { cancellations++ }
            override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
                operations += operation
                if (operation.intent is TransportIntent.Stop && stopFailure)
                    return OperationResult(ResultCode.FAILED)
                if (operation.intent is TransportIntent.Start && startPending)
                    return OperationResult(ResultCode.ACCEPTED_PENDING)
                if (operation.target != state.target ||
                    (operation.intent !is TransportIntent.Stop && operation.expectedRevision != state.revision))
                    return OperationResult(ResultCode.STALE)
                state = when (val intent = operation.intent) {
                    TransportIntent.Start -> state.copy(revision = state.revision + 1, playing = true)
                    TransportIntent.Stop -> state.copy(revision = state.revision + 1, playing = false)
                    is TransportIntent.SetTempo -> state.copy(revision = state.revision + 1, tempo = intent.value)
                    else -> return OperationResult(ResultCode.UNSUPPORTED)
                }
                return OperationResult(ResultCode.COMPLETED, state.revision)
            }
        }
        val controller = InstrumentController(instrument.adapter, transport)
        var serial = 0
        fun accept() { controller.capabilitiesAccepted(setOf(Wire6.TRANSPORT)) }
        fun view() = controller.snapshot()
        fun context() = Wire6.u28(view(), 2)
        fun scan(buttons: Int = 0, keys: Long = 0, select: Int = 0,
                 context: Int = context()): Boolean {
            val p = ByteArray(24)
            Wire6.put14(p, 0, buttons)
            Wire6.put28(p, 2, keys.toInt())
            for (i in 0..6) Wire6.put14(p, 6 + i * 2, 8192 + if (i == 0) select else 0)
            Wire6.put28(p, 20, context)
            return controller.input(++serial, p)
        }
    }

    @Test fun firstHeldScanIsBaselineAndDuplicatesDoNotReplayNotes() {
        val r = Rig()
        assertTrue(r.scan(keys = 1))
        assertTrue(r.operations.isEmpty())
        assertTrue(r.controller.input(r.serial, ByteArray(24)))
        assertTrue(r.operations.isEmpty())
        assertTrue(r.scan())
        assertTrue(r.scan(keys = 1))
        assertEquals(1, r.operations.count { it.intent is InstrumentIntent.NoteOn })
    }

    @Test fun serialGapFailsClosedAndCancelsHeldVoice() {
        val r = Rig(); r.scan(); r.scan(keys = 1)
        assertFalse(r.controller.input(r.serial + 2, ByteArray(24)))
        assertThrows(IllegalStateException::class.java) { r.controller.snapshot() }
    }

    @Test fun failedNoteStopsSessionImmediately() {
        val r = Rig(); r.scan(); r.failNotes = true
        assertFalse(r.scan(keys = 1))
        assertThrows(IllegalStateException::class.java) { r.controller.snapshot() }
    }

    @Test fun ownRelativeTurnsAccumulateButExternalRevisionInvalidatesDrawnContext() {
        val r = Rig(); r.sound()
        r.state = r.state.copy(revision = r.state.revision + 1,
            controls = listOf(r.knob, r.knob))
        val drawn = r.context()
        assertTrue(r.scan(deltas = mapOf(3 to 1, 4 to 1), context = drawn))
        assertEquals(drawn, r.context())
        assertTrue(r.scan(deltas = mapOf(3 to 1), context = drawn))
        assertEquals(3, r.operations.count { it.intent is InstrumentIntent.SetParameter })
        assertEquals(.5f, r.state.controls[0]!!.value!!, .0001f)
        r.state = r.state.copy(revision = r.state.revision + 1)
        assertTrue(r.scan(deltas = mapOf(3 to 1), context = drawn))
        assertEquals(3, r.operations.count { it.intent is InstrumentIntent.SetParameter })
    }

    @Test fun navigationSuppressesKnobAndFreshNoteInSameScan() {
        val r = Rig(); r.sound()
        r.scan(keys = 1, deltas = mapOf(0 to 1, 3 to 1))
        assertTrue(r.operations.none { it.intent is InstrumentIntent.SetParameter || it.intent is InstrumentIntent.NoteOn })
        assertEquals(1, Wire6.u14(r.controller.snapshot(), 34))
    }

    @Test fun selectingAnotherInstrumentReleasesOriginalVoiceAndHeldKeyDoesNotRetrigger() {
        val r = Rig(); r.scan(); r.scan(keys = 1)
        val on = r.operations.last().intent as InstrumentIntent.NoteOn
        r.scan(keys = 1, deltas = mapOf(2 to 1))
        val off = r.operations.first { it.intent is InstrumentIntent.NoteOff }
        assertEquals(r.a, off.target)
        assertEquals(on.voiceId, (off.intent as InstrumentIntent.NoteOff).voiceId)
        assertEquals(r.b, r.state.selected)
        assertEquals(1, r.operations.count { it.intent is InstrumentIntent.NoteOn })
        r.scan(keys = 1)
        assertEquals(1, r.operations.count { it.intent is InstrumentIntent.NoteOn })
    }

    @Test fun externalSelectionStillReleasesOriginalVoiceOnStaleInput() {
        val r = Rig(); r.scan(); r.scan(keys = 1)
        val old = r.context()
        r.state = r.state.copy(selected = r.b, revision = r.state.revision + 1)
        assertTrue(r.scan(keys = 0, deltas = mapOf(3 to 1), context = old))
        assertEquals(r.a, r.operations.last().target)
        assertTrue(r.operations.last().intent is InstrumentIntent.NoteOff)
        assertTrue(r.operations.none { it.intent is InstrumentIntent.SetParameter })
    }

    @Test fun failedReleaseOfMultipleVoicesReturnsFalseWithoutAnotherRequest() {
        val r = Rig(); r.scan(); r.scan(keys = 3)
        val old = r.context()
        r.state = r.state.copy(selected = r.b, revision = r.state.revision + 1)
        r.failNotes = true
        assertFalse(r.scan(keys = 0, context = old))
        assertEquals(1, r.operations.count { it.intent is InstrumentIntent.NoteOff })
    }

    @Test fun missingControlAndUnsupportedButtonsProduceAcknowledgedFeedback() {
        val r = Rig(); r.sound()
        r.scan(deltas = mapOf(4 to 1))
        val first = r.controller.snapshot()
        assertEquals(3, first[0].toInt())
        assertEquals(ResultCode.UNSUPPORTED.ordinal, first[34].toInt())
        assertEquals(Wire6.u28(first, 2), Wire6.u28(r.controller.snapshot(), 2))
        r.controller.acknowledged(Wire6.u28(first, 2))
        assertEquals(1, r.controller.snapshot()[0].toInt())
        r.scan(buttons = 1 shl 10)
        assertEquals(3, r.controller.snapshot()[0].toInt())
    }

    @Test fun pendingAndTerminalResultsEachRemainUntilDisplayAcknowledgement() {
        val r = Rig(); r.sound(); r.pending = true
        r.scan(deltas = mapOf(3 to 1))
        val pending = r.controller.snapshot()
        assertEquals(ResultCode.ACCEPTED_PENDING.ordinal, pending[34].toInt())
        r.completion!!.invoke(OperationResult(ResultCode.FAILED, message = "Rejected"))
        assertEquals(Wire6.u28(pending, 2), Wire6.u28(r.controller.snapshot(), 2))
        r.controller.acknowledged(Wire6.u28(pending, 2))
        val failed = r.controller.snapshot()
        assertEquals(ResultCode.FAILED.ordinal, failed[34].toInt())
        r.controller.acknowledged(Wire6.u28(failed, 2))
        assertEquals(1, r.controller.snapshot()[0].toInt())
    }

    @Test fun pendingSuccessIsDisplayedAfterPendingAcknowledgement() {
        val r = Rig(); r.sound(); r.pending = true
        r.scan(deltas = mapOf(3 to 1))
        val pending = r.controller.snapshot()
        r.state = r.state.copy(revision = r.state.revision + 1)
        r.completion!!.invoke(OperationResult(ResultCode.COMPLETED, r.state.revision))
        assertEquals(Wire6.u28(pending, 2), Wire6.u28(r.controller.snapshot(), 2))
        r.controller.acknowledged(Wire6.u28(pending, 2))
        val completed = r.controller.snapshot()
        assertEquals(3, completed[0].toInt())
        assertEquals(ResultCode.COMPLETED.ordinal, completed[34].toInt())
    }

    @Test fun changedControlLayoutCannotRetargetSecondTurnInSameScan() {
        val r = Rig(); r.sound()
        val other = ParameterBanks.Control(ParameterDescriptor("tone", "Tone", step = .1f), .2f)
        val replacement = ParameterBanks.Control(ParameterDescriptor("danger", "Danger", step = .1f), .2f)
        r.state = r.state.copy(revision = r.state.revision + 1,
            controls = listOf(r.knob, other))
        val drawn = r.context()
        r.afterParameter = { r.state = r.state.copy(controls = listOf(r.state.controls[0], replacement)) }
        assertTrue(r.scan(deltas = mapOf(3 to 1, 4 to 1), context = drawn))
        assertEquals(1, r.operations.count { it.intent is InstrumentIntent.SetParameter })
    }

    @Test fun statusDisplayRetiresOldBankContextEvenAfterAcknowledgement() {
        val r = Rig(); r.sound()
        val old = r.context()
        r.scan(deltas = mapOf(4 to 1), context = old)
        val status = r.controller.snapshot()
        assertEquals(3, status[0].toInt())
        r.scan(deltas = mapOf(3 to 1), context = old)
        assertTrue(r.operations.none { it.intent is InstrumentIntent.SetParameter })
        r.controller.acknowledged(Wire6.u28(status, 2))
        val bank = r.controller.snapshot()
        assertNotEquals(old, Wire6.u28(bank, 2))
        r.scan(deltas = mapOf(3 to 1), context = old)
        assertTrue(r.operations.none { it.intent is InstrumentIntent.SetParameter })
    }

    @Test fun completionFromOldSessionCannotAppearAfterReset() {
        val r = Rig(); r.sound(); r.pending = true
        r.scan(deltas = mapOf(3 to 1))
        val late = r.completion!!
        r.controller.reset()
        late(OperationResult(ResultCode.FAILED, message = "Old failure"))
        assertEquals(2, r.controller.snapshot()[0].toInt())
    }

    @Test fun acceptedTransportWithoutAdapterFailsClosed() {
        val r = Rig()
        assertThrows(IllegalStateException::class.java) {
            r.controller.capabilitiesAccepted(setOf(Wire6.TRANSPORT))
        }
        assertThrows(IllegalStateException::class.java) { r.controller.snapshot() }
        assertFalse(r.scan(context = 0))
        assertTrue(r.operations.isEmpty())
    }

    @Test fun transportRequiresAcceptedCapabilityAndResetsToDisabled() {
        val r = TransportRig()
        assertEquals(0, r.view()[34].toInt())
        r.scan(); r.scan(buttons = 1 shl 10)
        assertTrue(r.operations.isEmpty())
        r.controller.capabilitiesAccepted(setOf(Wire6.INSTRUMENT))
        r.scan(); r.scan(buttons = 1 shl 10)
        assertTrue(r.operations.isEmpty())
        r.controller.reset()
        r.accept()
        assertEquals(1200, Wire6.u14(r.view(), 35))
        r.scan(); r.scan(buttons = 1 shl 10)
        assertEquals(1, r.operations.size)
        r.controller.reset()
        assertEquals(1, r.cancellations)
        assertEquals(0, r.view()[34].toInt())
    }

    @Test fun playRestartStopAndTempoUseOneTransportSession() {
        val r = TransportRig(); r.accept(); r.scan()
        r.scan(buttons = 1 shl 10)
        assertTrue(r.state.playing)
        assertTrue(r.controller.input(r.serial, ByteArray(24)))
        assertEquals(1, r.operations.size)
        assertEquals(1, r.view()[34].toInt())
        r.scan(keys = 1)
        assertEquals(1, r.instrument.operations.count { it.intent is InstrumentIntent.NoteOn })
        r.scan(buttons = (1 shl 5) or (1 shl 10), keys = 1)
        assertEquals(2, r.operations.count { it.intent is TransportIntent.Start })
        val released = r.instrument.operations.last()
        assertTrue(released.intent is InstrumentIntent.NoteOff)
        assertTrue(released.id < r.operations.last().id)
        r.scan(buttons = 1 shl 5, select = 2)
        assertEquals(122f, r.state.tempo)
        assertEquals(1220, Wire6.u14(r.view(), 35))
        r.scan(buttons = 0)
        r.scan(buttons = 1 shl 10, keys = 1)
        assertFalse(r.state.playing)
        assertEquals(1, r.instrument.operations.count { it.intent is InstrumentIntent.NoteOn })
    }

    @Test fun staleEditsAreRejectedButStopWorksFromStatusAndOldContext() {
        val r = TransportRig(); r.accept(); r.scan()
        val old = r.context()
        r.state = r.state.copy(revision = 2, playing = true)
        r.scan(buttons = 1 shl 5, select = 1, context = old)
        assertTrue(r.operations.isEmpty())
        r.scan(buttons = 1 shl 11, context = r.context())
        val status = r.context()
        assertEquals(3, r.view()[0].toInt())
        r.scan(context = status)
        r.scan(buttons = 1 shl 10, context = status)
        assertEquals(listOf(TransportIntent.Stop), r.operations.map { it.intent })
        r.controller.reset(); r.accept(); r.scan()
        val fresh = r.context()
        r.state = r.state.copy(revision = r.state.revision + 1)
        r.scan(buttons = 1 shl 10, context = fresh)
        assertEquals(1, r.operations.size)
    }

    @Test fun positionTicksDoNotRetireTempoContextButExternalTempoDoes() {
        val r = TransportRig(); r.accept(); r.scan()
        val drawn = r.context()
        r.state = r.state.copy(position = TransportPosition(32, PositionUnit.TICKS, 96))
        assertEquals(drawn, r.context())
        assertEquals(32, Wire6.u28(r.view(), 37))
        assertEquals(2, r.view()[41].toInt())
        r.state = r.state.copy(position = TransportPosition(0x0fffffff, PositionUnit.TICKS, 96))
        assertEquals("TK 268435455", String(r.view(), 22, 12, Charsets.US_ASCII))
        r.scan(buttons = 1 shl 5, select = 1, context = drawn)
        assertEquals(121f, r.state.tempo)
        r.scan(buttons = 1 shl 5, select = 1, context = drawn)
        assertEquals(122f, r.state.tempo)
        r.state = r.state.copy(revision = r.state.revision + 1, tempo = 130f)
        r.scan(buttons = 1 shl 5, select = 1, context = drawn)
        assertEquals(130f, r.state.tempo)
    }

    @Test fun failedStopClosesSessionAndCancelsTransport() {
        val r = TransportRig(); r.accept(); r.scan()
        r.scan(buttons = 1 shl 10)
        r.stopFailure = true
        r.scan()
        assertFalse(r.scan(buttons = 1 shl 10))
        assertEquals(1, r.cancellations)
        assertThrows(IllegalStateException::class.java) { r.controller.snapshot() }
    }

    @Test fun pendingStartClosesSessionAndCancelsTransport() {
        val r = TransportRig(); r.accept(); r.scan()
        r.startPending = true
        assertFalse(r.scan(buttons = 1 shl 10))
        assertEquals(1, r.cancellations)
        assertThrows(IllegalStateException::class.java) { r.controller.snapshot() }
    }
}
