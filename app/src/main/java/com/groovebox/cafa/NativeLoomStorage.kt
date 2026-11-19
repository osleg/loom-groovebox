/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.TrackState
import com.groovebox.persistence.PersistenceManager

/** The app's saved material, separated from the owner-thread input controller. */
interface NativeLoomStorage {
    fun projects(): List<String>
    fun loadProject(name: String): GrooveboxState?
    fun saveProject(state: GrooveboxState, name: String): Boolean
    fun deleteProject(name: String): Boolean
    fun renameProject(from: String, to: String): Boolean
    fun presets(engine: EngineType): List<String>
    fun loadPreset(engine: EngineType, name: String): TrackState?
    fun savePreset(track: TrackState, name: String)
    fun deletePreset(engine: EngineType, name: String): Boolean
    fun renamePreset(engine: EngineType, from: String, to: String): Boolean
    fun sequences(): List<String>
    fun loadSequence(track: TrackState, name: String): TrackState?
    fun saveSequence(track: TrackState, name: String)
    fun deleteSequence(name: String): Boolean
    fun renameSequence(from: String, to: String): Boolean

    object Device : NativeLoomStorage {
        override fun projects() = PersistenceManager.listProjects()
        override fun loadProject(name: String) = PersistenceManager.loadProject(name)
        override fun saveProject(state: GrooveboxState, name: String) = PersistenceManager.saveProject(state,name)
        override fun deleteProject(name: String) = PersistenceManager.deleteProject(name)
        override fun renameProject(from: String, to: String) = PersistenceManager.renameProject(from,to)
        override fun presets(engine: EngineType) = PersistenceManager.listTrackPresets(engine)
        override fun loadPreset(engine: EngineType, name: String) = PersistenceManager.loadTrackPreset(engine,name)
        override fun savePreset(track: TrackState, name: String) = PersistenceManager.saveTrackPreset(track,name)
        override fun deletePreset(engine: EngineType, name: String) = PersistenceManager.deleteTrackPreset(engine,name)
        override fun renamePreset(engine: EngineType, from: String, to: String) =
            PersistenceManager.renameTrackPreset(engine,from,to)
        override fun sequences() = PersistenceManager.listSequences()
        override fun loadSequence(track: TrackState, name: String) = PersistenceManager.loadSequence(track,name)
        override fun saveSequence(track: TrackState, name: String) = PersistenceManager.saveSequence(track,name)
        override fun deleteSequence(name: String) = PersistenceManager.deleteSequence(name)
        override fun renameSequence(from: String, to: String) = PersistenceManager.renameSequence(from,to)
    }
}
