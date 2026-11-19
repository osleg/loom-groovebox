/* SPDX-License-Identifier: GPL-3.0-only
 * Android Skiff bridge for OberVave's two-oscillator synthesizer voice. */
#include "thirdparty/obv_voice.h"

enum { OBV_DUAL, OBV_SYNC, OBV_CROSS, OBV_WIDE, OBV_NOISE };

typedef struct {
    obv_voice_t voice[NVOICE];
} obervave_part_t;

static obv_voice_t *obervave_slot(track_t *t, voice_t *v)
{
    return &((obervave_part_t *)eng_part(t))->voice[v - t->v];
}

static void obervave_note_on(track_t *t, voice_t *v)
{
    obv_voice_t *o = obervave_slot(t, v);
    if (!v->env) {
        *o = (obv_voice_t){0};
        o->noise = ((uint32_t)(v - t->v) + 1u) * 0x9e3779b9u;
    }
}

static void obervave_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    const int16_t *p = t->p;
    int32_t pitch2 = clamp(m->pitch16 + p[P_E2] * 16, 0, 2047);
    int32_t circuit = p[P_E3], fine2 = m->fine + (circuit == OBV_WIDE ? 26 : 0);
    uint32_t inc2 = pitch_inc(pitch2), at = 0;
    uint32_t pw = (uint32_t)clamp(m->shape >> 8, 2, 125);
    obv_voice_params_t settings = {
        .inc1 = m->inc, .inc2 = inc2 + (uint32_t)((int32_t)(inc2 >> 12) * fine2),
        .pw1 = (pw << 25) + (1u << 24), .pw2 = 1u << 31,
        .wave1 = (uint8_t)p[P_E0], .wave2 = (uint8_t)p[P_E1],
        .sync = circuit == OBV_SYNC, .xmod = circuit == OBV_CROSS ? 82 : 0,
        .noise = circuit == OBV_NOISE ? 84 : 0,
        .filter = (uint8_t)p[P_E6], .morph = (uint8_t)p[P_E7],
        .drive = p[P_E6] == OBV_SEM ? 0 : (uint8_t)p[P_E7], .resonance = (uint8_t)p[P_E5],
        .cutoff_q8 = (p[P_E4] + 8) * 256 + m->cutoff + (m->pitch16 - 960) * 5,
    };
    obv_voice_t *o = obervave_slot(t, v);
    while (at < n) {
        int32_t samples[32];
        uint32_t i, count = n - at;
        if (count > 32u) count = 32u;
        obv_voice_render(o, &settings, samples, count);
        for (i = 0; i < count; i++)
            out[at + i] += voice_amp2(samples[i], m, at + i);
        at += count;
    }
}

static const param_desc_t *obervave_desc(const track_t *t, uint32_t field)
{
    static const param_desc_t morph = {"MORPH", 0, 0, 127, 57, F_PCT, 0};
    return field == 7u && t->p[P_E6] == OBV_SEM ? &morph : 0;
}

static const char *const OBV_WAVE_NAMES[] = {"OFF", "SAW", "PULSE", "SAW+PLS"};
static const char *const OBV_CIRCUIT_NAMES[] = {"DUAL", "SYNC", "X-MOD", "WIDE", "NOISE"};
static const char *const OBV_FILTER_NAMES[] = {"LP 12", "HP 12", "BP 12", "NOTCH", "LP 24", "SEM"};
static const preset_t OBERVAVE_PRESETS[] = {
    {"WIDE BRASS", {OBV_SAW, OBV_SAW, 0, OBV_WIDE, 80, 39, OBV_LP12, 57}, {10, 66, 94, 30}, 0, 0, TAG(PT_KEYS)},
    {"HARD RIBBON", {OBV_PULSE, OBV_SAW, 12, OBV_SYNC, 95, 67, OBV_LP24, 76}, {1, 66, 104, 31}, 0, 1, TAG(PT_LEAD)},
    {"VELVET PAD", {OBV_SAW, OBV_PULSE, 0, OBV_WIDE, 69, 22, OBV_SEM, 32}, {45, 83, 110, 63}, 0, 0, TAG(PT_PAD)},
    {"CROSS GLASS", {OBV_SAW, OBV_PULSE, 7, OBV_CROSS, 90, 70, OBV_BP12, 55}, {0, 43, 62, 45}, 0, 0, TAG(PT_PLUCK)},
};

static const engine_t ENG_OBERVAVE = {
    .name = "OBERVAVE",
    .page_title = {"OSCILLATORS", "FILTER"},
    .edit = {
        {"OSC 1", OBV_WAVE_NAMES, 0, 3, OBV_SAW, F_ENUM, 0},
        {"OSC 2", OBV_WAVE_NAMES, 0, 3, OBV_SAW, F_ENUM, 0},
        {"OSC 2 TUNE", 0, -24, 24, 0, F_SEMI, 0},
        {"CIRCUIT", OBV_CIRCUIT_NAMES, 0, 4, OBV_WIDE, F_ENUM, 0},
        {"CUTOFF", 0, 0, 127, 80, F_PCT, 0},
        {"RESO", 0, 0, 127, 39, F_PCT, 0},
        {"FILTER", OBV_FILTER_NAMES, 0, 5, OBV_LP12, F_ENUM, 0},
        {"DRIVE", 0, 0, 127, 57, F_PCT, 0},
    },
    .presets = OBERVAVE_PRESETS, .npresets = NELEM(OBERVAVE_PRESETS),
    .knob = {P_E4, P_E5, P_E6, P_E7},
    .poly = NVOICE, .note_on = obervave_note_on, .render = obervave_render, .desc = obervave_desc,
};
