/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.control.ParameterDescriptor
import org.fm1.companion.instrument.InstrumentAdapter
import org.fm1.companion.instrument.InstrumentDescriptor
import org.fm1.companion.instrument.InstrumentIntent
import org.fm1.companion.instrument.InstrumentState
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.Operation
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode

/** Main-thread-owned state of the standalone reference instrument. */
class DemoInstrumentAdapter(val synth: Synthesizer = Synthesizer()) : InstrumentAdapter {
    override val namespace = "org.fm1.demo"
    private val instrument = ObjectRef(namespace, ObjectRef.Kind.INSTRUMENT, "oscillator")
    private val descriptors = listOf(
        ParameterDescriptor("level", "Level", .7f, 0f, 1f, .02f),
        ParameterDescriptor("waveform", "Wave", 0f, 0f, 2f, 1f,
            listOf("Sine", "Triangle", "Square"), ""),
        ParameterDescriptor("attack_ms", "Attack", 10f, 0f, 1000f, 10f, unit = "ms"),
        ParameterDescriptor("release_ms", "Release", 180f, 5f, 2000f, 10f, unit = "ms"),
        ParameterDescriptor("pan", "Pan", 0f, -1f, 1f, .05f, unit = "")
    )
    private var revision = 0L

    override fun read() = InstrumentState(
        listOf(InstrumentDescriptor(instrument, "Oscillator")), instrument, revision,
        descriptors.map { ParameterBanks.Control(it, value(it.id)) },
        minimumNote = 36, maximumNote = 96
    )

    fun value(id: String): Float = when (id) {
        "level" -> synth.level
        "waveform" -> synth.waveform
        "attack_ms" -> synth.attackMs
        "release_ms" -> synth.releaseMs
        "pan" -> synth.pan
        else -> throw IllegalArgumentException("Unknown parameter")
    }

    override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
        val intent = operation.intent as? InstrumentIntent
            ?: return OperationResult(ResultCode.UNSUPPORTED, message = "Instrument operations only")
        if (intent is InstrumentIntent.NoteOff) {
            // A key-up belongs to the voice's original session and instrument even after edits.
            return if (synth.noteOff(operation.generation, intent.voiceId)) completed()
            else OperationResult(ResultCode.INVALID, message = "Voice is not active")
        }
        if (operation.target != instrument || operation.expectedRevision != revision)
            return OperationResult(ResultCode.STALE, message = "Instrument state changed")
        return when (intent) {
            is InstrumentIntent.SelectInstrument ->
                if (intent.instrument == instrument) completed()
                else OperationResult(ResultCode.UNSUPPORTED, message = "Only one instrument")
            is InstrumentIntent.SetParameter -> {
                if (descriptors.none { it.id == intent.parameterId } ||
                    !synth.setParameter(intent.parameterId, intent.value))
                    OperationResult(ResultCode.INVALID, message = "Invalid control value")
                else {
                    revision++
                    completed()
                }
            }
            is InstrumentIntent.NoteOn ->
                if (synth.noteOn(operation.generation, intent.voiceId, intent.note, intent.velocity)) completed()
                else OperationResult(ResultCode.BUSY, message = "Voice limit reached; release keys")
            is InstrumentIntent.NoteOff -> error("Handled above")
        }
    }

    override fun cancelSession(generation: Long) { synth.cancel(generation) }
    private fun completed() = OperationResult(ResultCode.COMPLETED, revision)
}
