/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.cafa.CafaConnection
import com.groovebox.midi.EmpledManager
import com.groovebox.midi.MidiConfigStore
import com.groovebox.midi.MidiManager
import com.groovebox.persistence.PersistenceManager
import com.groovebox.utils.sanitizeGrooveboxState
import com.groovebox.utils.syncNativeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI and foreground playback share a single native engine and project. Owner changes run on main. */
internal class LoomRuntime private constructor(private val context: Context) {
    internal class Session(val model: GrooveboxViewModel, val midi: MidiManager,
                           val leds: EmpledManager, val connection: CafaConnection,
                           val scope: CoroutineScope)
    val native = NativeLib()
    var session by mutableStateOf<Session?>(null)
        private set
    var status by mutableStateOf("Initializing…")
        private set
    var startupFailed by mutableStateOf(false)
        private set
    var playbackHeld by mutableStateOf(false)
        private set
    private var activities = 0
    private var initializing = false
    private val prefs = context.getSharedPreferences("GrooveboxPrefs", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val clearCrashFlag = Runnable { prefs.edit().putBoolean("crashed_on_launch", false).apply() }

    fun attachUi() {
        activities++
        if (session == null && !initializing) initialize()
    }

    fun retry() {
        if (startupFailed && activities > 0 && !initializing) initialize()
    }

    fun releaseUi() {
        activities--
        disposeIfUnused()
    }

    fun acquirePlayback(): CafaConnection? = session?.connection?.also { playbackHeld = true }

    fun releasePlayback() {
        if (!playbackHeld) return
        playbackHeld = false
        save()
        disposeIfUnused()
    }

    fun save() {
        session?.model?.let {
            it.drainRecordedSteps()
            PersistenceManager.saveProject(it.state, "last_session.gbx")
        }
    }

    private fun disposeIfUnused() {
        if (activities != 0 || playbackHeld || initializing) return
        val ready = session ?: return
        session = null
        ready.connection.close()
        ready.model.closeMidi()
        ready.midi.close()
        native.stop()
        ready.scope.cancel()
        handler.removeCallbacks(clearCrashFlag)
        clearCrashFlag.run()
    }

    private fun initialize() {
        initializing = true
        startupFailed = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            var model: GrooveboxViewModel? = null
            var midi: MidiManager? = null
            try {
                PersistenceManager.setContext(context)
                MidiConfigStore.load()
                val crashed = prefs.getBoolean("crashed_on_launch", false)
                prefs.edit().putBoolean("crashed_on_launch", true).apply()
                handler.removeCallbacks(clearCrashFlag)
                handler.postDelayed(clearCrashFlag, 10000)
                if (crashed) Toast.makeText(context, "Safe mode: loading initial sounds", Toast.LENGTH_LONG).show()
                status = "Restoring project and audio…"
                val restored = withContext(Dispatchers.IO) { restore(crashed) }
                val vm = GrooveboxViewModel(native, scope)
                model = vm
                vm.onStateChange(restored)
                val ports = MidiManager(context, vm::processMidiMessage)
                midi = ports
                vm.midiRouter.setMidiSender(ports::sendMidi)
                vm.attachMidiManager(ports)
                val leds = EmpledManager(ports)
                val link = CafaConnection(context, native, vm, ports)
                session = Session(vm, ports, leds, link, scope)
                leds.sendHandshake()
                scope.launch {
                    delay(1000)
                    leds.sendHandshake()
                }
            } catch (error: Exception) {
                session = null
                model?.closeMidi()
                midi?.close()
                native.stop()
                Log.e("Groovebox", "Audio initialization failed", error)
                status = "Audio startup failed: ${error.message.orEmpty().take(80)}"
                startupFailed = true
                handler.removeCallbacks(clearCrashFlag)
                clearCrashFlag.run()
                scope.cancel()
            } finally {
                initializing = false
                disposeIfUnused()
            }
        }
    }

    private fun restore(safeMode: Boolean): GrooveboxState {
        try {
            PersistenceManager.migrateToExternalStorage()
            PersistenceManager.copyWavetablesToFilesDir()
            PersistenceManager.copySoundFontsToFilesDir()
            PersistenceManager.copyDefaultsToFilesDir()
        } catch (error: Exception) {
            Log.e("Groovebox", "Could not prepare bundled assets", error)
        }
        val loaded = if (safeMode) createInitialState() else
            PersistenceManager.loadProject("last_session.gbx") ?: PersistenceManager.loadProject("Init.gbx")
            ?: createInitialState().also { PersistenceManager.saveProject(it, "Init.gbx") }
        val state = sanitizeGrooveboxState(loaded)
        native.setAppDataDir(context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath)
        native.init()
        CafaConnection.applyInitialRoute(context, native)
        native.setAppDataDir(context.filesDir.absolutePath)
        native.loadAppState()
        repeat(8) { native.clearSequencer(it) }
        // SoundFont loading must finish before starting the real-time audio thread.
        syncNativeState(state, native)
        native.start()
        return state.copy(tracks = state.tracks.mapIndexed { i, track ->
            track.copy(lastSamplePath = native.getLastSamplePath(i))
        })
    }

    private fun createInitialState(): GrooveboxState {
        val tracks = List(8) { i ->
            when(i) {
                0 -> TrackState(id = i, engineType = EngineType.SUBTRACTIVE)
                1 -> {
                    val fmParams = mutableMapOf<Int, Float>()
                    // Bell defaults moved to Track 2
                    fmParams[160] = 0.8f // Op 1 Lvl
                    fmParams[166] = 0.4f // Op 2 Lvl
                    fmParams[165] = 1.0f // Op 1 Ratio
                    fmParams[171] = 2.0f // Op 2 Ratio
                    fmParams[161] = 0.01f // Op 1 Atk
                    fmParams[162] = 0.5f // Op 1 Dcy
                    fmParams[9] = 0.5f   // Center Pan
                    TrackState(id = i, engineType = EngineType.FM, parameters = fmParams, fmCarrierMask = 3, pan = 0.5f)
                }
                2 -> TrackState(id = i, engineType = EngineType.WAVETABLE)
                3 -> TrackState(id = i, engineType = EngineType.SAMPLER)
                4 -> TrackState(id = i, engineType = EngineType.GRANULAR)
                5 -> TrackState(id = i, engineType = EngineType.FM_DRUM)
                6 -> TrackState(id = i, engineType = EngineType.ANALOG_DRUM)
                7 -> TrackState(id = i, engineType = EngineType.MIDI)
                else -> TrackState(id = i, engineType = EngineType.SUBTRACTIVE)
            }
        }

        return GrooveboxState(tracks = tracks, tempo = 80.0f)
    }

    companion object {
        private var instance: LoomRuntime? = null
        fun from(context: Context): LoomRuntime =
            instance ?: LoomRuntime(context.applicationContext).also { instance = it }
    }
}
