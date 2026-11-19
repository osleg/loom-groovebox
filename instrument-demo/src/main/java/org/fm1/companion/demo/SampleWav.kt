/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Reads bounded little-endian PCM WAV and converts it to Skiff's mono sample rate. */
internal object SampleWav {
    const val MAX_SECONDS = 3
    private const val SAMPLE_RATE = 44100
    private const val MAX_METADATA = 1024 * 1024
    private val pcmGuid = byteArrayOf(1, 0, 0, 0, 0, 0, 16, 0, -128, 0, 0, -86, 0, 56, -101, 113)
    private data class Format(val rate: Int, val channels: Int, val bytes: Int) {
        val frameBytes = channels * bytes
    }

    fun read(input: InputStream): ShortArray {
        val source = DataInputStream(input)
        try {
            val header = ByteArray(12).also { source.readFully(it) }
            require(header.tag(0) == "RIFF" && header.tag(8) == "WAVE") {
                "Choose a PCM WAV file."
            }
            var remaining = header.u32(4) - 4
            var metadata = 0L
            var format: Format? = null
            val chunk = ByteArray(8)
            while (remaining >= chunk.size) {
                source.readFully(chunk)
                val size = chunk.u32(4)
                val padded = size + (size and 1L)
                remaining -= chunk.size
                require(padded <= remaining) { "WAV chunk is truncated." }
                if (chunk.tag(0) == "data") {
                    val info = requireNotNull(format) { "WAV format must precede audio data." }
                    require(size > 0 && size % info.frameBytes == 0L) { "WAV has incomplete audio frames." }
                    require(size <= info.rate * MAX_SECONDS * info.frameBytes) {
                        "Choose a WAV file up to $MAX_SECONDS seconds long."
                    }
                    val audio = ByteArray(size.toInt()).also { source.readFully(it) }
                    return convert(audio, info)
                }
                metadata += chunk.size + padded
                require(metadata <= MAX_METADATA) { "WAV metadata is too large." }
                if (chunk.tag(0) == "fmt ") {
                    require(format == null && size >= 16) { "Invalid WAV format chunk." }
                    val bytes = ByteArray(minOf(size.toInt(), 40)).also { source.readFully(it) }
                    format = parseFormat(bytes, size.toInt())
                    skip(source, padded.toInt() - bytes.size)
                } else {
                    skip(source, padded.toInt())
                }
                remaining -= padded
            }
        } catch (_: EOFException) {
            throw IllegalArgumentException("WAV file is truncated.")
        }
        throw IllegalArgumentException("WAV file has no audio data.")
    }

    private fun parseFormat(bytes: ByteArray, chunkSize: Int): Format {
        val kind = bytes.u16(0)
        val channels = bytes.u16(2)
        val rate = bytes.u32(4)
        val bits = bytes.u16(14)
        val extensionSize = if (bytes.size >= 40) bytes.u16(16) else 0
        val extensiblePcm = kind == 0xfffe && extensionSize in 22..(chunkSize - 18) &&
            bytes.u16(18) in 1..bits && pcmGuid.indices.all { bytes[24 + it] == pcmGuid[it] }
        require(kind == 1 || extensiblePcm) { "WAV audio must use integer PCM." }
        require(channels in 1..2 && rate in 8000..96000 && bits in setOf(8, 16, 24, 32)) {
            "Use mono or stereo PCM WAV at 8–96 kHz with 8, 16, 24, or 32 bits."
        }
        val format = Format(rate.toInt(), channels, bits / 8)
        require(bytes.u16(12) == format.frameBytes && bytes.u32(8) == rate * format.frameBytes) {
            "WAV frame layout is invalid."
        }
        return format
    }

    private fun convert(raw: ByteArray, format: Format): ShortArray {
        fun sample(offset: Int): Int = when (format.bytes) {
            1 -> (raw.u8(offset) - 128) shl 8
            else -> raw.u16(offset + format.bytes - 2).toShort().toInt()
        }
        val frames = raw.size / format.frameBytes
        val mono = ShortArray(frames) { frame ->
            val offset = frame * format.frameBytes
            val left = sample(offset)
            (if (format.channels == 1) left else (left + sample(offset + format.bytes)) / 2).toShort()
        }
        if (format.rate == SAMPLE_RATE) return mono
        val outputFrames = maxOf(1, (frames.toLong() * SAMPLE_RATE / format.rate).toInt())
        return ShortArray(outputFrames) { frame ->
            val position = frame.toLong() * format.rate
            val index = (position / SAMPLE_RATE).toInt()
            val fraction = (position % SAMPLE_RATE).toInt()
            if (format.rate < SAMPLE_RATE) {
                val current = mono[index].toInt()
                val next = mono[minOf(index + 1, mono.lastIndex)].toInt()
                (current + (next - current).toLong() * fraction / SAMPLE_RATE).toShort()
            } else {
                // Average each output frame's source interval when reducing the sample rate.
                var sourceIndex = index
                var available = SAMPLE_RATE - fraction
                var left = format.rate
                var sum = 0L
                while (left > 0) {
                    val weight = minOf(left, available)
                    sum += mono[minOf(sourceIndex++, mono.lastIndex)].toLong() * weight
                    left -= weight
                    available = SAMPLE_RATE
                }
                (sum / format.rate).toShort()
            }
        }
    }

    private fun skip(input: DataInputStream, count: Int) {
        val scratch = ByteArray(minOf(count, 4096))
        var left = count
        while (left > 0) {
            val block = minOf(left, scratch.size)
            input.readFully(scratch, 0, block)
            left -= block
        }
    }

    private fun ByteArray.tag(offset: Int) = String(this, offset, 4, Charsets.US_ASCII)
    private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 255
    private fun ByteArray.u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
}
