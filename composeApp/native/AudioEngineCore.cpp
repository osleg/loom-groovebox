#define TSF_IMPLEMENTATION
#include "AudioEngineCore.h"
#include "WavFileUtils.h" // New
#include "DenormalDisabler.h"
#include "engines/BitcrusherFx.h"
#include <fstream>
#include <sstream>

#include <algorithm>
#include "Log.h"
#include <chrono>
#include <cmath>
#include <sys/stat.h>

#ifdef __ANDROID__
#include <sys/resource.h>
#endif

#if defined(__i386__) || defined(__x86_64__)
#include <xmmintrin.h>
#elif defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#undef LOG_TAG
#define LOG_TAG "AudioEngine"

// Fast, lock-free random generator (Linear Congruential Generator)
struct FastRandom {
  unsigned int seed = 123456789;
  inline float next() {
    seed = (seed * 1103515245 + 12345);
    return static_cast<float>(seed) / 4294967296.0f;
  }
} gRng;

// (Using fast_tanh from Utils.h)

static inline float softLimit(float x) {
  if (std::isnan(x))
    return 0.0f;
  // Soft-Knee Limiter (Linear until -1dB, then smooth curve to 0dB)
  // v3.1.43: Raised knee from 0.707 to 0.9 for more headroom
  float absX = std::abs(x);
  if (absX < 0.9f)
    return x;

  // Soft compression above -1dB:
  float extended = (absX - 0.9f) / 0.1f;
  float limited = 0.9f + 0.1f * fast_tanh(extended);
  return (x > 0) ? limited : -limited;
}

AudioEngineCore::AudioEngineCore() {
  // UI/hardware can query playheads before the first transport render.
  for (int t = 0; t < 8; ++t) {
    mTrackCurrentStep[t].store(0);
    for (int d = 0; d < 16; ++d) mTrackCurrentDrumStep[t][d].store(0);
  }
  mSampleRate = 48000.0; // Default to common Android rate
  mBpm = 120.0f;
  mPatternLength = 64;
  setupTracks();
  for (int i = 0; i < 18; ++i) {
    mFxChainDest[i] = -1;
    mFxBusBlockL[i].resize(1024, 0.0f);
    mFxBusBlockR[i].resize(1024, 0.0f);
  }
  mMixedSampleL.resize(1024, 0.0f);
  mMixedSampleR.resize(1024, 0.0f);
  mSidechainBuffer.resize(1024, 0.0f);
  mSpreadL.resize(1024, 0.0f);
  mSpreadR.resize(1024, 0.0f);
  mWetSampleL.resize(1024, 0.0f);
  mWetSampleR.resize(1024, 0.0f);
  mRecordingScratchBuffer.resize(1024, 0.0f);


  // FX Slot Filters (Slots 9/10)
  mHpLfoL.setMode(1); // HP
  mHpLfoR.setMode(1); // HP
  mHpLfoL.setCutoff(0.0f);
  mHpLfoR.setCutoff(0.0f);

  mLpLfoL.setMode(0); // LP
  mLpLfoR.setMode(0); // LP
  mLpLfoL.setCutoff(1.0f);
  mLpLfoR.setCutoff(1.0f);
  mHpLfoL.reset((float)mSampleRate);
  mHpLfoR.reset((float)mSampleRate);
  mLpLfoL.reset((float)mSampleRate);
  mLpLfoR.reset((float)mSampleRate);
  mSidechainSourceTrack = -1;
  mSidechainSourceDrumIdx = -1;

  for (int i = 0; i < 18; ++i) {
    mFxMixLevels[i] = 1.0f;
    mFxChainDest[i] = -1;

    mFxFeedbacksL[i] = 0.0f;
    mFxFeedbacksR[i] = 0.0f;
    mFxFade[i] = 1.0f;
  }

  // Initialize Filter Pedals
  mEq5BandFxL.reset();
  mEq5BandFxR.reset();

  for (int i = 0; i < 3; ++i) {
    mFilterPedalL[i].clear();
    mFilterPedalR[i].clear();
    // Default to mid-range cutoff (~632Hz) so filtering is audible
    mFilterPedalL[i].setCutoff(0.5f);
    mFilterPedalR[i].setCutoff(0.5f);
    mFilterPedalL[i].setMix(1.0f);
    mFilterPedalR[i].setMix(1.0f);
  }
  mFxMixLevels[12] = 1.0f; // Filter 1 Bus
  mFxMixLevels[15] = 1.0f; // Filter 2 Bus
  mFxMixLevels[16] = 1.0f; // Filter 3 Bus

  // Initialize other FX Mixes to 1.0 (Since we use per-track sends/mixes now)
  mChorusFxL.setMix(1.0f);
  mChorusFxR.setMix(1.0f);
  mPhaserFxL.setMix(1.0f);
  mPhaserFxR.setMix(1.0f);
  mFlangerFxL.setMix(1.0f);
  mFlangerFxR.setMix(1.0f);
  mOctaverFxL.setMix(1.0f);
  mOctaverFxR.setMix(1.0f);
  mTapeEchoFxL.setMix(1.0f);
  mTapeEchoFxR.setMix(1.0f);

  // Reverb and Delay can stay wet-only by default too
  mDelayFx.setMix(1.0f);
  mGlobalTranspose = 0;
  mInputWritePtr = 0;
  mInputReadPtr = 0;
}

AudioEngineCore::~AudioEngineCore() {}

void AudioEngineCore::setTrackTranspose(int trackIndex, int semitones) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_TRANSPOSE;
  cmd.trackIndex = trackIndex;
  cmd.data1 = std::max(-24, std::min(24, semitones));
  {
    std::lock_guard<std::mutex> lock(mCommandLock);
    mCommandQueue.push_back(cmd);
  }
}

void AudioEngineCore::setPitchBend(int trackIndex, float semitones) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_PITCH_BEND;
  cmd.trackIndex = trackIndex;
  cmd.value = std::max(-12.0f, std::min(12.0f, semitones));
  {
    std::lock_guard<std::mutex> lock(mCommandLock);
    mCommandQueue.push_back(cmd);
  }
}

// Helper to reset a single track's parameters and engine state
void AudioEngineCore::initTrack(int i) {
  mTracks[i].volume = 0.7f;
  mTracks[i].smoothedVolume = 0.7f;
  mTracks[i].pan = 0.5f;
  mTracks[i].smoothedPan = 0.5f;
  mTracks[i].panL = 0.7071f;
  mTracks[i].panR = 0.7071f;
  mTracks[i].mSilenceFrames = 48002;
  mTracks[i].isActive = false; // Start inactive to save CPU

  // Attempt to load from default file if it exists
  if (!mAppDataDir.empty()) {
    std::string path = mAppDataDir + "/defaults/default_" +
                       std::to_string(mTracks[i].engineType) + ".gbs";
    std::ifstream file(path);
    if (file.is_open()) {
      std::string line;
      if (std::getline(file, line) && line == "LOOM_PRESET_V1") {
        int idx = 0;
        int pCount = sizeof(mTracks[i].parameters) / sizeof(float);
        while (std::getline(file, line) && idx < pCount) {
          try {
            mTracks[i].parameters[idx] = std::stof(line);
            mTracks[i].appliedParameters[idx] = mTracks[i].parameters[idx];
          } catch (...) {
          }
          idx++;
        }
        file.close();
        mTracks[i].mParametersDirty = true;
        // If we loaded a preset, we still want to initialize non-parameter
        // state but we skip the hardcoded param defaults
        // Reset Engines to match params
        mTracks[i].subtractiveEngine.setSampleRate(mSampleRate);
        mTracks[i].fmEngine.setSampleRate(mSampleRate);
        mTracks[i].fmDrumEngine.setSampleRate(mSampleRate);
        mTracks[i].analogDrumEngine.setSampleRate(mSampleRate);
        mTracks[i].wavetableEngine.setSampleRate(mSampleRate);
        mTracks[i].audioInEngine.setSampleRate(mSampleRate);
        mTracks[i].soundFontEngine.setSampleRate(mSampleRate);
        mTracks[i].soundFontEngine.allNotesOff();

        // Apply loaded parameters to engines
        for (int p = 0; p < idx; ++p) {
          setParameter(i, p, mTracks[i].parameters[p], true);
        }
        return;
      }
      file.close();
    }
  }

  // Clear all parameters and FX sends using atomic-safe loops
  for (int j = 0; j < 2500; ++j) {
      mTracks[i].parameters[j] = 0.0f;
      mTracks[i].appliedParameters[j] = 0.0f;
  }
  for (int j = 0; j < 18; ++j) {
      mTracks[i].fxSends[j].store(0.0f);
      mTracks[i].smoothedFxSends[j] = 0.0f;
      mTracks[i].fxMix[j].store(1.0f); // Default to Unity!
  }

  // Initialize defaults
  mTracks[i].subtractiveEngine.setSustain(1.0f);
  mTracks[i].subtractiveEngine.setDecay(0.5f);

  mTracks[i].fmEngine.resetToDefaults();
  mTracks[i].fmEngine.setParameter(101, 0.5f); // Decay
  mTracks[i].fmEngine.setParameter(102, 1.0f); // Sustain

  // Analog Drum Defaults
  mTracks[i].analogDrumEngine.resetToDefaults();

  // Audio In Defaults
  mTracks[i].audioInEngine.resetToDefaults();

  // FM Drum Defaults
  for (int k = 0; k < 8; k++) {
    mTracks[i].fmDrumEngine.setParameter(k, 0, 0.5f);  // Pitch
    mTracks[i].fmDrumEngine.setParameter(k, 1, 0.5f);  // Tone
    mTracks[i].fmDrumEngine.setParameter(k, 2, 0.20f); // Decay (User Default)
  }

  // Sync parameters array with audible defaults so UI doesn't zero them out
  mTracks[i].parameters[0] = 0.7f;     // Volume
  mTracks[i].parameters[1] = 1.0f;     // Cutoff (Open)
  mTracks[i].parameters[2] = 0.0f;     // Resonance
  mTracks[i].parameters[9] = 0.5f;     // Pan (Center) - Fix "Left at Launch"
  mTracks[i].parameters[100] = 0.01f;  // Attack
  mTracks[i].parameters[101] = 0.5f;   // Decay
  mTracks[i].parameters[102] = 1.0f;   // Sustain
  mTracks[i].parameters[103] = 0.2f;   // Release
  mTracks[i].parameters[107] = 0.6f;   // Osc 1 Volume
  mTracks[i].parameters[108] = 0.4f;   // Osc 2 Volume
  mTracks[i].parameters[109] = 0.4f;   // Osc 3 (Sub) Volume
  mTracks[i].parameters[160] = 0.25f;  // Osc 1 Pitch (1.0)
  mTracks[i].parameters[161] = 0.25f;  // Osc 2 Pitch (1.0)
  mTracks[i].parameters[162] = 0.125f; // Osc 3 (Sub) Pitch (0.5)
  mTracks[i].parameters[163] = 0.25f;  // Osc 4 Pitch (1.0)

  // FM Defaults (Op Levels)
  mTracks[i].parameters[160] = 0.8f; // Op 1 Level
  mTracks[i].parameters[166] = 0.0f; // Op 2 Level (Modulator usually)

  // Wavetable Defaults
  mTracks[i].parameters[310] = 0.0f; // Position

  // SoundFont Defaults
  mTracks[i].parameters[0] = 0.7f; // Level override for SF
  mTracks[i].soundFontEngine.setSampleRate(mSampleRate);
  mTracks[i].parameters[311] = 0.0f; // Morph

  // Sampler Defaults
  mTracks[i].parameters[320] = 0.0f; // Mode: 1-Shot
  mTracks[i].parameters[341] = 0.5f; // Speed (50%)

  // CRITICAL: Force DSP to update immediately using these defaults.
  // The UI relies on the 'parameters' array, but the Engine needs
  // 'setParameter' calls.
  // Common Defaults
  setParameter(i, 0, 0.7f);     // Volume
  setParameter(i, 1, 1.0f);     // Cutoff
  setParameter(i, 2, 0.0f);     // Resonance
  setParameter(i, 100, 0.01f);  // Attack
  setParameter(i, 101, 0.5f);   // Decay
  setParameter(i, 102, 1.0f);   // Sustain
  setParameter(i, 103, 0.2f);   // Release
  setParameter(i, 107, 0.6f);   // Osc 1
  setParameter(i, 108, 0.4f);   // Osc 2
  setParameter(i, 109, 0.4f);   // Osc 3 (Sub)
  setParameter(i, 162, 0.125f); // Osc 3 Pitch (Sub)
  setParameter(i, 9, 0.5f);     // Pan (Center)
  setParameter(i, 341, 0.5f);   // Sampler Speed

  // Engine-Specific Defaults
  int type = mTracks[i].engineType;
  if (type == 0) {               // Subtractive
    setParameter(i, 104, 0.2f);  // Osc 1 Wave: Sawtooth
    setParameter(i, 105, 0.4f);  // Osc 2 Wave: Square
    setParameter(i, 160, 0.25f); // Osc 1 Pitch: 1.0 (Value * 4.0)
    setParameter(i, 161, 0.25f); // Osc 2 Pitch: 1.0 (Value * 4.0)
    setParameter(
        i, 162,
        0.5f); // Osc 3 (Sub) Pitch: 1.0 (Value * 2.0) -> One octave lower
    setParameter(i, 350, 1.0f); // Use Envelope: True
    setParameter(i, 107, 0.6f); // Ensure Osc 1 Vol is set
    setParameter(i, 9, 0.5f);   // PAN CENTER
  } else if (type == 1) {       // FM
    // Use the "Vibe" preset (ID 11) directly to guarantee good sound
    mTracks[i].fmEngine.loadPreset(11);

    // Ensure envelope is enabled
    setParameter(i, 350, 1.0f);

    // Sync critical params back to parameter array for UI consistency
    mTracks[i].parameters[156] = 0.2f;  // Algo 2
    mTracks[i].parameters[160] = 0.5f;  // Op 1 Level
    mTracks[i].parameters[161] = 0.05f; // Op 1 Atk
    mTracks[i].parameters[166] = 0.0f;  // Op 2 Level
  } else if (type == 5) {               // FM Drum
    // Initialize 8 drums (Kick, Snare, Tom, HH, OpenHH, Cymbal, Perc, Noise)
    for (int drum = 0; drum < 8; ++drum) {
      int baseId = 200 + (drum * 10);
      setParameter(i, baseId + 5, 0.7f);  // Gain
      setParameter(i, baseId + 2, 0.20f); // Decay
      setParameter(i, baseId + 1, 0.5f);  // Tone/Feedback
    }
  } else if (type == 4) { // Wavetable
    mTracks[i].wavetableEngine.resetToDefaults();
    setParameter(i, 458, 1.0f); // Cutoff
    // ADSR A=2, D=10, S=30, R=50 (approx scaled 0.0-1.0 or raw?)
    // Assuming 0-1 scale: 0.02, 0.1, 0.3, 0.5
    setParameter(i, 454, 0.02f);
    setParameter(i, 455, 0.1f);
    setParameter(i, 456, 0.3f);
    setParameter(i, 457, 0.5f);
    // Explicitly set bits/srate to full quality
    setParameter(i, 475, 0.0f); // Bits (0=Full)
    setParameter(i, 476, 0.0f); // Srate (0=Full)
  } else if (type == 3) {       // Granular
    mTracks[i].granularEngine.resetToDefaults();

    // Explicitly reset ALL parameters to match Engine defaults
    // This ensures that when "Restore Patch" is clicked, everything resets
    // and the UI (via getAllTrackParameters) reflects it.

    // Core (Window)
    setParameter(i, 400, 0.5f); // Position
    setParameter(i, 401, 1.0f); // Speed
    setParameter(i, 406, 0.2f); // Grain Size
    setParameter(i, 407, 0.5f); // Density
    setParameter(i, 415, 0.0f); // Spray
    setParameter(i, 429, 0.4f); // Gain

    // ADSR (Main) - 425-428
    setParameter(i, 425, 0.01f); // Attack (Main)
    setParameter(i, 426, 0.1f);  // Decay
    setParameter(i, 427, 1.0f);  // Sustain
    setParameter(i, 428, 0.2f);  // Release

    // Grain Envelope - 408-409 (Not typically exposed but good to reset)
    setParameter(i, 408, 0.5f);
    setParameter(i, 409, 0.5f);

    // Pitch & Mod
    setParameter(i, 410, 1.0f); // Pitch
    setParameter(i, 416, 0.0f); // Detune
    setParameter(i, 355, 0.0f); // Glide
    setParameter(i, 417, 0.0f); // Random Timing

    // Behavior
    setParameter(i, 420, 0.0f); // Reverse Prob
    setParameter(i, 419, 0.5f); // Width
    setParameter(i, 418, 0.2f); // Grain Count (approx 20)

    // LFOs (Reset all to 0/Basic)
    // LFO 1 (402-405)
    setParameter(i, 402, 0.0f);
    setParameter(i, 403, 0.1f);
    setParameter(i, 404, 0.0f);
    setParameter(i, 405, 0.0f);
    // LFO 2 (411-414)
    setParameter(i, 411, 0.0f);
    setParameter(i, 412, 0.1f);
    setParameter(i, 413, 0.0f);
    setParameter(i, 414, 0.0f);
    // LFO 3 (421-424)
    setParameter(i, 421, 0.0f);
    setParameter(i, 422, 0.1f);
    setParameter(i, 423, 0.0f);
    setParameter(i, 424, 0.0f);

    setParameter(i, 350, 1.0f);  // Use Env
  } else if (type == 2) {        // Sampler
    setParameter(i, 330, 0.0f);  // Start
    setParameter(i, 331, 1.0f);  // End
    setParameter(i, 300, 0.5f);  // Pitch (Normal / 50%)
    setParameter(i, 301, 0.25f); // Stretch (1.0x / 25%)
    setParameter(i, 302, 0.5f);  // Speed (1.0x)
    setParameter(i, 350, 1.0f);  // Use Envelope: True
    setParameter(i, 341, 0.5f);  // Speed UI Sync
    setParameter(i, 310, 0.01f); // Attack
    setParameter(i, 311, 0.5f);  // Decay
    setParameter(i, 312, 1.0f);  // Sustain
    setParameter(i, 313, 0.2f);  // Release
  } else {
    setParameter(i, 350, 1.0f); // Default Env usage True
  }

  clearSequencer(i);
}

void AudioEngineCore::setupTracks() {
  mTracks.reserve(8);
  for (int i = 0; i < 8; ++i) {
    mTracks.emplace_back();
    initTrack(i); // FORCE RESTORE PATCH STATE ON STARTUP
  }
}

void AudioEngineCore::restoreTrackPreset(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < 8) {
    initTrack(trackIndex);
  }
}

void AudioEngineCore::saveTrackPreset(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex < 0 || trackIndex >= (int)mTracks.size() ||
      mAppDataDir.empty())
    return;

  auto &track = mTracks[trackIndex];
  // Ensure "defaults" directory exists
  std::string dir = mAppDataDir + "/defaults";
  mkdir(dir.c_str(), 0777);

  std::string path =
      dir + "/default_" + std::to_string(track.engineType) + ".gbs";
  std::ofstream file(path);
  if (file.is_open()) {
    file << "LOOM_PRESET_V1\n";
    for (int i = 0; i < 2500; i++) {
      file << track.parameters[i] << "\n";
    }
    file.close();
    LOGD("Saved default preset for engine %d to %s", track.engineType,
         path.c_str());
  }
}

void AudioEngineCore::saveTrackPresetToPath(int trackIndex, std::string path) {
  std::string content;
  {
    std::lock_guard<std::recursive_mutex> lock(mLock);
    if (trackIndex < 0 || trackIndex >= (int)mTracks.size())
      return;

    auto &track = mTracks[trackIndex];
    std::stringstream ss;
    ss << "LOOM_PRESET_V1\n";
    // Save parameters
    for (int i = 0; i < 2500; i++) {
      float val = track.parameters[i];
      if (!std::isfinite(val))
        val = 0.0f;
      ss << val << "\n";
    }
    // Save sequencer steps
    auto steps = track.sequencer.getSteps();
    ss << "STEPS_V1\n";
    ss << steps.size() << "\n";
    for (const auto &step : steps) {
      float firstVel = step.notes.empty() ? 0.8f : step.notes[0].velocity;
      if (!std::isfinite(firstVel))
        firstVel = 0.8f;
      float gate = step.gate;
      if (!std::isfinite(gate))
        gate = 1.0f;

      ss << (step.active ? 1 : 0) << " " << firstVel << " " << gate << " "
         << step.notes.size();
      for (const auto &noteInfo : step.notes)
        ss << " " << noteInfo.note;
      ss << "\n";
    }
    content = ss.str();
  }

  // Disk I/O outside of the lock
  std::string tempPath = path + ".tmp";
  std::ofstream file(tempPath);
  if (file.is_open()) {
    file << content;
    file.close();
    // Atomic rename
    if (std::rename(tempPath.c_str(), path.c_str()) == 0) {
      LOGD("Auto-saved track %d sequence to %s", trackIndex, path.c_str());
    } else {
      std::remove(tempPath.c_str());
      LOGD("Failed to rename temp file for track %d save", trackIndex);
    }
  }
}


// Internal Note Logic
void AudioEngineCore::triggerNoteLocked(int trackIndex, int note, int velocity,
                                    bool isSequencerTrigger, float gate,
                                    bool punch, bool isArpTrigger) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    Track &track = mTracks[trackIndex];
    if (track.isDisabled)
      return;
    if (!isSequencerTrigger) {
      LOGD("ManualTrigger T%d Note=%d Vel=%d", trackIndex, note, velocity);
    }

    if (track.engineType == 7) {
      enqueueMidiEvent(0x90, track.midiOutChannel - 1, note, velocity,
                       trackIndex);
      track.isActive = true;
      return;
    }

    // Apply Per-Track Transpose (Skip Drums and Slicers)
    if (track.engineType != 5 && track.engineType != 6) {
      bool isSliceMode =
          (track.engineType == 2 && track.parameters[320] > 1.5f);
      if (!isSliceMode) {
        note += track.transpose;
      }
    }

    if (!isSequencerTrigger) {
      track.mPhysicallyHeldNoteCount++;
      if (track.arpeggiator.getMode() != ArpMode::OFF) {
        if (track.mPhysicallyHeldNoteCount == 1) {
          track.mArpCountdown = 0; // Immediate trigger for new gesture
        }
        track.arpeggiator.addNote(note);
        // Live clones follow the source: mirror the gesture so layering keeps
        // working after the cloned snapshot's first gesture ends.
        for (int d = 0; d < (int)mTracks.size(); ++d) {
          if (d != trackIndex && mArpCloneSource[d] == trackIndex &&
              mTracks[d].arpeggiator.getMode() != ArpMode::OFF) {
            if (track.mPhysicallyHeldNoteCount == 1) {
              mTracks[d].mArpCountdown = 0;
            }
            mTracks[d].arpeggiator.addNote(note);
          }
        }
        return;
      }
    }

    // Check for "Tie" (Sustain across steps)
    if (isSequencerTrigger) {
      static int trigLog = 0;
      if (trigLog++ % 50 == 0 ||
          trackIndex == 1) { // ALWAYS log FM (T1) for now
        float samplesPerStep = (15.0f * mSampleRate.load(std::memory_order_relaxed)) / std::max(1.0f, mBpm.load(std::memory_order_relaxed));
        float effectiveMultiplier = std::max(0.01f, track.mClockMultiplier);
        float trackSamplesPerStep = samplesPerStep / effectiveMultiplier;
        LOGD(
                            "SeqTrigger T%d Note=%d SPS=%.2f Multi=%.2f "
                            "Gate=%.2f Bank=%d Punch=%d",
                            trackIndex, note, trackSamplesPerStep,
                            effectiveMultiplier, gate,
                            track.selectedFmDrumInstrument, punch);
      }
    } else {
      // Recording Logic Fix: Avoid duplicate notes if one is already recording
      // for this pitch
      if (mIsRecording) {
        for (const auto &rn : track.mRecordingNotes) {
          if (rn.note == note)
            return; // Already recording this note (Legato/Slide)
        }
      }
    }

    if (punch) {
      track.mPunchCounter = 4000; // Reset punch counter on trigger
    }

    // 1. Legato / Retrigger Check
    // v3.0: Consolidated — single pass handles legato tie, retrigger, and voice
    // release. Prevents double-decrement of mGlobalVoiceCount.
    for (int i = 0; i < AudioEngineCore::Track::MAX_POLYPHONY; ++i) {
      if (track.mActiveNotes[i].active && track.mActiveNotes[i].note == note) {
        float samplesPerStep = (15.0f * mSampleRate.load(std::memory_order_relaxed)) / std::max(1.0f, mBpm.load(std::memory_order_relaxed));
        float trackSamplesPerStep =
            samplesPerStep / std::max(0.01f, track.mClockMultiplier);

        // Only tie if gate is explicitly long (legato) AND note has remaining
        // time
        // v3.0: Require gate >= 1.0f (user specifically configured tie) to
        // avoid accidental infinite ties when Humanize jitter overlaps default 0.95f gates.
        if (gate >= 1.0f && track.mActiveNotes[i].durationRemaining > 512) {
          track.mActiveNotes[i].durationRemaining =
              trackSamplesPerStep * (gate + 0.05f); // Overlap for tie
          return; // Skip re-triggering (Legato)
        }

        // Retriggering: Deactivate slot and release on engines for crisp attack
        track.mActiveNotes[i].active = false;
        mGlobalVoiceCount = std::max(0, mGlobalVoiceCount - 1);

        track.subtractiveEngine.releaseNote(note);
        track.fmEngine.releaseNote(note);
        track.samplerEngine.releaseNote(note);
        track.wavetableEngine.releaseNote(note);
        
      track.soundFontEngine.noteOff(note);
      
      // Fix: Add ALL engines to releaseNoteLocked and Retriggerings
      track.granularEngine.releaseNote(note);
      track.fmDrumEngine.releaseNote(note);
      // analogDrumEngine doesn't need releaseNote, but call it for safety/future compat
      track.analogDrumEngine.releaseNote(note);

      }
    }

    // 2. Setup Frequency & Track State
    float freq =
        (track.engineType == 5)
            ? 440.0f
            : 440.0f * powf(2.0f, (note - 69 + track.mPitchBend) / 12.0f);
    track.currentFrequency = freq;
    track.subtractiveEngine.setFrequency(freq, mSampleRate.load(std::memory_order_relaxed));
    track.fmEngine.setFrequency(freq, mSampleRate.load(std::memory_order_relaxed));
    track.wavetableEngine.setFrequency(freq, mSampleRate.load(std::memory_order_relaxed));
    track.analogDrumEngine.setSampleRate(mSampleRate.load(std::memory_order_relaxed));

    track.isActive = true;
    track.mSilenceFrames = 0;

    if (punch) {
      track.mPunchCounter = static_cast<int>(mSampleRate * 0.05f); // 50ms spike
    }

    // 3. Allocate Voice (find free slot, or steal oldest if at cap)
    // v3.0: CRITICAL — we MUST allocate a tracking slot for every engine
    // trigger. Without a slot, the note-off timer never fires, and the
    // engine voice gets stuck in Sustain forever ("envelope ignored").
    const int voiceCap = std::max(
        1, std::min((int)AudioEngineCore::Track::MAX_POLYPHONY,
                    mMaxVoicesPerTrack.load(std::memory_order_relaxed)));
    int activeVoiceCount = 0;
    for (int i = 0; i < AudioEngineCore::Track::MAX_POLYPHONY; ++i) {
      if (track.mActiveNotes[i].active)
        activeVoiceCount++;
    }
    int allocIdx = -1;
    if (activeVoiceCount < voiceCap) {
      for (int i = 0; i < AudioEngineCore::Track::MAX_POLYPHONY; ++i) {
        if (!track.mActiveNotes[i].active) {
          allocIdx = i;
          break;
        }
      }
    }
    // If no free slot (or the voice cap is reached), steal the active voice
    // with the least remaining time.
    bool stoleActiveVoice = false;
    if (allocIdx == -1) {
      double minRemaining = 1e18;
      for (int i = 0; i < AudioEngineCore::Track::MAX_POLYPHONY; ++i) {
        if (track.mActiveNotes[i].active &&
            track.mActiveNotes[i].durationRemaining < minRemaining) {
          minRemaining = track.mActiveNotes[i].durationRemaining;
          allocIdx = i;
        }
      }
      if (allocIdx >= 0) {
        // Release the stolen voice on engines
        int stolenNote = track.mActiveNotes[allocIdx].note;
        track.subtractiveEngine.releaseNote(stolenNote);
        track.fmEngine.releaseNote(stolenNote);
        track.samplerEngine.releaseNote(stolenNote);
        track.wavetableEngine.releaseNote(stolenNote);
        track.soundFontEngine.noteOff(stolenNote);
        stoleActiveVoice = true;
      } else {
        // Defensive fallback: no active voice to steal, use a free slot.
        for (int i = 0; i < AudioEngineCore::Track::MAX_POLYPHONY; ++i) {
          if (!track.mActiveNotes[i].active) {
            allocIdx = i;
            break;
          }
        }
      }
    }
    if (allocIdx >= 0 && !stoleActiveVoice) {
      mGlobalVoiceCount++;
    }

    if (allocIdx >= 0) {
      track.mActiveNotes[allocIdx].active = true;
      track.mActiveNotes[allocIdx].note = note;
      if (isSequencerTrigger) {
        float samplesPerStep = (15.0f * mSampleRate.load(std::memory_order_relaxed)) / std::max(1.0f, mBpm.load(std::memory_order_relaxed));
        float trackSamplesPerStep =
            samplesPerStep / std::max(0.01f, track.mClockMultiplier);
        track.mActiveNotes[allocIdx].durationRemaining =
            trackSamplesPerStep * gate;
      } else {
        track.mActiveNotes[allocIdx].durationRemaining = 9999998.0f;
      }
    }

    // 4. Trigger Actual Synthesis Engines
    switch (track.engineType) {
    case 0:
      track.subtractiveEngine.triggerNote(note, velocity);
      break;
    case 1:
      track.fmEngine.triggerNote(note, velocity);
      break;
    case 2:
      track.samplerEngine.triggerNote(note, velocity);
      break;
    case 3:
      track.granularEngine.triggerNote(note, velocity);
      break;
    case 4:
      track.wavetableEngine.triggerNote(note, velocity);
      break;
    case 5:
      track.fmDrumEngine.triggerNote(note, velocity);
      break;
    case 6:
      track.analogDrumEngine.triggerNote(note, velocity);
      break;
    case 8: // AUDIO IN
      track.audioInEngine.triggerNote(note, velocity);
      break;
    case 9: // SOUNDFONT
      track.soundFontEngine.noteOn(note, velocity);
      break;
    }

    // 5. Recording Logic
    // Record if it's a manual tap OR an Arp trigger (but NOT a sequencer
    // playback trigger)
    bool isPhysicalNote = !isSequencerTrigger && !isArpTrigger;
    bool arpActive = track.arpeggiator.getMode() != ArpMode::OFF;

    if (mIsRecording && mIsPlaying && (!isSequencerTrigger || isArpTrigger)) {
      // Don't re-record a note if the current step already has content
      // (prevents loop accumulation: each loop would double the note count)
      int currentStepIdx = track.sequencer.getCurrentStepIndex();
      bool stepAlreadyHasNote = false;
      if (isArpTrigger) {
        const auto &steps = track.sequencer.getSteps();
        if (currentStepIdx < (int)steps.size() && steps[currentStepIdx].active) {
          stepAlreadyHasNote = true;
        }
      }

      if (isPhysicalNote && arpActive) {
        // Do not record physical presses if Arp is handling them
      } else if (!stepAlreadyHasNote) {
        double phase = (double)mSampleCount / (mSamplesPerStep + 0.001);
        // MICROTIMING FIX: Always record to current step with precise offset
        int stepOffset = 0;
        float subStep = static_cast<float>(phase);

        int currentStepIdx = track.sequencer.getCurrentStepIndex();

        if (track.engineType == 5 || track.engineType == 6) {
          int drumIdx = -1;
          if (note >= 60)
            drumIdx = note - 60;
          else if (note >= 0 && note < 16)
            drumIdx = note;

          if (drumIdx >= 0 && drumIdx < 16) {
            Step &s =
                track.drumSequencers[drumIdx].getStepsMutable()[currentStepIdx];
            // Use precise subStep
            s.addNote(note, static_cast<float>(velocity) / 127.0f, subStep);

            track.mRecordingNotes.push_back({note, currentStepIdx, drumIdx,
                                             (uint64_t)mGlobalStepIndex,
                                             (double)subStep});
          }
        } else if (track.engineType == 2 &&
                   track.samplerEngine.getPlayMode() >= 3) {
          int drumIdx = -1;
          if (note >= 60)
            drumIdx = note - 60;

          if (drumIdx >= 0 && drumIdx < 16) {
            Step &s =
                track.drumSequencers[drumIdx].getStepsMutable()[currentStepIdx];
            s.addNote(note, static_cast<float>(velocity) / 127.0f, subStep);

            track.mRecordingNotes.push_back({note, currentStepIdx, drumIdx,
                                             (uint64_t)mGlobalStepIndex,
                                             (double)subStep});
          }
        } else {
          Step &s = track.sequencer.getStepsMutable()[currentStepIdx];
          s.addNote(note, static_cast<float>(velocity) / 127.0f, subStep);

          track.mRecordingNotes.push_back({note, currentStepIdx, -1,
                                           (uint64_t)mGlobalStepIndex,
                                           (double)subStep});
        }
      }
    }
  }
}

// Internal Param Logic
void AudioEngineCore::setParameter(int trackIndex, int parameterId, float value,
                               bool immediate) {
  AudioCommand cmd;
  cmd.type = (trackIndex == -1) ? AudioCommand::GLOBAL_PARAM_SET
                                : AudioCommand::PARAM_SET;
  cmd.trackIndex = trackIndex;
  cmd.data1 = parameterId;
  cmd.value = value;
  cmd.immediate = immediate;

  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setParameterPreview(int trackIndex, int parameterId,
                                          float value) {
  AudioCommand cmd;
  cmd.type = AudioCommand::PARAM_PREVIEW;
  cmd.trackIndex = trackIndex;
  cmd.data1 = parameterId;
  cmd.value = value;

  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::updateEngineParameter(int trackIndex, int parameterId,
                                        float value, bool immediate) {

  // Sanitization (v2.2.2 Stability)
  if (!std::isfinite(value)) {
    return;
  }

  // Global Parameters (trackIndex = -1)
  if (trackIndex == -1) {
    // Global FX Mix Levels (3000+)
    if (parameterId >= 3000 && parameterId < 3015) {
      int fxIdx = parameterId - 3000;
      mFxMixLevels[fxIdx] = value;
      // CRITICAL: Persist Mix Levels to Track 0
      if (mTracks.size() > 0)
        mTracks[0].parameters[parameterId] = value;
      // NO RETURN: Let it fall through if needed (though global might not need
      // fallthrough if track 0 is updated directly)
    }
  }

  // Filter Pedals (2200+) - Global but callable from Track 0
  if (parameterId >= 2200 && parameterId < 2300) {
    int filterIdx = -1;
    int subParam = -1;

    if (parameterId >= 2200 && parameterId < 2205) { // 2200-2204: Filter 1
      filterIdx = 0;
      subParam = parameterId - 2200;
    } else if (parameterId >= 2205 &&
               parameterId < 2210) { // 2205-2209: Filter 2
      filterIdx = 1;
      subParam = parameterId - 2205;
    } else if (parameterId >= 2210 &&
               parameterId < 2215) { // 2210-2214: Filter 3
      filterIdx = 2;
      subParam = parameterId - 2210;
    }

    if (filterIdx != -1) {
      if (subParam == 0) { // Cutoff
        mFilterPedalL[filterIdx].setCutoff(value);
        mFilterPedalR[filterIdx].setCutoff(value);
      } else if (subParam == 1) { // Resonance
        mFilterPedalL[filterIdx].setResonance(value);
        mFilterPedalR[filterIdx].setResonance(value);
      } else if (subParam == 2) { // Mode
        // Button sends 0.0, 1.0, 2.0 directly
        mFilterPedalL[filterIdx].setMode(value);
        mFilterPedalR[filterIdx].setMode(value);
      }
      // NOTE: subParam==3 (Mix) removed - filters use Pedal SEND control now
      // mFxMixLevels for filters are initialized to 1.0 and should stay there
      // NO RETURN: Let it fall through to update mParameters array for UI
      // Persistence
      if (immediate) {
        mFilterPedalL[filterIdx].snap();
        mFilterPedalR[filterIdx].snap();
      }
    }
  }

  // Global Parameters (trackIndex = -1) Continued...
  if (trackIndex == -1) {
    updateGlobalParameter(parameterId, value);
    return;
  }

  if (trackIndex < 0 || trackIndex >= mTracks.size())

    return;
  if (parameterId < 0 || parameterId >= 2500)
    return;
  Track &track = mTracks[trackIndex];

  // Specific Logic for Global / Sends
  if (parameterId >= 2000 &&
      parameterId < 2180) { // Covers all 18 FX slots (0-17, including 5-Band EQ)
    int fxIndex = (parameterId - 2000) / 10;
    int subId = (parameterId - 2000) % 10;
    if (fxIndex >= 0 && fxIndex < 18) {
      if (subId == 0) {
        track.fxSends[fxIndex].store(value);
      } else if (subId == 1) {
        track.fxMix[fxIndex].store(1.0f); // FORCE UNITY (Ignore 0.0 from sync)
      }
    }
    return;
  }

  // Common Track Params (< 100)
  if (parameterId < 100) {
    switch (parameterId) {
    case 0:
      track.volume.store(std::max(0.001f, value));
      break;
    case 9:
      track.pan.store(std::max(0.0f, std::min(value, 1.0f)));
      {
        float angle = track.pan.load() * (float)M_PI * 0.5f;
        track.panL = cosf(angle);
        track.panR = sinf(angle);
      }
      break;
    case 1: // Common Filter Cutoff
      track.subtractiveEngine.setCutoff(value);
      track.fmEngine.setFilter(value);
      track.samplerEngine.setFilterCutoff(value);
      track.wavetableEngine.setFilterCutoff(value);
      track.granularEngine.setParameter(1, value);
      track.soundFontEngine.setParameter(1, value);
      break;
    case 2: // Common Resonance
      track.subtractiveEngine.setResonance(value);
      track.fmEngine.setResonance(value);
      track.samplerEngine.setFilterResonance(value);
      track.wavetableEngine.setResonance(value);
      track.granularEngine.setParameter(2, value);
      track.soundFontEngine.setParameter(2, value);
      break;
    case 3: // Env Amount
      track.subtractiveEngine.setFilterEnvAmount(value);
      track.fmEngine.setParameter(3, value);
      track.soundFontEngine.setParameter(3, value);
      break;
    case 4:
      track.subtractiveEngine.setOscWaveform(1, value);
      break;
    case 5:
      track.subtractiveEngine.setOscVolume(0, std::max(0.001f, value));
      break;
    case 6:
      track.subtractiveEngine.setDetune(value);
      track.soundFontEngine.setParameter(6, value);
      break;
    case 7:
      // Cubic Scaling for Synth LFO too
      track.subtractiveEngine.setLfoRate(0.01f +
                                         (value * value * value) * 49.99f);
      track.soundFontEngine.setParameter(7, value);
      break;
    case 8:
      track.subtractiveEngine.setLfoDepth(value);
      track.soundFontEngine.setParameter(8, value);
      break;
    }
  }
  // ADSR / Internal Params (100-149)
  else if (parameterId >= 100 && parameterId < 150) {
    switch (parameterId) {
    case 120:
      mTracks[trackIndex].audioInEngine.setParameter(120, value);
      if (value >= 0.5f) { // Open Mode
        // Force track active immediately (Wake Up)
        mTracks[trackIndex].isActive = true;
        mTracks[trackIndex].mSilenceFrames = 0;
      }
      break;
    case 123: // Audio In Filter Mode
      mTracks[trackIndex].audioInEngine.setParameter(123, value);
      break;
    case 100:
      track.subtractiveEngine.setAttack(value);
      track.samplerEngine.setAttack(value);
      track.granularEngine.setAttack(value);
      track.wavetableEngine.setAttack(value);
      track.fmEngine.setParameter(100, value);
      track.audioInEngine.setParameter(100, value);
      track.soundFontEngine.setParameter(100, value);
      break;
    case 101:
      track.subtractiveEngine.setDecay(value);
      track.samplerEngine.setDecay(value);
      track.granularEngine.setDecay(value);
      track.wavetableEngine.setDecay(value);
      track.fmEngine.setParameter(101, value);
      track.audioInEngine.setParameter(101, value);
      track.soundFontEngine.setParameter(101, value);
      break;
    case 102:
      track.subtractiveEngine.setSustain(value);
      track.samplerEngine.setParameter(parameterId, value);
      track.granularEngine.setParameter(parameterId, value);
      track.fmEngine.setParameter(parameterId, value);
      track.wavetableEngine.setSustain(value);
      track.audioInEngine.setParameter(parameterId, value);
      track.soundFontEngine.setParameter(102, value);
      break;
    case 103:
      track.subtractiveEngine.setRelease(value);
      track.samplerEngine.setParameter(parameterId, value);
      track.granularEngine.setParameter(parameterId, value);
      track.fmEngine.setParameter(parameterId, value);
      track.wavetableEngine.setRelease(value);
      track.audioInEngine.setParameter(parameterId, value);
      track.soundFontEngine.setParameter(103, value);
      break;
    case 104:
      track.subtractiveEngine.setOscWaveform(0, value);
      break;
    case 105:
      track.subtractiveEngine.setOscWaveform(1, value);
      break;
    case 106:
      track.subtractiveEngine.setDetune(value);
      break;
    case 107:
      track.subtractiveEngine.setOscVolume(0, value);
      break;
    case 108:
      track.subtractiveEngine.setOscVolume(1, value);
      break;
    case 109:
      track.subtractiveEngine.setOscVolume(2, value);
      break;
    case 110:
      track.subtractiveEngine.setNoiseLevel(value);
      break;
    case 112:
    case 113:
    case 122: // Wavefold
      track.subtractiveEngine.setParameter(parameterId, value);
      track.samplerEngine.setParameter(parameterId, value);
      track.audioInEngine.setParameter(parameterId, value);
      track.soundFontEngine.setParameter(parameterId, value);
      break;
    case 118:
      track.subtractiveEngine.setFilterEnvAmount(value);
      track.samplerEngine.setFilterEnvAmount(value);
      track.audioInEngine.setParameter(118, value);
      break;
    case 114:
      track.subtractiveEngine.setFilterAttack(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    case 115:
      track.subtractiveEngine.setFilterDecay(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    case 116:
      track.subtractiveEngine.setFilterSustain(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    case 117:
      track.subtractiveEngine.setFilterRelease(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    }
  }
  // Filter & Env (120-149)
  else if (parameterId >= 120 && parameterId < 150) {
    track.subtractiveEngine.setParameter(parameterId, value);
    track.samplerEngine.setParameter(parameterId, value);
    track.granularEngine.setParameter(parameterId, value);
    track.wavetableEngine.setParameter(parameterId, value);
    track.fmDrumEngine.setParameter(track.selectedFmDrumInstrument, parameterId,
                                    value);
    track.audioInEngine.setParameter(parameterId, value);
  }
  // FM / Sound Design (150-199)
  else if (parameterId >= 150 && parameterId < 200) {
    if (track.engineType == 0) { // Subtractive
      track.subtractiveEngine.setParameter(parameterId, value);
    } else if (track.engineType == 1) { // FM
      if (parameterId == 156) {
        track.fmEngine.setParameter(156, value); // Mode
      } else {
        track.fmEngine.setParameter(parameterId, value);
      }
    }
  }
  // Subtractive oscillator macros (290-292), ported from Loom Pi. One knob
  // drives the same stage on all three oscillators, and the per-oscillator
  // parameters are kept in sync so the UI and presets show the result.
  // 290 = wave morph (the SHAPE knobs), 291 = fold, 292 = drive.
  else if (parameterId >= 290 && parameterId <= 292 &&
           track.engineType == 0) {
    static const int kMacroTargets[3][3] = {
        {104, 105, 155}, {180, 181, 182}, {170, 171, 172}};
    const int *dst = kMacroTargets[parameterId - 290];
    for (int i = 0; i < 3; ++i) {
      track.parameters[dst[i]] = value;
      track.appliedParameters[dst[i]] = value;
      updateEngineParameter(trackIndex, dst[i], value);
    }
  }
  // FM Drum (200-299)
  else if (parameterId >= 200 && parameterId < 300) {
    track.fmDrumEngine.setParameter((parameterId - 200) / 10,
                                    (parameterId - 200) % 10, value);
  }
  // Sampler & Engine Sub-params (300-399)
  else if (parameterId >= 300 && parameterId < 400) {
    if (parameterId == 350) {
      track.subtractiveEngine.setUseEnvelope(value > 0.5f);
      track.fmEngine.setUseEnvelope(value > 0.5f);
      track.samplerEngine.setParameter(350, value);
      track.granularEngine.setParameter(350, value);
    } else if (parameterId == 355) {
      // User requested Curve: val * val * 0.3 (Max 0.3s)
      float glideVal = value * value * 0.3f;
      track.subtractiveEngine.setParameter(355, glideVal);
      track.fmEngine.setParameter(355, glideVal);
      track.samplerEngine.setParameter(355, glideVal);
      track.granularEngine.setParameter(355, glideVal);
      track.wavetableEngine.setParameter(355, glideVal);
      track.soundFontEngine.setParameter(355, glideVal);
    } else if (track.engineType == 5) {
      track.fmDrumEngine.setParameter(track.selectedFmDrumInstrument,
                                      parameterId - 300, value);
    } else {
      track.samplerEngine.setParameter(parameterId, value);
    }
  }
  // Granular (400-449)
  else if (parameterId >= 400 && parameterId < 450) {
    track.granularEngine.setParameter(parameterId, value);
  }
  // Wavetable (450-489)
  else if (parameterId >= 450 && parameterId < 490) {
    if (parameterId == 450)
      track.wavetableEngine.setParameter(0, value);
    else if (parameterId == 451)
      track.wavetableEngine.setParameter(1, value);
    else if (parameterId == 454)
      track.wavetableEngine.setAttack(value);
    else if (parameterId == 455)
      track.wavetableEngine.setDecay(value);
    else if (parameterId == 456)
      track.wavetableEngine.setSustain(value);
    else if (parameterId == 457)
      track.wavetableEngine.setRelease(value);
    else if (parameterId == 458)
      track.wavetableEngine.setFilterCutoff(value);
    else if (parameterId == 459)
      track.wavetableEngine.setResonance(value);
    else if (parameterId == 461)
      track.wavetableEngine.setParameter(11, value);
    else if (parameterId == 464)
      track.wavetableEngine.setParameter(14, value);
    else if (parameterId == 465)
      track.wavetableEngine.setParameter(15, value);
    else if (parameterId == 466)
      track.wavetableEngine.setParameter(16, value);
    else if (parameterId == 467)
      track.wavetableEngine.setParameter(17, value);
    else if (parameterId == 470) // Filter Mode (Added)
      track.wavetableEngine.setParameter(20, value);
    else if (parameterId == 471) // Filter Atk (Added)
      track.wavetableEngine.setParameter(21, value);
    else if (parameterId == 472) // Filter Dcy (Shared handle)
      track.wavetableEngine.setParameter(11, value);
    else if (parameterId == 473) // Filter Sus (Added)
      track.wavetableEngine.setParameter(23, value);
    else if (parameterId == 474) // Filter Rel (Added)
      track.wavetableEngine.setParameter(24, value);
    else if (parameterId == 475) // Bits (Moved from 530)
      track.wavetableEngine.setParameter(30, value);
    else if (parameterId == 476) // Srate (Moved from 531)
      track.wavetableEngine.setParameter(31, value);
  }
  // LP LFO (Pedal 10)
  if (parameterId >= 490 && parameterId < 500) {
    updateGlobalParameter(parameterId, value);
  }
  // Global Effects & Arp (500-599)
  else if (parameterId >= 500 && parameterId < 600) {
    updateGlobalParameter(parameterId, value);
    // CRITICAL: Persist All Global FX (500-599) to Track 0
    if (mTracks.size() > 0)
      mTracks[0].parameters[parameterId] = value;
  }
  // Analog Drum (600-699)
  else if (parameterId >= 600 && parameterId < 700) {
    int drumIdx = (parameterId - 600) / 10;
    int subId = (parameterId - 600) % 10;
    track.analogDrumEngine.setParameter(drumIdx, subId, value);
  }
  // Sampler Per-Slice Parameters (700-859)
  else if (parameterId >= 700 && parameterId < 860) {
    if (track.engineType == 2) {
      int sliceIdx = (parameterId - 700) / 10;
      int subParam = (parameterId - 700) % 10;
      track.samplerEngine.setSliceParameter(sliceIdx, subParam, value);
    }
  }
  // Sampler Per-Slice FX Sends (1000-1319)
  else if (parameterId >= 1000 && parameterId < 1320) {
    if (track.engineType == 2) {
      int sliceIdx = (parameterId - 1000) / 20;
      int slotIdx = (parameterId - 1000) % 20;
      track.samplerEngine.setSliceParameter(sliceIdx, 8 + slotIdx, value);
    }
  }
  // Midi Channels (800-809)
  else if (parameterId >= 800 && parameterId < 810) {
    if (parameterId == 800)
      track.midiInChannel = static_cast<int>(value);
    else if (parameterId == 801)
      track.midiOutChannel = static_cast<int>(value);
  }
  // Extra Global FX (1500-1599)
  else if (parameterId >= 1500 && parameterId < 1600) {
    updateGlobalParameter(parameterId, value);
    if (mTracks.size() > 0)
      mTracks[0].parameters[parameterId] = value;
  }
}
// End of updateEngineParameter

// Processing Commands
int AudioEngineCore::resolveVoiceNote(int trackIndex, int note) const {
  if (trackIndex < 0 || trackIndex >= (int)mTracks.size())
    return note;
  const Track &track = mTracks[trackIndex];
  if (track.engineType != 5 && track.engineType != 6) {
    const bool isSliceMode =
        (track.engineType == 2 && track.parameters[320] > 1.5f);
    if (!isSliceMode)
      note += track.transpose;
  }
  return note;
}

void AudioEngineCore::processCommands() {
  std::vector<AudioCommand> todo;
  {
    std::lock_guard<std::mutex> lock(mCommandLock);
    if (mCommandQueue.empty())
      return;
    todo.swap(mCommandQueue);
  }
  for (const auto &cmd : todo) {
    switch (cmd.type) {
    case AudioCommand::NOTE_ON:
      triggerNoteLocked(cmd.trackIndex, cmd.data1, (int)cmd.value, false);
      break;
    case AudioCommand::NOTE_OFF:
      releaseNoteLocked(cmd.trackIndex, cmd.data1, false);
      break;
    case AudioCommand::PARAM_SET:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].parameters[cmd.data1] = cmd.value;
        mTracks[cmd.trackIndex].appliedParameters[cmd.data1] = cmd.value;
        mTracks[cmd.trackIndex].mParametersDirty = true;
        updateEngineParameter(cmd.trackIndex, cmd.data1, cmd.value,
                              cmd.immediate);
      }
      break;
    case AudioCommand::PARAM_PREVIEW:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        updateEngineParameter(cmd.trackIndex, cmd.data1, cmd.value,
                              cmd.immediate);
      }
      break;
    case AudioCommand::GLOBAL_PARAM_SET:
      updateGlobalParameter(cmd.data1, cmd.value);
      break;
    case AudioCommand::SET_ENGINE_TYPE:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        if (mTracks[cmd.trackIndex].engineType != cmd.data1) {
          mTracks[cmd.trackIndex].engineType = cmd.data1;
          initTrack(cmd.trackIndex);
        }
      }
      break;
    case AudioCommand::SET_TRACK_VOLUME:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].volume = cmd.value;
        if (cmd.value > 0.001f) {
          mTracks[cmd.trackIndex].isActive = true;
          mTracks[cmd.trackIndex].mSilenceFrames = 0;
        }
      }
      break;
    case AudioCommand::SET_TRACK_PAN:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size())
        mTracks[cmd.trackIndex].pan = cmd.value;
      break;
    case AudioCommand::SET_TRACK_ACTIVE:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size())
        mTracks[cmd.trackIndex].isActive = cmd.bValue;
      break;
    case AudioCommand::SET_TRACK_DISABLED:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        Track &t = mTracks[cmd.trackIndex];
        t.isDisabled = cmd.bValue;
        if (cmd.bValue) {
           t.isActive = false;
           for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
               if (t.mActiveNotes[v].active) {
                   const int n = t.mActiveNotes[v].note;
                   t.subtractiveEngine.releaseNote(n);
                   t.fmEngine.releaseNote(n);
                   t.samplerEngine.releaseNote(n);
                   t.fmDrumEngine.releaseNote(n);
                   t.granularEngine.releaseNote(n);
                   t.wavetableEngine.releaseNote(n);
                   t.analogDrumEngine.releaseNote(n);
                   t.soundFontEngine.noteOff(n);
                   t.mActiveNotes[v].active = false;
               }
           }
        }
      }
      break;
    case AudioCommand::SET_TEMPO:
      mBpm = cmd.value;
      for (auto &track : mTracks) {
        track.samplerEngine.setProjectBpm(mBpm);
      }
      break;
    case AudioCommand::SET_TRACK_HUMANIZE:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < 8) {
        mTracks[cmd.trackIndex].humanize = cmd.value;
      }
      break;
    case AudioCommand::SET_TRACK_TRANSPOSE:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].transpose = cmd.data1;
      }
      break;
    case AudioCommand::SET_PITCH_BEND:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        Track &track = mTracks[cmd.trackIndex];
        track.mPitchBend = cmd.value;
        track.subtractiveEngine.setPitchBend(cmd.value);
        track.fmEngine.setPitchBend(cmd.value);
        track.wavetableEngine.setPitchBend(cmd.value);
        track.samplerEngine.setPitchBend(cmd.value);
        track.granularEngine.setPitchBend(cmd.value);
      }
      break;
    case AudioCommand::SET_PAD_MOD:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].padModValue = cmd.value;
      }
      break;
    case AudioCommand::SET_PAD_MOD_ROUTING:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        Track &track = mTracks[cmd.trackIndex];
        track.padModPoly = cmd.bValue;
        track.padModXDest = cmd.data1;
        track.padModYDest = cmd.data2;
        track.padModXInt = cmd.value;
        track.padModYInt = cmd.gate;
        if (track.padModPoly) {
          track.subtractiveEngine.setModRouting(cmd.data1, cmd.value, cmd.data2, cmd.gate);
          track.fmEngine.setModRouting(cmd.data1, cmd.value, cmd.data2, cmd.gate);
          track.wavetableEngine.setModRouting(cmd.data1, cmd.value, cmd.data2, cmd.gate);
          track.samplerEngine.setModRouting(cmd.data1, cmd.value, cmd.data2, cmd.gate);
        } else {
          // Leaving poly mode must not strand a voice mid-bend.
          track.subtractiveEngine.clearAllVoiceMod();
          track.fmEngine.clearAllVoiceMod();
          track.wavetableEngine.clearAllVoiceMod();
          track.samplerEngine.clearAllVoiceMod();
        }
      }
      break;
    case AudioCommand::SET_VOICE_MOD_BEGIN: {
      const int voiceNote = resolveVoiceNote(cmd.trackIndex, cmd.data1);
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size() &&
          voiceNote >= 0 && voiceNote < 128) {
        Track &track = mTracks[cmd.trackIndex];
        if (track.voiceModRef[voiceNote] < 64)
          track.voiceModRef[voiceNote]++;
      }
      break;
    }
    case AudioCommand::SET_VOICE_MOD_UPDATE: {
      const int voiceNote = resolveVoiceNote(cmd.trackIndex, cmd.data1);
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size() &&
          voiceNote >= 0 && voiceNote < 128) {
        Track &track = mTracks[cmd.trackIndex];
        if (track.padModPoly) {
          // A direct update without a begin is treated as a single finger.
          if (track.voiceModRef[voiceNote] == 0)
            track.voiceModRef[voiceNote] = 1;
          track.subtractiveEngine.setVoiceMod(voiceNote, cmd.value, cmd.gate);
          track.fmEngine.setVoiceMod(voiceNote, cmd.value, cmd.gate);
          track.wavetableEngine.setVoiceMod(voiceNote, cmd.value, cmd.gate);
          track.samplerEngine.setVoiceMod(voiceNote, cmd.value, cmd.gate);
        }
      }
      break;
    }
    case AudioCommand::SET_VOICE_MOD_END: {
      const int voiceNote = resolveVoiceNote(cmd.trackIndex, cmd.data1);
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size() &&
          voiceNote >= 0 && voiceNote < 128) {
        Track &track = mTracks[cmd.trackIndex];
        if (track.voiceModRef[voiceNote] > 0)
          track.voiceModRef[voiceNote]--;
        if (track.voiceModRef[voiceNote] == 0) {
          track.subtractiveEngine.clearVoiceMod(voiceNote);
          track.fmEngine.clearVoiceMod(voiceNote);
          track.wavetableEngine.clearVoiceMod(voiceNote);
          track.samplerEngine.clearVoiceMod(voiceNote);
        }
      }
      break;
    }
    case AudioCommand::SET_PATTERN_LENGTH:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        int length = (cmd.data1 <= 0) ? 1 : (cmd.data1 > 64 ? 64 : cmd.data1);
        mTracks[cmd.trackIndex].patternLength = length;
        // A saved per-track length is exact, not rounded up to a 16-step bank.
        // Banks are a UI window; rounding here changed 4/17/33-step projects on load.
        mTracks[cmd.trackIndex].sequencer.setConfiguration(1, length);
        for (int d = 0; d < 16; ++d)
          mTracks[cmd.trackIndex].drumSequencers[d].setConfiguration(1, length);
      }
      break;
    case AudioCommand::SET_STEP:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        Step step;
        step.isSkipped = cmd.isSkipped;
        for (size_t i = 0; i < cmd.notes.size(); ++i) {
          int n = cmd.notes[i];
          float vel = (i < cmd.noteVelocities.size()) ? cmd.noteVelocities[i]
                                                      : cmd.velocity;
          float off = (i < cmd.noteOffsets.size()) ? cmd.noteOffsets[i]
                                                   : cmd.subStepOffset;
          step.addNote(n, vel, off);
        }
        step.active = cmd.bValue;
        step.ratchet = cmd.ratchet;
        step.punch = cmd.punch;
        step.probability = cmd.probability;
        step.gate = cmd.gate;

        int firstNote = cmd.notes.empty() ? 60 : cmd.notes[0];
        int drumIdx = -1;
        bool isSamplerChops =
            (mTracks[cmd.trackIndex].engineType == 2 &&
             mTracks[cmd.trackIndex].samplerEngine.getPlayMode() >= 3);

        if (mTracks[cmd.trackIndex].engineType == 5 ||
            mTracks[cmd.trackIndex].engineType == 6 || isSamplerChops) {
          if (firstNote >= 60)
            drumIdx = firstNote - 60;
          else if (firstNote >= 0 && firstNote < 16)
            drumIdx = firstNote;
          else if (firstNote >= 35) {
            if (firstNote == 35 || firstNote == 36)
              drumIdx = 0;
            else if (firstNote == 38 || firstNote == 40)
              drumIdx = 1;
            else if (firstNote == 39)
              drumIdx = 2; // Clap
            else if (firstNote == 41 || firstNote == 43 || firstNote == 45)
              drumIdx = 2; // Toms
            else if (firstNote == 42 || firstNote == 44 || firstNote == 46)
              drumIdx = 3; // Hats
            else if (firstNote == 49)
              drumIdx = 5; // Crash
            else
              drumIdx = (firstNote % 8);
          }
        }

        if (drumIdx >= 0 && drumIdx < 16) {
          mTracks[cmd.trackIndex].drumSequencers[drumIdx].setStep(cmd.data1,
                                                                  step);
        } else {
          mTracks[cmd.trackIndex].sequencer.setStep(cmd.data1, step);
        }
      }
      break;
    case AudioCommand::SET_TRACK_MUTE:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size())
        mTracks[cmd.trackIndex].isMuted = cmd.bValue;
      break;
    case AudioCommand::SET_TRACK_SOLO:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size())
        mTracks[cmd.trackIndex].isSoloed = cmd.bValue;
      break;
    case AudioCommand::SET_ARP_RATE:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].mArpRate = cmd.value;
        mTracks[cmd.trackIndex].mArpDivisionMode = cmd.data1;
      }
      break;
    case AudioCommand::SET_ARP_STRUM:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].arpeggiator.setStrum(cmd.value);
      }
      break;
    case AudioCommand::SET_SWING:
      mSwing = cmd.value;
      for (auto &track : mTracks) {
        track.sequencer.setSwing(mSwing);
      }
      break;
    case AudioCommand::SET_CHAIN_ENABLED:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].isChainEnabled = cmd.bValue;
      }
      break;
    case AudioCommand::SET_CHAIN_LENGTH:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].chainLength =
            std::max(1, std::min(16, cmd.data1));
      }
      break;
    case AudioCommand::SET_TRACK_PROBABILITY:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].sequenceProbability = cmd.value;
      }
      break;
    case AudioCommand::SET_CHAIN_SLOT:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        auto &track = mTracks[cmd.trackIndex];
        if (cmd.data1 >= 0 && cmd.data1 < 16) {
          if (cmd.laneIndex == -1) {
            track.chainSlots[cmd.data1].steps = cmd.steps;
          } else if (cmd.laneIndex >= 0 && cmd.laneIndex < 16) {
            track.chainSlots[cmd.data1].drumLanes[cmd.laneIndex] = cmd.steps;
          }
          track.chainSlots[cmd.data1].hasSequence = true;
        }
      }
      break;
    case AudioCommand::SET_SLICES:
      if (cmd.trackIndex >= 0 && cmd.trackIndex < (int)mTracks.size()) {
        mTracks[cmd.trackIndex].samplerEngine.setSlicePoints(cmd.sliceStarts,
                                                             cmd.sliceEnds);
      }
      break;
      break;
    }
  }
}

// Consolidated Global Parameter Logic
void AudioEngineCore::updateGlobalParameter(int parameterId, float value) {
  // LP LFO (490-499)
  if (parameterId >= 490 && parameterId < 500) {
    int subId = parameterId % 10;
    if (subId == 0) {
      mLpLfoL.setRate(value);
      mLpLfoR.setRate(value);
    } else if (subId == 1) {
      mLpLfoL.setDepth(value);
      mLpLfoR.setDepth(value);
    } else if (subId == 2) {
      mLpLfoL.setShape(value);
      mLpLfoR.setShape(value);
    } else if (subId == 3) {
      mLpLfoL.setCutoff(value);
      mLpLfoR.setCutoff(value);
    } else if (subId == 4) {
      mLpLfoL.setResonance(value);
      mLpLfoR.setResonance(value);
    }
  }
  // Handle Global Effects & Arp (500-599)
  else if (parameterId >= 500 && parameterId < 600) {
    int fxId = (parameterId - 500) / 10;
    int subId = parameterId % 10;
    switch (fxId) {
    case 0: // Reverb (Slot 6)
      if (subId == 0)
        mReverbFx.setSize(value);
      else if (subId == 1)
        mReverbFx.setDamping(value);
      else if (subId == 2)
        mReverbFx.setModDepth(value);
      else if (subId == 3) {     // MIX
        mReverbFx.setMix(value); // Internal Mix
        mFxMixLevels[6] = 1.0f;  // Pass through routeFx at unity
      } else if (subId == 4)
        mReverbFx.setPreDelay(value);
      else if (subId == 5)
        mReverbFx.setType(static_cast<int>(value * 3.9f));
      else if (subId == 6)
        mReverbFx.setTone(value);
      break;
    case 1: // Chorus (Slot 2)
      if (subId == 0) {
        mChorusFxL.setRate(value);
        mChorusFxR.setRate(value);
      } else if (subId == 1) {
        mChorusFxL.setDepth(value);
        mChorusFxR.setDepth(value);
      } else if (subId == 2) { // MIX
        mChorusFxL.setMix(value);
        mChorusFxR.setMix(value);
        mFxMixLevels[2] = 1.0f;
      } else if (subId == 3) {
        mChorusFxL.setVoices(value);
        mChorusFxR.setVoices(value);
      }
      break;
    case 2: // Delay (Slot 5)
      if (subId == 0)
        mDelayFx.setDelayTime(value);
      else if (subId == 1)
        mDelayFx.setFeedback(value);
      else if (subId == 2) { // MIX
        mDelayFx.setMix(value);
        mFxMixLevels[5] = 1.0f;
      } else if (subId == 3)
        mDelayFx.setFilterMix(value);
      else if (subId == 4)
        mDelayFx.setFilterResonance(value);
      else if (subId == 5)
        mDelayFx.setType(static_cast<int>(value * 3.9f));
      else if (subId == 6)
        mDelayFx.setFilterMode(static_cast<int>(value * 2.9f));
      break;
    case 3: // Bitcrusher (Slot 1)
      if (subId == 0) {
        mBitcrusherFxL.setBits(value);
        mBitcrusherFxR.setBits(value);
      } else if (subId == 1) {
        mBitcrusherFxL.setRate(value);
        mBitcrusherFxR.setRate(value);
      } else if (subId == 2) { // MIX
        mBitcrusherFxL.setMix(value);
        mBitcrusherFxR.setMix(value);
        mFxMixLevels[1] = 1.0f;
        // REMOVED FORCE SEND: Let users control per-track sends
      }
      break;
    case 4: // Overdrive (Slot 0)
      if (subId == 0) {
        mOverdriveFxL.setDrive(value);
        mOverdriveFxR.setDrive(value);
      } else if (subId == 1) { // DISTORTION (Acts as Mix/Enable)
        mOverdriveFxL.setDistortion(value);
        mOverdriveFxR.setDistortion(value);
        // Ensure enabled
        mOverdriveFxL.setMix(1.0f);
        mOverdriveFxR.setMix(1.0f);
        mFxMixLevels[0] = 1.0f;
        // REMOVED FORCE SEND: Let users control per-track sends
      } else if (subId == 2) {
        mOverdriveFxL.setLevel(value);
        mOverdriveFxR.setLevel(value);
      } else if (subId == 3) {
        mOverdriveFxL.setTone(value);
        mOverdriveFxR.setTone(value);
      }
      break;
    case 5: // Phaser (Slot 3)
      if (subId == 0) {
        mPhaserFxL.setRate(value);
        mPhaserFxR.setRate(value);
      } else if (subId == 1) {
        mPhaserFxL.setDepth(value);
        mPhaserFxR.setDepth(value);
      } else if (subId == 2) { // MIX
        mPhaserFxL.setMix(value);
        mPhaserFxR.setMix(value);
        mFxMixLevels[3] = 1.0f;
        // REMOVED FORCE SEND: Let users control per-track sends
      } else if (subId == 3) {
        mPhaserFxL.setIntensity(value);
        mPhaserFxR.setIntensity(value);
      }
      break;
    case 6: // Tape Wobble (Slot 4)
      if (subId == 0)
        mTapeWobbleFx.setRate(value);
      else if (subId == 1)
        mTapeWobbleFx.setDepth(value);
      else if (subId == 2)
        mTapeWobbleFx.setSaturation(value);
      else if (subId == 3) { // MIX
        mTapeWobbleFx.setMix(value);
        mFxMixLevels[4] = 1.0f;
        // REMOVED FORCE SEND: Let users control per-track sends
      }
      break;
    case 7: // Slicer (Slot 7)
      if (subId < 3) {
        float rates1[] = {0.015625f, 0.03125f, 0.0625f, 0.125f,
                          0.25f,     0.5f,     1.0f,    2.0f};
        float rates2[] = {0.046875f, 0.09375f, 0.1875f, 0.375f,
                          0.75f,     1.5f,     3.0f,    6.0f};
        float rates3[] = {0.1f, 0.2f, 0.4f, 0.8f, 1.6f, 3.2f, 6.4f, 12.8f};
        if (subId == 0) {
          int idx = static_cast<int>(value * 7.99f);
          mSlicerFxL.setRate1(rates1[idx]);
          mSlicerFxR.setRate1(rates1[idx]);
          mSlicerFxL.setActive1(true);
          mSlicerFxR.setActive1(true);
        } else if (subId == 1) {
          int idx = static_cast<int>(value * 7.99f);
          mSlicerFxL.setRate2(rates2[idx]);
          mSlicerFxR.setRate2(rates2[idx]);
          mSlicerFxL.setActive2(true);
          mSlicerFxR.setActive2(true);
        } else if (subId == 2) {
          int idx = static_cast<int>(value * 7.99f);
          mSlicerFxL.setRate3(rates3[idx]);
          mSlicerFxR.setRate3(rates3[idx]);
          mSlicerFxL.setActive3(true);
          mSlicerFxR.setActive3(true);
        }
      } else if (subId == 3) {
        bool v = (value > 0.5f);
        mSlicerFxL.setActive1(v);
        mSlicerFxR.setActive1(v);
      } else if (subId == 4) {
        bool v = (value > 0.5f);
        mSlicerFxL.setActive2(v);
        mSlicerFxR.setActive2(v);
      } else if (subId == 5) {
        bool v = (value > 0.5f);
        mSlicerFxL.setActive3(v);
        mSlicerFxR.setActive3(v);
      } else if (subId == 6) { // DEPTH -> Acts as Mix
        mSlicerFxL.setDepth(value);
        mSlicerFxR.setDepth(value);
        mFxMixLevels[7] = 1.0f;
        // REMOVED FORCE SEND: Let users control per-track sends
      }
      break;
    case 8: // Compressor
      if (subId == 0)
        mCompressorFx.setThreshold(value);
      else if (subId == 1)
        mCompressorFx.setRatio(value);
      else if (subId == 2)
        mCompressorFx.setAttack(value);
      else if (subId == 3)
        mCompressorFx.setRelease(value);
      else if (subId == 4)
        mCompressorFx.setMakeup(value);
      else if (subId == 5)
        mSidechainSourceTrack = static_cast<int>(value);
      else if (subId == 6)
        mSidechainSourceDrumIdx = static_cast<int>(value);
      break;
    case 9: // HP LFO
      if (subId == 0) {
        mHpLfoL.setRate(value);
        mHpLfoR.setRate(value);
      } else if (subId == 1) {
        mHpLfoL.setDepth(value);
        mHpLfoR.setDepth(value);
      } else if (subId == 2) {
        mHpLfoL.setShape(value);
        mHpLfoR.setShape(value);
      } else if (subId == 3) {
        mHpLfoL.setCutoff(value);
        mHpLfoR.setCutoff(value);
      } else if (subId == 4) {
        mHpLfoL.setResonance(value);
        mHpLfoR.setResonance(value);
      } else if (subId == 5) { // ADDED MIX for HP LFO
        // Removed coupling
      }
      break;
    }
  }
  // Handle Extra Global FX (1500-1599)
  else if (parameterId >= 1500 && parameterId < 1600) {
    int fxId = (parameterId - 1500) / 10;
    int subId = parameterId % 10;
    switch (fxId) {
    case 0: // Flanger (Slot 11) - Insert Style
      if (subId == 0) {
        mFlangerFxL.setRate(value);
        mFlangerFxR.setRate(value);
      } else if (subId == 1) {
        mFlangerFxL.setDepth(value);
        mFlangerFxR.setDepth(value);
      } else if (subId == 2) { // MIX
        mFlangerFxL.setMix(value);
        mFlangerFxR.setMix(value);
        mFxMixLevels[11] = 1.0f;
        // REMOVED FORCE SEND: Let users control per-track sends
      } else if (subId == 3) {
        mFlangerFxL.setFeedback(value);
        mFlangerFxR.setFeedback(value);
      } else if (subId == 4) {
        float d = value * 0.02f;
        mFlangerFxL.setDelay(d);
        mFlangerFxR.setDelay(d);
      }
      break;
    case 1: // Echo (Slot 13)
      if (subId == 0) {
        mTapeEchoFxL.setDelayTime(value);
        mTapeEchoFxR.setDelayTime(value);
      } else if (subId == 1) {
        mTapeEchoFxL.setFeedback(value);
        mTapeEchoFxR.setFeedback(value);
      } else if (subId == 2) { // MIX
        mTapeEchoFxL.setMix(value);
        mTapeEchoFxR.setMix(value);
        mFxMixLevels[13] = 1.0f;
      } else if (subId == 3) { // DRIVE
        mTapeEchoFxL.setDrive(value);
        mTapeEchoFxR.setDrive(value);
      } else if (subId == 4) { // WOW
        mTapeEchoFxL.setWow(value);
        mTapeEchoFxR.setWow(value);
      } else if (subId == 5) { // FLUTTER
        mTapeEchoFxL.setFlutter(value);
        mTapeEchoFxR.setFlutter(value);
      }
      break;
    case 2: // Octaver (Slot 14)
      // Based on previous code: 0=Mix, 1=Mode, 2=Unison, 3=Detune?
      // Let's check the header I just viewed (hypothetically)
      // If header says: setMix,    case 2: // Octaver (Slot 14)
      if (subId == 0) { // MIX
        mOctaverFxL.setMix(value);
        mOctaverFxR.setMix(value);
        mFxMixLevels[14] = 1.0f;
        // REMOVED FORCE SEND: Let users control per-track sends
      } else if (subId == 1) {
        mOctaverFxL.setMode(value);
        mOctaverFxR.setMode(value);
      } else if (subId == 2) {
        mOctaverFxL.setUnison(value);
        mOctaverFxR.setUnison(value);
      } else if (subId == 3) {
        mOctaverFxL.setDetune(value);
        mOctaverFxR.setDetune(value);
      }
      break;
    case 3: // 5-Band EQ (Slot 17)
      if (subId >= 0 && subId < 5) { // Band Gains 0-4
        mEq5BandFxL.setBandGain(subId, (value - 0.5f) * 48.0f); // +/- 24dB
        mEq5BandFxR.setBandGain(subId, (value - 0.5f) * 48.0f);
      } else if (subId == 9) { // MIX Level
        mEq5BandFxL.setMix(value);
        mEq5BandFxR.setMix(value);
        mFxMixLevels[17] = 1.0f;
      }
      break;
    }
  }

  // Filter Pedals (2200-2214)
  if (parameterId >= 2200 && parameterId < 2215) {
    int filterIdx = -1;
    int subParam = -1;

    if (parameterId >= 2200 && parameterId < 2205) { // 2200-2204: Filter 1
      filterIdx = 0;
      subParam = parameterId - 2200;
    } else if (parameterId >= 2205 &&
               parameterId < 2210) { // 2205-2209: Filter 2
      filterIdx = 1;
      subParam = parameterId - 2205;
    } else if (parameterId >= 2210 &&
               parameterId < 2215) { // 2210-2214: Filter 3
      filterIdx = 2;
      subParam = parameterId - 2210;
    }

    if (filterIdx != -1) {
      if (subParam == 0) { // Cutoff
        mFilterPedalL[filterIdx].setCutoff(value);
        mFilterPedalR[filterIdx].setCutoff(value);
      } else if (subParam == 1) { // Resonance
        mFilterPedalL[filterIdx].setResonance(value);
        mFilterPedalR[filterIdx].setResonance(value);
      } else if (subParam == 2) { // Mode
        mFilterPedalL[filterIdx].setMode(value);
        mFilterPedalR[filterIdx].setMode(value);
      }
    }
  }

  // CRITICAL: Persist All Global Parameters to Track 0
  // This ensures the UI can read them back via getTrackParameters(0)
  if (!mTracks.empty()) {
    if ((parameterId >= 490 && parameterId < 1600) ||
        (parameterId >= 2200 && parameterId < 2215)) {
      mTracks[0].parameters[parameterId] = value;
    }
  }
}

void AudioEngineCore::releaseNoteLocked(int trackIndex, int note,
                                    bool isSequencerTrigger) {
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    Track &track = mTracks[trackIndex];
    if (track.engineType == 7) {
      enqueueMidiEvent(0x80, track.midiOutChannel - 1, note, 0, trackIndex);
      track.isActive = false;
    } else {
      if (!isSequencerTrigger) {
        track.mPhysicallyHeldNoteCount--;
        if (track.mPhysicallyHeldNoteCount <= 0) {
          track.mPhysicallyHeldNoteCount = 0;
          track.arpeggiator.onAllPhysicallyReleased();
          // Stop live clones layered from this track when the source gesture
          // ends (latched clones intentionally keep running).
          if (!track.arpeggiator.isLatched()) {
            for (int d = 0; d < (int)mTracks.size(); ++d) {
              if (d != trackIndex && mArpCloneSource[d] == trackIndex) {
                mTracks[d].arpeggiator.onAllPhysicallyReleased();
              }
            }
          }
        }
        if (track.arpeggiator.getMode() != ArpMode::OFF) {
          track.arpeggiator.removeNote(note);
          // Keep live clones' held set in step with the source.
          for (int d = 0; d < (int)mTracks.size(); ++d) {
            if (d != trackIndex && mArpCloneSource[d] == trackIndex &&
                mTracks[d].arpeggiator.getMode() != ArpMode::OFF) {
              mTracks[d].arpeggiator.removeNote(note);
            }
          }
        }
      }

      // ALWAYS release engine notes for manual interaction,
      // even if Arp is ON, otherwise they get stuck when unlatching or
      // switching.
      for (int i = 0; i < Track::MAX_POLYPHONY; ++i) {
        if (track.mActiveNotes[i].active &&
            track.mActiveNotes[i].note == note) {
          track.mActiveNotes[i].active = false;
          mGlobalVoiceCount = std::max(0, mGlobalVoiceCount - 1);
          // Do NOT break, release all voices for this note to be safe
        }
      }
      if (mIsRecording && mIsPlaying && !isSequencerTrigger) {
        for (auto it = track.mRecordingNotes.begin();
             it != track.mRecordingNotes.end();) {
          if (it->note == note) {
            // Calculate gate length
            // Calculate gate length with wrapping support
            double currentPos =
                (double)mGlobalStepIndex +
                ((double)mSampleCount / (mSamplesPerStep + 0.001));
            double startPos = (double)it->startGlobalStep + it->startOffset;

            // Handle wrap-around
            if (currentPos < startPos) {
              // Fix: Use 4096 as the global wrapping point for step counters
              currentPos += 4096.0;
            }

            float gate = static_cast<float>(currentPos - startPos);

            if (gate < 0.1f)
              gate = 0.1f;
            if (gate > 64.0f)
              gate = 64.0f; // Max 64 steps in v2.2.1

            if (it->drumIdx >= 0 && it->drumIdx < 8) {
              track.drumSequencers[it->drumIdx]
                  .getStepsMutable()[it->stepIndex]
                  .gate = gate;
            } else {
              track.sequencer.getStepsMutable()[it->stepIndex].gate = gate;
            }
            it = track.mRecordingNotes.erase(it);
          } else {
            ++it;
          }
        }
      }

      track.subtractiveEngine.releaseNote(note);
      track.fmEngine.releaseNote(note);
      track.samplerEngine.releaseNote(note);
      track.fmDrumEngine.releaseNote(note);
      track.granularEngine.releaseNote(note);
      track.wavetableEngine.releaseNote(note);
      track.analogDrumEngine.releaseNote(note);
      track.audioInEngine.releaseNote(note);
      
      track.soundFontEngine.noteOff(note);
      
      // Fix: Add ALL engines to releaseNoteLocked and Retriggerings
      track.granularEngine.releaseNote(note);
      track.fmDrumEngine.releaseNote(note);
      // analogDrumEngine doesn't need releaseNote, but call it for safety/future compat
      track.analogDrumEngine.releaseNote(note);

    }
  }
}

void AudioEngineCore::render(float *audioData, int32_t numFrames) {

  // --- No Global Lock Here ---

  auto start = std::chrono::steady_clock::now();
  float *output = static_cast<float *>(audioData);
  int numChannels = 2; // Fixed Stereo for Core

  memset(output, 0, numFrames * numChannels * sizeof(float));

  if (mStartupFrames > 0) {
    mStartupFrames -= numFrames;
    return;
  }

  const int kBlockSize = 256;

  // Local snapshots of atomic parameters to ensure consistency within this block
  const float currentBpm = mBpm.load(std::memory_order_relaxed);
  const double currentSampleRate = mSampleRate.load(std::memory_order_relaxed);

  float samplesPerStep =
      (static_cast<float>(currentSampleRate) * 15.0f) / (std::max(1.0f, currentBpm));
  // Safety: Prevent near-infinite loops if BPM is crazy high
  if (samplesPerStep < 12.0f)
    samplesPerStep = 12.0f;
  
  // Optimization: Only store if it changed significantly (prevents unnecessary cache invalidation)
  if (std::abs(mSamplesPerStep.load(std::memory_order_relaxed) - samplesPerStep) > 0.001f) {
    mSamplesPerStep.store(samplesPerStep, std::memory_order_relaxed);
  }

  // --- No Mutex Needed Here (v3.1.27: Decoupled) ---
  // Parameter updates are now atomic (std::atomic<float>) and Note events
  // use the thread-safe mCommandQueue. The audio thread is now wait-free.

  // --- BLOCK-LEVEL JITTER CORRECTION (Fixes 5-25Hz flickering) ---
  bool inputNeeded = isInputNeeded();
  if (inputNeeded) {
    uint32_t writePos = mInputWritePtr.load();
    int32_t currentDist = static_cast<int32_t>(writePos - mInputReadPtr);
    if (currentDist < 512 || currentDist > 8000) {
      // Jump to safe middle ground if we drift too far or get too close
      mInputReadPtr = (writePos - 2048);
    }
  }

  processCommands();

  for (int frameIdx = 0; frameIdx < numFrames; frameIdx += kBlockSize) {
    int framesToDo = std::min(kBlockSize, numFrames - frameIdx);

    {
      // Unified Processing Block (Control + Audio + NoteOff)
      // mLock is already held by the outer onAudioReady scope.

      double scLocal = mSampleCount.load(std::memory_order_relaxed);
      scLocal += framesToDo;
      const int patternLen = mPatternLength.load(std::memory_order_relaxed);
      while (scLocal >= samplesPerStep && samplesPerStep > 0.0f) {
        scLocal -= samplesPerStep;
        if (mIsPlaying) {
          // Fix: Remove 16-step modulo constraint to support long sequences and accurate gate recording
          mGlobalStepIndex = (mGlobalStepIndex + 1) % 4096; 
        }
      }
      mSampleCount.store(scLocal, std::memory_order_relaxed);

      for (int t = 0; t < (int)mTracks.size(); ++t) {
        Track &track = mTracks[t];
        if (track.isDisabled) continue;
        float effectiveMultiplier = track.mClockMultiplier;

        // Only run Arp logic if NOT a drum track to prevent
        // dual-triggering/glitches
        bool isDrumTrack = (track.engineType == 5 || track.engineType == 6);
        if (!isDrumTrack && track.mArpTriplet &&
            track.arpeggiator.getMode() != ArpMode::OFF) {
          effectiveMultiplier *= 1.5f;
        }

        float trackSamplesPerStep =
            samplesPerStep / std::max(0.01f, effectiveMultiplier);

        if (trackSamplesPerStep < 2400.0f)
          trackSamplesPerStep = 2400.0f;

        if (mIsPlaying && trackSamplesPerStep > 0) {
          track.mStepCountdown -= framesToDo;
          int safetyCounter = 0;
          while (track.mStepCountdown <= 0 && safetyCounter < 4) {
            safetyCounter++;

            bool looped = track.sequencer.advance();
            mTrackCurrentStep[t].store(track.sequencer.getCurrentStepIndex());

            // Apply SWING: even steps are longer, odd steps are shorter
            // mSwing range is -0.23 to +0.23
            int currentStep = track.sequencer.getCurrentStepIndex();
            float swingFactor =
                (currentStep % 2 == 0) ? (1.0f + mSwing) : (1.0f - mSwing);
            track.mStepCountdown += trackSamplesPerStep * swingFactor;
            if (looped && track.isChainEnabled) {
              // 1. COMMIT EXECUTED STEPS BACK TO SLOT (Required for
              // recording!)
              if (track.currentChainSlot >= 0 && track.currentChainSlot < 100) {
                track.chainSlots[track.currentChainSlot].steps =
                    track.sequencer.getSteps();
                for (int d = 0; d < 16; ++d) {
                  track.chainSlots[track.currentChainSlot].drumLanes[d] =
                      track.drumSequencers[d].getSteps();
                }
                track.chainSlots[track.currentChainSlot].hasSequence = true;

                // 2. SIGNAL FOR BACKGROUND SAVE TO FILE (if recording)
                if (mIsRecording) {
                  enqueuePatternSaveEvent(t, track.currentChainSlot);
                }
              }

              // 3. ADVANCE TO NEXT SLOT
              int nextSlot = track.currentChainSlot;
              int searchCount = 0;
              do {
                nextSlot = (nextSlot + 1) % 16;
                searchCount++;
              } while (
                  searchCount < 16 && !track.chainSlots[nextSlot].hasSequence &&
                  !mIsRecording); // If recording, even empty slots are fine!

              if (nextSlot != track.currentChainSlot) {
                track.currentChainSlot = nextSlot;
                const auto &slot = track.chainSlots[track.currentChainSlot];
                track.sequencer.setSteps(slot.steps);
                for (int i = 0; i < 16; ++i) {
                  track.drumSequencers[i].setSteps(slot.drumLanes[i]);
                }
              }
            }
            int seqStep = track.sequencer.getCurrentStepIndex();
            track.mInternalStepIndex = seqStep;

            // Restoration: Revert P-locks from the PREVIOUS step to base
            // values Optimized: Only restore parameters that were actually
            // modified
            for (int i = 0; i < track.mActivePLockCount; ++i) {
              int p = track.mActivePLocks[i];
              track.appliedParameters[p] = track.parameters[p];
              updateEngineParameter(t, p, track.parameters[p], true);
            }
            track.mActivePLockCount = 0;

            const std::vector<Step> &steps = track.sequencer.getSteps();
            if (seqStep < steps.size()) {
              const Step &s = steps[seqStep];
              if (s.active) {
                float effectiveProb = s.probability * track.sequenceProbability;
                if (effectiveProb >= 1.0f || gRng.next() <= effectiveProb) {
                  for (const auto &ni : s.notes) {
                    double randomOffset = 0.0;
                    float velocityScale = 1.0f;
                    double effectiveSubStep = ni.subStepOffset;

                    if (track.humanize > 0.001f) {
                      // Increased Humanize intensity (v2.2.1 Feedback)
                      // Timing: Up to +/- 15% of step duration
                      randomOffset = (gRng.next() - 0.5f) * 0.3f *
                                     track.humanize * trackSamplesPerStep;
                      // Velocity: Up to +/- 30%
                      velocityScale =
                          1.0f + ((gRng.next() - 0.5f) * 0.6f * track.humanize);
                    }

                    // Sanity Clamp
                    if (effectiveSubStep < 0.0 || effectiveSubStep > 1.0)
                      effectiveSubStep = 0.0;

                    // FIX: Calculate offset within the current block
                    // accurately mStepCountdown has just been incremented by
                    // trackSamplesPerStep So the step event happened at:
                    // framesToDo + (mStepCountdown - trackSamplesPerStep)
                    double offsetInBlock =
                        (double)framesToDo +
                        (track.mStepCountdown - (double)trackSamplesPerStep);

                    double delayedSamples =
                        offsetInBlock + effectiveSubStep * trackSamplesPerStep +
                        randomOffset;

                    float ratchetedGate =
                        s.gate / static_cast<float>(s.ratchet);

                    // Apply velocity scaling
                    int vel =
                        static_cast<int>(ni.velocity * 127.0f * velocityScale);
                    if (vel < 1)
                      vel = 1;
                    if (vel > 127)
                      vel = 127;

                    if (delayedSamples <= 1.0) {
                      triggerNoteLocked(t, ni.note, vel, true, ratchetedGate);
                    } else {
                      track.mPendingNotes.push_back({ni.note, (float)vel,
                                                     delayedSamples,
                                                     ratchetedGate, 1});
                    }

                    if (s.ratchet > 1) {
                      float ratchetInterval =
                          trackSamplesPerStep / static_cast<float>(s.ratchet);
                      for (int r = 1; r < s.ratchet; ++r) {
                        double rDelay = delayedSamples + (r * ratchetInterval);
                        track.mPendingNotes.push_back(
                            {ni.note, ni.velocity * 127.0f, rDelay,
                             ratchetedGate, 1});
                      }
                    }
                  }
                  // v2.2.1: Apply Parameter Locks with microtiming alignment
                  // (Rushing fix)
                  if (!s.parameterLocks.empty()) {
                    double firstNoteOffset =
                        s.notes.empty() ? 0.0 : s.notes[0].subStepOffset;
                    for (auto const &lock : s.parameterLocks) {
                      int pid = lock.first;
                      float val = lock.second;
                      track.appliedParameters[pid] = val;
                      if (firstNoteOffset > 0.001) {
                        track.mPendingParams.push_back(
                            {pid, val, firstNoteOffset * trackSamplesPerStep});
                      } else {
                        updateEngineParameter(t, pid, val, true);
                      }
                      // Track modified parameters for efficient restoration
                      if (track.mActivePLockCount < 32) {
                        bool alreadyTracked = false;
                        for (int k = 0; k < track.mActivePLockCount; ++k) {
                          if (track.mActivePLocks[k] == pid) {
                            alreadyTracked = true;
                            break;
                          }
                        }
                        if (!alreadyTracked) {
                          track.mActivePLocks[track.mActivePLockCount++] = pid;
                        }
                      }
                    }
                  }
                }
              }
            }

            // Drum Sequencer
            bool isSamplerChops = (track.engineType == 2 &&
                                   track.samplerEngine.getPlayMode() >= 3);
            if (track.engineType == 5 || track.engineType == 6 ||
                isSamplerChops) {
              for (int d = 0; d < 16; ++d) {
                track.drumSequencers[d].advance();
                int drumStep = track.drumSequencers[d].getCurrentStepIndex();
                mTrackCurrentDrumStep[t][d].store(drumStep);
                const std::vector<Step> &dSteps =
                    track.drumSequencers[d].getSteps();
                if (drumStep < dSteps.size()) {
                  const Step &ds = dSteps[drumStep];
                  if (ds.active) {
                    float effectiveProb = ds.probability * track.sequenceProbability;
                    if (effectiveProb >= 1.0f || gRng.next() <= effectiveProb) {
                      for (const auto &ni : ds.notes) {

                        double randomOffset = 0.0;
                        float velocityScale = 1.0f;
                        double effectiveSubStep = ni.subStepOffset;

                        // FIX: ADD HUMANIZE TO DRUM SEQUENCER
                        if (track.humanize > 0.001f) {
                          // Increased Drum Humanize (v2.2.1 Feedback)
                          randomOffset = (gRng.next() - 0.5f) * 0.3f *
                                         track.humanize * trackSamplesPerStep;
                          velocityScale = 1.0f + ((gRng.next() - 0.5f) * 0.6f *
                                                  track.humanize);
                        }

                        // Sanity Clamp
                        if (effectiveSubStep < 0.0 || effectiveSubStep > 1.0)
                          effectiveSubStep = 0.0;

                        // FIX: Calculate offset within the current block
                        // accurately
                        double offsetInBlock =
                            (double)framesToDo + (track.mStepCountdown -
                                                  (double)trackSamplesPerStep);

                        double delayedSamples =
                            offsetInBlock +
                            effectiveSubStep * trackSamplesPerStep +
                            randomOffset;

                        float ratchetedGate =
                            ds.gate / static_cast<float>(ds.ratchet);

                        int vel = static_cast<int>(ni.velocity * 127.0f *
                                                   velocityScale);
                        if (vel < 1)
                          vel = 1;
                        if (vel > 127)
                          vel = 127;

                        if (delayedSamples <= 1.0) {
                          triggerNoteLocked(t, ni.note, vel, true,
                                            ratchetedGate, ds.punch);
                        } else {
                          track.mPendingNotes.push_back(
                              {ni.note, (float)vel, delayedSamples,
                               ratchetedGate, 1, ds.punch});
                        }
                        if (ds.ratchet > 1) {
                          float ratchetInterval =
                              trackSamplesPerStep /
                              static_cast<float>(ds.ratchet);
                          for (int r = 1; r < ds.ratchet; ++r) {
                            double rDelay =
                                delayedSamples + (r * ratchetInterval);
                            track.mPendingNotes.push_back(
                                {ni.note, (float)vel, rDelay, ratchetedGate, 1,
                                 ds.punch});
                          }
                        }
                      }
                      // v2.2.1: Apply Parameter Locks with microtiming
                      // alignment
                      if (!ds.parameterLocks.empty()) {
                        double firstNoteOffset =
                            ds.notes.empty() ? 0.0 : ds.notes[0].subStepOffset;
                        for (auto const &lock : ds.parameterLocks) {
                          int pid = lock.first;
                          float val = lock.second;
                          track.appliedParameters[pid] = val;
                          if (firstNoteOffset > 0.001) {
                            track.mPendingParams.push_back(
                                {pid, val,
                                 firstNoteOffset * trackSamplesPerStep});
                          } else {
                            updateEngineParameter(t, pid, val, true);
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
          if (track.mStepCountdown <= 0)
            track.mStepCountdown = trackSamplesPerStep;
        }

        // Arp Clock
        if (track.arpeggiator.getMode() != ArpMode::OFF) {
          float arpSamplesPerStep =
              samplesPerStep * std::max(0.125f, track.mArpRate);
          
          arpSamplesPerStep /= track.arpeggiator.getRateMultiplier();
          arpSamplesPerStep /= track.arpeggiator.getSpeedMultiplier();

          if (track.mArpDivisionMode == 1)
            arpSamplesPerStep *= 1.5f;
          else if (track.mArpDivisionMode == 2)
            arpSamplesPerStep *= 0.66667f;

          track.mArpCountdown -= framesToDo;
          int asafety = 0;
          while (track.mArpCountdown <= 0 && asafety < 8) {
            asafety++;
            track.mArpCountdown += arpSamplesPerStep;

            float currentGate = track.arpeggiator.getCurrentGate();
            const std::vector<int> &arpNotes = track.arpeggiator.nextNotes();

            float strum = track.arpeggiator.getStrum();
            int noteCount = (int)arpNotes.size();

            for (int i = 0; i < noteCount; ++i) {
              int arpNote = arpNotes[i];
              if (arpNote >= 0) {
                float delay = 0.0f;
                if (strum > 0.001f && noteCount > 1) {
                  // Spread notes over strum*stepDuration range
                  // Linear spread: 0 to strum*samplesPerStep ?
                  // Use 'arpSamplesPerStep' as reference duration
                  delay = (i / (float)noteCount) * strum * arpSamplesPerStep;
                }

                if (delay <= 1.0f) {
                  triggerNoteLocked(t, arpNote, 100, true, currentGate, false,
                                    true);
                } else {
                  track.mPendingNotes.push_back(
                      {arpNote, 100.0f, delay, currentGate, 1, false});
                }
              }
            }
          }
          if (track.mArpCountdown <= 0)
            track.mArpCountdown = arpSamplesPerStep;
        }

        // Process Pending Parameters (v2.2.1 Rushing Fix)
        for (auto it = track.mPendingParams.begin();
             it != track.mPendingParams.end();) {
          it->samplesRemaining -= framesToDo;
          if (it->samplesRemaining <= 0) {
            updateEngineParameter(t, it->id, it->value, true);
            it = track.mPendingParams.erase(it);
          } else {
            ++it;
          }
        }

        // Process Pending
        for (auto it = track.mPendingNotes.begin();
             it != track.mPendingNotes.end();) {
          it->samplesRemaining -= framesToDo;
          if (it->samplesRemaining <= 0) {
            triggerNoteLocked(t, it->note, static_cast<int>(it->velocity), true,
                              it->gate, it->punch);
            it = track.mPendingNotes.erase(it);
          } else {
            ++it;
          }
        }

        // Note Offs
        for (int i = 0; i < AudioEngineCore::Track::MAX_POLYPHONY; ++i) {
          if (track.mActiveNotes[i].active) {
            track.mActiveNotes[i].durationRemaining -= framesToDo;
            if (track.mActiveNotes[i].durationRemaining <= 0) {
              int noteToRelease = track.mActiveNotes[i].note;
              // v3.0: Deactivate BEFORE calling release to prevent
              // releaseNoteLocked from double-decrementing via its own scan
              track.mActiveNotes[i].active = false;
              mGlobalVoiceCount = std::max(0, mGlobalVoiceCount - 1);
              releaseNoteLocked(t, noteToRelease, true);
            }
          }
        }
      }

      // Audio Block Rendering
      renderStereo(&output[frameIdx * numChannels], framesToDo);
    }

    // Push to recorder (Sampler/Granular) - PRE-ALLOCATED v3.1.37
    if (mIsRecordingSample && mRecordingTrackIndex != -1) {
      auto &recTrack = mTracks[mRecordingTrackIndex];
      int recCount = std::min((int)framesToDo, 1024);

      if (mRecordingSource == 1) { // RESAMPLE (MIX)
        for (int k = 0; k < recCount; ++k) {
          float mixed = (output[(frameIdx + k) * numChannels] +
                         output[(frameIdx + k) * numChannels + 1]) * 0.5f;
          mRecordingScratchBuffer[k] = mixed;
        }
      } else if (mRecordingSource == 2) { // SYSTEM AUDIO
        for (int k = 0; k < recCount; ++k) {
          mRecordingScratchBuffer[k] = mSystemAudioRingBuffer[(mSystemAudioReadPtr + k) & 8191];
        }
        mSystemAudioReadPtr = (mSystemAudioReadPtr + recCount) & 8191;
      } else { // MIC (DEFAULT)
        for (int k = 0; k < recCount; ++k) {
            mRecordingScratchBuffer[k] = mInputRingBuffer[(mInputReadPtr - recCount + k) & 8191];
        }
      }

      if (recCount > 0) {
        if (recTrack.engineType == 2) // SAMPLER
          recTrack.samplerEngine.pushSamples(mRecordingScratchBuffer.data(), recCount);
        else if (recTrack.engineType == 3) // GRANULAR
          recTrack.granularEngine.pushSamples(mRecordingScratchBuffer.data(), recCount);
      }
    }
  }


  auto end = std::chrono::steady_clock::now();
  float elapsed = std::chrono::duration<float>(end - start).count();
  mCpuLoad =
      mCpuLoad * 0.95f + (elapsed / (numFrames / (float)mSampleRate)) * 0.05f;

  static int logCounter = 0;
  static float maxPeak = 0.0f;

  float currentPeak = 0.0f;
  for (int i = 0; i < numFrames * numChannels; ++i) {
    float a = std::abs(output[i]);
    if (a > currentPeak)
      currentPeak = a;
  }
  if (currentPeak > maxPeak)
    maxPeak = currentPeak;

  if (++logCounter > 187) { // ~Once per second at 48k/256
    logCounter = 0;
    int activeTracks = 0;
    for (const auto &tr : mTracks)
      if (tr.isActive)
        activeTracks++;

    // v3.0: Periodic voice count reconciliation (self-heal any drift)
    int actualVoiceCount = 0;
    for (const auto &tr : mTracks) {
      for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
        if (tr.mActiveNotes[v].active)
          actualVoiceCount++;
      }
    }
    if (mGlobalVoiceCount != actualVoiceCount) {
      LOGD("VoiceCount DRIFT: reported=%d actual=%d — correcting",
           mGlobalVoiceCount.load(), actualVoiceCount);
      mGlobalVoiceCount = actualVoiceCount;
    }

    LOGD("AudioEngineCore Stats: ActiveTracks=%d, Voices=%d, MasterVol=%.2f, "
         "SampleRate=%.1f, "
         "BlockPeak=%.4f, MaxPeak=%.4f",
         activeTracks, mGlobalVoiceCount.load(), mMasterVolume.load(std::memory_order_relaxed), (float)mSampleRate, currentPeak, maxPeak);

    // Extra debug: track states
    for (int t = 0; t < 8; ++t) {
      if (mTracks[t].isActive || mTracks[t].smoothedVolume > 0.01f) {
        LOGD("  T%d: Active=%s, SmVol=%.2f, Engine=%d, GainRed=%.2f", t,
             mTracks[t].isActive ? "YES" : "NO", mTracks[t].smoothedVolume,
             mTracks[t].engineType, mTracks[t].gainReduction);
      }
    }
    maxPeak = 0.0f; // Reset max peak every second
  }

  // Throttle UI synchronization: Update snapshot only every 4th callback
  // This reduces JNI and Mutex overhead by 75%, helping low-power CPU stability
  int counter = mSnapshotCounter.fetch_add(1, std::memory_order_relaxed);
  if (counter % 4 == 0) {
      updateUISnapshot();
  }
  return;
}

void AudioEngineCore::triggerNote(int trackIndex, int note, int velocity) {
  AudioCommand cmd;
  cmd.type = AudioCommand::NOTE_ON;
  cmd.trackIndex = trackIndex;
  cmd.data1 = note;
  cmd.value = static_cast<float>(velocity);
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::releaseNote(int trackIndex, int note) {
  AudioCommand cmd;
  cmd.type = AudioCommand::NOTE_OFF;
  cmd.trackIndex = trackIndex;
  cmd.data1 = note;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

// ... COPY OF OTHER METHODS ...
void AudioEngineCore::setArpRate(int trackIndex, float rate, int divisionMode) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_ARP_RATE;
  cmd.trackIndex = trackIndex;
  cmd.value = rate;
  cmd.data1 = divisionMode;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setArpStrum(int trackIndex, float strum) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_ARP_STRUM;
  cmd.trackIndex = trackIndex;
  cmd.value = strum;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setStep(int trackIndex, int stepIndex, bool active,
                          const std::vector<int> &notes, float velocity,
                          int ratchet, bool punch, float probability,
                          float gate, bool isSkipped, float subStepOffset,
                          const std::vector<float> &noteOffsets,
                          const std::vector<float> &noteVelocities) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_STEP;
  cmd.trackIndex = trackIndex;
  cmd.data1 = stepIndex;
  cmd.bValue = active;
  cmd.notes = notes;
  cmd.velocity = velocity;
  cmd.ratchet = ratchet;
  cmd.punch = punch;
  cmd.probability = probability;
  cmd.gate = gate;
  cmd.isSkipped = isSkipped;
  cmd.subStepOffset = subStepOffset;
  cmd.noteOffsets = noteOffsets;
  cmd.noteVelocities = noteVelocities;

  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::getFxSends(int trackIndex, float *dest) {
  // v3.1.27: Lock-free atomic access
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    for (int i = 0; i < 17; ++i) {
      dest[i] = mTracks[trackIndex].fxSends[i].load();
    }
  }
}

void AudioEngineCore::getFxMix(int trackIndex, float *dest) {
  // v3.1.27: Lock-free atomic access
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    for (int i = 0; i < 17; ++i) {
      dest[i] = mTracks[trackIndex].fxMix[i].load();
    }
  }
}

std::vector<float> AudioEngineCore::getAllTrackParameters(int trackIndex) {
  std::vector<float> params;
  params.reserve(1024);
  
  if (trackIndex >= 0 && trackIndex < 8) {
      // Use Snapshot Lock (faster than mLock)
      std::unique_lock<std::mutex> lock(mSnapshotLock, std::try_to_lock);
      // Even if lock fails, we return a vector (either zeroed or from snapshot if we got it)
      // Actually, let's just wait for mSnapshotLock since it's extremely fast (just a copy)
      if (!lock.owns_lock()) lock.lock(); 
      
      for (int i = 0; i < 1024; ++i) {
        params.push_back(mUISnapshot.tracks[trackIndex].parameters[i]);
      }
  } else {
      params.resize(1024, 0.0f);
  }
  return params;
}

void AudioEngineCore::setSequencerConfig(int trackIndex, int numPages,
                                     int stepsPerPage) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setConfiguration(numPages, stepsPerPage);
    if (mTracks[trackIndex].engineType == 5 ||
        mTracks[trackIndex].engineType == 6) {
      for (int i = 0; i < 16; ++i) {
        mTracks[trackIndex].drumSequencers[i].setConfiguration(numPages,
                                                               stepsPerPage);
      }
    }
  }
}

void AudioEngineCore::setTempo(float bpm) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TEMPO;
  cmd.value = bpm;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
  for (int i = 0; i < 6; ++i) {
    mLfos[i].setBpm(bpm);
  }
}

void AudioEngineCore::setPlaying(bool playing) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mIsPlaying = playing;
  if (!playing) {
    mSampleCount = 0;
    mGlobalStepIndex = 0;
    // Panic Logic (Unlocked copy) to stop CPU usage immediately
    for (auto &track : mTracks) {
      track.mInternalStepIndex = 0;
      track.mStepCountdown = 0.0;
      track.mPendingNotes.clear();

      track.sequencer.jumpToStep(0);
      if (track.engineType == 5 || track.engineType == 6 ||
          (track.engineType == 2 && track.samplerEngine.getPlayMode() >= 3)) {
        for (int d = 0; d < 16; ++d)
          track.drumSequencers[d].jumpToStep(0);
      }
      track.isActive = false;
      track.mPendingNotes.clear();

      // Panic: Force silence
      track.subtractiveEngine.allNotesOff();
      track.fmEngine.allNotesOff();
      track.samplerEngine.allNotesOff();
      track.fmDrumEngine.allNotesOff();
      track.granularEngine.allNotesOff();
      track.wavetableEngine.allNotesOff();
      track.analogDrumEngine.allNotesOff();
      track.soundFontEngine.allNotesOff();
    }
  } else {
    // FIX: reset startup frames so we don't skip the first step on play
    mStartupFrames = 0;

    for (auto &track : mTracks) {
      track.mInternalStepIndex = 0;
      track.mStepCountdown = 0.0;
      track.mPendingNotes.clear();
      track.isActive = true; // Activate tracks so sequencer advances

      // FIX: Reset Sequencers to start from the beginning
      track.sequencer.reset();
      // Reset Drum Sequencers too
      if (track.engineType == 5 || track.engineType == 6 ||
          (track.engineType == 2 && track.samplerEngine.getPlayMode() >= 3)) {
        for (int d = 0; d < 16; ++d)
          track.drumSequencers[d].reset(); // Ensure drum sequencers reset too
      }
    }
    // Clear Global FX Buffers on Start to prevent noise burst
    mDelayFx.clear();
    mLpLfoL.setDepth(0.0f);
    mLpLfoR.setDepth(0.0f);
    mHpLfoL.setDepth(0.0f);
    mHpLfoR.setDepth(0.0f);
    mLpLfoL.setCutoff(1.0f);
    mLpLfoR.setCutoff(1.0f);
    mHpLfoL.setCutoff(0.0f);
    mHpLfoR.setCutoff(0.0f);
    mReverbFx.clear();
    mTapeWobbleFx.clear();
    mPhaserFxL.clear();
    mPhaserFxR.clear();
    mChorusFxL.clear();
    mChorusFxR.clear();
    mFlangerFxL.clear();
    mFlangerFxR.clear();
    for (int i = 0; i < 3; ++i) {
      mFilterPedalL[i].clear();
      mFilterPedalR[i].clear();
    }
    // Prevent Silence for Filter Pedals (Default Mix to 1.0 if not touched)
    mFxMixLevels[12] = 1.0f; // Filter 1
    mFxMixLevels[15] = 1.0f; // Filter 2
    mFxMixLevels[16] = 1.0f; // Filter 3

    mHpLfoL.reset(mSampleRate);
    mHpLfoR.reset(mSampleRate);
    mLpLfoL.reset(mSampleRate);
    mLpLfoR.reset(mSampleRate);
  }
}

void AudioEngineCore::setClockMultiplier(int trackIndex, float multiplier) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].mClockMultiplier = multiplier;
  }
}

void AudioEngineCore::setSwing(float swing) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_SWING;
  cmd.value = swing;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setTrackHumanize(int trackIndex, float amount) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_HUMANIZE;
  cmd.trackIndex = trackIndex;
  cmd.value = amount;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setPatternLength(int trackIndex, int length) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_PATTERN_LENGTH;
  cmd.trackIndex = trackIndex;
  cmd.data1 = length;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setPlaybackDirection(int trackIndex, int direction) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setPlaybackDirection(direction);
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].setPlaybackDirection(direction);
    }
  }
}

void AudioEngineCore::setIsRandomOrder(int trackIndex, bool isRandom) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setIsRandomOrder(isRandom);
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].setIsRandomOrder(isRandom);
    }
  }
}

void AudioEngineCore::setIsJumpMode(int trackIndex, bool isJump) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].sequencer.setIsJumpMode(isJump);
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].setIsJumpMode(isJump);
    }
  }
}

void AudioEngineCore::setSelectedFmDrumInstrument(int trackIndex, int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].selectedFmDrumInstrument = drumIndex % 16;
  }
}

void AudioEngineCore::setParameterLock(int trackIndex, int stepIndex,
                                   int parameterId, float value) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setParameterLock(stepIndex, parameterId,
                                                   value);
  }
}

void AudioEngineCore::clearParameterLocks(int trackIndex, int stepIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.clearParameterLocks(stepIndex);
  }
}

void AudioEngineCore::setRouting(int destTrack, int sourceTrack, int source,
                             int dest, float amount, int destParamId) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  RoutingEntry entry = {sourceTrack, static_cast<ModSource>(source),
                        static_cast<ModDestination>(dest), destParamId, amount};
  mRoutingMatrix.addConnection(destTrack, entry);
}

void AudioEngineCore::applyModulations() {
  for (int t = 0; t < (int)mTracks.size(); ++t) {
    auto &track = mTracks[t];
    // Disabled tracks produce no audio, so spending CPU on their modulation
    // routing is pure waste.
    if (track.isDisabled)
      continue;

    // v3.0: Compact modulation tracking (replaces bitset<2500> scan)
    int currentModIds[32];
    int currentModCount = 0;

    const RoutingEntry *mods;
    int count;
    mRoutingMatrix.getFastConnections(t, &mods, &count);

    for (int i = 0; i < count; ++i) {
      const auto &mod = mods[i];
      float srcValue = 0.0f;

      // Get Source Value
      switch (mod.source) {
      case ModSource::LFO1:
        srcValue = mLfos[0].getCurrentValue();
        break;
      case ModSource::LFO2:
        srcValue = mLfos[1].getCurrentValue();
        break;
      case ModSource::LFO3:
        srcValue = mLfos[2].getCurrentValue();
        break;
      case ModSource::LFO4:
        srcValue = mLfos[3].getCurrentValue();
        break;
      case ModSource::LFO5:
        srcValue = mLfos[4].getCurrentValue();
        break;
      case ModSource::LFO6:
        srcValue = mLfos[5].getCurrentValue();
        break;
      case ModSource::Macro1:
        srcValue = mMacros[0].value;
        break;
      case ModSource::Macro2:
        srcValue = mMacros[1].value;
        break;
      case ModSource::Macro3:
        srcValue = mMacros[2].value;
        break;
      case ModSource::Macro4:
        srcValue = mMacros[3].value;
        break;
      case ModSource::Macro5:
        srcValue = mMacros[4].value;
        break;
      case ModSource::Macro6:
        srcValue = mMacros[5].value;
        break;
      case ModSource::Macro7:
        srcValue = mMacros[6].value;
        break;
      case ModSource::Macro8:
        srcValue = mMacros[7].value;
        break;
      default:
        break;
      }

      if (!std::isfinite(srcValue))
        srcValue = 0.0f;

      // Apply to Destination
      int destId = -1;
      if (mod.destination == ModDestination::Parameter &&
          mod.destParamId >= 0 && mod.destParamId < 2500) {
        destId = mod.destParamId;
      } else if (mod.destination == ModDestination::FilterCutoff) {
        destId = 112;
      }

      if (destId >= 0) {
        float baseVal = track.parameters[destId];
        float effectiveVal = baseVal + (srcValue * mod.amount);
        // v3.0: NaN guard
        if (!std::isfinite(effectiveVal))
          effectiveVal = baseVal;
        track.appliedParameters[destId] = effectiveVal;

        if (destId >= 290 && destId <= 292 && track.engineType == 0) {
          // Macro destinations fan out to the three oscillator parameters.
          // Unlike a UI change, modulation must not be written into the base
          // parameters, otherwise it would stick after the modulation ends.
          static const int kMacroTargets[3][3] = {
              {104, 105, 155}, {180, 181, 182}, {170, 171, 172}};
          const int *dst = kMacroTargets[destId - 290];
          for (int i = 0; i < 3; ++i) {
            track.appliedParameters[dst[i]] = effectiveVal;
            updateEngineParameter(t, dst[i], effectiveVal);
          }
        } else {
          updateEngineParameter(t, destId, effectiveVal);
        }

        // Track this modulated ID (avoid duplicates)
        if (currentModCount < 32) {
          bool found = false;
          for (int k = 0; k < currentModCount; ++k) {
            if (currentModIds[k] == destId) {
              found = true;
              break;
            }
          }
          if (!found) {
            currentModIds[currentModCount++] = destId;
          }
        }
      }
    }

    // Reset parameters that were modulated last block but not this block
    for (int p = 0; p < track.mModulatedCount; ++p) {
      int prevId = track.mModulatedIds[p];
      bool stillModulated = false;
      for (int k = 0; k < currentModCount; ++k) {
        if (currentModIds[k] == prevId) {
          stillModulated = true;
          break;
        }
      }
      if (!stillModulated) {
        float baseVal = track.parameters[prevId];
        track.appliedParameters[prevId] = baseVal;
        updateEngineParameter(t, prevId, baseVal);
      }
    }

    // Store current modulated set for next block
    track.mModulatedCount = currentModCount;
    for (int k = 0; k < currentModCount; ++k) {
      track.mModulatedIds[k] = currentModIds[k];
    }
  }
}

void AudioEngineCore::setIsRecording(bool isRecording) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (!isRecording && mIsRecording.load()) {
    // Flush all in-flight recording notes to prevent orphaned entries
    // that would cause duplicate writes on the next recording pass.
    for (auto &track : mTracks) {
      track.mRecordingNotes.clear();
    }
  }
  mIsRecording = isRecording;
}

void AudioEngineCore::jumpToStep(int stepIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mGlobalStepIndex = stepIndex % mPatternLength.load(std::memory_order_relaxed);

  for (auto &track : mTracks) {
    track.mStepCountdown = 0.0; // Force immediate trigger on next block
    track.sequencer.jumpToStep(mGlobalStepIndex);
    for (int i = 0; i < 16; ++i) {
      track.drumSequencers[i].jumpToStep(mGlobalStepIndex);
    }
  }
  mSampleCount.store(mSamplesPerStep.load(std::memory_order_relaxed), std::memory_order_relaxed);
}

int AudioEngineCore::getCurrentStep(int trackIndex, int drumIndex) {
  if (trackIndex >= 0 && trackIndex < 8) {
    if (drumIndex >= 0 && drumIndex < 16)
      return mTrackCurrentDrumStep[trackIndex][drumIndex].load();
    return mTrackCurrentStep[trackIndex].load();
  }
  return 0;
}

void AudioEngineCore::setArpConfig(int trackIndex, int mode, int octaves,
                               int inversion, bool isLatched, bool isMutated,
                               const std::vector<std::vector<bool>> &rhythms,
                               const std::vector<int> &sequence,
                               const std::vector<float> &gateLengths,
                               float probability, float weird) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    ArpMode newMode = static_cast<ArpMode>(mode);
    Track &track = mTracks[trackIndex];

    track.arpeggiator.setProbability(probability);
    track.arpeggiator.setWeird(weird);

    bool wasLatched = track.arpeggiator.isLatched();
    if (wasLatched && !isLatched && track.mPhysicallyHeldNoteCount == 0) {
      track.arpeggiator.clear();
      for (int i = 0; i < AudioEngineCore::Track::MAX_POLYPHONY; ++i) {
        if (track.mActiveNotes[i].active) {
          track.subtractiveEngine.releaseNote(track.mActiveNotes[i].note);
          track.fmEngine.releaseNote(track.mActiveNotes[i].note);
          track.samplerEngine.releaseNote(track.mActiveNotes[i].note);
          track.fmDrumEngine.releaseNote(track.mActiveNotes[i].note);
          track.granularEngine.releaseNote(track.mActiveNotes[i].note);
          track.wavetableEngine.releaseNote(track.mActiveNotes[i].note);
          track.mActiveNotes[i].active = false;
        }
      }
    }

    if (newMode == ArpMode::OFF) {
      track.arpeggiator.clear();
    }
    track.arpeggiator.setMode(newMode);
    track.arpeggiator.setOctaves(octaves);
    track.arpeggiator.setInversion(inversion);
    track.arpeggiator.setLatched(isLatched);
    track.arpeggiator.setIsMutated(isMutated);
    track.arpeggiator.setRhythm(rhythms);
    track.arpeggiator.setRandomSequence(sequence);
    track.arpeggiator.setGateLengths(gateLengths);
  }
}

void AudioEngineCore::copyArpeggiator(int srcTrack, int destTrack, bool live) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (srcTrack < 0 || srcTrack >= (int)mTracks.size() ||
      destTrack < 0 || destTrack >= (int)mTracks.size() ||
      srcTrack == destTrack) {
    return;
  }

  Track &src = mTracks[srcTrack];
  Track &dest = mTracks[destTrack];

  // Stop anything already sounding on the destination so a live clone does not
  // stack on top of stale voices from the old setup.
  for (int i = 0; i < Track::MAX_POLYPHONY; ++i) {
    if (dest.mActiveNotes[i].active) {
      const int n = dest.mActiveNotes[i].note;
      dest.subtractiveEngine.releaseNote(n);
      dest.fmEngine.releaseNote(n);
      dest.samplerEngine.releaseNote(n);
      dest.fmDrumEngine.releaseNote(n);
      dest.granularEngine.releaseNote(n);
      dest.wavetableEngine.releaseNote(n);
      dest.analogDrumEngine.releaseNote(n);
      dest.soundFontEngine.noteOff(n);
      dest.mActiveNotes[i].active = false;
    }
  }

  dest.arpeggiator.copyFrom(src.arpeggiator, live);
  dest.mArpRate = src.mArpRate;
  dest.mArpDivisionMode = src.mArpDivisionMode;
  dest.mArpTriplet = src.mArpTriplet;
  dest.mClockMultiplier = src.mClockMultiplier;
  dest.mArpCountdown = 0.0;
  dest.mPhysicallyHeldNoteCount = 0;

  if (live) {
    // Releases are propagated from the source, so the destination starts with
    // a clean physical-hold count and its own gestures behave normally.
    dest.isActive = true;
    mArpCloneSource[destTrack] = srcTrack;
  } else {
    mArpCloneSource[destTrack] = -1;
  }
}

void AudioEngineCore::setChordProgConfig(int trackIndex, bool enabled, int mood,
                                     int complexity) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].arpeggiator.setChordProgConfig(enabled, mood,
                                                       complexity);
  }
}
void AudioEngineCore::setScaleConfig(int rootNote,
                                 const std::vector<int> &intervals) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  for (auto &track : mTracks) {
    track.arpeggiator.setScaleConfig(rootNote, intervals);
  }
}

void AudioEngineCore::setSidechainConfig(int trackIndex, int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mSidechainSourceTrack = trackIndex;
  mSidechainSourceDrumIdx = drumIndex;
}

void AudioEngineCore::getGranularPlayheads(int trackIndex,
                                       GranularEngine::PlayheadInfo *out,
                                       int maxCount) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    // ENUM ID: SUB=0, FM=1, SAMP=2, GRAN=3, WAVE=4
    if (mTracks[trackIndex].engineType == 3) { // GRANULAR
      mTracks[trackIndex].granularEngine.getPlayheads(out, maxCount);
    } else if (mTracks[trackIndex].engineType == 2) { // SAMPLER
      // Reuse PlayheadInfo struct but cast or map if they aren't
      // bit-identical In our case they are both {float, float}
      mTracks[trackIndex].samplerEngine.getPlayheads(
          (SamplerEngine::PlayheadInfo *)out, maxCount);
    }
  }
}

void AudioEngineCore::normalizeSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].samplerEngine.normalize();
  }
}

void AudioEngineCore::saveSample(int trackIndex, const std::string &path) {
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  auto &track = mTracks[trackIndex];
  // Currently only support SamplerEngine saving
  if (track.engineType == 2) { // Sampler
    std::vector<float> data = track.samplerEngine.getSampleData();
    std::vector<float> slices = track.samplerEngine.getSlicePoints();
    WavFileUtils::writeWav(path, data, 48000, 1, slices);
  } else if (track.engineType == 3) { // Granular (3)
    std::vector<float> data = track.granularEngine.getSampleData();
    std::vector<float> slices;
    WavFileUtils::writeWav(path, data, 48000, 1, slices);
  }
}

void AudioEngineCore::loadSample(int trackIndex, const std::string &path) {
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  auto &track = mTracks[trackIndex];
  // Load using WavFileUtils
  std::vector<float> data;
  int sampleRate;
  int channels;
  std::vector<float> slicePoints;
  if (!WavFileUtils::loadWav(path, data, sampleRate, channels, slicePoints)) {
    return;
  }

  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (track.engineType == 2) { // Sampler
    track.samplerEngine.loadSample(data);
    if (!slicePoints.empty()) {
      track.samplerEngine.setSlicePoints(slicePoints);
    }
  } else if (track.engineType == 3) { // Granular
    track.granularEngine.setSource(data);
  }
  track.lastSamplePath = path;
}

size_t AudioEngineCore::getSampleLength(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    if (mTracks[trackIndex].engineType == 2) // Sampler
      return mTracks[trackIndex].samplerEngine.getSampleLength();
    else if (mTracks[trackIndex].engineType == 3) // Granular
      return mTracks[trackIndex].granularEngine.getSampleLength();
  }
  return 0;
}

void AudioEngineCore::setAppDataDir(const std::string &dir) { mAppDataDir = dir; }

void AudioEngineCore::saveAppState() {
  if (mAppDataDir.empty())
    return;
  std::string path = mAppDataDir + "/app_state.txt";
  // Write to temp file first to prevent partial writes
  std::string tempPath = path + ".tmp";
  std::ofstream file(tempPath);
  if (file.is_open()) {
    file << "LOOM_STATE_V1\n"; // Magic Header
    for (int i = 0; i < (int)mTracks.size(); ++i) {
      if (!mTracks[i].lastSamplePath.empty()) {
        file << i << ":" << mTracks[i].lastSamplePath << "\n";
      }
    }
    file.close();
    // Atomic rename (mostly atomic on POSIX)
    rename(tempPath.c_str(), path.c_str());
  }
}

void AudioEngineCore::loadAppState() {
  if (mAppDataDir.empty())
    return;
  std::string path = mAppDataDir + "/app_state.txt";
  std::ifstream file(path);
  if (file.is_open()) {
    std::string line;
    // Check Header
    if (std::getline(file, line)) {
      if (line != "LOOM_STATE_V1") {
        LOGD("Invalid State Header. resetting state.");
        file.close();
        return; // Discard invalid state
      }
    }

    while (std::getline(file, line)) {
      try {
        size_t pos = line.find(':');
        if (pos != std::string::npos) {
          int trackIndex = std::stoi(line.substr(0, pos));
          std::string samplePath = line.substr(pos + 1);
          if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
            // Verify file exists before loading to prevent crash loops
            FILE *f = fopen(samplePath.c_str(), "rb");
            if (f) {
              fclose(f);
              loadSample(trackIndex, samplePath);
            } else {
              LOGD("Skipping missing file in state: %s", samplePath.c_str());
            }
          }
        }
      } catch (...) {
        LOGD("Error parsing state line: %s", line.c_str());
      }
    }
    file.close();
  }
}

std::string AudioEngineCore::getLastSamplePath(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    return mTracks[trackIndex].lastSamplePath;
  }
  return "";
}

void AudioEngineCore::trimSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    if (mTracks[trackIndex].engineType == 2) {
      mTracks[trackIndex].samplerEngine.trim();
      // Reset markers to full range after destructive trim
      mTracks[trackIndex].parameters[330] = 0.0f;
      mTracks[trackIndex].parameters[331] = 1.0f;
    } else if (mTracks[trackIndex].engineType == 3) { // Granular
      float start = mTracks[trackIndex].parameters[330];
      float end = mTracks[trackIndex].parameters[331];
      mTracks[trackIndex].granularEngine.trim(start, end);
      mTracks[trackIndex].parameters[330] = 0.0f;
      mTracks[trackIndex].parameters[331] = 1.0f;
    }
  }
}

std::vector<float> AudioEngineCore::getRecordedSampleData(int trackIndex,
                                                      float targetSampleRate) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return {};

  auto &track = mTracks[trackIndex];
  const std::vector<float> *source = nullptr;

  if (track.engineType == 2) {
    source = &track.samplerEngine.getSampleData();
  } else if (track.engineType == 3) {
    source = &track.granularEngine.getSampleData();
  }

  if (!source || source->empty())
    return {};

  float sourceRate = static_cast<float>(mSampleRate);
  if (sourceRate <= 0)
    sourceRate = 48000.0f;

  if (std::abs(sourceRate - targetSampleRate) < 1.0f) {
    return *source; // No resampling needed
  }

  double ratio = static_cast<double>(sourceRate) / targetSampleRate;
  size_t targetSize = static_cast<size_t>(source->size() / ratio);
  std::vector<float> result(targetSize);

  for (size_t i = 0; i < targetSize; ++i) {
    double pos = i * ratio;
    int idx = static_cast<int>(pos);
    float frac = static_cast<float>(pos - idx);

    // Get 4 points for cubic interpolation
    float y0 = source->at(std::max(0, idx - 1));
    float y1 = source->at(idx);
    float y2 = source->at(std::min((int)source->size() - 1, idx + 1));
    float y3 = source->at(std::min((int)source->size() - 1, idx + 2));

    result[i] = cubicInterpolation(y0, y1, y2, y3, frac);
  }

  return result;
}

void AudioEngineCore::startRecordingSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  
  // v3.2.6: Debounce recording starts to prevent "double-click" artifacts on touch screens
  auto now = std::chrono::steady_clock::now();
  auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - mLastRecordStartTime).count();
  if (elapsed < 200) { // 200ms debounce
      return;
  }
  mLastRecordStartTime = now;

  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mIsRecordingSample = true;
    mIsRecordingLocked = false; // Reset lock on new recording start
    mRecordingTrackIndex = trackIndex;
    if (mTracks[trackIndex].engineType == 2)
      mTracks[trackIndex].samplerEngine.clearBuffer();
    else if (mTracks[trackIndex].engineType == 3) // Granular
      mTracks[trackIndex].granularEngine.clearSource();
  }
}

void AudioEngineCore::stopRecordingSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (!mIsRecordingLocked) {
    // Commit recorded samples to active buffer (lock-free swap)
    if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
      if (mTracks[trackIndex].engineType == 2) { // Sampler
        mTracks[trackIndex].samplerEngine.commitRecording();
        mTracks[trackIndex].samplerEngine.normalize();
      } else if (mTracks[trackIndex].engineType == 3) { // Granular
        mTracks[trackIndex].granularEngine.commitRecording();
        mTracks[trackIndex].granularEngine.normalize();
      }
    }
    mIsRecordingSample = false;
    mRecordingTrackIndex = -1;
  }
}

void AudioEngineCore::setRecordingLocked(bool locked) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mIsRecordingLocked = locked;
}

std::vector<float> AudioEngineCore::getSamplerWaveform(int trackIndex,
                                                   int numPoints) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    if (mTracks[trackIndex].engineType == 2)
      return mTracks[trackIndex].samplerEngine.getAmplitudeWaveform(numPoints);
    else if (mTracks[trackIndex].engineType == 3)
      return mTracks[trackIndex].granularEngine.getAmplitudeWaveform(numPoints);
  }
  return {};
}

std::vector<int> AudioEngineCore::getStepNotes(int trackIndex, int stepIndex,
                                              int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex < 0 || trackIndex >= (int)mTracks.size())
    return {};

  const auto &steps = (drumIndex >= 0 && drumIndex < 16)
                          ? mTracks[trackIndex].drumSequencers[drumIndex].getSteps()
                          : mTracks[trackIndex].sequencer.getSteps();

  if (stepIndex < 0 || stepIndex >= (int)steps.size())
    return {};

  const Step &s = steps[stepIndex];
  std::vector<int> notes;
  for (const auto &ni : s.notes)
    notes.push_back(ni.note);
  return notes;
}

float AudioEngineCore::getStepVelocity(int trackIndex, int stepIndex,
                                       int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex < 0 || trackIndex >= (int)mTracks.size())
    return 0.0f;

  const auto &steps = (drumIndex >= 0 && drumIndex < 16)
                          ? mTracks[trackIndex].drumSequencers[drumIndex].getSteps()
                          : mTracks[trackIndex].sequencer.getSteps();

  if (stepIndex < 0 || stepIndex >= (int)steps.size())
    return 0.0f;

  const Step &s = steps[stepIndex];
  if (s.notes.empty())
    return 0.0f;
  return s.notes[0].velocity;
}

float AudioEngineCore::getStepSubStep(int trackIndex, int stepIndex,
                                      int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex < 0 || trackIndex >= (int)mTracks.size())
    return 0.0f;

  const auto &steps = (drumIndex >= 0 && drumIndex < 16)
                          ? mTracks[trackIndex].drumSequencers[drumIndex].getSteps()
                          : mTracks[trackIndex].sequencer.getSteps();

  if (stepIndex < 0 || stepIndex >= (int)steps.size())
    return 0.0f;

  const Step &s = steps[stepIndex];
  if (s.notes.empty())
    return 0.0f;
  return s.notes[0].subStepOffset;
}

bool AudioEngineCore::getStepActive(int trackIndex, int stepIndex, int drumIndex) {
  if (trackIndex < 0 || trackIndex >= 8 || stepIndex < 0 || stepIndex >= 64) {
    return false;
  }

  std::unique_lock<std::mutex> lock(mSnapshotLock, std::try_to_lock);
  if (!lock.owns_lock()) lock.lock();

  const auto &snap = mUISnapshot.tracks[trackIndex];
  if (drumIndex >= 0 && drumIndex < 16) {
    return snap.activeDrumSteps[drumIndex].test(stepIndex);
  }
  return snap.activeSteps.test(stepIndex);
}

void AudioEngineCore::resetSampler(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].samplerEngine.clearBuffer();
  }
}

std::vector<float> AudioEngineCore::getSamplerSlicePoints(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    if (mTracks[trackIndex].engineType == 2)
      return mTracks[trackIndex].samplerEngine.getSlicePoints();
  }
  return {};
}

void AudioEngineCore::setSoundFontMapping(int trackIndex, int knobIndex,
                                      int paramId) {
  if (trackIndex >= 0 && trackIndex < 8) {
    mTracks[trackIndex].soundFontEngine.setMapping(knobIndex, paramId);
  }
}

void AudioEngineCore::clearSequencer(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.clear();
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].clear();
    }
  }
}

void AudioEngineCore::setMasterVolume(float volume) {
  mMasterVolume.store(volume * 1.5f, std::memory_order_relaxed);
}

void AudioEngineCore::panic() {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  for (auto &track : mTracks) {
    track.mSilenceFrames = 0;
    // Release all notes in the engine
    track.subtractiveEngine.allNotesOff();
    track.fmEngine.allNotesOff();
    track.fmDrumEngine.allNotesOff();
    track.analogDrumEngine.allNotesOff();
    track.wavetableEngine.allNotesOff();
    track.samplerEngine.allNotesOff();
    track.granularEngine.allNotesOff();
    track.soundFontEngine.allNotesOff();

    track.subtractiveEngine.clearAllVoiceMod();
    track.fmEngine.clearAllVoiceMod();
    track.wavetableEngine.clearAllVoiceMod();
    track.samplerEngine.clearAllVoiceMod();
    std::fill(std::begin(track.voiceModRef), std::end(track.voiceModRef), 0);

    for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
      track.mActiveNotes[v].active = false;
    }
  }
}

void AudioEngineCore::updateSampleRate(float sampleRate) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mSampleRate = sampleRate;
  for (int i = 0; i < (int)mTracks.size(); ++i) {
    auto &track = mTracks[i];
    track.subtractiveEngine.setSampleRate(sampleRate);
    track.fmEngine.setSampleRate(sampleRate);
    track.fmDrumEngine.setSampleRate(sampleRate);
    track.analogDrumEngine.setSampleRate(sampleRate);
    track.wavetableEngine.setSampleRate(sampleRate);
    track.audioInEngine.setSampleRate(sampleRate);
    track.soundFontEngine.setSampleRate(sampleRate);
    
    // Ensure SoundFont has a default volume if it's currently selected
    // Parameters[0] is often used for Level/Vol across engines.
    if (track.engineType == 9 && track.parameters[0] < 0.01f) {
        track.parameters[0] = 0.7f;
        track.appliedParameters[0] = 0.7f;
        track.soundFontEngine.setParameter(0, 0.7f);
    }
  }
}

uint64_t AudioEngineCore::getActiveNoteMask(int trackIndex) {
  if (trackIndex < 0 || trackIndex >= 8)
    return 0;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  uint64_t mask = 0;
  auto &track = mTracks[trackIndex];
  for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
    if (track.mActiveNotes[v].active) {
      int note = track.mActiveNotes[v].note;
      // We cover 64 notes starting from 48 (C3 is 60, so this starts at C2)
      if (note >= 48 && note < 112) {
        mask |= (1ULL << (note - 48));
      }
    }
  }
  return mask;
}

// ... COPY OF OTHER METHODS ...
// (Omitting small getters for brevity, but they follow the pattern)
// setGenericLfoParam, setMacroValue, setFxChain etc.

void AudioEngineCore::setGenericLfoParam(int lfoIndex, int paramId, float value) {
  if (lfoIndex < 0 || lfoIndex >= 6)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  switch (paramId) {
  case 0:
    // Cubic scaling: 0.01Hz to 30Hz
    // Range = 30 - 0.01 = 29.99
    // Val = 0.01 + (v^3 * 29.99)
    mLfos[lfoIndex].setFrequency(0.01f + (value * value * value) * 29.99f);
    mLfos[lfoIndex].setUiRate(value);
    break;
  case 1:
    mLfos[lfoIndex].setDepth(value);
    break;
  case 2:
    mLfos[lfoIndex].setShape((int)value);
    break;
  case 3:
    mLfos[lfoIndex].setSync(value > 0.5f);
    break;
  }
}

void AudioEngineCore::setMacroValue(int macroIndex, float value) {
  if (macroIndex < 0 || macroIndex >= 8)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mMacros[macroIndex].value = value;
}

void AudioEngineCore::setMacroSource(int macroIndex, int sourceType,
                                 int sourceIndex, int sourceTrackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (macroIndex >= 0 && macroIndex < 8) {
    mMacros[macroIndex].sourceType = sourceType;
    mMacros[macroIndex].sourceIndex = sourceIndex;
    mMacros[macroIndex].sourceTrackIndex = sourceTrackIndex;
  }
}

void AudioEngineCore::setFxChain(int sourceFx, int destFx) {
  if (sourceFx < 0 || sourceFx >= 17)
    return;
  if (destFx < -1 || destFx >= 17)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mFxChainDest[sourceFx] = destFx;
}

void AudioEngineCore::setFxEnabled(int fxIndex, bool enabled) {
  if (fxIndex < 0 || fxIndex >= 18)
    return;
  const uint32_t bit = 1u << fxIndex;
  if (enabled) {
    mFxEnabledMask.fetch_or(bit, std::memory_order_relaxed);
  } else {
    mFxEnabledMask.fetch_and(~bit, std::memory_order_relaxed);
  }
}

bool AudioEngineCore::isFxEnabled(int fxIndex) const {
  if (fxIndex < 0 || fxIndex >= 18)
    return false;
  return (mFxEnabledMask.load(std::memory_order_relaxed) & (1u << fxIndex)) != 0;
}

void AudioEngineCore::setPerformanceProfile(int profile) {
  if (profile < 0)
    profile = 0;
  if (profile > 2)
    profile = 2;

  // 0 = High: full polyphony, per-block modulation, FX always processed.
  // 1 = Balanced: 8 voices/track, idle FX gating.
  // 2 = Low: 4 voices/track, modulation every other block, idle FX gating.
  int cap = 16;
  if (profile == 1)
    cap = 8;
  else if (profile >= 2)
    cap = 4;
  mMaxVoicesPerTrack.store(cap, std::memory_order_relaxed);
  mModDecimation = (profile >= 2) ? 2 : 1;
  mModBlockCounter = 0;
  mIdleGateEnabled = (profile >= 1);
  mIdleBlocks = 0;
}


void AudioEngineCore::setFilterMode(int trackIndex, int mode) {
  if (trackIndex >= 0 && trackIndex < 8) {
    if (mTracks[trackIndex].engineType == 0) { // Subtractive
      mTracks[trackIndex].subtractiveEngine.setFilterMode(mode);
    }
  }
}

void AudioEngineCore::setArpTriplet(int trackIndex, bool isTriplet) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].mArpTriplet = isTriplet;
  }
}

float AudioEngineCore::getCpuLoad() { return mCpuLoad.load(); }

void AudioEngineCore::getTrackLevels(float *outLevels) const {
  for (int t = 0; t < 8; ++t) {
    if (t < (int)mTracks.size()) {
      outLevels[t] = mTracks[t].follower.getLevel();
    } else {
      outLevels[t] = 0.0f;
    }
  }
}


void AudioEngineCore::enqueuePatternSaveEvent(int trackIndex, int slotIndex) {
  std::lock_guard<std::mutex> lock(mEventLock);
  EngineEvent ev;
  ev.type = EngineEvent::PATTERN_SAVE;
  ev.trackIndex = trackIndex;
  ev.data = slotIndex;
  mEventQueue.push_back(ev);
}

int AudioEngineCore::fetchEngineEvents(int *outBuffer, int maxEvents) {
  std::unique_lock<std::mutex> lock(mEventLock, std::try_to_lock);
  if (!lock.owns_lock()) return 0;
  int count = 0;
  while (!mEventQueue.empty() && count < maxEvents) {
    auto ev = mEventQueue.front();
    mEventQueue.erase(mEventQueue.begin());
    outBuffer[count * 3 + 0] = (int)ev.type;
    outBuffer[count * 3 + 1] = ev.trackIndex;
    outBuffer[count * 3 + 2] = ev.data;
    count++;
  }
  return count;
}

void AudioEngineCore::enqueueMidiEvent(int type, int channel, int data1,
                                   int data2, int trackIndex) {
  std::lock_guard<std::mutex> lock(mMidiLock);
  MidiMessage msg;
  msg.type = type;
  msg.channel = channel;
  msg.data1 = data1;
  msg.data2 = data2;
  msg.trackIndex = trackIndex;
  mMidiQueue.push_back(msg);
}

void AudioEngineCore::restorePresets() {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  for (auto &track : mTracks) {
    track.volume = 0.8f;
    track.subtractiveEngine.resetToDefaults();
    track.fmEngine.resetToDefaults();
    track.fmDrumEngine.resetToDefaults();
    track.analogDrumEngine.resetToDefaults();
    track.samplerEngine.resetToDefaults();
    track.granularEngine.resetToDefaults();
    track.wavetableEngine.resetToDefaults();

    // CRITICAL: Also clear the parameter buffers so UI and Engine stay in
    // sync We set meaningful defaults so the UI knobs show the correct
    // initial values
    std::fill(std::begin(track.parameters), std::end(track.parameters), 0.0f);
    std::fill(std::begin(track.appliedParameters),
              std::end(track.appliedParameters), 0.0f);

    // Common EG Defaults
    track.parameters[100] = 0.01f; // Attack
    track.parameters[101] = 0.1f;  // Decay
    track.parameters[102] = 0.8f;  // Sustain
    track.parameters[103] = 0.5f;  // Release

    // Common Filter Defaults
    track.parameters[112] = 0.5f; // Cutoff
    track.parameters[113] = 0.0f; // Resonance

    // FM Specific Defaults
    track.parameters[150] = 0.0f;  // Algorithm 0
    track.parameters[153] = 1.0f;  // Carrier Mask (Op 1)
    track.parameters[155] = 63.0f; // Active Mask (All 6 Ops)
    track.parameters[157] = 0.5f;  // Brightness (1.0 in engine)

    // Sampler Defaults
    track.parameters[302] = 0.5f; // Speed 1.0x
    track.parameters[320] = 0.0f; // OneShot mode
    track.parameters[340] = 0.0f; // 2 slices

    // Wavetable Defaults
    track.parameters[450] = 0.0f; // Position
    track.parameters[451] = 0.0f; // Morph
  }
}

int AudioEngineCore::fetchMidiEvents(int *outBuffer, int maxEvents) {
  std::lock_guard<std::mutex> lock(mMidiLock);
  int count = 0;
  while (!mMidiQueue.empty() && count < maxEvents) {
    MidiMessage msg = mMidiQueue.front();
    mMidiQueue.erase(mMidiQueue.begin());
    int offset = count * 5;
    outBuffer[offset] = msg.type;
    outBuffer[offset + 1] = msg.channel;
    outBuffer[offset + 2] = msg.data1;
    outBuffer[offset + 3] = msg.data2;
    outBuffer[offset + 4] = msg.trackIndex;
    count++;
  }
  return count;
}

std::vector<Step> AudioEngineCore::getSequencerSteps(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    return mTracks[trackIndex].sequencer.getSteps();
  }
  return {};
}

void AudioEngineCore::loadFmPreset(int trackIndex, int presetId) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    Track &track = mTracks[trackIndex];
    track.fmEngine.loadPreset(presetId);

    // SYNC: Read back parameters from Engine to Track State for UI
    track.parameters[150] = (float)track.fmEngine.getAlgorithm();
    track.parameters[151] = track.fmEngine.getCutoff();
    track.parameters[152] = track.fmEngine.getResonance();
    track.parameters[153] = (float)track.fmEngine.getCarrierMask();
    track.parameters[154] = track.fmEngine.getFeedback();
    track.parameters[155] = (float)track.fmEngine.getActiveMask();
    track.parameters[156] = (float)track.fmEngine.getFilterMode();
    track.parameters[157] =
        track.fmEngine.getBrightness(); // Getter already normalizes (0.0-1.0)
    track.parameters[158] = track.fmEngine.getDetune();
    track.parameters[159] = track.fmEngine.getFeedbackDrive();

    // Amp Envelope
    track.parameters[100] = track.fmEngine.getAttack();
    track.parameters[101] = track.fmEngine.getDecay();
    track.parameters[102] = track.fmEngine.getSustain();
    track.parameters[103] = track.fmEngine.getRelease();

    // Operators
    for (int op = 0; op < 6; ++op) {
      int base = 160 + (op * 6);
      track.parameters[base + 0] = track.fmEngine.getOpLevel(op);
      track.parameters[base + 1] = track.fmEngine.getOpRatio(op);
      track.parameters[base + 2] = track.fmEngine.getOpAttack(op);
      track.parameters[base + 3] = track.fmEngine.getOpDecay(op);
      track.parameters[base + 4] = track.fmEngine.getOpSustain(op);
      track.parameters[base + 5] = track.fmEngine.getOpRelease(op);
    }
  }
}

void AudioEngineCore::setResampling(bool isResampling) {
  mRecordingSource = isResampling ? RESAMPLE : MIC;
}

void AudioEngineCore::setRecordingSource(int source) { mRecordingSource = source; }

void AudioEngineCore::pushSystemAudioSamples(const float *data, int numSamples) {
  for (int i = 0; i < numSamples; ++i) {
    mSystemAudioRingBuffer[mSystemAudioWritePtr % 8192] = data[i];
    mSystemAudioWritePtr++;
  }
}

void AudioEngineCore::pushInputSamples(const float *data, int numFrames,
                                       int numChannels) {
  for (int i = 0; i < numFrames; ++i) {
    float combined = 0.0f;
    if (numChannels == 2) {
      combined = (data[i * 2] + data[i * 2 + 1]) * 0.5f;
    } else {
      combined = data[i];
    }
    mInputRingBuffer[mInputWritePtr & 8191] = combined;
    mInputWritePtr++;
  }
}

void AudioEngineCore::renderStereo(float *outBuffer, int numFrames) {
  // Lock handled by onAudioReady caller
  // Master volume and safety
  float masterVol = mMasterVolume.load(std::memory_order_relaxed);
  if (!std::isfinite(masterVol))
    masterVol = 0.5f;

  // Force Filter FX Mix Levels to 1.0 (Passthrough Fix)
  mFxMixLevels[12] = 1.0f;
  mFxMixLevels[15] = 1.0f;
  mFxMixLevels[16] = 1.0f;

  float sampleRate = static_cast<float>(mSampleRate);

  // Snapshot the FX enable mask once per block so a toggle cannot change
  // behaviour halfway through a block.
  const uint32_t fxEnabledMask =
      mFxEnabledMask.load(std::memory_order_relaxed);
  auto fxOn = [fxEnabledMask](int index) {
    return (fxEnabledMask & (1u << index)) != 0;
  };

  // --- Block Rate Control Updates ---
  for (int l = 0; l < 6; ++l) {
    mLfos[l].process(sampleRate, numFrames);
  }
  for (int m = 0; m < 8; ++m) {
    if (mMacros[m].sourceType == 3) { // LFO
      int lfoIdx = mMacros[m].sourceIndex;
      if (lfoIdx >= 0 && lfoIdx < 6) {
        float val = (mLfos[lfoIdx].getCurrentValue() + 1.0f) * 0.5f;
        mMacros[m].value = std::max(0.0f, std::min(1.0f, val));
      }
    } else if (mMacros[m].sourceType == 4) { // Envelope
      int trackIdx = mMacros[m].sourceTrackIndex;
      if (trackIdx >= 0 && trackIdx < mTracks.size()) {
        float envVal = 0.0f;
        auto &t = mTracks[trackIdx];
        switch (t.engineType) {
        case 0:
          envVal = t.subtractiveEngine.getEnvelopeValue();
          break;
        case 1:
          envVal = t.fmEngine.getEnvelopeValue();
          break;
        case 2: // SAMPLER
          envVal = t.samplerEngine.getEnvelopeValue();
          break;
        case 3: // GRANULAR
          envVal = t.granularEngine.getEnvelopeValue();
          break;
        case 4: // WAVETABLE
          envVal = t.wavetableEngine.getEnvelopeValue();
          break;
        case 5:
          envVal = t.fmDrumEngine.getEnvelopeValue();
          break;
        case 6:
          envVal = t.analogDrumEngine.getEnvelopeValue();
          break;
        case 8: // AUDIO IN
          envVal = t.audioInEngine.getEnvelopeValue();
          break;
        case 9: // SOUNDFONT
          envVal = t.soundFontEngine.getEnvelopeValue();
          break;
        }
        mMacros[m].value = std::max(0.0f, std::min(1.0f, envVal));
      }
    } else if (mMacros[m].sourceType == 5) { // MIDI PADS
      int trackIdx = mMacros[m].sourceTrackIndex;
      if (trackIdx >= 0 && trackIdx < (int)mTracks.size()) {
        mMacros[m].value = mTracks[trackIdx].padModValue;
      }
    }
  }
  if (mModBlockCounter <= 0) {
    applyModulations();
    mModBlockCounter = std::max(1, mModDecimation);
  }
  mModBlockCounter--;
  // ----------------------------------

  bool anySolo = false;
  for (const auto &t : mTracks) {
    if (t.isSoloed) {
      anySolo = true;
      break;
    }
  }

  DenormalDisabler denormalDisabler;

  // Block-Based FX Processing Setup
  int safeNumFrames = std::min((int)numFrames, 1024);

  // Buffers for dry mix and sidechain
  std::fill(mMixedSampleL.begin(), mMixedSampleL.begin() + safeNumFrames, 0.0f);
  std::fill(mMixedSampleR.begin(), mMixedSampleR.begin() + safeNumFrames, 0.0f);
  std::fill(mSidechainBuffer.begin(), mSidechainBuffer.begin() + safeNumFrames, 0.0f);
  std::fill(mSpreadL.begin(), mSpreadL.begin() + safeNumFrames, 0.0f);
  std::fill(mSpreadR.begin(), mSpreadR.begin() + safeNumFrames, 0.0f);
  std::fill(mWetSampleL.begin(), mWetSampleL.begin() + safeNumFrames, 0.0f);
  std::fill(mWetSampleR.begin(), mWetSampleR.begin() + safeNumFrames, 0.0f);

  float* mixedSampleL_arr = mMixedSampleL.data();
  float* mixedSampleR_arr = mMixedSampleR.data();
  float* sidechain_arr = mSidechainBuffer.data();
  float* spreadL_arr = mSpreadL.data();
  float* spreadR_arr = mSpreadR.data();
  float* wetSampleL_arr = mWetSampleL.data();
  float* wetSampleR_arr = mWetSampleR.data();

  // 1. Initialize FX Buses with Feedback from previous block
  const float denormalBias = 1e-15f;
  // Optimized Block Clear with NEON (v3.2.1)
#ifdef __ARM_NEON
  float32x4_t vDenormal = vdupq_n_f32(denormalBias);
#endif
  for (int b = 0; b < 18; ++b) {
    if (mFxBusBlockL[b].size() < safeNumFrames) mFxBusBlockL[b].resize(safeNumFrames, 0.0f);
    if (mFxBusBlockR[b].size() < safeNumFrames) mFxBusBlockR[b].resize(safeNumFrames, 0.0f);
    
    float* bL = mFxBusBlockL[b].data();
    float* bR = mFxBusBlockR[b].data();
    
    int j = 0;
#ifdef __ARM_NEON
    for (; j <= safeNumFrames - 4; j += 4) {
      vst1q_f32(&bL[j], vDenormal);
      vst1q_f32(&bR[j], vDenormal);
    }
#endif
    for (; j < safeNumFrames; ++j) {
      bL[j] = bR[j] = denormalBias;
    }
    
    // Feedback injection (with denormal protection)
    bL[0] += mFxFeedbacksL[b] + 1e-15f;
    bR[0] += mFxFeedbacksR[b] + 1e-15f;
    mFxFeedbacksL[b] = mFxFeedbacksR[b] = 0.0f;
  }

  // --- PHASE 1: GENERATE DRY SIGNAL & FX SENDS (BLOCK PROCESSING) ---
  bool inputNeeded = isInputNeeded();

  int activeTracks = 0;
  for (int t = 0; t < (int)mTracks.size(); ++t) {
    Track &track = mTracks[t];
    track.gainReduction = 1.0f;

    // Hard-disable path: perfectly skips rendering and silence checks
    if (track.isDisabled) {
        track.follower.process(0.0f);
        continue;
    }

    // Fast-path for silent/inactive tracks
    if (!track.isActive && track.mSilenceFrames > 2400) {
      track.follower.process(0.0f);
      continue;
    }
    activeTracks++;

    bool shouldMute = track.isMuted;
    if (anySolo && !track.isSoloed) {
      shouldMute = true;
    }

      // v3.1.39: Per-Sample Volume Ramping (Smoothing)
      // This eliminates zipper noise and "envelope pops" by interpolating volume frame-by-frame.
      float startVol = track.smoothedVolume;
      float targetVol = track.volume.load(std::memory_order_relaxed);
      float volStep = (targetVol - startVol) / (float)safeNumFrames;
      float currentSmoothVol = startVol;
      track.smoothedVolume = targetVol; // For next block

      // Cache active FX sends for this block (Sparse Mixer)
      float activeSends[18];
      int activeSendIndices[18];
      int activeSendCount = 0;
      float maxDryKill = 0.0f;

      for (int f = 0; f < 18; ++f) {
        float targetFx = track.fxSends[f].load(std::memory_order_relaxed);
        if (std::abs(targetFx - track.smoothedFxSends[f]) > 0.0001f) {
          track.smoothedFxSends[f] += 0.05f * (targetFx - track.smoothedFxSends[f]);
        }
        float sendAmt = track.smoothedFxSends[f];
        float mixAmt = track.fxMix[f].load(std::memory_order_relaxed);
        float wetAmount = sendAmt * mixAmt;
        
        if (wetAmount > 0.001f && (fxEnabledMask & (1u << f))) {
          activeSends[activeSendCount] = wetAmount;
          activeSendIndices[activeSendCount] = f;
          activeSendCount++;
          if (wetAmount > maxDryKill) maxDryKill = wetAmount;
        }
      }

      float dryScale = 1.0f - maxDryKill;
      if (dryScale < 0.0f) dryScale = 0.0f;
      if (shouldMute) dryScale = 0.0f;

      // Generate Track Block
      float trackBlockL[1024] = {0.0f};
      float trackBlockR[1024] = {0.0f};

      switch(track.engineType) {
        case 0: track.subtractiveEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
        case 1: track.fmEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
        case 2: track.samplerEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
        case 3: track.granularEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
        case 4: track.wavetableEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
        case 5: track.fmDrumEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
        case 6: track.analogDrumEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
        case 8: {
          float inputBlock[1024] = {0.0f};
          if (inputNeeded) {
            for (int j = 0; j < safeNumFrames; ++j) {
               inputBlock[j] = mInputRingBuffer[(mInputReadPtr + j) & 8191];
            }
          }
          track.audioInEngine.render(inputBlock, trackBlockL, trackBlockR, safeNumFrames);
          break;
        }
        case 9: track.soundFontEngine.render(trackBlockL, trackBlockR, safeNumFrames); break;
      }

      // Mix into Master and FX buses (v3.1.43: Optimized with ARM NEON SIMD)
      float combinedScale = startVol * dryScale;
      float combinedStep = volStep * dryScale;

      int i = 0;
#ifdef __ARM_NEON
      // Use NEON to sum 4 samples at a time
      float scale_vals[4] = { combinedScale, combinedScale + combinedStep, combinedScale + combinedStep * 2.0f, combinedScale + combinedStep * 3.0f };
      float32x4_t vCombinedScale = vld1q_f32(scale_vals);
      float32x4_t vCombinedStep4 = vdupq_n_f32(combinedStep * 4.0f);
      
      for (; i <= safeNumFrames - 4; i += 4) {
        float32x4_t vRawL = vld1q_f32(&trackBlockL[i]);
        float32x4_t vRawR = vld1q_f32(&trackBlockR[i]);
        
        float32x4_t vMixedL = vld1q_f32(&mixedSampleL_arr[i]);
        float32x4_t vMixedR = vld1q_f32(&mixedSampleR_arr[i]);
        
        vld1q_f32(&mixedSampleL_arr[i]); // Prefetch hint (not real prefetch but keeps compiler happy)
        
        vMixedL = vmlaq_f32(vMixedL, vRawL, vCombinedScale);
        vMixedR = vmlaq_f32(vMixedR, vRawR, vCombinedScale);
        
        vst1q_f32(&mixedSampleL_arr[i], vMixedL);
        vst1q_f32(&mixedSampleR_arr[i], vMixedR);
        
        // Update combinedScale for next 4 samples
        vCombinedScale = vaddq_f32(vCombinedScale, vCombinedStep4);

        // Update follower sub-sampled
        if (i % 64 == 0) track.follower.process((std::abs(trackBlockL[i]) + std::abs(trackBlockR[i])) * 0.5f);
      }
#endif

      // Remainder loop
      for (; i < safeNumFrames; ++i) {
        float renderedVol = startVol + (volStep * (float)i);
        float scale = renderedVol * dryScale;
        mixedSampleL_arr[i] += trackBlockL[i] * scale;
        mixedSampleR_arr[i] += trackBlockR[i] * scale;
        if (i % 64 == 0) track.follower.process((std::abs(trackBlockL[i]) + std::abs(trackBlockR[i])) * 0.5f);
      }

      // v3.2.1: Vectorized FX Sends (NEON)
      // This is a sparse matrix sum: mFxBus[fx] += trackBlock * sendAmt
      for (int s = 0; s < activeSendCount; ++s) {
        int fxIdx = activeSendIndices[s];
        float sendAmt = activeSends[s];
        float* busL = mFxBusBlockL[fxIdx].data();
        float* busR = mFxBusBlockR[fxIdx].data();
        
        int j = 0;
#ifdef __ARM_NEON
        float32x4_t vSendAmt = vdupq_n_f32(sendAmt);
        for (; j <= safeNumFrames - 4; j += 4) {
          float32x4_t vRawL = vld1q_f32(&trackBlockL[j]);
          float32x4_t vRawR = vld1q_f32(&trackBlockR[j]);
          float32x4_t vBusL = vld1q_f32(&busL[j]);
          float32x4_t vBusR = vld1q_f32(&busR[j]);
          
          vst1q_f32(&busL[j], vmlaq_f32(vBusL, vRawL, vSendAmt));
          vst1q_f32(&busR[j], vmlaq_f32(vBusR, vRawR, vSendAmt));
        }
#endif
        for (; j < safeNumFrames; ++j) {
          busL[j] += trackBlockL[j] * sendAmt;
          busR[j] += trackBlockR[j] * sendAmt;
        }
      }
        
        // Sidechain source collection
        if (t == mSidechainSourceTrack) {
          if (mSidechainSourceDrumIdx == -1 || track.mDrumLastTriggeredNote[mSidechainSourceDrumIdx] != -1) {
            for (int i_fx = 0; i_fx < safeNumFrames; ++i_fx) {
              sidechain_arr[i_fx] = (trackBlockL[i_fx] + trackBlockR[i_fx]) * 0.5f * (startVol + (volStep * (float)i_fx));
            }
          }
        }

      // Silence Detection (Block-based check)
      float blockSum = 0.0f;
      for (int i=0; i<safeNumFrames; i+=16) blockSum += std::abs(trackBlockL[i]);
      if (blockSum < 0.0001f) {
        track.mSilenceFrames += safeNumFrames;
        if (track.mSilenceFrames > 2400) {
          bool activeVoices = false;
          for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
            if (track.mActiveNotes[v].active) {
              activeVoices = true;
              break;
            }
          }
          if (track.mPhysicallyHeldNoteCount == 0 && !activeVoices && !mIsPlaying) {
            track.isActive = false;
            // Keep the counter above the threshold so the silence fast-path
            // actually skips this track until a trigger wakes it up again.
            track.mSilenceFrames = 2401;
          }
        }
      } else {
        track.mSilenceFrames = 0;
        track.isActive = true; // Audio detected — mark active
      }
    } // End track loop
    
    // Prepare FX input buffer (Phase 1 result)
    for (int i = 0; i < safeNumFrames; ++i) {
        wetSampleL_arr[i] = mixedSampleL_arr[i];
        wetSampleR_arr[i] = mixedSampleR_arr[i];
    }
    if (inputNeeded) mInputReadPtr += safeNumFrames;

  // --- IDLE FX GATE (performance profile) ---
  // When the app has been silent for a while there is nothing for the FX to
  // process. Skip Phase 2 entirely and let the output stay silent. The gate
  // resets as soon as a track is active, the transport runs or input is live.
  bool anyAudible = mIsPlaying || inputNeeded || activeTracks > 0;
  for (const auto &t : mTracks) {
    if (!t.isDisabled && (t.isActive || t.mPhysicallyHeldNoteCount > 0)) {
      anyAudible = true;
      break;
    }
  }
  if (anyAudible || !mIdleGateEnabled) {
    mIdleBlocks = 0;
  } else if (mIdleBlocks < 100000) {
    mIdleBlocks++;
  }
  const bool runFx = !(mIdleGateEnabled && mIdleBlocks > 750); // ~4 s

  // --- PER-FX SILENCE SKIP ---
  // An enabled FX whose input bus is silent and whose tail has decayed has
  // nothing to add. Skipping it is a large win on phone-class CPUs: the
  // default project enables all 18 buses but has zero sends, so every effect
  // used to process silence forever. Tail lengths are deliberately generous
  // so delay/reverb/echo decays are never cut.
  static const int kFxTailBlockCounts[18] = {
      100, 100, 100, 100, 400, 4000, 4000, 100, 100,
      100, 100, 100, 100, 4000, 100, 100, 100, 100};
  auto busHasSignal = [this, safeNumFrames](int index) {
    const float *l = mFxBusBlockL[index].data();
    const float *r = mFxBusBlockR[index].data();
    for (int i = 0; i < safeNumFrames; ++i) {
      if (std::abs(l[i]) > 1e-6f || std::abs(r[i]) > 1e-6f)
        return true;
    }
    return false;
  };
  auto shouldProcessFx = [&](int index) {
    const bool enabled = fxOn(index);
    if (!enabled) {
      // A bypassed bus only needs processing while its click-free fade-out
      // is still running.
      if (mFxFade[index] > 0.0f)
        return true;
      mFxTailBlocks[index] = 0;
      return false;
    }
    if (busHasSignal(index)) {
      mFxTailBlocks[index] = kFxTailBlockCounts[index];
      return true;
    }
    if (mFxTailBlocks[index] > 0) {
      --mFxTailBlocks[index];
      return true;
    }
    return false;
  };

  // --- PHASE 2: BLOCK FX PROCESSING ---
  auto routeFxBlock = [this, safeNumFrames, &wetSampleL_arr, &wetSampleR_arr, &fxOn](int index, bool isDelta, float* outBufL, float* outBufR) {
    int dest = mFxChainDest[index];
    // Click-free bypass: ramp the wet contribution to zero over this block
    // when the bus was just disabled (and back up when re-enabled).
    const float fadeStart = mFxFade[index];
    const float fadeTarget = fxOn(index) ? 1.0f : 0.0f;
    const float fadeStep =
        (fadeTarget - fadeStart) / (float)std::max(1, safeNumFrames - 1);
    
    for (int i = 0; i < safeNumFrames; ++i) {
      if (!std::isfinite(outBufL[i]) || !std::isfinite(outBufR[i])) {
        outBufL[i] = outBufR[i] = 0.0f; 
      }
      
      float outL, outR;
      if (isDelta) {
        outL = (mFxBusBlockL[index][i] + outBufL[i]) * mFxMixLevels[index];
        outR = (mFxBusBlockR[index][i] + outBufR[i]) * mFxMixLevels[index];
      } else {
        outL = outBufL[i] * mFxMixLevels[index];
        outR = outBufR[i] * mFxMixLevels[index];
      }

      const float fade = fadeStart + fadeStep * (float)i;
      outL *= fade;
      outR *= fade;

      if (dest >= 0 && dest < 18) {
        const int order[18] = {0, 1, 4, 5, 6, 7, 8, 9, 10, 2, 3, 11, 12, 15, 16, 13, 14, 17};
        bool isForward = order[dest] > order[index];

        if (isForward) {
          mFxBusBlockL[dest][i] += outL;
          mFxBusBlockR[dest][i] += outR;
        } else {
          if (i == safeNumFrames - 1) {
            mFxFeedbacksL[dest] += outL;
            mFxFeedbacksR[dest] += outR;
          }
        }
      } else {
        wetSampleL_arr[i] += outL;
        wetSampleR_arr[i] += outR;
      }
    }
    mFxFade[index] = fadeTarget;
  };

  // FX 0: Overdrive
  if (runFx && shouldProcessFx(0)) {
    float tmpL[1024], tmpR[1024];
    for (int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[0][i]; tmpR[i] = mFxBusBlockR[0][i]; }
    mOverdriveFxL.processBlock(tmpL, safeNumFrames);
    mOverdriveFxR.processBlock(tmpR, safeNumFrames);
    routeFxBlock(0, false, tmpL, tmpR);
  }

  // FX 1: Bitcrusher
  if (runFx && shouldProcessFx(1)) {
    float tmpL[1024], tmpR[1024];
    for (int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[1][i]; tmpR[i] = mFxBusBlockR[1][i]; }
    mBitcrusherFxL.processBlock(tmpL, safeNumFrames);
    mBitcrusherFxR.processBlock(tmpR, safeNumFrames);
    routeFxBlock(1, false, tmpL, tmpR);
  }

  // FX 9: HP LFO
  if (runFx && shouldProcessFx(9)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[9][i]; tmpR[i] = mFxBusBlockR[9][i]; }
    mHpLfoL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mHpLfoR.syncFrom(mHpLfoL);
    mHpLfoR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(9, false, tmpL, tmpR);
  }

  // FX 10: LP LFO
  if (runFx && shouldProcessFx(10)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[10][i]; tmpR[i] = mFxBusBlockR[10][i]; }
    mLpLfoL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mLpLfoR.syncFrom(mLpLfoL);
    mLpLfoR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(10, false, tmpL, tmpR);
  }

  // FX 2: Chorus
  if (runFx && shouldProcessFx(2)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[2][i]; tmpR[i] = mFxBusBlockR[2][i]; }
    mChorusFxL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mChorusFxR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(2, false, tmpL, tmpR);
  }

  // FX 3: Phaser
  if (runFx && shouldProcessFx(3)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[3][i]; tmpR[i] = mFxBusBlockR[3][i]; }
    mPhaserFxL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mPhaserFxR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(3, false, tmpL, tmpR);
  }

  // FX 4: Tape Wobble
  if (runFx && shouldProcessFx(4)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[4][i]; tmpR[i] = mFxBusBlockR[4][i]; }
    mTapeWobbleFx.processBlockStereo(tmpL, tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(4, true, tmpL, tmpR); // Delta Mode
  }

  // FX 5: Delay
  if (runFx && shouldProcessFx(5)) {
    float dL[1024], dR[1024];
    for(int i=0; i<safeNumFrames; ++i) { dL[i] = mFxBusBlockL[5][i]; dR[i] = mFxBusBlockR[5][i]; }
    mDelayFx.processBlockStereo(dL, dR, safeNumFrames, mSampleRate);
    int dest = mFxChainDest[5];
    const float fadeStart = mFxFade[5];
    const float fadeTarget = fxOn(5) ? 1.0f : 0.0f;
    const float fadeStep =
        (fadeTarget - fadeStart) / (float)std::max(1, safeNumFrames - 1);
    if (dest >= 0 && dest < 17) {
      for(int i=0; i<safeNumFrames; ++i) {
        const float fade = fadeStart + fadeStep * (float)i;
        mFxBusBlockL[dest][i] += dL[i] * mFxMixLevels[5] * fade;
        mFxBusBlockR[dest][i] += dR[i] * mFxMixLevels[5] * fade;
      }
      // Note: Backward chaining handled via loop if applicable
    } else {
      for(int i=0; i<safeNumFrames; ++i) {
        const float fade = fadeStart + fadeStep * (float)i;
        spreadL_arr[i] += dL[i] * fade;
        spreadR_arr[i] += dR[i] * fade;
      }
    }
    mFxFade[5] = fadeTarget;
  }

  // FX 6: Reverb
  if (runFx && shouldProcessFx(6)) {
    float rL[1024], rR[1024];
    for(int i=0; i<safeNumFrames; ++i) { rL[i] = mFxBusBlockL[6][i]; rR[i] = mFxBusBlockR[6][i]; }
    mReverbFx.processBlockStereoWet(rL, rR, safeNumFrames);
    int dest = mFxChainDest[6];
    const float fadeStart = mFxFade[6];
    const float fadeTarget = fxOn(6) ? 1.0f : 0.0f;
    const float fadeStep =
        (fadeTarget - fadeStart) / (float)std::max(1, safeNumFrames - 1);
    if (dest >= 0 && dest < 17) {
      for(int i=0; i<safeNumFrames; ++i) {
        const float fade = fadeStart + fadeStep * (float)i;
        mFxBusBlockL[dest][i] += rL[i] * mFxMixLevels[6] * fade;
        mFxBusBlockR[dest][i] += rR[i] * mFxMixLevels[6] * fade;
      }
    } else {
      for(int i=0; i<safeNumFrames; ++i) {
        const float fade = fadeStart + fadeStep * (float)i;
        spreadL_arr[i] += rL[i] * fade;
        spreadR_arr[i] += rR[i] * fade;
      }
    }
    mFxFade[6] = fadeTarget;
  }

  // FX 7: Slicer (Requires mSampleCount per sample)
  if (runFx && shouldProcessFx(7)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) {
      tmpL[i] = mSlicerFxL.process(mFxBusBlockL[7][i], mSampleCount + i, mSamplesPerStep);
      tmpR[i] = mSlicerFxR.process(mFxBusBlockR[7][i], mSampleCount + i, mSamplesPerStep);
    }
    routeFxBlock(7, false, tmpL, tmpR);
  }

  // FX 8: Compressor (Requires sidechain)
  if (runFx && shouldProcessFx(8)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[8][i]; tmpR[i] = mFxBusBlockR[8][i]; }
    mCompressorFx.processBlock(tmpL, sidechain_arr, safeNumFrames);
    // Duplicate compressor for right? Or mono shared state? Current code used `mCompressorFx.process(R)`
    // So we use mCompressorFx for right as well
    mCompressorFx.processBlock(tmpR, sidechain_arr, safeNumFrames);
    routeFxBlock(8, false, tmpL, tmpR);
  }

  // FX 11: Flanger
  if (runFx && shouldProcessFx(11)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[11][i]; tmpR[i] = mFxBusBlockR[11][i]; }
    mFlangerFxL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mFlangerFxR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(11, true, tmpL, tmpR); // Delta Mode
  }

  // FX 12: FilterPedal 0
  if (runFx && shouldProcessFx(12)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[12][i]; tmpR[i] = mFxBusBlockR[12][i]; }
    for(int i=0; i<safeNumFrames; ++i) { 
      tmpL[i] = mFilterPedalL[0].process(tmpL[i], mSampleRate);
      tmpR[i] = mFilterPedalR[0].process(tmpR[i], mSampleRate);
    }
    routeFxBlock(12, false, tmpL, tmpR);
  }

  // FX 15: FilterPedal 1
  if (runFx && shouldProcessFx(15)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[15][i]; tmpR[i] = mFxBusBlockR[15][i]; }
    for(int i=0; i<safeNumFrames; ++i) { 
      tmpL[i] = mFilterPedalL[1].process(tmpL[i], mSampleRate);
      tmpR[i] = mFilterPedalR[1].process(tmpR[i], mSampleRate);
    }
    routeFxBlock(15, false, tmpL, tmpR);
  }

  // FX 16: FilterPedal 2
  if (runFx && shouldProcessFx(16)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[16][i]; tmpR[i] = mFxBusBlockR[16][i]; }
    for(int i=0; i<safeNumFrames; ++i) { 
      tmpL[i] = mFilterPedalL[2].process(tmpL[i], mSampleRate);
      tmpR[i] = mFilterPedalR[2].process(tmpR[i], mSampleRate);
    }
    routeFxBlock(16, false, tmpL, tmpR);
  }

  // FX 13: Tape Echo
  if (runFx && shouldProcessFx(13)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { 
      tmpL[i] = mFxBusBlockL[13][i] + 1.0e-18f; 
      tmpR[i] = mFxBusBlockR[13][i] + 1.0e-18f; 
    }
    mTapeEchoFxL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mTapeEchoFxR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(13, false, tmpL, tmpR);
  }

  // FX 14: Octaver
  if (runFx && shouldProcessFx(14)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[14][i]; tmpR[i] = mFxBusBlockR[14][i]; }
    mOctaverFxL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mOctaverFxR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(14, false, tmpL, tmpR);
  }

  // FX 17: Eq5Band
  if (runFx && shouldProcessFx(17)) {
    float tmpL[1024], tmpR[1024];
    for(int i=0; i<safeNumFrames; ++i) { tmpL[i] = mFxBusBlockL[17][i]; tmpR[i] = mFxBusBlockR[17][i]; }
    mEq5BandFxL.processBlock(tmpL, safeNumFrames, mSampleRate);
    mEq5BandFxR.processBlock(tmpR, safeNumFrames, mSampleRate);
    routeFxBlock(17, false, tmpL, tmpR);
  }

  // v3.2.1: Dynamic Headroom Scaling
  // If track count > 4, we scale down by 3dB to prevent cubic saturation artifacts
  float trackScale = (activeTracks > 4) ? 0.707f : 1.0f;
  for (int i = 0; i < safeNumFrames; ++i) {
    float finalL = (mixedSampleL_arr[i] + wetSampleL_arr[i] + spreadL_arr[i]) * masterVol * trackScale;
    float finalR = (mixedSampleR_arr[i] + wetSampleR_arr[i] + spreadR_arr[i]) * masterVol * trackScale;

    if (!std::isfinite(finalL)) finalL = 0.0f;
    if (!std::isfinite(finalR)) finalR = 0.0f;

    // Apply soft-limiter to the final summed result before writing to hardware buffer
    outBuffer[i * 2] = softLimit(finalL);
    outBuffer[i * 2 + 1] = softLimit(finalR);
  }

// Reset Punch Active flags for all tracks after processing the block
// Reset of mPunchActive removed here, handled frame-by-frame
}

void AudioEngineCore::renderToWav(int numCycles, const std::string &path) {
  std::lock_guard<std::recursive_mutex> lock(mLock);

  // Offline rendering logic
  int framesPerCycle = mSamplesPerStep * 16; // One bar (16 steps)
  int totalFrames = framesPerCycle * numCycles;
  std::vector<float> output(totalFrames * 2);

  // Reset sequence state for export
  mSampleCount.store(0, std::memory_order_relaxed);
  // --- High Priority: Audio Rendering ---
  int framesRendered = 0;
  while (framesRendered < totalFrames) {
    int chunk = std::min(64, totalFrames - framesRendered);

    // Lock only for the minimum duration needed to update state or commands
    {
      std::lock_guard<std::recursive_mutex> lock(mLock);
      processCommands();

      // Check for next step trigger
      double scExport = mSampleCount.load(std::memory_order_relaxed);
      double spsExport = mSamplesPerStep.load(std::memory_order_relaxed);
      if (scExport >= spsExport) {
        scExport -= spsExport;
        mSampleCount.store(scExport, std::memory_order_relaxed);
        mGlobalStepIndex = (mGlobalStepIndex + 1) % mPatternLength.load(std::memory_order_relaxed);

        // Advance sequencers
        for (int t = 0; t < (int)mTracks.size(); ++t) {
          if (mTracks[t].isActive) {
            mTracks[t].sequencer.advance();
            const Step &s = mTracks[t].sequencer.getCurrentStep();
            if (s.active) {
              // Trigger logic here... simplified for stability
              triggerNoteLocked(t, 60, 100, true);
            }
          }
        }
      }
    } // Unlock immediately after state update

    // Render Audio (Lock is re-acquired inside renderStereo only if needed?
    // Actually renderStereo accesses mTracks heavily, so it probably NEEDS
    // safety. However, locking for the WHOLE block causes glitches. The
    // "Shield" strategy: Hold lock during render, but check it's not held
    // too long. For now, simplicity = safety.
    {
      std::lock_guard<std::recursive_mutex> lock(mLock);
      renderStereo(&output[framesRendered * 2], chunk);
    }

    framesRendered += chunk;
    mSampleCount.store(mSampleCount.load(std::memory_order_relaxed) + chunk, std::memory_order_relaxed);
  }

  WavFileUtils::writeWav(path, output, 48000, 2, {});
}

void AudioEngineCore::loadWavetable(int trackIndex, const std::string &path) {
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    if (mTracks[trackIndex].engineType == 4) { // Wavetable Engine
      mTracks[trackIndex].wavetableEngine.loadWavetable(path);
    }
  }
}

void AudioEngineCore::loadDefaultWavetable(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    if (mTracks[trackIndex].engineType == 4) {
      mTracks[trackIndex].wavetableEngine.loadDefaultWavetable();
    }
  }
}

void AudioEngineCore::getStepActiveStates(int trackIndex, bool *out, int maxSize) {
  if (trackIndex < 0 || trackIndex >= 8) {
    if (out) std::fill(out, out + maxSize, false);
    return;
  }

  std::unique_lock<std::mutex> lock(mSnapshotLock, std::try_to_lock);
  if (!lock.owns_lock()) lock.lock();

  const auto &snap = mUISnapshot.tracks[trackIndex];
  int limit = std::min(maxSize, 64);
  for (int i = 0; i < limit; ++i) {
    out[i] = snap.activeSteps.test(i);
  }
}
void AudioEngineCore::setInputDevice(int deviceId) {
  // Platform-specific implementation required in the driver (e.g. MacAudioEngine or OboeAudioEngine)
  LOGD("AudioEngineCore::setInputDevice(%d) - Platform implementation required", deviceId);
}
void AudioEngineCore::setTrackVolume(int trackIndex, float volume) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_VOLUME;
  cmd.trackIndex = trackIndex;
  cmd.value = volume;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setTrackActive(int trackIndex, bool active) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_ACTIVE;
  cmd.trackIndex = trackIndex;
  cmd.bValue = active;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setTrackDisabled(int trackIndex, bool disabled) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_DISABLED;
  cmd.trackIndex = trackIndex;
  cmd.bValue = disabled;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setTrackPan(int trackIndex, float pan) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_PAN;
  cmd.trackIndex = trackIndex;
  cmd.value = pan;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setPadMod(int trackIndex, float value) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_PAD_MOD;
  cmd.trackIndex = trackIndex;
  cmd.value = value;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::beginVoicePadMod(int trackIndex, int note) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_VOICE_MOD_BEGIN;
  cmd.trackIndex = trackIndex;
  cmd.data1 = note;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setVoicePadMod(int trackIndex, int note, float normX,
                                     float normY) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_VOICE_MOD_UPDATE;
  cmd.trackIndex = trackIndex;
  cmd.data1 = note;
  cmd.value = normX;
  cmd.gate = normY;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::endVoicePadMod(int trackIndex, int note) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_VOICE_MOD_END;
  cmd.trackIndex = trackIndex;
  cmd.data1 = note;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setPadModRouting(int trackIndex, int xDest, float xInt,
                                       int yDest, float yInt, bool polyMode) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_PAD_MOD_ROUTING;
  cmd.trackIndex = trackIndex;
  cmd.data1 = xDest;
  cmd.data2 = yDest;
  cmd.value = xInt;
  cmd.gate = yInt;
  cmd.bValue = polyMode;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setTrackMute(int trackIndex, bool muted) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_MUTE;
  cmd.trackIndex = trackIndex;
  cmd.bValue = muted;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setTrackSolo(int trackIndex, bool soloed) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_SOLO;
  cmd.trackIndex = trackIndex;
  cmd.bValue = soloed;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setEngineType(int trackIndex, int type) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_ENGINE_TYPE;
  cmd.trackIndex = trackIndex;
  cmd.data1 = type;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}
void AudioEngineCore::loadSoundFont(int trackIndex, const std::string &path) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f)
      return;

    // Check file size > 1KB (SoundFonts are usually larger)
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fclose(f); // <--- Close AFTER using

    if (size < 1024) {
      return;
    }

    std::lock_guard<std::recursive_mutex> lock(mLock);
    mTracks[trackIndex].soundFontEngine.load(path);
  }
}

void AudioEngineCore::setSoundFontPreset(int trackIndex, int presetIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    std::lock_guard<std::recursive_mutex> lock(mLock);
    mTracks[trackIndex].soundFontEngine.setPreset(presetIndex);
  }
}

int AudioEngineCore::getSoundFontPresetCount(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    return mTracks[trackIndex].soundFontEngine.getPresetCount();
  }
  return 0;
}

std::string AudioEngineCore::getSoundFontPresetName(int trackIndex,
                                                int presetIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    return mTracks[trackIndex].soundFontEngine.getPresetName(presetIndex);
  }
  return "";
}

void AudioEngineCore::setSlices(int trackIndex, const std::vector<int> &starts, const std::vector<int> &ends) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_SLICES;
  cmd.trackIndex = trackIndex;
  cmd.sliceStarts = starts;
  cmd.sliceEnds = ends;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::getFxChain(int *destination) {
  for (int i = 0; i < 17; ++i)
    destination[i] = mFxChainDest[i];
}

void AudioEngineCore::setFocusedTrack(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < 8) {
    mFocusedTrackIndex.store(trackIndex);
  }
}

void AudioEngineCore::updateUISnapshot() {
  std::unique_lock<std::mutex> lock(mSnapshotLock, std::try_to_lock);
  if (!lock.owns_lock()) return;

  mUISnapshot.isPlaying = mIsPlaying.load();
  mUISnapshot.isRecording = mIsRecording.load();
  mUISnapshot.masterVolume = mMasterVolume.load();

  int focusedIdx = mFocusedTrackIndex.load();

  for (int i = 0; i < 8 && i < (int)mTracks.size(); ++i) {
    Track &track = mTracks[i];
    auto &snap = mUISnapshot.tracks[i];
    
    // Always update track levels for activity highlights (fast)
    mUISnapshot.trackLevels[i] = track.follower.getLevel();
    
    // Only perform heavy copies for the track the user is actually looking at
    if (i == focusedIdx) {
      // Sync playhead (wait-free)
      snap.currentStep = track.sequencer.getCurrentStepIndex();

      // Copy parameters (limit to 1024)
      std::memcpy(snap.parameters, track.parameters, sizeof(float) * 1024);
      
      // Sync sequencer active states
      const auto &steps = track.sequencer.getSteps();
      for(int s=0; s<64 && s<(int)steps.size(); ++s) {
          snap.activeSteps.set(s, steps[s].active);
      }
      
      // Sync Drum Sequencer active states (only if it's a drum engine)
      if (track.engineType == 5 || track.engineType == 6) {
        for(int d=0; d<16; ++d) {
            const auto &dsteps = track.drumSequencers[d].getSteps();
            for(int s=0; s<64 && s<(int)dsteps.size(); ++s) {
                snap.activeDrumSteps[d].set(s, dsteps[s].active);
            }
        }
      }
    } else {
        // Just sync playhead for other tracks (cheap)
        snap.currentStep = track.sequencer.getCurrentStepIndex();
    }
  }
}

void AudioEngineCore::setChainEnabled(int trackIndex, bool enabled) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_CHAIN_ENABLED;
  cmd.trackIndex = trackIndex;
  cmd.bValue = enabled;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setChainLength(int trackIndex, int length) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_CHAIN_LENGTH;
  cmd.trackIndex = trackIndex;
  cmd.data1 = length;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setChainSlot(int trackIndex, int slotIndex, int laneIndex, const std::vector<Step> &steps) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_CHAIN_SLOT;
  cmd.trackIndex = trackIndex;
  cmd.data1 = slotIndex;
  cmd.laneIndex = laneIndex;
  cmd.steps = steps;
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(cmd);
}

void AudioEngineCore::setSlicePosition(int trackIndex, int sliceIndex,
                                   float position) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].samplerEngine.setSlicePosition(sliceIndex, position);
  }
}
bool AudioEngineCore::isInputNeeded() {
    // 1. Check if any track is an Audio In engine
    for (const auto& track : mTracks) {
        if (track.isActive && (track.engineType == 8)) {
            return true;
        }
    }
    // 2. Check if recording is active and targeting input
    if (mIsRecordingSample && (mRecordingSource == 0)) { // 0 = Mic
        return true;
    }
    return false;
}
void AudioEngineCore::setTrackProbability(int trackIndex, float probability) {
  AudioCommand cmd;
  cmd.type = AudioCommand::SET_TRACK_PROBABILITY;
  cmd.trackIndex = trackIndex;
  cmd.value = probability;
  {
    std::lock_guard<std::mutex> lock(mCommandLock);
    mCommandQueue.push_back(cmd);
  }
}
