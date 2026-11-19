/* SPDX-License-Identifier: MIT AND GPL-3.0-only
 *
 * MonkSynth's DSP (dsp/voice.c, synth.c, delay.c), Copyright (c) 2026 Jonathan Taylor, MIT License:
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 *   associated documentation files (the "Software"), to deal in the Software without restriction,
 *   including without limitation the rights to use, copy, modify, merge, publish, distribute,
 *   sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions: The above copyright notice and this
 *   permission notice shall be included in all copies or substantial portions of the Software.
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 *
 * Adapted for FM-1 Instrument, 2026-11-19. Grain synthesis follows MONK-1.
 * Skiff handles note allocation, amplitude envelope, glide and effects. */

#include "monk.h"
#include "fastmath.h"
#include <string.h>

#define FS 44100.0f
#define SINE_SIZE 1024u
#define NUM_FORMANTS 3

static const float FORMANT_FREQS[NUM_FORMANTS][5] = {
    {280.0f, 450.0f, 800.0f, 350.0f, 270.0f},
    {600.0f, 800.0f, 1150.0f, 2000.0f, 2140.0f},
    {2240.0f, 2830.0f, 2900.0f, 2800.0f, 2950.0f},
};
static const float FORMANT_BW[NUM_FORMANTS] = {32.5f, 47.5f, 62.5f};


/* Initialized by the runtime's audio thread. No per-track configuration here. */
static float sine_tbl[SINE_SIZE], env_tbl[NUM_FORMANTS][MONK_GRAIN], asp_tbl[MONK_GRAIN];
static int tables_ready;

static void build_tables(void)
{
    if (tables_ready)
        return;
    for (uint32_t i = 0; i < SINE_SIZE; i++)
        sine_tbl[i] = fm_sin_turns((float)i / SINE_SIZE);
    int f;
    {   /* the grain window: a short cosine attack, flat, a cosine release that stops short of zero */
        float sr = FS, bw = 50.0f * FM_PI, p1 = sr * 0.000202f, p2 = sr * 0.000263f;
        uint32_t attack_len = (uint32_t)(sr * 0.0018f), release_start = (uint32_t)(sr * 0.013f);
        uint32_t release_len = (uint32_t)(sr * 0.007f), decay_len = MONK_GRAIN * 4u, n;
        for (n = 0; n < MONK_GRAIN; n++) {
            float w = 1.0f, s1, s2, d1 = 0.0f, d2 = 0.0f;
            uint32_t i1, i2;
            if (n < attack_len)
                w = 0.5f * (1.0f - fm_cosf(FM_PI * (float)n / (float)attack_len));
            if (n >= release_start)
                w = 0.5f * (1.0f - fm_cosf(FM_PI * (float)(release_len + n) / (float)release_len));
            for (f = 0; f < NUM_FORMANTS; f++) {
                uint32_t di = (uint32_t)(FORMANT_BW[f] * (float)n / 50.0f);
                env_tbl[f][n] = (di < decay_len ? fm_expf(-bw * (float)di / sr) : 0.0f) * w;
            }
            s1 = fm_sinf(FM_TWO_PI * (float)n / p1);   /* the breath: two inharmonic decaying sines */
            i1 = (uint32_t)((float)n * 3.0f);
            if (i1 < decay_len)
                d1 = fm_expf(-bw * (float)i1 / sr);
            s2 = fm_sinf(FM_TWO_PI * (float)n / p2);
            i2 = (uint32_t)((float)n * 3.5f);
            if (i2 < decay_len)
                d2 = fm_expf(-bw * (float)i2 / sr);
            asp_tbl[n] = (s1 * d1 + s2 * d2) * w;
        }
    }

    tables_ready = 1;
}

/* The five source vowels are interpolated on the donor's Catmull-Rom curve. */
static float lookup_formant(int f, float vowel)
{
    const float *p = FORMANT_FREQS[f];
    const float position = 4.0f * vowel;
    const int s = position < 4.0f ? (int)position : 3;
    const float t = position - (float)s, t2 = t * t, t3 = t2 * t;
    const float p0 = p[s > 0 ? s - 1 : 0], p1 = p[s], p2 = p[s + 1], p3 = p[s < 3 ? s + 2 : 4];
    return 0.5f * ((2.0f * p1) + (-p0 + p2) * t +
                   (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
                   (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
}

static void compute_grain(monk_voice_t *v, const monk_patch_t *p)
{
    float step[NUM_FORMANTS];
    const float size = p->head * 0.5f + 0.75f;
    for (int f = 0; f < NUM_FORMANTS; f++)
        step[f] = lookup_formant(f, p->vowel) * size * ((float)SINE_SIZE / FS);
    for (uint32_t i = 0; i < MONK_GRAIN; i++) {
        const float fi = (float)i;
        v->grain[i] = sine_tbl[(uint32_t)(fi * step[0]) & (SINE_SIZE - 1)] * env_tbl[0][i] +
                      sine_tbl[(uint32_t)(fi * step[1]) & (SINE_SIZE - 1)] * env_tbl[1][i] +
                      sine_tbl[(uint32_t)(fi * step[2]) & (SINE_SIZE - 1)] * env_tbl[2][i] +
                      asp_tbl[i] * p->breath;
    }
    v->vowel = p->vowel;
    v->head = p->head;
    v->breath = p->breath;
}

static void overlap_add(monk_voice_t *v)
{
    const uint32_t tail = MONK_OVERLAP - v->read;
    const uint32_t first = tail < MONK_GRAIN ? tail : MONK_GRAIN;
    for (uint32_t i = 0; i < first; i++)
        v->overlap[v->read + i] += v->grain[i];
    for (uint32_t i = first; i < MONK_GRAIN; i++)
        v->overlap[i - first] += v->grain[i];
}

void monk_voice_start(monk_voice_t *v, uint32_t seed)
{
    build_tables();
    memset(v, 0, sizeof *v);
    v->rng = 12345u + seed * 7919u;
    v->vibrato_phase = (float)(v->rng & (SINE_SIZE - 1));
    v->jitter = 5.0f;
    v->pitch_step = 0x7FFFFFFF;
    v->vowel = -1.0f;
    v->ready = 1;
}

/* Keep the donor's 32 pitch steps per semitone. A fractional countdown keeps
 * the average period in tune with the other engines at short periods. */
static float note_period(monk_voice_t *v, float note)
{
    const int step = (int)(32.0f * note);
    if (step != v->pitch_step) {
        v->pitch_step = step;
        v->period = FS / (8.17579892f * fm_exp2f((float)step / 384.0f));
    }
    return v->period;
}

void monk_voice_render(monk_voice_t *v, const monk_patch_t *p, float pitch, float *out, uint32_t n)
{
    const float rate_scale = fm_exp2f((p->rate * 2.0f - 1.0f) * 2.0f);
    const float pitch_gain = fm_clampf(pitch * (-1.0f / 72.0f) + 2.15f, 0.30f, 2.0f);
    const int changed = p->vowel != v->vowel || p->head != v->head || p->breath != v->breath;
    if (changed)
        compute_grain(v, p);
    for (uint32_t i = 0; i < n; i++) {
        const float vibrato = p->vibrato * sine_tbl[(uint32_t)v->vibrato_phase];
        if (v->trigger_in <= 0.0f) {
            overlap_add(v);
            v->trigger_in += note_period(v, pitch + vibrato);
        }
        v->trigger_in -= 1.0f;
        v->vibrato_phase += (p->vibrato * 0.2f + 1.0f) * v->jitter * rate_scale * ((float)SINE_SIZE / FS);
        if (v->vibrato_phase >= SINE_SIZE)
            v->vibrato_phase -= SINE_SIZE;
        if (++v->jitter_counter >= 4586u) {
            v->jitter_counter = 0;
            v->rng = v->rng * 1103515245u + 12345u;
            v->jitter = 5.0f + (float)((v->rng >> 16) & 0x7fffu) / 16384.0f;
        }
        out[i] = fm_tanhf(v->overlap[v->read] * pitch_gain);
        v->overlap[v->read] = 0.0f;
        v->read = (v->read + 1u) & (MONK_OVERLAP - 1u);
    }
}
