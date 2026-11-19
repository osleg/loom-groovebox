/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.protocol

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit wire-6 USB MIDI connection. Call connect/close on the main thread; callbacks use it too. */
class OpenMidiConnection(context: Context, private val surface: Surface, private val listener: Listener) {
    interface Surface {
        fun snapshot(): ByteArray
        fun input(serial: Int, scan: ByteArray): Boolean
        fun reset()
        fun capabilitiesAccepted(accepted: Set<Int>) {}
        fun acknowledged(context: Int) {}
    }
    interface Listener {
        /** Return false when the chosen audio route is no longer available. */
        fun beforeTick(now: Long): Boolean = true
        /** Negotiated TRS packets run on Android's MIDI receiver thread. */
        fun midiPacket(packet: Int, timestamp: Long) {}
        fun opened()
        /** The application verifies its chosen output route before enabling audio. */
        fun synchronizedDisplay(audioMode: Int, transportAccepted: Boolean)
        fun stopped(reason: String)
        fun diagnostics(op: Int, values: IntArray) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val midi = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private var device: MidiDevice? = null
    private var input: MidiInputPort? = null
    private var output: MidiOutputPort? = null
    private var session: OpenSession? = null
    private val panelLock = Any()
    private var activePanelToken: Int? = null
    private var generation = 0
    private var midiId = -1
    private var nativeTickQueued = false

    private fun promptNativeTick(token: Int) {
        if (nativeTickQueued) return
        nativeTickQueued = true
        main.post {
            nativeTickQueued = false
            if (token != generation || session?.isOpen != true) return@post
            main.removeCallbacks(timer)
            timer.run() // Includes the normal route check and diagnostic ordering.
        }
    }

    fun devices(): List<MidiDeviceInfo> = midi?.devices?.filter {
        it.type == MidiDeviceInfo.TYPE_USB && it.inputPortCount > 0 && it.outputPortCount > 0
    } ?: emptyList()

    private val timer = object : Runnable {
        override fun run() {
            val link = session ?: return
            if (devices().none { it.id == midiId }) {
                fail("FM1 USB MIDI disconnected. Reconnect required."); return
            }
            try {
                val now = SystemClock.elapsedRealtime()
                if (!listener.beforeTick(now)) { fail("Selected audio route is unavailable."); return }
                if (session === link) {
                    link.tick(now)
                    if (session === link && link.isOpen) link.flushDiagnostics(now)
                }
            } catch (error: Exception) {
                fail("FM1 session failed safely: ${error.javaClass.simpleName}"); return
            }
            if (session === link && link.isOpen) main.postDelayed(this, 34)
        }
    }

    /** Audio route is explicit; no automatic fallback or legacy protocol switch. */
    fun connect(info: MidiDeviceInfo, namespace: String, audioMode: Int,
                vararg capabilities: Wire6.Capability) {
        check(Looper.myLooper() == Looper.getMainLooper())
        // Validate the application contract before tearing down a previous connection.
        Wire6.hello(namespace, audioMode, *capabilities)
        close()
        if (devices().none { it.id == info.id }) {
            fail("Select an available USB MIDI device with input and output ports."); return
        }
        val token = generation
        midiId = info.id
        try {
            midi?.openDevice(info, { opened ->
                if (token != generation) { runCatching { opened?.close() }; return@openDevice }
                try {
                    if (opened == null) { fail("Android could not open selected USB MIDI device."); return@openDevice }
                    device = opened
                    input = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                        ?.let { opened.openInputPort(it.portNumber) }
                    output = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                        ?.let { opened.openOutputPort(it.portNumber) }
                    if (input == null || output == null) { fail("Selected USB MIDI ports are unavailable."); return@openDevice }
                    var panelMidi = false
                    val link = OpenSession(object : OpenSession.Host {
                        override fun send(frame: ByteArray) {
                            val port = input ?: error("MIDI port closed")
                            port.send(frame, 0, frame.size)
                        }
                        override fun snapshot() = surface.snapshot()
                        override fun input(serial: Int, scan: ByteArray) = surface.input(serial, scan)
                        override fun reset() = surface.reset()
                        override fun capabilitiesAccepted(accepted: Set<Int>) {
                            panelMidi = Wire6.PANEL_MIDI in accepted
                            surface.capabilitiesAccepted(accepted)
                        }
                        override fun synchronizedDisplay(audioMode: Int, transportAccepted: Boolean) {
                            listener.synchronizedDisplay(audioMode, transportAccepted)
                            synchronized(panelLock) {
                                if (token == generation && panelMidi) activePanelToken = token
                            }
                        }
                        override fun displayAcknowledged(context: Int) = surface.acknowledged(context)
                        override fun nativeKnobApplied() = promptNativeTick(token)
                        override fun nativeViewAcknowledged() = promptNativeTick(token)
                        override fun diagnostics(op: Int, values: IntArray) = listener.diagnostics(op, values)
                        override fun stopped(reason: String) = fail(reason)
                    }, namespace, audioMode, *capabilities)
                    session = link
                    // The decoder may run on Android's MIDI thread. Bound complete frames before
                    // posting so a busy main thread cannot accumulate an unbounded callback backlog.
                    // These counters belong to this connection, so stale callbacks cannot affect a new one.
                    val queuedCallbacks = AtomicInteger(0)
                    val overflowQueued = AtomicBoolean(false)
                    val decoder = Wire6.Decoder { frame ->
                        if (!overflowQueued.get()) {
                            if (queuedCallbacks.incrementAndGet() > 32) {
                                queuedCallbacks.decrementAndGet()
                                if (overflowQueued.compareAndSet(false, true)) main.post {
                                    if (token == generation && session === link)
                                        fail("FM1 input queue overflow; reconnect required.")
                                }
                            } else {
                                val posted = main.post {
                                    try {
                                        if (!overflowQueued.get() && token == generation && session === link)
                                            link.receive(frame, SystemClock.elapsedRealtime())
                                    } catch (error: Exception) {
                                        if (token == generation && session === link)
                                            fail("FM1 input failed safely: ${error.javaClass.simpleName}")
                                    } finally {
                                        queuedCallbacks.decrementAndGet()
                                    }
                                }
                                if (!posted) queuedCallbacks.decrementAndGet()
                            }
                        }
                    }
                    var receivedAt = 0L
                    val musical = UsbMidiPackets { packet -> listener.midiPacket(packet, receivedAt) }
                    output?.connect(object : MidiReceiver() {
                        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            synchronized(decoder) {
                                decoder.feed(data, offset, count)
                                synchronized(panelLock) {
                                    if (activePanelToken == token) {
                                        receivedAt = timestamp
                                        musical.feed(data, offset, count)
                                    }
                                }
                            }
                        }
                        override fun onFlush() { synchronized(decoder) { decoder.reset(); musical.reset() } }
                    })
                    listener.opened()
                    if (token != generation) return@openDevice
                    link.begin(1 + SecureRandom().nextInt(16383), SystemClock.elapsedRealtime())
                    if (session === link && link.isOpen) main.postDelayed(timer, 34)
                } catch (error: Exception) {
                    fail("USB MIDI setup failed safely: ${error.javaClass.simpleName}")
                }
            }, main)
        } catch (error: Exception) { fail("USB MIDI open failed: ${error.javaClass.simpleName}") }
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(panelLock) { activePanelToken = null; generation++ }
        nativeTickQueued = false
        main.removeCallbacks(timer)
        val old = session; session = null
        try { old?.close() } finally {
            runCatching { output?.close() }; runCatching { input?.close() }; runCatching { device?.close() }
            output = null; input = null; device = null; midiId = -1
        }
    }
    /** Returns false unless the dedicated key-timing native-panel session is active and ready. */
    fun requestDiagnostics(op: Int): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return session?.requestDiagnostics(op, SystemClock.elapsedRealtime()) ?: false
    }
    private fun fail(reason: String) {
        try { close() } finally { listener.stopped(reason) }
    }
}
