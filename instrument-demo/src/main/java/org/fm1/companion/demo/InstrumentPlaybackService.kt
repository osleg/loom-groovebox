/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import org.fm1.companion.protocol.NativeUi
import org.fm1.companion.protocol.OpenMidiConnection
import org.fm1.companion.protocol.Wire6
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Main-thread owner of the reference instrument, including its screen-off MIDI/audio lease. */
class InstrumentPlaybackService : Service() {
    data class State(val status: String, val active: Boolean, val synchronized: Boolean,
                     val destination: AudioDestination?, val midiId: Int, val audioId: Int,
                     val midiName: String, val audioName: String,
                     val externalInputId: Int, val externalOutputId: Int)
    data class SampleState(val session: String?, val status: String, val recording: Boolean,
                           val busy: Boolean, val ready: Long?)
    private data class StagedSample(val id: Long, val encoded: ByteArray)

    inner class LocalBinder : Binder() { val service: InstrumentPlaybackService get() = this@InstrumentPlaybackService }
    private val binder = LocalBinder()
    @Volatile private var runtime: SkiffRuntime? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var midi: OpenMidiConnection
    private lateinit var audioManager: AudioManager
    private lateinit var usbManager: UsbManager
    private var output: InstrumentOutput? = null
    @Volatile private var external: ExternalMidiRoute? = null
    private val observers = mutableSetOf<() -> Unit>()
    @Volatile private var state = State("Disconnected", false, false, null, -1, -1, "", "", -1, -1)
    private var measurement: HardwareMeasurement? = null
    private var sampleSession: String? = null
    private var sampleStatus = ""
    private var sampleWorker: ExecutorService? = null
    private var stagedSample: StagedSample? = null
    private var sampleSerial = 0L
    private var sampleBusy = false
    private var sampleRecording = false
    private val sampleTimeout = Runnable { finishSampleCapture() }
    private var measureAt = 0L
    private var measuring = false
    private var pendingOp = 0
    private var requestAt = 0L
    private var deadline = 0L
    private var lastDisplayedSecond = -1
    private var attempt = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    @Volatile private var finishing = false
    private val usbDetach = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED ||
                !state.active || state.destination != AudioDestination.FM1_DIRECT) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device?.deviceId == state.audioId) finish("Selected FM-1 was unplugged. Reconnect manually.")
        }
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (state.active && state.destination != AudioDestination.FM1_DIRECT &&
                removedDevices.any { it.id == state.audioId })
                finish("Selected audio output was removed. Reconnect manually.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        val usbFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbDetach, usbFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbDetach, usbFilter)
        midi = OpenMidiConnection(this, object : OpenMidiConnection.Surface {
            override fun snapshot(): ByteArray {
                val view = requireNotNull(runtime).surface.snapshot()
                val title = titleOf(view)
                if (screenTitle != title) { screenTitle = title; notifyObservers() }
                return view
            }
            override fun input(serial: Int, scan: ByteArray): Boolean =
                requireNotNull(runtime).surface.input(serial, scan)
            override fun reset() { runtime?.surface?.reset() }
        }, object : OpenMidiConnection.Listener {
            override fun beforeTick(now: Long): Boolean {
                if (measurement == null) runtime?.checkpoint()
                else tickMeasurement(now)
                // The connection checks its MIDI endpoint before this callback.
                return output?.routeMatches() == true && externalDevicesPresent()
            }
            override fun midiPacket(packet: Int, timestamp: Long) {
                if (!finishing && state.active && runtime?.midiIn(packet, 2) == false)
                    main.post { if (!finishing && state.active) finish("FM-1 TRS MIDI input overflow") }
            }
            override fun diagnostics(op: Int, values: IntArray) {
                val capture = measurement ?: return
                if (finishing || capture.finished || op != pendingOp) return
                pendingOp = 0
                try {
                    val instrument = requireNotNull(runtime)
                    capture.sample(op, values, instrument.diagnosticProbeStats(),
                        if (op == 1) requireNotNull(output).queueDescription() else null, instrument::phoneTiming)
                    if (op == 3) {
                        val summary = capture.finish(null)
                        main.post { if (capture === measurement) finish(summary) }
                        return
                    }
                    measuring = true
                    measureAt = SystemClock.elapsedRealtime() + 220L
                    val seconds = capture.elapsedMs() / 1000
                    if (seconds != lastDisplayedSecond) {
                        lastDisplayedSecond = seconds
                        update(status = "Measuring ${capture.progress()}. Tap and release the leftmost white key every three seconds.")
                    }
                } catch (error: Exception) {
                    main.post { if (capture === measurement) finish("Measurement failed: ${error.message}") }
                }
            }
            override fun opened() { update(status = "FM-1 opened; waiting for display sync") }
            override fun synchronizedDisplay(audioMode: Int, transportAccepted: Boolean) {
                val selected = state.destination
                if (!state.active || selected == null || audioMode != selected.wireMode ||
                    !selectedDevicesPresent() || output?.routeMatches() != true) {
                    finish("Chosen audio mode or output changed. Reconnect manually.")
                    return
                }
                try { output?.enable() } catch (error: Exception) {
                    finish(error.message ?: "Chosen audio output changed")
                    return
                }
                if (measurement != null) {
                    measureAt = SystemClock.elapsedRealtime() + 4000L
                    deadline = 0L
                    update(status = "Busy pattern warming up on FM-1 USB audio…", synchronized = true)
                } else update(status = "Connected to FM-1. Audio: ${selected.title} — ${state.audioName}", synchronized = true)
                showNotification()
            }
            override fun stopped(reason: String) { finish(reason) }
        })
        val channel = NotificationChannel(CHANNEL, "FM-1 playback", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                if (state.active) return START_NOT_STICKY
                val destination = AudioDestination.values().getOrNull(intent.getIntExtra(EXTRA_DESTINATION, -1))
                val midiId = intent.getIntExtra(EXTRA_MIDI_ID, -1)
                val audioId = intent.getIntExtra(EXTRA_AUDIO_ID, -1)
                if (destination == null || midiId < 0 || audioId < 0) {
                    finish("Choose FM-1 MIDI and an audio output")
                    return START_NOT_STICKY
                }
                connect(destination, midiId, audioId,
                    intent.getIntExtra(EXTRA_EXTERNAL_INPUT_ID, -1),
                    intent.getIntExtra(EXTRA_EXTERNAL_OUTPUT_ID, -1),
                    intent.getBooleanExtra(EXTRA_DIAGNOSTIC, false))
            }
            ACTION_STOP -> finish("Disconnected")
            else -> { if (!state.active) stopSelf() }
        }
        return START_NOT_STICKY
    }

    fun addObserver(value: () -> Unit) { observers.add(value); value.invoke() }
    fun removeObserver(value: () -> Unit) { observers.remove(value) }
    private fun notifyObservers() { observers.toList().forEach { it.invoke() } }
    fun snapshot(): State = state
    fun midiDevices(): List<MidiDeviceInfo> = midi.devices()
    fun externalDevices(fm1Id: Int, input: Boolean): List<MidiDeviceInfo> =
        (getSystemService(MIDI_SERVICE) as MidiManager).devices.filter {
            it.type == MidiDeviceInfo.TYPE_USB && it.id != fm1Id &&
                (if (input) it.outputPortCount > 0 else it.inputPortCount > 0)
        }
    private var screenTitle = ""
    fun screenStatus(): String = screenTitle
    private fun titleOf(view: ByteArray): String = run {
        if (view.size < NativeUi.HEADER) "" else {
            val title = String(view, NativeUi.TITLE, 24, Charsets.US_ASCII).trim()
            val detail = String(view, NativeUi.SUBTITLE, 24, Charsets.US_ASCII).trim()
            if (detail.isEmpty()) title else "$title — $detail"
        }
    }
    fun disconnect() { finish("Disconnected") }

    private val dxPreferences by lazy { getSharedPreferences("dx386", MODE_PRIVATE) }

    fun dxText(): String = dxPreferences.getString("line", "").orEmpty()

    fun saveDxText(text: String): Boolean {
        if (measurement != null || text.length > 63 || text.any { it !in ' '..'~' }) return false
        val instrument = runtime
        if (instrument != null && !instrument.setDxText(text)) return false
        dxPreferences.edit().putString("line", text).apply()
        return true
    }

    fun sampleState(): SampleState = SampleState(
        sampleSession.takeIf { state.synchronized && !finishing }, sampleStatus,
        sampleRecording, sampleBusy, stagedSample?.id)

    fun importSample(session: String, uri: Uri): Boolean {
        val worker = sampleWorker ?: return false
        if (session != sampleState().session || sampleBusy || sampleRecording || stagedSample != null) return false
        updateSample("Reading WAV…", busy = true)
        worker.execute {
            val result = runCatching {
                val pcm = requireNotNull(contentResolver.openInputStream(uri)) { "Could not open the WAV file." }
                    .use(SampleWav::read)
                encodeSample(pcm, sampleName(uri))
            }
            main.post { stageSample(session, result) }
        }
        return true
    }

    private fun sampleName(uri: Uri): String {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        return name.orEmpty().substringBeforeLast('.').take(64).uppercase(Locale.ROOT)
            .filter { it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '-' || it == '_' }
            .trim().take(8).ifBlank { "WAV" }
    }

    fun startSampleCapture() {
        if (sampleState().session == null || sampleBusy || sampleRecording || stagedSample != null) return
        if (runtime?.startSampleCapture() != true) {
            updateSample("Could not start mix capture.")
            return
        }
        sampleRecording = true
        main.postDelayed(sampleTimeout, SampleWav.MAX_SECONDS * 1000L)
        updateSample("Capturing the Skiff mix for up to ${SampleWav.MAX_SECONDS} seconds…")
    }

    fun finishSampleCapture() {
        val session = sampleState().session ?: return
        val instrument = runtime ?: return
        val worker = sampleWorker ?: return
        if (!sampleRecording) return
        main.removeCallbacks(sampleTimeout)
        sampleRecording = false
        updateSample("Preparing captured mix…", busy = true)
        worker.execute {
            val result = runCatching {
                val pcm = requireNotNull(instrument.finishSampleCapture()) { "No mix audio was captured." }
                encodeSample(pcm, "CAPTURE")
            }
            main.post { stageSample(session, result) }
        }
    }

    private fun encodeSample(pcm: ShortArray, name: String): ByteArray =
        requireNotNull(SkiffNative.encodeSample(pcm, name, 60)) { "Could not encode the sample." }

    private fun stageSample(session: String, result: Result<ByteArray>) {
        if (session != sampleState().session) return
        result.onSuccess {
            stagedSample = StagedSample(++sampleSerial, it)
            updateSample("Sample ready. Choose a slot and save.")
        }.onFailure { updateSample(it.message ?: "Could not prepare the sample.") }
    }

    fun saveSample(session: String, id: Long, slot: Int): Boolean {
        val instrument = runtime ?: return false
        val sample = stagedSample ?: return false
        if (session != sampleState().session || id != sample.id || slot !in 0..2 || sampleBusy) return false
        val label = "USR${slot + 1}"
        updateSample("Saving $label…", busy = true)
        instrument.storeSample(slot, sample.encoded) { result ->
            main.post {
                if (session != sampleState().session) return@post
                result.onSuccess { code ->
                    when (code) {
                        0 -> {
                            stagedSample = null
                            updateSample("Sample saved to $label.")
                        }
                        1 -> updateSample("Skiff refused this sample.")
                        2 -> updateSample("Stop the Skiff transport, then save again.")
                        else -> updateSample("The sample slot is unavailable.")
                    }
                }.onFailure { updateSample("Could not save $label to storage.") }
            }
        }
        return true
    }

    fun discardSample() {
        if (sampleBusy || stagedSample == null) return
        stagedSample = null
        updateSample("")
    }

    private fun updateSample(status: String, busy: Boolean = false) {
        sampleStatus = status
        sampleBusy = busy
        notifyObservers()
    }

    private fun connect(destination: AudioDestination, midiId: Int, audioId: Int,
                        externalInputId: Int, externalOutputId: Int, diagnostic: Boolean) {
        if (diagnostic && (destination.wireMode != Wire6.USB_RETURN || externalInputId >= 0 || externalOutputId >= 0)) {
            finish("The hardware test needs FM-1 USB audio with external MIDI disabled")
            return
        }
        // The foreground notification is established before requesting focus or starting PCM/MIDI.
        val selectedMidi = midi.devices().firstOrNull { it.id == midiId }
        val direct = destination == AudioDestination.FM1_DIRECT
        val usbDevice = if (direct && selectedMidi != null)
            DirectUsbOutput.deviceFor(usbManager, selectedMidi)?.takeIf { it.deviceId == audioId } else null
        val selectedAudio = if (direct) null else AudioOutput.choices(audioManager, destination).firstOrNull { it.id == audioId }
        if (selectedMidi == null || (usbDevice == null && selectedAudio == null) ||
            externalInputId >= 0 && externalDevices(midiId, true).none { it.id == externalInputId } ||
            externalOutputId >= 0 && externalDevices(midiId, false).none { it.id == externalOutputId }) {
            finish("Device changed; choose again")
            return
        }
        if (usbDevice != null && !usbManager.hasPermission(usbDevice)) {
            finish("USB access is required for direct playback. Reconnect and allow access.")
            return
        }
        val midiName = selectedMidi.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI $midiId"
        val audioName = if (usbDevice != null) "${usbDevice.productName ?: "FM-1"} (direct USB)"
            else selectedAudio?.productName?.toString() ?: "Audio output $audioId"
        update(State("Starting ${destination.title} audio…", true, false, destination,
            midiId, audioId, midiName, audioName, externalInputId, externalOutputId))
        val token = ++attempt
        finishing = false
        try {
            val failure: (String) -> Unit = { reason -> main.post { if (token == attempt) finish(reason) } }
            val instrument = if (diagnostic) {
                measurement = HardwareMeasurement(filesDir, midiName, audioName).also { it.start() }
                SkiffRuntime(object : NorStore {
                    override fun read(): ByteArray? = null
                    override fun write(image: ByteArray) = Unit
                }, failure)
            } else SkiffRuntime(filesDir.resolve("skiff-nor.bin"), failure)
            runtime = instrument
            instrument.boot()
            if (diagnostic) instrument.startDiagnosticPattern() else {
                instrument.setDxText(dxText())
                sampleSession = UUID.randomUUID().toString()
                sampleWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "Skiff-samples") }
            }
            external = ExternalMidiRoute(this, requireNotNull(runtime)) { reason ->
                main.post { if (token == attempt) finish(reason) }
            }.also { it.open(externalInputId, externalOutputId, midiId) }
            showNotification()
            acquireWakeLock()
            // Direct PCM uses a private USB interface. Android may route notifications or other media independently.
            if (!direct && !requestAudioFocus()) { finish("Audio focus unavailable. Reconnect manually."); return }
            update(status = "Checking ${destination.title} output with silent audio…")
            val ready: () -> Unit = {
                if (token == attempt && state.active) {
                    if (!selectedDevicesPresent() || output?.routeMatches() != true) {
                        finish("Chosen audio output changed. Reconnect manually.")
                    } else {
                        update(status = "Output verified; connecting FM-1…")
                        val namespace = when {
                            diagnostic && direct -> "org.fm1.skiff.keytime2"
                            diagnostic -> "org.fm1.skiff.keytime"
                            else -> "org.fm1.skiff"
                        }
                        midi.connect(selectedMidi, namespace, destination.wireMode,
                            Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true),
                            Wire6.Capability(Wire6.NATIVE_UI, 1, 0, true),
                            Wire6.Capability(Wire6.NATIVE_IDLE_KEEPALIVE, 1, 0, false),
                            Wire6.Capability(Wire6.NATIVE_DELTA, 1, 0, false),
                            Wire6.Capability(Wire6.NATIVE_PANEL, 1, 0, true),
                            Wire6.Capability(Wire6.PANEL_MIDI, 1, 0, true),
                            Wire6.Capability(Wire6.NATIVE_CHROME, 1, 0, false))
                    }
                }
            }
            val midiOut: ((LongArray, Long) -> Unit)? = if (externalOutputId < 0) null else { packets, blockTime ->
                external?.queueTimed(packets, blockTime)
            }
            output = if (usbDevice != null)
                DirectUsbOutput(usbManager, usbDevice, instrument, ready, failure, midiOut)
            else AudioOutput(instrument, requireNotNull(selectedAudio), ready, failure, midiOut)
        } catch (error: Throwable) {
            finish(error.message ?: "Could not start audio")
        }
    }

    private fun tickMeasurement(now: Long) {
        val capture = measurement ?: return
        if (!state.synchronized || capture.finished || finishing) return
        if (deadline != 0L && now > deadline) { finish("The measurement window expired before a complete report arrived"); return }
        if (pendingOp != 0) {
            if (now - requestAt > 2200L) finish("FM-1 did not answer the timing request. Check the USB connection and the firmware for the selected playback mode.")
            return
        }
        if (now < measureAt) return
        if (!measuring) {
            // There can be no audible onset with MASTER at zero. Wait before starting the clock.
            if (requireNotNull(runtime).diagnosticProbeStats().masterGainQ12 == 0) {
                measureAt = now + 500L
                if (state.status != "Raise MASTER to start the key-to-audio test.")
                    update(status = "Raise MASTER to start the key-to-audio test.")
                return
            }
            deadline = now + 75_000L
        }
        val op = when {
            !measuring -> 1
            capture.elapsedMs() >= 60_000 -> 3
            else -> 2
        }
        pendingOp = op
        requestAt = now
        if (!midi.requestDiagnostics(op)) finish("FM-1 diagnostic request could not be sent")
    }

    private fun selectedDevicesPresent(): Boolean {
        val selected = state.destination ?: return false
        val controller = midi.devices().firstOrNull { it.id == state.midiId } ?: return false
        val present = if (selected == AudioDestination.FM1_DIRECT)
            DirectUsbOutput.deviceFor(usbManager, controller)?.let { it.deviceId == state.audioId && usbManager.hasPermission(it) } == true
        else AudioOutput.choices(audioManager, selected).any { it.id == state.audioId }
        return present && externalDevicesPresent()
    }

    private fun externalDevicesPresent() =
            (state.externalInputId < 0 || externalDevices(state.midiId, true).any { it.id == state.externalInputId }) &&
            (state.externalOutputId < 0 || externalDevices(state.midiId, false).any { it.id == state.externalOutputId })

    private fun requestAudioFocus(): Boolean {
        val token = attempt
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN && state.active &&
                    token == attempt && focusRequest === request)
                    finish("Audio focus lost. Reconnect manually.")
            }, main).build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun acquireWakeLock() {
        val lock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FM1:InstrumentPlayback")
        lock.setReferenceCounted(false)
        lock.acquire()
        wakeLock = lock
    }

    private fun showNotification() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, InstrumentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, InstrumentPlaybackService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("FM-1 Instrument")
            .setContentText("${state.destination?.title ?: "Audio"}: ${state.audioName} • ${state.status}")
            .setContentIntent(open).addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun update(value: State) { state = value; notifyObservers() }
    private fun update(status: String = state.status, synchronized: Boolean = state.synchronized) =
        update(state.copy(status = status, synchronized = synchronized))

    private fun finish(message: String) {
        if (finishing) return
        finishing = true
        runtime?.retire()
        main.removeCallbacks(sampleTimeout)
        sampleWorker?.shutdownNow()
        sampleWorker = null
        sampleSession = null
        sampleRecording = false
        sampleBusy = false
        stagedSample = null
        sampleStatus = ""
        var reason = message
        ++attempt
        measurement?.let { capture ->
            if (!capture.finished) reason = try { capture.finish(message) }
            catch (error: Exception) { "Could not save the hardware report: ${error.message}" }
        }
        measurement = null
        measureAt = 0L
        pendingOp = 0
        measuring = false
        lastDisplayedSecond = -1
        runCatching { output?.stop() }
        output = null
        runCatching { midi.close() }
        runCatching { external?.stopInput() }
        val ending = runtime
        runtime = null
        runCatching { ending?.stop() }.onSuccess { packets ->
            if (packets != null) external?.finishWith(packets)
        }.onFailure { reason = "Storage save failed: ${it.message ?: it.javaClass.simpleName}" }
        runCatching { external?.close() }
        external = null
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
        if (foreground) { runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }; foreground = false }
        screenTitle = ""
        update(State(reason, false, false, null, -1, -1, "", "", -1, -1))
        stopSelf()
    }

    override fun onDestroy() {
        if (state.active || foreground || wakeLock != null || focusRequest != null) finish("Disconnected")
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        unregisterReceiver(usbDetach)
        observers.clear()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "fm1_instrument_playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "org.fm1.companion.demo.CONNECT"
        const val ACTION_STOP = "org.fm1.companion.demo.STOP"
        const val EXTRA_DIAGNOSTIC = "hardware_measurement"
        const val EXTRA_MIDI_ID = "midi_id"
        const val EXTRA_AUDIO_ID = "audio_id"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_EXTERNAL_INPUT_ID = "external_input_id"
        const val EXTRA_EXTERNAL_OUTPUT_ID = "external_output_id"
    }
}
