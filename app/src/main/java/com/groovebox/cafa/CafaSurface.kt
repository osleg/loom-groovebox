/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.*
import kotlin.math.roundToInt

/** FM1 intentions mutate the real Loom state AND real Loom engine, never a second synth. */
class CafaSurface(
    private val read: () -> GrooveboxState,
    private val write: (GrooveboxState) -> Unit,
    private val engine: Engine,
    private val selectTrack: (Int) -> Unit,
    private val save: () -> Unit,
    private val notice: (String) -> Unit
) {
    interface Engine {
        fun noteOn(track: Int, note: Int)
        fun noteOff(track: Int, note: Int)
        fun playing(value: Boolean)
        fun recording(value: Boolean)
        fun parameter(track: Int, id: Int, value: Float)
        fun volume(track: Int, value: Float)
        fun pan(track: Int, value: Float)
        fun mute(track: Int, value: Boolean)
        fun tempo(value: Float)
        fun length(track: Int, value: Int)
        fun step(track: Int, index: Int, value: StepState)
        fun playhead(track: Int, lane: Int): Int
    }
    companion object {
        const val HOME = 0; const val SEQ = 1; const val SOUND = 2
        const val ENV = 3; const val FX = 4; const val MIX = 5
        val WHITE_KEYS = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)
    }
    private var buttons = 0
    private var keys = 0L
    private var lastSerial = 0
    private var first = true
    private val heldTracks = IntArray(27)
    private val heldNotes = IntArray(27) { -1 }
    private var lastTab = -1
    var page = HOME; private set
    var octave = 0; private set
    var note = 60; private set

    fun reset() {
        for (k in heldNotes.indices) if (heldNotes[k] >= 0) engine.noteOff(heldTracks[k], heldNotes[k])
        heldNotes.fill(-1); buttons = 0; keys = 0; lastSerial = 0; first = true
    }
    fun stop() {
        reset(); engine.playing(false); engine.recording(false)
        write(read().copy(isPlaying = false, isRecording = false, heldNotes = emptySet()))
    }
    private fun state(): GrooveboxState {
        val s = read()
        require(s.selectedTrackIndex in 0..7 && s.selectedTrackIndex in s.tracks.indices)
        if (s.selectedTab != lastTab) {
            page = when (s.selectedTab) { 1 -> SOUND; 2 -> SEQ; 3 -> FX; else -> HOME }
            lastTab = s.selectedTab
        }
        return s
    }
    fun choosePage(value: Int) {
        page = value.coerceIn(HOME, MIX)
        lastTab = when (page) { SOUND, ENV -> 1; SEQ -> 2; FX -> 3; else -> 0 }
        write(read().copy(selectedTab = lastTab))
    }
    private fun multi(t: TrackState) = t.engineType == EngineType.FM_DRUM ||
        t.engineType == EngineType.ANALOG_DRUM ||
        (t.engineType == EngineType.SAMPLER && (t.parameters[320] ?: 0f) >= 0.5f)
    private fun lastLane(t: TrackState) = if (t.engineType == EngineType.SAMPLER) 15 else 7
    private fun lane(t: TrackState) = if (multi(t)) t.selectedFmDrumInstrument.coerceIn(0, lastLane(t)) else -1
    private fun steps(t: TrackState) = if (multi(t)) t.drumSteps[lane(t)] else t.steps
    private fun bank(s: GrooveboxState, t: TrackState) = s.currentSequencerBank.coerceIn(0, (t.patternLength.coerceIn(1, 64) - 1) / 16)

    fun input(serial: Int, payload: ByteArray): Boolean {
        if (payload.size != 20 || payload.any { it.toInt() < 0 }) return false
        if (!first && serial == lastSerial) return true
        if (serial != ((lastSerial + 1) and 16383)) return false
        val nextButtons = Protocol.u14(payload, 0)
        val nextKeys = Protocol.u28(payload, 2)
        if ((nextKeys and 0x7ffffffL.inv()) != 0L) return false
        var pressed = nextButtons and buttons.inv()
        var down = nextKeys and keys.inv()
        val up = keys and nextKeys.inv()
        buttons = nextButtons; keys = nextKeys; lastSerial = serial
        if (first) { first = false; pressed = 0; down = 0 }
        for (k in 0..26) if ((up and (1L shl k)) != 0L && heldNotes[k] >= 0) {
            engine.noteOff(heldTracks[k], heldNotes[k]); heldNotes[k] = -1
        }
        state()
        for ((bit, target) in listOf(0 to FX, 1 to SOUND, 2 to ENV, 4 to SOUND, 5 to MIX, 6 to HOME, 9 to SEQ)) {
            if ((pressed and (1 shl bit)) != 0) choosePage(target)
        }
        if ((pressed and (1 shl 3)) != 0) notice("Use Loom's phone LFO controls; FM1 mapping is not implemented yet.")
        if ((pressed and (1 shl 7)) != 0) save()
        if ((pressed and (1 shl 8)) != 0) notice("Use Loom's phone arpeggiator controls; FM1 mapping is not implemented yet.")
        if ((pressed and (1 shl 10)) != 0) {
            val playing = !read().isPlaying
            engine.playing(playing)
            if (!playing) engine.recording(false)
            write(read().copy(isPlaying = playing, isRecording = playing && read().isRecording))
        }
        if ((pressed and (1 shl 11)) != 0) {
            notice("FM1 recording is not enabled yet. Loom's phone recording controls remain available.")
        }
        if ((pressed and (1 shl 12)) != 0) octave = (octave - 1).coerceIn(-3, 3)
        if ((pressed and (1 shl 13)) != 0) octave = (octave + 1).coerceIn(-3, 3)
        for (e in 0..6) {
            val d = Protocol.u14(payload, 6 + e * 2) - 8192
            if (d == 0) continue
            val s = state(); val t = s.tracks[s.selectedTrackIndex]
            when (e) {
                0 -> if (page == SEQ) write(s.copy(currentSequencerBank = (bank(s, t) + d).coerceIn(0, (t.patternLength.coerceIn(1, 64) - 1) / 16)))
                     else choosePage(Math.floorMod(page + d, 6))
                1 -> selectTrack((s.selectedTrackIndex + d).coerceIn(0, minOf(7, s.tracks.lastIndex)))
                2 -> if (multi(t)) {
                    val selected = (t.selectedFmDrumInstrument + d).coerceIn(0, lastLane(t))
                    replaceTrack(s, t.copy(selectedFmDrumInstrument = selected))
                } else notice("Choose Loom presets/engines on the phone in this slice.")
                else -> knob(e - 3, d)
            }
        }
        for (k in 0..26) if ((down and (1L shl k)) != 0L) {
            val s = state(); val t = s.tracks[s.selectedTrackIndex]
            val white = WHITE_KEYS.indexOf(k)
            if (page == SEQ && white >= 0) toggle(s, t, bank(s, t) * 16 + white)
            else {
                if (multi(t) && white !in 0..lastLane(t)) continue
                val pitch = if (multi(t)) 60 + white else (53 + k + octave * 12).coerceIn(0, 127)
                note = pitch; heldTracks[k] = s.selectedTrackIndex; heldNotes[k] = pitch
                engine.noteOn(s.selectedTrackIndex, pitch)
                write(read().copy(lastMidiNote = pitch, lastMidiVelocity = 100))
            }
        }
        return true
    }
    private fun replaceTrack(s: GrooveboxState, t: TrackState) = write(s.copy(tracks = s.tracks.mapIndexed { i, old -> if (i == s.selectedTrackIndex) t else old }))
    private fun toggle(s: GrooveboxState, t: TrackState, index: Int) {
        if (index !in 0 until t.patternLength.coerceIn(1, 64)) return
        val old = steps(t)[index]
        if (old.parameterLocks.isNotEmpty()) {
            notice("Edit this P-locked step on the phone; FM1 will not overwrite its locks."); return
        }
        val pitches = if (multi(t)) listOf(60 + lane(t)) else old.notes.ifEmpty { listOf(note) }
        val value = old.copy(active = !old.active, notes = pitches)
        val updated = steps(t).mapIndexed { i, v -> if (i == index) value else v }
        val next = if (multi(t)) t.copy(drumSteps = t.drumSteps.mapIndexed { i, v -> if (i == lane(t)) updated else v }) else t.copy(steps = updated)
        engine.step(s.selectedTrackIndex, index, value); replaceTrack(s, next)
    }
    private data class Param(val id: Int, val label: String, val fallback: Float = 0f)
    private fun params(t: TrackState): List<Param> {
        if (page == ENV) return when (t.engineType) {
            EngineType.SUBTRACTIVE, EngineType.FM, EngineType.AUDIO_IN, EngineType.SOUNDFONT -> listOf(100, 101, 102, 103)
            EngineType.SAMPLER -> listOf(310, 311, 312, 313)
            EngineType.GRANULAR -> listOf(425, 426, 427, 428)
            EngineType.WAVETABLE -> listOf(454, 455, 456, 457)
            else -> emptyList()
        }.mapIndexed { i, id -> Param(id, listOf("ATK %", "DEC %", "SUS %", "REL %")[i], listOf(.01f, .5f, 1f, .2f)[i]) }
        return when (t.engineType) {
            EngineType.SUBTRACTIVE -> listOf(Param(112, "CUTOFF", 1f), Param(113, "RESO"), Param(100, "ATK %", .01f), Param(103, "REL %", .2f))
            EngineType.FM -> listOf(Param(151, "CUTOFF", 1f), Param(157, "BRIGHT", .5f), Param(100, "ATK %", .01f), Param(103, "REL %", .2f))
            EngineType.SAMPLER -> listOf(Param(303, "CUTOFF", 1f), Param(304, "RESO"), Param(310, "ATK %", .01f), Param(313, "REL %", .2f))
            EngineType.GRANULAR -> listOf(Param(400, "POSITION", .5f), Param(406, "GRAIN", .2f), Param(425, "ATK %", .01f), Param(428, "REL %", .2f))
            EngineType.WAVETABLE -> listOf(Param(450, "MORPH"), Param(458, "CUTOFF", 1f), Param(454, "ATK %", .01f), Param(457, "REL %", .2f))
            EngineType.AUDIO_IN -> listOf(Param(112, "CUTOFF", 1f), Param(113, "RESO"), Param(100, "ATK %", .01f), Param(103, "REL %", .2f))
            EngineType.SOUNDFONT -> listOf(Param(1, "CUTOFF", 1f), Param(2, "RESO"), Param(100, "ATK %", .01f), Param(103, "REL %", .2f))
            EngineType.FM_DRUM -> listOf(Param(200 + lane(t) * 10, "PITCH", .5f), Param(202 + lane(t) * 10, "DECAY", .5f))
            EngineType.ANALOG_DRUM -> listOf(Param(600 + lane(t) * 10, "DECAY", .5f), Param(601 + lane(t) * 10, "TONE", .5f))
            else -> emptyList()
        }
    }
    fun knob(index: Int, delta: Int) {
        if (index !in 0..3 || delta == 0) return
        val s = state(); val i = s.selectedTrackIndex; val t = s.tracks[i]
        when (page) {
            SEQ -> when (index) {
                0 -> note = (note + delta).coerceIn(0, 127)
                1 -> {
                    val length = (t.patternLength + delta).coerceIn(4, 64)
                    engine.length(i, length)
                    replaceTrack(s.copy(currentSequencerBank = s.currentSequencerBank.coerceIn(0, (length - 1) / 16)), t.copy(patternLength = length, numPages = 1, stepsPerPage = length))
                }
                2 -> if (multi(t)) replaceTrack(s, t.copy(selectedFmDrumInstrument = (lane(t) + delta).coerceIn(0, lastLane(t))))
                     else notice("Edit step velocity/gate on the phone in this slice.")
                3 -> { val v = (s.tempo + delta).coerceIn(12f, 240f); engine.tempo(v); write(s.copy(tempo = v)) }
            }
            MIX, HOME -> when (index) {
                0 -> { val v = (t.volume + delta / 100f).coerceIn(0f, 1f); engine.volume(i, v); replaceTrack(s, t.copy(volume = v)) }
                1 -> { val v = (t.pan + delta / 100f).coerceIn(0f, 1f); engine.pan(i, v); replaceTrack(s, t.copy(pan = v)) }
                2 -> { val v = delta > 0; engine.mute(i, v); replaceTrack(s, t.copy(isMuted = v)) }
                3 -> { val v = (s.tempo + delta).coerceIn(12f, 240f); engine.tempo(v); write(s.copy(tempo = v)) }
            }
            SOUND, ENV -> {
                val p = params(t).getOrNull(index)
                if (p == null) { notice("This engine's controls remain available on the phone."); return }
                val v = ((t.parameters[p.id] ?: p.fallback) + delta / 100f).coerceIn(0f, 1f)
                engine.parameter(i, p.id, v); replaceTrack(s, t.copy(parameters = t.parameters + (p.id to v)))
            }
            else -> notice("FX editing stays on the phone in this slice.")
        }
    }
    fun snapshot(): ByteArray {
        val s = state(); val t = s.tracks[s.selectedTrackIndex]
        require(s.tempo.isFinite() && s.tempo in 12f..300f) { "Tempo outside CAFA v3 range" }
        val length = t.patternLength.coerceIn(1, 64); val start = bank(s, t) * 16
        val head = if (s.isPlaying) engine.playhead(s.selectedTrackIndex, lane(t)).takeIf { it in 0 until length } ?: Protocol.NONE else Protocol.NONE
        val b = ByteArray(92)
        b[0] = s.selectedTrackIndex.toByte(); b[1] = page.toByte()
        b[2] = ((if (s.isPlaying) 1 else 0) or (if (s.isRecording) 2 else 0) or (if (t.isMuted) 4 else 0)).toByte()
        Protocol.put14(b, 3, length); Protocol.put14(b, 5, start); Protocol.put14(b, 7, head); Protocol.put14(b, 9, s.tempo.roundToInt().coerceIn(12, 300))
        var mask = 0; var leds = keys
        for (i in 0..15) if (start + i < length) {
            if (steps(t)[start + i].active) mask = mask or (1 shl i)
            if (page == SEQ && (steps(t)[start + i].active || head == start + i)) leds = leds or (1L shl WHITE_KEYS[i])
        }
        b[11] = (mask and 127).toByte(); b[12] = ((mask ushr 7) and 127).toByte(); b[13] = ((mask ushr 14) and 3).toByte()
        val pageLed = intArrayOf(6, 9, 4, 2, 0, 5)[page]
        Protocol.put14(b, 14, (1 shl pageLed) or (if (s.isPlaying) 1 shl 10 else 0) or (if (s.isRecording) 1 shl 11 else 0))
        Protocol.put28(b, 16, leds)
        Protocol.text(b, 20, 16, "LOOM T${s.selectedTrackIndex + 1}" + if (multi(t)) " ROW${lane(t) + 1}" else " OCT${if (octave >= 0) "+" else ""}$octave")
        Protocol.text(b, 36, 16, t.engineType.name.replace('_', ' '))
        val values: List<Pair<String, Int>> = when (page) {
            SEQ -> listOf("NOTE" to note, "LENGTH" to length, (if (multi(t)) "ROW" else "PHONE") to (if (multi(t)) lane(t) + 1 else 0), "BPM" to s.tempo.roundToInt())
            HOME, MIX -> listOf("VOL %" to (t.volume * 100).roundToInt(), "PAN %" to (t.pan * 100).roundToInt(), "MUTE" to if (t.isMuted) 1 else 0, "BPM" to s.tempo.roundToInt())
            SOUND, ENV -> params(t).map { it.label to ((t.parameters[it.id] ?: it.fallback) * 100).roundToInt() }.ifEmpty { listOf((if (page == ENV) "NO ADSR" else "ON PHONE") to 0) }
            else -> listOf("ON PHONE" to 0)
        }
        for (i in 0..3) {
            val v = values.getOrNull(i) ?: ("" to 0)
            Protocol.text(b, 52 + i * 8, 8, v.first); Protocol.put14(b, 84 + i * 2, v.second.coerceIn(0, 16383))
        }
        return b
    }
}
