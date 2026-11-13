package com.groovebox

actual class NativeLib {
    // CAFA Android USB routing is unavailable on desktop; do not report a fake route.
    actual fun setCafaOutputDevice(deviceId: Int): Boolean = false
    actual fun getCafaOutputDevice(): Int = 0
    actual fun setCafaAudioEnabled(enabled: Boolean) = Unit
    actual fun isCafaAudioEnabled(): Boolean = false
    actual fun init() = nInit()
    actual fun start() = nStart()
    actual fun stop() = nStop()
    actual fun setTrackVolume(trackIndex: Int, volume: Float) = nSetTrackVolume(trackIndex, volume)
    actual fun setEngineType(trackIndex: Int, type: Int) = nSetEngineType(trackIndex, type)
    actual fun setTempo(bpm: Float) = nSetTempo(bpm)
    actual fun setPatternLength(trackIndex: Int, length: Int) = nSetPatternLength(trackIndex, length)
    actual fun setPlaying(playing: Boolean) = nSetPlaying(playing)
    actual fun triggerNote(trackIndex: Int, note: Int, velocity: Int) = nTriggerNote(trackIndex, note, velocity)
    actual fun releaseNote(trackIndex: Int, note: Int) = nReleaseNote(trackIndex, note)
    actual fun setStep(trackIndex: Int, stepIndex: Int, active: Boolean, notes: IntArray, velocity: Float, ratchet: Int, punch: Boolean, probability: Float, gate: Float, isSkipped: Boolean, subStepOffset: Float, noteOffsets: FloatArray?, noteVelocities: FloatArray?) = 
        nSetStep(trackIndex, stepIndex, active, notes, velocity, ratchet, punch, probability, gate, isSkipped, subStepOffset, noteOffsets, noteVelocities)
    actual fun setSequencerConfig(trackIndex: Int, numPages: Int, stepsPerPage: Int) = nSetSequencerConfig(trackIndex, numPages, stepsPerPage)
    actual fun setRouting(destTrack: Int, sourceTrack: Int, source: Int, dest: Int, amount: Float, destParamId: Int) = nSetRouting(destTrack, sourceTrack, source, dest, amount, destParamId)
    actual fun setParameter(trackIndex: Int, parameterId: Int, value: Float) = nSetParameter(trackIndex, parameterId, value)
    actual fun setParameterPreview(trackIndex: Int, parameterId: Int, value: Float) = nSetParameterPreview(trackIndex, parameterId, value)
    actual fun setSwing(swing: Float) = nSetSwing(swing)
    actual fun setTrackHumanize(trackIndex: Int, amount: Float) = nSetTrackHumanize(trackIndex, amount)
    actual fun setPlaybackDirection(trackIndex: Int, direction: Int) = nSetPlaybackDirection(trackIndex, direction)
    actual fun setIsRandomOrder(trackIndex: Int, isRandom: Boolean) = nSetIsRandomOrder(trackIndex, isRandom)
    actual fun setIsJumpMode(trackIndex: Int, isJump: Boolean) = nSetIsJumpMode(trackIndex, isJump)
    actual fun setSelectedFmDrumInstrument(trackIndex: Int, drumIndex: Int) = nSetSelectedFmDrumInstrument(trackIndex, drumIndex)
    actual fun jumpToStep(stepIndex: Int) = nJumpToStep(stepIndex)
    actual fun setParameterLock(trackIndex: Int, stepIndex: Int, parameterId: Int, value: Float) = nSetParameterLock(trackIndex, stepIndex, parameterId, value)
    actual fun clearParameterLocks(trackIndex: Int, stepIndex: Int) = nClearParameterLocks(trackIndex, stepIndex)
    actual fun loadFmPreset(trackIndex: Int, presetId: Int) = nLoadFmPreset(trackIndex, presetId)
    actual fun setIsRecording(isRecording: Boolean) = nSetIsRecording(isRecording)
    actual fun setResampling(isResampling: Boolean) = nSetResampling(isResampling)
    actual fun setRecordingSource(source: Int) = nSetRecordingSource(source)
    actual fun loadSample(trackIndex: Int, path: String) = nLoadSample(trackIndex, path)
    actual fun loadWavetable(trackIndex: Int, path: String) = nLoadWavetable(trackIndex, path)
    actual fun loadDefaultWavetable(trackIndex: Int) = nLoadDefaultWavetable(trackIndex)
    actual fun loadSoundFont(trackIndex: Int, path: String) = nLoadSoundFont(trackIndex, path)
    actual fun setSoundFontPreset(trackIndex: Int, presetIndex: Int) = nSetSoundFontPreset(trackIndex, presetIndex)
    actual fun getSoundFontPresetCount(trackIndex: Int): Int = nGetSoundFontPresetCount(trackIndex)
    actual fun getSoundFontPresetName(trackIndex: Int, presetIndex: Int): String = nGetSoundFontPresetName(trackIndex, presetIndex)
    actual fun setSoundFontMapping(trackIndex: Int, knobIndex: Int, paramId: Int) = nSetSoundFontMapping(trackIndex, knobIndex, paramId)
    actual fun saveSample(trackIndex: Int, path: String) = nSaveSample(trackIndex, path)
    actual fun restorePresets() = nRestorePresets()
    actual fun restoreTrackPreset(trackIndex: Int) = nRestoreTrackPreset(trackIndex)
    actual fun saveTrackPreset(trackIndex: Int) = nSaveTrackPreset(trackIndex)
    actual fun saveTrackPresetToPath(trackIndex: Int, path: String) = nSaveTrackPresetToPath(trackIndex, path)
    actual fun trimSample(trackIndex: Int) = nTrimSample(trackIndex)
    actual fun getCurrentStep(trackIndex: Int, drumIndex: Int): Int = nGetCurrentStep(trackIndex, drumIndex)
    actual fun getStepActive(trackIndex: Int, stepIndex: Int, drumIndex: Int): Boolean = nGetStepActive(trackIndex, stepIndex, drumIndex)
    actual fun getStepNotes(trackIndex: Int, stepIndex: Int, drumIndex: Int): IntArray = nGetStepNotes(trackIndex, stepIndex, drumIndex)
    actual fun getStepVelocity(trackIndex: Int, stepIndex: Int, drumIndex: Int): Float = nGetStepVelocity(trackIndex, stepIndex, drumIndex)
    actual fun getStepSubStep(trackIndex: Int, stepIndex: Int, drumIndex: Int): Float = nGetStepSubStep(trackIndex, stepIndex, drumIndex)
    actual fun setArpConfig(trackIndex: Int, mode: Int, octaves: Int, inversion: Int, isLatched: Boolean, isMutated: Boolean, rhythms: Array<BooleanArray>, sequence: IntArray, gateLengths: FloatArray, probability: Float, weird: Float) = 
        nSetArpConfig(trackIndex, mode, octaves, inversion, isLatched, isMutated, rhythms, sequence, gateLengths, probability, weird)
    actual fun copyArpeggiator(sourceTrack: Int, destTrack: Int, live: Boolean) = nCopyArpeggiator(sourceTrack, destTrack, live)
    actual fun setChordProgConfig(trackIndex: Int, enabled: Boolean, mood: Int, complexity: Int) = nSetChordProgConfig(trackIndex, enabled, mood, complexity)
    actual fun setScaleConfig(rootNote: Int, intervals: IntArray) = nSetScaleConfig(rootNote, intervals)
    actual fun getGranularPlayheads(trackIndex: Int): FloatArray = nGetGranularPlayheads(trackIndex)
    actual fun startRecordingSample(trackIndex: Int) = nStartRecordingSample(trackIndex)
    actual fun stopRecordingSample(trackIndex: Int) = nStopRecordingSample(trackIndex)
    actual fun getWaveform(trackIndex: Int): FloatArray? = nGetWaveform(trackIndex)
    actual fun setSlices(trackIndex: Int, starts: IntArray, ends: IntArray) = nSetSlices(trackIndex, starts, ends)
    actual fun resetSampler(trackIndex: Int) = nResetSampler(trackIndex)
    actual fun getSlicePoints(trackIndex: Int): FloatArray = nGetSlicePoints(trackIndex)
    actual fun clearSequencer(trackIndex: Int) = nClearSequencer(trackIndex)
    actual fun setMasterVolume(volume: Float) = nSetMasterVolume(volume)
    actual fun setTrackTranspose(trackIndex: Int, semitones: Int) = nSetTrackTranspose(trackIndex, semitones)
    actual fun panic() = nPanic()
    actual fun getActiveNoteMask(trackIndex: Int): Long = nGetActiveNoteMask(trackIndex)
    actual fun setPitchBend(trackIndex: Int, value: Float) = nSetPitchBend(trackIndex, value)
    actual fun setPadMod(trackIndex: Int, value: Float) = nSetPadMod(trackIndex, value)
    actual fun beginVoicePadMod(trackIndex: Int, note: Int) = nBeginVoicePadMod(trackIndex, note)
    actual fun setVoicePadMod(trackIndex: Int, note: Int, normX: Float, normY: Float) = nSetVoicePadMod(trackIndex, note, normX, normY)
    actual fun endVoicePadMod(trackIndex: Int, note: Int) = nEndVoicePadMod(trackIndex, note)
    actual fun setPadModRouting(trackIndex: Int, xDest: Int, xInt: Float, yDest: Int, yInt: Float, polyMode: Boolean) = nSetPadModRouting(trackIndex, xDest, xInt, yDest, yInt, polyMode)
    actual fun getCpuLoad(): Float = nGetCpuLoad()
    actual fun setGenericLfoParam(lfoIndex: Int, paramId: Int, value: Float) = nSetGenericLfoParam(lfoIndex, paramId, value)
    actual fun setMacroValue(macroIndex: Int, value: Float) = nSetMacroValue(macroIndex, value)
    actual fun setMacroSource(macroIndex: Int, sourceType: Int, sourceIndex: Int, sourceTrackIndex: Int) = nSetMacroSource(macroIndex, sourceType, sourceIndex, sourceTrackIndex)
    actual fun setFxChain(sourceFx: Int, destFx: Int) = nSetFxChain(sourceFx, destFx)
    actual fun setFxEnabled(fxIndex: Int, enabled: Boolean) = nSetFxEnabled(fxIndex, enabled)
    actual fun setPerformanceProfile(profile: Int) = nSetPerformanceProfile(profile)
    actual fun fetchMidiEvents(): IntArray = nFetchMidiEvents()
    actual fun fetchEngineEvents(): IntArray = nFetchEngineEvents()
    private var mAppDataDir: String = ""
    actual fun setAppDataDir(path: String) {
        mAppDataDir = path
        nSetAppDataDir(path)
    }
    actual fun getAppDataDir(): String = mAppDataDir
    actual fun loadAppState() = nLoadAppState()
    actual fun getLastSamplePath(trackIndex: Int): String = nGetLastSamplePath(trackIndex)
    actual fun exportAudio(numRepeats: Int, path: String) = nExportAudio(numRepeats, path)
    actual fun setArpRate(trackIndex: Int, rate: Float, divisionMode: Int) = nSetArpRate(trackIndex, rate, divisionMode)
    actual fun setArpStrum(trackIndex: Int, strum: Float) = nSetArpStrum(trackIndex, strum)
    actual fun setClockMultiplier(trackIndex: Int, multiplier: Float) = nSetClockMultiplier(trackIndex, multiplier)
    actual fun setFilterMode(trackIndex: Int, mode: Int) = nSetFilterMode(trackIndex, mode)
    actual fun getAllTrackParameters(trackIndex: Int): FloatArray = nGetAllTrackParameters(trackIndex)
    actual fun getAllStepActiveStates(trackIndex: Int): BooleanArray = nGetAllStepActiveStates(trackIndex)
    actual fun getRecordedSampleData(trackIndex: Int, targetSampleRate: Float): FloatArray? = nGetRecordedSampleData(trackIndex, targetSampleRate)
    actual fun setInputDevice(deviceId: Int) = nSetInputDevice(deviceId)
    actual fun setRecordingLocked(locked: Boolean) = nSetRecordingLocked(locked)
    actual fun setTrackActive(trackIndex: Int, active: Boolean) = nSetTrackActive(trackIndex, active)
    actual fun setTrackPan(trackIndex: Int, pan: Float) = nSetTrackPan(trackIndex, pan)
    actual fun setTrackMute(trackIndex: Int, muted: Boolean) = nSetTrackMute(trackIndex, muted)
    actual fun setTrackSolo(trackIndex: Int, soloed: Boolean) = nSetTrackSolo(trackIndex, soloed)
    actual fun setTrackDisabled(trackIndex: Int, disabled: Boolean) = nSetTrackDisabled(trackIndex, disabled)
    actual fun getFxSends(trackIndex: Int): FloatArray = nGetFxSends(trackIndex)
    actual fun getFxMix(trackIndex: Int): FloatArray = nGetFxMix(trackIndex)
    actual fun getFxChain(): IntArray = nGetFxChain()
    actual fun setSlicePosition(trackIndex: Int, sliceIndex: Int, position: Float) = nSetSlicePosition(trackIndex, sliceIndex, position)
    actual fun setSidechainConfig(trackIndex: Int, drumIndex: Int) = nSetSidechainConfig(trackIndex, drumIndex)
    actual fun pushSystemAudioSamples(data: FloatArray) = nPushSystemAudioSamples(data)
    actual fun getSampleLength(trackIndex: Int): Long = nGetSampleLength(trackIndex)
    actual fun setChainEnabled(trackIndex: Int, enabled: Boolean) = nSetChainEnabled(trackIndex, enabled)
    actual fun setChainLength(trackIndex: Int, length: Int) = nSetChainLength(trackIndex, length)
    actual fun setChainSlot(trackIndex: Int, slotIndex: Int, laneIndex: Int, steps: Array<StepState>) = nSetChainSlot(trackIndex, slotIndex, laneIndex, steps)
    actual fun getIsPlaying(): Boolean = nGetIsPlaying()
    actual fun getIsRecording(): Boolean = nGetIsRecording()
    actual fun getIsRecordingSample(): Boolean = nGetIsRecordingSample()
    actual fun getTrackLevels(): FloatArray = FloatArray(8)
    actual fun setFocusedTrack(trackIndex: Int) = nSetFocusedTrack(trackIndex)
    actual fun setTrackProbability(trackIndex: Int, probability: Float) = nSetTrackProbability(trackIndex, probability)

    private external fun nInit()
    private external fun nStart()
    private external fun nStop()
    private external fun nSetTrackVolume(trackIndex: Int, volume: Float)
    private external fun nSetEngineType(trackIndex: Int, type: Int)
    private external fun nSetTempo(bpm: Float)
    private external fun nSetFocusedTrack(trackIndex: Int)
    private external fun nSetPatternLength(trackIndex: Int, length: Int)
    private external fun nSetPlaying(playing: Boolean)
    private external fun nTriggerNote(trackIndex: Int, note: Int, velocity: Int)
    private external fun nReleaseNote(trackIndex: Int, note: Int)
    private external fun nSetStep(trackIndex: Int, stepIndex: Int, active: Boolean, notes: IntArray, velocity: Float, ratchet: Int, punch: Boolean, probability: Float, gate: Float, isSkipped: Boolean, subStepOffset: Float, noteOffsets: FloatArray?, noteVelocities: FloatArray?)
    private external fun nSetSequencerConfig(trackIndex: Int, numPages: Int, stepsPerPage: Int)
    private external fun nSetRouting(destTrack: Int, sourceTrack: Int, source: Int, dest: Int, amount: Float, destParamId: Int)
    private external fun nSetParameter(trackIndex: Int, parameterId: Int, value: Float)
    private external fun nSetParameterPreview(trackIndex: Int, parameterId: Int, value: Float)
    private external fun nSetSwing(swing: Float)
    private external fun nSetTrackHumanize(trackIndex: Int, amount: Float)
    private external fun nSetPlaybackDirection(trackIndex: Int, direction: Int)
    private external fun nSetIsRandomOrder(trackIndex: Int, isRandom: Boolean)
    private external fun nSetIsJumpMode(trackIndex: Int, isJump: Boolean)
    private external fun nSetSelectedFmDrumInstrument(trackIndex: Int, drumIndex: Int)
    private external fun nJumpToStep(stepIndex: Int)
    private external fun nSetParameterLock(trackIndex: Int, stepIndex: Int, parameterId: Int, value: Float)
    private external fun nClearParameterLocks(trackIndex: Int, stepIndex: Int)
    private external fun nLoadFmPreset(trackIndex: Int, presetId: Int)
    private external fun nSetIsRecording(isRecording: Boolean)
    private external fun nSetResampling(isResampling: Boolean)
    private external fun nSetRecordingSource(source: Int)
    private external fun nLoadSample(trackIndex: Int, path: String)
    private external fun nLoadWavetable(trackIndex: Int, path: String)
    private external fun nLoadDefaultWavetable(trackIndex: Int)
    private external fun nLoadSoundFont(trackIndex: Int, path: String)
    private external fun nSetSoundFontPreset(trackIndex: Int, presetIndex: Int)
    private external fun nGetSoundFontPresetCount(trackIndex: Int): Int
    private external fun nGetSoundFontPresetName(trackIndex: Int, presetIndex: Int): String
    private external fun nSetSoundFontMapping(trackIndex: Int, knobIndex: Int, paramId: Int)
    private external fun nSaveSample(trackIndex: Int, path: String)
    private external fun nRestorePresets()
    private external fun nRestoreTrackPreset(trackIndex: Int)
    private external fun nSaveTrackPreset(trackIndex: Int)
    private external fun nSaveTrackPresetToPath(trackIndex: Int, path: String)
    private external fun nTrimSample(trackIndex: Int)
    private external fun nGetCurrentStep(trackIndex: Int, drumIndex: Int): Int
    private external fun nGetStepActive(trackIndex: Int, stepIndex: Int, drumIndex: Int): Boolean
    private external fun nGetStepNotes(trackIndex: Int, stepIndex: Int, drumIndex: Int): IntArray
    private external fun nGetStepVelocity(trackIndex: Int, stepIndex: Int, drumIndex: Int): Float
    private external fun nGetStepSubStep(trackIndex: Int, stepIndex: Int, drumIndex: Int): Float
    private external fun nSetArpConfig(trackIndex: Int, mode: Int, octaves: Int, inversion: Int, isLatched: Boolean, isMutated: Boolean, rhythms: Array<BooleanArray>, sequence: IntArray, gateLengths: FloatArray, probability: Float, weird: Float)
    private external fun nCopyArpeggiator(sourceTrack: Int, destTrack: Int, live: Boolean)
    private external fun nSetChordProgConfig(trackIndex: Int, enabled: Boolean, mood: Int, complexity: Int)
    private external fun nSetScaleConfig(rootNote: Int, intervals: IntArray)
    private external fun nGetGranularPlayheads(trackIndex: Int): FloatArray
    private external fun nStartRecordingSample(trackIndex: Int)
    private external fun nStopRecordingSample(trackIndex: Int)
    private external fun nGetWaveform(trackIndex: Int): FloatArray?
    private external fun nSetSlices(trackIndex: Int, starts: IntArray, ends: IntArray)
    private external fun nResetSampler(trackIndex: Int)
    private external fun nGetSlicePoints(trackIndex: Int): FloatArray
    private external fun nClearSequencer(trackIndex: Int)
    private external fun nSetMasterVolume(volume: Float)
    private external fun nSetTrackTranspose(trackIndex: Int, semitones: Int)
    private external fun nPanic()
    private external fun nGetActiveNoteMask(trackIndex: Int): Long
    private external fun nSetPitchBend(trackIndex: Int, value: Float)
    private external fun nSetPadMod(trackIndex: Int, value: Float)
    private external fun nBeginVoicePadMod(trackIndex: Int, note: Int)
    private external fun nSetVoicePadMod(trackIndex: Int, note: Int, normX: Float, normY: Float)
    private external fun nEndVoicePadMod(trackIndex: Int, note: Int)
    private external fun nSetPadModRouting(trackIndex: Int, xDest: Int, xInt: Float, yDest: Int, yInt: Float, polyMode: Boolean)
    private external fun nGetCpuLoad(): Float
    private external fun nSetGenericLfoParam(lfoIndex: Int, paramId: Int, value: Float)
    private external fun nSetMacroValue(macroIndex: Int, value: Float)
    private external fun nSetMacroSource(macroIndex: Int, sourceType: Int, sourceIndex: Int, sourceTrackIndex: Int)
    private external fun nSetFxChain(sourceFx: Int, destFx: Int)
    private external fun nSetFxEnabled(fxIndex: Int, enabled: Boolean)
    private external fun nSetPerformanceProfile(profile: Int)
    private external fun nFetchMidiEvents(): IntArray
    private external fun nFetchEngineEvents(): IntArray
    private external fun nSetAppDataDir(path: String)
    private external fun nLoadAppState()
    private external fun nGetLastSamplePath(trackIndex: Int): String
    private external fun nExportAudio(numRepeats: Int, path: String)
    private external fun nSetArpRate(trackIndex: Int, rate: Float, divisionMode: Int)
    private external fun nSetArpStrum(trackIndex: Int, strum: Float)
    private external fun nSetClockMultiplier(trackIndex: Int, multiplier: Float)
    private external fun nSetFilterMode(trackIndex: Int, mode: Int)
    private external fun nGetAllTrackParameters(trackIndex: Int): FloatArray
    private external fun nGetAllStepActiveStates(trackIndex: Int): BooleanArray
    private external fun nGetRecordedSampleData(trackIndex: Int, targetSampleRate: Float): FloatArray?
    private external fun nSetInputDevice(deviceId: Int)
    private external fun nSetRecordingLocked(locked: Boolean)
    private external fun nSetTrackActive(trackIndex: Int, active: Boolean)
    private external fun nSetTrackDisabled(trackIndex: Int, disabled: Boolean)
    private external fun nSetTrackProbability(trackIndex: Int, probability: Float)
    private external fun nSetTrackPan(trackIndex: Int, pan: Float)
    private external fun nSetTrackMute(trackIndex: Int, muted: Boolean)
    private external fun nSetTrackSolo(trackIndex: Int, soloed: Boolean)
    private external fun nGetFxSends(trackIndex: Int): FloatArray
    private external fun nGetFxMix(trackIndex: Int): FloatArray
    private external fun nGetFxChain(): IntArray
    private external fun nSetSlicePosition(trackIndex: Int, sliceIndex: Int, position: Float)
    private external fun nSetSidechainConfig(trackIndex: Int, drumIndex: Int)
    private external fun nPushSystemAudioSamples(data: FloatArray)
    private external fun nGetSampleLength(trackIndex: Int): Long
    private external fun nSetChainEnabled(trackIndex: Int, enabled: Boolean)
    private external fun nSetChainLength(trackIndex: Int, length: Int)
    private external fun nSetChainSlot(trackIndex: Int, slotIndex: Int, laneIndex: Int, steps: Array<StepState>)
    private external fun nGetIsPlaying(): Boolean
    private external fun nGetIsRecording(): Boolean
    private external fun nGetIsRecordingSample(): Boolean

    companion object {
        init {
            try {
                val libName = "libloom-native-core.dylib"
                val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "loom_native")
                if (!tempDir.exists()) tempDir.mkdirs()
                
                val tempFile = java.io.File(tempDir, libName)
                
                // Always extract to ensure we have the latest version in this session
                val inputStream = NativeLib::class.java.getResourceAsStream("/$libName")
                if (inputStream != null) {
                    val outputStream = java.io.FileOutputStream(tempFile)
                    inputStream.copyTo(outputStream)
                    outputStream.close()
                    inputStream.close()
                    
                    if (tempFile.exists()) {
                        System.load(tempFile.absolutePath)
                        println("Successfully loaded native library from JAR extraction: ${tempFile.absolutePath}")
                    } else {
                        throw Exception("Failed to extract native library")
                    }
                } else {
                    // Fallback to searching on disk if not in resources
                    System.loadLibrary("loom-native-core")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Final fallback for development environments
                try {
                    System.loadLibrary("loom-native-core")
                } catch (e2: Exception) {
                    System.err.println("CRITICAL: Failed to load native library loom-native-core")
                    e2.printStackTrace()
                }
            }
        }
    }
}
