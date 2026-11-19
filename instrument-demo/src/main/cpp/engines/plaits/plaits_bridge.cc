/* SPDX-License-Identifier: GPL-3.0-only
 * Per-voice adapter for the Mutable Instruments synthesis DSP by Emilie Gillet. */
#include "plaits_bridge.h"

#include "plaits/dsp/engine/additive_engine.h"
#include "plaits/dsp/engine/fm_engine.h"
#include "plaits/dsp/engine/modal_engine.h"
#include "plaits/dsp/engine/noise_engine.h"
#include "plaits/dsp/engine/string_engine.h"
#include "plaits/dsp/engine/swarm_engine.h"
#include "plaits/dsp/engine/virtual_analog_engine.h"
#include "plaits/dsp/engine/waveshaping_engine.h"
#include "plaits/dsp/engine/bass_drum_engine.h"
#include "plaits/dsp/engine/chord_engine.h"
#include "plaits/dsp/engine/grain_engine.h"
#include "plaits/dsp/engine/hi_hat_engine.h"
#include "plaits/dsp/engine/particle_engine.h"
#include "plaits/dsp/engine/snare_drum_engine.h"
#include "plaits/dsp/engine/speech_engine.h"
#include "plaits/dsp/engine/wavetable_engine.h"
#include "plaits/dsp/engine2/chiptune_engine.h"
#include "plaits/dsp/engine2/phase_distortion_engine.h"
#include "plaits/dsp/engine2/six_op_engine.h"
#include "plaits/dsp/engine2/string_machine_engine.h"
#include "plaits/dsp/engine2/virtual_analog_vcf_engine.h"
#include "plaits/dsp/engine2/wave_terrain_engine.h"
#include "stmlib/utils/random.h"

#include <cstdint>
#include <new>
#include <cstring>

namespace {
using namespace plaits;

struct Voice {
    Engine* engine;
    uint32_t random;
    int model;
    bool trigger;
    alignas(double) uint8_t storage[sizeof(plaits_voice_t) - 32u];
};

static_assert(sizeof(Voice) <= sizeof(plaits_voice_t), "C voice storage size");
static_assert(alignof(Voice) <= alignof(plaits_voice_t), "C voice storage alignment");

// Each model takes only the space it needs before the work area. In particular,
// the small ParticleEngine leaves room for its large delay without increasing
// every voice by the size of the six-operator engine.
template<class Model, size_t ScratchSize = 0>
Engine* construct(Voice* voice)
{
    constexpr size_t kAlignment = alignof(double);
    constexpr size_t kOffset = (sizeof(Model) + kAlignment - 1u) & ~(kAlignment - 1u);
    static_assert(alignof(Model) <= alignof(Voice), "model storage alignment");
    static_assert(kOffset + ScratchSize <= sizeof(voice->storage), "model and scratch storage size");

    std::memset(voice->storage, 0, sizeof(Model));
    Model* instance = new (voice->storage) Model();
    stmlib::BufferAllocator allocator(voice->storage + kOffset, sizeof(voice->storage) - kOffset);
    instance->Init(&allocator);
    return instance;
}

Engine* construct_model(Voice* voice, int model)
{
    switch (model) {
    case MACRO_WAVEFOLD: return construct<WaveshapingEngine>(voice);
    case MACRO_FM: return construct<FMEngine>(voice);
    case MACRO_ADDITIVE: return construct<AdditiveEngine>(voice);
    case MACRO_SWARM: return construct<SwarmEngine, sizeof(SwarmVoice) * kNumSwarmVoices>(voice);
    case MACRO_NOISE: return construct<NoiseEngine>(voice);
    case MACRO_STRING:
        return construct<StringEngine, sizeof(float) * (kMaxBlockSize + 16u +
                         kNumStrings * (kDelayLineSize + kDelayLineSize / 4u))>(voice);
    case MACRO_MODAL: return construct<ModalEngine>(voice);
    case MACRO_VCF: return construct<VirtualAnalogVCFEngine>(voice);
    case MACRO_PHASE_DISTORTION: return construct<PhaseDistortionEngine>(voice);
    case MACRO_SIX_OP_KEYS:
    case MACRO_SIX_OP_BELLS:
    case MACRO_SIX_OP_PADS:
        return construct<SixOpEngine, 3u * kMaxBlockSize * sizeof(float) + 32u * sizeof(fm::Patch)>(voice);
    case MACRO_WAVE_TERRAIN: return construct<WaveTerrainEngine>(voice);
    case MACRO_STRING_MACHINE: return construct<StringMachineEngine>(voice);
    case MACRO_CHIPTUNE: return construct<ChiptuneEngine>(voice);
    case MACRO_GRAIN: return construct<GrainEngine>(voice);
    case MACRO_WAVETABLE: return construct<WavetableEngine>(voice);
    case MACRO_CHORD: return construct<ChordEngine>(voice);
    case MACRO_SPEECH:
        return construct<SpeechEngine, kLPCSpeechSynthMaxFrames * sizeof(LPCSpeechSynth::Frame) +
                         kLPCSpeechSynthMaxWords * sizeof(int) + 2u * kMaxBlockSize * sizeof(float)>(voice);
    case MACRO_PARTICLE: return construct<ParticleEngine, 8192u * sizeof(uint16_t)>(voice);
    case MACRO_KICK: return construct<BassDrumEngine>(voice);
    case MACRO_SNARE: return construct<SnareDrumEngine>(voice);
    case MACRO_HI_HAT: return construct<HiHatEngine>(voice);
    default: return construct<VirtualAnalogEngine>(voice);
    }
}

void select_model(Voice* voice, int model)
{
    voice->engine = construct_model(voice, model);
    if (model >= MACRO_SIX_OP_KEYS && model <= MACRO_SIX_OP_PADS) {
        const size_t bank = static_cast<size_t>(model - MACRO_SIX_OP_KEYS);
        voice->engine->LoadUserData(macro_fm_banks + bank * 32u * fm::Patch::SYX_SIZE);
    } else if (model == MACRO_WAVETABLE) {
        voice->engine->LoadUserData(nullptr);
    }
    voice->engine->Reset();
    voice->model = model;
    voice->trigger = true;
}
}

extern "C" void app_plaits_note(plaits_voice_t* state, uint32_t seed, int reset)
{
    Voice* voice = reinterpret_cast<Voice*>(state->bytes);
    if (reset) {
        voice = new (state->bytes) Voice{};
        voice->model = -1;
        voice->random = seed;
    }
    voice->trigger = true;
}

extern "C" void app_plaits_render(plaits_voice_t* state, const plaits_controls_t* patch, float* out, uint32_t n)
{
    Voice* voice = reinterpret_cast<Voice*>(state->bytes);
    const uint32_t previous_random = stmlib::Random::state();
    stmlib::Random::Seed(voice->random);
    if (voice->model != patch->model)
        select_model(voice, patch->model);

    EngineParameters p{};
    p.note = patch->note;
    p.harmonics = patch->harmonics;
    p.timbre = patch->timbre;
    p.morph = patch->morph;
    p.accent = patch->velocity;
    for (uint32_t at = 0; at < n;) {
        const uint32_t count = n - at > kBlockSize ? kBlockSize : n - at;
        float main[kBlockSize], aux[kBlockSize];
        bool already_enveloped = false;
        p.trigger = (patch->gate ? TRIGGER_HIGH : TRIGGER_LOW) | (voice->trigger ? TRIGGER_RISING_EDGE : TRIGGER_LOW);
        voice->engine->Render(p, main, aux, count, &already_enveloped);
        voice->trigger = false;
        for (uint32_t i = 0; i < count; i++)
            out[at + i] = main[i] * (1.0f - patch->mix) + aux[i] * patch->mix;
        at += count;
    }
    voice->random = stmlib::Random::state();
    stmlib::Random::Seed(previous_random);
}
