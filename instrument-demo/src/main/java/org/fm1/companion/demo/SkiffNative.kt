/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

/** JNI entry points for the native Skiff 2.5.1 instrument. */
object SkiffNative {
    init { System.loadLibrary("skiff-companion") }
    external fun boot(storage: ByteArray?)
    external fun render(out: ShortArray)
    external fun startDiagnosticPattern()
    external fun diagnosticProbeStats(): IntArray
    external fun diagnosticOnsets(): LongArray?
    external fun masterGain(q12: Int)
    external fun input(buttons: Int, keys: Int, turns: IntArray)
    external fun inputSerial(buttons: Int, keys: Int, turns: IntArray, serial: Int): Boolean
    external fun snapshot(): ByteArray
    external fun editContext(): Int
    external fun midiIn(packet: Int, source: Int): Boolean
    external fun midiOut(): IntArray
    external fun midiOutTimed(): LongArray?
    external fun exitDeviceMenu()
    external fun stop()
    external fun storage(lastRevision: Int): ByteArray?
    external fun storageRevision(): Int
    external fun encodeSample(pcm: ShortArray, name: String, root: Int): ByteArray?
    external fun storeSample(slot: Int, encoded: ByteArray): Int
    external fun startSampleCapture(): Boolean
    external fun finishSampleCapture(): ShortArray?
    external fun cancelSampleCapture()
    external fun setDxText(text: String): Boolean
}
