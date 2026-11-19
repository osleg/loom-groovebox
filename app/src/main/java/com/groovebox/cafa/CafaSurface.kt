/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.*
import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.legacy.LegacySurface
import org.fm1.companion.legacy.Protocol
import org.fm1.companion.instrument.OperationDispatcher
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ObjectRef
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.transport.TransportControls
import org.fm1.companion.transport.TransportIntent
import org.fm1.companion.parts.PartsControls
import org.fm1.companion.parts.PartsIntent
import org.fm1.companion.parts.PartsState
import org.fm1.companion.sequence.SequenceIntent
import org.fm1.companion.sequence.SequenceScan
import org.fm1.companion.sequence.SequenceValueFormat
import org.fm1.companion.sequence.SkiffSequenceEditor
import kotlin.math.roundToInt

/** FM1 intentions mutate the real Loom state AND real Loom engine, never a second synth. */
class CafaSurface(
    private val read: () -> GrooveboxState,
    private val write: (GrooveboxState) -> Unit,
    private val engine: Engine,
    private val selectTrack: (Int) -> Unit,
    private val save: () -> Boolean,
    private val notice: (String) -> Unit
) : LegacySurface {
    interface Engine {
        fun selectEngine(track: Int, type: EngineType) {}
        fun noteOn(track: Int, note: Int)
        fun noteOff(track: Int, note: Int)
        fun playing(value: Boolean)
        fun recording(value: Boolean)
        fun parameter(track: Int, id: Int, value: Float)
        fun volume(track: Int, value: Float)
        fun pan(track: Int, value: Float)
        fun mute(track: Int, value: Boolean)
        fun solo(track: Int, value: Boolean)
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
        fun chainEnabled(track: Int, enabled: Boolean) {}
        fun chainLength(track: Int, length: Int) {}
        fun chainSlot(track: Int, slot: Int, steps: Array<StepState>) {}
        fun scale(root: Int, intervals: IntArray) {}
        fun macro(index: Int, value: Float) {}
        fun sidechain(track: Int, drum: Int) {}
        fun masterVolume(value: Float) {}
    }
    companion object {
        const val HOME = 0; const val SEQ = 1; const val SOUND = 2
        const val ENV = 3; const val FX = 4; const val MIX = 5
        const val LFO = 6; const val ARP = 7; const val PATTERN = 8
        val ARP_RATES = listOf(.5f, 1f, 2f, 4f, 8f)
        val WHITE_KEYS = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)
    }
    val catalog = CafaCatalog()
    private val transportAdapter = LoomTransportAdapter(read, write, engine::playing, engine::recording, engine::tempo)
    private val transportDispatcher = OperationDispatcher(transportAdapter)
    private var transportSessionActive = false
    private fun performTransport(target: ObjectRef, revision: Long, intent: TransportIntent): OperationResult {
        if (!transportSessionActive) {
            transportDispatcher.begin()
            transportSessionActive = true
        }
        return transportDispatcher.submit(transportDispatcher.request(target, revision, intent))
    }
    private val transport = TransportControls(transportAdapter, ::performTransport)
    private val partsAdapter = LoomPartsAdapter(read, write, selectTrack, engine::volume, engine::pan, engine::mute, engine::solo)
    private val partsDispatcher = OperationDispatcher(partsAdapter)
    private var partsSessionActive = false
    private fun performParts(target: ObjectRef, revision: Long, intent: PartsIntent): OperationResult {
        if (!partsSessionActive) {
            partsDispatcher.begin()
            partsSessionActive = true
        }
        if (intent is PartsIntent.SelectPart) releaseHeldNotes()
        return partsDispatcher.submit(partsDispatcher.request(target, revision, intent))
    }
    private val parts = PartsControls(partsAdapter, ::performParts)
    private var knownPartsContext: PartsState.Context? = null
    private var mixBank = 0
    private val soundBanks = ParameterBanks(LoomParameterAdapter(read, write, ::catalogControls,
        { catalog.generation }, engine::parameter))
    private var heldDefaultControls: List<org.fm1.companion.control.ParameterDescriptor?>? = null
    private data class SoundIdentity(
        val trackId: Int, val engine: EngineType, val catalogEntryId: String?,
        val catalogGeneration: Int, val selectedFmPreset: Int?, val selectedFmDrumInstrument: Int,
        val soundFontPath: String?, val soundFontPresetIndex: Int, val soundFontPresetName: String,
        val lastSamplePath: String?, val activeWavetableName: String,
        val soundBindings: List<Fm1KnobBinding?>?,
        val catalogControls: List<org.fm1.companion.control.ParameterDescriptor>
    )
    private var heldSoundIdentity: SoundIdentity? = null
    private val sequenceAdapter = LoomSequenceAdapter(read, write,
        { trackIndex, lane, step, value ->
            engine.step(trackIndex, step, if (lane >= 0) value.copy(notes = listOf(60 + lane)) else value)
        },
        { selected ->
            val s = read(); val index = s.selectedTrackIndex
            engine.lane(index, selected)
            replaceTrack(s, s.tracks[index].copy(selectedFmDrumInstrument = selected))
        }, ::heldLockControls)
    private val sequenceDispatcher = OperationDispatcher(sequenceAdapter)
    private var sequenceSessionActive = false
    private fun performSequence(target: ObjectRef, revision: Long, intent: SequenceIntent): OperationResult {
        if (!sequenceSessionActive) {
            sequenceDispatcher.begin(); sequenceSessionActive = true
        }
        return sequenceDispatcher.submit(sequenceDispatcher.request(target, revision, intent))
    }
    private val sequenceEditor = SkiffSequenceEditor(sequenceAdapter, ::performSequence) { pitch, _ ->
        val track = read().selectedTrackIndex
        engine.noteOn(track, pitch)
        val release: () -> Unit = { engine.noteOff(track, pitch) }
        release
    }
    private var knownSequenceContext: Long? = null
    private var knownSequencerBank: Int? = null
    private var homeUsedAsShift = false
    private var sequenceMessage = ""
    private var staleSequenceCancelled = false
    private var knownSoundContext: ParameterBanks.Context? = null
    private var catalogContext = ""
    private var catalogEpoch = 1
    private var lastKnobBindings: List<Fm1KnobBinding?>? = null
    private var saveFeedback = ""
    private var saveFeedbackUntil = 0L
    init {
        for (type in EngineType.values()) catalog.register(CafaCatalog.Entry(type.name, type.name.replace('_', ' '), type, { state, track ->
            // Reuse the user's live phone control assignments, not firmware engine tables.
            val assigned = (state.engineTypeStripAssignments[type].orEmpty() + state.engineTypeKnobAssignments[type].orEmpty())
                .filter { it.targetType == 1 }.distinctBy { it.targetId }
            if (assigned.isNotEmpty()) assigned.map { CafaCatalog.Control(it.targetId, it.parameterName) }
            else soundParams(track).map { CafaCatalog.Control(it.id, it.label, it.fallback) }
        }))
    }
    private fun catalogEntry(t: TrackState): CafaCatalog.Entry? {
        val entries = catalog.list()
        return if (t.catalogEntryId == null) entries.firstOrNull { it.id == t.engineType.name && it.backend == t.engineType }
            else entries.firstOrNull { it.id == t.catalogEntryId && it.backend == t.engineType }
    }
    private fun catalogControls(s: GrooveboxState, t: TrackState): List<CafaCatalog.Control?> {
        val entry = catalogEntry(t) ?: return emptyList()
        val controls = entry.controls(s,t)
        val bindings = t.fm1KnobBindings ?: return controls
        // Fixed positions preserve empty knobs and duplicate assignments. The normal banks follow.
        return List(4) { i ->
            bindings.getOrNull(i)?.takeIf { it.isValid() }?.let {
                CafaCatalog.Control(it.parameterId, it.label, it.defaultValue)
            }
        } + controls
    }
    private fun heldLockControls(s: GrooveboxState, t: TrackState): List<ParameterBanks.Control?> {
        val descriptors = t.fm1StepKnobBindings?.let { bindings -> List(4) { index ->
            bindings.getOrNull(index)?.takeIf { it.isValid() }?.let { binding ->
                CafaCatalog.Control(binding.parameterId, binding.label, binding.defaultValue).descriptor
            }
        } } ?: (heldDefaultControls ?: List(4) { index -> catalogControls(s, t).getOrNull(index)?.descriptor })
        return descriptors.map { descriptor -> descriptor?.let {
            ParameterBanks.Control(it, t.parameters[it.id.toIntOrNull()] ?: it.fallback)
        } }
    }
    private fun soundIdentity(s: GrooveboxState, t: TrackState) = SoundIdentity(
        t.id, t.engineType, t.catalogEntryId, catalog.generation, t.selectedFmPreset,
        t.selectedFmDrumInstrument, t.soundFontPath, t.soundFontPresetIndex,
        t.soundFontPresetName, t.lastSamplePath, t.activeWavetableName,
        t.fm1KnobBindings, catalogEntry(t)?.controls?.invoke(s, t).orEmpty().map { it.descriptor }
    )
    private fun captureSoundControls() {
        val s = read(); val t = s.tracks.getOrNull(s.selectedTrackIndex) ?: return
        val identity = soundIdentity(s, t)
        if (heldSoundIdentity != null && heldSoundIdentity != identity) soundBanks.resetBank()
        heldDefaultControls = when (page) {
            SOUND -> soundBanks.page().controls.map { it?.parameter }
            ENV -> List(4) { index -> params(t).getOrNull(index)?.let { CafaCatalog.Control(it.id, it.label, it.fallback).descriptor } }
            else -> heldDefaultControls
        }
        heldSoundIdentity = identity
    }
    private fun syncCatalog(s: GrooveboxState, t: TrackState) {
        val controls = catalogControls(s,t)
        if (lastKnobBindings != t.fm1KnobBindings) {
            soundBanks.resetBank()
            lastKnobBindings = t.fm1KnobBindings
        }
        val soundContext = soundBanks.page().context
        val context = "${s.selectedTrackIndex}:$page:$mixBank:${t.engineType}:${t.catalogEntryId}:${soundContext.bank}:${catalog.generation}:${controls}"
        if (context != catalogContext || (page == SOUND && soundContext != knownSoundContext)) {
            catalogContext = context; catalogEpoch = (catalogEpoch + 1) and 16383
        }
        knownSoundContext = soundContext
    }
    private fun browseEngine(delta: Int) {
        val s = state(); val t = s.tracks[s.selectedTrackIndex]; val entries = catalog.list()
        val next = entries[(entries.indexOf(catalogEntry(t)) + delta).coerceIn(0, entries.lastIndex)]
        releaseHeldNotes()
        sequenceEditor.cancel()
        engine.selectEngine(s.selectedTrackIndex, next.backend); next.activate(s.selectedTrackIndex)
        soundBanks.resetBank()
        heldDefaultControls = null; heldSoundIdentity = null
        replaceTrack(s, t.copy(engineType = next.backend, catalogEntryId = next.id,
            fm1KnobBindings = null, fm1StepKnobBindings = null))
    }
    private var buttons = 0
    private var keys = 0L
    private var lastSerial = 0
    private var first = true
    private val heldTracks = IntArray(27)
    private val heldNotes = IntArray(27) { -1 }
    private fun releaseHeldNotes() {
        for (k in heldNotes.indices) if (heldNotes[k] >= 0) {
            engine.noteOff(heldTracks[k], heldNotes[k]); heldNotes[k] = -1
        }
    }
    private var lastTab = -1
    private var lastTrack = -1
    private var lastTrackId: Int? = null
    var lfoIndex = 0; private set
    var fxIndex = 0; private set
    var arpPage = 0; private set
    var page = HOME; private set
    var octave = 0; private set
    var note = 60; private set

    override fun reset() {
        transportDispatcher.end(); transportSessionActive = false
        partsDispatcher.end(); partsSessionActive = false
        sequenceEditor.cancel()
        sequenceDispatcher.end(); sequenceSessionActive = false
        releaseHeldNotes()
        heldNotes.fill(-1); buttons = 0; keys = 0; lastSerial = 0; first = true
        knownSoundContext = null; knownPartsContext = null; mixBank = 0
        knownSequenceContext = null; knownSequencerBank = null
        homeUsedAsShift = false; staleSequenceCancelled = false
        heldDefaultControls = null; heldSoundIdentity = null
    }
    fun stop() {
        try {
            // A bad imported tempo cannot prevent transport and held-note teardown.
            reportTransport(performTransport(transportAdapter.target, 0, TransportIntent.Stop))
        } finally {
            reset()
            write(read().copy(heldNotes = emptySet()))
        }
    }
    private fun state(): GrooveboxState {
        val s = read()
        require(s.selectedTrackIndex in 0..7 && s.selectedTrackIndex in s.tracks.indices)
        if (lastTrack != s.selectedTrackIndex || lastTrackId != s.tracks[s.selectedTrackIndex].id) {
            releaseHeldNotes()
            soundBanks.resetBank()
            sequenceEditor.cancel(); heldDefaultControls = null; heldSoundIdentity = null
            knownSequencerBank = s.currentSequencerBank
            lastTrack = s.selectedTrackIndex; lastTrackId = s.tracks[s.selectedTrackIndex].id
        }
        if (s.selectedTab != lastTab) {
            if (page == SOUND || page == ENV) captureSoundControls()
            sequenceEditor.cancel()
            page = when (s.selectedTab) { 1 -> SOUND; 2 -> SEQ; 3 -> FX; 4 -> LFO; else -> HOME }
            lastTab = s.selectedTab
        }
        val t = s.tracks[s.selectedTrackIndex]
        if (knownSequencerBank != null && knownSequencerBank != s.currentSequencerBank && page == SEQ)
            sequenceEditor.cancel()
        knownSequencerBank = s.currentSequencerBank
        val soundIdentity = soundIdentity(s, t)
        if (heldSoundIdentity != soundIdentity) {
            heldDefaultControls = null
            heldSoundIdentity = soundIdentity
        }
        syncCatalog(s, t)
        val currentPartsContext = parts.state().context
        if (knownPartsContext != null && currentPartsContext != knownPartsContext)
            catalogEpoch = (catalogEpoch + 1) and 16383
        knownPartsContext = currentPartsContext
        if (page == SEQ) {
            val sequenceContext = sequenceEditor.view().context
            if (knownSequenceContext != null && sequenceContext != knownSequenceContext)
                catalogEpoch = (catalogEpoch + 1) and 16383
            knownSequenceContext = sequenceContext
        } else knownSequenceContext = null
        return s
    }
    fun choosePage(value: Int) {
        if (page == SOUND || page == ENV) captureSoundControls()
        if (page == SEQ) sequenceEditor.cancel()
        page = value.coerceIn(HOME, PATTERN)
        if (page != MIX) mixBank = 0
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

    override fun input(serial: Int, payload: ByteArray): Boolean {
        if (payload.size != 22 || payload.any { it.toInt() < 0 }) return false
        if (!first && serial == lastSerial) return true
        if (serial != ((lastSerial + 1) and 16383)) return false
        val nextButtons = Protocol.u14(payload, 0)
        val nextKeys = Protocol.u28(payload, 2)
        if ((nextKeys and 0x7ffffffL.inv()) != 0L) return false
        val initial = first
        var pressed = nextButtons and buttons.inv()
        val releasedButtons = buttons and nextButtons.inv()
        var down = nextKeys and keys.inv()
        val up = keys and nextKeys.inv()
        buttons = nextButtons; keys = nextKeys; lastSerial = serial
        if (initial) { first = false; pressed = 0; down = 0 }
        state() // Cancel pending gestures if the touchscreen changed track/page.
        val staleContext = Protocol.u14(payload,20) != catalogEpoch
        for (k in 0..26) if ((up and (1L shl k)) != 0L) {
            if (heldNotes[k] >= 0) {
                engine.noteOff(heldTracks[k], heldNotes[k]); heldNotes[k] = -1
            }
        }
        if (staleContext) {
            if (!staleSequenceCancelled) sequenceEditor.cancelGestures()
            staleSequenceCancelled = true
            return true
        }
        staleSequenceCancelled = false
        val inputEpoch = catalogEpoch
        if ((pressed and (1 shl 6)) != 0) homeUsedAsShift = false
        val shiftHeld = (buttons and (1 shl 6)) != 0
        val presetsDelta = if (initial) 0 else Protocol.u14(payload, 10) - 8192
        if (shiftHeld && page == SEQ && presetsDelta != 0) homeUsedAsShift = true
        val editPressed = page == SEQ && (pressed and (1 shl 4)) != 0 &&
            sequenceEditor.view().let { it.stepEntry || it.holdingSteps }
        if (page != SEQ && (pressed and (1 shl 6)) != 0) choosePage(HOME)
        for ((bit, target) in listOf(0 to FX, 1 to SOUND, 2 to ENV, 3 to LFO,
                4 to SOUND, 5 to MIX, 8 to ARP, 9 to SEQ)) {
            if ((pressed and (1 shl bit)) != 0 && !(bit == 4 && editPressed)) {
                if (target == SEQ && page == SEQ) choosePage(PATTERN) else choosePage(target)
            }
        }
        if ((releasedButtons and (1 shl 6)) != 0 && page == SEQ && !homeUsedAsShift) choosePage(HOME)
        if ((pressed and (1 shl 7)) != 0) {
            saveFeedback = if (save()) "SAVED" else "SAVE FAILED"
            saveFeedbackUntil = System.nanoTime() + 2_000_000_000L
            notice(saveFeedback)
        }
        if ((pressed and (1 shl 10)) != 0) {
            val current = transport.state()
            if (current.playing) reportTransport(transport.stop())
            else if (page == SEQ && sequenceEditor.view().stepEntry) showSequenceMessage("LIVE REC UNAVAILABLE")
            else reportTransport(transport.start(current.context))
        }
        if ((pressed and (1 shl 11)) != 0 && page != SEQ) {
            notice("FM1 REC is unavailable in this version: complete recorded-step synchronization is not implemented.")
        }
        if ((pressed and (1 shl 12)) != 0) octave = (octave - 1).coerceIn(-3, 3)
        if ((pressed and (1 shl 13)) != 0) octave = (octave + 1).coerceIn(-3, 3)
        // Track changes precede new key presses in the same input snapshot.
        val trackDelta = if (initial) 0 else Protocol.u14(payload, 8) - 8192
        if (trackDelta != 0) {
            sequenceEditor.cancel()
            val expected = knownPartsContext ?: parts.state().context
            reportParts(parts.select(trackDelta, expected))
        }
        state()
        if (catalogEpoch != inputEpoch) down = 0L
        if (page != SEQ) for (k in 0..26) if ((down and (1L shl k)) != 0L) {
                val s = state(); val t = s.tracks[s.selectedTrackIndex]
                val white = WHITE_KEYS.indexOf(k)
                if (multi(t) && white !in 0..lastLane(t)) continue
                val pitch = if (multi(t)) 60 + white else (53 + k + octave * 12).coerceIn(0, 127)
                note = pitch; heldTracks[k] = s.selectedTrackIndex; heldNotes[k] = pitch
                engine.noteOn(s.selectedTrackIndex, pitch)
                write(read().copy(lastMidiNote = pitch, lastMidiVelocity = 100))
        }
        val sequenceKnobs = IntArray(4)
        var sequenceSelect = 0
        var sequencePresets = 0
        if (!initial) for (e in 0..6) {
            if (e == 1) continue
            val d = Protocol.u14(payload, 6 + e * 2) - 8192
            if (d == 0) continue
            if (e == 0 && (buttons and (1 shl 5)) != 0) { tempo(d); continue }
            if (catalogEpoch != inputEpoch) continue
            val s = state(); val t = s.tracks[s.selectedTrackIndex]
            when (e) {
                0 -> when (page) {
                    SEQ -> sequenceSelect = d
                    PATTERN -> choosePage(if (d > 0) PATTERN else SEQ)
                    LFO -> lfoIndex = (lfoIndex + d).coerceIn(0, s.lfos.lastIndex)
                    FX -> fxIndex = (fxIndex + d).coerceIn(0, minOf(t.fxSends.lastIndex, t.fxMix.lastIndex, s.fxEnabled.lastIndex))
                    ARP -> arpPage = (arpPage + d).coerceIn(0, 1)
                    SOUND -> { soundBanks.selectBank(d) }
                    MIX -> { mixBank = (mixBank + d).coerceIn(0, 1); state() }
                    else -> Unit // SELECT stays within the physical button's family.
                }
                2 -> if (catalogEpoch != inputEpoch) {
                    // Navigation in this scan invalidates its old-context PRESET intent.
                } else if (page == SEQ) {
                    sequencePresets = d
                } else if (page == SOUND && catalogEpoch == inputEpoch) { browseEngine(d)
                } else if (multi(t)) {
                    val selected = (t.selectedFmDrumInstrument + d).coerceIn(0, lastLane(t))
                    engine.lane(s.selectedTrackIndex, selected)
                    replaceTrack(s, t.copy(selectedFmDrumInstrument = selected))
                } else notice("Preset loading remains on the phone; existing sounds are unchanged.")
                else -> { state(); if (catalogEpoch == inputEpoch) {
                    if (page == SEQ) sequenceKnobs[e - 3] = d else knob(e - 3, d)
                } }
            }
        }
        if (page == SEQ && catalogEpoch == inputEpoch) {
            val view = sequenceEditor.scan(SequenceScan(down, up, nextKeys, sequenceKnobs,
                sequencePresets, sequenceSelect, shiftHeld,
                (pressed and (1 shl 11)) != 0, editPressed, octave,
                sequenceEditor.view().context))
            if (view.message.isNotEmpty() && view.message != sequenceMessage) showSequenceMessage(view.message)
            if (view.message.isEmpty()) sequenceMessage = ""
            val current = read()
            if (current.currentSequencerBank != view.bank) {
                knownSequencerBank = view.bank
                write(current.copy(currentSequencerBank = view.bank))
            }
            state()
        }
        return true
    }
    private fun showSequenceMessage(message: String) {
        sequenceMessage = message
        saveFeedback = message
        saveFeedbackUntil = System.nanoTime() + 2_000_000_000L
        notice(message)
    }
    private fun tempo(delta: Int) {
        val current = transport.state()
        reportTransport(transport.adjustTempo(delta, current.context))
    }
    private fun reportTransport(result: OperationResult) {
        if (result.code != ResultCode.COMPLETED) notice("FM1 transport: ${result.message.ifEmpty { result.code.name }}")
    }
    private fun reportParts(result: OperationResult) {
        if (result.code != ResultCode.COMPLETED) notice("FM1 mix: ${result.message.ifEmpty { result.code.name }}")
        else knownPartsContext = parts.state().context
    }
    private fun replaceTrack(s: GrooveboxState, t: TrackState) = write(s.copy(tracks = s.tracks.mapIndexed { i, old -> if (i == s.selectedTrackIndex) t else old }))
    private data class Param(val id: Int, val label: String, val fallback: Float = 0f)
    private fun params(t: TrackState): List<Param> {
        if (page == ENV) return when (t.engineType) {
            EngineType.SUBTRACTIVE, EngineType.FM, EngineType.AUDIO_IN, EngineType.SOUNDFONT -> listOf(100, 101, 102, 103)
            EngineType.SAMPLER -> listOf(310, 311, 312, 313)
            EngineType.GRANULAR -> listOf(425, 426, 427, 428)
            EngineType.WAVETABLE -> listOf(454, 455, 456, 457)
            else -> emptyList()
        }.mapIndexed { i, id -> Param(id, listOf("ATK %", "DEC %", "SUS %", "REL %")[i], listOf(.01f, .5f, 1f, .2f)[i]) }
        return soundParams(t)
    }
    private fun soundParams(t: TrackState): List<Param> = when (t.engineType) {
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
    fun knob(index: Int, delta: Int) {
        if (index !in 0..3 || delta == 0) return
        val s = state(); val i = s.selectedTrackIndex; val t = s.tracks[i]
        if (page == SEQ) {
            val deltas = IntArray(4); deltas[index] = delta
            val view = sequenceEditor.scan(SequenceScan(keysHeld = keys, knobs = deltas,
                octave = octave, expectedContext = sequenceEditor.view().context))
            if (view.message.isNotEmpty() && view.message != sequenceMessage) showSequenceMessage(view.message)
            state()
            return
        }
        when (page) {
            PATTERN -> when (index) {
                0 -> changeLength(s, t, delta)
                1 -> tempo(delta)
                2 -> { val v = (s.swing + delta / 100f).coerceIn(-.23f, .23f); engine.swing(v); write(s.copy(swing = v)) }
                3 -> { val v = (t.humanize + delta / 100f).coerceIn(0f, 1f); engine.humanize(i, v); replaceTrack(s, t.copy(humanize = v)) }
            }
            MIX, HOME -> if (index == 3 && (page == HOME || mixBank == 0)) tempo(delta)
                else {
                    val expected = knownPartsContext ?: parts.state().context
                    if (index == 0) {
                        val current = parts.state()
                        if (current.context != expected) reportParts(OperationResult(ResultCode.STALE))
                        else {
                            val value = (t.volume + delta / 100f).coerceIn(0f, 1f)
                            reportParts(performParts(current.selected, current.revision, PartsIntent.SetLevel(value)))
                        }
                    } else reportParts(parts.adjust(index, delta, expected))
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
                1 -> {
                    val v = (t.fxSends[fxIndex] + delta / 100f).coerceIn(0f, 1f)
                    engine.parameter(i, 2000 + fxIndex * 10, v)
                    replaceTrack(s, t.copy(fxSends = t.fxSends.mapIndexed { n, x -> if (n == fxIndex) v else x }))
                }
                2 -> notice("Loom's DSP fixes FX mix at unity. SEND controls the wet contribution.")
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
            SOUND -> {
                val entry = catalogEntry(t)
                if (entry == null) { notice("Missing catalog entry ${t.catalogEntryId}"); return }
                val expected = knownSoundContext ?: return
                when (soundBanks.adjust(index, delta, expected)) {
                    // Accepted relative turns can continue before the next display refresh.
                    // A phone edit still changes the revision observed by syncCatalog.
                    ParameterBanks.Result.COMPLETED -> knownSoundContext = soundBanks.page().context
                    ParameterBanks.Result.UNSUPPORTED -> Unit
                    ParameterBanks.Result.STALE -> notice("Sound changed; refresh the controls and retry.")
                    else -> notice("Sound parameter edit was not accepted.")
                }
            }
            ENV -> {
                if (catalogEntry(t) == null) { notice("Missing catalog entry ${t.catalogEntryId}"); return }
                val p = params(t).getOrNull(index)
                if (p == null) { notice("This engine's controls remain available on the phone."); return }
                val v = ((t.parameters[p.id] ?: p.fallback) + delta / 100f).coerceIn(0f, 1f)
                engine.parameter(i, p.id, v); replaceTrack(s, t.copy(parameters = t.parameters + (p.id to v)))
            }
        }
    }
    private fun changeLength(s: GrooveboxState, t: TrackState, delta: Int) {
        val length = (t.patternLength + delta).coerceIn(4, 64)
        sequenceEditor.cancel()
        engine.length(s.selectedTrackIndex, length)
        replaceTrack(s.copy(currentSequencerBank = s.currentSequencerBank.coerceIn(0, (length - 1) / 16)),
            t.copy(patternLength = length, numPages = 1, stepsPerPage = length))
    }
    override fun snapshot(): ByteArray {
        val s = state(); val t = s.tracks[s.selectedTrackIndex]
        val sequenceView = if (page == SEQ) sequenceEditor.view() else null
        lfoIndex = lfoIndex.coerceIn(0, s.lfos.lastIndex)
        fxIndex = fxIndex.coerceIn(0, minOf(t.fxSends.lastIndex, t.fxMix.lastIndex, s.fxEnabled.lastIndex))
        require(s.tempo.isFinite() && s.tempo in 12f..300f) { "Tempo outside CAFA v5 range" }
        val length = t.patternLength.coerceIn(1, 64); val start = (sequenceView?.bank ?: bank(s, t)) * 16
        val head = if (s.isPlaying) engine.playhead(s.selectedTrackIndex, lane(t)).takeIf { it in 0 until length } ?: Protocol.NONE else Protocol.NONE
        val b = ByteArray(156)
        b[0] = s.selectedTrackIndex.toByte(); b[1] = page.toByte()
        b[2] = ((if (s.isPlaying) 1 else 0) or (if (s.isRecording || sequenceView?.stepEntry == true) 2 else 0) or (if (t.isMuted) 4 else 0) or ((octave + 3) shl 3)).toByte()
        Protocol.put14(b, 3, length); Protocol.put14(b, 5, start); Protocol.put14(b, 7, head); Protocol.put14(b, 9, s.tempo.roundToInt().coerceIn(12, 300))
        var mask = 0; var leds = keys
        for (i in 0..15) if (start + i < length) {
            if (steps(t)[start + i].active) mask = mask or (1 shl i)
            if (page == SEQ && (steps(t)[start + i].active || head == start + i)) leds = leds or (1L shl WHITE_KEYS[i])
        }
        b[11] = (mask and 127).toByte(); b[12] = ((mask ushr 7) and 127).toByte(); b[13] = ((mask ushr 14) and 3).toByte()
        val pageLed = intArrayOf(6, 9, 4, 2, 0, 5, 3, 8, 9)[page]
        Protocol.put14(b, 14, (1 shl pageLed) or (if (s.isPlaying) 1 shl 10 else 0) or (if (s.isRecording || sequenceView?.stepEntry == true) 1 shl 11 else 0))
        Protocol.put28(b, 16, leds)
        Protocol.text(b, 20, 16, sequenceView?.title ?: ("LOOM T${s.selectedTrackIndex + 1}" + if (multi(t)) " ROW${lane(t) + 1}" else " OCT${if (octave >= 0) "+" else ""}$octave"))
        Protocol.text(b, 36, 16, if (saveFeedback.isNotEmpty() && System.nanoTime() - saveFeedbackUntil < 0) saveFeedback else if (page == ENV && catalogEntry(t) == null) "MISSING ENTRY" else when (page) {
            SEQ -> sequenceView?.selectionText ?: ""
            LFO -> "LFO ${lfoIndex + 1} ${s.lfos[lfoIndex].targetLabel}"
            FX -> "LOOM FX ${fxIndex + 1}"
            ARP -> "ARP ${arpPage + 1} ${t.arpConfig.mode.name}"
            else -> t.engineType.name.replace('_', ' ')
        })
        val values: List<Pair<String, Int>> = when (page) {
            SEQ -> sequenceView?.controls.orEmpty().map { control ->
                val raw = control.rawValue
                if (!control.available || raw == null) "UNAVAIL" to 0
                else when (control.format) {
                    SequenceValueFormat.PERCENT -> (control.label.take(6) + " %") to (raw * 100).roundToInt()
                    SequenceValueFormat.GATE_STEPS -> "LEN x100" to (raw * 100).roundToInt()
                    SequenceValueFormat.STEP_POSITION -> "POS x100" to (raw * 100).roundToInt()
                    SequenceValueFormat.BOOLEAN -> control.label to (if (raw >= .5f) 1 else 0)
                    SequenceValueFormat.NUMBER, SequenceValueFormat.PITCH -> control.label to raw.roundToInt()
                }
            }
            HOME, MIX -> listOf("VOL %" to (t.volume * 100).roundToInt(), "PAN %" to (t.pan * 100).roundToInt(),
                "MUTE" to if (t.isMuted) 1 else 0,
                (if (page == MIX && mixBank == 1) "SOLO" else "BPM") to
                    (if (page == MIX && mixBank == 1) { if (t.isSoloed) 1 else 0 } else s.tempo.roundToInt()))
            SOUND, ENV -> params(t).map { it.label to ((t.parameters[it.id] ?: it.fallback) * 100).roundToInt() }.ifEmpty { listOf((if (page == ENV) "NO ADSR" else "ON PHONE") to 0) }
            PATTERN -> listOf("LENGTH" to length, "BPM" to s.tempo.roundToInt(),
                "SWING+23" to ((s.swing + .23f) * 100).roundToInt(), "HUMAN %" to (t.humanize * 100).roundToInt())
            LFO -> s.lfos[lfoIndex].let { listOf("RATE %" to (it.rate * 100).roundToInt(),
                "DEPTH %" to (it.depth * 100).roundToInt(), "SHAPE" to it.shape, "SYNC" to if (it.sync) 1 else 0) }
            FX -> listOf("FX BUS" to fxIndex + 1, "SEND %" to (t.fxSends[fxIndex] * 100).roundToInt(),
                "UNITY %" to 100, "ENABLED" to if (s.fxEnabled[fxIndex]) 1 else 0)
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
        Protocol.put14(b,152,catalogEpoch)
        if (page == SOUND) {
            val entry = catalogEntry(t); val entries = catalog.list(); val soundPage = soundBanks.page()
            val catalogBank = soundPage.context.bank
            b[1] = 9
            Protocol.text(b,20,16,entry?.name ?: "MISSING ENTRY")
            if (saveFeedback.isEmpty() || System.nanoTime() - saveFeedbackUntil >= 0)
                Protocol.text(b,36,16,if (entry == null) "ID ${t.catalogEntryId}" else if (t.fm1KnobBindings != null && catalogBank == 0) "PRESET KNOBS" else "CONTROLS ${catalogBank+1-(if (t.fm1KnobBindings != null) 1 else 0)}")
            Protocol.put14(b,92,catalogBank); Protocol.put14(b,94,soundPage.bankCount)
            Protocol.put14(b,96,if (entry == null) 0 else entries.indexOf(entry)); Protocol.put14(b,98,entries.size)
            for (i in 0..3) {
                val control = soundPage.controls[i]
                Protocol.text(b,52+i*8,8,control?.parameter?.label ?: "")
                Protocol.put14(b,84+i*2,control?.parameter?.normalized(control.value) ?: 0)
                Protocol.text(b,100+i*12,12,control?.parameter?.display(control.value) ?: "")
                b[148+i] = (if (control == null) 0 else if (control.parameter.choices.isEmpty()) 1 else 2).toByte()
            }
        }
        return b
    }
}
