/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.legacy

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

/** USB MIDI transport for the legacy v5 surface. No audio route or application model is owned here.
 * Call connect/close on the main thread; surface and listener callbacks run on that thread.
 */
class LegacyMidiConnection(context: Context, private val surface: LegacySurface,
                           private val listener: Listener) {
    interface Listener {
        /** Optional application readiness check. False defers this tick; close to end the session. */
        fun beforeTick(now: Long): Boolean = true
        fun opened()
        fun applied()
        fun stopped(reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val midi = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private var device: MidiDevice? = null
    private var input: MidiInputPort? = null
    private var output: MidiOutputPort? = null
    private var session: CafaSession? = null
    private var generation = 0
    private var midiId = -1

    fun devices(): List<MidiDeviceInfo> = midi?.devices?.filter {
        it.type == MidiDeviceInfo.TYPE_USB && it.inputPortCount > 0 && it.outputPortCount > 0
    } ?: emptyList()

    private val timer = object : Runnable {
        override fun run() {
            val link = session ?: return
            if (devices().none { it.id == midiId }) {
                fail("FM1 USB MIDI disconnected. Reconnect to resynchronize."); return
            }
            try {
                val now = SystemClock.elapsedRealtime()
                if (listener.beforeTick(now) && session === link) link.tick(now)
            } catch (error: Exception) {
                fail("FM1 state failed safely: ${error.javaClass.simpleName}"); return
            }
            if (session === link && link.isOpen) main.postDelayed(this, 34)
        }
    }

    fun connect(info: MidiDeviceInfo) {
        close()
        if (devices().none { it.id == info.id }) {
            fail("Select an available USB MIDI device with input and output ports."); return
        }
        val token = generation
        midiId = info.id
        try { midi?.openDevice(info, { opened ->
            if (token != generation) { runCatching { opened?.close() }; return@openDevice }
            try {
                if (opened == null) { fail("Android could not open selected USB MIDI device."); return@openDevice }
                device = opened
                input = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }?.let { opened.openInputPort(it.portNumber) }
                output = info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }?.let { opened.openOutputPort(it.portNumber) }
                if (input == null || output == null) { fail("Selected USB MIDI ports are unavailable."); return@openDevice }
                val link = CafaSession(object : CafaSession.Host {
                    override fun send(bytes: ByteArray) { val port = input ?: error("MIDI port closed"); port.send(bytes, 0, bytes.size) }
                    override fun snapshot() = surface.snapshot()
                    override fun input(serial: Int, payload: ByteArray) = surface.input(serial, payload)
                    override fun reset() { surface.reset() }
                    override fun applied() { listener.applied() }
                    override fun stopped(reason: String) { fail(reason) }
                })
                session = link
                val decoder = Protocol.Decoder { frame -> main.post {
                    if (token == generation) try { session?.receive(frame, SystemClock.elapsedRealtime()) }
                    catch (error: Exception) { fail("FM1 input failed safely: ${error.javaClass.simpleName}") }
                } }
                output?.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        synchronized(decoder) { decoder.feed(data, offset, count) }
                    }
                    override fun onFlush() { synchronized(decoder) { decoder.reset() } }
                })
                listener.opened()
                if (token != generation) return@openDevice
                link.begin(1 + SecureRandom().nextInt(16383), SystemClock.elapsedRealtime())
                if (session === link && link.isOpen) main.postDelayed(timer, 34)
            } catch (error: Exception) { fail("USB MIDI setup failed safely: ${error.javaClass.simpleName}") }
        }, main) } catch (error: Exception) { fail("USB MIDI open failed: ${error.javaClass.simpleName}") }
    }

    fun close() {
        generation++; main.removeCallbacks(timer)
        val old = session; session = null
        try { old?.close() } finally {
            runCatching { output?.close() }; runCatching { input?.close() }; runCatching { device?.close() }
            output = null; input = null; device = null; midiId = -1
        }
    }

    private fun fail(reason: String) {
        try { close() } finally { listener.stopped(reason) }
    }
}
