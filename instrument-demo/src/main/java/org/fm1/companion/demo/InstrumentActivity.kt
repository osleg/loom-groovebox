/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class InstrumentActivity : Activity() {
    private lateinit var audioManager: AudioManager
    private lateinit var usbManager: UsbManager
    private lateinit var modeChoice: Spinner
    private lateinit var midiChoice: Spinner
    private lateinit var outputChoice: Spinner
    private lateinit var externalInputChoice: Spinner
    private lateinit var externalOutputChoice: Spinner
    private lateinit var outputLabel: TextView
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var measureButton: Button
    private lateinit var resultButton: Button
    private lateinit var sampleSlot: Spinner
    private lateinit var importButton: Button
    private lateinit var captureButton: Button
    private lateinit var saveSampleButton: Button
    private lateinit var discardSampleButton: Button
    private lateinit var sampleStatus: TextView
    private var sampleNotice = ""
    private var wavPickerSession: String? = null
    private var wavPickerUri: Uri? = null
    private var service: InstrumentPlaybackService? = null
    private var bound = false
    private var visible = false
    private var wasActive = false
    private var lastMidiId = -1
    private var lastAudioId = -1
    private var missingMidiId = -1
    private var pendingUsb: PendingUsb? = null
    private var permissionInterrupted = false
    private var permissionNotice = ""
    private var permissionSerial = 0
    private var updatingChoices = false
    private var receiverRegistered = false
    private var midiDevices = emptyList<MidiDeviceInfo>()
    private var outputs = emptyList<AudioDeviceInfo>()
    private var externalInputs = emptyList<MidiDeviceInfo>()
    private var externalOutputs = emptyList<MidiDeviceInfo>()
    private val observer: () -> Unit = { if (visible) refreshFromService() }
    private val main = Handler(Looper.getMainLooper())
    private val routeCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshDevices()
    }
    private data class PendingUsb(val serial: Int, val midiId: Int, val deviceId: Int,
                                  val deviceName: String, val diagnostic: Boolean,
                                  val request: PendingIntent)
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val pending = pendingUsb ?: return
                    if (intent.getIntExtra(EXTRA_PERMISSION_SERIAL, -1) != pending.serial) return
                    pendingUsb = null
                    pending.request.cancel()
                    val device = usbDevice(intent)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        device?.deviceId == pending.deviceId && device.deviceName == pending.deviceName &&
                        usbManager.hasPermission(device)) {
                        if (visible && !permissionInterrupted && service != null)
                            connectDirect(pending.midiId, pending.deviceId, pending.deviceName, pending.diagnostic)
                        else permissionNotice = "FM-1 USB permission is ready. Tap Connect or Measure again."
                    } else {
                        permissionNotice = "FM-1 direct USB permission denied or device changed. Choose again."
                        if (visible) status.text = permissionNotice
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val pending = pendingUsb ?: return
                    val device = usbDevice(intent)
                    if (device?.deviceId == pending.deviceId && device.deviceName == pending.deviceName) {
                        clearPendingUsb()
                        refreshDevices()
                        status.text = "Selected FM-1 was detached. Choose again."
                    }
                }
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible) return
            service = (binder as InstrumentPlaybackService.LocalBinder).service
            service?.addObserver(observer)
            refreshDevices()
            refreshFromService()
            deliverWav()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            clearPendingUsb()
            service = null
            status.text = "Connection service stopped"
            connectButton.isEnabled = false
            refreshSampleControls()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wavPickerSession = savedInstanceState?.getString("wav_session")
        wavPickerUri = savedInstanceState?.getString("wav_uri")?.let(Uri::parse)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(stack) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        stack.addView(TextView(this).apply { text = "FM-1 Instrument • Skiff 2.5.1"; textSize = 21f })
        stack.addView(TextView(this).apply {
            text = "Choose FM-1 USB MIDI and an audio destination. Direct USB sends the synth to FM-1 MASTER; Android audio shares its output. Playback continues with the screen off. Disconnect to change selections."
        })
        stack.addView(label("Audio destination"))
        modeChoice = Spinner(this).also { stack.addView(it) }
        modeChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            AudioDestination.values().map { it.title })
        modeChoice.setSelection(AudioDestination.FM1_DIRECT.ordinal)
        modeChoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingChoices) {
                    if (destination() != AudioDestination.FM1_DIRECT) clearPendingUsb()
                    refreshDevices()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        stack.addView(label("FM-1 USB MIDI endpoint"))
        midiChoice = Spinner(this).also { stack.addView(it) }
        midiChoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingChoices && service != null && service?.snapshot()?.active == false) {
                    if (pendingUsb?.midiId != midiDevices.getOrNull(position)?.id) clearPendingUsb()
                    refreshExternalChoices()
                    refreshDirectTarget()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        outputLabel = label("Phone speaker or wired output").also { stack.addView(it) }
        outputChoice = Spinner(this).also { stack.addView(it) }
        stack.addView(label("External USB MIDI input (optional)"))
        externalInputChoice = Spinner(this).also { stack.addView(it) }
        stack.addView(label("External USB MIDI output (optional)"))
        externalOutputChoice = Spinner(this).also { stack.addView(it) }
        stack.addView(Button(this).apply {
            text = "Refresh devices"
            setOnClickListener { missingMidiId = -1; lastMidiId = -1; refreshDevices() }
        })
        connectButton = Button(this).apply {
            text = "Connect"; isEnabled = false; setOnClickListener { toggle() }
        }
        stack.addView(connectButton)
        measureButton = Button(this).apply {
            text = "Measure key-to-audio · 60 s"
            isEnabled = false
            setOnClickListener { toggle(diagnostic = true) }
        }
        stack.addView(measureButton)
        resultButton = Button(this).apply {
            text = "Last measurement"
            setOnClickListener {
                val summary = HardwareMeasurement.lastSummary(filesDir) ?: "No saved hardware measurement yet."
                AlertDialog.Builder(this@InstrumentActivity).setTitle("FM-1 measurement")
                    .setMessage(summary).setPositiveButton("Close", null).show()
            }
        }
        stack.addView(resultButton)
        status = label("Loading…").also { it.setTextIsSelectable(true); stack.addView(it) }
        stack.addView(label("User sample slot"))
        sampleSlot = Spinner(this).apply {
            adapter = ArrayAdapter(this@InstrumentActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("USR1", "USR2", "USR3"))
            setSelection(savedInstanceState?.getInt("sample_slot", 0) ?: 0)
        }.also { stack.addView(it) }
        importButton = Button(this).apply {
            text = "Import WAV"
            setOnClickListener { chooseWav() }
        }.also { stack.addView(it) }
        captureButton = Button(this).apply {
            setOnClickListener {
                val owner = service ?: return@setOnClickListener
                sampleNotice = ""
                if (owner.sampleState().recording) owner.finishSampleCapture() else owner.startSampleCapture()
            }
        }.also { stack.addView(it) }
        saveSampleButton = Button(this).apply {
            text = "Save sample"
            setOnClickListener { confirmSampleSave() }
        }.also { stack.addView(it) }
        discardSampleButton = Button(this).apply {
            text = "Discard sample"
            setOnClickListener { service?.discardSample() }
        }.also { stack.addView(it) }
        sampleStatus = label("").also { stack.addView(it) }
        stack.addView(Button(this).apply {
            text = "Edit DX386 lyrics"
            setOnClickListener { editDxText() }
        })
        refreshSampleControls()
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // The signature permission also protects the reply on Android versions predating exported receiver flags.
        registerReceiver(usbReceiver, filter, "org.fm1.companion.demo.permission.USB_CALLBACK",
            main, RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun editDxText() {
        val owner = service ?: return
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "English lyrics"
            setText(owner.dxText())
            setSelection(length())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("DX386 lyrics")
            .setMessage("Enter up to 63 English characters. Select USER on DX386 to sing this line.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val line = input.text.toString().trim()
            input.error = when {
                line.length > 63 || line.any { it !in ' '..'~' } -> "Use up to 63 ASCII characters."
                line.isNotEmpty() && line.none { it.isLetterOrDigit() } -> "Enter at least one letter or digit."
                service !== owner || !owner.saveDxText(line) -> "Lyrics are unavailable in this session."
                else -> { dialog.dismiss(); null }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseWav() {
        val session = service?.sampleState()?.session ?: return
        if (wavPickerSession != null) return
        sampleNotice = ""
        wavPickerSession = session
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "application/octet-stream"))
            }, PICK_WAV)
        } catch (_: android.content.ActivityNotFoundException) {
            wavPickerSession = null
            sampleNotice = "No file picker is available."
        }
        refreshSampleControls()
    }

    @Deprecated("Uses the platform picker result API for this Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_WAV || wavPickerSession == null) return
        wavPickerUri = data?.data?.takeIf { resultCode == RESULT_OK }
        if (wavPickerUri == null) wavPickerSession = null
        deliverWav()
        refreshSampleControls()
    }

    private fun deliverWav() {
        val owner = service ?: return
        val session = wavPickerSession ?: return
        val uri = wavPickerUri ?: return
        wavPickerSession = null
        wavPickerUri = null
        if (!owner.importSample(session, uri)) {
            sampleNotice = "The sample session changed. Choose the WAV again."
            refreshSampleControls()
        }
    }

    private fun confirmSampleSave() {
        val owner = service ?: return
        val state = owner.sampleState()
        val session = state.session ?: return
        val sample = state.ready ?: return
        val slot = sampleSlot.selectedItemPosition
        if (slot !in 0..2 || state.busy) return
        val label = "USR${slot + 1}"
        sampleNotice = ""
        AlertDialog.Builder(this)
            .setTitle("Save to $label?")
            .setMessage("This replaces any existing sample and slice edits in $label.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace") { _, _ ->
                if (service !== owner || !owner.saveSample(session, sample, slot)) {
                    sampleNotice = "The sample is no longer ready to save."
                    refreshSampleControls()
                }
            }.show()
    }

    private fun refreshSampleControls() {
        val sample = service?.sampleState()
        val available = sample?.session != null
        val busy = sample?.busy == true || wavPickerSession != null
        val recording = sample?.recording == true
        val ready = sample?.ready != null
        sampleSlot.isEnabled = available && !busy && !recording
        importButton.isEnabled = available && !busy && !recording && !ready
        captureButton.text = if (recording) "Finish capture" else "Capture Skiff mix"
        captureButton.isEnabled = available && !busy && !ready
        saveSampleButton.visibility = if (ready) View.VISIBLE else View.GONE
        discardSampleButton.visibility = saveSampleButton.visibility
        saveSampleButton.isEnabled = available && !busy
        discardSampleButton.isEnabled = saveSampleButton.isEnabled
        sampleStatus.text = sampleNotice.ifEmpty { sample?.status.orEmpty().ifEmpty {
            if (available) "Import up to 3 seconds of PCM WAV, or capture the current mix."
            else "Connect to FM-1 to import or capture a sample."
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("wav_session", wavPickerSession)
        outState.putString("wav_uri", wavPickerUri?.toString())
        outState.putInt("sample_slot", sampleSlot.selectedItemPosition)
    }

    private fun label(value: String) = TextView(this).apply { text = value; textSize = 16f }
    private fun destination(): AudioDestination =
        AudioDestination.values().getOrElse(modeChoice.selectedItemPosition) { AudioDestination.PHONE }

    private fun refreshDevices() {
        val owner = service ?: return
        val state = owner.snapshot()
        updatingChoices = true
        try {
        if (state.active) {
            lastMidiId = state.midiId
            lastAudioId = state.audioId
            midiChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf(state.midiName))
            outputChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf(state.audioName))
            externalInputChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf(if (state.externalInputId < 0) "None" else "USB MIDI ${state.externalInputId}"))
            externalOutputChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                listOf(if (state.externalOutputId < 0) "None" else "USB MIDI ${state.externalOutputId}"))
            refreshFromService()
            return
        }
        val previousMidi = if (wasActive) lastMidiId else
            midiDevices.getOrNull(midiChoice.selectedItemPosition)?.id ?: lastMidiId
        val previousAudio = if (wasActive) lastAudioId else
            outputs.getOrNull(outputChoice.selectedItemPosition)?.id ?: lastAudioId
        val availableMidi = owner.midiDevices()
        if (previousMidi >= 0 && availableMidi.none { it.id == previousMidi }) missingMidiId = previousMidi
        midiDevices = if (missingMidiId >= 0) emptyList() else availableMidi
        val selected = destination()
        outputs = if (selected == AudioDestination.FM1_DIRECT) emptyList() else
            AudioOutput.choices(audioManager, selected)
        outputLabel.text = when (selected) {
            AudioDestination.FM1_DIRECT -> "FM-1 direct USB output (synth to MASTER)"
            AudioDestination.FM1 -> "Select FM-1 USB output"
            else -> "Select Phone speaker or wired output"
        }
        outputChoice.isEnabled = selected != AudioDestination.FM1_DIRECT
        midiChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            midiDevices.map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI ${it.id}" }
                .ifEmpty { listOf(if (missingMidiId >= 0) "Selected FM-1 removed · refresh to choose" else
                    "No FM-1 USB MIDI endpoint") })
        outputChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (selected == AudioDestination.FM1_DIRECT) listOf("Checking selected FM-1…") else
                outputs.map { "${it.productName} (${if (it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "speaker" else if (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) "USB" else "wired"})" }
                    .ifEmpty { listOf(if (selected == AudioDestination.FM1) "No USB output" else "No phone output") })
        midiDevices.indexOfFirst { it.id == previousMidi }.takeIf { it >= 0 }?.let { midiChoice.setSelection(it) }
        outputs.indexOfFirst { it.id == previousAudio }.takeIf { it >= 0 }?.let { outputChoice.setSelection(it) }
        lastMidiId = midiDevices.getOrNull(midiChoice.selectedItemPosition)?.id ?: -1
        lastAudioId = outputs.getOrNull(outputChoice.selectedItemPosition)?.id ?: -1
        refreshExternalChoices()
        refreshDirectTarget()
        connectButton.isEnabled = midiDevices.isNotEmpty() &&
            (selected == AudioDestination.FM1_DIRECT || outputs.isNotEmpty())
        measureButton.isEnabled = connectButton.isEnabled && destination() != AudioDestination.PHONE
        } finally { updatingChoices = false }
    }

    private fun refreshDirectTarget() {
        if (destination() != AudioDestination.FM1_DIRECT) return
        val selectedMidi = midiDevices.getOrNull(midiChoice.selectedItemPosition)
        val device = selectedMidi?.let { DirectUsbOutput.deviceFor(usbManager, it) }
        outputChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(if (device != null) "${device.productName ?: device.deviceName} · FM-1 MASTER" else
                "No direct interface on selected FM-1"))
        outputChoice.isEnabled = false
        connectButton.isEnabled = selectedMidi != null
    }

    private fun refreshExternalChoices() {
        val owner = service ?: return
        val fm1Id = midiDevices.getOrNull(midiChoice.selectedItemPosition)?.id ?: -1
        val previousInput = externalInputs.getOrNull(externalInputChoice.selectedItemPosition - 1)?.id ?: -1
        val previousOutput = externalOutputs.getOrNull(externalOutputChoice.selectedItemPosition - 1)?.id ?: -1
        externalInputs = owner.externalDevices(fm1Id, true)
        externalOutputs = owner.externalDevices(fm1Id, false)
        externalInputChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("None") + externalInputs.map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI ${it.id}" })
        externalOutputChoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("None") + externalOutputs.map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "USB MIDI ${it.id}" })
        externalInputs.indexOfFirst { it.id == previousInput }.takeIf { it >= 0 }?.let { externalInputChoice.setSelection(it + 1) }
        externalOutputs.indexOfFirst { it.id == previousOutput }.takeIf { it >= 0 }?.let { externalOutputChoice.setSelection(it + 1) }
    }

    private fun refreshFromService() {
        val owner = service ?: return
        val state = owner.snapshot()
        if (wasActive && !state.active) refreshDevices()
        wasActive = state.active
        status.text = state.status + owner.screenStatus().let { if (it.isEmpty()) "" else "\nSkiff: $it" } +
            permissionNotice.let { if (it.isEmpty()) "" else "\n$it" }
        if (state.active) {
            val modes = AudioDestination.values()
            updatingChoices = true
            try { modes.indexOf(state.destination).takeIf { it >= 0 }?.let { modeChoice.setSelection(it) } }
            finally { updatingChoices = false }
            outputLabel.text = "${state.destination?.title} output: ${state.audioName}"
        }
        modeChoice.isEnabled = !state.active
        midiChoice.isEnabled = !state.active
        outputChoice.isEnabled = !state.active && destination() != AudioDestination.FM1_DIRECT
        externalInputChoice.isEnabled = !state.active
        externalOutputChoice.isEnabled = !state.active
        measureButton.isEnabled = !state.active && destination() != AudioDestination.PHONE &&
            midiDevices.isNotEmpty() && (destination() == AudioDestination.FM1_DIRECT || outputs.isNotEmpty())
        connectButton.text = if (state.active) "Disconnect" else "Connect"
        connectButton.isEnabled = state.active || (midiDevices.isNotEmpty() &&
            (destination() == AudioDestination.FM1_DIRECT || outputs.isNotEmpty()))
        refreshSampleControls()
    }

    private fun toggle(diagnostic: Boolean = false) {
        permissionNotice = ""
        val owner = service ?: return
        if (owner.snapshot().active) { owner.disconnect(); refreshDevices(); return }
        val selectedMidi = midiDevices.getOrNull(midiChoice.selectedItemPosition)
        val selected = destination()
        if (selectedMidi == null) { status.text = "Choose FM-1 USB MIDI"; return }
        if (selected == AudioDestination.FM1_DIRECT) {
            val device = DirectUsbOutput.deviceFor(usbManager, selectedMidi)
            if (device == null) {
                status.text = "This FM-1 firmware has no direct USB interface. Install the matching update, or deliberately select FM-1 · Android audio (shared)."
                return
            }
            if (owner.midiDevices().none { it.id == selectedMidi.id } ||
                usbManager.deviceList.values.none { it.deviceId == device.deviceId && it.deviceName == device.deviceName }) {
                refreshDevices(); status.text = "Device changed; choose again"; return
            }
            if (usbManager.hasPermission(device)) connectDirect(selectedMidi.id, device.deviceId, device.deviceName, diagnostic)
            else requestDirectPermission(selectedMidi, device, diagnostic)
            return
        }
        val selectedAudio = outputs.getOrNull(outputChoice.selectedItemPosition)
        if (selectedAudio == null) { status.text = "Choose an audio output"; return }
        lastMidiId = selectedMidi.id
        lastAudioId = selectedAudio.id
        if (owner.midiDevices().none { it.id == selectedMidi.id } ||
            AudioOutput.choices(audioManager, selected).none { it.id == selectedAudio.id }) {
            refreshDevices(); status.text = "Device changed; choose again"; return
        }
        startPlayback(selected, selectedMidi.id, selectedAudio.id, diagnostic)
    }

    private fun requestDirectPermission(midi: MidiDeviceInfo, device: UsbDevice, diagnostic: Boolean) {
        clearPendingUsb()
        val serial = ++permissionSerial
        permissionInterrupted = false
        val request = PendingIntent.getBroadcast(this, serial,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName)
                .putExtra(EXTRA_PERMISSION_SERIAL, serial),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        pendingUsb = PendingUsb(serial, midi.id, device.deviceId, device.deviceName, diagnostic, request)
        try {
            usbManager.requestPermission(device, request)
            status.text = "Allow direct USB access to the selected FM-1 to use MASTER output."
        } catch (error: RuntimeException) {
            clearPendingUsb()
            status.text = "Could not request FM-1 USB permission: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun clearPendingUsb() {
        pendingUsb?.request?.cancel()
        pendingUsb = null
        permissionInterrupted = false
    }

    @Suppress("DEPRECATION")
    private fun usbDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun connectDirect(midiId: Int, deviceId: Int, deviceName: String, diagnostic: Boolean) {
        val owner = service ?: return
        if (!visible || owner.snapshot().active || destination() != AudioDestination.FM1_DIRECT ||
            midiDevices.getOrNull(midiChoice.selectedItemPosition)?.id != midiId) {
            status.text = "Selection changed; choose again"
            return
        }
        val midi = owner.midiDevices().firstOrNull { it.id == midiId }
        val device = midi?.let { DirectUsbOutput.deviceFor(usbManager, it) }
        if (device == null || device.deviceId != deviceId || device.deviceName != deviceName ||
            usbManager.deviceList.values.none { it.deviceId == deviceId && it.deviceName == deviceName } ||
            !usbManager.hasPermission(device)) {
            refreshDevices()
            status.text = "Selected FM-1 or USB permission changed. Choose again."
            return
        }
        lastMidiId = midiId
        lastAudioId = deviceId
        startPlayback(AudioDestination.FM1_DIRECT, midiId, deviceId, diagnostic)
    }

    private fun startPlayback(selected: AudioDestination, midiId: Int, audioId: Int,
                              diagnostic: Boolean) {
        val intent = Intent(this, InstrumentPlaybackService::class.java).setAction(InstrumentPlaybackService.ACTION_CONNECT)
            .putExtra(InstrumentPlaybackService.EXTRA_DIAGNOSTIC, diagnostic)
            .putExtra(InstrumentPlaybackService.EXTRA_MIDI_ID, midiId)
            .putExtra(InstrumentPlaybackService.EXTRA_AUDIO_ID, audioId)
            .putExtra(InstrumentPlaybackService.EXTRA_DESTINATION, selected.ordinal)
            .putExtra(InstrumentPlaybackService.EXTRA_EXTERNAL_INPUT_ID,
                if (diagnostic) -1 else externalInputs.getOrNull(externalInputChoice.selectedItemPosition - 1)?.id ?: -1)
            .putExtra(InstrumentPlaybackService.EXTRA_EXTERNAL_OUTPUT_ID,
                if (diagnostic) -1 else externalOutputs.getOrNull(externalOutputChoice.selectedItemPosition - 1)?.id ?: -1)
        try { startForegroundService(intent) } catch (error: RuntimeException) {
            status.text = "Could not start playback: ${error.message ?: error.javaClass.simpleName}"
            connectButton.isEnabled = true
            return
        }
        connectButton.isEnabled = false
        status.text = "Starting ${selected.title} output…"
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        bound = bindService(Intent(this, InstrumentPlaybackService::class.java), connection, BIND_AUTO_CREATE)
        audioManager.registerAudioDeviceCallback(routeCallback, main)
    }
    override fun onStop() {
        visible = false
        if (pendingUsb != null) permissionInterrupted = true
        audioManager.unregisterAudioDeviceCallback(routeCallback)
        service?.removeObserver(observer)
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        clearPendingUsb()
        if (receiverRegistered) { unregisterReceiver(usbReceiver); receiverRegistered = false }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "org.fm1.companion.demo.USB_PERMISSION"
        private const val EXTRA_PERMISSION_SERIAL = "permission_serial"
        private const val PICK_WAV = 2
    }
}
