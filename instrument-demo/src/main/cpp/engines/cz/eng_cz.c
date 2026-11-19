/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff bridge for the Melodee uPD933 phase-distortion voice. */
#include "thirdparty/cz_voice.h"

typedef struct {
    cz_voice_t voice[NVOICE];
} cz1_part_t;

static cz_voice_t *cz1_slot(track_t *t, voice_t *v)
{
    return &((cz1_part_t *)eng_part(t))->voice[v - t->v];
}

static void cz1_note_on(track_t *t, voice_t *v)
{
    uint32_t seed = ((uint32_t)(v - t->v) + 1u) * 0x9e3779b9u;
    cz_voice_trigger(cz1_slot(t, v), seed, v->env == 0);
}

static void cz1_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    const int16_t *p = t->p;
    int32_t pitch_b = clamp(m->pitch16 + p[P_E4] * 16, 0, 2047);
    uint32_t inc_b = pitch_inc(pitch_b), at = 0;
    cz_voice_params_t settings = {
        .inc = {m->inc, inc_b + (uint32_t)((int32_t)(inc_b >> 12) * m->fine)},
        .wave = {(uint8_t)p[P_E0], (uint8_t)p[P_E1]},
        .brightness = (uint8_t)p[P_E2], .contour = (uint8_t)p[P_E3],
        .line_b = (uint8_t)p[P_E5], .window = (uint8_t)p[P_E6], .mode = (uint8_t)p[P_E7], .gate = v->gate,
        .modulation = (float)(m->cutoff + m->shape - (64 << 8)) * (1.0f / 32.0f),
    };
    cz_voice_t *s = cz1_slot(t, v);
    while (at < n) {
        int32_t samples[32];
        uint32_t i, count = n - at;
        if (count > 32u) count = 32u;
        cz_voice_render(s, &settings, samples, count);
        for (i = 0; i < count; i++)
            out[at + i] += voice_amp2(samples[i], m, at + i);
        at += count;
    }
}

static const char *const CZ_SHAPE_NAMES[] = {"SAW", "SQUARE", "PULSE", "OFF", "DOUBLE", "HALF", "RESONANT", "FOLDED"};
static const char *const CZ_WINDOW_NAMES[] = {"FLAT", "RAMP", "TRI", "HALF", "PULSE", "FOLD"};
static const char *const CZ_MODE_NAMES[] = {"MIX", "ALTERNATE", "RING", "NOISE"};

static const preset_t CZ1_PRESETS[] = {
    {"LUMEN KEYS", {0, 5, 58, 60, 12, 78, 0, CZ_MIX}, {0, 53, 82, 33}, 0, 0, TAG(PT_KEYS)},
    {"FOLDED WIRE", {1, 7, 78, 45, 0, 90, 0, CZ_ALTERNATE}, {2, 63, 101, 37}, 0, 1, TAG(PT_LEAD)},
    {"RING DROPLET", {0, 2, 62, 70, 7, 121, 2, CZ_RING}, {0, 42, 69, 52}, 0, 0, TAG(PT_PLUCK)},
    {"DUSK PULSE", {5, 6, 45, 44, -12, 60, 4, CZ_NOISE}, {27, 70, 104, 60}, 0, 0, TAG(PT_PAD)},
};

static const engine_t ENG_CZ1 = {
    .name = "CZ-1",
    .page_title = {"PHASE DIST", "LINE MIX"},
    .edit = {
        {"WAVE A", CZ_SHAPE_NAMES, 0, 7, 0, F_ENUM, 0},
        {"WAVE B", CZ_SHAPE_NAMES, 0, 7, 5, F_ENUM, 0},
        {"DCW", 0, 0, 127, 58, F_PCT, 0},
        {"DCW ENV", 0, 0, 127, 60, F_PCT, 0},
        {"B TUNE", 0, -24, 24, 12, F_SEMI, 0},
        {"B LEVEL", 0, 0, 127, 78, F_PCT, 0},
        {"WINDOW", CZ_WINDOW_NAMES, 0, 5, 0, F_ENUM, 0},
        {"MODE", CZ_MODE_NAMES, 0, 3, CZ_MIX, F_ENUM, 0},
    },
    .presets = CZ1_PRESETS, .npresets = NELEM(CZ1_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3},
    .poly = NVOICE, .note_on = cz1_note_on, .render = cz1_render,
};
