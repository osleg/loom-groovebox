/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SampleWavTest {
    @Test fun importsStereoPcmWithOddMetadataAndResamplesToMono() {
        val pcm = byteArrayOf(0, 64, 0, 0, 0, -64, 0, 0)
        val source = wav(22050, 2, 16, pcm, extra = chunk("JUNK", byteArrayOf(42)))
        assertArrayEquals(shortArrayOf(8192, 0, -8192, -8192), SampleWav.read(source.inputStream()))
    }

    @Test fun readsEightBitUnsignedAndTwentyFourBitSignedPcm() {
        assertArrayEquals(shortArrayOf(-32768, 0, 32512),
            SampleWav.read(wav(44100, 1, 8, byteArrayOf(0, -128, -1)).inputStream()))
        assertArrayEquals(shortArrayOf(-32768, 32767),
            SampleWav.read(wav(44100, 1, 24, byteArrayOf(0, 0, -128, -1, -1, 127)).inputStream()))
    }

    @Test fun rejectsUnsupportedTruncatedAndOversizedWav() {
        val pcm = byteArrayOf(0, 0)
        val invalid = listOf(
            wav(44100, 1, 16, pcm, kind = 3),
            wav(44100, 1, 16, pcm).dropLast(1).toByteArray(),
            wav(8000, 1, 8, ByteArray(24001)),
            wav(44100, 1, 16, pcm, extra = chunk("JUNK", ByteArray(1024 * 1024)))
        )
        for (bytes in invalid)
            assertThrows(IllegalArgumentException::class.java) { SampleWav.read(bytes.inputStream()) }
    }

    private fun wav(rate: Int, channels: Int, bits: Int, pcm: ByteArray, kind: Int = 1,
                    extra: ByteArray = byteArrayOf()): ByteArray {
        val stride = channels * bits / 8
        val format = le(16).putShort(kind.toShort()).putShort(channels.toShort())
            .putInt(rate).putInt(rate * stride).putShort(stride.toShort()).putShort(bits.toShort())
        return chunk("RIFF", "WAVE".toByteArray() + chunk("fmt ", format.array()) + extra + chunk("data", pcm))
    }

    private fun chunk(tag: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(tag.toByteArray())
        write(le(4).putInt(bytes.size).array())
        write(bytes)
        if (bytes.size % 2 != 0) write(0)
    }.toByteArray()

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
