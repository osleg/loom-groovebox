/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import android.util.AtomicFile
import android.os.SystemClock
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors

/** Serializes native access and writes complete emulated NOR images in revision order. */
class SkiffRuntime(private val store: NorStore, private val onFailure: (String) -> Unit,
                   private val native: SkiffPort = NativeSkiffPort) {
    constructor(file: File, onFailure: (String) -> Unit) : this(AtomicNorStore(file), onFailure)

    val lock = Any()
    val surface = SkiffSurface(lock, native)
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "Skiff-NOR") }
    @Volatile private var savedRevision = -1
    @Volatile private var queued = false
    private var started = false
    private var stopping = false
    private var diagnostic = false
    private class PhoneReceipts {
        val receiptNanos = LongArray(16384)
        val onsetNanos = LongArray(16384)
        val onsetFrames = IntArray(16384)
    }
    private var phoneReceipts: PhoneReceipts? = null


    fun boot() {
        val image = store.read()
        synchronized(lock) {
            native.boot(image)
            started = true
            stopping = false
            savedRevision = native.storageRevision()
        }
    }

    fun render(out: ShortArray): LongArray = synchronized(lock) {
        if (started && !stopping) {
            native.render(out)
            if (diagnostic) {
                val receipts = requireNotNull(phoneReceipts)
                val onsets = native.diagnosticOnsets()
                if (onsets.isNotEmpty()) {
                    val renderedAt = SystemClock.elapsedRealtimeNanos()
                    for (onset in onsets) {
                        val serial = (onset ushr 32).toInt()
                        if (serial in receipts.receiptNanos.indices && receipts.receiptNanos[serial] != 0L) {
                            receipts.onsetNanos[serial] = renderedAt
                            receipts.onsetFrames[serial] = onset.toInt()
                        }
                    }
                }
            }
            native.midiOutTimed()
        }
        else { out.fill(0); longArrayOf() }
    }

    /** Called once after a memory-only diagnostic NOR has booted. */
    fun startDiagnosticPattern() = synchronized(lock) {
        check(started && !stopping) { "Skiff is not running" }
        native.startDiagnosticPattern()
        val receipts = PhoneReceipts()
        phoneReceipts = receipts
        diagnostic = true
        surface.diagnosticReceipt = { serial, at ->
            receipts.receiptNanos[serial] = at
            receipts.onsetNanos[serial] = 0L
            receipts.onsetFrames[serial] = 0
        }
    }

    fun phoneTiming(serial: Int): PhoneTiming? = synchronized(lock) {
        val receipts = phoneReceipts
        if (!diagnostic || receipts == null || serial !in receipts.receiptNanos.indices ||
            receipts.receiptNanos[serial] == 0L) null
        else PhoneTiming(serial, if (receipts.onsetNanos[serial] == 0L) null else
            (receipts.onsetNanos[serial] - receipts.receiptNanos[serial]) / 1000L,
            if (receipts.onsetNanos[serial] == 0L) null else receipts.onsetFrames[serial]).also {
            receipts.receiptNanos[serial] = 0L
        }
    }

    fun diagnosticProbeStats(): ProbeStats = synchronized(lock) {
        if (!started) ProbeStats(0, 0, 0, 0)
        else SkiffNative.diagnosticProbeStats().let {
            ProbeStats(it[0].toLong(), it[1].toLong(), it[2].toLong(), it[3])
        }
    }

    fun midiIn(packet: Int, source: Int): Boolean = synchronized(lock) {
        started && !stopping && native.midiIn(packet, source)
    }

    fun startSampleCapture(): Boolean = synchronized(lock) {
        started && !stopping && !diagnostic && native.startSampleCapture()
    }

    fun setDxText(text: String): Boolean = synchronized(lock) {
        started && !stopping && !diagnostic && native.setDxText(text)
    }

    fun finishSampleCapture(): ShortArray? = synchronized(lock) {
        if (started && !stopping) native.finishSampleCapture() else null
    }

    /** Queue the slot write with the NOR checkpoints and acknowledge after its revision is on disk. */
    fun storeSample(slot: Int, encoded: ByteArray, done: (Result<Int>) -> Unit) {
        writer.execute {
            val result = runCatching {
                val code = synchronized(lock) {
                    if (started && !stopping && !diagnostic) native.storeSample(slot, encoded)
                    else 3
                }
                if (code == 0) saveCurrent()
                code
            }
            done(result)
        }
    }

    fun retire() = synchronized(lock) {
        stopping = true
        if (started) native.cancelSampleCapture()
    }

    /** Cheap revision check on the MIDI timer; all file access stays on the writer. */
    fun checkpoint() {
        val changed = synchronized(lock) { started && native.storageRevision() != savedRevision }
        if (changed && !queued) {
            queued = true
            writer.execute {
                try { saveUntilCurrent() }
                catch (error: Throwable) { onFailure("Skiff storage failed: ${error.message ?: error.javaClass.simpleName}") }
                finally { queued = false }
            }
        }
    }

    private fun saveUntilCurrent() {
        while (saveCurrent()) {
            // A change can arrive while the preceding image is being written.
        }
    }

    private fun saveCurrent(): Boolean {
        val capture = synchronized(lock) {
            if (!started || native.storageRevision() == savedRevision) return false
            val bytes = checkNotNull(native.storage(savedRevision)) { "Skiff storage snapshot unavailable" }
            native.storageRevision() to bytes
        }
        store.write(capture.second)
        savedRevision = capture.first
        return true
    }

    /** Drain earlier saves, persist the final revision, then retire the native runtime. */
    fun stop(): IntArray {
        try {
            return writer.submit<IntArray> {
                try {
                    val packets = synchronized(lock) {
                        if (started) {
                            stopping = true
                            diagnostic = false
                            surface.diagnosticReceipt = null
                            phoneReceipts = null
                            native.stop()
                            val pending = ArrayList<Int>()
                            while (true) {
                                val block = native.midiOut()
                                if (block.isEmpty()) break
                                pending.addAll(block.toList())
                                check(pending.size <= 4096) { "Skiff MIDI output did not drain" }
                            }
                            pending.toIntArray()
                        } else intArrayOf()
                    }
                    saveUntilCurrent()
                    packets
                } finally { synchronized(lock) { started = false } }
            }.get()
        } finally { writer.shutdown() }
    }
}

/** Atomic whole-image persistence; the runtime invokes it only from its storage worker. */
interface NorStore {
    fun read(): ByteArray?
    fun write(image: ByteArray)
}

class AtomicNorStore(file: File) : NorStore {
    private val atomic = AtomicFile(file)
    override fun read(): ByteArray? = try {
        atomic.openRead().use { it.readBytes() }
    } catch (_: FileNotFoundException) { null }
    override fun write(image: ByteArray) {
        val stream = atomic.startWrite()
        try {
            stream.write(image)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
