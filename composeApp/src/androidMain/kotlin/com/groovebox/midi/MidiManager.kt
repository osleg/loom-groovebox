package com.groovebox.midi

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

actual class MidiManager(
    context: Context,
    private val onMessageReceived: (ByteArray, String?) -> Unit
) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as android.media.midi.MidiManager

    private data class DeviceEntry(
        val id: String,
        val info: android.media.midi.MidiDeviceInfo,
        val device: MidiDevice,
        val incoming: List<MidiOutputPort>, // data FROM the device
        val outgoing: List<MidiInputPort>   // data TO the device
    )

    private val registry = mutableMapOf<String, DeviceEntry>()
    private val pendingMessages = mutableListOf<ByteArray>()
    // A selected CAFA endpoint belongs exclusively to its framed-session transport.
    // Generic LED broadcasts, clock, queued traffic and raw MIDI must not share it.
    @Volatile private var cafaReservedId: Int? = null
    @Volatile private var closed = false

    @Synchronized
    fun reserveForCafa(deviceId: Int?) {
        val old = cafaReservedId
        cafaReservedId = deviceId
        registry.values.firstOrNull { it.info.id == deviceId }?.let { removeDevice(it.info) }
        pendingMessages.clear()
        if (old != null && old != deviceId && !closed) {
            midiManager.devices.firstOrNull { it.id == old }?.let { connectToDevice(it) }
        }
    }

    // --- LOOPBACK FILTERING ---
    private data class SentMessage(val data: ByteArray, val timestamp: Long) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SentMessage) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    private val recentSentMessages = mutableListOf<SentMessage>()
    private val MAX_SENT_CACHE = 512
    private val LOOPBACK_THRESHOLD_MS = 200L

    private val _midiLog = mutableStateOf("MIDI: Init...")
    private val _deviceName = mutableStateOf("Searching...")
    private val _devices = mutableStateOf<List<MidiDeviceInfo>>(emptyList())
    private val _deviceActivity = mutableStateOf<Map<String, Long>>(emptyMap())

    actual val midiLog: State<String> = _midiLog
    actual val deviceName: State<String> = _deviceName
    actual val devices: State<List<MidiDeviceInfo>> = _devices
    actual val deviceActivity: State<Map<String, Long>> = _deviceActivity

    private fun logToUi(msg: String) {
        val current = _midiLog.value.split("\n").takeLast(14).toMutableList()
        current.add(msg)
        _midiLog.value = current.joinToString("\n")
    }

    init {
        MidiConfigStore.load()
        connectExistingDevices()
        midiManager.registerDeviceCallback(object : android.media.midi.MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: android.media.midi.MidiDeviceInfo) {
                connectToDevice(device)
            }

            override fun onDeviceRemoved(device: android.media.midi.MidiDeviceInfo) {
                removeDevice(device)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun connectExistingDevices() {
        val all = midiManager.devices
        logToUi("MidiManager Init: Found ${all.size} devices")
        all.forEach { connectToDevice(it) }
    }

    private fun stableId(info: android.media.midi.MidiDeviceInfo): String {
        val props = info.properties
        val name = props.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown"
        val manufacturer = props.getString(android.media.midi.MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""
        val product = props.getString(android.media.midi.MidiDeviceInfo.PROPERTY_PRODUCT) ?: ""
        val serial = props.getString(android.media.midi.MidiDeviceInfo.PROPERTY_SERIAL_NUMBER) ?: ""
        val parts = listOf(manufacturer, product, name, serial).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "android-midi-$name" else parts.joinToString("|")
    }

    private fun deviceDisplayName(info: android.media.midi.MidiDeviceInfo): String =
        info.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "Unknown"

    private fun connectToDevice(info: android.media.midi.MidiDeviceInfo) {
        if (closed || info.id == cafaReservedId) return
        val id = stableId(info)
        synchronized(this) {
            if (registry.containsKey(id)) return
        }
        midiManager.openDevice(info, { device ->
            if (device == null) {
                logToUi("Failed to open: ${deviceDisplayName(info)}")
                return@openDevice
            }
            if (closed || info.id == cafaReservedId) { device.close(); return@openDevice }
            // Ignore a duplicate open that raced with the registry update.
            synchronized(this@MidiManager) {
                if (registry.containsKey(id)) {
                    device.close()
                    return@openDevice
                }
            }
            // The device may have been unplugged while it was opening.
            if (midiManager.devices.none { stableId(it) == id }) {
                device.close()
                return@openDevice
            }

            val incoming = mutableListOf<MidiOutputPort>()
            val outgoing = mutableListOf<MidiInputPort>()

            // Ports that PROVIDE MIDI (device -> app)
            for (i in 0 until info.outputPortCount) {
                val port = try { device.openOutputPort(i) } catch (e: Exception) { null } ?: continue
                incoming.add(port)
                port.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val message = data.copyOfRange(offset, offset + count)
                        handleIncoming(id, message)
                    }
                })
            }

            // Ports that RECEIVE MIDI (app -> device)
            for (i in 0 until info.inputPortCount) {
                val port = try { device.openInputPort(i) } catch (e: Exception) { null } ?: continue
                outgoing.add(port)
            }

            val entry = DeviceEntry(id, info, device, incoming, outgoing)
            synchronized(this@MidiManager) {
                registry[id] = entry
                publishDevices()
            }

            val name = deviceDisplayName(info)
            _deviceName.value = name
            logToUi("Connected: $name")
            Log.i("MidiManager", "Connected $name ($id) in=${incoming.size} out=${outgoing.size}")

            synchronized(this@MidiManager) {
                flushPending(outgoing)
            }
        }, Handler(Looper.getMainLooper()))
    }

    @Synchronized
    private fun removeDevice(info: android.media.midi.MidiDeviceInfo) {
        val id = stableId(info)
        val entry = registry.remove(id) ?: return
        runCatching { entry.incoming.forEach { it.close() } }
        runCatching { entry.outgoing.forEach { it.close() } }
        runCatching { entry.device.close() }
        _deviceActivity.value = _deviceActivity.value - id
        publishDevices()
        logToUi("Disconnected: ${deviceDisplayName(info)}")
    }

    private fun publishDevices() {
        _devices.value = registry.values.map { entry ->
            MidiDeviceInfo(
                id = entry.id,
                name = deviceDisplayName(entry.info),
                vendor = entry.info.properties
                    .getString(android.media.midi.MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: "",
                isInput = entry.incoming.isNotEmpty(),
                isOutput = entry.outgoing.isNotEmpty()
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun markActivity(deviceId: String) {
        val now = System.currentTimeMillis()
        val previous = _deviceActivity.value[deviceId] ?: 0L
        if (now - previous > 400) {
            _deviceActivity.value = _deviceActivity.value + (deviceId to now)
        }
    }

    private fun handleIncoming(deviceId: String, message: ByteArray) {
        if (message.isEmpty()) return
        val config = MidiConfigStore.configFor(deviceId)
        if (!config.enabled || config.ignoreIncoming) return
        if (config.receiveChannel in 1..16 &&
            !MidiFilters.matchesChannel(message, config.receiveChannel)) return

        // Loopback filter: ignore echoes of our own LED/clock output.
        val now = System.currentTimeMillis()
        synchronized(recentSentMessages) {
            val iterator = recentSentMessages.iterator()
            while (iterator.hasNext()) {
                val sent = iterator.next()
                if (now - sent.timestamp > LOOPBACK_THRESHOLD_MS * 2) {
                    iterator.remove()
                    continue
                }
                if (sent.data.contentEquals(message) && (now - sent.timestamp) < LOOPBACK_THRESHOLD_MS) {
                    return
                }
            }
        }

        markActivity(deviceId)
        val hex = message.joinToString(" ") { String.format("%02X", it) }
        logToUi("IN: $hex")
        onMessageReceived(message, deviceId)
    }

    private fun flushPending(outgoing: List<MidiInputPort>) {
        if (outgoing.isEmpty() || pendingMessages.isEmpty()) return
        logToUi("Flushing ${pendingMessages.size} msgs...")
        pendingMessages.forEach { msg ->
            outgoing.forEach { port ->
                runCatching { port.send(msg, 0, msg.size) }
            }
        }
        pendingMessages.clear()
    }

    private fun sendToDevice(deviceId: String, message: ByteArray) {
        val config = MidiConfigStore.configFor(deviceId)
        if (!config.enabled || config.muteOutgoing || config.sendChannel == 0) return
        val payload = if (config.sendChannel in 1..16)
            MidiFilters.remapChannel(message, config.sendChannel) else message

        val ports = registry[deviceId]?.outgoing ?: return
        ports.forEach { port ->
            runCatching { port.send(payload, 0, payload.size) }
                .onFailure { logToUi("Send Error: ${it.message}") }
        }
        markActivity(deviceId)
    }

    @Synchronized
    actual fun sendMidi(data: ByteArray) {
        // Cache before sending so a fast echo cannot beat the cache write.
        synchronized(recentSentMessages) {
            recentSentMessages.add(SentMessage(data.copyOf(), System.currentTimeMillis()))
            if (recentSentMessages.size > MAX_SENT_CACHE) recentSentMessages.removeAt(0)
        }

        if (registry.values.none { it.outgoing.isNotEmpty() }) {
            // Never replay stale notes/clock into a newly connected instrument.
            if (cafaReservedId == null && pendingMessages.size < 128) pendingMessages.add(data.copyOf())
            return
        }
        registry.keys.toList().forEach { sendToDevice(it, data) }
    }

    @Synchronized
    actual fun sendMidiTo(deviceId: String?, data: ByteArray) {
        if (deviceId == null) {
            sendMidi(data)
            return
        }
        synchronized(recentSentMessages) {
            recentSentMessages.add(SentMessage(data.copyOf(), System.currentTimeMillis()))
            if (recentSentMessages.size > MAX_SENT_CACHE) recentSentMessages.removeAt(0)
        }
        sendToDevice(deviceId, data)
    }

    @Synchronized
    actual fun refreshDevices() {
        val present = midiManager.devices.map { stableId(it) }.toSet()
        registry.keys.toList().filter { it !in present }.forEach { stale ->
            registry[stale]?.let { entry ->
                runCatching { entry.incoming.forEach { p -> p.close() } }
                runCatching { entry.outgoing.forEach { p -> p.close() } }
                runCatching { entry.device.close() }
                registry.remove(stale)
                _deviceActivity.value = _deviceActivity.value - stale
            }
        }
        midiManager.devices.forEach { connectToDevice(it) }
        publishDevices()
    }

    @Synchronized
    actual fun close() {
        closed = true
        registry.values.forEach { entry ->
            runCatching { entry.incoming.forEach { it.close() } }
            runCatching { entry.outgoing.forEach { it.close() } }
            runCatching { entry.device.close() }
        }
        registry.clear()
        _devices.value = emptyList()
        _deviceActivity.value = emptyMap()
    }
}
