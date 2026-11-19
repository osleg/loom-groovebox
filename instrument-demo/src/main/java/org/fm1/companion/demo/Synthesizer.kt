/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.pow

/** Bounded PCM synth. The render thread and main-thread edits share this object's monitor. */
class Synthesizer(val sampleRate: Int = 44100) {
    init { require(sampleRate == 44100) { "Reference audio requires 44.1 kHz" } }
    companion object { const val MAX_VOICES = 16 }
    private data class Voice(val generation: Long, val id: Long, val note: Int, val velocity: Int,
                             var phase: Double = 0.0, var age: Int = 0,
                             var releaseStart: Int = -1, var releaseGain: Double = 0.0)
    private val voices = mutableListOf<Voice>()
    var level = 0.7f; private set
    var waveform = 0f; private set
    var attackMs = 10f; private set
    var releaseMs = 180f; private set
    var pan = 0f; private set

    @Synchronized fun setParameter(id: String, value: Float): Boolean {
        if (!value.isFinite()) return false
        when (id) {
            "level" -> if (value in 0f..1f) level = value else return false
            "waveform" -> if (value in 0f..2f && value == value.toInt().toFloat()) waveform = value else return false
            "attack_ms" -> if (value in 0f..1000f) attackMs = value else return false
            "release_ms" -> if (value in 5f..2000f) releaseMs = value else return false
            "pan" -> if (value in -1f..1f) pan = value else return false
            else -> return false
        }
        return true
    }

    @Synchronized fun noteOn(generation: Long, id: Long, note: Int, velocity: Int): Boolean {
        if (generation < 0 || id <= 0 || note !in 0..127 || velocity !in 1..127 ||
            voices.size >= MAX_VOICES || voices.any { it.generation == generation && it.id == id }) return false
        voices.add(Voice(generation, id, note, velocity))
        return true
    }

    @Synchronized fun noteOff(generation: Long, id: Long): Boolean {
        val voice = voices.firstOrNull { it.generation == generation && it.id == id } ?: return false
        if (voice.releaseStart < 0) {
            voice.releaseStart = voice.age
            voice.releaseGain = attackGain(voice.age)
        }
        return true
    }

    @Synchronized fun cancel(generation: Long) { voices.removeAll { it.generation == generation } }
    @Synchronized fun silence() { voices.clear() }
    @Synchronized fun voiceCount(): Int = voices.size

    /** Stereo interleaved PCM16. A released voice reaches silence within its release duration. */
    @Synchronized fun render(frames: Int): ShortArray {
        require(frames in 1..4096)
        val output = ShortArray(frames * 2)
        val releaseFrames = (releaseMs * sampleRate / 1000f).toInt().coerceAtLeast(1)
        for (frame in 0 until frames) {
            var sample = 0.0
            for (voice in voices) {
                val envelope = if (voice.releaseStart >= 0)
                    voice.releaseGain * (1.0 - (voice.age - voice.releaseStart).toDouble() / releaseFrames).coerceAtLeast(0.0)
                else attackGain(voice.age)
                val wave = when (waveform.toInt()) {
                    1 -> 4.0 * kotlin.math.abs(voice.phase - 0.5) - 1.0
                    2 -> if (voice.phase < 0.5) 1.0 else -1.0
                    else -> sin(2.0 * PI * voice.phase)
                }
                sample += wave * envelope * voice.velocity / 127.0
                voice.phase = (voice.phase + 440.0 * 2.0.pow((voice.note - 69) / 12.0) / sampleRate) % 1.0
                voice.age++
            }
            sample *= level.toDouble() * 0.06
            output[frame * 2] = (sample * (1.0 - pan.coerceAtLeast(0f).toDouble()) * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
            output[frame * 2 + 1] = (sample * (1.0 + pan.coerceAtMost(0f).toDouble()) * Short.MAX_VALUE)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
            voices.removeAll { it.releaseStart >= 0 && it.age - it.releaseStart >= releaseFrames }
        }
        return output
    }

    private fun attackGain(age: Int): Double {
        val frames = (attackMs * sampleRate / 1000f).toInt()
        return if (frames == 0) 1.0 else (age.toDouble() / frames).coerceIn(0.0, 1.0)
    }
}
