package com.groovebox.presets

import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.presets.PresetEngine
import com.groovebox.presets.PresetRanges
import com.groovebox.presets.LoomPreset
import com.groovebox.presets.PresetCodec
import com.groovebox.persistence.PersistenceManager
import java.io.File

/**
 * Applies a [LoomPreset] to a track, updating both the native engine and the
 * Kotlin state used by the UI.
 */
object PresetApplier {

    fun apply(
        preset: LoomPreset,
        trackIndex: Int,
        state: GrooveboxState,
        onStateChange: (GrooveboxState) -> Unit,
        nativeLib: NativeLib
    ) {
        val track = state.tracks.getOrNull(trackIndex) ?: return

        // Start from the engine's own defaults so parameters the preset does not
        // mention cannot leak in from the previous patch.
        nativeLib.restoreTrackPreset(trackIndex)

        preset.parameters.forEach { (id, value) ->
            if (value.isFinite()) nativeLib.setParameter(trackIndex, id, value)
        }

        if (preset.engine == PresetEngine.SUBTRACTIVE) {
            val mode = (preset.filterMode ?: 0).coerceIn(0, 2)
            nativeLib.setFilterMode(trackIndex, mode)
        }

        var wavetableName = track.activeWavetableName
        if (preset.engine == PresetEngine.WAVETABLE) {
            val requested = preset.wavetableName?.takeIf { it.isNotBlank() && it != "Basic" }
            val file = requested?.let { resolveWavetable(it) }
            if (file != null) {
                nativeLib.loadWavetable(trackIndex, file.absolutePath)
                wavetableName = requested
            } else {
                nativeLib.loadDefaultWavetable(trackIndex)
                wavetableName = "Basic"
            }
        }

        // Mirror the preset into the UI's parameter map. The engine ranges are
        // cleared first so stale values from the previous patch disappear.
        val newParameters = track.parameters.toMutableMap()
        PresetRanges.idsFor(preset.engine).forEach { newParameters[it] = 0f }
        preset.parameters.forEach { (id, value) ->
            if (value.isFinite()) newParameters[id] = value
        }

        val newTracks = state.tracks.mapIndexed { i, t ->
            if (i != trackIndex) t
            else t.copy(
                parameters = newParameters,
                filterMode = if (preset.engine == PresetEngine.SUBTRACTIVE)
                    (preset.filterMode ?: t.filterMode).coerceIn(0, 2) else t.filterMode,
                activeWavetableName = wavetableName,
                fm1KnobBindings = preset.fm1KnobBindings?.let { bindings ->
                    List(4) { index -> bindings.getOrNull(index)?.toTrackBinding()
                        ?.takeIf { it.isValid() } }
                },
                fm1StepKnobBindings = preset.fm1StepKnobBindings?.let { bindings ->
                    List(4) { index -> bindings.getOrNull(index)?.toTrackBinding()
                        ?.takeIf { it.isValid() } }
                }
            )
        }
        onStateChange(state.copy(tracks = newTracks))
    }

    /** Build a portable preset from the current state of a track. */
    fun exportTrack(
        trackIndex: Int,
        state: GrooveboxState,
        name: String
    ): LoomPreset? {
        val track = state.tracks.getOrNull(trackIndex) ?: return null
        val engine = PresetEngine.forEngineTypeId(track.engineType.ordinal) ?: return null
        return PresetCodec.fromTrack(track, trackIndex, engine, name)
    }

    fun resolveWavetable(name: String): File? {
        val folder = PersistenceManager.getLoomFolder()
        val dirs = listOf(
            File(folder, "wavetables"),
            File(folder, "Wavetables")
        )
        val candidates = listOf(name, "$name.WAV", "$name.wav")
        for (dir in dirs) {
            for (candidate in candidates) {
                val file = File(dir, candidate)
                if (file.exists()) return file
            }
        }
        return null
    }
}
