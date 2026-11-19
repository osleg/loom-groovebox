/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff allocation and modulation adapter for the FM-1 ESQ voice. */
#include "thirdparty/esq_voice.h"

typedef struct {
    esq_core_voice_t voice[NVOICE];
} esq_part_t;

/* DOC wave selectors follow the donor's ESQ-1 table order after OFF. */
static const char *const ESQ_SK_WAVE[] = {
    "OFF", "SAW", "BELL", "SINE", "SQUARE", "PULSE", "NOISE1", "NOISE2", "NOISE3", "BASS",
    "PIANO", "EL PNO", "VOICE1", "VOICE2", "KICK", "REED", "ORGAN", "SYNTH1", "SYNTH2", "SYNTH3",
    "FORMT1", "FORMT2", "FORMT3", "FORMT4", "FORMT5", "PULSE2", "SQR 2", "4 OCTS", "PRIME",
    "BASS 2", "E PNO2", "OCTAVE", "OCT+5",
};
static const char *const ESQ_SK_MODE[] = {"MIX", "SYNC", "AM", "SYNC+AM"};
_Static_assert(NELEM(ESQ_SK_WAVE) == ESQ_VOICE_WAVES + 1u, "ESQ selector size");

static esq_core_voice_t *esq_slot(track_t *t, const voice_t *v)
{
    return &((esq_part_t *)eng_part(t))->voice[v - t->v];
}

static void esq_note_start(track_t *t, voice_t *v)
{
    if (!v->env_out) memset(esq_slot(t, v), 0, sizeof(esq_core_voice_t));
}

static void esq_voice_mix(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    const int16_t *p = t->p;
    esq_core_voice_t *osc = esq_slot(t, v);
    int32_t layers = clamp(p[P_E3] + ((m->shape - (64 << 8)) >> 8), 0, 127);
    esq_voice_control_t control = {
        .wave = {(uint8_t)clamp(p[P_E0], 0, ESQ_VOICE_WAVES),
                 (uint8_t)clamp(p[P_E1], 0, ESQ_VOICE_WAVES),
                 (uint8_t)clamp(p[P_E2], 0, ESQ_VOICE_WAVES)},
        .level = {192u, (uint8_t)(layers * 200 / 127), (uint8_t)layers},
        .transpose = {0, (int8_t)clamp(p[P_E7], -24, 24), -12},
        /* Skiff's 1/4096 relative fine increment is about 1.08 DOC pitch units. */
        .pitch = m->pitch16 * 16 + m->fine * 277 / 256,
        .cutoff = (uint16_t)clamp((p[P_E5] * 256 + m->cutoff) * 2, 0, 255 << 8),
        .key = v->note,
        .resonance = (uint8_t)clamp(p[P_E6], 0, 31),
        .mode = (uint8_t)clamp(p[P_E4], 0, ESQ_VOICE_SYNC | ESQ_VOICE_AM),
    };
    for (uint32_t at = 0; at < n;) {
        int32_t samples[ESQ_VOICE_BLOCK];
        uint32_t count = n - at;
        if (count > ESQ_VOICE_BLOCK) count = ESQ_VOICE_BLOCK;
        esq_voice_render(osc, &control, samples, count);
        for (uint32_t i = 0; i < count; i++)
            out[at + i] += voice_amp2(clamp(samples[i], -32768, 32767), m, at + i);
        at += count;
    }
}

static const preset_t ESQ_SK_PRESETS[] = {
    {"DOC CHOIR", {12, 13, 3, 50, 0, 94, 10, 7}, {36, 86, 106, 73}, 5, 0, TAG(PT_PAD)},
    {"GLASS KEY", {11, 2, 0, 47, 0, 113, 2, 12}, {1, 78, 33, 48}, 10, 0, TAG(PT_KEYS)},
    {"SYNC REED", {1, 15, 0, 101, 1, 88, 17, 19}, {0, 75, 102, 27}, 20, 1, TAG(PT_LEAD)},
    {"AM BRASS", {4, 20, 0, 100, 2, 104, 12, 7}, {15, 74, 107, 40}, 8, 0, TAG(PT_KEYS)},
    {"AFTER HOURS", {9, 0, 5, 77, 0, 66, 19, 0}, {0, 74, 106, 30}, 27, 1, TAG(PT_BASS)},
};

static const engine_t ENG_ESQ = {
    .name = "ESQ",
    .page_title = {"DOC OSC", "FILTER"},
    .edit = {
        {"OSC 1", ESQ_SK_WAVE, 0, ESQ_VOICE_WAVES, 12, F_ENUM, 0},
        {"OSC 2", ESQ_SK_WAVE, 0, ESQ_VOICE_WAVES, 13, F_ENUM, 0},
        {"OSC 3", ESQ_SK_WAVE, 0, ESQ_VOICE_WAVES, 3, F_ENUM, 0},
        {"LAYERS", 0, 0, 127, 50, F_PCT, 0},
        {"MODE", ESQ_SK_MODE, 0, 3, 0, F_ENUM, 0},
        {"CUTOFF", 0, 0, 127, 94, F_PCT, 0},
        {"RESO", 0, 0, 31, 10, F_INT, 0},
        {"OSC 2 TUNE", 0, -24, 24, 7, F_INT, 0},
    },
    .presets = ESQ_SK_PRESETS, .npresets = NELEM(ESQ_SK_PRESETS),
    .knob = {P_E5, P_E6, P_E3, P_E7},
    .poly = 8, .note_on = esq_note_start, .render = esq_voice_mix,
};
