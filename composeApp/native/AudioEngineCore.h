#ifndef AUDIO_ENGINE_CORE_H
#define AUDIO_ENGINE_CORE_H

#include <bitset>
#include <memory>
#include <mutex>
#include <chrono>
#include <vector>
#include <string>
#include <atomic>
#include "UISnapshot.h"

#include "Arpeggiator.h"
#include "EnvelopeFollower.h"
#include "RoutingMatrix.h"
#include "Sequencer.h"
#include "engines/AnalogDrumEngine.h"
#include "engines/AudioInEngine.h"
#include "engines/AutoPannerFx.h"
#include "engines/BitcrusherFx.h"
#include "engines/ChorusFx.h"
#include "engines/CompressorFx.h"
#include "engines/DelayFx.h"
#include "engines/Eq5BandFx.h"
#include "engines/FilterLfoFx.h"
#include "engines/FlangerFx.h"
#include "engines/FmDrumEngine.h"
#include "engines/FmEngine.h"
#include "engines/GalacticReverb.h"
#include "engines/GranularEngine.h"
#include "engines/LfoEngine.h"
#include "engines/OctaverFx.h"
#include "engines/OverdriveFx.h"
#include "engines/PhaserFx.h"
#include "engines/SamplerEngine.h"
#include "engines/SimpleFilterFx.h"
#include "engines/SlicerFx.h"
#include "engines/SoundFontEngine.h"
#include "engines/SubtractiveEngine.h"
#include "engines/TapeEchoFx.h"
#include "engines/TapeWobbleFx.h"
#include "engines/WavetableEngine.h"

class AudioEngineCore {
public:
  enum RecordingSource { MIC = 0, RESAMPLE = 1, SYSTEM = 2 };

  AudioEngineCore();
  virtual ~AudioEngineCore();

  // Core Render Method (Replaces onAudioReady)
  void render(float *audioData, int32_t numFrames);

  void setAppDataDir(const std::string &dir);
  void saveAppState();
  void loadAppState();
  std::string getLastSamplePath(int trackIndex);

  // Control methods
  void setTrackVolume(int trackIndex, float volume);
  void setEngineType(int trackIndex, int type);
  void triggerNote(int trackIndex, int note, int velocity);
  void releaseNote(int trackIndex, int note);
  void setTempo(float bpm);
  void setPlaying(bool playing);
  bool getIsPlaying() const { return mIsPlaying; }
  bool getIsRecording() const { return mIsRecording; }
  bool getIsRecordingSample() const { return mIsRecordingSample; }
  void setStep(int trackIndex, int stepIndex, bool active,
               const std::vector<int> &notes, float velocity = 0.8f,
               int ratchet = 1, bool punch = false, float probability = 1.0f,
               float gate = 1.0f, bool isSkipped = false,
               float subStepOffset = 0.0f,
               const std::vector<float> &noteOffsets = {},
               const std::vector<float> &noteVelocities = {},
               const std::vector<float> &noteGates = {},
               const std::map<int, float> *parameterLocks = nullptr);
  void setSequencerConfig(int trackIndex, int numPages, int stepsPerPage);
  // New helper for modulation without affecting UI state
  void updateEngineParameter(int trackIndex, int parameterId, float value,
                             bool immediate = false);

  void setFilterType(int trackIndex, int filterType);
  void setFilterCutoff(int trackIndex, float cutoff);
  void setFilterResonance(int trackIndex, float resonance);

  // setRouting takes specific destParamId now
  void setRouting(int destTrack, int sourceTrack, int source, int dest,
                  float amount, int destParamId = -1);
  void getFxChain(int *destination);
  void setParameter(int trackIndex, int parameterId, float value,
                    bool immediate = false);
  void setParameterPreview(int trackIndex, int parameterId, float value);
  void setSwing(float swing);
  void setTrackHumanize(int trackIndex, float amount);
  void setPatternLength(int trackIndex, int length);
  void setPlaybackDirection(int trackIndex, int direction);
  void setIsRandomOrder(int trackIndex, bool isRandom);
  void setIsJumpMode(int trackIndex, bool isJump);
  void setSelectedFmDrumInstrument(int trackIndex, int drumIndex);
  void jumpToStep(int stepIndex);
  void setParameterLock(int trackIndex, int stepIndex, int parameterId,
                        float value);
  void setOpLevel(int trackIndex, int op, float l);
  float getOpLevel(int trackIndex, int op) const;
  void clearParameterLocks(int trackIndex, int stepIndex);
  void setIsRecording(bool isRecording);
  void setResampling(bool isResampling); // Deprecated: Use setRecordingSource
  void setRecordingSource(int source);   // New: MIC, RESAMPLE, or SYSTEM
  int getCurrentStep(int trackIndex, int drumIndex = -1);
  void setArpConfig(int trackIndex, int mode, int octaves, int inversion,
                    bool isLatched, bool isMutated,
                    const std::vector<std::vector<bool>> &rhythms,
                    const std::vector<int> &sequence,
                    const std::vector<float> &gateLengths,
                    float probability, float weird);
  // Copy arp settings from srcTrack to destTrack.
  // live == true clones the RNG seed and held notes (Clone live);
  // live == false copies settings with a fresh seed (Copy settings).
  void copyArpeggiator(int srcTrack, int destTrack, bool live);
  void setChordProgConfig(int trackIndex, bool enabled, int mood,
                          int complexity);
  void setScaleConfig(int rootNote, const std::vector<int> &intervals);
  void getGranularPlayheads(int trackIndex, GranularEngine::PlayheadInfo *out,
                            int maxCount);
  void startRecordingSample(int trackIndex);
  void stopRecordingSample(int trackIndex);
  void setRecordingLocked(bool locked);
  std::vector<float> getSamplerWaveform(int trackIndex, int numPoints);
  void normalizeSample(int trackIndex);
  void resetSampler(int trackIndex);
  void loadSample(int trackIndex, const std::string &path);
  void loadWavetable(int trackIndex, const std::string &path);
  void loadDefaultWavetable(int trackIndex);
  void saveSample(int trackIndex, const std::string &path); // New
  void trimSample(int trackIndex);
  void loadSoundFont(int trackIndex, const std::string &path);
  void setSoundFontPreset(int trackIndex, int presetIndex);
  void setSoundFontMapping(int trackIndex, int knobIndex, int paramId);
  int getSoundFontPresetCount(int trackIndex);
  std::string getSoundFontPresetName(int trackIndex, int presetIndex);
  void clearSequencer(int trackIndex);
  void setMasterVolume(float volume);
  bool getStepActive(int trackIndex, int stepIndex, int drumIndex = -1);
  bool getStepActiveStates(int trackIndex, bool *out, int maxSize);
  std::vector<Step> getSequencerSteps(int trackIndex);
  std::vector<float> getAllTrackParameters(int trackIndex); // New sync method
  void getFxSends(int trackIndex, float *dest);
  void getFxMix(int trackIndex, float *dest);
  std::vector<float> getSamplerSlicePoints(int trackIndex);
  std::vector<float> getRecordedSampleData(int trackIndex,
                                           float targetSampleRate);
  size_t getSampleLength(int trackIndex);
  std::vector<int> getStepNotes(int trackIndex, int stepIndex, int drumIndex = -1);
  float getStepVelocity(int trackIndex, int stepIndex, int drumIndex = -1);
  float getStepSubStep(int trackIndex, int stepIndex, int drumIndex = -1);
  // Drain only sequencer cells changed by live recording. Each record is
  // track, lane (-1 for melodic), step, active, shared gate, note count,
  // followed by note, velocity, offset, and per-note gate for each note.
  std::vector<float> drainRecordedSteps();
  uint64_t getActiveNoteMask(int trackIndex);
  void setPitchBend(int trackIndex, float semitones);
  void setPadMod(int trackIndex, float value);
  // Per-voice performance modulation (Phase 4).  begin/end bracket a finger
  // gesture so two pointers on the same note cannot cancel each other.
  void beginVoicePadMod(int trackIndex, int note);
  void setVoicePadMod(int trackIndex, int note, float normX, float normY);
  void endVoicePadMod(int trackIndex, int note);
  void setPadModRouting(int trackIndex, int xDest, float xInt, int yDest,
                        float yInt, bool polyMode);
  void panic();
  void loadFmPreset(int trackIndex, int presetId);
  void setClockMultiplier(int trackIndex, float multiplier);
  void setFilterMode(int trackIndex, int mode);
  void setArpTriplet(int trackIndex, bool isTriplet);
  void setArpRate(int trackIndex, float rate, int divisionMode);
  void updateSampleRate(float sampleRate);
  void setArpStrum(int trackIndex, float strum);
  float getCpuLoad();
  // Returns 8 envelope follower levels (0..1) for track activity highlighting
  void getTrackLevels(float *outLevels) const;
  void setInputDevice(int deviceId);
  void setSidechainConfig(int trackIndex, int drumIndex);
  void setSlicePosition(int trackIndex, int sliceIndex, float position);
  void setTrackActive(int trackIndex, bool active);
  void setTrackDisabled(int trackIndex, bool disabled);
  void setTrackPan(int trackIndex, float pan);
  void setTrackMute(int trackIndex, bool muted);
  void setTrackSolo(int trackIndex, bool soloed);
  void setSlices(int trackIndex, const std::vector<int> &starts,
                 const std::vector<int> &ends);

  void updateUISnapshot();
  void setFocusedTrack(int trackIndex);

  void setChainEnabled(int trackIndex, bool enabled);
  void setChainLength(int trackIndex, int length);
  void setChainSlot(int trackIndex, int slotIndex, int laneIndex,
                    const std::vector<Step> &steps);
  void setTrackProbability(int trackIndex, float probability);

  // Audio Export
  void renderToWav(int numCycles, const std::string &path);
  void renderStereo(float *outBuffer, int numFrames);
  void pushSystemAudioSamples(const float *data,
                              int numSamples); // New: for System Audio
  void pushInputSamples(const float *data, int numFrames, int numChannels);
  bool isInputNeeded();

  // Track Management
  void initTrack(int i);
  void restoreTrackPreset(int trackIndex);
  void saveTrackPreset(int trackIndex);
  void saveTrackPresetToPath(int trackIndex, std::string path);

  // Routing / Macro Controls
  void setGenericLfoParam(int lfoIndex, int paramId, float value);
  void setMacroValue(int macroIndex, float value);
  void setFxChain(int sourceFx, int destFx);
  // Enable/disable an FX bus (0-17). Disabled buses are not processed, do not
  // receive track sends and do not duck the dry signal.
  void setFxEnabled(int fxIndex, bool enabled);
  bool isFxEnabled(int fxIndex) const;
  // Performance profile: 0 = High, 1 = Balanced, 2 = Low.
  // Controls per-track voice cap, modulation control rate and idle FX gating.
  void setPerformanceProfile(int profile);

  // Global Transpose
  void setTrackTranspose(int trackIndex, int semitones);

  // MIDI Mode
  struct MidiMessage {
    int type;      // 0x90 (NoteOn), 0x80 (NoteOff)
    int channel;   // MIDI channel (0-15)
    int data1;     // Note
    int data2;     // Velocity
    int trackIndex = -1; // Source track for per-track output routing
  };

  void restorePresets();

  // Returns flat array: [type, ch, d1, d2, type, ch, d1, d2...]
  int fetchMidiEvents(int *outBuffer, int maxEvents);

private:
  void enqueueMidiEvent(int type, int channel, int data1, int data2,
                        int trackIndex = -1);
  std::vector<MidiMessage> mMidiQueue;
  std::mutex mMidiLock;

  // Command Queue for Race-Free UI->Audio Communication
  struct AudioCommand {
    enum Type {
      NOTE_ON,
      NOTE_OFF,
      PARAM_SET,
      GLOBAL_PARAM_SET,
      SET_ENGINE_TYPE,
      SET_TRACK_VOLUME,
      SET_TRACK_PAN,
      SET_TRACK_ACTIVE,
      SET_TEMPO,
      SET_PATTERN_LENGTH,
      SET_STEP,
      SET_TRACK_MUTE,
      SET_TRACK_SOLO,
      SET_ARP_RATE,
      SET_ARP_STRUM,
      SET_SWING,
      SET_SLICES,
      SET_TRACK_HUMANIZE,
      SET_TRACK_TRANSPOSE,
      SET_PITCH_BEND,
      SET_CHAIN_ENABLED,
      SET_CHAIN_LENGTH,
      SET_CHAIN_SLOT,
      SET_PAD_MOD,
      SET_VOICE_MOD_BEGIN,
      SET_VOICE_MOD_UPDATE,
      SET_VOICE_MOD_END,
      SET_PAD_MOD_ROUTING,
      PARAM_PREVIEW,
      SET_TRACK_PROBABILITY,
      SET_TRACK_DISABLED
    };
    Type type;
    int trackIndex;
    int data1;                         // note, or paramId
    int data2;                         // Second data field (e.g. stepIndex)
    int trackIdx;                      // Specific track for global commands if needed
    float value;                       // velocity, or paramValue
    bool bValue;                       // Boolean value
    std::vector<int> notes;            // For SET_STEP
    float velocity;                    // For SET_STEP
    int ratchet;                       // For SET_STEP
    bool punch;                        // For SET_STEP
    float probability;                 // For SET_STEP
    float gate;                        // For SET_STEP
    bool isSkipped;                    // For SET_STEP
    std::vector<int> sliceStarts;      // For SET_SLICES
    std::vector<int> sliceEnds;        // For SET_SLICES
    int extraData;                     // extra
    bool immediate;                    // For P-Locks (Snap)
    float subStepOffset;               // For SET_STEP (Microtiming)
    std::vector<float> noteOffsets;    // For SET_STEP (Per-Note Microtiming)
    std::vector<float> noteVelocities; // For SET_STEP (Per-Note Velocity)
    std::vector<float> noteGates;      // For SET_STEP (Per-Note Gate)
    std::map<int, float> parameterLocks; // For SET_STEP (Exact locks)
    bool hasParameterLocks = false;
    std::vector<Step> steps;           // For SET_CHAIN_SLOT
    int laneIndex;                     // For SET_CHAIN_SLOT (-1 for melodic)
  };
  std::vector<AudioCommand> mCommandQueue;
  std::mutex mCommandLock;

  void processCommands();
  // Maps a pad/UI note to the note the engine actually sounds (per-track
  // transpose and slice-mode rules), so per-voice modulation addresses the
  // right voice.
  int resolveVoiceNote(int trackIndex, int note) const;

  struct EngineEvent {
    enum Type { PATTERN_SAVE = 1 };
    Type type;
    int trackIndex;
    int data;
  };
  std::vector<EngineEvent> mEventQueue;
  std::mutex mEventLock;
  void enqueuePatternSaveEvent(int trackIndex, int slotIndex);

public:
  int fetchEngineEvents(int *outBuffer, int maxEvents);
  void updateGlobalParameter(int parameterId, float value);

  std::atomic<bool> mFirstRun{true};
  std::atomic<float> mCpuLoad{0.0f};
  std::atomic<int> mFocusedTrackIndex{0};

public:
  struct Track {
    std::atomic<float> volume{0.8f};
    float smoothedVolume = 0.8f;
    std::atomic<float> pan{0.5f};
    int transpose = 0;
    std::atomic<float> mPitchBend{0.0f};  // -1.0 to 1.0 (semitones)
    std::atomic<float> padModValue{0.0f}; // 0.0 to 1.0 (Y-axis)
    // Phase 4 per-voice modulation routing (audio thread only).
    bool padModPoly = false;
    int padModXDest = -1;
    int padModYDest = -1;
    float padModXInt = 2.0f;
    float padModYInt = 1.0f;
    int voiceModRef[128] = {0};
    std::atomic<float> sequenceProbability{1.0f}; // Track-level probability

    Track() = default;
    Track(Track&& other) noexcept :
      volume(other.volume.load()),
      smoothedVolume(other.smoothedVolume),
      pan(other.pan.load()),
      transpose(other.transpose),
      mPitchBend(other.mPitchBend.load()),
      padModValue(other.padModValue.load()),
      padModPoly(other.padModPoly),
      padModXDest(other.padModXDest),
      padModYDest(other.padModYDest),
      padModXInt(other.padModXInt),
      padModYInt(other.padModYInt),
      smoothedPan(other.smoothedPan),
      humanize(other.humanize),
      isMuted(other.isMuted),
      isSoloed(other.isSoloed),
      engineType(other.engineType),
      selectedFmDrumInstrument(other.selectedFmDrumInstrument),
      subtractiveEngine(std::move(other.subtractiveEngine)),
      fmEngine(std::move(other.fmEngine)),
      fmDrumEngine(std::move(other.fmDrumEngine)),
      samplerEngine(std::move(other.samplerEngine)),
      granularEngine(std::move(other.granularEngine)),
      wavetableEngine(std::move(other.wavetableEngine)),
      analogDrumEngine(std::move(other.analogDrumEngine)),
      audioInEngine(std::move(other.audioInEngine)),
      soundFontEngine(std::move(other.soundFontEngine)),
      mModulatedCount(other.mModulatedCount),
      mRecordingNotes(std::move(other.mRecordingNotes)),
      sequencer(std::move(other.sequencer)),
      arpeggiator(std::move(other.arpeggiator)),
      follower(std::move(other.follower)),
      isActive(other.isActive),
      currentFrequency(other.currentFrequency),
      lastOutput(other.lastOutput),
      gainReduction(other.gainReduction),
      patternLength(other.patternLength),
      mPhysicallyHeldNoteCount(other.mPhysicallyHeldNoteCount),
      midiInChannel(other.midiInChannel),
      midiOutChannel(other.midiOutChannel),
      mPendingNotes(std::move(other.mPendingNotes)),
      mPendingParams(std::move(other.mPendingParams)),
      mClockMultiplier(other.mClockMultiplier),
      mArpRate(other.mArpRate),
      mArpDivisionMode(other.mArpDivisionMode),
      mActivePLockCount(other.mActivePLockCount),
      mArpTriplet(other.mArpTriplet),
      mParametersDirty(other.mParametersDirty),
      mStepCountdown(other.mStepCountdown),
      mArpCountdown(other.mArpCountdown),
      mInternalStepIndex(other.mInternalStepIndex),
      panL(other.panL),
      panR(other.panR),
      mPunchCounter(other.mPunchCounter),
      mLastTriggeredNote(other.mLastTriggeredNote),
      mNoteDurationRemaining(other.mNoteDurationRemaining),
      lastSamplePath(std::move(other.lastSamplePath)),
      chainSlots(std::move(other.chainSlots)),
      currentChainSlot(other.currentChainSlot),
      chainLength(other.chainLength),
      isChainEnabled(other.isChainEnabled),
      mSilenceFrames(other.mSilenceFrames)
    {
        std::memcpy(parameters, other.parameters, sizeof(parameters));
        std::memcpy(appliedParameters, other.appliedParameters, sizeof(appliedParameters));
        std::memcpy(mModulatedIds, other.mModulatedIds, sizeof(mModulatedIds));
        for(int i=0; i<16; ++i) drumSequencers[i] = std::move(other.drumSequencers[i]);
        for(int i=0; i<18; ++i) fxSends[i].store(other.fxSends[i].load());
        std::memcpy(smoothedFxSends, other.smoothedFxSends, sizeof(smoothedFxSends));
        for(int i=0; i<18; ++i) fxMix[i].store(other.fxMix[i].load());
        std::memcpy(mActivePLocks, other.mActivePLocks, sizeof(mActivePLocks));
        std::memcpy(mActiveNotes, other.mActiveNotes, sizeof(mActiveNotes));
        std::memcpy(mDrumNoteDurationRemaining, other.mDrumNoteDurationRemaining, sizeof(mDrumNoteDurationRemaining));
        std::memcpy(mDrumLastTriggeredNote, other.mDrumLastTriggeredNote, sizeof(mDrumLastTriggeredNote));
        std::memcpy(voiceModRef, other.voiceModRef, sizeof(voiceModRef));
    }
    Track& operator=(Track&&) = delete;


    float smoothedPan = 0.5f;
    float humanize = 0.0f;
    bool isMuted = false;
    bool isSoloed = false;
    int engineType = 0; // 0=Subtractive, 1=FM, 2=Sampler, etc.
    int selectedFmDrumInstrument = 0;
    SubtractiveEngine subtractiveEngine;
    FmEngine fmEngine;
    FmDrumEngine fmDrumEngine;
    SamplerEngine samplerEngine;
    GranularEngine granularEngine;
    WavetableEngine wavetableEngine;
    AnalogDrumEngine analogDrumEngine;
    AudioInEngine audioInEngine;
    SoundFontEngine soundFontEngine;

    float parameters[2500] = {0.0f};
    float appliedParameters[2500] = {0.0f}; // Values after P-locks and Mods
    // v3.0: Compact modulation tracking (replaces bitset<2500>)
    int mModulatedIds[32];     // Parameter IDs modulated last block
    int mModulatedCount = 0;   // Count of modulated params

    struct RecordingNote {
      int note;
      int stepIndex;
      int drumIdx; // -1 if synth
      uint64_t startGlobalStep;
      double startOffset; // within step
    };
    std::vector<RecordingNote> mRecordingNotes;

    Sequencer sequencer;
    Sequencer drumSequencers[16];
    Arpeggiator arpeggiator;
    EnvelopeFollower follower;
    std::atomic<float> fxSends[18];
    float smoothedFxSends[18] = {0.0f};
    std::atomic<float> fxMix[18];

    bool isActive = false;
    bool isDisabled = false;
    float currentFrequency = 440.0f;
    float lastOutput = 0.0f;
    float gainReduction = 1.0f;
    int patternLength = 16;
    int mPhysicallyHeldNoteCount = 0;
    int midiInChannel = 17; // 1-16, 17=ALL, 0=NONE
    int midiOutChannel = 1; // 1-16
    struct PendingNote {
      int note;
      float velocity;
      double samplesRemaining;
      float gate = 0.5f;
      int ratchetCount = 1;
      bool punch = false;
    };
    std::vector<PendingNote> mPendingNotes;

    struct PendingParam {
      int id;
      float value;
      double samplesRemaining;
    };
    std::vector<PendingParam> mPendingParams;
    float mClockMultiplier = 1.0f;
    float mArpRate = 1.0f;    // 1.0 = 1/16th, 0.5 = 1/8th, etc.
    int mArpDivisionMode = 0; // 0=Reg, 1=Dotted, 2=Triplet
    int mActivePLocks[32];    // Track up to 32 P-locks per step for fast reset
    int mActivePLockCount = 0;
    bool mArpTriplet = false;
    bool mParametersDirty = true; // Flag for optimization

    double mStepCountdown = 0.0;
    double mArpCountdown = 0.0;
    int mInternalStepIndex = 0;

    struct ActiveNote {
      int note = -1;
      double durationRemaining = 0.0;
      bool active = false;
    };
    static const int MAX_POLYPHONY = 16;
    ActiveNote mActiveNotes[MAX_POLYPHONY];
    float panL = 0.707f;
    float panR = 0.707f;

    int mPunchCounter = 0; // Frames remaining for punch compression

    int mLastTriggeredNote = -1;
    double mNoteDurationRemaining = 0.0;
    double mDrumNoteDurationRemaining[16] = {0.0};
    int mDrumLastTriggeredNote[16] = {-1, -1, -1, -1, -1, -1, -1, -1,
                                      -1, -1, -1, -1, -1, -1, -1, -1};
    std::string lastSamplePath = "";
    struct ChainSlot {
      std::vector<Step> steps; // For melodic
      std::vector<std::vector<Step>> drumLanes =
          std::vector<std::vector<Step>>(16);
      bool hasSequence = false;
    };

    std::vector<ChainSlot> chainSlots = std::vector<ChainSlot>(100);
    int currentChainSlot = 0;
    int chainLength = 1;
    bool isChainEnabled = false;

    int mSilenceFrames = 0;
  };

  std::vector<Track> mTracks;
  RoutingMatrix mRoutingMatrix;
  std::atomic<bool> mIsPlaying{false};
  std::atomic<bool> mIsRecording{false};       // Transport record (sequencer)
  std::atomic<bool> mIsRecordingSample{false}; // Sample capture
  std::chrono::steady_clock::time_point mLastRecordStartTime;
  std::atomic<int> mRecordingSource{0};        // 0=Mic, 1=Resample, 2=System
  std::atomic<bool> mIsRecordingLocked{false};
  std::atomic<bool> mSampleRateChanged{false};
  std::atomic<float> mPendingSampleRate{48000.0f};
  int mRecordingTrackIndex = -1;
  std::atomic<float> mBpm{120.0f};
  std::atomic<double> mSampleCount{0.0};
  std::atomic<double> mSamplesPerStep{0.0};
  std::atomic<int> mGlobalStepIndex{0};
  std::atomic<int> mTrackCurrentStep[8];
  std::atomic<int> mTrackCurrentDrumStep[8][16];
  std::atomic<int> mPatternLength{16};
  std::atomic<float> mTempo{120.0f};
  std::atomic<float> mSwing{0.0f};
  long mStartupFrames = 10000; // Wait ~200ms at 48k
  std::atomic<double> mSampleRate{48000.0};
  std::atomic<int> mSnapshotCounter{0};
  std::recursive_mutex mLock;
  std::recursive_mutex mRecordedStepLock;
  std::bitset<64> mRecordedStepDirty[8][17];
  void markRecordedStepDirty(int trackIndex, int drumIndex, int stepIndex);
  void triggerNoteLocked(int trackIndex, int note, int velocity,
                         bool isSequencerTrigger = false, float gate = 0.95f,
                         bool punch = false, bool isArpTrigger = false);
  void releaseNoteLocked(int trackIndex, int note,
                         bool isSequencerTrigger = false);
  void setupTracks();

  // Global Effects
  GalacticReverb mReverbFx;
  DelayFx mDelayFx;
  SlicerFx mSlicerFxL, mSlicerFxR;
  CompressorFx mCompressorFx;
  FilterLfoFx mFilterLfoFx{FilterLfoMode::LowPass};

  // New Effects (Mono per channel)
  ChorusFx mChorusFxL, mChorusFxR;
  PhaserFx mPhaserFxL, mPhaserFxR;
  OverdriveFx mOverdriveFxL, mOverdriveFxR;
  BitcrusherFx mBitcrusherFxL, mBitcrusherFxR;
  TapeWobbleFx mTapeWobbleFx; // Stereo Linked!
  FlangerFx mFlangerFxL, mFlangerFxR;
  SimpleFilterFx mFilterPedalL[3]; // 3 Stereo Pairs of Filters
  SimpleFilterFx mFilterPedalR[3];
  TapeEchoFx mTapeEchoFxL, mTapeEchoFxR;
  OctaverFx mOctaverFxL, mOctaverFxR;
  Eq5BandFx mEq5BandFxL, mEq5BandFxR;

  // Generic LFOs for Routing
  LfoEngine mLfos[6];

  // Macros (Patch Points)
  struct MacroModule {
    float value = 0.0f;
    int sourceType = 0; // 0=None, 1=Strip, 2=Knob, 3=LFO, 4=Envelope
    int sourceIndex = -1;
    int sourceTrackIndex = -1; // For Env/Track sources
  };
  MacroModule mMacros[8]; // Increased to 8 to match UI

  // Public methods for modulation
public:
  void setMacroSource(int macroIndex, int sourceType, int sourceIndex,
                      int sourceTrackIndex = -1);
  void applyModulations();

  // FX Chaining (Soft Routing)
  // Maps SourceFX Index -> DestinationFX Index. -1 means Master Mix.
  int mFxChainDest[18];
  // Bit per FX bus: 1 = processed, 0 = bypassed entirely.
  std::atomic<uint32_t> mFxEnabledMask{0x3FFFF};
  // Per-bus fade gain used when toggling (audio thread only).
  float mFxFade[18] = {};
  // Blocks left to keep processing an FX after its input bus went silent, so
  // delay/reverb tails are not cut. Zero means "skip while the bus is silent".
  int mFxTailBlocks[18] = {};
  // Performance profile settings.
  std::atomic<int> mMaxVoicesPerTrack{16};
  int mModDecimation = 1;    // Apply modulation routing every N blocks
  int mModBlockCounter = 0;  // Audio thread only
  bool mIdleGateEnabled = false;
  int mIdleBlocks = 0;       // Consecutive silent blocks (audio thread only)
  // Feedback buffers for backward-chaining effects (1-sample latency)
  float mFxFeedbacksL[18] = {0.0f};
  float mFxFeedbacksR[18] = {0.0f};

  // v3.1 Block Buffers (pre-allocated to avoid malloc on audio thread)
  std::vector<float> mFxBusBlockL[18];
  std::vector<float> mFxBusBlockR[18];
  std::vector<float> mMixedSampleL;
  std::vector<float> mMixedSampleR;
  std::vector<float> mSidechainBuffer;
  std::vector<float> mSpreadL;
  std::vector<float> mSpreadR;
  std::vector<float> mWetSampleL;
  std::vector<float> mWetSampleR;


  // FX Split Filter LFO Effects (Slots 9/10)
  FilterLfoFx mHpLfoL{FilterLfoMode::HighPass};
  FilterLfoFx mHpLfoR{FilterLfoMode::HighPass};
  FilterLfoFx mLpLfoL{FilterLfoMode::LowPass};
  FilterLfoFx mLpLfoR{FilterLfoMode::LowPass};
  
  // v3.1.37: Pre-allocated scratch buffer for recording/sidechain (Wait-free)
  std::vector<float> mRecordingScratchBuffer;

  int mSidechainSourceTrack = -1;
  int mSidechainSourceDrumIdx = -1;
  // Tracks cloned live from another track (for note-off propagation).
  int mArpCloneSource[8] = {-1, -1, -1, -1, -1, -1, -1, -1};
  std::atomic<float> mMasterVolume{0.8f};
  bool mIsFloatFormat = true; // Assume Float, but verify at stream open
  float mFxMixLevels[18] = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
                            1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
                            1.0f, 1.0f, 1.0f, 1.0f}; // Default to 1.0
  float mInputRingBuffer[8192] = {0.0f};
  std::atomic<uint32_t> mInputWritePtr{0};
  uint32_t mInputReadPtr = 0;
  
  float mSystemAudioRingBuffer[8192] = {0.0f};
  std::atomic<uint32_t> mSystemAudioWritePtr{0};
  uint32_t mSystemAudioReadPtr = 0;
  std::atomic<int> mGlobalVoiceCount{0};
  std::string mAppDataDir = "";
  std::atomic<int> mGlobalTranspose{0};
  
  UISnapshot mUISnapshot;
  std::mutex mSnapshotLock;
  std::atomic<bool> mUISnapshotReady{false};
  std::recursive_mutex &getLock() { return mLock; }
  const std::vector<Track> &getTracks() const { return mTracks; }
};

#endif // AUDIO_ENGINE_CORE_H
