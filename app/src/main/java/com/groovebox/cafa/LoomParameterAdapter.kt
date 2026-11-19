/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.GrooveboxState
import com.groovebox.TrackState
import org.fm1.companion.control.ParameterBanks

/** Maps synchronous control edits to LOOM. All reads/writes run on the model's owner thread. */
class LoomParameterAdapter(
    private val read: () -> GrooveboxState,
    private val write: (GrooveboxState) -> Unit,
    private val controls: (GrooveboxState, TrackState) -> List<CafaCatalog.Control?>,
    private val catalogGeneration: () -> Int,
    private val parameter: (Int, Int, Float) -> Unit
) : ParameterBanks.Adapter {
    private data class Key(val target: String, val generation: Int,
                           val parameters: Map<Int, Float>, val controls: List<ParameterBanks.Control?>)
    private var previous: Key? = null
    private var revision = 0L

    private fun state(s: GrooveboxState): ParameterBanks.State {
        require(s.selectedTrackIndex in s.tracks.indices)
        val t = s.tracks[s.selectedTrackIndex]
        // This identifies the current session target, not a persistent project ID.
        val target = "com.groovebox/track/${s.selectedTrackIndex}/${t.id}/${t.engineType.name}/${t.catalogEntryId ?: t.engineType.name}"
        val slots = controls(s,t).map { control ->
            control?.let { ParameterBanks.Control(it.descriptor, t.parameters[it.id]) }
        }
        val key = Key(target, catalogGeneration(), t.parameters, slots)
        if (key != previous) {
            check(revision < Long.MAX_VALUE) { "Parameter revision exhausted; recreate the adapter" }
            revision++; previous = key
        }
        return ParameterBanks.State(target, revision, slots)
    }

    override fun read(): ParameterBanks.State = state(read.invoke())

    override fun apply(edit: ParameterBanks.Edit): ParameterBanks.Result {
        val s = read.invoke()
        val current = state(s)
        if (edit.targetId != current.targetId || edit.expectedRevision != current.revision)
            return ParameterBanks.Result.STALE
        if (!edit.value.isFinite()) return ParameterBanks.Result.INVALID
        val t = s.tracks[s.selectedTrackIndex]
        val matches = controls(s,t).filterNotNull().filter { it.id.toString() == edit.parameterId }
        if (matches.isEmpty()) return ParameterBanks.Result.UNSUPPORTED
        val control = matches.firstOrNull { edit.value in it.minimum..it.maximum }
            ?: return ParameterBanks.Result.INVALID
        parameter(s.selectedTrackIndex, control.id, edit.value)
        val updated = t.copy(parameters = t.parameters + (control.id to edit.value))
        write(s.copy(tracks = s.tracks.mapIndexed { index, old -> if (index == s.selectedTrackIndex) updated else old }))
        return ParameterBanks.Result.COMPLETED
    }
}
