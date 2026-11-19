/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import org.fm1.companion.protocol.UsbMidiPackets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit optional USB MIDI ingress and egress; audio never waits on a MIDI port. */
class ExternalMidiRoute(context: Context, private val runtime: SkiffRuntime,
                        private val onFailure: (String) -> Unit) {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private val main = Handler(Looper.getMainLooper())
    private var inputDevice: MidiDevice? = null
    private var outputDevice: MidiDevice? = null
    private var inputPort: MidiOutputPort? = null
    @Volatile private var outputPort: MidiInputPort? = null
    private data class Outgoing(val packet: Int, val timestamp: Long)
    private val outbound = ArrayBlockingQueue<Outgoing>(1024)
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "Skiff-MIDI-out") }
    private val inputWorker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "Skiff-MIDI-in") }
    private val pendingInput = AtomicInteger()
    private var inputTimestamp = 0L
    @Volatile private var closed = false
    @Volatile private var inputClosed = false
    private var outputSelected = false
    private var finalPackets = intArrayOf()
    private val parser = UsbMidiPackets { packet ->
        if (pendingInput.incrementAndGet() > 1024) {
            pendingInput.decrementAndGet()
            fail("External MIDI input overflow")
        } else {
            val delay = if (inputTimestamp <= 0) 0L else
                (inputTimestamp - System.nanoTime()).coerceAtLeast(0L)
            inputWorker.schedule({
                try { if (!inputClosed && !runtime.midiIn(packet, 1)) fail("External MIDI input overflow") }
                finally { pendingInput.decrementAndGet() }
            }, delay, TimeUnit.NANOSECONDS)
        }
    }

    fun devices(fm1Id: Int, input: Boolean): List<MidiDeviceInfo> = manager.devices.filter {
        it.type == MidiDeviceInfo.TYPE_USB && it.id != fm1Id &&
            (if (input) it.outputPortCount > 0 else it.inputPortCount > 0)
    }

    fun open(inputId: Int, outputId: Int, fm1Id: Int) {
        if (inputId >= 0) {
            val device = devices(fm1Id, true).firstOrNull { it.id == inputId }
                ?: error("External MIDI input changed")
            manager.openDevice(device, { opened ->
                if (closed) { opened?.close(); return@openDevice }
                if (opened == null) { fail("External MIDI input unavailable"); return@openDevice }
                inputDevice = opened
                inputPort = device.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                    ?.let { opened.openOutputPort(it.portNumber) }
                val port = inputPort
                if (port == null) { fail("External MIDI input port unavailable"); return@openDevice }
                port.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        synchronized(parser) {
                            if (!inputClosed) { inputTimestamp = timestamp; parser.feed(data, offset, count) }
                        }
                    }
                })
            }, main)
        }
        outputSelected = outputId >= 0
        if (outputId >= 0) {
            val device = devices(fm1Id, false).firstOrNull { it.id == outputId }
                ?: error("External MIDI output changed")
            manager.openDevice(device, { opened ->
                if (closed) { opened?.close(); return@openDevice }
                if (opened == null) { fail("External MIDI output unavailable"); return@openDevice }
                outputDevice = opened
                outputPort = device.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                    ?.let { opened.openInputPort(it.portNumber) }
                if (outputPort == null) { fail("External MIDI output port unavailable"); return@openDevice }
                writer.execute { drain() }
            }, main)
        }
    }

    fun queueTimed(packets: LongArray, blockTime: Long) {
        if (closed || !outputSelected) return
        for (event in packets) {
            val timestamp = blockTime + (event shr 32) * 1_000_000_000L / 44100
            if (!outbound.offer(Outgoing(event.toInt(), timestamp))) {
                fail("External MIDI output overflow"); return
            }
        }
    }

    fun finishWith(packets: IntArray) {
        finalPackets = packets
    }

    private fun drain() {
        try {
            while (!closed) {
                val packet = outbound.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                val bytes = UsbMidiPackets.bytes(packet.packet)
                outputPort?.send(bytes, 0, bytes.size, packet.timestamp)
            }
            outputPort?.flush()
            for (packet in finalPackets) {
                val bytes = UsbMidiPackets.bytes(packet)
                outputPort?.send(bytes, 0, bytes.size)
            }
            // Stop all voices at the selected destination before releasing the port.
            for (channel in 0..15) for (control in intArrayOf(123, 120)) {
                val bytes = byteArrayOf((0xb0 or channel).toByte(), control.toByte(), 0)
                outputPort?.send(bytes, 0, bytes.size)
            }
        } catch (error: Exception) { fail("External MIDI output failed") }
    }

    private fun fail(reason: String) { main.post { if (!closed) onFailure(reason) } }

    fun stopInput() {
        synchronized(parser) {
            inputClosed = true
            inputWorker.shutdownNow()
        }
        inputWorker.awaitTermination(1, TimeUnit.SECONDS)
        runCatching { inputPort?.close() }; inputPort = null
        runCatching { inputDevice?.close() }; inputDevice = null
    }

    fun close() {
        stopInput()
        closed = true
        writer.shutdown()
        writer.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { outputPort?.close() }; outputPort = null
        runCatching { outputDevice?.close() }; outputDevice = null
    }
}
