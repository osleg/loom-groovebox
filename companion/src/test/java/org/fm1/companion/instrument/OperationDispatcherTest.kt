/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.instrument

import org.junit.Assert.*
import org.junit.Test
import org.fm1.companion.transport.TransportIntent
import org.fm1.companion.parts.PartsIntent
import org.fm1.companion.sequence.SequenceIntent

class OperationDispatcherTest {
    private class App : OperationAdapter {
        override val namespace = "test.instrument"
        val ref = ObjectRef(namespace, ObjectRef.Kind.INSTRUMENT, "tone")
        val transport = ObjectRef(namespace, ObjectRef.Kind.TRANSPORT, "transport")
        var revision = 0L
        var calls = 0
        var defer = false
        var deferNote = false
        var value = 0f
        val completions = mutableListOf<() -> Unit>()
        val cancelled = mutableListOf<Long>()
        val voices = mutableSetOf<Long>()
        override fun cancelSession(generation: Long) { cancelled += generation; voices.clear() }
        override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
            calls++
            val intent = operation.intent
            if (intent is TransportIntent) return OperationResult(
                if (intent == TransportIntent.Start && deferNote) ResultCode.ACCEPTED_PENDING else ResultCode.COMPLETED,
                revision)
            if (intent is InstrumentIntent.NoteOff) {
                voices.remove(intent.voiceId)
                return OperationResult(ResultCode.COMPLETED, revision)
            }
            if (operation.target != ref || operation.expectedRevision != revision) return OperationResult(ResultCode.STALE)
            if (intent is InstrumentIntent.NoteOn) {
                voices += intent.voiceId
                return OperationResult(if (deferNote) ResultCode.ACCEPTED_PENDING else ResultCode.COMPLETED, revision)
            }
            fun apply(): OperationResult {
                if (intent is InstrumentIntent.SetParameter) value = intent.value
                return OperationResult(ResultCode.COMPLETED, ++revision)
            }
            if (!defer) return apply()
            completions += { complete(apply()) }
            return OperationResult(ResultCode.ACCEPTED_PENDING)
        }
    }

    private fun edit(dispatcher: OperationDispatcher, app: App, value: Float = .5f) =
        dispatcher.request(app.ref, app.revision, InstrumentIntent.SetParameter("level", value))

    @Test fun retryReturnsReceiptWithoutRepeatingTheMutationAndOldReceiptsNeverExecute() {
        val app = App()
        val dispatcher = OperationDispatcher(app)
        dispatcher.begin()
        val first = edit(dispatcher, app)
        val result = dispatcher.submit(first)
        assertEquals(result, dispatcher.submit(first))
        assertEquals(ResultCode.INVALID, dispatcher.submit(first.copy(intent = InstrumentIntent.SetParameter("level", .2f))).code)
        assertEquals(1, app.calls)
        repeat(65) { assertEquals(ResultCode.COMPLETED, dispatcher.submit(edit(dispatcher, app)).code) }
        assertEquals(ResultCode.STALE, dispatcher.submit(first).code)
        assertEquals(66, app.calls)
    }

    @Test fun pendingCapacityNeverBlocksReleaseAndCompletionRetiresExactlyOneAdmission() {
        val app = App().also { it.defer = true; it.voices += 77L }
        val events = mutableListOf<ResultCode>()
        val dispatcher = OperationDispatcher(app) { _, result -> events += result.code }
        dispatcher.begin()
        val first = edit(dispatcher, app)
        assertEquals(ResultCode.ACCEPTED_PENDING, dispatcher.submit(first).code)
        repeat(7) { dispatcher.submit(edit(dispatcher, app)) }
        val busy = edit(dispatcher, app)
        assertEquals(ResultCode.BUSY, dispatcher.submit(busy).code)
        assertEquals(8, app.calls)
        val release = dispatcher.request(app.ref, 999, InstrumentIntent.NoteOff(77))
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(release).code)
        assertTrue(app.voices.isEmpty())
        app.completions[0]()
        val eventsAfterCompletion = events.size
        app.completions[0]() // Erroneous repeated adapter notification cannot publish a second terminal result.
        assertEquals(eventsAfterCompletion, events.size)
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(first).code)
        assertEquals(ResultCode.BUSY, dispatcher.submit(busy).code)
        assertEquals(ResultCode.ACCEPTED_PENDING, dispatcher.submit(edit(dispatcher, app)).code)
        assertEquals(10, app.calls)
    }

    @Test fun cancellationDiscardsOldCompletionAndInvalidArgumentsNeverReachTheAdapter() {
        val app = App().also { it.defer = true }
        val events = mutableListOf<ResultCode>()
        val dispatcher = OperationDispatcher(app) { _, result -> events += result.code }
        val generation = dispatcher.begin()
        val old = edit(dispatcher, app)
        dispatcher.submit(old)
        dispatcher.begin()
        assertEquals(listOf(generation), app.cancelled)
        app.completions.single()()
        assertEquals(listOf(ResultCode.ACCEPTED_PENDING), events)
        assertEquals(ResultCode.STALE, dispatcher.submit(old).code)
        assertEquals(ResultCode.INVALID, dispatcher.submit(edit(dispatcher, app, Float.NaN)).code)
        assertEquals(1, app.calls)
    }

    @Test fun delayedPerformanceStartCancelsTheSessionInsteadOfOutlivingTermination() {
        for (transport in listOf(false, true)) {
            val app = App().also { it.deferNote = true }
            val dispatcher = OperationDispatcher(app)
            val generation = dispatcher.begin()
            val start = if (transport) dispatcher.request(app.transport, 0, TransportIntent.Start)
                else dispatcher.request(app.ref, 0, InstrumentIntent.NoteOn(1, 60, 100))
            assertEquals(ResultCode.FAILED, dispatcher.submit(start).code)
            assertEquals(listOf(generation), app.cancelled)
            assertTrue(app.voices.isEmpty())
            assertEquals(ResultCode.STALE, dispatcher.submit(start).code)
        }
    }

    @Test fun transportStopBypassesFullAdmissionAndRetriesWithoutRepeatingTheCall() {
        val app = App().also { it.defer = true }
        val dispatcher = OperationDispatcher(app)
        dispatcher.begin()
        repeat(8) { assertEquals(ResultCode.ACCEPTED_PENDING, dispatcher.submit(edit(dispatcher, app)).code) }
        val stop = dispatcher.request(app.transport, 999, TransportIntent.Stop)
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(stop).code)
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(stop).code)
        assertEquals(9, app.calls)
        assertEquals(ResultCode.BUSY, dispatcher.submit(dispatcher.request(app.transport, 0, TransportIntent.Start)).code)
        assertEquals(ResultCode.INVALID, dispatcher.submit(dispatcher.request(app.ref, 0, TransportIntent.Stop)).code)
        assertEquals(ResultCode.INVALID, dispatcher.submit(dispatcher.request(app.transport, 0,
            TransportIntent.SetTempo(Float.NaN))).code)
        assertEquals(9, app.calls)
    }

    @Test fun partsAreTargetValidatedAndPendingSelectionEndsTheSession() {
        val app = App()
        val dispatcher = OperationDispatcher(app)
        dispatcher.begin()
        val part = ObjectRef(app.namespace, ObjectRef.Kind.PART, "track")
        val foreign = ObjectRef("other", ObjectRef.Kind.PART, "track")
        val invalid = dispatcher.request(part, 0, PartsIntent.SelectPart(foreign))
        assertEquals(ResultCode.INVALID, dispatcher.submit(invalid).code)
        assertEquals(0, app.calls)
        val pendingApp = object : OperationAdapter {
            override val namespace = app.namespace
            var calls = 0
            var cancelled = 0
            override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
                calls++
                return OperationResult(ResultCode.ACCEPTED_PENDING)
            }
            override fun cancelSession(generation: Long) { cancelled++ }
        }
        val pending = OperationDispatcher(pendingApp)
        pending.begin()
        val selection = pending.request(part, 0, PartsIntent.SelectPart(part))
        assertEquals(ResultCode.FAILED, pending.submit(selection).code)
        assertEquals(1, pendingApp.calls)
        assertEquals(1, pendingApp.cancelled)
        assertFalse(pending.isActive)
    }

    @Test fun sequenceRetryIsDeduplicatedAndPendingEditsEndTheSession() {
        val app = object : OperationAdapter {
            override val namespace = "test.sequence"
            var calls = 0
            var pending = false
            var cancelled = 0
            override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
                calls++
                return if (pending) OperationResult(ResultCode.ACCEPTED_PENDING)
                    else OperationResult(ResultCode.COMPLETED, calls.toLong())
            }
            override fun cancelSession(generation: Long) { cancelled++ }
        }
        val dispatcher = OperationDispatcher(app)
        val pattern = ObjectRef(app.namespace, ObjectRef.Kind.PATTERN, "pattern")
        dispatcher.begin()
        val edit = dispatcher.request(pattern, 0, SequenceIntent.ClearSteps(listOf(0)))
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(edit).code)
        assertEquals(ResultCode.COMPLETED, dispatcher.submit(edit).code)
        assertEquals(1, app.calls)
        assertEquals(ResultCode.INVALID, dispatcher.submit(dispatcher.request(pattern, 1,
            SequenceIntent.ClearSteps(listOf(-1)))).code)
        assertEquals(1, app.calls)
        app.pending = true
        val delayed = dispatcher.request(pattern, 1, SequenceIntent.SetStepsActive(listOf(0), true))
        assertEquals(ResultCode.FAILED, dispatcher.submit(delayed).code)
        assertFalse(dispatcher.isActive)
        assertEquals(1, app.cancelled)
    }
}
