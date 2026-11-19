/* SPDX-License-Identifier: GPL-3.0-only */
package com.groovebox.cafa

import com.groovebox.*
import com.groovebox.presets.FactoryPresets
import com.groovebox.presets.LoomPreset
import com.groovebox.presets.PresetEngine
import org.fm1.companion.control.ParameterBanks
import org.fm1.companion.instrument.OperationDispatcher
import org.fm1.companion.instrument.OperationResult
import org.fm1.companion.instrument.ResultCode
import org.fm1.companion.parts.PartsControls
import org.fm1.companion.parts.PartsIntent
import org.fm1.companion.protocol.NativeUi
import org.fm1.companion.protocol.OpenMidiConnection
import org.fm1.companion.protocol.Wire6
import org.fm1.companion.sequence.SequenceScan
import org.fm1.companion.sequence.SequencePage
import org.fm1.companion.sequence.SkiffSequenceEditor
import org.fm1.companion.transport.TransportControls
import org.fm1.companion.transport.TransportIntent
import kotlin.math.roundToInt
import kotlin.math.exp
import java.util.ArrayDeque

/** LOOM is the sole owner of app navigation and edits in the native Skiff profile. */
class NativeLoomSurface(
    private val read: () -> GrooveboxState,
    private val write: (GrooveboxState) -> Unit,
    private val engine: CafaSurface.Engine,
    private val selectTrack: (Int) -> Unit,
    private val save: () -> Boolean,
    private val loadState: (GrooveboxState) -> Unit,
    private val notice: (String) -> Unit,
    private val applyFactoryPreset: (LoomPreset) -> Unit = {},
    private val storage: NativeLoomStorage = NativeLoomStorage.Device,
    private val refreshRecordedState: () -> Unit = {},
    private val nowNanos: () -> Long = System::nanoTime
) : OpenMidiConnection.Surface {
    companion object {
        private val TITLES = arrayOf("ENV", "ENV 2", "LFO", "LFO 2", "MOD", "FX", "DELAY", "REVERB", "DRIVE", "INSERT", "SLICER", "DUCK", "SCALE", "CHORD", "FOLLOW", "EDIT 1", "EDIT 2", "OPERATOR", "ALL OPS", "LANES", "SOUND", "LANE MIX", "SLICES", "SAMPLES", "OP1 ENV", "OP2 ENV", "OP3 ENV", "OP4 ENV", "OP LEVEL", "VOICE", "VOICE 2", "MIXER", "DELAY", "REVERB", "CHORUS", "GLOBAL", "PRESETS", "USER", "PATTERNS", "PROJECT", "ARP", "ARP 2", "NOTES", "FEEL", "PATTERN", "AUTOMATION", "SONG", "HOME", "SCOPE", "FX LAYER", "GLO LAYER", "SEL LAYER", "EDIT LAYER", "SEQ LAYER", "REC LAYER", "PERFORM", "SHIFT", "DISPLAY", "CONTROL", "AUDIO", "SYSTEM", "MIDI", "ABOUT", "CONFIRM", "NAME", "UNDO", "TAKE", "TAKE PICKER", "COUNT IN", "UPDATE", "CALIBRATION", "RESET SETTINGS")
        private val FAMILIES = arrayOf(
            intArrayOf(5,6,7,8,9,10,11), intArrayOf(12,13,14), intArrayOf(0,1),
            intArrayOf(2,3,4), intArrayOf(15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30),
            intArrayOf(31,32,33,34,35), intArrayOf(47,48), intArrayOf(36,37,38,39),
            intArrayOf(40,41), intArrayOf(42,43,44,45,46))
        private val WHITE = intArrayOf(0,2,4,6,7,9,11,12,14,16,18,19,21,23,24,26)
        private const val NONE = 16383
    }
    private val transportAdapter = LoomTransportAdapter(read, ::commit, engine::playing, engine::recording, engine::tempo, refreshRecordedState)
    private val transportDispatcher = OperationDispatcher(transportAdapter)
    private val transport = TransportControls(transportAdapter) { target, revision, intent ->
        transportDispatcher.submit(transportDispatcher.request(target, revision, intent))
    }
    private val partsAdapter = LoomPartsAdapter(read, ::commit, { index -> selectTrack(index); observe(read()) }, engine::volume,
        engine::pan, engine::mute, engine::solo)
    private val partsDispatcher = OperationDispatcher(partsAdapter)
    private val parts = PartsControls(partsAdapter) { target, revision, intent ->
        partsDispatcher.submit(partsDispatcher.request(target, revision, intent))
    }
    private val sequenceAdapter = LoomSequenceAdapter(read, ::commit,
        { track, lane, index, step -> engine.step(track, index,
            if (lane >= 0) step.copy(notes = listOf(60 + lane)) else step) },
        { lane ->
            val s = read(); val index = s.selectedTrackIndex
            engine.lane(index, lane)
            commit(s.copy(tracks = s.tracks.mapIndexed { i, t ->
                if (i == index) t.copy(selectedFmDrumInstrument = lane) else t }))
        }, { _, track -> List(4) { slot ->
            track.fm1StepKnobBindings?.getOrNull(slot)?.takeIf { it.isValid() }?.let { binding ->
                val descriptor = CafaCatalog.Control(binding.parameterId, binding.label,
                    binding.defaultValue).descriptor
                ParameterBanks.Control(descriptor, track.parameters[binding.parameterId] ?: binding.defaultValue)
            }
        } })
    private val sequenceDispatcher = OperationDispatcher(sequenceAdapter)
    private val editor = SkiffSequenceEditor(sequenceAdapter, { target, revision, intent ->
        sequenceDispatcher.submit(sequenceDispatcher.request(target, revision, intent))
    }) { pitch, _ ->
        val track = read().selectedTrackIndex
        engine.noteOn(track, pitch)
        val release: () -> Unit = { engine.noteOff(track, pitch) }
        release
    }
    private var screen = 47
    private val familyPage = IntArray(FAMILIES.size)
    private var layer: Int? = null
    private var modal: Int? = null
    private var modalReturn = 47
    private var modalChoice = 0
    private var buttons = 0
    private var keys = 0L
    private var serial = -1
    private var context = 1
    private var publishedContext = 0
    private var first = true
    private var accepted = false
    private var deviceMenu = false
    private var menuWasOpen = false
    private var menuRequested = false
    private var frozenMenuView: ByteArray? = null
    private var homePress = 0L
    private var homeUsedAsShift = false
    private var savePress = 0L
    private val pressTimes = LongArray(12)
    private var recPressScreen = -1
    private var recPressTrack = -1
    private var recPressShift = false
    private var lockedLayer: Int? = null
    private var octave = 0
    private var row = 0
    private var firstRow = 0
    private var fx = 0
    private var lfo = 0
    private var fmSheetWindow = 0
    private data class SoundControl(val id: Int, val label: String, val default: Float)
    private fun soundControls(t: TrackState, page: Int): List<SoundControl> {
        val lane=lane(t)
        val banks=when(t.engineType) {
            EngineType.SUBTRACTIVE -> listOf(
                listOf(SoundControl(104,"OSC SHAPE",0f),SoundControl(107,"OSC LEVEL",.7f),
                    SoundControl(112,"CUTOFF",1f),SoundControl(113,"RESON",0f)),
                listOf(SoundControl(105,"OSC2 SHAPE",.5f),SoundControl(108,"OSC2 LEVEL",.4f),
                    SoundControl(109,"SUB LEVEL",.4f),SoundControl(118,"ENV AMT",0f)))
            EngineType.FM -> listOf(
                listOf(SoundControl(150,"ALGORITHM",0f),SoundControl(151,"FILTER",1f),
                    SoundControl(157,"BRIGHT",.5f),SoundControl(160,"OP1 LEVEL",.25f)),
                listOf(SoundControl(154,"FEEDBACK",0f),SoundControl(165,"OP1 RATIO",.0625f),
                    SoundControl(166,"OP2 LEVEL",.25f),SoundControl(171,"OP2 RATIO",.0625f)))
            EngineType.FM_DRUM -> listOf(
                listOf(SoundControl(200+lane*10,"PITCH",.5f),SoundControl(201+lane*10,"CLICK",.1f),
                    SoundControl(202+lane*10,"DECAY",.3f),SoundControl(205+lane*10,"LEVEL",.8f)),
                listOf(SoundControl(204+lane*10,"PUNCH",0f)))
            EngineType.ANALOG_DRUM -> listOf(
                listOf(SoundControl(600+lane*10,"DECAY",.5f),SoundControl(601+lane*10,"TONE",.8f),
                    SoundControl(602+lane*10,"TUNE",.5f),SoundControl(605+lane*10,"GAIN",.8f)),
                listOf(SoundControl(603+lane*10,"COLOR A",0f),SoundControl(604+lane*10,"COLOR B",0f)))
            EngineType.SAMPLER -> listOf(
                listOf(SoundControl(300,"PITCH",.5f),SoundControl(302,"SPEED",.5f),
                    SoundControl(303,"CUTOFF",1f),SoundControl(304,"RESON",0f)),
                listOf(SoundControl(310,"ATTACK",.01f),SoundControl(311,"DECAY",.5f),
                    SoundControl(312,"SUSTAIN",1f),SoundControl(313,"RELEASE",.2f)))
            EngineType.GRANULAR -> listOf(
                listOf(SoundControl(400,"POSITION",.5f),SoundControl(406,"SIZE",.2f),
                    SoundControl(407,"DENSITY",.5f),SoundControl(415,"SPRAY",0f)),
                listOf(SoundControl(401,"SPEED",.5f),SoundControl(410,"PITCH",.5f),
                    SoundControl(411,"JITTER",0f),SoundControl(420,"REVERSE",0f)))
            EngineType.WAVETABLE -> listOf(
                listOf(SoundControl(450,"WAVE",0f),SoundControl(458,"CUTOFF",1f),
                    SoundControl(459,"RESON",0f),SoundControl(451,"DETUNE",.5f)),
                listOf(SoundControl(454,"ATTACK",.01f),SoundControl(455,"DECAY",.5f),
                    SoundControl(456,"SUSTAIN",1f),SoundControl(457,"RELEASE",.2f)))
            EngineType.AUDIO_IN -> listOf(listOf(SoundControl(121,"GAIN",.8f),
                SoundControl(122,"FOLD",0f),SoundControl(112,"CUTOFF",1f),
                SoundControl(113,"RESON",0f)),emptyList())
            EngineType.SOUNDFONT -> listOf(
                listOf(SoundControl(1,"CUTOFF",1f),SoundControl(2,"RESON",0f),
                    SoundControl(100,"ATTACK",.01f),SoundControl(103,"RELEASE",.2f)),
                listOf(SoundControl(101,"DECAY",.5f),SoundControl(102,"SUSTAIN",.8f)))
            EngineType.MIDI -> listOf(emptyList(),emptyList())
        }
        val bindings=t.fm1KnobBindings
        if(page!=16 && bindings!=null) return List(4) { i ->
            bindings.getOrNull(i)?.takeIf { it.isValid() }?.let {
                SoundControl(it.parameterId,it.label,it.defaultValue)
            } ?: SoundControl(-1,"",0f)
        }
        return banks[if(page==16) 1 else 0]
    }
    private var heldTracks = IntArray(27)
    private var heldNotes = IntArray(27) { -1 }
    private var feedback = ""
    private var feedbackUntil = 0L
    private var observedTracks: List<TrackState>? = null
    private var observedTrackIndex = -1
    private var observedBank = -1
    private var observedTempo = Float.NaN
    private var observedScale: ScaleType? = null
    private var observedRoot = -1
    private var pendingAction: (() -> Unit)? = null
    private var nameBuffer = ""
    private var nameCursor = 0
    private var nameNumeric = false
    private var nameTapKey = -1
    private var nameTapIndex = 0
    private var nameTapAt = 0L
    private var nameAction: ((String) -> Unit)? = null
    private val heldFx = HashMap<Int, Pair<Int, Boolean>>()
    private val heldSolo = HashMap<Int, Pair<Int, Boolean>>()
    private val blackHeld = HashMap<Int, Long>()
    private val taps = ArrayDeque<Long>()
    private var localNotesContext = 0
    private var localNotesTrack = -1
    private var localNotesTrackId = -1
    private var localNotesScreen = -1

    private fun retireLocalNotesContext() {
        localNotesContext = 0; localNotesTrack = -1
        localNotesTrackId = -1; localNotesScreen = -1
    }

    init { transportDispatcher.begin(); partsDispatcher.begin(); sequenceDispatcher.begin() }
    private fun invalidate() { context = if (context >= 0x0fffffff) 1 else context + 1 }
    override fun capabilitiesAccepted(accepted: Set<Int>) {
        check(Wire6.NATIVE_UI in accepted && Wire6.INSTRUMENT in accepted &&
            Wire6.TRANSPORT in accepted && Wire6.PARTS in accepted)
        this.accepted = true; invalidate()
    }
    override fun acknowledged(context: Int) { publishedContext = context }
    override fun reset() {
        retireLocalNotesContext()
        releaseVoices(); editor.cancel()
        transportDispatcher.end(); partsDispatcher.end(); sequenceDispatcher.end()
        transportDispatcher.begin(); partsDispatcher.begin(); sequenceDispatcher.begin()
        accepted = false; buttons = 0; keys = 0; serial = -1; first = true
        deviceMenu = false; menuWasOpen = false; menuRequested = false; frozenMenuView = null
        modal = null; layer = null; lockedLayer = null; pressTimes.fill(0L)
        publishedContext = 0; invalidate()
        observedTracks = null; observedTrackIndex = -1; observedBank = -1
        observedTempo = Float.NaN; observedScale = null; observedRoot = -1
    }
    fun stop() {
        runCatching { transport.stop() }
        reset(); commit(read().copy(heldNotes = emptySet()))
    }
    private fun releaseVoices() {
        for (i in heldNotes.indices) if (heldNotes[i] >= 0) {
            engine.noteOff(heldTracks[i], heldNotes[i]); heldNotes[i] = -1
        }
        for ((_,effect) in heldFx) {
            engine.fxEnabled(effect.first,effect.second)
            val s=read(); commit(s.copy(fxEnabled=s.fxEnabled.mapIndexed { i,v ->
                if(i==effect.first) effect.second else v }))
        }
        heldFx.clear()
        for ((_,solo) in heldSolo) {
            engine.solo(solo.first,solo.second)
            val s=read(); commit(s.copy(tracks=s.tracks.mapIndexed { i,t ->
                if(i==solo.first) t.copy(isSoloed=solo.second) else t }))
        }
        heldSolo.clear(); blackHeld.clear()
    }
    private fun observe(s: GrooveboxState) {
        observedTracks=s.tracks; observedTrackIndex=s.selectedTrackIndex
        observedBank=s.currentSequencerBank; observedTempo=s.tempo
        observedScale=s.scaleType; observedRoot=s.rootNote
    }
    private fun commit(s: GrooveboxState) {
        write(s)
        observe(read())
    }
    private fun applyLoaded(s: GrooveboxState) {
        loadState(s)
        observe(read())
        invalidate()
    }
    private fun blackPlace(key: Int): Int {
        if (key in WHITE) return -1
        return (0 until key).count { it !in WHITE }
    }
    private fun releaseLayerKeys(up: Long, now: Long) {
        for(key in 0..26) if(up and (1L shl key)!=0L) {
            heldFx.remove(key)?.let { (effect,old) ->
                val s=read(); engine.fxEnabled(effect,old)
                commit(s.copy(fxEnabled=s.fxEnabled.mapIndexed { i,v -> if(i==effect) old else v }))
                invalidate()
            }
            heldSolo.remove(key)?.let { (track,old) ->
                val s=read(); engine.solo(track,old)
                commit(s.copy(tracks=s.tracks.mapIndexed { i,t -> if(i==track) t.copy(isSoloed=old) else t }))
                invalidate()
            }
            val started=blackHeld.remove(key)
            if(started!=null && now-started<450_000_000L) {
                val track=blackPlace(key)
                val s=read()
                if(track in s.tracks.indices) {
                    val mute=!s.tracks[track].isMuted
                    engine.mute(track,mute)
                    commit(s.copy(tracks=s.tracks.mapIndexed { i,t ->
                        if(i==track) t.copy(isMuted=mute) else t }))
                    invalidate()
                }
            }
        }
    }
    /** Retire the last drawn edit target before applying a scan after a phone edit. */
    private fun reconcileModel(s: GrooveboxState) {
        val previous=observedTracks
        val changed=previous != null && (musicalTracksChanged(previous,s.tracks,s.isRecording) ||
            observedTrackIndex != s.selectedTrackIndex || observedBank != s.currentSequencerBank ||
            observedTempo != s.tempo || observedScale != s.scaleType || observedRoot != s.rootNote)
        if (changed) {
            retireLocalNotesContext()
            if (observedTrackIndex != s.selectedTrackIndex ||
                observedTracks?.getOrNull(s.selectedTrackIndex)?.id != s.tracks.getOrNull(s.selectedTrackIndex)?.id ||
                observedBank != s.currentSequencerBank) releaseVoices()
            editor.cancel(); invalidate()
            modal=null; pendingAction=null; nameAction=null
        }
        observe(read())
    }
    private fun musicalTracksChanged(previous: List<TrackState>, current: List<TrackState>, recording: Boolean): Boolean {
        if (previous === current) return false
        if (previous.size != current.size) return true
        return previous.indices.any { index ->
            val old=previous[index]
            val next=current[index]
            // The activity light can change on every note without changing a knob's target.
            old !== next && old.copy(isAudiblyActive=next.isAudiblyActive,
                steps=if(recording) next.steps else old.steps,
                drumSteps=if(recording) next.drumSteps else old.drumSteps) != next
        }
    }
    private fun drum(track: TrackState) = track.engineType == EngineType.FM_DRUM ||
        track.engineType == EngineType.ANALOG_DRUM ||
        track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) >= .5f
    private fun lane(track: TrackState) = if (drum(track))
        track.selectedFmDrumInstrument.coerceIn(0, if (track.engineType == EngineType.SAMPLER) 15 else 7) else -1
    private fun steps(track: TrackState): List<StepState> = if (drum(track))
        track.drumSteps.getOrNull(lane(track)).orEmpty() else track.steps
    private fun familyOf(id: Int) = FAMILIES.indices.firstOrNull { id in FAMILIES[it] } ?: 6
    private fun switchFamily(family: Int) {
        val pages = FAMILIES[family]
        val current = familyPage[family].coerceIn(0, pages.lastIndex)
        val next = if (screen in pages) (pages.indexOf(screen) + 1) % pages.size else current
        changeScreen(pages[next]); familyPage[family] = next
    }
    private fun changeScreen(id: Int) {
        if (id != screen) retireLocalNotesContext()
        val phoneTab=when (NativeUi.familyFor(id)) {
            NativeUi.FX -> 3
            NativeUi.SEL, NativeUi.ENV, NativeUi.EDIT -> 1
            NativeUi.LFO -> 4
            NativeUi.SEQ -> 2
            NativeUi.SAVE -> 5
            NativeUi.GLO, NativeUi.ARP -> 0
            NativeUi.HOME -> if (id == 47 || id == 48) 0 else null
            else -> null
        }
        val s=read()
        if (phoneTab != null && phoneTab != s.selectedTab) commit(s.copy(selectedTab=phoneTab))
        if (screen == id) return
        editor.cancel(); screen = id; row = 0; firstRow = 0; layer = null
        if(id==42 || id==43) {
            val desired=if(id==42) SequencePage.NOTES else SequencePage.FEEL
            val view=editor.view()
            if(view.page!=desired) editor.scan(SequenceScan(selectDelta=1,
                expectedContext=view.context))
        }
        invalidate()
    }
    private fun flash(message: String) {
        feedback = message; feedbackUntil = nowNanos() + 2_000_000_000L
        notice(message)
    }
    private fun replaceTrack(s: GrooveboxState, track: TrackState) {
        commit(s.copy(tracks = s.tracks.mapIndexed { index, old ->
            if (index == s.selectedTrackIndex) track else old }))
    }
    private fun tempo(delta: Int) {
        val result = transport.adjustTempo(delta, transport.state().context)
        if (result.code != ResultCode.COMPLETED) flash("TEMPO ${result.code}")
    }
    private fun notesScan(down: Long, up: Long, held: Long, knobs: IntArray,
                          select: Int, presets: Int, shift: Boolean, rec: Boolean, edit: Boolean) {
        // Releasing a page button alone has no sequence action and leaves the drawn target valid.
        if (down == 0L && up == 0L && select == 0 && presets == 0 &&
            !rec && !edit && knobs.all { it == 0 }) return
        val view = editor.view()
        val next = editor.scan(SequenceScan(down, up, held, knobs, presets, select,
            shift, rec, edit, octave, view.context))
        if (next.message.isNotEmpty()) flash(next.message)
        val s = read()
        if (s.currentSequencerBank != next.bank) commit(s.copy(currentSequencerBank = next.bank))
        val current = read()
        if (screen in 42..43 && publishedContext != 0 &&
            current.selectedTrackIndex in current.tracks.indices) {
            localNotesContext = publishedContext
            localNotesTrack = current.selectedTrackIndex
            localNotesTrackId = current.tracks[localNotesTrack].id
            localNotesScreen = screen
        }
        invalidate()
    }
    override fun input(serial: Int, scan: ByteArray): Boolean {
        if (!accepted || scan.size != 24 || scan.any { it.toInt() < 0 }) return false
        if (this.serial == serial) return true
        if (this.serial >= 0 && serial != ((this.serial + 1) and 16383)) return false
        this.serial = serial
        val rawButtons = Wire6.u14(scan, 0)
        val nextKeys = Wire6.u28(scan, 2).toLong() and 0x7ffffffL
        val menuFlag = scan[5].toInt() and 64 != 0
        val nextButtons = rawButtons and 0x3fff
        val downButtons = nextButtons and buttons.inv()
        val upButtons = buttons and nextButtons.inv()
        val down = nextKeys and keys.inv()
        val up = keys and nextKeys.inv()
        buttons = nextButtons; keys = nextKeys
        val initial = first; first = false
        val now = nowNanos()
        reconcileModel(read())
        releaseLayerKeys(up,now)
        val recStarted = pressTimes[11]
        if (downButtons and (1 shl 11) != 0) {
            recPressScreen = screen
            recPressTrack = read().selectedTrackIndex
            recPressShift = buttons and (1 shl 6) != 0
        }
        for (bit in 0..11) {
            if (downButtons and (1 shl bit) != 0) pressTimes[bit] = now
            if (upButtons and (1 shl bit) != 0) pressTimes[bit] = 0L
        }
        if (menuFlag) {
            menuRequested = false
            if (!deviceMenu) {
                deviceMenu = true; menuWasOpen = true; releaseVoices(); editor.cancel()
                layer = null; modal = null; frozenMenuView = null; changeScreen(57)
            }
            // Device controls and menu navigation are firmware-owned.
            if (!initial && downButtons and (1 shl 10) != 0) togglePlay()
            return true
        }
        if (deviceMenu) {
            if (menuRequested) return true
            deviceMenu = false; frozenMenuView = null; changeScreen(47)
            // The first non-menu scan retires all old held edges.
            return true
        }
        if (initial) return true
        val scanContext = Wire6.u28(scan, 20)
        val stale = scanContext != publishedContext ||
            publishedContext == 0 || context != publishedContext
        for (key in 0..26) if (up and (1L shl key) != 0L && heldNotes[key] >= 0) {
            engine.noteOff(heldTracks[key], heldNotes[key]); heldNotes[key] = -1
        }
        if (upButtons and (1 shl 6) != 0 && homePress != 0L) {
            if (now - homePress > 450_000_000L && !menuWasOpen && !homeUsedAsShift) {
                deviceMenu = true; menuRequested = true; frozenMenuView = null
                changeScreen(57); menuWasOpen = true
            } else if(screen in 42..43 && !homeUsedAsShift) {
                changeScreen(47)
            } else if (layer == 56) { layer = null; invalidate() }
            homePress = 0L; menuWasOpen = false; homeUsedAsShift=false
        }
        if (upButtons and (1 shl 7) != 0 && savePress != 0L) {
            if (now - savePress > 450_000_000L) showModal(65)
            else if (!stale) changeScreen(37)
            savePress = 0L
        }
        var recHandled = false
        if (upButtons and (1 shl 11) != 0) {
            val shortTap = recStarted != 0L && now - recStarted < 450_000_000L
            val sameTarget = recPressScreen == screen && recPressTrack == read().selectedTrackIndex &&
                !recPressShift
            recPressScreen = -1; recPressTrack = -1; recPressShift = false
            if (layer == 54 && lockedLayer == null) { layer = null; invalidate() }
            if (shortTap && sameTarget && !deviceMenu && modal == null) {
                val s = read()
                if (screen in 42..43 && !s.isPlaying && !drum(s.tracks[s.selectedTrackIndex]))
                    notesScan(0L, 0L, nextKeys, IntArray(4), 0, 0, false, true, false)
                else toggleRecording()
                recHandled = true
            }
        }
        if (stale) {
            if (downButtons and (1 shl 10) != 0 && read().isPlaying) togglePlay()
            val current = read()
            val localNotes = scanContext != 0 && scanContext == localNotesContext &&
                scanContext == publishedContext && screen == localNotesScreen &&
                current.selectedTrackIndex == localNotesTrack &&
                current.tracks.getOrNull(localNotesTrack)?.id == localNotesTrackId
            if (localNotes && (down != 0L || up != 0L))
                notesScan(down, up, nextKeys, IntArray(4), 0, 0, false, false, false)
            else if (screen in 42..43 && up != 0L)
                notesScan(0L, up, nextKeys, IntArray(4), 0, 0, false, false, false)
            if (!recHandled && !localNotes) editor.cancelGestures()
            return true
        }
        val inputContext = context
        if(screen in 42..43) {
            val edit=downButtons and (1 shl 4)!=0
            val shift=buttons and (1 shl 6)!=0
            val presets=Wire6.u14(scan,10)-8192
            val select=Wire6.u14(scan,6)-8192
            val knobs=IntArray(4) { Wire6.u14(scan,12+2*it)-8192 }
            if(downButtons and (1 shl 6)!=0) { homePress=now; homeUsedAsShift=false; return true }
            if(shift && (presets!=0 || edit || knobs.any { it!=0 })) homeUsedAsShift=true
            if(edit && nextKeys == 0L) { switchFamily(4); return true }
            if(edit || shift && (presets!=0 || knobs.any { it!=0 })) {
                notesScan(down,up,nextKeys,knobs,select,presets,shift,
                    false,edit)
                return true
            }
            if(upButtons and (1 shl 6)!=0) return true
        }
        if (layer != null && lockedLayer == null && upButtons != 0) {
            val owner = when(layer) { 49 -> 0; 51 -> 1; 52 -> 4; 50 -> 5;
                55 -> 8; 53 -> 9; 54 -> 11; else -> 6 }
            if (upButtons and (1 shl owner) != 0) { layer = null; invalidate() }
        }
        if (buttons and (1 shl 6) != 0) for ((bit, layerId) in listOf(
            0 to 49, 1 to 51, 4 to 52, 5 to 50, 8 to 55, 9 to 53, 11 to 54)) {
            if (downButtons and (1 shl bit) != 0) {
                lockedLayer = if (lockedLayer == layerId) null else layerId
                layer = lockedLayer; invalidate(); return true
            }
        }
        if (downButtons and (1 shl 6) != 0) { homePress = now; menuWasOpen = false }
        if (downButtons and (1 shl 7) != 0) savePress = now
        if (modal != null) {
            if (modal == 64) {
                if (downButtons and (1 shl 12) != 0) {
                    modal=null; nameAction=null; invalidate(); return true
                }
                if (downButtons and (1 shl 13) != 0) {
                    nameAction?.invoke(nameBuffer.trim()); nameAction=null; modal=null; invalidate(); return true
                }
                val move=Wire6.u14(scan,12)-8192
                val change=Wire6.u14(scan,14)-8192
                if(move!=0) { nameCommit(); nameCursor=(nameCursor+move).coerceIn(0,nameBuffer.length) }
                if(change!=0) {
                    nameCommit()
                    val chars=" ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._/#+"
                    if(nameCursor==nameBuffer.length && nameBuffer.length<12) nameBuffer += " "
                    if(nameCursor<nameBuffer.length) {
                        val current=chars.indexOf(nameBuffer[nameCursor]).coerceAtLeast(0)
                        nameBuffer=nameBuffer.toCharArray().also {
                            it[nameCursor]=chars[Math.floorMod(current+change,chars.length)]
                        }.concatToString()
                    }
                }
                for (key in 0..26) if(down and (1L shl key)!=0L) nameKey(key,now)
                if(move!=0 || change!=0) invalidate()
                return true
            }
            if (downButtons and ((1 shl 12) or (1 shl 13)) != 0) {
                if (downButtons and (1 shl 13) != 0) pendingAction?.invoke()
                modal = null; pendingAction = null; invalidate()
            }
            return true
        }
        if (downButtons and (1 shl 10) != 0) togglePlay()
        if (downButtons and (1 shl 12) != 0) octave = (octave - 1).coerceAtLeast(-3)
        if (downButtons and (1 shl 13) != 0) octave = (octave + 1).coerceAtMost(3)
        if (downButtons and (1 shl 6) != 0) changeScreen(47)
        for (family in FAMILIES.indices) if (family != 6 && family != 7 &&
            downButtons and (1 shl family) != 0) switchFamily(family)
        if (downButtons and (1 shl 6) != 0 && screen == 47) layer = 56
        val select = Wire6.u14(scan, 6) - 8192
        val trackDelta = Wire6.u14(scan, 8) - 8192
        val presets = Wire6.u14(scan, 10) - 8192
        val knobs = IntArray(4) { Wire6.u14(scan, 12 + 2 * it) - 8192 }
        if (trackDelta != 0) {
            releaseVoices(); editor.cancel()
            val result = parts.select(trackDelta, parts.state().context)
            if (result.code != ResultCode.COMPLETED) flash("TRACK ${result.code}") else invalidate()
        }
        if (context != inputContext) return true
        if (layer != null && layer != 56) {
            applyLayerKnobs(knobs)
            applyLayerKeys(down)
            return true
        }
        if (buttons and (1 shl 5) != 0 && select != 0) tempo(select)
        else if (screen == 42 || screen == 43) {
            if(select!=0) {
                changeScreen((screen+select).coerceIn(42,46)); return true
            }
            notesScan(down, up, nextKeys, knobs, 0, presets,
                buttons and (1 shl 6) != 0, false,
                downButtons and (1 shl 4) != 0)
        } else {
            if (select != 0) {
                val family = familyOf(screen)
                val pages = FAMILIES[family]
                if (family != 6) {
                    val index = (pages.indexOf(screen) + select).coerceIn(0, pages.lastIndex)
                    familyPage[family] = index; changeScreen(pages[index])
                } else if (family == 6) changeScreen(if (select > 0) 48 else 47)
            }
            if (context != inputContext) return true
            if (presets != 0) browse(presets)
            for (i in 0..3) if (knobs[i] != 0) adjust(i, knobs[i])
            for (key in 0..26) if (down and (1L shl key) != 0L) {
                val s = read(); val track = s.tracks[s.selectedTrackIndex]
                val white = WHITE.indexOf(key)
                if(screen==10 && white in 0..2) {
                    val param=573+white
                    val active=(s.globalParameters[param] ?: 0f)<=.5f
                    val v=if(active) 1f else 0f
                    engine.parameter(-1,param,v)
                    commit(s.copy(globalParameters=s.globalParameters+(param to v)))
                    invalidate(); continue
                }
                val pitch = if (drum(track)) 60 + white else (53 + key + 12 * octave).coerceIn(0,127)
                if (white < 0 && drum(track)) continue
                heldTracks[key] = s.selectedTrackIndex; heldNotes[key] = pitch
                engine.noteOn(s.selectedTrackIndex, pitch)
                commit(s.copy(lastMidiNote = pitch, lastMidiVelocity = 100))
            }
        }
        return true
    }
    private fun togglePlay() {
        val state = transport.state()
        val result = if (state.playing) transport.stop() else transport.start(state.context)
        if (result.code != ResultCode.COMPLETED) flash("PLAY ${result.code}") else invalidate()
    }
    private fun toggleRecording() {
        val s = read()
        val enable = !s.isRecording
        engine.recording(enable)
        if (!enable) refreshRecordedState()
        if (enable && !s.isPlaying) engine.playing(true)
        commit(read().copy(isRecording = enable, isPlaying = s.isPlaying || enable))
        invalidate()
    }
    private fun applyLayerKeys(down: Long) {
        if (down == 0L) return
        for (key in 0..26) if (down and (1L shl key) != 0L) {
            val s = read()
            val index = WHITE.indexOf(key)
            val black = blackPlace(key)
            if(black>=0) {
                if(layer in listOf(49,50,55) && black<s.tracks.size) blackHeld[key]=nowNanos()
                else if(layer==53 && drum(s.tracks[s.selectedTrackIndex])) {
                    val laneCount=if(s.tracks[s.selectedTrackIndex].engineType==EngineType.SAMPLER) 16 else 8
                    if(black<laneCount) {
                        engine.lane(s.selectedTrackIndex,black)
                        replaceTrack(s,s.tracks[s.selectedTrackIndex].copy(selectedFmDrumInstrument=black))
                        invalidate()
                    }
                } else if(layer==51) setRootFromKey(s,key)
                continue
            }
            when(layer) {
                49 -> {
                    val effect=index+(fx-13).coerceAtLeast(0)
                    if (effect >= s.fxEnabled.size) continue
                    heldFx[key]=effect to s.fxEnabled[effect]
                    engine.fxEnabled(effect,true)
                    commit(s.copy(fxEnabled=s.fxEnabled.mapIndexed { i,v -> if(i==effect) true else v }))
                }
                50 -> if(index<6 && index<s.tracks.size) {
                    heldSolo[key]=index to s.tracks[index].isSoloed
                    engine.solo(index,true)
                    commit(s.copy(tracks=s.tracks.mapIndexed { i,t -> if(i==index) t.copy(isSoloed=true) else t }))
                } else if(index==6) {
                    s.tracks.forEachIndexed { i,t -> if(t.isMuted) engine.mute(i,false) }
                    commit(s.copy(tracks=s.tracks.map { it.copy(isMuted=false) }))
                } else if(index==8) {
                    val now=nowNanos(); taps.addLast(now)
                    while(taps.size>3) taps.removeFirst()
                    if(taps.size==3) {
                        val first=taps.first; val last=taps.last
                        val bpm=(120_000_000_000f/(last-first)).coerceIn(12f,300f)
                        engine.tempo(bpm); commit(s.copy(tempo=bpm)); taps.clear()
                    }
                }
                51 -> setRootFromKey(s,key)
                52 -> if(index<EngineType.values().size) {
                    val type=EngineType.values()[index]
                    applyLoaded(s.copy(tracks=s.tracks.mapIndexed { i,t ->
                        if(i==s.selectedTrackIndex) t.copy(engineType=type,catalogEntryId=null,
                            fm1KnobBindings=null,fm1StepKnobBindings=null) else t }))
                } else if(index==15) showModal(63) {
                    val t=s.tracks[s.selectedTrackIndex]
                    val initial=TrackState(id=t.id,engineType=t.engineType)
                    applyLoaded(s.copy(tracks=s.tracks.mapIndexed { i,old ->
                        if(i==s.selectedTrackIndex) old.copy(parameters=initial.parameters,
                            selectedFmPreset=null,fm1KnobBindings=null,fm1StepKnobBindings=null) else old }))
                    flash("SOUND INITIALIZED")
                }
                53 -> sequenceTool(index)
                55 -> if(index<s.tracks[s.selectedTrackIndex].songChainNames.size) {
                    val name=s.tracks[s.selectedTrackIndex].songChainNames[index]
                    if(name!=null) storage.loadSequence(s.tracks[s.selectedTrackIndex],name)?.let { loaded ->
                        applyLoaded(s.copy(tracks=s.tracks.mapIndexed { i,old ->
                            if(i==s.selectedTrackIndex) loaded else old }))
                        flash("PATTERN ${index+1} LOADED")
                    }
                }
                else -> Unit
            }
            invalidate()
        }
    }
    private fun setRootFromKey(s: GrooveboxState,key: Int) {
        val pitchClass=(key+5)%12
        val root=(s.rootNote/12*12+pitchClass).coerceIn(0,127)
        engine.scale(root,s.scaleType.intervals.toIntArray())
        commit(s.copy(rootNote=root)); invalidate()
    }
    private fun sequenceTool(index: Int) {
        if(index !in listOf(0,1,2,3,4,8,9,10,12,13)) {
            flash("TOOL UNAVAILABLE"); return
        }
        val s=read(); val t=s.tracks[s.selectedTrackIndex]
        val length=t.patternLength.coerceIn(1,64)
        val current=steps(t).take(length)
        if(current.isEmpty()) return
        val newLength=when(index) { 12 -> (length*2).coerceAtMost(64)
            13 -> (length/2).coerceAtLeast(1); else -> length }
        fun transform(source: List<StepState>): List<StepState> {
            val active=source.take(length)
            if(active.isEmpty()) return source
            val changed=when(index) {
                0,8 -> List(length) { StepState() }
                1,9 -> active.reversed()
                2 -> active.drop(1)+active.first()
                3 -> listOf(active.last())+active.dropLast(1)
                4 -> active.shuffled()
                10 -> active.map { it.copy(active=true) }
                12 -> (active+active).take(64)
                else -> active
            }
            return if(index==13) source.take(newLength)+List((source.size-newLength).coerceAtLeast(0)) { StepState() }
                else changed+source.drop(if(index==12) newLength else length)
        }
        val next=if(drum(t)) {
            val lane=lane(t)
            t.copy(drumSteps=t.drumSteps.mapIndexed { i,v ->
                if(index in listOf(8,9,10) && i!=lane) v else transform(v) },
                patternLength=newLength,stepsPerPage=newLength)
        } else t.copy(steps=transform(t.steps),patternLength=newLength,stepsPerPage=newLength)
        applyLoaded(s.copy(tracks=s.tracks.mapIndexed { i,old ->
            if(i==s.selectedTrackIndex) next else old }))
        flash("SEQUENCE TOOL APPLIED")
    }
    private fun adjustAt(target: Int, index: Int, delta: Int) {
        val previous=screen
        screen=target
        try { adjust(index,delta) } finally { screen=previous }
    }
    private fun applyLayerKnobs(knobs: IntArray) {
        for (i in 0..3) if (knobs[i]!=0) when(layer) {
            49 -> {
                val s=read(); val old=s.macros.getOrNull(i) ?: continue
                val v=(old.value+knobs[i]/100f).coerceIn(0f,1f)
                engine.macro(i,v)
                commit(s.copy(macros=s.macros.mapIndexed { index,m -> if(index==i) m.copy(value=v) else m }))
                invalidate()
            }
            50 -> {
                val s=read(); val track=s.tracks.getOrNull(i) ?: continue
                val v=(track.volume+knobs[i]/100f).coerceIn(0f,1f)
                engine.volume(i,v)
                commit(s.copy(tracks=s.tracks.mapIndexed { index,t -> if(index==i) t.copy(volume=v) else t }))
                invalidate()
            }
            51 -> adjustAt(12,i,knobs[i])
            52 -> if(i==0) {
                val s=read(); val t=s.tracks[s.selectedTrackIndex]
                val type=EngineType.values()[(t.engineType.ordinal+knobs[i]).coerceIn(0,EngineType.values().lastIndex)]
                applyLoaded(s.copy(tracks=s.tracks.mapIndexed { index,old ->
                    if(index==s.selectedTrackIndex) old.copy(engineType=type,catalogEntryId=null) else old }))
                invalidate()
            }
            55 -> {
                val s=read(); val t=s.tracks[s.selectedTrackIndex]
                val v=(t.fxSends[i]+knobs[i]/100f).coerceIn(0f,1f)
                engine.parameter(s.selectedTrackIndex,2000+i*10,v)
                replaceTrack(s,t.copy(fxSends=t.fxSends.mapIndexed { index,x -> if(index==i) v else x }))
            }
        }
    }
    private fun showModal(id: Int, action: (() -> Unit)? = null) {
        modalReturn = screen; modal = id; modalChoice = 0; pendingAction = action; invalidate()
    }
    private fun showName(initial: String, action: (String) -> Unit) {
        nameBuffer=initial.removeSuffix(".gbx").take(12)
        nameCursor=nameBuffer.length.coerceIn(0,11)
        nameNumeric=false; nameTapKey=-1; nameTapIndex=0; nameTapAt=0L
        nameAction=action
        showModal(64)
    }
    private fun nameCommit() {
        if(nameTapKey>=0) { nameTapKey=-1; nameCursor=(nameCursor+1).coerceAtMost(nameBuffer.length) }
    }
    private fun nameKey(key: Int, now: Long) {
        val place=WHITE.indexOf(key)
        if(place>=0) {
            val groups=arrayOf("AB","CD","EF","GH","IJK","LM","NO","PQ","RS","TU","VW","XYZ",
                "123","456","789","0-.")
            val numeric="1234567890-._/#+"
            val group=if(nameNumeric) numeric[place].toString() else groups[place]
            if(place==nameTapKey && now-nameTapAt<800_000_000L && nameCursor<nameBuffer.length) {
                nameTapIndex=(nameTapIndex+1)%group.length
                nameBuffer=nameBuffer.toCharArray().also { it[nameCursor]=group[nameTapIndex] }.concatToString()
            } else {
                nameCommit()
                if(nameBuffer.length>=12) return
                nameBuffer=nameBuffer.substring(0,nameCursor)+group[0]+nameBuffer.substring(nameCursor)
                nameTapKey=if(group.length>1) place else -1
                nameTapIndex=0
                if(nameTapKey<0) nameCursor++
            }
            nameTapAt=now; invalidate(); return
        }
        nameCommit()
        when((key+5)%12) {
            6 -> nameCursor=(nameCursor-1).coerceAtLeast(0)
            8 -> if(nameBuffer.length<12) {
                nameBuffer=nameBuffer.substring(0,nameCursor)+" "+nameBuffer.substring(nameCursor); nameCursor++ }
            10 -> nameCursor=(nameCursor+1).coerceAtMost(nameBuffer.length)
            1 -> if(nameCursor>0) {
                nameCursor--; nameBuffer=nameBuffer.removeRange(nameCursor,nameCursor+1) }
            3 -> nameNumeric=!nameNumeric
        }
        invalidate()
    }
    private fun browse(delta: Int) {
        val s = read(); val track = s.tracks[s.selectedTrackIndex]
        when (screen) {
            17,18,28 -> { row=(row+delta).coerceIn(0,5); invalidate() }
            9 -> { row=(row+delta).coerceIn(0,(s.fxChainSlots.size-1).coerceAtLeast(0)); invalidate() }
            15,16,19,20,21,22,23,42,43 -> if (drum(track)) {
                val selected = (lane(track) + delta).coerceIn(0, if (track.engineType == EngineType.SAMPLER) 15 else 7)
                engine.lane(s.selectedTrackIndex, selected)
                replaceTrack(s, track.copy(selectedFmDrumInstrument = selected))
                invalidate()
            }
            2,3,4 -> { lfo = (lfo + delta).coerceIn(0, s.lfos.lastIndex); invalidate() }
            11 -> {
                val selected=(s.sidechainSourceTrack+delta).coerceIn(-1,s.tracks.lastIndex)
                engine.sidechain(selected,if(selected<0) -1 else s.sidechainSourceDrumIdx)
                commit(s.copy(sidechainSourceTrack=selected)); invalidate()
            }
            in 5..11, in 32..34 -> { fx = (fx + delta).coerceIn(0, s.fxEnabled.lastIndex); invalidate() }
            else -> { row = (row + delta).coerceAtLeast(0); invalidate() }
        }
    }
    private fun browserNames(s: GrooveboxState, t: TrackState, id: Int): List<String> = when(id) {
        36 -> FactoryPresets.forEngine(PresetEngine.forEngineTypeId(t.engineType.ordinal) ?: "")
            .map { it.name }
        37 -> storage.presets(t.engineType)
        38 -> storage.sequences()
        39 -> storage.projects()
        else -> emptyList()
    }
    private fun browserAction(s: GrooveboxState, t: TrackState, slot: Int, delta: Int) {
        val names=browserNames(s,t,screen)
        if(slot==0) { row=(row+delta).coerceIn(0,(names.size-1).coerceAtLeast(0)); invalidate(); return }
        val name=names.getOrNull(row)
        when(screen) {
            36 -> if(slot==1 && name!=null) {
                val engine=PresetEngine.forEngineTypeId(t.engineType.ordinal) ?: return
                val preset=FactoryPresets.forEngine(engine).getOrNull(row) ?: return
                applyFactoryPreset(preset); observe(read()); flash("PRESET LOADED")
            }
            37 -> when(slot) {
                1 -> if(name!=null) storage.loadPreset(t.engineType,name)?.let { loaded ->
                    val next=loaded.copy(id=t.id)
                    applyLoaded(s.copy(tracks=s.tracks.mapIndexed { i,old ->
                        if(i==s.selectedTrackIndex) next else old }))
                    flash("PRESET LOADED")
                }
                2 -> if(name!=null) if(delta>0) showModal(63) {
                    if(storage.deletePreset(t.engineType,name)) flash("PRESET DELETED")
                    else flash("DELETE FAILED")
                } else showName(name) { next ->
                    if(storage.renamePreset(t.engineType,name,next)) flash("PRESET RENAMED")
                    else flash("RENAME FAILED")
                }
                3 -> showName("Track ${s.selectedTrackIndex+1}") { next ->
                    refreshRecordedState()
                    storage.savePreset(read().tracks[s.selectedTrackIndex],next); flash("PRESET SAVED") }
            }
            38 -> when(slot) {
                1 -> if(name!=null) storage.loadSequence(t,name)?.let { loaded ->
                    applyLoaded(s.copy(tracks=s.tracks.mapIndexed { i,old ->
                        if(i==s.selectedTrackIndex) loaded else old }))
                    flash("SEQUENCE LOADED")
                }
                2 -> if(name!=null) if(delta>0) showModal(63) {
                    if(storage.deleteSequence(name)) flash("SEQUENCE DELETED") else flash("DELETE FAILED")
                } else showName(name) { next ->
                    if(storage.renameSequence(name,next)) flash("SEQUENCE RENAMED")
                    else flash("RENAME FAILED")
                }
                3 -> showName("Track ${s.selectedTrackIndex+1}") { next ->
                    refreshRecordedState()
                    storage.saveSequence(read().tracks[s.selectedTrackIndex],next); flash("SEQUENCE SAVED") }
            }
            39 -> when(slot) {
                1 -> if(name!=null) storage.loadProject(name)?.let { loaded ->
                    applyLoaded(loaded); flash("PROJECT LOADED")
                }
                2 -> if(name!=null) if(delta>0) showModal(63) {
                    if(storage.deleteProject(name)) flash("PROJECT DELETED") else flash("DELETE FAILED")
                } else showName(name.removeSuffix(".gbx")) { next ->
                    if(storage.renameProject(name,next)) flash("PROJECT RENAMED")
                    else flash("RENAME FAILED")
                }
                3 -> showName("Project") { next ->
                    refreshRecordedState()
                    if(storage.saveProject(read(),next)) flash("PROJECT SAVED")
                    else flash("SAVE FAILED") }
            }
        }
    }
    private fun normalized(value: Float) = (value.coerceIn(0f,1f) * 16383).roundToInt()
    /** Matches the cubic time controls and linear/exponential stages in Adsr.h. */
    private fun envelope(a: Float,d: Float,s: Float,r: Float): List<NativeUiView.Point> {
        val attack=a.coerceIn(0f,1f).let { it*it*it*2f+1f/48000f }
        val decay=d.coerceIn(0f,1f).let { it*it*it*3f+1f/48000f }
        val release=r.coerceIn(0f,1f).let { it*it*it*3f+1f/48000f }
        val held=.5f
        val end=attack+decay*5f+held+release*5f
        val sustain=s.coerceIn(0f,1f)
        return (0..64).map { index ->
            val time=end*index/64f
            val y=when {
                time<attack -> time/attack
                time<attack+decay*5f -> sustain+(1f-sustain)*exp(-(time-attack)/decay)
                time<attack+decay*5f+held -> sustain
                else -> sustain*exp(-(time-attack-decay*5f-held)/release)
            }
            NativeUiView.Point((index*16383/64),normalized(y))
        }
    }
    private fun envelopeMarkers(a: Float,d: Float,s: Float,r: Float): List<NativeUiView.Marker> {
        val attack=a.coerceIn(0f,1f).let { it*it*it*2f+1f/48000f }
        val decay=d.coerceIn(0f,1f).let { it*it*it*3f+1f/48000f }
        val release=r.coerceIn(0f,1f).let { it*it*it*3f+1f/48000f }
        val end=attack+decay*5f+.5f+release*5f
        val sustain=normalized(s)
        return listOf(NativeUiView.Marker(normalized(attack/end),16383,1,"ATTACK"),
            NativeUiView.Marker(normalized((attack+decay*5f)/end),sustain,1,"DECAY"),
            NativeUiView.Marker(normalized((attack+decay*5f+.5f)/end),sustain,1,"RELEASE"))
    }
    private fun fmSeries(t: TrackState, operators: IntRange = 0..5): List<NativeUiView.Series> =
        operators.map { op ->
            val base=160+op*6
            val level=t.parameters[base] ?: .25f
            val curve=envelope(t.parameters[base+1] ?: .01f,
                t.parameters[base+2] ?: .5f,t.parameters[base+3] ?: 1f,
                t.parameters[base+4] ?: .2f)
            NativeUiView.Series(op,(if(op==row) 1 else 0) or
                (if(t.fmCarrierMask and (1 shl op)!=0) 2 else 0) or
                (if(t.fmActiveMask and (1 shl op)!=0) 4 else 0),
                (0..11).map { index ->
                    val point=curve[index*64/11]
                    Triple(point.x,point.y,normalized(level)*point.y/16383)
                })
        }
    private fun percent(label: String, value: Float, enabled: Boolean = true) = NativeUiView.Control(
        label, "${(value * 100).roundToInt()}%", normalized(value), "%", 1,
        if (enabled) 1 else 4)
    private fun value(label: String, text: String, norm: Float = 0f, kind: Int = 2,
                      enabled: Boolean = true) = NativeUiView.Control(label, text,
        normalized(norm), "", kind, if (enabled) 1 else 4)
    private fun fxParameters(id: Int): List<Pair<String,Int>> = when(id) {
        6,32 -> listOf("TIME" to 520,"FEEDBACK" to 521,"MIX" to 522,"FILTER" to 523)
        7,33 -> listOf("SIZE" to 500,"DAMP" to 501,"MIX" to 503,"PRE DELAY" to 504)
        8 -> listOf("DRIVE" to 540,"DIST" to 541,"LEVEL" to 542,"TONE" to 543)
        10 -> listOf("1/4" to 570,"1/3" to 571,"1/5" to 572,"DEPTH" to 576)
        11 -> listOf("THRESHOLD" to 580,"RATIO" to 581,"ATTACK" to 582,"RELEASE" to 583)
        34 -> listOf("RATE" to 510,"DEPTH" to 511,"MIX" to 512,"VOICES" to 513)
        else -> emptyList()
    }
    private fun lfoPreview(l: LfoState, bpm: Float): List<NativeUiView.Point> {
        val rate=l.rate.coerceIn(0f,1f)
        val frequency=if(l.sync) {
            val beat=bpm.coerceAtLeast(0f)/60f
            val multiples=floatArrayOf(1f/32f,1f/24f,1f/16f,1f/12f,1f/8f,1f/4f,
                1f/2f,.75f,1f,1.5f,2f,3f,4f,6f,8f,12f,16f,18f,24f)
            beat*multiples[(rate*18.99f).toInt().coerceIn(0,18)]
        } else .01f+rate*rate*rate*29.99f
        val depth=l.depth.coerceIn(0f,1f)
        return (0 until 256).map { index ->
            val cycles=index/256f*frequency
            val phase=cycles-kotlin.math.floor(cycles)
            val signal=when(l.shape) {
                0 -> kotlin.math.sin(phase*2f*Math.PI).toFloat()
                1 -> if(phase<.5f) 4f*phase-1f else 3f-4f*phase
                2 -> if(phase<.5f) 1f else -1f
                3 -> 2f*phase-1f
                else -> {
                    val period=kotlin.math.floor(cycles).toInt()
                    if(period==0) 0f else {
                        val hashed=(period.toLong()*1103515245L+12345L) and 0x7fffffffL
                        hashed/1073741823.5f-1f
                    }
                }
            }
            NativeUiView.Point(index*64,normalized(.5f+signal*depth*.5f))
        }
    }
    private fun controls(s: GrooveboxState, t: TrackState, id: Int): List<NativeUiView.Control> = when (id) {
        47,31 -> listOf(percent("LEVEL", t.volume), percent("PAN", t.pan),
            value("MUTE", if (t.isMuted) "ON" else "OFF", if (t.isMuted) 1f else 0f, 3),
            value("TEMPO", "${s.tempo.roundToInt()} BPM", s.tempo / 300f))
        0,1,24,25,26,27 -> {
            if (id in 24..27 && t.engineType!=EngineType.FM) emptyList() else {
            val ids = when (t.engineType) {
                EngineType.SAMPLER -> intArrayOf(310,311,312,313)
                EngineType.GRANULAR -> intArrayOf(425,426,427,428)
                EngineType.WAVETABLE -> intArrayOf(454,455,456,457)
                else -> if(id in 24..27) IntArray(4) { 160+(id-24)*6+it+1 }
                    else intArrayOf(100,101,102,103)
            }
            listOf("ATTACK","DECAY","SUSTAIN","RELEASE").mapIndexed { i, name ->
                percent(name, t.parameters[ids[i]] ?: listOf(.01f,.5f,1f,.2f)[i])
            }
            }
        }
        2,3,4 -> s.lfos.getOrNull(lfo)?.let { listOf(percent("RATE", it.rate),
            percent("DEPTH", it.depth), value("SHAPE",listOf("SINE","TRIANGLE","SQUARE","SAW","RANDOM")
                .getOrElse(it.shape) { "?" },it.shape/4f),
            value("SYNC", if (it.sync) "ON" else "OFF", if (it.sync) 1f else 0f,3)) }.orEmpty()
        6,7,8,10,11,32,33,34 -> fxParameters(id).map { (name,param) ->
            percent(name,s.globalParameters[param] ?: 0f) }
        9 -> listOf(value("SLOT","${row+1}",row/4f,1),
            value("EFFECT",s.fxChainSlots.getOrNull(row)?.takeIf { it>=0 }?.let { "FX ${it+1}" }
                ?: "EMPTY",kind=2))
        in 5..11, in 32..34 -> listOf(value("BUS", "${fx+1}", fx/17f),
            percent("SEND", t.fxSends.getOrElse(fx) { 0f }),
            value("MIX", "UNITY", 1f, enabled = false),
            value("BYPASS", if (s.fxEnabled.getOrElse(fx) { false }) "ON" else "OFF",kind=3))
        12 -> listOf(value("ROOT", ScaleLogic.getMidiNoteName(s.rootNote), s.rootNote/127f),
            value("SCALE", s.scaleType.displayName, s.scaleType.ordinal.toFloat()/ScaleType.values().lastIndex))
        13 -> listOf(value("CHORD", if (t.arpConfig.isChordProgEnabled) "ON" else "OFF", kind=3),
            value("MOOD", "${t.arpConfig.chordProgMood}"), value("COMPLEX", "${t.arpConfig.chordProgComplexity}"))
        17,18,28 -> if (t.engineType == EngineType.FM) {
            val op=row.coerceIn(0,5)
            when(fmSheetWindow) {
                0 -> listOf(value("SHEET","TONE",kind=2),
                    percent("LEVEL",t.parameters[160+op*6] ?: .25f),
                    value("RATIO","${((t.parameters[165+op*6] ?: .0625f)*16f).roundToInt()}",
                        t.parameters[165+op*6] ?: .0625f),
                    percent("FEEDBACK",t.parameters[154] ?: 0f))
                1 -> listOf(value("SHEET","ENV 1"),
                    percent("ATTACK",t.parameters[161+op*6] ?: .01f),
                    percent("DECAY",t.parameters[162+op*6] ?: .5f),
                    percent("SUSTAIN",t.parameters[163+op*6] ?: 1f))
                2 -> listOf(value("SHEET","ENV 2"),
                    percent("RELEASE",t.parameters[164+op*6] ?: .2f))
                else -> listOf(value("SHEET","MORE"),
                    value("ALGORITHM","${((t.parameters[150] ?: 0f)*31.99f).toInt()+1}"),
                    percent("BRIGHT",t.parameters[157] ?: .5f))
            }
        } else emptyList()
        15,16,20 -> soundControls(t,id).map { entry ->
            percent(entry.label,t.parameters[entry.id] ?: entry.default,entry.id>=0)
        }
        29,30 -> emptyList()
        35 -> listOf(percent("MASTER", s.masterVolume),
            value("TRANSPOSE", "${s.globalTranspose}",enabled=false))
        40,41 -> listOf(value("MODE",t.arpConfig.mode.name),
            value("OCTAVES","${t.arpConfig.octaves}"),
            percent("PROB",t.arpConfig.probability),percent("STRUM",t.arpConfig.strum))
        42,43 -> editor.view().controls.map { control ->
            value(control.label, control.value, control.rawValue ?: 0f, enabled=control.available) }
        36,37,38,39 -> listOf(value("ITEM","${row+1}",kind=1),value("LOAD","TURN",kind=4),
            value("DELETE","CONFIRM",kind=4),value("SAVE","TURN",kind=4))
        44 -> listOf(value("LENGTH","${t.patternLength}",t.patternLength/64f),
            value("TEMPO","${s.tempo.roundToInt()} BPM",s.tempo/300f),
            percent("SWING",(s.swing+.23f)/.46f),percent("HUMAN",t.humanize))
        46 -> listOf(value("SLOT","${row+1}",row/15f,1),
            value("CHAIN",if(t.isChainEnabled) "ON" else "OFF",kind=3),
            value("LENGTH","${t.songChainLength}",t.songChainLength/16f,1),
            value("PATTERN",t.songChainNames.getOrNull(row) ?: "EMPTY",kind=2))
        else -> emptyList()
    }
    private fun adjust(index: Int, delta: Int) {
        val s = read(); val t = s.tracks[s.selectedTrackIndex]
        when (screen) {
            47,31 -> when (index) {
                0 -> { val v=(t.volume+delta/100f).coerceIn(0f,1f); engine.volume(s.selectedTrackIndex,v); replaceTrack(s,t.copy(volume=v)) }
                1 -> { val v=(t.pan+delta/100f).coerceIn(0f,1f); engine.pan(s.selectedTrackIndex,v); replaceTrack(s,t.copy(pan=v)) }
                2 -> { val v=delta>0; engine.mute(s.selectedTrackIndex,v); replaceTrack(s,t.copy(isMuted=v)) }
                3 -> tempo(delta)
            }
            17,18,28 -> if(t.engineType==EngineType.FM) {
                if(index==0) { fmSheetWindow=(fmSheetWindow+delta).coerceIn(0,4); invalidate() }
                else {
                    val op=row.coerceIn(0,5)
                    val id=when(fmSheetWindow) {
                        0 -> when(index) { 1 -> 160+op*6; 2 -> 165+op*6; else -> 154 }
                        1 -> 160+op*6+index
                        2 -> 164+op*6
                        else -> when(index) { 1 -> 150; else -> 157 }
                    }
                    val old=t.parameters[id] ?: 0f
                    val v=(old+delta/100f).coerceIn(0f,1f)
                    engine.parameter(s.selectedTrackIndex,id,v)
                    replaceTrack(s,t.copy(parameters=t.parameters+(id to v)))
                }
            }
            15,16,20 -> {
                val entry=soundControls(t,screen).getOrNull(index) ?: return
                if(entry.id<0) return
                val v=((t.parameters[entry.id] ?: entry.default)+delta/100f).coerceIn(0f,1f)
                engine.parameter(s.selectedTrackIndex,entry.id,v)
                val updated=t.copy(parameters=t.parameters+(entry.id to v))
                replaceTrack(s,updated)
                if(drum(t)!=drum(updated)) invalidate()
            }
            0,1,24,25,26,27 -> {
                val ids = if (screen in listOf(0,1,24,25,26,27)) when(t.engineType) {
                    EngineType.SAMPLER -> intArrayOf(310,311,312,313)
                    EngineType.GRANULAR -> intArrayOf(425,426,427,428)
                    EngineType.WAVETABLE -> intArrayOf(454,455,456,457)
                    else -> if(screen in 24..27) IntArray(4) { 161+(screen-24)*6+it }
                        else intArrayOf(100,101,102,103)
                } else intArrayOf(112,113,100,103)
                val id = ids[index]
                val v=((t.parameters[id] ?: 0f)+delta/100f).coerceIn(0f,1f)
                engine.parameter(s.selectedTrackIndex,id,v)
                replaceTrack(s,t.copy(parameters=t.parameters+(id to v)))
            }
            2,3,4 -> {
                val old=s.lfos.getOrNull(lfo) ?: return
                val next=when(index) {
                    0 -> old.copy(rate=(old.rate+delta/100f).coerceIn(0f,1f))
                    1 -> old.copy(depth=(old.depth+delta/100f).coerceIn(0f,1f))
                    2 -> old.copy(shape=(old.shape+delta).coerceIn(0,4))
                    else -> old.copy(sync=delta>0)
                }
                engine.lfo(lfo,next); commit(s.copy(lfos=s.lfos.mapIndexed { i,x -> if(i==lfo) next else x }))
            }
            6,7,8,10,11,32,33,34 -> {
                val param=fxParameters(screen).getOrNull(index)?.second ?: return
                val old=s.globalParameters[param] ?: 0f
                val v=(old+delta/100f).coerceIn(0f,1f)
                engine.parameter(-1,param,v)
                commit(s.copy(globalParameters=s.globalParameters+(param to v)))
            }
            9 -> when(index) {
                0 -> { row=(row+delta).coerceIn(0,(s.fxChainSlots.size-1).coerceAtLeast(0)); invalidate() }
                1 -> {
                    val current=s.fxChainSlots.getOrNull(row) ?: -1
                    val next=(current+delta).coerceIn(-1,s.fxEnabled.lastIndex)
                    applyLoaded(s.copy(fxChainSlots=s.fxChainSlots.mapIndexed { i,v -> if(i==row) next else v }))
                    invalidate()
                }
            }
            in 5..11, in 32..34 -> when(index) {
                0 -> { fx=(fx+delta).coerceIn(0,s.fxEnabled.lastIndex); invalidate() }
                1 -> { val v=(t.fxSends[fx]+delta/100f).coerceIn(0f,1f)
                    engine.parameter(s.selectedTrackIndex,2000+fx*10,v)
                    replaceTrack(s,t.copy(fxSends=t.fxSends.mapIndexed { i,x -> if(i==fx) v else x })) }
                3 -> { val v=delta>0; engine.fxEnabled(fx,v)
                    commit(s.copy(fxEnabled=s.fxEnabled.mapIndexed { i,x -> if(i==fx) v else x })) }
            }
            12 -> if(index==0) {
                val root=(s.rootNote+delta).coerceIn(0,127)
                engine.scale(root,s.scaleType.intervals.toIntArray()); commit(s.copy(rootNote=root))
            } else if(index==1) {
                val scale=ScaleType.values()[(s.scaleType.ordinal+delta).coerceIn(0,ScaleType.values().lastIndex)]
                engine.scale(s.rootNote,scale.intervals.toIntArray()); commit(s.copy(scaleType=scale))
            }
            13 -> { val old=t.arpConfig; val next=when(index) {
                0 -> old.copy(isChordProgEnabled=delta>0)
                1 -> old.copy(chordProgMood=(old.chordProgMood+delta).coerceIn(0,4))
                else -> old.copy(chordProgComplexity=(old.chordProgComplexity+delta).coerceIn(0,4)) }
                engine.arp(s.selectedTrackIndex,next); replaceTrack(s,t.copy(arpConfig=next)) }
            35 -> if(index==0) {
                val v=(s.masterVolume+delta/100f).coerceIn(0f,1f)
                engine.masterVolume(v); commit(s.copy(masterVolume=v))
            }
            36,37,38,39 -> browserAction(s,t,index,delta)
            40,41 -> { val old=t.arpConfig; val next=when(index) {
                0 -> old.copy(mode=ArpMode.values()[(old.mode.ordinal+delta).coerceIn(0,ArpMode.values().lastIndex)])
                1 -> old.copy(octaves=(old.octaves+delta).coerceIn(-3,3))
                2 -> old.copy(probability=(old.probability+delta/100f).coerceIn(0f,1f))
                else -> old.copy(strum=(old.strum+delta/100f).coerceIn(0f,1f)) }
                engine.arp(s.selectedTrackIndex,next); replaceTrack(s,t.copy(arpConfig=next)) }
            44 -> when(index) {
                0 -> { val length=(t.patternLength+delta).coerceIn(1,64); engine.length(s.selectedTrackIndex,length)
                    replaceTrack(s,t.copy(patternLength=length,stepsPerPage=length)); invalidate() }
                1 -> tempo(delta)
                2 -> { val v=(s.swing+delta/100f).coerceIn(-.23f,.23f); engine.swing(v); commit(s.copy(swing=v)) }
                3 -> { val v=(t.humanize+delta/100f).coerceIn(0f,1f); engine.humanize(s.selectedTrackIndex,v); replaceTrack(s,t.copy(humanize=v)) }
            }
            46 -> when(index) {
                0 -> { row=(row+delta).coerceIn(0,15); invalidate() }
                1 -> { val enabled=delta>0; engine.chainEnabled(s.selectedTrackIndex,enabled)
                    replaceTrack(s,t.copy(isChainEnabled=enabled)) }
                2 -> { val length=(t.songChainLength+delta).coerceIn(1,16)
                    engine.chainLength(s.selectedTrackIndex,length)
                    replaceTrack(s,t.copy(songChainLength=length)); invalidate() }
                3 -> {
                    val choices=storage.sequences().filter { it.endsWith(".mid").not() && it.endsWith(".midi").not() }
                    val current=t.songChainNames.getOrNull(row)
                    val currentIndex=choices.indexOf(current)
                    val nextIndex=(currentIndex+delta).coerceIn(-1,choices.lastIndex)
                    val name=choices.getOrNull(nextIndex)
                    val loaded=name?.let { storage.loadSequence(t,it) }
                    val names=t.songChainNames.toMutableList(); names[row]=name
                    engine.chainSlot(s.selectedTrackIndex,row,(loaded?.steps ?: List(64){StepState()}).toTypedArray())
                    replaceTrack(s,t.copy(songChainNames=names)); invalidate()
                }
            }
        }
    }
    override fun snapshot(): ByteArray {
        if (deviceMenu) frozenMenuView?.let { return it.clone() }
        if (!deviceMenu && modal == null && lockedLayer == null && layer == null) {
            val now = nowNanos()
            for ((bit, id) in listOf(0 to 49, 1 to 51, 4 to 52, 5 to 50,
                8 to 55, 9 to 53, 11 to 54)) {
                if (buttons and (1 shl bit) != 0 && pressTimes[bit] != 0L &&
                    now - pressTimes[bit] > 450_000_000L) {
                    layer = id; invalidate(); break
                }
            }
        }
        val s = read()
        require(s.selectedTrackIndex in s.tracks.indices)
        val t = s.tracks[s.selectedTrackIndex]
        reconcileModel(s)
        val id = modal ?: layer ?: screen
        val family = NativeUi.familyFor(id).takeIf { it >= 0 } ?: familyOf(screen)
        val length = t.patternLength.coerceIn(1,64)
        val seq = if (screen == 42 || screen == 43) editor.view() else null
        val start = if (screen in 42..46) (seq?.bank ?: s.currentSequencerBank).coerceIn(0,3)*16 else 0
        val playhead = if (s.isPlaying && (id==47 || id in 42..46))
            engine.playhead(s.selectedTrackIndex,lane(t)).takeIf { it in 0 until length } ?: NONE
            else NONE
        val data = content(s,t,id,start,length,playhead)
        val title = TITLES.getOrElse(id) { "LOOM" }
        val subtitle = if (id == 47) "TRACK ${s.selectedTrackIndex+1}  ${t.engineType.name.replace('_',' ')}"
            else if (id == 42 || id == 43) seq?.selectionText.orEmpty()
            else "TRACK ${s.selectedTrackIndex+1}  ${t.engineType.name.replace('_',' ')}"
        val unavailable = data.unavailable
        val view = NativeUiView(id,family,title,subtitle,
            if (feedback.isNotEmpty() && nowNanos() < feedbackUntil) feedback else data.hint,
            context,s.selectedTrackIndex,s.tracks.size,
            page=FAMILIES.getOrNull(family)?.indexOf(screen)?.coerceAtLeast(0) ?: 0,
            pageCount=FAMILIES.getOrNull(family)?.size ?: 1,
            flags=(if (unavailable) 4 else 0) or (if (deviceMenu) 8 else 0),
            variant=if (id in 15..30) when {
                id in listOf(19,21) && drum(t) -> 6
                t.engineType == EngineType.FM -> 3
                drum(t) -> 4
                t.engineType in listOf(EngineType.SAMPLER,EngineType.WAVETABLE,EngineType.GRANULAR) -> 5
                else -> 2
            } else if (drum(t)) 1 else 0,
            tempo=(s.tempo*10).roundToInt().coerceIn(0,16383),
            transport=(if(s.isPlaying) 1 else 0) or (if(s.isRecording) 2 else 0) or
                (if(seq?.stepEntry==true) 4 else 0),
            firstStep=start,visibleSteps=if(id in 42..46 || id==47) minOf(16,length-start) else 0,
            cursor=if(id==64) nameCursor else if(id==42 || id==43) seq?.cursor ?: NONE else
                if(id in listOf(17,18,28)) fmSheetWindow else NONE,
            playhead=playhead,selectedRow=if(data.rows.isEmpty()) NONE else
                (when(id) { 31,47,48 -> s.selectedTrackIndex; 49 -> fx;
                    51 -> s.scaleType.ordinal; else -> row })
                    .coerceIn(0,data.totalRows.coerceAtLeast(1)-1),
            firstRow=data.firstRow,totalRows=data.totalRows,
            controls=when(id) {
                49 -> List(4) { index -> s.macros.getOrNull(index)?.let {
                    percent(it.label,it.value) } ?: value("MACRO ${index+1}","UNAVAILABLE",enabled=false) }
                50 -> List(4) { index -> s.tracks.getOrNull(index)?.let {
                    percent("T${index+1} LEVEL",it.volume) } ?: value("TRACK","UNAVAILABLE",enabled=false) }
                51 -> controls(s,t,12)
                52 -> listOf(value("ENGINE",t.engineType.name),value("PRESET","PHONE",enabled=false),
                    value("FAVORITE","UNAVAILABLE",enabled=false))
                55 -> List(4) { index -> percent("FX ${index+1} SEND",t.fxSends[index]) }
                else -> if(id==screen) controls(s,t,id) else emptyList()
            },
            rows=data.rows,cells=data.cells,notes=data.notes,curve=data.curve,
            markers=data.markers,keyboard=data.keyboard,root=s.rootNote,lines=data.lines,
            edges=data.edges,series=data.series)
        return view.encode().also { if (deviceMenu) frozenMenuView = it.clone() }
    }
    private data class PageData(
        val rows: List<NativeUiView.Row> = emptyList(),
        val cells: List<NativeUiView.Cell> = emptyList(),
        val notes: List<NativeUiView.Note> = emptyList(),
        val curve: List<NativeUiView.Point> = emptyList(),
        val markers: List<NativeUiView.Marker> = emptyList(),
        val edges: List<NativeUiView.Edge> = emptyList(),
        val series: List<NativeUiView.Series> = emptyList(),
        val keyboard: Set<Int>? = null,
        val lines: List<String> = emptyList(),
        val firstRow: Int = 0,
        val totalRows: Int = rows.size,
        val unavailable: Boolean = false,
        val hint: String = "SELECT PAGE  PRESETS ITEM"
    )
    private fun homeData(s: GrooveboxState, selectedHead: Int): PageData {
        val first=(s.selectedTrackIndex-5).coerceAtLeast(0)
        val visible=s.tracks.drop(first).take(6)
        val heads=visible.mapIndexed { offset,t ->
            val index=first+offset
            if(!s.isPlaying) NONE else if(index==s.selectedTrackIndex) selectedHead
            else engine.playhead(index,lane(t)).takeIf { it in 0 until t.patternLength.coerceIn(1,64) } ?: NONE
        }
        val rows=visible.mapIndexed { offset,t ->
            val index=first+offset
            val length=t.patternLength.coerceIn(1,64)
            val stepStart=(s.currentSequencerBank*16).coerceIn(0,(length-1)/16*16)
            val head=heads[offset]
            val sound=t.catalogEntryId ?: when(t.engineType) {
                EngineType.SAMPLER -> t.lastSamplePath.substringAfterLast('/').ifBlank { "SAMPLE" }
                EngineType.WAVETABLE -> t.activeWavetableName
                EngineType.SOUNDFONT -> t.soundFontPresetName
                else -> t.engineType.name.replace('_',' ')
            }
            NativeUiView.Row(sound,t.subTrackNames[lane(t)] ?: "T${index+1}",
                t.engineType.name.take(8),
                (if(index==s.selectedTrackIndex) 1 else 0) or
                    (if(t.isAudiblyActive) 2 else 0) or (if(t.isMuted) 4 else 0),
                stepStart,head,index)
        }
        val cells=visible.flatMapIndexed { offset,t ->
            val index=first+offset
            val source=steps(t)
            val length=t.patternLength.coerceIn(1,64)
            val stepStart=(s.currentSequencerBank*16).coerceIn(0,(length-1)/16*16)
            val head=heads[offset]
            val ties=BooleanArray(length)
            for(previous in 0 until length) {
                val old=source.getOrNull(previous) ?: continue
                if(!old.active || old.notes.isEmpty()) continue
                for(note in old.notes.indices) {
                    val gate=old.noteGates.getOrNull(note) ?: old.gate
                    var target=previous+1
                    while(target<length && gate>target-previous) { ties[target]=true; target++ }
                }
            }
            (0..15).map { slot ->
                val stepIndex=stepStart+slot
                val step=source.getOrNull(stepIndex)
                NativeUiView.Cell(stepIndex.coerceAtMost(16383),
                    (if(stepIndex<length && step?.active==true) 1 else 0) or
                    (if(stepIndex<length && ties[stepIndex]) 2 else 0) or (if(stepIndex==head) 4 else 0) or
                    (if(step?.parameterLocks?.isNotEmpty()==true) 8 else 0) or
                    (if(step?.punch==true) 32 else 0) or
                    (if(stepIndex>=length) 64 else 0),"${slot+1}")
            }
        }
        return PageData(rows=rows,cells=cells,firstRow=first,totalRows=s.tracks.size,
            hint="TRACKS  PLAY  SELECT SCOPE")
    }
    private fun content(s: GrooveboxState,t: TrackState,id: Int,start: Int,length: Int,
                        playhead: Int): PageData {
        fun window(rows: List<NativeUiView.Row>): PageData {
            val maxFirst=(rows.size-16).coerceAtLeast(0)
            firstRow=firstRow.coerceIn(0,maxFirst)
            row=row.coerceIn(0,(rows.size-1).coerceAtLeast(0))
            if (row < firstRow) firstRow=row
            if (row >= firstRow+16) firstRow=row-15
            return PageData(rows=rows.drop(firstRow).take(16),firstRow=firstRow,totalRows=rows.size)
        }
        val trackRows by lazy { s.tracks.mapIndexed { index, track ->
            NativeUiView.Row("T${index+1} ${track.engineType.name.take(10)}",
                "${(track.volume*100).roundToInt()}%",if(track.isSoloed) "SOLO" else "",
                (if(index==s.selectedTrackIndex) 1 else 0) or
                    (if(track.isSoloed) 2 else 0) or (if(track.isMuted) 4 else 0),
                normalized(track.volume),normalized(track.pan),index)
        } }
        val selectedSteps by lazy { steps(t) }
        val sequenceView by lazy { editor.view() }
        val cells by lazy { (start until minOf(start+16,length)).map { step ->
            val value=selectedSteps.getOrNull(step) ?: StepState()
            NativeUiView.Cell(step,(if(value.active) 1 else 0) or
                (if(id in 42..43 && step==sequenceView.cursor) 2 else 0) or
                (if(step==playhead) 4 else 0) or
                (if(value.parameterLocks.isNotEmpty()) 8 else 0) or
                (if(value.isSkipped) 16 else 0) or (if(value.punch) 32 else 0),"${step+1}")
        } }
        val scale by lazy { (0..127).filter { Math.floorMod(it-s.rootNote,12) in s.scaleType.intervals }.toSet() }
        return when(id) {
            47 -> homeData(s,playhead)
            48 -> PageData(rows=trackRows.map { it.copy(a=0,b=0) },totalRows=trackRows.size,
                unavailable=true,hint="LIVE OUTPUT WAVEFORM UNAVAILABLE")
            0,1,24,25,26,27 -> {
                val c=controls(s,t,id)
                // Sustain is level; attack/decay/release timings come from the current engine parameters.
                val attack=c.getOrNull(0)?.normalized ?: 0
                val decay=c.getOrNull(1)?.normalized ?: 0
                val sustain=c.getOrNull(2)?.normalized ?: 0
                val release=c.getOrNull(3)?.normalized ?: 0
                val supported=t.engineType !in listOf(EngineType.FM_DRUM,EngineType.ANALOG_DRUM,EngineType.MIDI) &&
                    (id !in 24..27 || t.engineType==EngineType.FM)
                PageData(curve=if(supported) envelope(attack/16383f,decay/16383f,
                    sustain/16383f,release/16383f) else emptyList(),
                    markers=if(supported) envelopeMarkers(attack/16383f,decay/16383f,
                        sustain/16383f,release/16383f) else emptyList(),unavailable=!supported,
                    series=if(supported && id in 24..27) fmSeries(t,(id-24)..(id-24)) else emptyList(),
                    hint="A ${attack/164} D ${decay/164} S ${sustain/164} R ${release/164}")
            }
            2,3 -> {
                val l=s.lfos.getOrNull(lfo)
                PageData(curve=if(l!=null) lfoPreview(l,s.tempo) else emptyList(),
                    markers=listOf(NativeUiView.Marker(0,normalized(l?.depth ?: 0f),1,"DEPTH")),
                    hint="LFO ${lfo+1} ${l?.targetLabel ?: "NONE"}${if(l?.shape==4) " RANDOM PREVIEW" else ""}")
            }
            4 -> window(s.routingConnections.map { route ->
                NativeUiView.Row("SRC ${route.source}","T${route.destTrack+1} P${route.destParam}",
                    "${(route.amount*100).roundToInt()}%",a=normalized((route.amount+1f)/2f)) })
            5 -> {
                val rows=t.fxSends.mapIndexed { i,send ->
                    NativeUiView.Row("FX ${i+1}","${(send*100).roundToInt()}%",
                        if(s.fxEnabled.getOrElse(i){false}) "ON" else "OFF",
                        (if(i==fx) 1 else 0) or (if(s.fxEnabled.getOrElse(i){false}) 2 else 0),
                        normalized(send),16383,i) }
                window(rows).copy(hint="SEND/BYPASS AVAILABLE  DSP GRAPH UNAVAILABLE")
            }
            6,7,8,32,33,34 -> PageData(rows=controls(s,t,id).map { control ->
                NativeUiView.Row(control.label,control.value,a=control.normalized) },
                hint="DSP PREVIEW UNAVAILABLE")
            9 -> PageData(rows=s.fxChainSlots.mapIndexed { index,effect ->
                NativeUiView.Row("INSERT ${index+1}",if(effect<0) "EMPTY" else "FX ${effect+1}",
                    if(index==row) "SELECTED" else "",if(index==row) 1 else 0,
                    effect.coerceAtLeast(0),index,0) },hint="CHAIN SLOTS ON PHONE")
            10 -> PageData(rows=controls(s,t,id).map { control ->
                NativeUiView.Row(control.label,control.value,a=control.normalized) },
                cells=(0..2).map { index ->
                    val active=(s.globalParameters[573+index] ?: 0f)>.5f
                    NativeUiView.Cell(index,if(active) 1 else 0,
                        listOf("1/4","1/3","1/5")[index]) },
                hint="RATE AND DEPTH FROM LOOM")
            11 -> PageData(rows=s.tracks.mapIndexed { index,track ->
                NativeUiView.Row("T${index+1} ${track.engineType.name.take(9)}",
                    if(index==s.sidechainSourceTrack) "SOURCE" else "",
                    if(index==s.sidechainSourceTrack) "SELECTED" else "",
                    if(index==s.sidechainSourceTrack) 1 else 0,
                    index,s.sidechainSourceDrumIdx,0) },
                hint="PRESETS SOURCE  KNOBS DUCK")
            12 -> PageData(keyboard=scale,hint="${ScaleLogic.getMidiNoteName(s.rootNote)} ${s.scaleType.displayName}")
            13 -> PageData(keyboard=scale,rows=listOf(NativeUiView.Row("CHORD PROGRESSION",
                if(t.arpConfig.isChordProgEnabled) "ON" else "OFF",
                "MOOD ${t.arpConfig.chordProgMood}",a=t.arpConfig.chordProgComplexity)))
            14 -> PageData(unavailable=true,keyboard=scale,hint="FOLLOW SOURCE UNAVAILABLE")
            20 -> PageData(rows=controls(s,t,id).map {
                NativeUiView.Row(it.label,it.value,a=it.normalized) },
                hint=if(drum(t)) "LANE ${lane(t)+1}  BROWSE LANES" else
                    t.activeWavetableName.ifBlank { t.engineType.name })
            29,30 -> PageData(unavailable=true,hint="VOICE ACTIVITY UNAVAILABLE")
            15,16 -> PageData(rows=controls(s,t,id).map {
                NativeUiView.Row(it.label,it.value,a=it.normalized) },
                unavailable=controls(s,t,id).isEmpty(),
                hint=if(drum(t)) "LANE ${lane(t)+1}  BROWSE LANES" else t.engineType.name)
            17,18,28 -> {
                val rows=(0..5).map { op ->
                    val level=t.parameters[160+op*6] ?: .25f
                    val ratio=(t.parameters[165+op*6] ?: .0625f)*16f
                    NativeUiView.Row("${op+1}","${ratio.roundToInt()}:1",
                        if(t.fmCarrierMask and (1 shl op)!=0) "CARRIER" else "MOD",
                        (if(op==row) 1 else 0) or
                            (if(t.fmActiveMask and (1 shl op)!=0) 2 else 0),
                        op%3,op/3,normalized(level)) }
                val algorithm=((t.parameters[150] ?: 0f)*31.99f).toInt().coerceIn(0,31)
                PageData(rows=if(t.engineType==EngineType.FM) rows else if(id in 15..16)
                    controls(s,t,id).map { NativeUiView.Row(it.label,it.value,a=it.normalized) }
                    else emptyList(),
                    edges=if(t.engineType==EngineType.FM) FmTopology.edges(algorithm) else emptyList(),
                    series=if(t.engineType==EngineType.FM) fmSeries(t) else emptyList(),
                    unavailable=t.engineType!=EngineType.FM && id !in 15..16,
                    hint="ALGORITHM ${algorithm+1}  SHEET ${fmSheetWindow+1}")
            }
            19,21 -> window((0 until if(t.engineType==EngineType.SAMPLER) 16 else 8).map { lane ->
                val steps=t.drumSteps.getOrNull(lane).orEmpty().take(length)
                NativeUiView.Row("LANE ${lane+1}","${steps.count{it.active}} STEPS",
                    if(lane==lane(t)) "SELECT" else "",if(lane==lane(t)) 1 else 0,
                    steps.count{it.active},lane,0) }).copy(unavailable=!drum(t))
            22 -> PageData(rows=if(t.engineType==EngineType.SAMPLER) t.drumSteps.take(16).mapIndexed { index,steps ->
                NativeUiView.Row("SLICE ${index+1}","${steps.count{it.active}} HITS",
                    if(index==lane(t)) "SELECT" else "",if(index==lane(t)) 1 else 0) } else emptyList(),
                unavailable=t.engineType!=EngineType.SAMPLER,
                hint="SLICE MARKERS NOT EXPOSED")
            23 -> PageData(rows=listOf(NativeUiView.Row("SAMPLE",t.lastSamplePath.substringAfterLast('/').take(16))),
                unavailable=t.engineType!=EngineType.SAMPLER || t.lastSamplePath.isBlank())
            31 -> PageData(rows=trackRows.drop((s.selectedTrackIndex-5).coerceAtLeast(0)).take(6),
                firstRow=(s.selectedTrackIndex-5).coerceAtLeast(0),totalRows=trackRows.size)
            35 -> PageData(rows=listOf(NativeUiView.Row("MASTER","${(s.masterVolume*100).roundToInt()}%"),
                NativeUiView.Row("TRANSPOSE","${s.globalTranspose}")))
            36,37,38,39 -> window(browserNames(s,t,id).mapIndexed { index,name ->
                NativeUiView.Row(name,if(index==row) "SELECTED" else "",
                    if(id==39) "PROJECT" else if(id==38) "SEQUENCE" else "PRESET",
                    if(index==row) 1 else 0) }).copy(hint="KNOB 1 ITEM  2 LOAD  3 DEL  4 SAVE")
            40,41 -> PageData(rows=t.arpConfig.rhythms.mapIndexed { i,rhythm ->
                NativeUiView.Row("RHYTHM ${i+1}","${rhythm.count{it}}/16") },
                cells=t.arpConfig.rhythms.firstOrNull().orEmpty().mapIndexed { i,on ->
                    NativeUiView.Cell(8192,if(on) 1 else 64,"${i+1}") },
                hint="RHYTHM PREVIEW")
            42,43 -> {
                val visible=selectedSteps.drop(start).take(16)
                val seqView=sequenceView
                val invalidChord=visible.any { it.notes.size>16 }
                val notes=if(drum(t) || invalidChord) emptyList() else visible.flatMapIndexed { index,step ->
                    step.notes.mapIndexed { n,pitch ->
                        val offset=step.noteOffsets.getOrNull(n) ?: step.subStepOffset
                        val gate=step.noteGates.getOrNull(n) ?: step.gate
                        NativeUiView.Note(pitch,index*128+(offset*127).roundToInt().coerceIn(0,127),
                            (gate*128).roundToInt().coerceIn(1,16383),
                            (if(org.fm1.companion.sequence.NoteAddress(start+index,n) in seqView.selectedNotes) 1 else 0) or
                            (if(step.parameterLocks.isNotEmpty()) 8 else 0) or
                            (if(!step.active || step.isSkipped) 16 else 0) or
                            (if(step.punch) 32 else 0),
                            normalized(step.noteVelocities.getOrNull(n) ?: step.velocity)/129)
                    }
                }
                val grid=if(drum(t)) t.drumSteps.take(if(t.engineType==EngineType.SAMPLER)16 else 8)
                    .flatMapIndexed { laneIndex,steps ->
                        steps.drop(start).take(16).mapIndexed { index,step ->
                            NativeUiView.Cell(laneIndex*16+index,
                                (if(step.active) 1 else 0) or
                                (if(laneIndex==lane(t)) 2 else 0) or
                                (if(step.isSkipped) 16 else 0) or
                                (if(step.parameterLocks.isNotEmpty()) 8 else 0),
                                "${laneIndex+1}")
                        }
                    } else cells
                PageData(cells=grid,notes=notes,keyboard=if(drum(t)) null else scale,
                    unavailable=invalidChord,
                    hint=if(invalidChord) "CHORD OVER 16 NOTES" else
                        seqView.message.ifBlank { "SELECT CURSOR  PRESETS BANK" })
            }
            44 -> PageData(cells=cells,rows=listOf(NativeUiView.Row("LENGTH","$length"),
                NativeUiView.Row("SWING","${(s.swing*100).roundToInt()}%"),
                NativeUiView.Row("HUMAN","${(t.humanize*100).roundToInt()}%")))
            45 -> window(selectedSteps.drop(start).take(16).flatMapIndexed { index,step ->
                step.parameterLocks.entries.map { (param,v) -> NativeUiView.Row(
                    "STEP ${start+index+1}","PARAM $param","${(v*100).roundToInt()}%",
                    a=normalized(v),b=start+index,c=param) } })
            46 -> PageData(rows=t.songChainNames.take(16).mapIndexed { index,name ->
                NativeUiView.Row("SLOT ${index+1}",name ?: "EMPTY",
                    if(index<t.songChainLength) "IN CHAIN" else "",
                    (if(index==row) 1 else 0) or (if(index<t.songChainLength && name!=null) 2 else 0),
                    index,t.songChainLength,0) },totalRows=16,
                hint="KNOBS SLOT CHAIN LENGTH PATTERN")
            49 -> PageData(rows=t.fxSends.drop((fx-13).coerceAtLeast(0)).take(14).mapIndexed { offset, send ->
                val index=offset+(fx-13).coerceAtLeast(0)
                NativeUiView.Row("FX ${index+1}","KEY ${offset+1}",
                    "${(send*100).roundToInt()}%",
                    (if(index==fx) 1 else 0) or (if(s.fxEnabled.getOrElse(index){false}) 2 else 0),
                    normalized(send),index,0) },
                cells=s.tracks.take(6).mapIndexed { index, track ->
                    NativeUiView.Cell(index,if(track.isMuted) 2 else 1,"T${index+1}") },
                firstRow=(fx-13).coerceAtLeast(0),totalRows=t.fxSends.size,
                hint="KEYS FX BYPASS  KNOBS SEND")
            50 -> PageData(rows=trackRows.take(6).mapIndexed { index, track ->
                track.copy(value="KEY ${index+1}") },
                cells=listOf("ALL","FILL","TAP").mapIndexed { index,label ->
                    NativeUiView.Cell(index,if(index==1) 64 else 0,label) },
                hint="WHITE SOLO BLACK MUTE")
            51 -> PageData(rows=ScaleType.values().drop((s.scaleType.ordinal-15).coerceAtLeast(0))
                .take(16).mapIndexed { offset,scaleType ->
                val index=offset+(s.scaleType.ordinal-15).coerceAtLeast(0)
                NativeUiView.Row(scaleType.displayName,"SCALE ${index+1}",
                    if(scaleType==s.scaleType) "SELECTED" else "",
                    if(scaleType==s.scaleType) 1 else 0) },
                keyboard=scale,lines=listOf("${s.scaleType.displayName} ${ScaleLogic.getMidiNoteName(s.rootNote)}"),
                firstRow=(s.scaleType.ordinal-15).coerceAtLeast(0),totalRows=ScaleType.values().size,
                hint="KEYS CHOOSE ROOT")
            52 -> PageData(rows=(0..15).map { index ->
                val type=EngineType.values().getOrNull(index)
                NativeUiView.Row(type?.name ?: if(index==15) "INIT SOUND" else "",
                    "KEY ${index+1}",if(type==t.engineType) "ACTIVE" else "",
                    (if(type==t.engineType) 2 else 0) or (if(type==null && index!=15) 16 else 0)) },
                lines=listOf(t.engineType.name.replace('_',' ')),hint="WHITE ENGINE  LAST INIT")
            53 -> PageData(rows=listOf("CLEAR","REVERSE","SHIFT LEFT","SHIFT RIGHT",
                "RANDOM","COOK","BEAT","EUCLID","LANE CLEAR","LANE REVERSE",
                "LANE FILL","LANE RANDOM","DOUBLE","HALF").mapIndexed { index,name ->
                NativeUiView.Row(name,"KEY ${index+1}",flags=if(index in listOf(5,6,7,11) ||
                    index in 8..10 && !drum(t)) 16 else 0) },
                cells=(0 until if(t.engineType==EngineType.SAMPLER) 16 else 8).map { index ->
                    NativeUiView.Cell(index,if(index==lane(t)) 2 else 0,"L${index+1}") },
                hint="WHITE TOOLS BLACK LANE")
            54 -> PageData(rows=listOf(
                NativeUiView.Row("SOURCE", listOf("MIC", "RESAMPLE", "SYSTEM")
                    .getOrElse(s.recordingSource) { "MIC" }),
                NativeUiView.Row("COUNT IN", "UNAVAILABLE", flags=16),
                NativeUiView.Row("RECORD", if(s.isRecording) "ON" else "OFF",
                    flags=if(s.isRecording) 2 else 0),
                NativeUiView.Row("MONITOR", "UNAVAILABLE", flags=16)),
                lines=listOf(if(s.isRecording) "RECORDING" else "READY",
                    if(s.isPlaying) "PLAYING" else "STOPPED"),
                hint="REC TO TOGGLE  STEPS SAVED")
            55 -> PageData(rows=trackRows.take(6),cells=t.songChainNames.take(16).mapIndexed { index,name ->
                NativeUiView.Cell(index,(if(name!=null) 1 else 64) or
                    (if(index==row) 2 else 0),"${index+1}") },
                hint="WHITE LOAD SLOT BLACK MUTE")
            56 -> PageData(rows=listOf("FX","SEL","ENV","LFO","EDIT","GLO","HOME","SAVE",
                "ARP","SEQ","PLAY","REC").mapIndexed { index,name ->
                NativeUiView.Row(name,"SHIFT ${index+1}",flags=if(buttons and (1 shl index)!=0) 1 else 0) },
                hint="SHIFT + BUTTON LOCKS LAYER")
            57,58,59,60,61,62,70,71 -> PageData(unavailable=!deviceMenu,
                hint=if(deviceMenu) "DEVICE CONTROLS" else "HOLD HOME FOR DEVICE MENU")
            63 -> PageData(lines=listOf("DELETE SAVED ITEM?","THIS CANNOT BE UNDONE",
                "OCT- CANCEL  OCT+ CONFIRM", "CANCEL", "DELETE"))
            64 -> PageData(lines=listOf(nameBuffer,"SELECT CURSOR PRESETS CHAR"),
                rows=listOf(NativeUiView.Row("NAME",nameBuffer,
                    if(nameNumeric) "123" else "ABC",a=if(nameNumeric) 1 else 0,
                    b=(nameTapKey+1).coerceAtLeast(0),c=nameTapIndex)),
                hint="K1 CURSOR K2 CHAR OCT+ SAVE")
            65 -> PageData(lines=listOf("UNDO","NO APP HISTORY"),unavailable=true)
            66,67 -> PageData(lines=listOf("TAKES","TAKES UNAVAILABLE"),unavailable=true)
            68,69 -> PageData(unavailable=true)
            else -> PageData(unavailable=true,hint="APP DATA UNAVAILABLE")
        }
    }
}
