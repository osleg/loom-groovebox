package com.groovebox

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import com.groovebox.ui.views.isBlackKey
import com.groovebox.utils.*
import com.groovebox.midi.MidiRouter
import com.groovebox.midi.MidiCommand
import com.groovebox.midi.MidiClockOut
import com.groovebox.midi.MidiConfigStore

class GrooveboxViewModel(
    private val nativeLib: NativeLib,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {
    private val recordedStepGuards = mutableMapOf<Pair<Int, Int>, Boolean>()
    val midiRouter = MidiRouter(nativeLib) { command ->
        handleMidiCommand(command)
    }

    var state by mutableStateOf(createInitialState())
        private set

    fun onStateChange(update: GrooveboxState) {
        state = update
        syncMidiClock()
    }

    fun updateState(transform: (GrooveboxState) -> GrooveboxState) {
        state = transform(state)
        syncMidiClock()
    }

    fun updateCurrentStep(newStep: Int) {
        if (state.currentStep != newStep) {
            state = state.copy(currentStep = newStep)
        }
    }

    fun setPlaybackState(isPlaying: Boolean, isRecording: Boolean = state.isRecording) {
        state = state.copy(isPlaying = isPlaying, isRecording = isRecording)
        syncMidiClock()
    }

    fun setSelectedTab(index: Int) {
        if (state.selectedTab != index) {
            state = state.copy(selectedTab = index)
        }
    }

    fun setSelectedTrack(index: Int) {
        if (state.selectedTrackIndex != index) {
            val track = state.tracks[index]
            val isDrum = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM
            
            // Re-sync active routings with the new track's engine type
            val stripAssignments = state.engineTypeStripAssignments[track.engineType] 
                ?: com.groovebox.utils.getDefaultStripAssignments(track.engineType)
            val knobAssignments = state.engineTypeKnobAssignments[track.engineType] 
                ?: com.groovebox.utils.getDefaultKnobAssignments(track.engineType)

            state = state.copy(
                selectedTrackIndex = index,
                stripRoutings = stripAssignments,
                knobRoutings = knobAssignments,
                gridMode = if (isDrum) GridMode.GRID_4X4 else state.gridMode
            )
        }
    }

    fun setSelectedFmDrumInstrument(trackIdx: Int, instIdx: Int) {
        state = state.copy(
            tracks = state.tracks.mapIndexed { idx, t ->
                if (idx == trackIdx) t.copy(selectedFmDrumInstrument = instIdx) else t
            }
        )
    }

    private fun createInitialState(): GrooveboxState {
        val tracks = List(8) { i ->
            val engineType = when(i) {
                0 -> EngineType.SUBTRACTIVE
                1 -> EngineType.FM
                2 -> EngineType.SAMPLER
                3 -> EngineType.GRANULAR
                4 -> EngineType.WAVETABLE
                5 -> EngineType.FM_DRUM
                6 -> EngineType.ANALOG_DRUM
                7 -> EngineType.MIDI
                else -> EngineType.SUBTRACTIVE
            }
            
            val params = mutableMapOf<Int, Float>()
            var padModTarget = 1 // Default Cutoff
            
            when(engineType) {
                EngineType.SUBTRACTIVE -> {
                    params[118] = 0.5f // Env Intensity
                    padModTarget = 1
                }
                EngineType.FM -> {
                    params[160] = 0.8f // Op 1 Lvl
                    params[166] = 0.4f // Op 2 Lvl
                    params[165] = 1.0f // Op 1 Ratio
                    params[171] = 2.0f // Op 2 Ratio
                    params[161] = 0.01f // Op 1 Atk
                    params[162] = 0.5f // Op 1 Dcy
                    params[9] = 0.5f   // Center Pan
                    padModTarget = 159 // Drive
                }
                EngineType.WAVETABLE -> padModTarget = 450 // Morph
                EngineType.SAMPLER -> padModTarget = 302 // Speed
                EngineType.GRANULAR -> padModTarget = 406 // Grain Size
                EngineType.AUDIO_IN -> padModTarget = 122 // Fold
                EngineType.SOUNDFONT -> padModTarget = 1 // Filter
                else -> {}
            }
            
            TrackState(id = i, engineType = engineType, parameters = params, padModTargetId = getDefaultPadModTarget(engineType))
        }

        val stripAssignments = EngineType.values().associateWith { getDefaultStripAssignments(it) }
        val knobAssignments = EngineType.values().associateWith { getDefaultKnobAssignments(it) }

        val firstTrackEngine = tracks[0].engineType
        return GrooveboxState(
            tracks = tracks, 
            tempo = 80.0f,
            engineTypeStripAssignments = stripAssignments,
            engineTypeKnobAssignments = knobAssignments,
            stripRoutings = stripAssignments[firstTrackEngine] ?: emptyList(),
            knobRoutings = knobAssignments[firstTrackEngine] ?: emptyList()
        )
    }

    fun sanitizeAndSetState(loadedState: GrooveboxState) {
        recordedStepGuards.clear()
        state = sanitizeGrooveboxState(loadedState)
        syncWithNative() // Ensure native engine is in sync after loading
    }

    fun syncWithNative() {
        syncNativeState(state, nativeLib)
    }

    fun toggleStep(trackIdx: Int, stepIdx: Int) {
        if (trackIdx !in state.tracks.indices) return
        val track = state.tracks[trackIdx]
        if (stepIdx !in 0..63) return

        val isSamplerChops = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) > 0.6f
        val isMultiTrack = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || isSamplerChops
        
        val currentStep = if (isMultiTrack) track.drumSteps[track.selectedFmDrumInstrument][stepIdx] else track.steps[stepIdx]
        val newActive = !currentStep.active
        
        if (isMultiTrack) {
            val instIdx = track.selectedFmDrumInstrument
            val drumNote = 60 + instIdx
            val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(drumNote) else currentStep.notes
            val newDrumSteps = track.drumSteps.mapIndexed { di, dsteps ->
                if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive, notes = finalNotes) else s }
                else dsteps
            }
            state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(drumSteps = newDrumSteps) else t })
            nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped, currentStep.subStepOffset, currentStep.noteOffsets.toFloatArray(), currentStep.noteVelocities.toFloatArray(), ((currentStep.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), currentStep.parameterLocks.keys.toIntArray(), currentStep.parameterLocks.values.toFloatArray())
        } else {
            val rootNote = 60 // Default note if empty
            val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(rootNote) else currentStep.notes
            val newSteps = track.steps.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive, notes = finalNotes) else s }
            state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(steps = newSteps) else t })
            nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped, currentStep.subStepOffset, currentStep.noteOffsets.toFloatArray(), currentStep.noteVelocities.toFloatArray(), ((currentStep.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), currentStep.parameterLocks.keys.toIntArray(), currentStep.parameterLocks.values.toFloatArray())
        }
    }


    /** Apply native recording changes to the latest state, including unselected lanes. */
    fun drainRecordedSteps() {
        val changes = nativeLib.drainRecordedSteps()
        if (changes.isEmpty()) return
        var tracks = state.tracks
        var cursor = 0
        while (cursor + 6 <= changes.size) {
            val trackIndex = changes[cursor++].toInt()
            val lane = changes[cursor++].toInt()
            val stepIndex = changes[cursor++].toInt()
            val active = changes[cursor++] != 0f
            val gate = changes[cursor++]
            val noteCount = changes[cursor++].toInt()
            if (noteCount < 0 || noteCount > (changes.size - cursor) / 4) break
            val notes = ArrayList<Int>(noteCount)
            val velocities = ArrayList<Float>(noteCount)
            val offsets = ArrayList<Float>(noteCount)
            val gates = ArrayList<Float>(noteCount)
            repeat(noteCount) {
                notes += changes[cursor++].toInt()
                velocities += changes[cursor++]
                offsets += changes[cursor++]
                gates += changes[cursor++]
            }
            val track = tracks.getOrNull(trackIndex) ?: continue
            val steps = (if (lane == -1) track.steps else track.drumSteps.getOrNull(lane)) ?: continue
            val old = steps.getOrNull(stepIndex) ?: continue
            val updated = old.copy(
                active = active,
                notes = notes,
                velocity = velocities.firstOrNull() ?: old.velocity,
                gate = gate,
                subStepOffset = offsets.firstOrNull() ?: old.subStepOffset,
                noteOffsets = offsets,
                noteVelocities = velocities,
                noteGates = gates.map { if (it < 0.01f) gate else it }
            )
            if (updated == old) continue
            if (lane == -1) recordedStepGuards[trackIndex to stepIndex] = active
            val updatedSteps = steps.toMutableList().also { it[stepIndex] = updated }
            val updatedTrack = if (lane == -1) track.copy(steps = updatedSteps)
                else track.copy(drumSteps = track.drumSteps.toMutableList().also { it[lane] = updatedSteps })
            tracks = tracks.toMutableList().also { it[trackIndex] = updatedTrack }
        }
        if (tracks !== state.tracks) state = state.copy(tracks = tracks)
    }

    fun pullTransportState() {
        val playing = nativeLib.getIsPlaying()
        val recording = nativeLib.getIsRecording()
        val recordingSample = nativeLib.getIsRecordingSample()
        
        if (state.isPlaying != playing || state.isRecording != recording || state.isRecordingSample != recordingSample) {
            state = state.copy(
                isPlaying = playing,
                isRecording = recording,
                isRecordingSample = recordingSample
            )
        }
    }

    // Parameter snapshots are a 1024-float JNI copy and only change on
    // native-side actions (preset load, MIDI learn, macro fan-out), so they are
    // polled a few times per second instead of on every UI tick.
    private var paramPollCounter = 0
    private var lastUiActivity = kotlin.time.TimeSource.Monotonic.markNow()

    /**
     * True while something on screen is actually moving (transport running or
     * a recent level/step/parameter change). Screens use this to poll fast
     * during playback and slowly when the app is sitting idle, which is what
     * keeps the CPU low on phone-class hardware.
     */
    val isUiActive: Boolean
        get() = state.isPlaying ||
            lastUiActivity.elapsedNow() < kotlin.time.Duration.parse("1s")

    fun pollEngineState() {
        drainRecordedSteps()
        pullTransportState()
        // MIDI clock output must keep ticking even when nothing visible changed.
        syncMidiClock()
        val focusedTrackIdx = state.selectedTrackIndex
        nativeLib.setFocusedTrack(focusedTrackIdx)
        
        val cpu = nativeLib.getCpuLoad()
        val track = state.tracks[focusedTrackIdx]
        
        var paramsChanged = false
        var updatedParams: Map<Int, Float>? = null
        if (paramPollCounter == 0) {
            // Pull modulated parameter values for visual consistency
            val engineParams = nativeLib.getAllTrackParameters(focusedTrackIdx)
            var anyDifferent = false
            engineParams.forEachIndexed { pid, v ->
                val old = track.parameters[pid]
                if (old != null && Math.abs(old - v) > 0.001f) anyDifferent = true
            }
            if (anyDifferent) {
                val merged = track.parameters.toMutableMap()
                engineParams.forEachIndexed { pid, v ->
                    val old = track.parameters[pid]
                    if (old != null && Math.abs(old - v) > 0.001f) merged[pid] = v
                }
                updatedParams = merged
                paramsChanged = true
            }
        }
        paramPollCounter = (paramPollCounter + 1) % 10
        
        // Pull real-time track levels for activity indicators
        val levels = nativeLib.getTrackLevels()
        var levelsChanged = false
        var activeChanged = false
        levels.forEachIndexed { idx, level ->
            if (Math.abs(level - state.trackLevels.getOrElse(idx) { 0f }) > 0.01f) {
                levelsChanged = true
            }
            val isNowActive = level > 0.01f
            if (isNowActive != (state.tracks.getOrNull(idx)?.isAudiblyActive ?: false)) {
                activeChanged = true
            }
        }
        
        // Pull active step bits from native for the focused track (Wait-free)
        val activeBits = if (state.isRecording) BooleanArray(0) else nativeLib.getAllStepActiveStates(focusedTrackIdx)
        var stepsChanged = false
        activeBits.forEachIndexed { si, active ->
            val key = focusedTrackIdx to si
            if (recordedStepGuards[key] == active) recordedStepGuards.remove(key)
            if (key !in recordedStepGuards && si < track.steps.size && track.steps[si].active != active) {
                stepsChanged = true
            }
        }
        
        // Playhead sync (native is the source of truth while playing)
        val drumLane = if (track.engineType == EngineType.FM_DRUM ||
            track.engineType == EngineType.ANALOG_DRUM ||
            (track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) > 0.6f))
            track.selectedFmDrumInstrument else -1
        val newStep = if (state.isPlaying) nativeLib.getCurrentStep(focusedTrackIdx, drumLane) else 0
        val stepChanged = newStep != state.currentStep
        // Quantise the CPU readout so a noisy meter cannot recompose the UI on
        // every poll while nothing is happening.
        val cpuChanged = Math.abs(cpu - state.cpuLoad) > 0.01f

        if (!paramsChanged && !levelsChanged && !activeChanged &&
            !stepsChanged && !stepChanged && !cpuChanged) {
            // Nothing visible changed: keep the same state object so Compose
            // can skip the whole tree. This is the idle-CPU fast path.
            return
        }
        lastUiActivity = kotlin.time.TimeSource.Monotonic.markNow()
        
        state = state.copy(
            cpuLoad = cpu,
            currentStep = newStep,
            trackLevels = if (levelsChanged) levels.toList() else state.trackLevels,
            tracks = state.tracks.mapIndexed { idx, t ->
                val isNowActive = levels.getOrElse(idx) { 0f } > 0.01f
                when {
                    idx == focusedTrackIdx && (paramsChanged || stepsChanged || activeChanged) ->
                        t.copy(
                            parameters = updatedParams ?: t.parameters,
                            isAudiblyActive = isNowActive,
                            steps = if (stepsChanged) {
                                t.steps.mapIndexed { si, step ->
                                    if (si < activeBits.size && (focusedTrackIdx to si) !in recordedStepGuards) step.copy(active = activeBits[si]) else step
                                }
                            } else t.steps
                        )
                    t.isAudiblyActive != isNowActive -> t.copy(isAudiblyActive = isNowActive)
                    else -> t
                }
            }
        )
        
        // Sync waveform if on Sampler/Granular and recording or focused
        if (track.engineType == EngineType.SAMPLER || track.engineType == EngineType.GRANULAR) {
            pullWaveform(focusedTrackIdx)
        }

    }

    fun pullWaveform(trackIdx: Int) {
        val waveform = nativeLib.getWaveform(trackIdx)
        if (waveform != null) {
            // We don't store waveform in state directly usually, it's often a separate Flow or managed by the screen
            // But if we want to ensure visual preview, we might need a way to pass it.
            // For now, let's assume the screen calls nativeLib.getWaveform directly or we add a waveform field to TrackState if needed.
            // Actually, looking at SamplerScreen, it might be using a local state.
        }
    }


    /**
     * Helper to determine which MIDI note a given 4x4 pad index (0-15) should trigger
     * based on the current track's engine type and scale settings.
     */
    fun getNoteForPadIndex(padIndex: Int, state: GrooveboxState): Int {
        val track = state.tracks[state.selectedTrackIndex]
        val maxPadIndex = when(state.gridMode) {
            GridMode.GRID_4X4 -> 15
            GridMode.GRID_6X6 -> 35
            GridMode.MAC_KEYS -> 45
            GridMode.TONNETZ -> 127 // Tonnetz handles itself elsewhere usually but for safety
        }
        if (padIndex < 0 || padIndex > maxPadIndex) return -1
        
        val samplerMode = track.parameters[320] ?: 0f
        val isChopMode = track.engineType == EngineType.SAMPLER && samplerMode >= 0.6f
        val numSlices = if (isChopMode) (((track.parameters[340] ?: 0f) * 14f).toInt() + 2) else 0

        return if (track.engineType == EngineType.FM_DRUM) {
            60 + (padIndex % 16)
        } else if (isChopMode) {
            if (padIndex < numSlices) 60 + padIndex else -1
        } else if (track.engineType == EngineType.ANALOG_DRUM) {
            val localIdx = if (padIndex >= 8) padIndex - 8 else padIndex
            if (localIdx < 6) {
                when(localIdx) {
                    0 -> 60 // Kick
                    1 -> 61 // Snare
                    2 -> 62 // Rim
                    3 -> 63 // Hat C
                    4 -> 64 // Hat O
                    5 -> 65 // Cymbal
                    else -> -1
                }
            } else -1
        } else {
            // Melodic mapping
            val count = maxOf(16, padIndex + 1)
            val scaleNotes = ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, count)
            scaleNotes.getOrElse(padIndex) { state.rootNote + padIndex }
        }
    }

    fun triggerPad(padIndex: Int, velocity: Int) {
        val note = getNoteForPadIndex(padIndex, state)
        if (note != -1) {
            nativeLib.triggerNote(state.selectedTrackIndex, note, velocity)
            state = state.copy(heldNotes = state.heldNotes + note)
        }
    }

    fun releasePad(padIndex: Int) {
        val note = getNoteForPadIndex(padIndex, state)
        if (note != -1) {
            nativeLib.releaseNote(state.selectedTrackIndex, note)
            state = state.copy(heldNotes = state.heldNotes - note)
        }
    }

    fun processMidiMessage(data: ByteArray, sourceDeviceId: String? = null) {
        midiRouter.processMidiMessage(data, state, sourceDeviceId)
    }

    // --- MIDI output pump -----------------------------------------------
    // The native sequencer enqueues MIDI note events; this thread drains them
    // and routes each to its track's chosen output device (or all devices).
    @Volatile
    private var midiManagerRef: com.groovebox.midi.MidiManager? = null

    @Volatile
    private var midiPumpStarted = false

    @Volatile
    private var midiClosed = false
    private val midiPumpLock = Any()
    private var midiPumpThread: Thread? = null

    /** 24 PPQN generator + transport messages (Phase 5). */
    private val midiClockOut = MidiClockOut()

    /**
     * Reconcile MIDI clock output with transport, tempo and global settings.
     * Cheap enough to call on every state change; also called from the UI poll
     * so a tempo knob move or transport change is picked up immediately.
     */
    fun syncMidiClock() {
        if (midiClosed) return
        val settings = MidiConfigStore.current()
        val hasOutput = midiManagerRef?.devices?.value?.any { it.isOutput } == true
        midiClockOut.sync(
            isRunning = state.isPlaying,
            tempo = state.tempo,
            clockEnabled = settings.clockOutEnabled && hasOutput,
            sendTransport = settings.clockOutStartStop
        )
    }

    fun attachMidiManager(manager: com.groovebox.midi.MidiManager) {
        if (midiClosed) return
        midiManagerRef = manager
        midiClockOut.attachSender(manager::sendMidi)
        syncMidiClock()
        if (midiPumpStarted) return
        midiPumpStarted = true
        midiPumpThread = Thread({
            while (!midiClosed) {
                // With no MIDI output device attached there is nothing to send,
                // so don't burn a JNI call 200 times a second on it.
                val hasOutput = midiManagerRef?.devices?.value?.any { it.isOutput } == true
                if (hasOutput) {
                    try {
                        synchronized(midiPumpLock) {
                            if (!midiClosed) pumpMidiOut()
                        }
                    } catch (e: Exception) {
                        // Never let output routing take down the app.
                    }
                }
                try {
                    Thread.sleep(if (hasOutput) 5 else 50)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "LoomMidiOutPump").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop MIDI work before the runtime releases its manager and native engine. */
    fun closeMidi() {
        if (midiClosed) return
        midiClosed = true
        midiClockOut.detachSender()
        midiClockOut.close()
        midiPumpThread?.interrupt()
        synchronized(midiPumpLock) { midiManagerRef = null }
        midiPumpThread = null
    }

    private fun pumpMidiOut() {
        val manager = midiManagerRef ?: return
        val events = nativeLib.fetchMidiEvents()
        if (events.isEmpty()) return

        val tracks = state.tracks
        var i = 0
        while (i + 4 < events.size) {
            val type = events[i]
            val channel = events[i + 1] and 0x0F
            val data1 = events[i + 2] and 0x7F
            val data2 = events[i + 3] and 0x7F
            val trackIdx = events[i + 4]
            i += 5

            val track = tracks.getOrNull(trackIdx)
            val deviceId = track?.midiOutDeviceId

            // Per-track output channel: 1..16 picks that channel, 17 ("All")
            // passes through on channels 1..8 by track index, anything else
            // keeps the channel the native sequencer produced.
            val configured = track?.midiOutChannel ?: 17
            val targetChannel = when {
                configured == 17 -> trackIdx
                configured in 1..16 -> configured - 1
                else -> channel
            }

            val status = (type and 0xF0) or (targetChannel and 0x0F)
            manager.sendMidiTo(
                deviceId,
                byteArrayOf(status.toByte(), data1.toByte(), data2.toByte())
            )
        }
    }

    private fun handleMidiCommand(command: MidiCommand) {
        when (command) {
            is MidiCommand.BankChange -> {
                state = state.copy(currentSequencerBank = command.bank)
            }
            is MidiCommand.TrackVolume -> {
                val newTracks = state.tracks.toMutableList()
                if (command.trackIdx in newTracks.indices) {
                    newTracks[command.trackIdx] = newTracks[command.trackIdx].copy(volume = command.volume)
                    state = state.copy(tracks = newTracks)
                }
            }
            is MidiCommand.ParameterChange -> {
                if (command.parameterId in -103..-100) {
                    val stripIdx = -(command.parameterId + 100)
                    val newValues = state.stripValues.toMutableList()
                    if (stripIdx in newValues.indices) {
                        newValues[stripIdx] = command.value
                        state = state.copy(stripValues = newValues)
                    }
                } else if (command.parameterId in -203..-200) {
                    val knobIdx = -(command.parameterId + 200)
                    val newValues = state.knobValues.toMutableList()
                    if (knobIdx in newValues.indices) {
                        newValues[knobIdx] = command.value
                        state = state.copy(knobValues = newValues)
                    }
                }
            }
            is MidiCommand.Transport -> {
                when (command.action) {
                    "PLAY" -> { state = state.copy(isPlaying = true); nativeLib.setPlaying(true) }
                    "STOP" -> { state = state.copy(isPlaying = false, isRecording = false); nativeLib.setPlaying(false); nativeLib.setIsRecording(false) }
                    "RECORD" -> { val newRec = !state.isRecording; state = state.copy(isRecording = newRec, isPlaying = if (newRec) true else state.isPlaying); nativeLib.setIsRecording(newRec); if (newRec) nativeLib.setPlaying(true) }
                }
                syncMidiClock()
            }
            is MidiCommand.MidiClockTick -> {
                // External clock drives the project tempo; the knob follows so
                // the UI tells the truth while synced.
                if (kotlin.math.abs(state.tempo - command.bpm) > 0.05f) {
                    state = state.copy(tempo = command.bpm)
                    nativeLib.setTempo(command.bpm)
                }
            }
            is MidiCommand.DrumMapLearn -> {
                val idx = command.trackIdx
                if (idx in state.tracks.indices) {
                    val track = state.tracks[idx]
                    // One input note maps to one row: drop any previous binding
                    // for this note before adding the new one.
                    val cleaned = track.drumNoteMap.filterKeys { it != command.note }
                    val updated = cleaned + (command.note to command.row)
                    state = state.copy(
                        tracks = state.tracks.mapIndexed { i, t ->
                            if (i == idx) t.copy(drumNoteMap = updated) else t
                        },
                        drumMapLearnRow = -1,
                        drumMapLearnTrack = -1
                    )
                }
            }
            is MidiCommand.MidiClockTransport -> {
                when (command.action) {
                    "START" -> {
                        nativeLib.setPlaying(true)
                        command.step?.let { nativeLib.jumpToStep(it) }
                        state = state.copy(
                            isPlaying = true,
                            currentStep = command.step ?: 0
                        )
                    }
                    "CONTINUE" -> {
                        nativeLib.setPlaying(true)
                        command.step?.let { nativeLib.jumpToStep(it) }
                        state = state.copy(isPlaying = true)
                    }
                    "STOP" -> {
                        nativeLib.setPlaying(false)
                        nativeLib.setIsRecording(false)
                        state = state.copy(isPlaying = false, isRecording = false)
                    }
                }
                syncMidiClock()
            }
            is MidiCommand.NextTrack -> { state = state.copy(selectedTrackIndex = (state.selectedTrackIndex + 1) % state.tracks.size) }
            is MidiCommand.PreviousTrack -> { 
                val newIdx = if (state.selectedTrackIndex == 0) state.tracks.size - 1 else state.selectedTrackIndex - 1
                state = state.copy(selectedTrackIndex = newIdx) 
            }
            is MidiCommand.SelectTrack -> {
                if (command.trackIdx in state.tracks.indices) {
                    setSelectedTrack(command.trackIdx)
                }
            }
            is MidiCommand.ToggleMidiLearn -> { val nl = !state.midiLearnActive; state = state.copy(midiLearnActive = nl, midiLearnStep = if (nl) 1 else 0, midiLearnSelectedStrip = null) }
            is MidiCommand.MidiLearnSelect -> { if (state.midiLearnActive && state.midiLearnStep == 1) state = state.copy(midiLearnSelectedStrip = command.stripIdx, midiLearnStep = 2) }
            is MidiCommand.MacroValue -> { val mi = command.macroIdx; if (mi in state.macros.indices) { val nm = state.macros.toMutableList(); nm[mi] = nm[mi].copy(value = command.value); state = state.copy(macros = nm) } }
            is MidiCommand.NoteTriggered -> { state = state.copy(lastMidiNote = command.note, lastMidiVelocity = command.velocity) }
            is MidiCommand.StepToggle -> { 
                // We need the toggleStep logic here or accessible. 
                // For now I will manually implement it or leave it as it matches MainActivity.
                // Re-implementing toggleStep logic in VM is better anyway for shared logic.
                val trackIdx = state.selectedTrackIndex
                val track = state.tracks[trackIdx]
                val stepIdx = command.stepIdx
                val isDrum = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM
                if (isDrum) {
                    val instIdx = track.selectedFmDrumInstrument
                    val currentDrumSteps = track.drumSteps[instIdx]
                    val step = currentDrumSteps[stepIdx]
                    val newActive = !step.active
                    val newDrumSteps = track.drumSteps.mapIndexed { idx, ds ->
                        if (idx == instIdx) ds.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive) else s }
                        else ds
                    }
                    val finalNotes = if (newActive && step.notes.isEmpty()) listOf(60) else step.notes
                    state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(drumSteps = newDrumSteps) else t })
                    nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), step.velocity, step.ratchet, step.punch, step.probability, step.gate, step.isSkipped, step.subStepOffset, step.noteOffsets.toFloatArray(), step.noteVelocities.toFloatArray(), ((step.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), step.parameterLocks.keys.toIntArray(), step.parameterLocks.values.toFloatArray())
                } else {
                    val step = track.steps[stepIdx]
                    val newActive = !step.active
                    val finalNotes = if (newActive && step.notes.isEmpty()) listOf(60) else step.notes
                    val newSteps = track.steps.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive, notes = finalNotes) else s }
                    state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(steps = newSteps) else t })
                    nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), step.velocity, step.ratchet, step.punch, step.probability, step.gate, step.isSkipped, step.subStepOffset, step.noteOffsets.toFloatArray(), step.noteVelocities.toFloatArray(), ((step.noteGates as Any?) as? List<Float> ?: emptyList()).toFloatArray(), step.parameterLocks.keys.toIntArray(), step.parameterLocks.values.toFloatArray())
                }
            }
        }
    }
}
