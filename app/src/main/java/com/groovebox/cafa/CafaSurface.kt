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
        fun lfo(index: Int, value: LfoState)
        fun arp(track: Int, value: ArpConfig)
        fun fxEnabled(index: Int, enabled: Boolean)
        fun swing(value: Float)
        fun humanize(track: Int, value: Float)
        fun lane(track: Int, value: Int)
    }
    companion object {
        const val HOME = 0; const val SEQ = 1; const val SOUND = 2
        const val ENV = 3; const val FX = 4; const val MIX = 5
        const val LFO = 6; const val ARP = 7; const val PATTERN = 8
        val ARP_RATES = listOf(.5f, 1f, 2f, 4f, 8f)
        val WHITE_KEYS = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)
    }
    private var buttons = 0
    private var keys = 0L
    private var lastSerial = 0
    private var first = true
    private val heldTracks = IntArray(27)
    private val heldNotes = IntArray(27) { -1 }
    private var lastTab = -1
    private var lastTrack = -1
    private data class HeldStep(val track: Int, val lane: Int, val index: Int, var edited: Boolean = false)
    private val heldSteps = arrayOfNulls<HeldStep>(27)
    var lfoIndex = 0; private set
    var fxIndex = 0; private set
    var arpPage = 0; private set
    var page = HOME; private set
    var octave = 0; private set
    var note = 60; private set

    fun reset() {
        for (k in heldNotes.indices) if (heldNotes[k] >= 0) engine.noteOff(heldTracks[k], heldNotes[k])
        heldSteps.fill(null); heldNotes.fill(-1); buttons = 0; keys = 0; lastSerial = 0; first = true
    }
    fun stop() {
        reset(); engine.playing(false); engine.recording(false)
        write(read().copy(isPlaying = false, isRecording = false, heldNotes = emptySet()))
    }
    private fun state(): GrooveboxState {
        val s = read()
        require(s.selectedTrackIndex in 0..7 && s.selectedTrackIndex in s.tracks.indices)
        if (lastTrack != s.selectedTrackIndex) {
            heldSteps.fill(null); lastTrack = s.selectedTrackIndex
        }
        if (s.selectedTab != lastTab) {
            heldSteps.fill(null)
            page = when (s.selectedTab) { 1 -> SOUND; 2 -> SEQ; 3 -> FX; 4 -> LFO; else -> HOME }
            lastTab = s.selectedTab
        }
        return s
    }
    fun choosePage(value: Int) {
        heldSteps.fill(null)
        page = value.coerceIn(HOME, PATTERN)
        lastTab = when (page) { SOUND, ENV -> 1; SEQ, PATTERN -> 2; FX -> 3; LFO -> 4; else -> 0 }
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
        val initial = first
        var pressed = nextButtons and buttons.inv()
        var down = nextKeys and keys.inv()
        val up = keys and nextKeys.inv()
        buttons = nextButtons; keys = nextKeys; lastSerial = serial
        if (initial) { first = false; pressed = 0; down = 0 }
        state() // Cancel pending gestures if the touchscreen changed track/page.
        for (k in 0..26) if ((up and (1L shl k)) != 0L) {
            if (heldNotes[k] >= 0) {
                engine.noteOff(heldTracks[k], heldNotes[k]); heldNotes[k] = -1
            }
            heldSteps[k]?.let { h ->
                val s = state(); val t = s.tracks[s.selectedTrackIndex]
                if (!h.edited && page == SEQ && h.track == s.selectedTrackIndex && h.lane == lane(t))
                    toggle(s, t, h.index)
            }
            heldSteps[k] = null
        }
        for ((bit, target) in listOf(0 to FX, 1 to SOUND, 2 to ENV, 3 to LFO,
                4 to SOUND, 5 to MIX, 6 to HOME, 8 to ARP, 9 to SEQ)) {
            if ((pressed and (1 shl bit)) != 0) {
                if (target == SEQ && page == SEQ) choosePage(PATTERN) else choosePage(target)
            }
        }
        if ((pressed and (1 shl 7)) != 0) save()
        if ((pressed and (1 shl 10)) != 0) {
            val playing = !read().isPlaying
            engine.playing(playing)
            if (!playing) engine.recording(false)
            write(read().copy(isPlaying = playing, isRecording = playing && read().isRecording))
        }
        if ((pressed and (1 shl 11)) != 0) {
            notice("REC awaits complete recorded-step synchronization; use phone recording for now.")
        }
        if ((pressed and (1 shl 12)) != 0) octave = (octave - 1).coerceIn(-3, 3)
        if ((pressed and (1 shl 13)) != 0) octave = (octave + 1).coerceIn(-3, 3)
        // Track changes precede new key presses in the same input snapshot.
        val trackDelta = if (initial) 0 else Protocol.u14(payload, 8) - 8192
        if (trackDelta != 0) {
            val s = state()
            heldSteps.fill(null)
            selectTrack((s.selectedTrackIndex + trackDelta).coerceIn(0, minOf(7, s.tracks.lastIndex)))
        }
        for (k in 0..26) if ((down and (1L shl k)) != 0L) {
            val s = state(); val t = s.tracks[s.selectedTrackIndex]
            val white = WHITE_KEYS.indexOf(k)
            if (page == SEQ && white >= 0) {
                val index = bank(s, t) * 16 + white
                if (index < t.patternLength) heldSteps[k] = HeldStep(s.selectedTrackIndex, lane(t), index)
            } else {
                if (multi(t) && white !in 0..lastLane(t)) continue
                val pitch = if (multi(t)) 60 + white else (53 + k + octave * 12).coerceIn(0, 127)
                note = pitch; heldTracks[k] = s.selectedTrackIndex; heldNotes[k] = pitch
                engine.noteOn(s.selectedTrackIndex, pitch)
                write(read().copy(lastMidiNote = pitch, lastMidiVelocity = 100))
            }
        }
        if (!initial) for (e in 0..6) {
            if (e == 1) continue
            val d = Protocol.u14(payload, 6 + e * 2) - 8192
            if (d == 0) continue
            val s = state(); val t = s.tracks[s.selectedTrackIndex]
            when (e) {
                0 -> if ((buttons and (1 shl 5)) != 0) tempo(s, d) else when (page) {
                    SEQ, PATTERN -> choosePage(if (d > 0) PATTERN else SEQ)
                    LFO -> lfoIndex = (lfoIndex + d).coerceIn(0, s.lfos.lastIndex)
                    FX -> fxIndex = (fxIndex + d).coerceIn(0, minOf(t.fxSends.lastIndex, t.fxMix.lastIndex, s.fxEnabled.lastIndex))
                    ARP -> arpPage = (arpPage + d).coerceIn(0, 1)
                    else -> Unit // SELECT stays within the physical button's family.
                }
                2 -> if (page == SEQ) {
                    heldSteps.fill(null)
                    write(s.copy(currentSequencerBank = (bank(s, t) + d).coerceIn(0, (t.patternLength.coerceIn(1, 64) - 1) / 16)))
                } else if (multi(t)) {
                    val selected = (t.selectedFmDrumInstrument + d).coerceIn(0, lastLane(t))
                    engine.lane(s.selectedTrackIndex, selected)
                    replaceTrack(s, t.copy(selectedFmDrumInstrument = selected))
                } else notice("Preset loading remains on the phone; existing sounds are unchanged.")
                else -> knob(e - 3, d)
            }
        }
        return true
    }
    private fun tempo(s: GrooveboxState, delta: Int) {
        val v = (s.tempo + delta).coerceIn(12f, 240f)
        engine.tempo(v); write(s.copy(tempo = v))
    }
    private fun replaceTrack(s: GrooveboxState, t: TrackState) = write(s.copy(tracks = s.tracks.mapIndexed { i, old -> if (i == s.selectedTrackIndex) t else old }))
    private fun toggle(s: GrooveboxState, t: TrackState, index: Int) {
        if (s.isRecording) { notice("Stop recording before replacing a step."); return }
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
    private fun editHeld(index: Int, delta: Int): Boolean {
        val held = heldSteps.filterNotNull()
        if (held.isEmpty()) return false
        // Mark consumed even if refused, so releasing cannot accidentally toggle a hit.
        held.forEach { it.edited = true }
        if (read().isRecording) { notice("Stop recording before replacing a step."); return true }
        for (h in held) {
            val s = state(); val t = s.tracks[s.selectedTrackIndex]
            if (h.track != s.selectedTrackIndex || h.lane != lane(t) || h.index !in 0 until t.patternLength) continue
            val old = steps(t)[h.index]
            if (old.parameterLocks.isNotEmpty()) {
                notice("Edit this P-locked step on the phone; FM1 will not overwrite its locks."); continue
            }
            val pitches = old.notes.ifEmpty { listOf(if (multi(t)) 60 + lane(t) else note) }
            val value = when (index) {
                0 -> if (multi(t)) old else {
                    val d = delta.coerceIn(-pitches.minOrNull()!!, 127 - pitches.maxOrNull()!!)
                    old.copy(notes = pitches.map { it + d })
                }
                1 -> old.copy(velocity = (old.velocity + delta / 100f).coerceIn(0f, 1f),
                    noteVelocities = old.noteVelocities.map { (it + delta / 100f).coerceIn(0f, 1f) })
                2 -> old.copy(gate = (old.gate + delta / 10f).coerceIn(.1f, 64f))
                else -> old.copy(probability = (old.probability + delta / 100f).coerceIn(0f, 1f))
            }
            val updated = steps(t).mapIndexed { i, v -> if (i == h.index) value else v }
            val next = if (multi(t)) t.copy(drumSteps = t.drumSteps.mapIndexed { i, v -> if (i == lane(t)) updated else v }) else t.copy(steps = updated)
            engine.step(s.selectedTrackIndex, h.index, value); replaceTrack(s, next)
        }
        return true
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
        if (page == SEQ && editHeld(index, delta)) return
        when (page) {
            SEQ -> when (index) {
                0 -> note = (note + delta).coerceIn(0, 127)
                1 -> changeLength(s, t, delta)
                2 -> if (multi(t)) {
                    val selected = (lane(t) + delta).coerceIn(0, lastLane(t))
                    engine.lane(i, selected); replaceTrack(s, t.copy(selectedFmDrumInstrument = selected))
                }
                3 -> tempo(s, delta)
            }
            PATTERN -> when (index) {
                0 -> changeLength(s, t, delta)
                1 -> tempo(s, delta)
                2 -> { val v = (s.swing + delta / 100f).coerceIn(-.23f, .23f); engine.swing(v); write(s.copy(swing = v)) }
                3 -> { val v = (t.humanize + delta / 100f).coerceIn(0f, 1f); engine.humanize(i, v); replaceTrack(s, t.copy(humanize = v)) }
            }
            MIX, HOME -> when (index) {
                0 -> { val v = (t.volume + delta / 100f).coerceIn(0f, 1f); engine.volume(i, v); replaceTrack(s, t.copy(volume = v)) }
                1 -> { val v = (t.pan + delta / 100f).coerceIn(0f, 1f); engine.pan(i, v); replaceTrack(s, t.copy(pan = v)) }
                2 -> { val v = delta > 0; engine.mute(i, v); replaceTrack(s, t.copy(isMuted = v)) }
                3 -> tempo(s, delta)
            }
            LFO -> {
                val old = s.lfos[lfoIndex]
                val v = when (index) {
                    0 -> old.copy(rate = (old.rate + delta / 100f).coerceIn(0f, 1f))
                    1 -> old.copy(depth = (old.depth + delta / 100f).coerceIn(0f, 1f))
                    2 -> old.copy(shape = (old.shape + delta).coerceIn(0, 4))
                    else -> old.copy(sync = delta > 0)
                }
                engine.lfo(lfoIndex, v)
                write(s.copy(lfos = s.lfos.mapIndexed { n, oldLfo -> if (n == lfoIndex) v else oldLfo }))
            }
            FX -> when (index) {
                0 -> fxIndex = (fxIndex + delta).coerceIn(0, minOf(t.fxSends.lastIndex, t.fxMix.lastIndex, s.fxEnabled.lastIndex))
                1, 2 -> {
                    val old = if (index == 1) t.fxSends else t.fxMix
                    val v = (old[fxIndex] + delta / 100f).coerceIn(0f, 1f)
                    val updated = old.mapIndexed { n, x -> if (n == fxIndex) v else x }
                    engine.parameter(i, 2000 + fxIndex * 10 + index - 1, v)
                    replaceTrack(s, if (index == 1) t.copy(fxSends = updated) else t.copy(fxMix = updated))
                }
                else -> {
                    val v = delta > 0; engine.fxEnabled(fxIndex, v)
                    write(s.copy(fxEnabled = s.fxEnabled.mapIndexed { n, old -> if (n == fxIndex) v else old }))
                }
            }
            ARP -> {
                val old = t.arpConfig
                val v = if (arpPage == 0) when (index) {
                    0 -> old.copy(mode = ArpMode.values()[(old.mode.ordinal + delta).coerceIn(0, ArpMode.values().lastIndex)])
                    1 -> old.copy(octaves = (old.octaves + delta).coerceIn(-3, 3))
                    2 -> old.copy(isLatched = delta > 0)
                    else -> old.copy(arpRate = ARP_RATES[(ARP_RATES.indexOfFirst { it >= old.arpRate }.coerceAtLeast(0) + delta).coerceIn(0, ARP_RATES.lastIndex)])
                } else when (index) {
                    0 -> old.copy(inversion = (old.inversion + delta).coerceIn(-2, 2))
                    1 -> old.copy(probability = (old.probability + delta / 100f).coerceIn(0f, 1f))
                    2 -> old.copy(weird = (old.weird + delta / 100f).coerceIn(0f, 1f))
                    else -> old.copy(strum = (old.strum + delta / 100f).coerceIn(0f, 1f))
                }
                engine.arp(i, v); replaceTrack(s, t.copy(arpConfig = v))
            }
            SOUND, ENV -> {
                val p = params(t).getOrNull(index)
                if (p == null) { notice("This engine's controls remain available on the phone."); return }
                val v = ((t.parameters[p.id] ?: p.fallback) + delta / 100f).coerceIn(0f, 1f)
                engine.parameter(i, p.id, v); replaceTrack(s, t.copy(parameters = t.parameters + (p.id to v)))
            }
        }
    }
    private fun changeLength(s: GrooveboxState, t: TrackState, delta: Int) {
        val length = (t.patternLength + delta).coerceIn(4, 64)
        heldSteps.fill(null)
        engine.length(s.selectedTrackIndex, length)
        replaceTrack(s.copy(currentSequencerBank = s.currentSequencerBank.coerceIn(0, (length - 1) / 16)),
            t.copy(patternLength = length, numPages = 1, stepsPerPage = length))
    }
    fun snapshot(): ByteArray {
        val s = state(); val t = s.tracks[s.selectedTrackIndex]
        require(s.tempo.isFinite() && s.tempo in 12f..300f) { "Tempo outside CAFA v4 range" }
        val length = t.patternLength.coerceIn(1, 64); val start = bank(s, t) * 16
        val head = if (s.isPlaying) engine.playhead(s.selectedTrackIndex, lane(t)).takeIf { it in 0 until length } ?: Protocol.NONE else Protocol.NONE
        val b = ByteArray(92)
        b[0] = s.selectedTrackIndex.toByte(); b[1] = page.toByte()
        b[2] = ((if (s.isPlaying) 1 else 0) or (if (s.isRecording) 2 else 0) or (if (t.isMuted) 4 else 0) or ((octave + 3) shl 3)).toByte()
        Protocol.put14(b, 3, length); Protocol.put14(b, 5, start); Protocol.put14(b, 7, head); Protocol.put14(b, 9, s.tempo.roundToInt().coerceIn(12, 300))
        var mask = 0; var leds = keys
        for (i in 0..15) if (start + i < length) {
            if (steps(t)[start + i].active) mask = mask or (1 shl i)
            if (page == SEQ && (steps(t)[start + i].active || head == start + i)) leds = leds or (1L shl WHITE_KEYS[i])
        }
        b[11] = (mask and 127).toByte(); b[12] = ((mask ushr 7) and 127).toByte(); b[13] = ((mask ushr 14) and 3).toByte()
        val pageLed = intArrayOf(6, 9, 4, 2, 0, 5, 3, 8, 9)[page]
        Protocol.put14(b, 14, (1 shl pageLed) or (if (s.isPlaying) 1 shl 10 else 0) or (if (s.isRecording) 1 shl 11 else 0))
        Protocol.put28(b, 16, leds)
        Protocol.text(b, 20, 16, "LOOM T${s.selectedTrackIndex + 1}" + if (multi(t)) " ROW${lane(t) + 1}" else " OCT${if (octave >= 0) "+" else ""}$octave")
        Protocol.text(b, 36, 16, when (page) {
            LFO -> "LFO ${lfoIndex + 1} ${s.lfos[lfoIndex].targetLabel}"
            FX -> "LOOM FX ${fxIndex + 1}"
            ARP -> "ARP ${arpPage + 1} ${t.arpConfig.mode.name}"
            else -> t.engineType.name.replace('_', ' ')
        })
        val values: List<Pair<String, Int>> = when (page) {
            SEQ -> heldSteps.filterNotNull().firstOrNull()?.let { h ->
                val step = steps(t)[h.index]
                listOf("NOTE" to (step.notes.firstOrNull() ?: note), "VEL %" to (step.velocity * 100).roundToInt(),
                    "GATE x10" to (step.gate * 10).roundToInt(), "PROB %" to (step.probability * 100).roundToInt())
            } ?: listOf("NOTE" to note, "LENGTH" to length, (if (multi(t)) "ROW" else "HOLD KEY") to (if (multi(t)) lane(t) + 1 else 0), "BPM" to s.tempo.roundToInt())
            HOME, MIX -> listOf("VOL %" to (t.volume * 100).roundToInt(), "PAN %" to (t.pan * 100).roundToInt(), "MUTE" to if (t.isMuted) 1 else 0, "BPM" to s.tempo.roundToInt())
            SOUND, ENV -> params(t).map { it.label to ((t.parameters[it.id] ?: it.fallback) * 100).roundToInt() }.ifEmpty { listOf((if (page == ENV) "NO ADSR" else "ON PHONE") to 0) }
            PATTERN -> listOf("LENGTH" to length, "BPM" to s.tempo.roundToInt(),
                "SWING+23" to ((s.swing + .23f) * 100).roundToInt(), "HUMAN %" to (t.humanize * 100).roundToInt())
            LFO -> s.lfos[lfoIndex].let { listOf("RATE %" to (it.rate * 100).roundToInt(),
                "DEPTH %" to (it.depth * 100).roundToInt(), "SHAPE" to it.shape, "SYNC" to if (it.sync) 1 else 0) }
            FX -> listOf("FX BUS" to fxIndex + 1, "SEND %" to (t.fxSends[fxIndex] * 100).roundToInt(),
                "MIX %" to (t.fxMix[fxIndex] * 100).roundToInt(), "ENABLED" to if (s.fxEnabled[fxIndex]) 1 else 0)
            ARP -> t.arpConfig.let { if (arpPage == 0) listOf("MODE" to it.mode.ordinal, "OCT+3" to it.octaves + 3,
                "LATCH" to if (it.isLatched) 1 else 0, "RATE x2" to (it.arpRate * 2).roundToInt())
                else listOf("INV+2" to it.inversion + 2, "PROB %" to (it.probability * 100).roundToInt(),
                    "WEIRD %" to (it.weird * 100).roundToInt(), "STRUM %" to (it.strum * 100).roundToInt()) }
            else -> emptyList()
        }
        for (i in 0..3) {
            val v = values.getOrNull(i) ?: ("" to 0)
            Protocol.text(b, 52 + i * 8, 8, v.first); Protocol.put14(b, 84 + i * 2, v.second.coerceIn(0, 16383))
        }
        return b
    }
}
