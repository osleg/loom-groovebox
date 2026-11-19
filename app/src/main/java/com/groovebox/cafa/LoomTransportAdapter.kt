/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.GrooveboxState
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.Operation
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.transport.TransportAdapter
import org.fm1.companion.transport.TransportIntent
import org.fm1.companion.transport.TransportState

/** Synchronous LOOM transport operations on the model owner's thread. */
class LoomTransportAdapter(
    private val readModel: () -> GrooveboxState,
    private val writeModel: (GrooveboxState) -> Unit,
    private val playing: (Boolean) -> Unit,
    private val recording: (Boolean) -> Unit,
    private val tempo: (Float) -> Unit,
    private val refreshRecordedState: () -> Unit = {}
) : TransportAdapter {
    override val namespace = "com.groovebox"
    val target = ObjectRef(namespace, ObjectRef.Kind.TRANSPORT, "transport")
    private data class Values(val playing: Boolean, val recording: Boolean, val tempo: Float)
    private var previous: Values? = null
    private var revision = 0L
    private var latestGeneration = -1L
    private var cancelledGeneration = -1L
    private var stoppedGeneration = -1L

    override fun read(): TransportState = state(readModel())

    private fun observe(model: GrooveboxState): Long {
        val values = Values(model.isPlaying, model.isRecording, model.tempo)
        if (values != previous) {
            check(revision < Long.MAX_VALUE) { "Transport revision exhausted; recreate the adapter" }
            revision++
            previous = values
        }
        return revision
    }

    private fun state(model: GrooveboxState): TransportState {
        val revision = observe(model)
        return TransportState(target, revision, model.isPlaying, model.isRecording, model.tempo,
            minimumTempo = 12f, maximumTempo = 240f)
    }

    override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
        if (operation.target != target) return OperationResult(ResultCode.STALE)
        val intent = operation.intent as? TransportIntent ?: return OperationResult(ResultCode.UNSUPPORTED)
        if (operation.generation <= cancelledGeneration || operation.generation < latestGeneration)
            return OperationResult(ResultCode.STALE)
        if (intent == TransportIntent.Stop) {
            latestGeneration = operation.generation
            stopNative()
            stoppedGeneration = operation.generation
            return OperationResult(ResultCode.COMPLETED, observe(readModel()))
        }
        val model = readModel()
        val current = state(model)
        if (operation.expectedRevision != current.revision)
            return OperationResult(ResultCode.STALE)
        latestGeneration = operation.generation
        when (intent) {
            TransportIntent.Start -> {
                if (model.isPlaying) playing(false) // Native Stop resets the sequencer to its beginning.
                playing(true)
                if (model.isRecording) recording(true)
                writeModel(readModel().copy(isPlaying = true, isRecording = model.isRecording))
                stoppedGeneration = -1L
            }
            TransportIntent.Stop -> error("Stop handled above")
            TransportIntent.Continue -> return OperationResult(ResultCode.UNSUPPORTED, message = "Continue unavailable")
            is TransportIntent.SetTempo -> {
                if (!intent.value.isFinite() || intent.value !in 12f..240f)
                    return OperationResult(ResultCode.INVALID)
                if (intent.value != model.tempo) {
                    tempo(intent.value)
                    writeModel(readModel().copy(tempo = intent.value))
                }
                stoppedGeneration = -1L
            }
        }
        return OperationResult(ResultCode.COMPLETED, read().revision)
    }

    private fun stopNative() {
        playing(false)
        recording(false)
        refreshRecordedState()
        writeModel(readModel().copy(isPlaying = false, isRecording = false))
    }

    override fun cancelSession(generation: Long) {
        if (generation <= cancelledGeneration || generation < latestGeneration) return
        latestGeneration = generation
        cancelledGeneration = generation
        val model = readModel()
        if (stoppedGeneration != generation || model.isPlaying || model.isRecording) stopNative()
    }
}
