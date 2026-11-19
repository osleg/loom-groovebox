/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.midi.MidiDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Sends Skiff PCM through FM-1's isolated vendor interface. The worker alone owns its USB connection. */
class DirectUsbOutput(private val manager: UsbManager, private val device: UsbDevice,
                      private val runtime: SkiffRuntime, private val onReady: () -> Unit,
                      private val onFailure: (String) -> Unit,
                      private val onMidiOut: ((LongArray, Long) -> Unit)?) : InstrumentOutput {
    private val main = Handler(Looper.getMainLooper())
    private val stateLock = Object()
    @Volatile private var running = true
    @Volatile private var verified = false
    private var audible = false
    private var explicitlyStopped = false
    private val worker: Thread

    init {
        require(deviceForDescriptor(manager, device) != null && manager.hasPermission(device)) {
            "FM-1 direct USB output is unavailable or permission was denied"
        }
        worker = Thread({ pump() }, "FM1-direct-USB").also { it.start() }
    }

    // The claimed file descriptor is bound to one physical device and cannot be rerouted.
    // The service observes USB detach; a failed bulk write also stops playback.
    override fun routeMatches(): Boolean = running && verified

    override fun enable() {
        synchronized(stateLock) {
            check(routeMatches()) { "FM-1 direct USB output changed" }
            audible = true
            stateLock.notifyAll()
        }
    }

    override fun queueDescription(): String {
        check(routeMatches()) { "FM-1 direct USB output changed" }
        return "direct USB, 128-frame writes, 384-frame maximum device queue (estimated)"
    }

    override fun stop() {
        synchronized(stateLock) {
            explicitlyStopped = true
            running = false
            audible = false
            stateLock.notifyAll()
        }
        worker.interrupt()
        if (Thread.currentThread() !== worker) {
            try { worker.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun pump() {
        var connection: UsbDeviceConnection? = null
        var alt0: UsbInterface? = null
        var claimed = false
        try {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            catch (_: SecurityException) { /* Best effort on app threads. */ }

            val route = requireNotNull(deviceForDescriptor(manager, device)) { "FM-1 USB output changed" }
            alt0 = route.first
            val endpoint = route.second.getEndpoint(0)
            val opened = manager.openDevice(device) ?: error("Could not open FM-1 USB output")
            connection = opened
            check(opened.claimInterface(route.first, false)) { "Could not claim FM-1 direct output" }
            claimed = true
            check(opened.setInterface(route.second)) { "Could not select FM-1 direct output" }

            val silence = ByteArray(BLOCK_BYTES)
            // The first complete silent block proves the selected endpoint accepts output.
            writeBlock(opened, endpoint, silence)
            synchronized(stateLock) { if (running) verified = true }
            main.post {
                if (routeMatches()) {
                    if (deviceForDescriptor(manager, device) != null && manager.hasPermission(device)) onReady()
                    else onFailure("FM-1 was unplugged before direct playback started")
                }
            }

            val pcm = ShortArray(BLOCK_FRAMES * 2)
            val bytes = ByteArray(BLOCK_BYTES)
            while (running) {
                synchronized(stateLock) {
                    while (running && !audible) {
                        stateLock.wait(100)
                    }
                    if (!running) return
                }
                val packets = runtime.render(pcm)
                if (!running) break
                val sendMidi = onMidiOut
                if (sendMidi != null && packets.isNotEmpty()) {
                    // The firmware queue is capped at 384 frames. This is an estimate, not a DAC timestamp.
                    sendMidi(packets, System.nanoTime() + MAX_QUEUE_FRAMES * 1_000_000_000L / SAMPLE_RATE)
                }
                for (i in pcm.indices) {
                    val sample = pcm[i].toInt()
                    bytes[i * 2] = sample.toByte()
                    bytes[i * 2 + 1] = (sample ushr 8).toByte()
                }
                writeBlock(opened, endpoint, bytes)
            }
        } catch (error: Throwable) {
            val report = synchronized(stateLock) {
                val report = running && !explicitlyStopped
                running = false
                audible = false
                stateLock.notifyAll()
                report
            }
            if (report) main.post {
                if (!explicitlyStopped) onFailure(error.message ?: "FM-1 direct USB output failed")
            }
        } finally {
            // Keep all interface changes and connection disposal on the same worker.
            if (connection != null) {
                if (claimed && alt0 != null) {
                    runCatching { connection.setInterface(alt0) }
                    runCatching { connection.releaseInterface(alt0) }
                }
                runCatching { connection.close() }
            }
            synchronized(stateLock) {
                running = false
                verified = false
                audible = false
                stateLock.notifyAll()
            }
        }
    }

    private fun writeBlock(connection: UsbDeviceConnection, endpoint: UsbEndpoint, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (!running) return
            // Android splits the block into endpoint packets; continue only for reported progress.
            val length = bytes.size - offset
            check(length > 0 && length % 4 == 0) { "FM-1 USB packet is not frame aligned" }
            val sent = connection.bulkTransfer(endpoint, bytes, offset, length, WRITE_TIMEOUT_MS)
            check(sent > 0 && sent <= length && sent % 4 == 0) {
                "FM-1 direct USB write failed or returned an incomplete stereo frame ($sent bytes)"
            }
            offset += sent
        }
    }

    companion object {
        private const val VID = 0x1209
        private const val PID = 0x0001
        private const val SUBCLASS = 0x46
        private const val PROTOCOL = 1
        private const val BLOCK_FRAMES = 128
        private const val BLOCK_BYTES = BLOCK_FRAMES * 4
        private const val MAX_QUEUE_FRAMES = 384L
        private const val SAMPLE_RATE = 44100L
        private const val WRITE_TIMEOUT_MS = 50

        /** A permission-free identity and descriptor check; discovery never opens the device. */
        fun deviceFor(manager: UsbManager, midi: MidiDeviceInfo): UsbDevice? {
            val bound = midi.properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
                ?: return null
            return manager.deviceList.values.singleOrNull {
                it.deviceName == bound.deviceName && it.deviceId == bound.deviceId &&
                    deviceForDescriptor(manager, it) != null
            }
        }

        private fun deviceForDescriptor(manager: UsbManager, device: UsbDevice): Pair<UsbInterface, UsbInterface>? {
            if (device.vendorId != VID || device.productId != PID ||
                manager.deviceList.values.none { it.deviceName == device.deviceName && it.deviceId == device.deviceId })
                return null
            val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }.filter {
                it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    it.interfaceSubclass == SUBCLASS && it.interfaceProtocol == PROTOCOL
            }
            val zero = candidates.filter { it.alternateSetting == 0 && it.endpointCount == 0 }
            val one = candidates.filter {
                it.alternateSetting == 1 && it.endpointCount == 1 &&
                    it.getEndpoint(0).address == 0x03 &&
                    it.getEndpoint(0).type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.getEndpoint(0).direction == UsbConstants.USB_DIR_OUT &&
                    it.getEndpoint(0).maxPacketSize == 64
            }
            return if (zero.size == 1 && one.size == 1 && zero[0].id == one[0].id &&
                candidates.size == 2) zero[0] to one[0] else null
        }
    }
}
