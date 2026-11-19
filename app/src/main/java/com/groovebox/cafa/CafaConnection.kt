/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.*
import com.groovebox.persistence.PersistenceManager
import com.groovebox.presets.PresetApplier
import org.fm1.companion.protocol.OpenMidiConnection
import org.fm1.companion.protocol.Wire6

/** Selected USB MIDI and an explicit phone or USB output. */
class CafaConnection(context: Context, private val native: NativeLib,
                     private val model: GrooveboxViewModel,
                     private val ordinaryMidi: com.groovebox.midi.MidiManager) {
    companion object {
        private const val PREFS = "CafaRouting"
        private const val HELD = "output_held"
        /** Call immediately after native init and before any start on recreation. */
        fun applyInitialRoute(context: Context, native: NativeLib) {
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HELD, false)) {
                native.setCafaOutputDevice(0)
            }
        }
    }
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioId = 0
    private var audioMode = Wire6.USB_RETURN
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private var pendingRoute: Runnable? = null
    private val openTimeout = Runnable { disconnect("USB MIDI did not open. Reconnect explicitly.") }
    private var lastRecordingReadback = 0L
    private var ending = false
    var onSessionEnded: (() -> Unit)? = null
    var hasSession by mutableStateOf(false); private set
    var status by mutableStateOf(if (preferences.getBoolean(HELD, false)) "FM1 output remains muted. Reconnect or Restore normal audio." else "FM1 disconnected"); private set
    var connected by mutableStateOf(false); private set
    var routeHeld by mutableStateOf(preferences.getBoolean(HELD, false)); private set
    var notice by mutableStateOf(""); private set
    val surface = NativeLoomSurface({ model.state }, { model.onStateChange(it) }, object : CafaSurface.Engine {
        override fun selectEngine(track: Int, type: EngineType) { native.setEngineType(track, type.ordinal) }
        override fun noteOn(track: Int, note: Int) { native.triggerNote(track, note, 100) }
        override fun noteOff(track: Int, note: Int) { native.releaseNote(track, note) }
        override fun playing(value: Boolean) { native.setPlaying(value) }
        override fun recording(value: Boolean) { native.setIsRecording(value) }
        override fun parameter(track: Int, id: Int, value: Float) { native.setParameter(track, id, value) }
        override fun volume(track: Int, value: Float) { native.setTrackVolume(track, value) }
        override fun pan(track: Int, value: Float) { native.setTrackPan(track, value) }
        override fun mute(track: Int, value: Boolean) { native.setTrackMute(track, value) }
        override fun solo(track: Int, value: Boolean) { native.setTrackSolo(track, value) }
        override fun tempo(value: Float) { native.setTempo(value) }
        override fun length(track: Int, value: Int) { native.setSequencerConfig(track, 1, value) }
        override fun step(track: Int, index: Int, value: StepState) {
            native.setStep(track, index, value.active, value.notes.toIntArray(), value.velocity,
                value.ratchet, value.punch, value.probability, value.gate, value.isSkipped,
                value.subStepOffset, value.noteOffsets.toFloatArray(), value.noteVelocities.toFloatArray(),
                ((value.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(),
                value.parameterLocks.keys.toIntArray(), value.parameterLocks.values.toFloatArray())
        }
        override fun playhead(track: Int, lane: Int) = native.getCurrentStep(track, lane)
        override fun lfo(index: Int, value: LfoState) {
            native.setGenericLfoParam(index, 0, value.rate)
            native.setGenericLfoParam(index, 1, value.depth)
            native.setGenericLfoParam(index, 2, value.shape.toFloat())
            native.setGenericLfoParam(index, 3, if (value.sync) 1f else 0f)
        }
        override fun arp(track: Int, value: ArpConfig) {
            native.setArpConfig(track, value.mode.ordinal, value.octaves, value.inversion,
                value.isLatched, value.isMutated, value.rhythms.map { it.toBooleanArray() }.toTypedArray(),
                value.randomSequence.toIntArray(), value.gateLengths.toFloatArray(), value.probability, value.weird)
            native.setArpRate(track, value.arpRate, value.arpDivisionMode)
            native.setArpStrum(track, value.strum)
        }
        override fun fxEnabled(index: Int, enabled: Boolean) { native.setFxEnabled(index, enabled) }
        override fun swing(value: Float) { native.setSwing(value) }
        override fun humanize(track: Int, value: Float) { native.setTrackHumanize(track, value) }
        override fun lane(track: Int, value: Int) { native.setSelectedFmDrumInstrument(track, value) }
        override fun chainEnabled(track: Int, enabled: Boolean) { native.setChainEnabled(track, enabled) }
        override fun chainLength(track: Int, length: Int) { native.setChainLength(track, length) }
        override fun chainSlot(track: Int, slot: Int, steps: Array<StepState>) {
            native.setChainSlot(track, slot, 0, steps)
        }
        override fun scale(root: Int, intervals: IntArray) { native.setScaleConfig(root, intervals) }
        override fun macro(index: Int, value: Float) { native.setMacroValue(index, value) }
        override fun sidechain(track: Int, drum: Int) { native.setSidechainConfig(track, drum) }
        override fun masterVolume(value: Float) { native.setMasterVolume(value) }
    }, { model.setSelectedTrack(it); native.setFocusedTrack(it) }, {
        // Snapshot only the phone's own project, never FM1 standalone data.
        model.drainRecordedSteps()
        PersistenceManager.saveProject(model.state, "last_session.gbx")
    }, { model.sanitizeAndSetState(it) }, { notice = it }, { preset ->
        PresetApplier.apply(preset, model.state.selectedTrackIndex, model.state,
            model::onStateChange, native)
    }, refreshRecordedState = model::drainRecordedSteps)

    fun midiDevices(): List<MidiDeviceInfo> = link.devices()
    fun audioDevices(): List<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
    fun phoneDevices(): List<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
    }
    private fun selectedDevices(mode: Int) = when (mode) {
        Wire6.PHONE -> phoneDevices()
        Wire6.USB_RETURN -> audioDevices()
        else -> emptyList()
    }
    private fun selectedRate(info: AudioDeviceInfo, mode: Int): Int? {
        if (info.channelCounts.isNotEmpty() && 2 !in info.channelCounts) return null
        val rates = info.sampleRates
        return when (mode) {
            Wire6.USB_RETURN -> 44100.takeIf { rates.isEmpty() || it in rates }
            Wire6.PHONE -> listOf(48000, 44100).firstOrNull { rates.isEmpty() || it in rates }
            else -> null
        }
    }
    private val link = OpenMidiConnection(context, surface, object : OpenMidiConnection.Listener {
        override fun beforeTick(now: Long): Boolean {
            if (selectedDevices(audioMode).none { it.id == audioId }) {
                disconnect("Selected audio output disconnected. Output muted; reconnect explicitly."); return false
            }
            if (native.getCafaOutputDevice() != audioId) {
                disconnect("Selected output route was lost. Output muted; reconnect explicitly."); return false
            }
            if (connected && !native.isCafaAudioEnabled()) {
                disconnect("Selected output restarted or was muted. Reconnect to resynchronize."); return false
            }
            // The same session updates recorded edits with or without a visible activity.
            if (connected && native.getIsRecording() &&
                now - lastRecordingReadback >= 100) {
                model.drainRecordedSteps()
                lastRecordingReadback = now
            }
            return true
        }
        override fun opened() {
            main.removeCallbacks(openTimeout)
            status = "Waiting for FM1 native UI…"
        }
        override fun synchronizedDisplay(audioMode: Int, transportAccepted: Boolean) {
            if (audioMode != this@CafaConnection.audioMode || native.getCafaOutputDevice() != audioId) {
                disconnect("Output route verification failed. Output stays muted."); return
            }
            native.setCafaAudioEnabled(true)
            if (!native.isCafaAudioEnabled()) { disconnect("Output could not be enabled. Output stays muted."); return }
            connected = true
            status = if (audioMode == Wire6.PHONE) "FM1 synchronized · Phone audio" else "FM1 synchronized · FM1 USB audio"
        }
        override fun stopped(reason: String) { if (hasSession) disconnect(reason) }
    })
    fun connect(midiId: Int, audioId: Int, audioMode: Int) {
        if (closed) return
        endSession("Opening selected FM1 endpoints…", notify = false)
        hasSession = true
        val midiInfo = midiDevices().firstOrNull { it.id == midiId }
        val audioInfo = selectedDevices(audioMode).firstOrNull { it.id == audioId }
        val rate = audioInfo?.let { selectedRate(it, audioMode) }
        if (midiInfo == null || audioInfo == null || audioInfo.id <= 0 || rate == null) {
            disconnect("Select available USB MIDI and a compatible audio output."); return
        }
        // Commit the fail-closed choice before touching output, so process death
        // or activity recreation cannot silently restore the default speaker.
        if (!preferences.edit().putBoolean(HELD, true).commit()) {
            disconnect("Could not save the safe output mode. FM1 connection was not started."); return
        }
        routeHeld = true; this.audioId = audioInfo.id; this.audioMode = audioMode
        ordinaryMidi.reserveForCafa(midiInfo.id)
        native.setCafaAudioEnabled(false)
        if (!native.setCafaOutputDevice(audioId, rate)) {
            disconnect("Android could not verify the selected stereo output at $rate Hz. Output stays muted."); return
        }
        // AAudio requestStart can return before the route is usable. Only start
        // MIDI negotiation once that exact route has started with muted output.
        val deadline = SystemClock.elapsedRealtime() + 1000
        val waitForRoute = object : Runnable {
            override fun run() {
                if (native.getCafaOutputDevice() == audioId) {
                    pendingRoute = null
                    main.postDelayed(openTimeout, 5000)
                    openMidi(midiInfo, audioMode)
                } else if (SystemClock.elapsedRealtime() >= deadline) {
                    disconnect("Selected output did not start. Output stays muted.")
                } else {
                    main.postDelayed(this, 25)
                }
            }
        }
        pendingRoute = waitForRoute
        waitForRoute.run()
    }
    private fun openMidi(midiInfo: MidiDeviceInfo, audioMode: Int) {
        link.connect(midiInfo, "com.groovebox", audioMode,
            Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
            Wire6.Capability(Wire6.TRANSPORT, 1, 0, true),
            Wire6.Capability(Wire6.PARTS, 1, 0, true),
            Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
            Wire6.Capability(Wire6.NATIVE_IDLE_KEEPALIVE, 1, 0, false),
            Wire6.Capability(Wire6.NATIVE_DELTA, 1, 0, false))
    }
    fun disconnect(reason: String = "FM1 disconnected. Output remains muted until reconnect or Restore normal audio.") {
        endSession(reason, notify = true)
    }
    private fun endSession(reason: String, notify: Boolean) {
        if (ending) return
        ending = true
        pendingRoute?.let { main.removeCallbacks(it) }
        pendingRoute = null
        main.removeCallbacks(openTimeout)
        val wasActive = hasSession
        hasSession = false
        if (routeHeld) native.setCafaAudioEnabled(false)
        connected = false
        link.close()
        if (routeHeld) { surface.stop(); native.panic(); native.setCafaOutputDevice(0) }
        status = reason
        // Keep selected FM1 excluded from generic MIDI until user explicitly restores normal audio.
        ending = false
        if (notify && wasActive) onSessionEnded?.invoke()
    }
    fun restoreNormalAudio() {
        endSession("Normal Loom audio selected. Transport stopped.", notify = false)
        native.setCafaOutputDevice(-1); routeHeld = false
        preferences.edit().putBoolean(HELD, false).commit()
        ordinaryMidi.reserveForCafa(null)
        onSessionEnded?.invoke()
    }
    fun close() { disconnect(); closed = true }
}
