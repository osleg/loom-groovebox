package com.groovebox

enum class EngineType {
    SUBTRACTIVE, FM, SAMPLER, GRANULAR, WAVETABLE, FM_DRUM, ANALOG_DRUM, MIDI, AUDIO_IN, SOUNDFONT
}

enum class ArpMode { OFF, UP, DOWN, UP_DOWN, STAGGER_UP, STAGGER_DOWN, RANDOM, BACH, BROWNIAN, CONVERGE, DIVERGE }

enum class GridMode { GRID_4X4, GRID_6X6, TONNETZ, MAC_KEYS }

/**
 * What the on-screen pads play, independent of the track's engine.
 *
 * AUTO keeps the v1 behaviour (engine chooses), the rest force a mapping so a
 * drum kit or slice bank can be played from any track, and FX turns the pads
 * into per-bus FX bypass toggles.
 */
enum class PadMode { AUTO, SCALES, KEYBOARD, FM_DRUM, ANALOG_DRUM, SLICES, FX }

/** Play-screen arrangement: one grid or two independent 5x4 performance banks. */
enum class PlayLayout { GRID, SPLIT_20_20 }

/** One 20-pad bank in the split performance layout. */
data class SplitZone(
    val trackIndex: Int = 0,
    val octave: Int = 0,
    /** Per-bank Y-axis destination; -1 = use the track's padModTargetId. */
    val modTargetId: Int = -1,
    /** Y-axis intensity, 0..1. */
    val modIntensity: Float = 1f,
    /** X-axis pitch-bend range in semitones at the edge of the pad. */
    val bendRange: Float = 2f,
    /** Per-voice (poly) modulation; false = the v1 global fallback. */
    val polyMod: Boolean = true
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 2L
    }
}

data class ArpConfig(
    val mode: ArpMode = ArpMode.OFF,
    val octaves: Int = 0, // -3 to +3
    val isMutated: Boolean = false,
    val isLatched: Boolean = false,
    val inversion: Int = 0, // -2 to +2
    val rhythms: List<List<Boolean>> = listOf(
        List(16) { true },
        List(16) { false },
        List(16) { false }
    ), // 3 lanes x 16 steps
    val randomSequence: List<Int> = emptyList(), // The 8-step random seed
    val arpRate: Float = 1.0f,
    val arpDivisionMode: Int = 0, // 0=Reg, 1=Dotted, 2=Triplet
    val strum: Float = 0.0f, // 0.0 to 1.0 (Time Spread)
    val isChordProgEnabled: Boolean = false,
    val chordProgMood: Int = 0,
    val chordProgComplexity: Int = 0,
    val probability: Float = 0.0f,
    val weird: Float = 0.0f,
    val gateLengths: List<Float> = List(16) { 0.5f } // 0.5 is detent for Envelope default. Min 0.0=1/16, Max 1.0=4 steps
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** One sound parameter assigned to an FM1 hardware knob. */
data class Fm1KnobBinding(
    val parameterId: Int,
    val label: String,
    val defaultValue: Float = 0f
) : java.io.Serializable {
    fun isValid(): Boolean = parameterId >= 0 && label.isNotBlank() &&
        defaultValue.isFinite() && defaultValue in 0f..1f

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class TrackState(
    val id: Int,
    val volume: Float = 0.25f,
    val pan: Float = 0.5f,
    val humanize: Float = 0.0f,
    val engineType: EngineType = EngineType.SUBTRACTIVE,
    val catalogEntryId: String? = null,
    val isActive: Boolean = true,
    val isMuted: Boolean = false,
    val isSoloed: Boolean = false,
    val isDisabled: Boolean = false,
    val transpose: Int = 0,
    val steps: List<StepState> = List(64) { StepState() },
    val drumSteps: List<List<StepState>> = List(16) { List(64) { StepState() } },
    val patternLength: Int = 16,
    val isAudiblyActive: Boolean = false,
    val numPages: Int = 1,
    val stepsPerPage: Int = 16,
    val selectedFmDrumInstrument: Int = 0,
    val selectedFmPreset: Int? = null,
    /** Null selects the legacy SOUND banks; otherwise four slots, including optional holes. */
    val fm1KnobBindings: List<Fm1KnobBinding?>? = null,
    /** Null selects the declared step-lock defaults; otherwise four independent held-step slots. */
    val fm1StepKnobBindings: List<Fm1KnobBinding?>? = null,
    val arpConfig: ArpConfig = ArpConfig(),
    val mutatedNotes: Map<Int, Int> = emptyMap(),
    val fmCarrierMask: Int = 1,
    val fmActiveMask: Int = 63,
    val useEnvelope: Boolean = true,
    val fxSends: List<Float> = List(18) { 0.0f },
    val fxMix: List<Float> = List(18) { 0.0f },
    val midiInChannel: Int = 17,
    val midiOutChannel: Int = 1,
    val midiInDeviceId: String? = null,  // null = all input devices
    val midiOutDeviceId: String? = null, // null = all output devices
    val lastSamplePath: String = "",
    val activeWavetableName: String = "Basic",
    val filterMode: Int = 0,
    val clockMultiplier: Float = 1.0f,
    val soundFontPath: String? = null,
    val soundFontPresetIndex: Int = 0,
    val soundFontPresetName: String = "None",
    val soundFontMapping: Map<Int, Int> = emptyMap(),
    /**
     * Drum-row wizard: incoming MIDI note -> drum row (instrument index).
     * Empty means the default mapping (note 60 + row). Only used by drum tracks.
     */
    val drumNoteMap: Map<Int, Int> = emptyMap(),
    val parameters: Map<Int, Float> = mapOf(
        0 to 0.7f,    // Volume
        1 to 1.0f,    // Cutoff
        2 to 0.0f,    // Resonance
        100 to 0.01f, // Attack
        101 to 0.5f,  // Decay
        102 to 1.0f,  // Sustain
        103 to 0.2f,  // Release
        104 to 0.2f,  // Osc 1 Wave (Saw)
        105 to 0.4f,  // Osc 2 Wave (Square)
        107 to 0.6f,  // Osc 1 Volume
        108 to 0.4f,  // Osc 2 Volume
        109 to 0.4f,  // Osc 3 (Sub) Volume
        9 to 0.5f,    // Pan
        160 to 0.25f, // Subtractive Osc 1 Pitch / FM Op 1 Level (1.0 / 0.25)
        161 to 0.25f, // Subtractive Osc 2 Pitch / FM Op 2 Level (1.0 / 0.25)
        162 to 0.125f, // Subtractive Osc 3 (Sub) Pitch / FM Op 3 Level (0.5 / 0.125)
        163 to 0.25f, // Subtractive Osc 4 Pitch / FM Op 4 Level (1.0 / 0.25)
        170 to 0.0f,  // Osc 1 Drive (1.0)
        171 to 0.0f,  // Osc 2 Drive (1.0)
        172 to 0.0f,  // Osc 3 Drive (1.0)
        173 to 0.0f,  // Osc 4 Drive (1.0)
        156 to 0.2f,  // FM Algorithm
        151 to 0.5f,  // FM Filter
        157 to 0.5f,  // FM Brightness
        320 to 0.0f,  // Sampler Mode
        341 to 0.5f,  // Sampler Speed
        350 to 1.0f,  // Use Envelope (True)
        400 to 0.5f,  // Granular Position
        406 to 0.2f,  // Granular Grain Size
        407 to 0.5f,  // Granular Density
        429 to 0.5f,  // Granular Gain
        // FM Drum Defaults (Level 0.7, other params 0.5)
        200 to 0.5f, 201 to 0.5f, 202 to 0.5f, 203 to 0.5f, 204 to 0.5f, 205 to 0.7f,
        210 to 0.5f, 211 to 0.5f, 212 to 0.5f, 213 to 0.5f, 214 to 0.5f, 215 to 0.7f,
        220 to 0.5f, 221 to 0.5f, 222 to 0.5f, 223 to 0.5f, 224 to 0.5f, 225 to 0.7f,
        230 to 0.5f, 231 to 0.5f, 232 to 0.5f, 233 to 0.5f, 234 to 0.5f, 235 to 0.7f,
        240 to 0.5f, 241 to 0.5f, 242 to 0.5f, 243 to 0.5f, 244 to 0.5f, 245 to 0.7f,
        250 to 0.5f, 251 to 0.5f, 252 to 0.5f, 253 to 0.5f, 254 to 0.5f, 255 to 0.7f,
        260 to 0.5f, 261 to 0.5f, 262 to 0.5f, 263 to 0.5f, 264 to 0.5f, 265 to 0.7f,
        270 to 0.5f, 271 to 0.5f, 272 to 0.5f, 273 to 0.5f, 274 to 0.5f, 275 to 0.7f,
        // Analog Drum Defaults
        600 to 0.5f, 601 to 0.8f, 602 to 0.25f, 603 to 0.5f, 604 to 0.5f, 605 to 0.8f, // Kick
        610 to 0.2f, 611 to 0.5f, 612 to 0.33f, 613 to 0.4f, 614 to 0.5f, 615 to 0.8f, // Snare
        620 to 0.3f, 621 to 0.5f, 622 to 0.5f, 623 to 0.5f, 624 to 0.5f, 625 to 0.8f, // Clap
        630 to 0.1f, 631 to 0.8f, 632 to 0.5f, 633 to 0.0f, 634 to 0.5f, 635 to 0.8f, // CH
        640 to 0.4f, 641 to 0.8f, 642 to 0.5f, 643 to 0.0f, 644 to 0.5f, 645 to 0.8f, // OH
        650 to 0.6f, 651 to 0.7f, 652 to 0.5f, 653 to 0.0f, 654 to 0.6f, 655 to 0.8f, // Cymbal
        660 to 0.1f, 661 to 0.5f, 662 to 0.8f, 663 to 0.5f, 664 to 0.0f, 665 to 0.8f, // Perc
        670 to 0.3f, 671 to 0.9f, 672 to 0.5f, 673 to 0.2f, 674 to 0.8f, 675 to 0.8f  // Noise
    ),
    val sequenceProbability: Float = 1.0f,
    val isChainEnabled: Boolean = false,
    val songChainNames: List<String?> = List(16) { null },
    val songChainLength: Int = 1,
    val padModTargetId: Int = 1, // Default to Cutoff
    val subTrackNames: Map<Int, String> = emptyMap() // Index -> Custom Name
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}


data class StepState(
    val active: Boolean = false,
    val isSkipped: Boolean = false,
    val notes: List<Int> = emptyList(),
    val velocity: Float = 0.8f,
    val ratchet: Int = 1, // x1 to x9 repeats
    val punch: Boolean = false, // 1.1x volume + overdrive
    val probability: Float = 1.0f,
    val gate: Float = 1.0f, // 0.0 to 1.0 (1/128 to 1 full step)
    val subStepOffset: Float = 0.0f, // 0.0 to 1.0 (Microtiming - applied to ALL notes if noteOffsets is empty)
    val noteOffsets: List<Float> = emptyList(), // Per-note microtiming (matches notes array)
    val noteVelocities: List<Float> = emptyList(), // Per-note velocity (matches notes array)
    val noteGates: List<Float> = emptyList(), // Per-note length in steps; empty uses the shared gate
    val parameterLocks: Map<Int, Float> = emptyMap() // parameterId -> value (max 4)
) : java.io.Serializable {
    // Java serialization leaves fields added after an old save as null; constructor defaults do not run.
    private fun readResolve(): Any = copy(noteGates = (noteGates as List<Float>?) ?: emptyList())

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class LfoState(
    val rate: Float = 0.5f, // 0..1 (UI value)
    val depth: Float = 0.5f,
    val shape: Int = 0, 
    val sync: Boolean = false,
    val intensity: Float = 0.5f, // Routing Amount
    val targetType: Int = 0, // 0=None, 1=TrackParam, 2=GlobalFX, 3=Macro
    val targetId: Int = -1,
    val targetLabel: String = "None"
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class MacroTarget(
    val targetId: Int = -1,
    val targetLabel: String = "None",
    val isInverted: Boolean = false,
    val enabled: Boolean = false
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}


data class MacroState(
    val label: String = "Macro", 
    var sourceLabel: String = "None",
    var sourceType: Int = 0, // 0=None, 1=Strip, 2=Knob, 3=LFO, 4=Envelope
    var sourceIndex: Int = -1, 
    var sourceTrackIndex: Int = -1,
    val targets: List<MacroTarget> = List(3) { MacroTarget() },
    val value: Float = 0.0f
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class RoutingConnection(
    val id: String = java.util.UUID.randomUUID().toString(),
    val source: Int, // ModSource Enum Int
    val destTrack: Int,
    val destParam: Int, // ModDestination Enum Int
    val amount: Float = 0.0f
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class StripRouting(
    val stripIndex: Int,
    val parameterName: String = "None",
    val targetType: Int = 0, // 0=None, 1=Track Param, 2=Global FX
    val targetId: Int = 0,
    val min: Float = 0f,
    val max: Float = 1f
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class GrooveboxState(
    val tracks: List<TrackState> = List(8) { i -> 
        val defaultEngine = when(i) {
            0 -> EngineType.SUBTRACTIVE
            1 -> EngineType.FM
            2 -> EngineType.WAVETABLE
            3 -> EngineType.SAMPLER
            4 -> EngineType.GRANULAR
            5 -> EngineType.FM_DRUM
            6 -> EngineType.ANALOG_DRUM
            else -> EngineType.SUBTRACTIVE
        }
        TrackState(id = i, engineType = defaultEngine)
    },
    val tempo: Float = 120.0f,
    val isPlaying: Boolean = false,
    val currentStep: Int = 0,
    val selectedTab: Int = 0,
    val masterVolume: Float = 0.8f,
    val globalTranspose: Int = 0,
    val globalParameters: Map<Int, Float> = emptyMap(), 
    val sidechainSourceTrack: Int = -1,
    val sidechainSourceDrumIdx: Int = 0,
    val isSelectingSidechain: Boolean = false,
    
    // Advanced Features
    val scaleType: ScaleType = ScaleType.MAJOR,
    val rootNote: Int = 48, // C3
    val padPageCount: Int = 1,
    val currentPadPage: Int = 0,
    val padColor: Long = 0xFFBB86FC,
    val stripRoutings: List<StripRouting> = List(4) { i -> StripRouting(stripIndex = i) },
    val stripValues: List<Float> = List(4) { 0.5f },
    val knobRoutings: List<StripRouting> = List(4) { i -> StripRouting(stripIndex = i + 4, parameterName = "Knob ${i+1}") }, 
    val knobValues: List<Float> = List(4) { 0.5f },
    val heldNotes: Set<Int> = emptySet(),
    val selectedTrackIndex: Int = 0,
    val midiLearnActive: Boolean = false,
    val midiLearnStep: Int = 0, // 0: OFF, 1: SELECT_STRIP, 2: SELECT_PARAM
    val midiLearnSelectedStrip: Int? = null,
    val focusedParameter: Int? = null, // parameterId
    val currentSequencerBank: Int = 0, // 0=A, 1=B, 2=C, 3=D, 4=64-Step Grid
    val is64StepView: Boolean = false,
    val echoModeActive: Boolean = false, // Debugging: Echo received MIDI back to device

    // Transport & Sequencing Logic
    val swing: Float = 0f, // -0.23f to +0.23f
    // humanize moved to TrackState
    val playbackDirection: Int = 0, // 0=Forward, 1=Reverse, 2=Ping-Pong
    val isRandomOrder: Boolean = false,
    val isJumpMode: Boolean = false,
    val isJumpHold: Boolean = false,
    val jumpModeWaitingForTap: Boolean = false,
    val maxChainLength: Int = 16,
    val padXAttenuation: Float? = null,
    val padYAttenuation: Float? = null,
    val invertPadY: Boolean = false,
    val patternLength: Int = 16,

    // Parameter Locking & Recording State
    val isRecording: Boolean = false,
    val isParameterLocking: Boolean = false,
    val isResampling: Boolean = false, 
    val recordingSource: Int = 0, // 0=Mic, 1=Resample, 2=System
    val isRecordingSample: Boolean = false,
    val isRecordingLocked: Boolean = false,
    val recordingTrackIndex: Int = -1,
    val lockedParamsThisStep: Set<Int> = emptySet(), 
    
    // Persistent Strip Assignments
    val engineTypeStripAssignments: Map<EngineType, List<StripRouting>> = emptyMap(),
    val engineTypeKnobAssignments: Map<EngineType, List<StripRouting>> = emptyMap(),
    
    // Routing Screen State
    val lfos: List<LfoState> = List(6) { LfoState() },
    val macros: List<MacroState> = List(8) { MacroState() },
    val routingConnections: List<RoutingConnection> = emptyList(),
    val fxChain: Map<Int, Int> = emptyMap(), // Legacy map, keeping for safety but moving to slots
    val fxChainSlots: List<Int> = List(5) { -1 }, // 5 Serial Slots. -1 = Empty
    val fxEnabled: List<Boolean> = List(18) { true }, // Per-FX bus bypass (performance)
    val focusedValue: String? = null,
    val lockingTarget: Pair<Int, Int>? = null,
    
    // LFO Learn
    val lfoLearnActive: Boolean = false,
    val lfoLearnLfoIndex: Int = -1,
    
    // Macro Learn (Target)
    val macroLearnActive: Boolean = false,
    val macroLearnMacroIndex: Int = -1,
    val macroLearnTargetIndex: Int = -1,

    // Macro Learn (Source/Env)
    val macroSourceLearnActive: Boolean = false,
    val macroSourceLearnIndex: Int = -1,
    
    // Pad Modulation Learn
    val isPadModLearnActive: Boolean = false,
    /** Which split bank learns the next mod destination; -1 = selected track. */
    val padModLearnBank: Int = -1,
    /** Single-grid pads use per-voice modulation when true. */
    val polyPadMod: Boolean = true,

    // MIDI UI Feedback
    val lastMidiNote: Int = -1,
    val lastMidiVelocity: Int = 0,
    
    // Copy/Paste State
    val copiedSteps: List<StepState>? = null,
    val copiedDrumSteps: List<List<StepState>>? = null,
    
    // UI State
    val gridMode: GridMode = GridMode.GRID_4X4,
    val playLayout: PlayLayout = PlayLayout.GRID,
    /** Phase 6: explicit pad mapping (AUTO = engine decides). */
    val padMode: PadMode = PadMode.AUTO,
    /** Drum-row wizard: row currently waiting for a MIDI note, -1 = off. */
    val drumMapLearnRow: Int = -1,
    /** Drum-row wizard: track the armed row belongs to, -1 = off. */
    val drumMapLearnTrack: Int = -1,
    val splitLeft: SplitZone = SplitZone(trackIndex = 0, octave = 0),
    val splitRight: SplitZone = SplitZone(trackIndex = 1, octave = 1),
    val uiLayoutMode: Int = 0, // 0=Auto, 1=Phone, 2=Tablet
    val showCpuMonitor: Boolean = true, // Toggles performance overlay
    val performanceProfile: Int = 0, // 0 = High, 1 = Balanced, 2 = Low
    val isPerformanceMode: Boolean = false, // Toggles visual touch cues
    val isKeyboardModeEnabled: Boolean = false, // Toggle for Android physical keyboard
    val cpuLoad: Float = 0f,
    val trackLevels: List<Float> = List(8) { 0f },
    val importedFmPresets: List<Map<String, Any>> = emptyList(), // Imported DX7 voice data
    val appVersion: String = com.groovebox.utils.Version.APP_VERSION
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
