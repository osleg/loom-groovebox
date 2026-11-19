/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.GrooveboxState
import com.groovebox.TrackState
import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.control.ParameterDescriptor
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.Operation
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.parts.PartDescriptor
import org.fm1.companion.parts.PartsAdapter
import org.fm1.companion.parts.PartsIntent
import org.fm1.companion.parts.PartsState

/** Synchronous mix edits on LOOM's model owner thread. Track IDs survive reordering. */
class LoomPartsAdapter(
    private val readModel: () -> GrooveboxState,
    private val writeModel: (GrooveboxState) -> Unit,
    private val selectTrack: (Int) -> Unit,
    private val level: (Int, Float) -> Unit,
    private val pan: (Int, Float) -> Unit,
    private val mute: (Int, Boolean) -> Unit,
    private val solo: (Int, Boolean) -> Unit
) : PartsAdapter {
    override val namespace = "com.groovebox"
    private val levelParameter = ParameterDescriptor("level", "LEVEL", minimum = 0f, maximum = 2f, step = .01f, unit = "x")
    private val panParameter = ParameterDescriptor("pan", "PAN", minimum = 0f, maximum = 1f, step = .01f, unit = "%")
    private data class Mix(val id: Int, val name: String, val level: Float, val pan: Float,
                           val mute: Boolean, val solo: Boolean, val enabled: Boolean)
    private data class Values(val selectedId: Int, val tracks: List<Mix>)
    private var previous: Values? = null
    private var revision = 0L
    private var latestGeneration = -1L
    private var cancelledGeneration = -1L

    private fun ref(id: Int) = ObjectRef(namespace, ObjectRef.Kind.PART, id.toString())

    private fun state(model: GrooveboxState): PartsState {
        require(model.selectedTrackIndex in model.tracks.indices)
        val values = Values(model.tracks[model.selectedTrackIndex].id, model.tracks.mapIndexed { index, track ->
            Mix(track.id, "T${index + 1} ${track.engineType.name}", track.volume, track.pan, track.isMuted, track.isSoloed, !track.isDisabled)
        })
        if (values != previous) {
            check(revision < Long.MAX_VALUE) { "Parts revision exhausted; recreate the adapter" }
            revision++
            previous = values
        }
        return PartsState(model.tracks.mapIndexed { index, track ->
            PartDescriptor(ref(track.id), "T${index + 1} ${track.engineType.name}",
                ParameterBanks.Control(levelParameter, track.volume),
                ParameterBanks.Control(panParameter, track.pan),
                track.isMuted, track.isSoloed, !track.isDisabled)
        }, ref(values.selectedId), revision)
    }

    override fun read(): PartsState = state(readModel())

    private fun updateTrack(index: Int, change: (TrackState) -> TrackState) {
        val model = readModel()
        writeModel(model.copy(tracks = model.tracks.mapIndexed { i, old -> if (i == index) change(old) else old }))
    }

    override fun execute(operation: Operation, complete: (OperationResult) -> Unit): OperationResult {
        if (operation.generation <= cancelledGeneration || operation.generation < latestGeneration)
            return OperationResult(ResultCode.STALE)
        val intent = operation.intent as? PartsIntent ?: return OperationResult(ResultCode.UNSUPPORTED)
        val model = readModel()
        val current = state(model)
        if (operation.target != current.selected || operation.expectedRevision != current.revision)
            return OperationResult(ResultCode.STALE)
        val index = model.selectedTrackIndex
        val track = model.tracks[index]
        latestGeneration = operation.generation
        when (intent) {
            is PartsIntent.SelectPart -> {
                val next = model.tracks.indexOfFirst { ref(it.id) == intent.part }
                if (next < 0) return OperationResult(ResultCode.INVALID)
                if (next != index) selectTrack(next)
            }
            is PartsIntent.SetLevel -> {
                if (!intent.value.isFinite() || intent.value !in 0f..2f) return OperationResult(ResultCode.INVALID)
                if (intent.value != track.volume) {
                    level(index, intent.value)
                    updateTrack(index) { it.copy(volume = intent.value) }
                }
            }
            is PartsIntent.SetPan -> {
                if (!intent.value.isFinite() || intent.value !in 0f..1f) return OperationResult(ResultCode.INVALID)
                if (intent.value != track.pan) {
                    pan(index, intent.value)
                    updateTrack(index) { it.copy(pan = intent.value) }
                }
            }
            is PartsIntent.SetMute -> if (intent.value != track.isMuted) {
                mute(index, intent.value)
                updateTrack(index) { it.copy(isMuted = intent.value) }
            }
            is PartsIntent.SetSolo -> if (intent.value != track.isSoloed) {
                solo(index, intent.value)
                updateTrack(index) { it.copy(isSoloed = intent.value) }
            }
        }
        return OperationResult(ResultCode.COMPLETED, read().revision)
    }

    override fun cancelSession(generation: Long) {
        if (generation > cancelledGeneration) cancelledGeneration = generation
    }
}
