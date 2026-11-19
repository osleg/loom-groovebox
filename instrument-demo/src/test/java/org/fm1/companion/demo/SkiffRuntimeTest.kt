/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkiffRuntimeTest {
    private class Fake : SkiffPort {
        var revision = 0
        var image = byteArrayOf(0)
        var loaded: ByteArray? = null
        var stopped = false
        override fun boot(storage: ByteArray?) { loaded = storage }
        override fun render(out: ShortArray) {}
        override fun masterGain(q12: Int) {}
        override fun input(buttons: Int, keys: Int, turns: IntArray) {}
        override fun snapshot() = ByteArray(256)
        override fun midiIn(packet: Int, source: Int) = true
        override fun editContext() = 0
        override fun midiOut() = intArrayOf()
        override fun midiOutTimed() = longArrayOf()
        override fun exitDeviceMenu() {}
        override fun stop() { stopped = true; revision = 3; image = byteArrayOf(3) }
        override fun storage(lastRevision: Int) = if (lastRevision == revision) null else image.clone()
        override fun storageRevision() = revision
        override fun storeSample(slot: Int, encoded: ByteArray): Int {
            revision++
            image = encoded.clone()
            return 0
        }
    }

    @Test fun savesNewerRevisionAfterWriteInProgressAndBeforeStop() {
        val native = Fake()
        val writes = mutableListOf<ByteArray>()
        lateinit var runtime: SkiffRuntime
        val store = object : NorStore {
            override fun read() = byteArrayOf(9)
            override fun write(image: ByteArray) {
                writes.add(image.clone())
                if (image.contentEquals(byteArrayOf(1))) synchronized(runtime.lock) {
                    native.revision = 2
                    native.image = byteArrayOf(2)
                }
            }
        }
        runtime = SkiffRuntime(store, { throw AssertionError(it) }, native)
        runtime.boot()
        assertArrayEquals(byteArrayOf(9), native.loaded)
        synchronized(runtime.lock) { native.revision = 1; native.image = byteArrayOf(1) }
        runtime.checkpoint()
        runtime.stop()
        assertArrayEquals(byteArrayOf(3), writes.last())
        assertEquals(listOf(1, 2, 3), writes.map { it[0].toInt() })
        assertEquals(true, native.stopped)
    }

    @Test fun acknowledgesSampleAfterPersistenceAndDropsQueuedWriteWhenRuntimeRetires() {
        val writing = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val persisted = mutableListOf<Int>()
        val results = LinkedBlockingQueue<Int>()
        val runtime = SkiffRuntime(object : NorStore {
            override fun read(): ByteArray? = null
            override fun write(image: ByteArray) {
                writing.countDown()
                check(allowWrite.await(5, TimeUnit.SECONDS))
                persisted.add(image.single().toInt())
            }
        }, { throw AssertionError(it) }, Fake())
        runtime.boot()
        try {
            runtime.storeSample(0, byteArrayOf(1)) { results.add(it.getOrThrow()) }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            assertTrue(results.isEmpty())
            runtime.storeSample(1, byteArrayOf(2)) { results.add(it.getOrThrow()) }
            runtime.retire()
            allowWrite.countDown()
            assertEquals(0, results.poll(5, TimeUnit.SECONDS))
            assertEquals(3, results.poll(5, TimeUnit.SECONDS))
        } finally {
            allowWrite.countDown()
            runtime.stop()
        }
        assertEquals(listOf(1, 3), persisted)
    }
}
