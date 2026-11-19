package com.groovebox.persistence

import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.TrackState
import java.io.File

expect object PersistenceManager {
    fun getLoomFolder(): File
    fun saveProject(state: GrooveboxState, fileName: String): Boolean
    fun loadProject(fileName: String): GrooveboxState?
    fun listProjects(): List<String>
    fun deleteProject(fileName: String): Boolean
    fun renameProject(from: String, to: String): Boolean
    fun copyProject(fileName: String): Boolean
    fun clearAssignments()
    fun saveTrackPreset(trackState: TrackState, name: String)
    fun loadTrackPreset(engineType: EngineType, name: String): TrackState?
    fun listTrackPresets(engineType: EngineType): List<String>
    fun deleteTrackPreset(engineType: EngineType, name: String): Boolean
    fun renameTrackPreset(engineType: EngineType, from: String, to: String): Boolean
    fun saveSequence(trackState: TrackState, name: String)
    fun listSequences(): List<String>
    fun deleteSequence(name: String): Boolean
    fun renameSequence(from: String, to: String): Boolean
    fun loadSequence(targetTrack: TrackState, name: String): TrackState?
    fun loadMidiSequence(targetTrack: TrackState, path: String): TrackState?
    fun copyWavetablesToFilesDir()
    fun copySoundFontsToFilesDir()
    fun copyDefaultsToFilesDir()
    fun migrateToExternalStorage()

    // Global (non-project) MIDI device configuration
    fun saveMidiSettings(settings: com.groovebox.midi.MidiSettings)
    fun loadMidiSettings(): com.groovebox.midi.MidiSettings?
}
