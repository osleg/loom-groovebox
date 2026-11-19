/* SPDX-License-Identifier: MIT AND GPL-3.0-only */
#pragma once
#include <stdint.h>

#define MONK_GRAIN 882u
#define MONK_OVERLAP 1024u

typedef struct {
    float vowel, head, breath, vibrato, rate;
} monk_patch_t;

typedef struct {
    float overlap[MONK_OVERLAP];
    float grain[MONK_GRAIN];
    float vowel, head, breath, trigger_in, period, vibrato_phase, jitter;
    uint32_t read, rng, jitter_counter;
    int pitch_step;
    uint8_t ready;
} monk_voice_t;

void monk_voice_start(monk_voice_t *v, uint32_t seed);
void monk_voice_render(monk_voice_t *v, const monk_patch_t *p, float pitch, float *out, uint32_t n);
