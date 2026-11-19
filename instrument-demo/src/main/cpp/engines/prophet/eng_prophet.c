/* SPDX-License-Identifier: GPL-3.0-only */
#include <math.h>
#include "p5_coefficients.h"

typedef struct {
    uint32_t phase[2], coeff_valid;
    int32_t noise;
    float z[4], triangle, sync_tail;
    float coeff_cut, coeff_k, coeff_g[4], coeff_inv;
} p5_voice_t;

typedef struct { p5_voice_t voice[NVOICE]; } prophet_part_t;

static inline float p5_limit(float v, float lo, float hi)
{
    return v < lo ? lo : v > hi ? hi : v;
}

#include "p5_core.inc"

static p5_voice_t *prophet_voice(track_t *t, const voice_t *v)
{
    return &((prophet_part_t *)eng_part(t))->voice[v - t->v];
}

static void prophet_strike(track_t *t, voice_t *v)
{
    p5_voice_t *s = prophet_voice(t, v);
    if (s->noise) return;
    s->noise = 0x517953 + (int32_t)(t - trk) * 7919 + (int32_t)(v - t->v) * 127;
    s->phase[1] = noise32(&s->noise);
}

static void prophet_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    p5_voice_t *s = prophet_voice(t, v);
    const int16_t *p = t->p;
    uint32_t a_wave = 1u + (uint32_t)p[P_E0], b_wave = 1u + (uint32_t)p[P_E1];
    uint32_t db = cents_inc(m->pitch16, p[P_E2], m->fine);
    float depth = p[P_E3] * (1.0f / 127.0f), res = p[P_E5];
    float cutoff = p[P_E4] + m->cutoff * (1.0f / 256.0f) + (m->pitch16 - 960) * (1.0f / 120.0f);
    uint32_t pw = p5_pw(0.5f + (m->shape - (64 << 8)) * (1.0f / 65536.0f));
    uint32_t i;
    for (i = 0; i < n; i++) {
        float triangle = 0.0f;
        float b = p5_wave(s->phase[1], db, pw, b_wave & 1u, b_wave & 2u, b_wave & 4u, &s->triangle);
        uint32_t da = p5_inc(m->inc, b * depth * 0.9f);
        float a = p5_wave(s->phase[0], da, pw, a_wave & 1u, a_wave & 2u, 0, &triangle) + s->sync_tail;
        uint32_t nb = s->phase[1] + db;
        uint32_t na = s->phase[0] + da;
        s->sync_tail = 0.0f;
        if (p[P_E6] && nb < s->phase[1]) {
            float edge = p5_sync_fraction(0u - s->phase[1], db);
            float rem = 1.0f - edge;
            uint32_t at = s->phase[0] + (uint32_t)((float)da * edge);
            float before = p5_wave(at, da, pw, a_wave & 1u, a_wave & 2u, 0, &triangle);
            float after = p5_wave(0, da, pw, a_wave & 1u, a_wave & 2u, 0, &triangle);
            float jump = (after - before) * 0.5f;
            a += jump * rem * rem;
            s->sync_tail = -jump * edge * edge;
            na = (uint32_t)((float)da * rem);
        }
        s->phase[0] = na;
        s->phase[1] = nb;
        float y = p5_filter(s, p5_fast_softclip(0.74f * a + 0.65f * b), cutoff + 48.0f * b * depth,
                            res, p[P_E7]);
        out[i] += voice_amp2((int32_t)(y * 26000.0f), m, i);
    }
}

static const char *const P5_WAVE_A[] = {"SAW", "PULSE", "SAW+PLS"};
static const char *const P5_WAVE_B[] = {"SAW", "PULSE", "SAW+PLS", "TRI"};
static const char *const P5_OFF_ON[] = {"OFF", "ON"};
static const char *const P5_FILTER[] = {"REV 1/2", "REV 3"};
static const preset_t PROPHET_PRESETS[] = {
    {"ORBIT BRASS", {0, 0, 7, 0, 78, 18, 0, 1}, {29, 73, 99, 65}, 45, 0, FX(0, 25, 25, 46), TAG(PT_LEAD)},
    {"SOFT POLY", {2, 3, -8, 4, 73, 38, 0, 0}, {50, 81, 97, 81}, 20, 0, FX(0, 47, 20, 62), TAG(PT_PAD)},
    {"SYNC GLINT", {0, 1, -63, 78, 78, 52, 1, 1}, {0, 74, 101, 35}, 16, 1, TAG(PT_LEAD)},
    {"COLD BASS", {1, 0, -50, 17, 48, 68, 0, 0}, {0, 71, 74, 43}, 42, 1, FX(20, 0, 0, 12), TAG(PT_BASS)},
};

static const engine_t ENG_PROPHET = {
    .name = "PROPHET",
    .page_title = {"OSCILLATORS", "FILTER"},
    .edit = {
        {"WAVE A", P5_WAVE_A, 0, 2, 0, F_ENUM, 0},
        {"WAVE B", P5_WAVE_B, 0, 3, 0, F_ENUM, 0},
        {"DET B", 0, -63, 63, 7, F_INT, U_CT},
        {"POLYMOD", 0, 0, 127, 0, F_PCT, 0},
        {"CUTOFF", 0, 0, 127, 78, F_PCT, 0},
        {"RESO", 0, 0, 127, 18, F_PCT, 0},
        {"SYNC", P5_OFF_ON, 0, 1, 0, F_ENUM, 0},
        {"MODEL", P5_FILTER, 0, 1, 1, F_ENUM, 0},
    },
    .presets = PROPHET_PRESETS, .npresets = NELEM(PROPHET_PRESETS),
    .knob = {P_E4, P_E5, P_E3, P_E2}, .poly = 8,
    .note_on = prophet_strike, .render = prophet_render,
};
