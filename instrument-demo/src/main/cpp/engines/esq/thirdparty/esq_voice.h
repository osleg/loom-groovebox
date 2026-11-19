/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef ESQ_SKIFF_VOICE_H
#define ESQ_SKIFF_VOICE_H

#include <stdint.h>

enum {
    ESQ_VOICE_WAVES = 32,
    ESQ_VOICE_OSC = 3,
    ESQ_VOICE_HISTORY = 16,
    ESQ_VOICE_BLOCK = 32,
    ESQ_VOICE_SYNC = 1,
    ESQ_VOICE_AM = 2,
};

typedef struct {
    uint32_t phase[ESQ_VOICE_OSC], fraction;
    int32_t integrator[4], dc, output_lp[2];
    int16_t history[ESQ_VOICE_HISTORY];
} esq_core_voice_t;

typedef struct {
    uint8_t wave[ESQ_VOICE_OSC];        /* 0 off, 1–32 the stand-in waves */
    uint8_t level[ESQ_VOICE_OSC];
    int8_t transpose[ESQ_VOICE_OSC];
    int32_t pitch;                    /* MIDI pitch in 1/256 semitones */
    uint16_t cutoff;                  /* DAC value in Q8, 0–255 */
    uint8_t key, resonance, mode;
} esq_voice_control_t;

/* The frame count is at most ESQ_VOICE_BLOCK. Output is mono PCM at 44.1 kHz. */
void esq_voice_render(esq_core_voice_t *voice, const esq_voice_control_t *control,
                      int32_t *out, uint32_t count);

#endif
