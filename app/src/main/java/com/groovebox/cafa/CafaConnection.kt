/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.*
import com.groovebox.persistence.PersistenceManager
import java.security.SecureRandom

/** Selected USB MIDI + selected USB audio only. Reconnect/normal-audio restoration are explicit. */
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
    private val main = Handler(Looper.getMainLooper())
    private val midi = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var device: MidiDevice? = null
    private var input: MidiInputPort? = null
    private var output: MidiOutputPort? = null
    private var session: CafaSession? = null
    private var generation = 0
    private var audioId = 0
    private var midiId = -1
    private var closed = false
    private var routeStart = 0L
    var status by mutableStateOf(if (preferences.getBoolean(HELD, false)) "FM1 output remains muted. Reconnect or Restore normal audio." else "FM1 disconnected"); private set
    var connected by mutableStateOf(false); private set
    var routeHeld by mutableStateOf(preferences.getBoolean(HELD, false)); private set
    var notice by mutableStateOf(""); private set
    val surface = CafaSurface({ model.state }, { model.onStateChange(it) }, object : CafaSurface.Engine {
        override fun selectEngine(track: Int, type: EngineType) { native.setEngineType(track, type.ordinal) }
        override fun noteOn(track: Int, note: Int) { native.triggerNote(track, note, 100) }
        override fun noteOff(track: Int, note: Int) { native.releaseNote(track, note) }
        override fun playing(value: Boolean) { native.setPlaying(value) }
        override fun recording(value: Boolean) { native.setIsRecording(value) }
        override fun parameter(track: Int, id: Int, value: Float) { native.setParameter(track, id, value) }
        override fun volume(track: Int, value: Float) { native.setTrackVolume(track, value) }
        override fun pan(track: Int, value: Float) { native.setTrackPan(track, value) }
        override fun mute(track: Int, value: Boolean) { native.setTrackMute(track, value) }
        override fun tempo(value: Float) { native.setTempo(value) }
        override fun length(track: Int, value: Int) { native.setSequencerConfig(track, 1, value) }
        override fun step(track: Int, index: Int, value: StepState) {
            native.setStep(track, index, value.active, value.notes.toIntArray(), value.velocity,
                value.ratchet, value.punch, value.probability, value.gate, value.isSkipped,
                value.subStepOffset, value.noteOffsets.toFloatArray(), value.noteVelocities.toFloatArray())
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
    }, { model.setSelectedTrack(it); native.setFocusedTrack(it) }, {
        // Snapshot only the phone's own project, never FM1 standalone data.
        PersistenceManager.saveProject(model.state, "last_session.gbx")
        notice = "Saved Loom's local last session."
    }, { notice = it })

    fun midiDevices(): List<MidiDeviceInfo> = midi?.devices?.filter {
        it.type == MidiDeviceInfo.TYPE_USB && it.inputPortCount > 0 && it.outputPortCount > 0
    } ?: emptyList()
    fun audioDevices(): List<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
        it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
    private val timer = object : Runnable {
        override fun run() {
            if (closed || session == null) return
            if (midiDevices().none { it.id == midiId } || audioDevices().none { it.id == audioId }) {
                disconnect("FM1 USB disconnected. Output muted; reconnect explicitly."); return
            }
            if (native.getCafaOutputDevice() != audioId) {
                // AAudio requestStart is asynchronous. Permit bounded startup while
                // still muted, but never tolerate a route loss after synchronization.
                if (!connected && SystemClock.elapsedRealtime() - routeStart < 1000) {
                    main.postDelayed(this, 34); return
                }
                disconnect("USB output route was lost. Output muted; reconnect explicitly."); return
            }
            if (connected && !native.isCafaAudioEnabled()) {
                disconnect("USB output restarted or was muted. Reconnect to resynchronize."); return
            }
            try { session?.tick(SystemClock.elapsedRealtime()) }
            catch (error: Exception) { disconnect("FM1 state failed safely: ${error.javaClass.simpleName}"); return }
            if (session?.isOpen == true) main.postDelayed(this, 34)
        }
    }
    fun connect(midiInfo: MidiDeviceInfo, audioInfo: AudioDeviceInfo) {
        if (closed) return
        disconnect("Opening selected FM1 endpoints…")
        if (midiDevices().none { it.id == midiInfo.id } || audioDevices().none { it.id == audioInfo.id }) {
            status = "Select available USB MIDI and USB audio outputs."; return
        }
        val token = ++generation
        // Commit the fail-closed choice before touching output, so process death
        // or activity recreation cannot silently restore the default speaker.
        if (!preferences.edit().putBoolean(HELD, true).commit()) {
            status = "Could not save the safe output mode. FM1 connection was not started."; return
        }
        routeHeld = true; audioId = audioInfo.id; midiId = midiInfo.id
        routeStart = SystemClock.elapsedRealtime()
        ordinaryMidi.reserveForCafa(midiInfo.id)
        native.setCafaAudioEnabled(false)
        if (!native.setCafaOutputDevice(audioId)) {
            disconnect("Android could not verify 44.1 kHz stereo on this USB output. Output stays muted."); return
        }
        try { midi?.openDevice(midiInfo, { opened ->
            if (token != generation || closed) { runCatching { opened?.close() }; return@openDevice }
            try {
            if (opened == null) { disconnect("Android could not open selected USB MIDI device."); return@openDevice }
            device = opened
            input = midiInfo.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }?.let { opened.openInputPort(it.portNumber) }
            output = midiInfo.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }?.let { opened.openOutputPort(it.portNumber) }
            if (input == null || output == null) { disconnect("Selected USB MIDI ports are unavailable."); return@openDevice }
            val link = CafaSession(object : CafaSession.Host {
                override fun send(bytes: ByteArray) { val port = input ?: error("MIDI port closed"); port.send(bytes, 0, bytes.size) }
                override fun snapshot() = surface.snapshot()
                override fun input(serial: Int, payload: ByteArray) = surface.input(serial, payload)
                override fun reset() { surface.reset() }
                override fun applied() {
                    if (native.getCafaOutputDevice() != audioId) { disconnect("USB route verification failed. Output stays muted."); return }
                    native.setCafaAudioEnabled(true); connected = true
                    status = "FM1 synchronized · Loom 8 tracks · controls v5"
                }
                override fun stopped(reason: String) { disconnect(reason) }
            })
            session = link
            val decoder = Protocol.Decoder { frame -> main.post {
                if (token == generation) try { session?.receive(frame, SystemClock.elapsedRealtime()) }
                catch (error: Exception) { disconnect("FM1 input failed safely: ${error.javaClass.simpleName}") }
            } }
            output?.connect(object : MidiReceiver() {
                override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) { synchronized(decoder) { decoder.feed(data, offset, count) } }
                override fun onFlush() { synchronized(decoder) { decoder.reset() } }
            })
            status = "Waiting for Loom CAFA v5 firmware…"
            link.begin(1 + SecureRandom().nextInt(16383), SystemClock.elapsedRealtime())
            main.postDelayed(timer, 34)
            } catch (error: Exception) { disconnect("USB MIDI setup failed safely: ${error.javaClass.simpleName}") }
        }, main) } catch (error: Exception) { disconnect("USB MIDI open failed: ${error.javaClass.simpleName}") }
    }
    fun disconnect(reason: String = "FM1 disconnected. Output remains muted until reconnect or Restore normal audio.") {
        generation++; main.removeCallbacks(timer)
        if (routeHeld) native.setCafaAudioEnabled(false)
        connected = false
        val old = session; session = null; old?.close()
        if (routeHeld) { surface.stop(); native.panic(); native.setCafaOutputDevice(0) }
        runCatching { output?.close() }; runCatching { input?.close() }; runCatching { device?.close() }
        output = null; input = null; device = null; status = reason
        // Keep selected FM1 excluded from generic MIDI until user explicitly restores normal audio.
    }
    fun restoreNormalAudio() {
        disconnect("Normal Loom audio selected. Transport stopped.")
        native.setCafaOutputDevice(-1); routeHeld = false
        preferences.edit().putBoolean(HELD, false).commit()
        ordinaryMidi.reserveForCafa(null)
    }
    fun close() { disconnect(); closed = true }
}
