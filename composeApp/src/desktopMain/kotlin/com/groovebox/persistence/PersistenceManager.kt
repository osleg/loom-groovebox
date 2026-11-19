package com.groovebox.persistence

import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.TrackState
import java.io.*

actual object PersistenceManager {
    actual fun getLoomFolder(): File {
        val userHome = System.getProperty("user.home")
        val loomDir = File(userHome, "Documents/Loom")
        if (!loomDir.exists()) loomDir.mkdirs()
        return loomDir
    }

    private fun getProjectsDir(): File {
        val dir = File(getLoomFolder(), "Projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    actual fun saveProject(state: GrooveboxState, fileName: String): Boolean {
        return try {
            val name = if (fileName.endsWith(".gbx")) fileName else "$fileName.gbx"
            val file = File(getProjectsDir(), name)
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(state) }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    actual fun loadProject(fileName: String): GrooveboxState? {
        try {
            val file = File(getProjectsDir(), fileName)
            if (!file.exists()) return null
            ObjectInputStream(FileInputStream(file)).use {
                return it.readObject() as? GrooveboxState
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    actual fun listProjects(): List<String> = getProjectsDir().listFiles { _, name -> name.endsWith(".gbx") }?.map { it.name } ?: emptyList()

    actual fun deleteProject(fileName: String): Boolean = File(getProjectsDir(), fileName).let { if (it.exists()) it.delete() else false }
    actual fun renameProject(from: String, to: String): Boolean =
        renameWithin(getProjectsDir(), from, to, ".gbx")

    actual fun copyProject(fileName: String): Boolean {
        try {
            val source = File(getProjectsDir(), fileName)
            if (!source.exists()) return false
            val destName = fileName.removeSuffix(".gbx") + "_copy.gbx"
            val dest = File(getProjectsDir(), destName)
            source.copyTo(dest, overwrite = true)
            return true
        } catch (e: Exception) { return false }
    }

    actual fun clearAssignments() {}

    actual fun saveTrackPreset(trackState: TrackState, name: String) {
        try {
            val engineDir = File(File(getLoomFolder(), "Presets"), trackState.engineType.name)
            if (!engineDir.exists()) engineDir.mkdirs()
            val file = File(engineDir, if (name.endsWith(".gbp")) name else "$name.gbp")
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(trackState) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    actual fun loadTrackPreset(engineType: EngineType, name: String): TrackState? {
        try {
            val engineDir = File(File(getLoomFolder(), "Presets"), engineType.name)
            val file = File(engineDir, if (name.endsWith(".gbp")) name else "$name.gbp")
            if (!file.exists()) return null
            ObjectInputStream(FileInputStream(file)).use { return it.readObject() as? TrackState }
        } catch (e: Exception) { }
        return null
    }

    actual fun listTrackPresets(engineType: EngineType): List<String> {
        val engineDir = File(File(getLoomFolder(), "Presets"), engineType.name)
        return engineDir.listFiles { _, name -> name.endsWith(".gbp") }?.map { it.name.removeSuffix(".gbp") } ?: emptyList()
    }

    private fun renameWithin(dir: File, from: String, to: String, extension: String): Boolean {
        if (from.isBlank() || to.isBlank() || from.contains('/') || from.contains('\\') ||
            to.contains('/') || to.contains('\\')) return false
        val source = File(dir, from.removeSuffix(extension) + extension)
        val target = File(dir, to.removeSuffix(extension) + extension)
        return source.isFile && !target.exists() && source.renameTo(target)
    }
    actual fun deleteTrackPreset(engineType: EngineType, name: String): Boolean {
        val file = File(File(File(getLoomFolder(), "Presets"), engineType.name),
            name.removeSuffix(".gbp") + ".gbp")
        return file.isFile && file.delete()
    }
    actual fun renameTrackPreset(engineType: EngineType, from: String, to: String): Boolean =
        renameWithin(File(File(getLoomFolder(), "Presets"), engineType.name), from, to, ".gbp")

    actual fun saveSequence(trackState: TrackState, name: String) {
        try {
            val seqDir = File(getLoomFolder(), "Sequences")
            if (!seqDir.exists()) seqDir.mkdirs()
            val file = File(seqDir, if (name.endsWith(".gbs")) name else "$name.gbs")
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(trackState) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    actual fun listSequences(): List<String> {
        val seqDir = File(getLoomFolder(), "Sequences")
        return seqDir.listFiles { _, name -> 
            name.endsWith(".gbs") || name.endsWith(".mid") || name.endsWith(".midi") 
        }?.map { it.name.removeSuffix(".gbs") } ?: emptyList()
    }
    actual fun deleteSequence(name: String): Boolean {
        val file = File(File(getLoomFolder(), "Sequences"), name.removeSuffix(".gbs") + ".gbs")
        return file.isFile && file.delete()
    }
    actual fun renameSequence(from: String, to: String): Boolean =
        renameWithin(File(getLoomFolder(), "Sequences"), from, to, ".gbs")


    actual fun loadSequence(targetTrack: TrackState, name: String): TrackState? {
        try {
            val seqDir = File(getLoomFolder(), "Sequences")
            val file = File(seqDir, if (name.endsWith(".gbs")) name else "$name.gbs")
            if (!file.exists()) return null
            ObjectInputStream(FileInputStream(file)).use {
                val sourceTrack = it.readObject() as? TrackState ?: return null
                return targetTrack.copy(steps = sourceTrack.steps, drumSteps = sourceTrack.drumSteps)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    actual fun loadMidiSequence(targetTrack: TrackState, path: String): TrackState? {
        try {
            val file = File(path)
            if (!file.exists()) return null
            return FileInputStream(file).use { input ->
                com.groovebox.midi.MidiParser.parseMidiToTrackState(input, targetTrack)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }


    actual fun copyWavetablesToFilesDir() { /* TODO: Copy from JAR resources */ }
    actual fun copySoundFontsToFilesDir() { /* TODO: Copy from JAR resources */ }
    actual fun copyDefaultsToFilesDir() { /* TODO: Copy from JAR resources */ }
    actual fun migrateToExternalStorage() {}

    private fun getMidiSettingsFile(): File = File(getLoomFolder(), "midi-settings.gbs")

    actual fun saveMidiSettings(settings: com.groovebox.midi.MidiSettings) {
        try {
            ObjectOutputStream(FileOutputStream(getMidiSettingsFile())).use { it.writeObject(settings) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    actual fun loadMidiSettings(): com.groovebox.midi.MidiSettings? {
        try {
            val file = getMidiSettingsFile()
            if (!file.exists()) return null
            ObjectInputStream(FileInputStream(file)).use {
                return it.readObject() as? com.groovebox.midi.MidiSettings
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }
}
