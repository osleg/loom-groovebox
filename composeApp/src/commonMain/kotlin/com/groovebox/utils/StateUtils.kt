package com.groovebox.utils

import com.groovebox.GrooveboxState
import com.groovebox.EngineType
import com.groovebox.TrackState
import com.groovebox.NativeLib
import com.groovebox.MacroState
import com.groovebox.StripRouting
import com.groovebox.LfoState
import com.groovebox.RoutingConnection

fun getDefaultStripAssignments(engineType: EngineType): List<StripRouting> {
    val params = when(engineType) {
        EngineType.SUBTRACTIVE -> listOf("Cutoff" to 1, "Resonance" to 113, "Filt Env Int" to 118, "Detune" to 106)
        EngineType.FM -> listOf("Feedback" to 154, "Brightness" to 157, "Cutoff" to 151, "Resonance" to 152)
        EngineType.WAVETABLE -> listOf("Filter" to 458, "Resonance" to 459, "Warp" to 465, "Drive" to 467)
        EngineType.GRANULAR -> listOf("Position" to 400, "Density" to 407, "Count" to 418, "Reverse" to 420)
        EngineType.SAMPLER -> listOf("Pitch" to 304, "Stretch" to 301, "Filter" to 310, "Resonance" to 311)
        EngineType.FM_DRUM -> listOf("Kick Lvl" to 205, "Kick Snap" to 201, "Tom Pitch" to 220, "Tom Decay" to 222)
        EngineType.ANALOG_DRUM -> listOf("Kick Decay" to 600, "Kick Tone" to 601, "Kick Tune" to 602, "Kick Gain" to 605)
        EngineType.AUDIO_IN -> listOf("Gain" to 121, "Cutoff" to 112, "Resonance" to 113, "Env Amt" to 118)
        EngineType.SOUNDFONT -> listOf("Cutoff" to 1, "Resonance" to 2, "Env Amt" to 3, "Detune" to 6)
        else -> emptyList()
    }
    return params.mapIndexed { idx, (name, pid) -> 
        StripRouting(stripIndex = idx, parameterName = name, targetType = 1, targetId = pid) 
    }
}

fun getDefaultKnobAssignments(engineType: EngineType): List<StripRouting> {
    val params = when(engineType) {
        EngineType.SUBTRACTIVE -> listOf("Amp Atk" to 100, "Amp Rel" to 103, "Filt Atk" to 114, "Filt Rel" to 117)
        EngineType.FM -> listOf("Attack" to 100, "Decay" to 101, "Sustain" to 102, "Release" to 103)
        EngineType.WAVETABLE -> listOf("Bits" to 468, "SampleRate" to 469, "Attack" to 454, "Release" to 457)
        EngineType.GRANULAR -> listOf("Spray" to 415, "Width" to 419, "Attack" to 425, "Release" to 428)
        EngineType.SAMPLER -> listOf("Attack" to 300, "Release" to 314, "Env Int" to 312, "Glide" to 355)
        EngineType.FM_DRUM -> listOf("Hihat Pitch" to 230, "Hihat Decay" to 232, "Perc Snap" to 261, "Perc Level" to 265)
        EngineType.ANALOG_DRUM -> listOf("Rim Decay" to 620, "Rim Color" to 621, "CH Decay" to 630, "CH Color" to 631)
        EngineType.AUDIO_IN -> listOf("Attack" to 100, "Decay" to 101, "Sustain" to 102, "Release" to 103)
        EngineType.SOUNDFONT -> listOf("Attack" to 100, "Decay" to 101, "Sustain" to 102, "Release" to 103)
        else -> emptyList()
    }
    return params.mapIndexed { idx, (name, pid) -> 
        StripRouting(stripIndex = idx + 4, parameterName = name, targetType = 1, targetId = pid) 
    }
}

fun getDefaultPadModTarget(engineType: EngineType): Int {
    return when(engineType) {
        EngineType.SUBTRACTIVE -> 1
        EngineType.FM -> 159
        EngineType.WAVETABLE -> 450
        EngineType.SAMPLER -> 302
        EngineType.GRANULAR -> 406
        EngineType.AUDIO_IN -> 122
        EngineType.SOUNDFONT -> 1
        EngineType.FM_DRUM -> -1
        EngineType.ANALOG_DRUM -> -1
        else -> -1
    }
}

fun sanitizeGrooveboxState(state: GrooveboxState): GrooveboxState {
    val sanitizedTracks = state.tracks.map { track ->
        val volCandidate = (track.volume as Any?) as? Float ?: 0.7f
        val safeVol = if (volCandidate < 0.05f) 0.7f else volCandidate
        val newParams = ((track.parameters as Any?) as? Map<Int, Float> ?: emptyMap()).toMutableMap()
        
        // Ensure volume parameter matches
        newParams[0] = safeVol
        
        // Safety: Ensure critical parameters are valid
        if (track.engineType == EngineType.FM_DRUM && (newParams[200] ?: 0f) < 0.01f) {
            for (i in 0 until 8) {
                val base = 200 + i * 10
                newParams[base] = 0.5f 
                newParams[base + 1] = 0.5f
                newParams[base + 2] = 0.5f
                newParams[base + 5] = 0.7f
            }
        }

        track.copy(
            id = (track.id as Any?) as? Int ?: 0,
            volume = safeVol,
            pan = (track.pan as Any?) as? Float ?: 0.5f,
            humanize = (track.humanize as Any?) as? Float ?: 0.0f,
            engineType = (track.engineType as Any?) as? EngineType ?: EngineType.SUBTRACTIVE,
            isActive = (track.isActive as Any?) as? Boolean ?: true,
            isMuted = (track.isMuted as Any?) as? Boolean ?: false,
            isSoloed = (track.isSoloed as Any?) as? Boolean ?: false,
            isDisabled = (track.isDisabled as Any?) as? Boolean ?: false,
            transpose = (track.transpose as Any?) as? Int ?: 0,
            steps = (track.steps as Any?) as? List<com.groovebox.StepState> ?: List(64) { com.groovebox.StepState() },
            drumSteps = (track.drumSteps as Any?) as? List<List<com.groovebox.StepState>> ?: List(16) { List(64) { com.groovebox.StepState() } },
            patternLength = (track.patternLength as Any?) as? Int ?: 16,
            numPages = (track.numPages as Any?) as? Int ?: 1,
            stepsPerPage = (track.stepsPerPage as Any?) as? Int ?: 16,
            selectedFmDrumInstrument = (track.selectedFmDrumInstrument as Any?) as? Int ?: 0,
            selectedFmPreset = (track.selectedFmPreset as Any?) as? Int,
            arpConfig = (track.arpConfig as Any?) as? com.groovebox.ArpConfig ?: com.groovebox.ArpConfig(),
            mutatedNotes = (track.mutatedNotes as Any?) as? Map<Int, Int> ?: emptyMap(),
            fmCarrierMask = (track.fmCarrierMask as Any?) as? Int ?: 1,
            fmActiveMask = (track.fmActiveMask as Any?) as? Int ?: 63,
            useEnvelope = (track.useEnvelope as Any?) as? Boolean ?: true,
            fxSends = (track.fxSends as Any?) as? List<Float> ?: List(18) { 0.0f },
            fxMix = (track.fxMix as Any?) as? List<Float> ?: List(18) { 0.0f },
            midiInChannel = (track.midiInChannel as Any?) as? Int ?: 17,
            midiOutChannel = (track.midiOutChannel as Any?) as? Int ?: 1,
            midiInDeviceId = (track.midiInDeviceId as Any?) as? String,
            midiOutDeviceId = (track.midiOutDeviceId as Any?) as? String,
            lastSamplePath = (track.lastSamplePath as Any?) as? String ?: "",
            activeWavetableName = (track.activeWavetableName as Any?) as? String ?: "Basic",
            filterMode = (track.filterMode as Any?) as? Int ?: 0,
            clockMultiplier = (track.clockMultiplier as Any?) as? Float ?: 1.0f,
            soundFontPath = (track.soundFontPath as Any?) as? String,
            soundFontPresetIndex = (track.soundFontPresetIndex as Any?) as? Int ?: 0,
            soundFontPresetName = (track.soundFontPresetName as Any?) as? String ?: "None",
            soundFontMapping = (track.soundFontMapping as Any?) as? Map<Int, Int> ?: emptyMap(),
            drumNoteMap = (track.drumNoteMap as Any?) as? Map<Int, Int> ?: emptyMap(),
            parameters = newParams,
            sequenceProbability = (track.sequenceProbability as Any?) as? Float ?: 1.0f,
            isChainEnabled = (track.isChainEnabled as Any?) as? Boolean ?: false,
            songChainNames = (track.songChainNames as Any?) as? List<String?> ?: List(16) { null },
            songChainLength = (track.songChainLength as Any?) as? Int ?: 1,
            padModTargetId = (track.padModTargetId as Any?) as? Int ?: getDefaultPadModTarget(track.engineType),
            subTrackNames = (track.subTrackNames as Any?) as? Map<Int, String> ?: emptyMap()
        )
    }
    
    val currentStripAssignments = (state.engineTypeStripAssignments as Any?) as? Map<EngineType, List<StripRouting>> ?: emptyMap()
    val stripAssignments = currentStripAssignments.toMutableMap()
    
    val currentKnobAssignments = (state.engineTypeKnobAssignments as Any?) as? Map<EngineType, List<StripRouting>> ?: emptyMap()
    val knobAssignments = currentKnobAssignments.toMutableMap()
    
    // Ensure all engines have default mappings if missing or incorrectly mapped to Subtractive
    val subtractiveDefaults = getDefaultStripAssignments(EngineType.SUBTRACTIVE)
    val subtractiveKnobDefaults = getDefaultKnobAssignments(EngineType.SUBTRACTIVE)
    
    EngineType.values().forEach { type ->
        val currentStrips = stripAssignments[type]
        val currentKnobs = knobAssignments[type]
        
        // Reset if empty OR if they appear to be a Subtractive leak (labels match exactly but engine is different)
        // Subtractive labels are: Cutoff, Resonance, Filt Env Int, Detune
        val subLabels = listOf("Cutoff", "Resonance", "Filt Env Int", "Detune")
        val isStripLeak = type != EngineType.SUBTRACTIVE && currentStrips?.all { subLabels.contains(it.parameterName) } == true
        
        val subKnobLabels = listOf("Amp Atk", "Amp Rel", "Filt Atk", "Filt Rel")
        val isKnobLeak = type != EngineType.SUBTRACTIVE && currentKnobs?.all { subKnobLabels.contains(it.parameterName) } == true

        if (currentStrips.isNullOrEmpty() || (currentStrips.size >= 4 && isStripLeak)) {
            stripAssignments[type] = getDefaultStripAssignments(type)
        }
        if (currentKnobs.isNullOrEmpty() || (currentKnobs.size >= 4 && isKnobLeak)) {
            knobAssignments[type] = getDefaultKnobAssignments(type)
        }
    }

    val loadedMasterVol = (state.masterVolume as Any?) as? Float ?: 0.8f
    val safeMasterVol = if (loadedMasterVol < 0.1f) 0.8f else loadedMasterVol

    val selectedIdx = (state.selectedTrackIndex as Any?) as? Int ?: 0
    val activeEngine = sanitizedTracks.getOrNull(selectedIdx)?.engineType ?: EngineType.SUBTRACTIVE
    
    // Fix top-level routings if they are leaked or empty
    val currentTopStrips = (state.stripRoutings as Any?) as? List<StripRouting> ?: emptyList()
    val subLabels = listOf("Cutoff", "Resonance", "Filt Env Int", "Detune")
    val isTopStripLeak = activeEngine != EngineType.SUBTRACTIVE && currentTopStrips.size >= 4 && 
                         currentTopStrips.take(4).all { subLabels.contains(it.parameterName) }
    val isTopStripEmpty = currentTopStrips.all { it.parameterName.isNullOrEmpty() }
    
    val finalTopStrips = if (isTopStripLeak || isTopStripEmpty) {
        stripAssignments[activeEngine] ?: getDefaultStripAssignments(activeEngine)
    } else {
        currentTopStrips
    }

    val currentTopKnobs = (state.knobRoutings as Any?) as? List<StripRouting> ?: emptyList()
    val subKnobLabels = listOf("Amp Atk", "Amp Rel", "Filt Atk", "Filt Rel")
    val isTopKnobLeak = activeEngine != EngineType.SUBTRACTIVE && currentTopKnobs.size >= 4 && 
                        currentTopKnobs.take(4).all { subKnobLabels.contains(it.parameterName) }
    val isTopKnobEmpty = currentTopKnobs.all { it.parameterName.isNullOrEmpty() }

    val finalTopKnobs = if (isTopKnobLeak || isTopKnobEmpty) {
        knobAssignments[activeEngine] ?: getDefaultKnobAssignments(activeEngine)
    } else {
        currentTopKnobs
    }

    return state.copy(
        tracks = sanitizedTracks,
        tempo = (state.tempo as Any?) as? Float ?: 80.0f,
        isPlaying = false,
        currentStep = (state.currentStep as Any?) as? Int ?: 0,
        selectedTab = 0,
        masterVolume = safeMasterVol,
        globalTranspose = (state.globalTranspose as Any?) as? Int ?: 0,
        globalParameters = (state.globalParameters as Any?) as? Map<Int, Float> ?: emptyMap(),
        sidechainSourceTrack = (state.sidechainSourceTrack as Any?) as? Int ?: -1,
        sidechainSourceDrumIdx = (state.sidechainSourceDrumIdx as Any?) as? Int ?: 0,
        isSelectingSidechain = (state.isSelectingSidechain as Any?) as? Boolean ?: false,
        scaleType = (state.scaleType as Any?) as? com.groovebox.ScaleType ?: com.groovebox.ScaleType.MAJOR,
        rootNote = (state.rootNote as Any?) as? Int ?: 48,
        padPageCount = (state.padPageCount as Any?) as? Int ?: 1,
        currentPadPage = (state.currentPadPage as Any?) as? Int ?: 0,
        padColor = (state.padColor as Any?) as? Long ?: 0xFFBB86FC,
        stripRoutings = finalTopStrips,
        stripValues = (state.stripValues as Any?) as? List<Float> ?: List(4) { 0.5f },
        knobRoutings = finalTopKnobs,
        knobValues = (state.knobValues as Any?) as? List<Float> ?: List(4) { 0.5f },
        heldNotes = (state.heldNotes as Any?) as? Set<Int> ?: emptySet(),
        selectedTrackIndex = (state.selectedTrackIndex as Any?) as? Int ?: 0,
        midiLearnActive = (state.midiLearnActive as Any?) as? Boolean ?: false,
        midiLearnStep = (state.midiLearnStep as Any?) as? Int ?: 0,
        midiLearnSelectedStrip = (state.midiLearnSelectedStrip as Any?) as? Int,
        focusedParameter = (state.focusedParameter as Any?) as? Int,
        currentSequencerBank = (state.currentSequencerBank as Any?) as? Int ?: 0,
        is64StepView = (state.is64StepView as Any?) as? Boolean ?: false,
        echoModeActive = (state.echoModeActive as Any?) as? Boolean ?: false,
        swing = (state.swing as Any?) as? Float ?: 0f,
        playbackDirection = (state.playbackDirection as Any?) as? Int ?: 0,
        isRandomOrder = (state.isRandomOrder as Any?) as? Boolean ?: false,
        isJumpMode = (state.isJumpMode as Any?) as? Boolean ?: false,
        isJumpHold = (state.isJumpHold as Any?) as? Boolean ?: false,
        jumpModeWaitingForTap = (state.jumpModeWaitingForTap as Any?) as? Boolean ?: false,
        maxChainLength = (state.maxChainLength as Any?) as? Int ?: 16,
        padXAttenuation = (state.padXAttenuation as Any?) as? Float,
        padYAttenuation = (state.padYAttenuation as Any?) as? Float,
        patternLength = (state.patternLength as Any?) as? Int ?: 16,
        isRecording = (state.isRecording as Any?) as? Boolean ?: false,
        isParameterLocking = (state.isParameterLocking as Any?) as? Boolean ?: false,
        isResampling = (state.isResampling as Any?) as? Boolean ?: false,
        recordingSource = (state.recordingSource as Any?) as? Int ?: 0,
        isRecordingSample = (state.isRecordingSample as Any?) as? Boolean ?: false,
        isRecordingLocked = (state.isRecordingLocked as Any?) as? Boolean ?: false,
        recordingTrackIndex = (state.recordingTrackIndex as Any?) as? Int ?: -1,
        lockedParamsThisStep = (state.lockedParamsThisStep as Any?) as? Set<Int> ?: emptySet(),
        engineTypeStripAssignments = stripAssignments,
        engineTypeKnobAssignments = knobAssignments,
        lfos = (state.lfos as Any?) as? List<LfoState> ?: List(6) { LfoState() },
        macros = (state.macros as Any?) as? List<com.groovebox.MacroState> ?: List(8) { com.groovebox.MacroState() },
        routingConnections = (state.routingConnections as Any?) as? List<RoutingConnection> ?: emptyList(),
        fxChain = (state.fxChain as Any?) as? Map<Int, Int> ?: emptyMap(),
        fxChainSlots = (state.fxChainSlots as Any?) as? List<Int> ?: List(5) { -1 },
        fxEnabled = ((state.fxEnabled as Any?) as? List<Boolean> ?: emptyList())
            .let { saved -> List(18) { i -> saved.getOrNull(i) ?: true } },
        focusedValue = (state.focusedValue as Any?) as? String,
        lockingTarget = (state.lockingTarget as Any?) as? Pair<Int, Int>,
        lfoLearnActive = (state.lfoLearnActive as Any?) as? Boolean ?: false,
        lfoLearnLfoIndex = (state.lfoLearnLfoIndex as Any?) as? Int ?: -1,
        macroLearnActive = (state.macroLearnActive as Any?) as? Boolean ?: false,
        macroLearnMacroIndex = (state.macroLearnMacroIndex as Any?) as? Int ?: -1,
        macroLearnTargetIndex = (state.macroLearnTargetIndex as Any?) as? Int ?: -1,
        macroSourceLearnActive = (state.macroSourceLearnActive as Any?) as? Boolean ?: false,
        macroSourceLearnIndex = (state.macroSourceLearnIndex as Any?) as? Int ?: -1,
        isPadModLearnActive = (state.isPadModLearnActive as Any?) as? Boolean ?: false,
        padModLearnBank = (((state.padModLearnBank as Any?) as? Int) ?: -1).coerceIn(-1, 1),
        polyPadMod = (state.polyPadMod as Any?) as? Boolean ?: true,
        lastMidiNote = (state.lastMidiNote as Any?) as? Int ?: -1,
        lastMidiVelocity = (state.lastMidiVelocity as Any?) as? Int ?: 0,
        copiedSteps = (state.copiedSteps as Any?) as? List<com.groovebox.StepState>,
        copiedDrumSteps = (state.copiedDrumSteps as Any?) as? List<List<com.groovebox.StepState>>,
        gridMode = (state.gridMode as Any?) as? com.groovebox.GridMode ?: com.groovebox.GridMode.GRID_4X4,
        playLayout = (state.playLayout as Any?) as? com.groovebox.PlayLayout ?: com.groovebox.PlayLayout.GRID,
        padMode = (state.padMode as Any?) as? com.groovebox.PadMode ?: com.groovebox.PadMode.AUTO,
        // Transient wizard state never survives a load.
        drumMapLearnRow = -1,
        drumMapLearnTrack = -1,
        splitLeft = ((state.splitLeft as Any?) as? com.groovebox.SplitZone ?: com.groovebox.SplitZone(0, 0))
            .let { it.copy(
                trackIndex = it.trackIndex.coerceIn(0, 7),
                octave = it.octave.coerceIn(-3, 3),
                modTargetId = it.modTargetId.coerceIn(-1, 2999),
                modIntensity = it.modIntensity.coerceIn(0f, 1f),
                bendRange = it.bendRange.coerceIn(0f, 24f)) },
        splitRight = ((state.splitRight as Any?) as? com.groovebox.SplitZone ?: com.groovebox.SplitZone(1, 1))
            .let { it.copy(
                trackIndex = it.trackIndex.coerceIn(0, 7),
                octave = it.octave.coerceIn(-3, 3),
                modTargetId = it.modTargetId.coerceIn(-1, 2999),
                modIntensity = it.modIntensity.coerceIn(0f, 1f),
                bendRange = it.bendRange.coerceIn(0f, 24f)) },
        uiLayoutMode = (state.uiLayoutMode as Any?) as? Int ?: 0,
        showCpuMonitor = (state.showCpuMonitor as Any?) as? Boolean ?: true,
        performanceProfile = (((state.performanceProfile as Any?) as? Int) ?: 0).coerceIn(0, 2),
        isPerformanceMode = (state.isPerformanceMode as Any?) as? Boolean ?: false,
        isKeyboardModeEnabled = (state.isKeyboardModeEnabled as Any?) as? Boolean ?: false,
        cpuLoad = (state.cpuLoad as Any?) as? Float ?: 0f,
        trackLevels = (state.trackLevels as Any?) as? List<Float> ?: List(8) { 0f },
        importedFmPresets = (state.importedFmPresets as Any?) as? List<Map<String, Any>> ?: emptyList(),
        appVersion = (state.appVersion as Any?) as? String ?: Version.APP_VERSION
    )
}

fun syncNativeState(state: GrooveboxState, nativeLib: NativeLib) {
    nativeLib.setTempo(state.tempo)
    nativeLib.setMasterVolume(state.masterVolume)
    nativeLib.setScaleConfig(state.rootNote, state.scaleType.intervals.toIntArray())
    nativeLib.setRecordingSource(state.recordingSource)
    nativeLib.setSwing(state.swing)
    
    // Sync Global Parameters (sent to Track 0)
    state.globalParameters.forEach { (pid, v) -> nativeLib.setParameter(0, pid, v) }

    // Sync Tracks
    state.tracks.forEachIndexed { trackIdx, t ->
        // Ensure engine is set FIRST
        nativeLib.setEngineType(trackIdx, t.engineType.ordinal)
        // Safety: Clamp volume to prevent NaNs/Inf
        val safeVol = if (t.volume.isNaN() || t.volume.isInfinite()) 0.45f else t.volume.coerceIn(0f, 2f)
        
        nativeLib.setTrackVolume(trackIdx, safeVol)
        nativeLib.setTrackTranspose(trackIdx, t.transpose)
        nativeLib.setTrackActive(trackIdx, if (t.isDisabled) false else t.isActive)
        nativeLib.setTrackDisabled(trackIdx, t.isDisabled)
        nativeLib.setTrackPan(trackIdx, t.pan)
        nativeLib.setSelectedFmDrumInstrument(trackIdx, t.selectedFmDrumInstrument)
        
        val macroIds = if (t.engineType == EngineType.SUBTRACTIVE) setOf(290, 291, 292) else emptySet()
        t.parameters.forEach { (pid, v) ->
            if (pid in macroIds) return@forEach
            val safeVal = if (v.isNaN() || v.isInfinite()) 0.0f else v
            nativeLib.setParameter(trackIdx, pid, safeVal) 
        }
        // Apply the oscillator macros last: they fan out to 190-192 / 180-182 /
        // 170-172, so they must win over the individual values regardless of
        // the order the parameter map was built in.
        t.parameters.forEach { (pid, v) ->
            if (pid !in macroIds) return@forEach
            val safeVal = if (v.isNaN() || v.isInfinite()) 0.0f else v
            nativeLib.setParameter(trackIdx, pid, safeVal)
        }
        
        nativeLib.setPatternLength(trackIdx, t.patternLength.coerceIn(4, 64))
        
        // Sync Arp
        nativeLib.setArpConfig(
            trackIdx, 
            t.arpConfig.mode.ordinal, 
            t.arpConfig.octaves, 
            t.arpConfig.inversion,
            t.arpConfig.isLatched,
            t.arpConfig.isMutated,
            t.arpConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(),
            t.arpConfig.randomSequence.toIntArray(),
            t.arpConfig.gateLengths.toFloatArray(),
            t.arpConfig.probability,
            t.arpConfig.weird
        )
        nativeLib.setArpRate(trackIdx, t.arpConfig.arpRate, t.arpConfig.arpDivisionMode)
        nativeLib.setChordProgConfig(trackIdx, t.arpConfig.isChordProgEnabled, t.arpConfig.chordProgMood, t.arpConfig.chordProgComplexity)
        
        // E. Steps & Automation (Lock Parameters)
        if (t.engineType == EngineType.FM_DRUM || t.engineType == EngineType.ANALOG_DRUM || t.engineType == EngineType.SAMPLER) {
             // For Drum/Sampler tracks, we must sync ALL 16 internal sequencers
             for (instIdx in 0 until 16) {
                 val voiceSteps = t.drumSteps.getOrNull(instIdx) ?: emptyList()
                 voiceSteps.forEachIndexed { stepIdx, s ->
                     nativeLib.setStep(
                         trackIdx, 
                         stepIdx, 
                         s.active, 
                         intArrayOf(60 + instIdx), 
                         s.velocity, 
                         s.ratchet, 
                         s.punch, 
                         s.probability, 
                         s.gate,
                         s.isSkipped,
                         s.subStepOffset
                     )
                     s.parameterLocks.forEach { (pid, valAmt) ->
                         nativeLib.setParameterLock(trackIdx, stepIdx, pid, valAmt)
                     }
                 }
             }
        } else {
             // Standard Tracks
             t.steps.forEachIndexed { stepIdx, s ->
                 val isActiveWithNotes = s.active && s.notes.isNotEmpty()
                 nativeLib.setStep(trackIdx, stepIdx, isActiveWithNotes, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped, s.subStepOffset)
                 
                 // SYNC P-LOCKS
                 s.parameterLocks.forEach { (pid, valAmt) ->
                     nativeLib.setParameterLock(trackIdx, stepIdx, pid, valAmt)
                 }
             }
        }

        // F. Sample/Wavetable/SoundFont
        if ((t.lastSamplePath ?: "").isNotEmpty()) {
            if (t.engineType == EngineType.WAVETABLE) {
                nativeLib.loadWavetable(trackIdx, t.lastSamplePath ?: "")
            } else if (t.engineType == EngineType.SAMPLER || t.engineType == EngineType.GRANULAR) {
                nativeLib.loadSample(trackIdx, t.lastSamplePath ?: "")
            }
        }
        if ((t.soundFontPath ?: "").isNotEmpty() && t.engineType == EngineType.SOUNDFONT) {
            nativeLib.loadSoundFont(trackIdx, t.soundFontPath ?: "")
            nativeLib.setSoundFontPreset(trackIdx, t.soundFontPresetIndex)
            (t.soundFontMapping ?: emptyMap()).forEach { (knobId, genId) ->
                nativeLib.setSoundFontMapping(trackIdx, knobId, genId)
            }
        }

        // G. FX Sends
        t.fxSends.forEachIndexed { fxIdx, sendAmt ->
            nativeLib.setParameter(trackIdx, 2000 + (fxIdx * 10), sendAmt)
        }
    }

    // 3. LFOs
    state.lfos.forEachIndexed { i, lfo ->
        nativeLib.setGenericLfoParam(i, 0, lfo.rate)
        nativeLib.setGenericLfoParam(i, 1, lfo.depth)
        nativeLib.setGenericLfoParam(i, 2, lfo.shape.toFloat())
        nativeLib.setGenericLfoParam(i, 3, if (lfo.sync) 1.0f else 0.0f)
    }

    // 4. Macros
    state.macros.forEachIndexed { i, m ->
        nativeLib.setMacroSource(i, m.sourceType, m.sourceIndex, m.sourceTrackIndex)
        nativeLib.setMacroValue(i, m.value)
    }
    
    // 5. Routing Matrix
    state.routingConnections.forEach { connection ->
        nativeLib.setRouting(connection.destTrack, -1, connection.source, connection.destParam, connection.amount, -1)
    }

    // 6. FX Chain
    val activeSlots = (state.fxChainSlots ?: emptyList()).filter { it != -1 }
    if (activeSlots.isNotEmpty()) {
        for (i in 0 until activeSlots.size - 1) {
            nativeLib.setFxChain(activeSlots[i], activeSlots[i+1])
        }
    }

    // 6b. FX enable mask (performance)
    val fxEnabled = state.fxEnabled
    for (fxIdx in 0 until 18) {
        nativeLib.setFxEnabled(fxIdx, fxEnabled.getOrNull(fxIdx) ?: true)
    }

    // 6c. Performance profile
    nativeLib.setPerformanceProfile(state.performanceProfile.coerceIn(0, 2))

    // 7. Sidechain
    nativeLib.setSidechainConfig(state.sidechainSourceTrack, state.sidechainSourceDrumIdx)
}

fun toggleStep(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, trackIdx: Int, stepIdx: Int) {
    if (trackIdx !in state.tracks.indices) return
    val track = state.tracks[trackIdx]
    
    val newState = if (track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || track.engineType == EngineType.SAMPLER) {
        val instrumentIdx = 0 
        val drumSteps = track.drumSteps.toMutableList()
        val voiceSteps = drumSteps[instrumentIdx].toMutableList()
        val step = voiceSteps[stepIdx]
        val newStep = step.copy(active = !step.active, notes = if (!step.active) listOf(60 + instrumentIdx) else emptyList())
        voiceSteps[stepIdx] = newStep
        drumSteps[instrumentIdx] = voiceSteps
        
        val newTrack = track.copy(drumSteps = drumSteps)
        val newTracks = state.tracks.toMutableList()
        newTracks[trackIdx] = newTrack
        
        nativeLib.setStep(trackIdx, stepIdx, newStep.active, newStep.notes.toIntArray(), newStep.velocity, newStep.ratchet, newStep.punch, newStep.probability, newStep.gate, newStep.isSkipped, newStep.subStepOffset)
        
        state.copy(tracks = newTracks)
    } else {
        val steps = track.steps.toMutableList()
        val step = steps[stepIdx]
        val newStep = step.copy(active = !step.active, notes = if (!step.active) listOf(60) else emptyList())
        steps[stepIdx] = newStep
        
        val newTrack = track.copy(steps = steps)
        val newTracks = state.tracks.toMutableList()
        newTracks[trackIdx] = newTrack
        
        nativeLib.setStep(trackIdx, stepIdx, newStep.active && newStep.notes.isNotEmpty(), newStep.notes.toIntArray(), newStep.velocity, newStep.ratchet, newStep.punch, newStep.probability, newStep.gate, newStep.isSkipped, newStep.subStepOffset)
        
        state.copy(tracks = newTracks)
    }
    
    onStateChange(newState)
}
