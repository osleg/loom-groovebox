/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import org.fm1.companion.protocol.OpenMidiConnection
import org.fm1.companion.protocol.Wire6
import android.os.SystemClock

/** Applies device-owned scans in order and only against the display context the player saw. */
class SkiffSurface(private val lock: Any, private val native: SkiffPort) : OpenMidiConnection.Surface {
    var diagnosticReceipt: ((Int, Long) -> Unit)? = null
    private var firstScan = true
    private var inDeviceMenu = false
    private var heldButtons = 0
    private var heldKeys = 0
    private var forwardedButtons = 0
    private var forwardedKeys = 0
    private var suppressedButtons = 0
    private var suppressedKeys = 0
    override fun snapshot(): ByteArray = synchronized(lock) { native.snapshot() }

    override fun input(serial: Int, scan: ByteArray): Boolean = synchronized(lock) {
        if (scan.size != 26) return@synchronized false
        native.masterGain(Wire6.u14(scan, 24))
        val deviceMenu = (scan[5].toInt() and 64) != 0
        if (inDeviceMenu && !deviceMenu) native.exitDeviceMenu()
        inDeviceMenu = deviceMenu
        val buttons = Wire6.u14(scan, 0)
        val keys = Wire6.u28(scan, 2) and (1 shl 27).inv()
        val turns = IntArray(7) { Wire6.u14(scan, 6 + it * 2) - 8192 }
        val drawnContext = Wire6.u28(scan, 20)
        val fresh = !firstScan && !deviceMenu && native.editContext() == drawnContext
        if (deviceMenu) {
            suppressedButtons = suppressedButtons or buttons
            suppressedKeys = suppressedKeys or keys
            forwardedButtons = 0
            forwardedKeys = 0
            native.input(0, 0, IntArray(7))
        } else if (!fresh) {
            // A stale page can release an old hold, but cannot press a new control or turn a knob.
            suppressedButtons = (suppressedButtons and buttons) or (buttons and heldButtons.inv())
            suppressedKeys = (suppressedKeys and keys) or (keys and heldKeys.inv())
            forwardedButtons = forwardedButtons and buttons
            forwardedKeys = forwardedKeys and keys
            native.input(forwardedButtons, forwardedKeys, IntArray(7))
        } else {
            suppressedButtons = suppressedButtons and buttons
            suppressedKeys = suppressedKeys and keys
            forwardedButtons = buttons and suppressedButtons.inv()
            val previousKeys = forwardedKeys
            forwardedKeys = keys and suppressedKeys.inv()
            val keyDown = (forwardedKeys and 1) != 0 && (previousKeys and 1) == 0
            val receipt = if (keyDown && serial in 0..16383 && diagnosticReceipt != null)
                SystemClock.elapsedRealtimeNanos() else 0L
            val accepted = if (diagnosticReceipt != null)
                native.inputSerial(forwardedButtons, forwardedKeys, turns, serial)
            else { native.input(forwardedButtons, forwardedKeys, turns); false }
            if (accepted && receipt != 0L) diagnosticReceipt?.invoke(serial, receipt)
        }
        heldButtons = buttons
        heldKeys = keys
        firstScan = false
        true
    }

    override fun reset() = synchronized(lock) {
        native.masterGain(-1)
        native.input(0, 0, IntArray(7))
        firstScan = true
        inDeviceMenu = false
        heldButtons = 0
        heldKeys = 0
        forwardedButtons = 0
        forwardedKeys = 0
        suppressedButtons = 0
        suppressedKeys = 0
    }
}

/** Shared by audio, MIDI and storage so every native operation uses the same lock. */
interface SkiffPort {
    fun boot(storage: ByteArray?)
    fun render(out: ShortArray)
    fun masterGain(q12: Int)
    fun input(buttons: Int, keys: Int, turns: IntArray)
    fun inputSerial(buttons: Int, keys: Int, turns: IntArray, serial: Int): Boolean {
        input(buttons, keys, turns)
        return true
    }
    fun snapshot(): ByteArray
    fun editContext(): Int
    fun midiIn(packet: Int, source: Int): Boolean
    fun midiOut(): IntArray
    fun midiOutTimed(): LongArray
    fun startDiagnosticPattern() {}
    fun diagnosticOnsets(): LongArray = longArrayOf()
    fun exitDeviceMenu()
    fun stop()
    fun storage(lastRevision: Int): ByteArray?
    fun storageRevision(): Int
    fun storeSample(slot: Int, encoded: ByteArray): Int = 3
    fun startSampleCapture(): Boolean = false
    fun finishSampleCapture(): ShortArray? = null
    fun cancelSampleCapture() {}
    fun setDxText(text: String): Boolean = false
}

object NativeSkiffPort : SkiffPort {
    private val emptyTimedMidi = longArrayOf()
    override fun boot(storage: ByteArray?) = SkiffNative.boot(storage)
    override fun render(out: ShortArray) = SkiffNative.render(out)
    override fun masterGain(q12: Int) = SkiffNative.masterGain(q12)
    override fun input(buttons: Int, keys: Int, turns: IntArray) = SkiffNative.input(buttons, keys, turns)
    override fun inputSerial(buttons: Int, keys: Int, turns: IntArray, serial: Int) =
        SkiffNative.inputSerial(buttons, keys, turns, serial)
    override fun snapshot() = SkiffNative.snapshot()
    override fun editContext() = SkiffNative.editContext()
    override fun midiIn(packet: Int, source: Int) = SkiffNative.midiIn(packet, source)
    override fun midiOut() = SkiffNative.midiOut()
    override fun midiOutTimed() = SkiffNative.midiOutTimed() ?: emptyTimedMidi
    override fun startDiagnosticPattern() = SkiffNative.startDiagnosticPattern()
    override fun diagnosticOnsets() = SkiffNative.diagnosticOnsets() ?: emptyTimedMidi
    override fun exitDeviceMenu() = SkiffNative.exitDeviceMenu()
    override fun stop() = SkiffNative.stop()
    override fun storage(lastRevision: Int) = SkiffNative.storage(lastRevision)
    override fun storageRevision() = SkiffNative.storageRevision()
    override fun storeSample(slot: Int, encoded: ByteArray) = SkiffNative.storeSample(slot, encoded)
    override fun startSampleCapture() = SkiffNative.startSampleCapture()
    override fun finishSampleCapture() = SkiffNative.finishSampleCapture()
    override fun cancelSampleCapture() = SkiffNative.cancelSampleCapture()
    override fun setDxText(text: String) = SkiffNative.setDxText(text)
}
