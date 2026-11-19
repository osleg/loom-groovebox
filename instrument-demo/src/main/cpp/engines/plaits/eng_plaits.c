/* SPDX-License-Identifier: GPL-3.0-only */
#include "plaits_bridge.h"

#define MACRO_POLY 4u

typedef struct {
    plaits_voice_t voice[MACRO_POLY];
    uint8_t ready[MACRO_POLY];
} plaits_part_t;

static void macro_note_on(track_t *t, voice_t *v)
{
    plaits_part_t *part = (plaits_part_t *)eng_part(t);
    const uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= MACRO_POLY)
        return;
    const int reset = !part->ready[slot] || (!v->env && !v->env_out);
    const uint32_t seed = 0x21u + ((uint32_t)(t - trk) * MACRO_POLY + slot) * 7919u;
    app_plaits_note(&part->voice[slot], seed, reset);
    part->ready[slot] = 1;
}

static void macro_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    static const float gains[MACRO_MODEL_COUNT] = {
        18500.0f, 15000.0f, 14500.0f, 16500.0f, 19800.0f, 16600.0f, 18500.0f, 15900.0f,
        17200.0f, 13500.0f, 19000.0f, 19000.0f, 19000.0f, 15200.0f, 18000.0f, 12500.0f,
        15700.0f, 16300.0f, 17000.0f, 12500.0f, 14000.0f, 16000.0f, 15300.0f, 14000.0f,
    };
    plaits_part_t *part = (plaits_part_t *)eng_part(t);
    const uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= MACRO_POLY || !part->ready[slot])
        return;

    const int16_t *p = t->p;
    const uint8_t model = (uint8_t)clamp(p[P_E0], 0, MACRO_MODEL_COUNT - 1);
    const float scale = 1.0f / 127.0f;
    const plaits_controls_t patch = {
        .note = (float)m->pitch16 * (1.0f / 16.0f) + (float)m->fine * (17.31234f / 4096.0f),
        .harmonics = p[P_E1] * scale,
        .timbre = clamp(p[P_E2] + (m->cutoff >> 9), 0, 127) * scale,
        .morph = clamp(p[P_E3] + (m->shape >> 8) - 64, 0, 127) * scale,
        .mix = p[P_E4] * scale,
        .velocity = v->vel * scale,
        .model = model, .gate = v->gate,
    };
    float samples[CTL];
    app_plaits_render(&part->voice[slot], &patch, samples, n);
    for (uint32_t i = 0; i < n; i++) {
        const float s = samples[i] * gains[model];
        const int32_t q = s > 32767.0f ? 32767 : s < -32767.0f ? -32767 : (int32_t)s;
        out[i] += voice_amp2(soft_knee(q, 18000), m, i);
    }
}

static const char *const MACRO_MODEL_NAMES[] = {
    "ANALOG", "FOLD", "FM", "ADDITIVE", "SWARM", "NOISE", "STRING", "MODAL",
    "ANALOG VCF", "PHASE DIST", "6OP KEYS", "6OP BELLS", "6OP PADS", "TERRAIN", "STRING MACHINE", "CHIP",
    "GRAIN", "WAVETABLE", "CHORD", "SPEECH", "PARTICLE", "KICK", "SNARE", "HI-HAT",
};

static const preset_t MACRO_PRESETS[] = {
    {"SILK STACK", {MACRO_VIRTUAL_ANALOG, 44, 54, 67, 8}, {4, 56, 102, 52}, 0, 0, TAG(PT_KEYS)},
    {"FOLD BASS", {MACRO_WAVEFOLD, 31, 74, 52, 18}, {0, 62, 102, 37}, 0, 1, TAG(PT_BASS)},
    {"FM EMBER", {MACRO_FM, 43, 35, 61, 1}, {0, 78, 93, 68}, 0, 0, TAG(PT_KEYS)},
    {"DRAWBAR LITE", {MACRO_ADDITIVE, 63, 71, 41, 6}, {3, 74, 118, 54}, 0, 0, TAG(PT_KEYS)},
    {"SWARM CLOUD", {MACRO_SWARM, 45, 29, 87, 34}, {38, 68, 109, 89}, 0, 0, FX(0, 20, 0, 16), TAG(PT_PAD)},
    {"SHORTWAVE", {MACRO_NOISE, 106, 76, 32, 10}, {22, 59, 97, 53}, 0, 0, TAG(PT_FX)},
    {"PLUCKED COIL", {MACRO_STRING, 54, 85, 50, 3}, {0, 65, 111, 78}, 0, 0, TAG(PT_PLUCK)},
    {"BRONZE MODES", {MACRO_MODAL, 67, 63, 43, 12}, {0, 75, 112, 83}, 0, 0, FX(0, 0, 0, 19), TAG(PT_PLUCK)},
    {"AMBER FILTER", {MACRO_VCF, 58, 76, 31, 9}, {1, 61, 112, 52}, 0, 0, TAG(PT_BASS)},
    {"GLASS EDGE", {MACRO_PHASE_DISTORTION, 51, 67, 37, 20}, {2, 62, 109, 53}, 0, 0, TAG(PT_LEAD)},
    {"TINE LOOP", {MACRO_SIX_OP_KEYS, 12, 56, 55, 0}, {0, 87, 115, 66}, 0, 0, TAG(PT_KEYS)},
    {"PEBBLE BELLS", {MACRO_SIX_OP_BELLS, 67, 67, 66, 0}, {0, 75, 114, 76}, 0, 0, TAG(PT_PLUCK)},
    {"AFTERGLOW", {MACRO_SIX_OP_PADS, 104, 57, 51, 0}, {19, 72, 120, 88}, 0, 0, TAG(PT_PAD)},
    {"CONTOUR", {MACRO_WAVE_TERRAIN, 83, 71, 79, 11}, {5, 60, 110, 57}, 0, 0, TAG(PT_LEAD)},
    {"VELVET TAPE", {MACRO_STRING_MACHINE, 69, 79, 83, 35}, {30, 70, 114, 82}, 0, 0, TAG(PT_PAD)},
    {"TIN ARPEGGIO", {MACRO_CHIPTUNE, 83, 68, 40, 8}, {1, 61, 105, 41}, 0, 0, TAG(PT_LEAD)},
    {"CLOUD SEED", {MACRO_GRAIN, 65, 58, 82, 18}, {22, 68, 114, 71}, 0, 0, TAG(PT_PAD)},
    {"RASTER", {MACRO_WAVETABLE, 47, 92, 59, 12}, {4, 66, 109, 65}, 0, 0, TAG(PT_KEYS)},
    {"SLATE CHORD", {MACRO_CHORD, 59, 37, 72, 14}, {15, 68, 117, 70}, 0, 0, TAG(PT_KEYS)},
    {"MOTOR MOUTH", {MACRO_SPEECH, 40, 64, 59, 12}, {0, 78, 112, 55}, 0, 0, TAG(PT_FX)},
    {"DUST RING", {MACRO_PARTICLE, 83, 85, 48, 10}, {2, 67, 114, 79}, 0, 0, TAG(PT_FX)},
    {"LOW IMPACT", {MACRO_KICK, 37, 43, 81, 11}, {0, 68, 105, 46}, 0, 0, TAG(PT_DRUM)},
    {"WIRE SNAP", {MACRO_SNARE, 49, 71, 59, 27}, {0, 61, 105, 47}, 0, 0, TAG(PT_DRUM)},
    {"SHEET TICK", {MACRO_HI_HAT, 64, 91, 37, 24}, {0, 53, 99, 38}, 0, 0, TAG(PT_DRUM)},
};

static const engine_t ENG_MACRO = {
    .name = "MACRO", .page_title = {"MODEL", "OUTPUT"},
    .edit = {
        {"MODEL", MACRO_MODEL_NAMES, 0, MACRO_MODEL_COUNT - 1, MACRO_VIRTUAL_ANALOG, F_ENUM, 0},
        {"HARMONICS", 0, 0, 127, 44, F_PCT, 0},
        {"TIMBRE", 0, 0, 127, 54, F_PCT, 0},
        {"MORPH", 0, 0, 127, 67, F_PCT, 0},
        {"OUT MIX", 0, 0, 127, 8, F_PCT, 0},
    },
    .presets = MACRO_PRESETS, .npresets = NELEM(MACRO_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3},
    .poly = MACRO_POLY, .note_on = macro_note_on, .render = macro_render,
};
