/* SPDX-License-Identifier: GPL-3.0-only */
#pragma once
#include <stdint.h>

#define FIMBA_MODES 5
enum { FIMBA_STEEL, FIMBA_BRASS, FIMBA_BRONZE, FIMBA_ALUMINUM, FIMBA_BAMBOO, FIMBA_GLASS, FIMBA_MATERIALS };
enum { FIMBA_BARE, FIMBA_BOARD, FIMBA_BOX, FIMBA_GOURD, FIMBA_BODIES };

typedef struct {
    float s1, s2, a1, a2, a3, gain;
} fimba_filter_t;

typedef struct {
    uint8_t material, hard, decay, tone, body, buzz, wear, hole;
} fimba_patch_t;

typedef struct {
    float b1[FIMBA_MODES], b2[FIMBA_MODES], gain[FIMBA_MODES];
    float y1[FIMBA_MODES], y2[FIMBA_MODES];
    fimba_filter_t body[4], muffle;
    float pitch, tension, pulse, pulse_step, pulse_amp, click, buzz_state, low, high;
    uint32_t rng;
    int contact_samples;
    int note;
    uint8_t material, decay, tone, wear, body_type, hole, ready;
} fimba_voice_t;

void fimba_pluck(fimba_voice_t *v, const fimba_patch_t *p, int note, int velocity);
void fimba_render(fimba_voice_t *v, const fimba_patch_t *p, float pitch, float *out, uint32_t n);
