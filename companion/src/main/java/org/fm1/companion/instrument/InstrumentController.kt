/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.instrument

import org.fm1.companion.protocol.OpenMidiConnection
import org.fm1.companion.protocol.Wire6
import org.fm1.companion.parts.PartsAdapter
import org.fm1.companion.parts.PartsControls
import org.fm1.companion.parts.PartsIntent
import org.fm1.companion.parts.PartsState
import org.fm1.companion.parts.SoloMode
import org.fm1.companion.transport.PositionUnit
import org.fm1.companion.transport.TransportAdapter
import org.fm1.companion.transport.TransportControls
import org.fm1.companion.transport.TransportIntent
import org.fm1.companion.transport.TransportState
import java.util.ArrayDeque
import kotlin.math.roundToInt

/** Owner-thread interpretation of the FM1 instrument profile and optional transport. */
class InstrumentController(private val adapter: InstrumentAdapter,
                           private val transport: TransportAdapter? = null,
                           private val parts: PartsAdapter? = null) : OpenMidiConnection.Surface {
    private var transportAccepted = false
    private var partsAccepted = false
    private val operations = object : OperationAdapter {
        override val namespace = adapter.namespace
        override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult =
            when (operation.intent) {
                is InstrumentIntent -> adapter.execute(operation, complete)
                is TransportIntent -> if (transportAccepted && transport != null)
                    transport.execute(operation, complete) else OperationResult(ResultCode.UNSUPPORTED)
                is PartsIntent -> if (partsAccepted && parts != null)
                    parts.execute(operation, complete) else OperationResult(ResultCode.UNSUPPORTED)
                else -> OperationResult(ResultCode.INVALID)
            }
        override fun cancelSession(generation: Long) {
            try { adapter.cancelSession(generation) }
            finally {
                if (transportAccepted) transport?.cancelSession(generation)
                if (partsAccepted) parts?.cancelSession(generation)
            }
        }
    }
    private val dispatcher = OperationDispatcher(operations, ::operationResult)
    private val transportControls = transport?.let { TransportControls(it, ::submitTransport) }
    private val partsControls = parts?.let { PartsControls(it, ::submitParts) }
    private data class Voice(val target: ObjectRef, val id: Long)
    private data class Feedback(val result: OperationResult, val title: String, val subtitle: String,
                                var context: Int = 0)
    private val voices = arrayOfNulls<Voice>(27)
    private val feedback = ArrayDeque<Feedback>()
    private val pendingIds = HashSet<Long>()
    private var nextVoice = 1L
    private var nextContext = 1
    private var context = 0
    private var lineage: InstrumentState? = null
    private var transportLineage: TransportState? = null
    private var partsLineage: PartsState? = null
    private var pageSound = false
    private var pageMix = false
    private var bank = 0
    private var octave = 0
    private var buttons = 0
    private var keys = 0L
    private var serial = -1
    private var first = true
    private var failed = false

    init {
        require(transport == null || transport.namespace == adapter.namespace) {
            "Transport and instrument namespaces differ"
        }
        require(parts == null || parts.namespace == adapter.namespace) {
            "Parts and instrument namespaces differ"
        }
        dispatcher.begin()
    }

    override fun capabilitiesAccepted(accepted: Set<Int>) {
        if ((Wire6.TRANSPORT in accepted && transport == null) ||
            (Wire6.PARTS in accepted && parts == null)) {
            failClosed()
            throw IllegalStateException("Accepted capability without adapter")
        }
        transportAccepted = Wire6.TRANSPORT in accepted
        partsAccepted = Wire6.PARTS in accepted
        transportLineage = null
        partsLineage = null
        invalidate()
    }

    override fun reset() {
        dispatcher.end()
        transportAccepted = false
        partsAccepted = false
        voices.fill(null)
        dispatcher.begin()
        feedback.clear()
        pendingIds.clear()
        buttons = 0; keys = 0; serial = -1; first = true
        lineage = null; context = 0; nextContext = 1
        transportLineage = null
        partsLineage = null
        pageSound = false; pageMix = false; bank = 0; octave = 0; failed = false
    }

    private fun token(): Int {
        if (nextContext > 0x0fffffff) { failClosed(); return 0 }
        return nextContext++
    }

    private fun failClosed() {
        if (failed) return
        failed = true
        dispatcher.end()
        voices.fill(null)
        pendingIds.clear()
        transportAccepted = false
        partsAccepted = false
    }

    private fun state(): InstrumentState {
        val current = adapter.read()
        val previous = lineage
        if (previous != current) {
            if (previous != null && previous.selected != current.selected) {
                releaseVoices()
                bank = 0
            }
            lineage = current
            context = token()
            bank = bank.coerceIn(0, maxOf(0, (current.controls.size - 1) / 4))
        }
        return current
    }

    private fun invalidate() { context = token() }

    private fun transportState(): TransportState? {
        if (!transportAccepted) return null
        val current = transportControls!!.state()
        val previous = transportLineage
        if (previous == null || !sameTransportEditState(previous, current)) {
            transportLineage = current
            context = token()
        }
        return current
    }

    private fun sameTransportEditState(before: TransportState, after: TransportState): Boolean =
        before.copy(position = null) == after.copy(position = null)

    private fun partsState(): PartsState? {
        if (!partsAccepted) return null
        val current = partsControls!!.state()
        val previous = partsLineage
        if (previous != current) {
            if (previous != null && previous.selected != current.selected) {
                releaseVoices()
                bank = 0
            }
            partsLineage = current
            context = token()
        }
        return current
    }

    override fun snapshot(): ByteArray {
        check(!failed) { "Instrument controller stopped safely" }
        val s = state()
        val t = transportState()
        val p = partsState()
        check(!failed) { "Instrument controller stopped safely" }
        val notice = feedback.firstOrNull()
        if (notice != null) {
            if (notice.context == 0) notice.context = token()
            check(!failed) { "Instrument controller stopped safely" }
            val flags = (if (notice.result.code == ResultCode.ACCEPTED_PENDING) 1 else 0) or
                (if (notice.result.code !in listOf(ResultCode.COMPLETED, ResultCode.ACCEPTED_PENDING)) 2 else 0)
            return Wire6.viewPrefix(3, flags, notice.context, notice.title, notice.subtitle).also {
                it[34] = notice.result.code.ordinal.toByte()
                Wire6.validateView(it)
            }
        }
        val selectedIndex = s.instruments.indexOfFirst { it.ref == s.selected }
        val name = s.instruments[selectedIndex].name
        if (pageMix && p != null) return mixerView(p)
        if (!pageSound) return Wire6.viewPrefix(2, 0, context, name,
            t?.position?.let { "${if (it.unit == PositionUnit.TICKS) "TK" else "MS"} ${it.value}" }
                ?: "INST ${selectedIndex + 1}/${s.instruments.size}").also {
            if (t != null) {
                it[34] = ((if (t.playing) 1 else 0) or (if (t.recording) 2 else 0)).toByte()
                Wire6.put14(it, 35, (t.tempo * 10).roundToInt().coerceIn(1, 16383))
                Wire6.put28(it, 37, t.position?.value ?: 0)
                it[41] = when (t.position?.unit) {
                    null -> 0
                    PositionUnit.MILLISECONDS -> 1
                    PositionUnit.TICKS -> 2
                }.toByte()
            }
            Wire6.validateView(it)
        }
        val count = maxOf(1, (s.controls.size + 3) / 4)
        bank = bank.coerceIn(0, count - 1)
        return Wire6.viewPrefix(1, 0, context, name, "BANK ${bank + 1}/$count").also { p ->
            Wire6.put14(p, 34, bank)
            Wire6.put14(p, 36, count)
            Wire6.put14(p, 38, selectedIndex)
            Wire6.put14(p, 40, s.instruments.size)
            for (slot in 0..3) {
                val at = 42 + slot * 24
                Wire6.text(p, at, 8, "")
                Wire6.text(p, at + 8, 12, "")
                val control = s.controls.getOrNull(bank * 4 + slot) ?: continue
                val d = control.parameter
                Wire6.text(p, at, 8, d.label)
                Wire6.text(p, at + 8, 12, d.display(control.value))
                Wire6.put14(p, at + 20, d.normalized(control.value))
                p[at + 22] = (if (d.choices.isEmpty()) 1 else 2).toByte()
                p[at + 23] = 1
            }
            Wire6.validateView(p)
        }
    }

    private fun mixerView(state: PartsState): ByteArray {
        val selected = state.parts.indexOfFirst { it.ref == state.selected }
        val first = selected / 8 * 8
        val current = state.current
        return Wire6.viewPrefix(4, 0, context, current.name,
            "PART ${selected + 1}/${state.parts.size}").also { view ->
            Wire6.put14(view, 34, selected)
            Wire6.put14(view, 36, state.parts.size)
            Wire6.put14(view, 38, first)
            view[40] = minOf(8, state.parts.size - first).toByte()
            view[41] = (if (state.soloMode == SoloMode.EXCLUSIVE) 1 else 0).toByte()
            for (slot in 0..7) {
                val part = state.parts.getOrNull(first + slot)
                val at = 42 + slot * 8
                Wire6.text(view, at, 5, part?.name ?: "")
                if (part == null) continue
                part.level?.let { Wire6.put14(view, at + 5, it.parameter.normalized(it.value)) }
                view[at + 7] = ((if (part.muted == true) 1 else 0) or
                    (if (part.soloed == true) 2 else 0) or
                    (if (part.enabled) 4 else 0) or
                    (if (part.level != null) 8 else 0) or
                    (if (part.muted != null) 16 else 0) or
                    (if (part.soloed != null) 32 else 0)).toByte()
            }
            Wire6.text(view, 106, 12, current.level?.let { it.parameter.display(it.value) } ?: "")
            Wire6.text(view, 118, 12, current.pan?.let { it.parameter.display(it.value) } ?: "")
            current.pan?.let { Wire6.put14(view, 130, it.parameter.normalized(it.value)) }
            view[132] = ((if (current.level != null) 1 else 0) or
                (if (current.pan != null) 2 else 0) or
                (if (current.muted != null) 4 else 0) or
                (if (current.soloed != null) 8 else 0)).toByte()
            Wire6.validateView(view)
        }
    }

    override fun acknowledged(context: Int) {
        if (feedback.firstOrNull()?.context == context) feedback.removeFirst()
    }

    override fun input(serial: Int, scan: ByteArray): Boolean {
        if (failed || serial !in 0..16383 || scan.size != 24 || scan.any { it.toInt() < 0 } ||
            (scan[5].toInt() and 64) != 0) return false
        if (this.serial >= 0) {
            val distance = (serial - this.serial) and 16383
            if (distance == 0) return true
            if (distance != 1) { failClosed(); return false }
        }
        this.serial = serial
        val s = state()
        val t = transportState()
        val p = partsState()
        if (failed) return false
        val nextButtons = Wire6.u14(scan, 0)
        val nextKeys = Wire6.u28(scan, 2).toLong()
        val pressed = if (first) 0 else nextButtons and buttons.inv()
        val down = if (first) 0L else nextKeys and keys.inv()
        val up = keys and nextKeys.inv()
        buttons = nextButtons; keys = nextKeys
        val initial = first; first = false
        for (key in voices.indices) if ((up and (1L shl key)) != 0L) release(key)
        if (failed) return false
        if (initial) return true
        val drawn = Wire6.u28(scan, 20)
        val glo = (nextButtons and (1 shl 5)) != 0
        val play = (pressed and (1 shl 10)) != 0
        if (t != null && play && (t.playing && !glo ||
                (drawn == context && feedback.isEmpty()))) {
            releaseVoices()
            if (failed) return false
            val result = if (t.playing && !glo) transportControls!!.stop()
                else transportControls!!.start(t.context)
            if (result.code == ResultCode.COMPLETED) transportState()
            return !failed
        }
        // A queued status is a real display context, never an edit authorization.
        if (drawn != context) return true
        if (t != null && glo && deltasFrom(scan, 0) != 0) {
            val result = transportControls!!.adjustTempo(deltasFrom(scan, 0), t.context)
            if (failed) return false
            val after = transportControls!!.state()
            if (result.code == ResultCode.COMPLETED && result.revision == after.revision &&
                sameTransportLayout(t, after)) transportLineage = after
            else invalidate()
            return !failed
        }
        val deltas = IntArray(7) { Wire6.u14(scan, 6 + it * 2) - 8192 }
        val navigation = (pressed and ((1 shl 1) or (1 shl 4) or (1 shl 5) or (1 shl 6))) != 0 ||
            deltas[0] != 0 || deltas[1] != 0 || deltas[2] != 0
        if ((pressed and (1 shl 6)) != 0) { pageSound = false; pageMix = false; invalidate() }
        if ((pressed and ((1 shl 1) or (1 shl 4))) != 0) {
            pageSound = true; pageMix = false; invalidate()
        }
        if ((pressed and (1 shl 5)) != 0 && p != null) {
            pageSound = false; pageMix = true; invalidate()
        }
        if ((pressed and (1 shl 12)) != 0) octave = (octave - 1).coerceIn(-3, 3)
        if ((pressed and (1 shl 13)) != 0) octave = (octave + 1).coerceIn(-3, 3)
        val unsupported = pressed and ((1 shl 0) or (1 shl 2) or (1 shl 3) or
            (if (p == null) (1 shl 5) else 0) or
            (if (t == null) (1 shl 10) else 0) or
            (1 shl 7) or (1 shl 8) or (1 shl 9) or (1 shl 11))
        if (unsupported != 0) offer(ResultCode.UNSUPPORTED, "UNAVAILABLE", "INSTRUMENT ONLY")
        if (glo && t == null && deltas[0] != 0)
            offer(ResultCode.UNSUPPORTED, "NO TEMPO", "TRANSPORT UNAVAILABLE")
        if (deltas[1] != 0) {
            if (p == null) offer(ResultCode.UNSUPPORTED, "NO PARTS", "PARTS UNAVAILABLE")
            else {
                val index = p.parts.indexOfFirst { it.ref == p.selected }
                val next = (index.toLong() + deltas[1]).coerceIn(0L, p.parts.lastIndex.toLong()).toInt()
                if (next != index) {
                    releaseVoices()
                    if (failed) return false
                    val result = partsControls!!.select(deltas[1], p.context)
                    if (result.code == ResultCode.COMPLETED) {
                        bank = 0
                        state()
                        partsState()
                        invalidate()
                        return !failed
                    } else invalidate()
                }
            }
        }
        if (deltas[0] != 0 && pageSound) {
            bank = (bank.toLong() + deltas[0]).coerceIn(0L, maxOf(0, (s.controls.size - 1) / 4).toLong()).toInt()
            invalidate()
        }
        if (deltas[2] != 0) {
            val index = s.instruments.indexOfFirst { it.ref == s.selected }
            val next = (index.toLong() + deltas[2]).coerceIn(0L, s.instruments.lastIndex.toLong()).toInt()
            if (next != index) {
                releaseVoices()
                if (failed) return false
                val result = submit(s.selected, adapter.read().revision,
                    InstrumentIntent.SelectInstrument(s.instruments[next].ref))
                if (result.code == ResultCode.COMPLETED) {
                    bank = 0; state(); invalidate()
                } else invalidate()
            }
        }
        if (navigation) return !failed
        for (key in voices.indices) if ((down and (1L shl key)) != 0L) {
            val note = (53 + key + octave * 12).coerceIn(s.minimumNote, s.maximumNote)
            val id = nextVoice++
            val result = submit(s.selected, s.revision, InstrumentIntent.NoteOn(id, note, s.velocity))
            if (result.code == ResultCode.COMPLETED) voices[key] = Voice(s.selected, id)
            else { failClosed(); return false }
        }
        if (pageMix && p != null) for (slot in 0..3) {
            val delta = deltas[slot + 3]
            if (delta == 0) continue
            val current = partsState() ?: break
            val result = partsControls!!.adjust(slot, delta, current.context)
            val after = partsControls.state()
            if (result.code == ResultCode.COMPLETED && result.revision == after.revision &&
                samePartsLayout(current, after)) {
                partsLineage = after
                val instrumentAfter = adapter.read()
                if (sameLayout(s, instrumentAfter)) lineage = instrumentAfter
                else { invalidate(); break }
            } else {
                if (result.code == ResultCode.UNSUPPORTED)
                    offer(ResultCode.UNSUPPORTED, "NO CONTROL", "MIX UNAVAILABLE")
                else invalidate()
                break
            }
        }
        if (pageSound) for (slot in 0..3) {
            val delta = deltas[slot + 3]
            if (delta == 0) continue
            val current = state()
            val control = current.controls.getOrNull(bank * 4 + slot)
            if (control == null) {
                offer(ResultCode.UNSUPPORTED, "NO CONTROL", "BANK ${bank + 1}")
                continue
            }
            val result = submit(current.selected, current.revision,
                InstrumentIntent.SetParameter(control.parameter.id,
                    control.parameter.adjusted(control.value, delta)))
            val after = adapter.read()
            if (result.code == ResultCode.COMPLETED && result.revision == after.revision &&
                sameLayout(current, after)) {
                lineage = after // This accepted edit belongs to the drawn context lineage.
                if (p != null) {
                    val partsAfter = partsControls!!.state()
                    if (samePartsLayout(p, partsAfter)) partsLineage = partsAfter
                    else { invalidate(); break }
                }
            } else { invalidate(); break }
        }
        return !failed
    }

    private fun submit(target: ObjectRef, revision: Long, intent: InstrumentIntent): OperationResult =
        dispatcher.submit(dispatcher.request(target, revision, intent))

    private fun submitTransport(target: ObjectRef, revision: Long, intent: TransportIntent): OperationResult {
        val result = dispatcher.submit(dispatcher.request(target, revision, intent))
        if (!dispatcher.isActive ||
            ((intent is TransportIntent.Start || intent is TransportIntent.Stop) &&
                result.code != ResultCode.COMPLETED))
            failClosed()
        return result
    }

    private fun submitParts(target: ObjectRef, revision: Long, intent: PartsIntent): OperationResult {
        val result = dispatcher.submit(dispatcher.request(target, revision, intent))
        if (!dispatcher.isActive) failClosed()
        return result
    }

    private fun deltasFrom(scan: ByteArray, slot: Int) = Wire6.u14(scan, 6 + slot * 2) - 8192

    private fun sameTransportLayout(before: TransportState, after: TransportState): Boolean =
        before.target == after.target && before.playing == after.playing &&
            before.recording == after.recording && before.minimumTempo == after.minimumTempo &&
            before.maximumTempo == after.maximumTempo && before.tempoStep == after.tempoStep &&
            before.supportsContinue == after.supportsContinue

    private fun sameLayout(before: InstrumentState, after: InstrumentState): Boolean =
        before.selected == after.selected && before.instruments == after.instruments &&
            before.minimumNote == after.minimumNote && before.maximumNote == after.maximumNote &&
            before.velocity == after.velocity &&
            before.controls.map { it?.parameter } == after.controls.map { it?.parameter }

    private fun samePartsLayout(before: PartsState, after: PartsState): Boolean =
        before.selected == after.selected && before.soloMode == after.soloMode &&
            before.parts.map { listOf(it.ref, it.name, it.enabled, it.level?.parameter,
                it.pan?.parameter, it.muted != null, it.soloed != null) } ==
            after.parts.map { listOf(it.ref, it.name, it.enabled, it.level?.parameter,
                it.pan?.parameter, it.muted != null, it.soloed != null) }

    private fun release(key: Int) {
        if (failed) return
        val voice = voices[key] ?: return
        voices[key] = null
        val revision = adapter.read().revision
        if (submit(voice.target, revision, InstrumentIntent.NoteOff(voice.id)).code != ResultCode.COMPLETED)
            failClosed()
    }

    private fun releaseVoices() {
        for (key in voices.indices) {
            if (failed) break
            release(key)
        }
    }

    private fun operationResult(operation: Operation, result: OperationResult) {
        if (operation.generation != dispatcher.generation || failed) return
        if (operation.intent is InstrumentIntent.NoteOn || operation.intent is InstrumentIntent.NoteOff) return
        if (result.code == ResultCode.ACCEPTED_PENDING) pendingIds.add(operation.id)
        else if (result.code == ResultCode.COMPLETED && !pendingIds.remove(operation.id)) return
        else pendingIds.remove(operation.id)
        offer(result.code, if (result.code == ResultCode.ACCEPTED_PENDING) "WORKING" else "OPERATION", result.message)
    }

    private fun offer(code: ResultCode, title: String, subtitle: String) {
        if (feedback.size >= 32) { failClosed(); return }
        if (feedback.isEmpty()) invalidate() // A status takes over the display and retires its edit token.
        feedback.addLast(Feedback(OperationResult(code), title, subtitle))
    }
}
