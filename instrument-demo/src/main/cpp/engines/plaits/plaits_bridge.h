/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    MACRO_VIRTUAL_ANALOG, MACRO_WAVEFOLD, MACRO_FM, MACRO_ADDITIVE,
    MACRO_SWARM, MACRO_NOISE, MACRO_STRING, MACRO_MODAL,
    MACRO_VCF, MACRO_PHASE_DISTORTION, MACRO_SIX_OP_KEYS, MACRO_SIX_OP_BELLS,
    MACRO_SIX_OP_PADS, MACRO_WAVE_TERRAIN, MACRO_STRING_MACHINE, MACRO_CHIPTUNE,
    MACRO_GRAIN, MACRO_WAVETABLE, MACRO_CHORD, MACRO_SPEECH,
    MACRO_PARTICLE, MACRO_KICK, MACRO_SNARE, MACRO_HI_HAT, MACRO_MODEL_COUNT
};

typedef union {
    uint64_t alignment;
    unsigned char bytes[18432];
} plaits_voice_t;

typedef struct {
    float note, harmonics, timbre, morph, mix, velocity;
    uint8_t model, gate;
} plaits_controls_t;

void app_plaits_note(plaits_voice_t *voice, uint32_t seed, int reset);
void app_plaits_render(plaits_voice_t *voice, const plaits_controls_t *patch, float *out, uint32_t n);

#ifdef __cplusplus
}
#endif
