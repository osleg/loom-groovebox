/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff voice bridges for the dry FiMba-1 and MONK-1 models. */
#include "thirdparty/kalimba.h"
#include "thirdparty/monk.h"

#define MONK_POLY 6u

typedef union {
    fimba_voice_t fimba[NVOICE];
    monk_voice_t monk[MONK_POLY];
} dock_modeled_part_t;

static float modeled_pitch(const vmod_t *m)
{
    return (float)m->pitch16 * (1.0f / 16.0f) + (float)m->fine * (17.31234f / 4096.0f);
}

static void modeled_mix(const float *samples, float gain, int32_t *out, uint32_t n, const vmod_t *m)
{
    for (uint32_t i = 0; i < n; i++) {
        const float s = samples[i] * gain;
        const int32_t q = s > 32767.0f ? 32767 : s < -32767.0f ? -32767 : (int32_t)s;
        out[i] += voice_amp2(q, m, i);
    }
}

static fimba_patch_t fimba_controls(const track_t *t, const vmod_t *m)
{
    const int16_t *p = t->p;
    const int tone = p[P_E3] + (m ? m->cutoff >> 9 : 0);
    return (fimba_patch_t){
        (uint8_t)p[P_E0], (uint8_t)p[P_E1], (uint8_t)p[P_E2], (uint8_t)clamp(tone, 0, 127),
        (uint8_t)p[P_E4], (uint8_t)p[P_E5], (uint8_t)p[P_E6], (uint8_t)p[P_E7],
    };
}

static void fimba_note_on(track_t *t, voice_t *v)
{
    dock_modeled_part_t *part = (dock_modeled_part_t *)eng_part(t);
    fimba_voice_t *f = &part->fimba[v - t->v];
    const fimba_patch_t patch = fimba_controls(t, 0);
    if (!v->env && !v->env_out)
        f->ready = 0;
    fimba_pluck(f, &patch, v->note, v->vel);
}

static void fimba_render_voice(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    dock_modeled_part_t *part = (dock_modeled_part_t *)eng_part(t);
    fimba_voice_t *f = &part->fimba[v - t->v];
    const fimba_patch_t patch = fimba_controls(t, m);
    float samples[CTL];
    if (!f->ready)
        return;
    fimba_render(f, &patch, modeled_pitch(m), samples, n);
    modeled_mix(samples, 18400.0f, out, n, m);
}

static void monk_note_on(track_t *t, voice_t *v)
{
    dock_modeled_part_t *part = (dock_modeled_part_t *)eng_part(t);
    const uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= MONK_POLY)
        return;
    monk_voice_t *voice = &part->monk[slot];
    if (!voice->ready || (!v->env && !v->env_out))
        monk_voice_start(voice, slot + (uint32_t)(t - trk) * MONK_POLY);
}

static void monk_render_voice(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    dock_modeled_part_t *part = (dock_modeled_part_t *)eng_part(t);
    const uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= MONK_POLY || !part->monk[slot].ready)
        return;
    const int16_t *p = t->p;
    const int32_t vowel = clamp(p[P_E0] + (m->shape >> 8) - 64, 0, 127);
    const float scale = 1.0f / 127.0f;
    const monk_patch_t patch = {vowel * scale, p[P_E1] * scale, p[P_E2] * scale, p[P_E3] * scale, p[P_E4] * scale};
    float samples[CTL];
    monk_voice_render(&part->monk[slot], &patch, modeled_pitch(m), samples, n);
    modeled_mix(samples, 12400.0f, out, n, m);
}

static const char *const FIMBA_MATERIAL_NAMES[] = {"STEEL", "BRASS", "BRONZE", "ALUMIN", "BAMBOO", "GLASS"};
static const char *const FIMBA_BODY_NAMES[] = {"BARE", "BOARD", "BOX", "GOURD"};

static const preset_t FIMBA_PRESETS[] = {
    {"THUMB TINES", {FIMBA_STEEL, 49, 62, 69, FIMBA_BOX, 0, 8, 7}, {0, 67, 115, 74}, 0, 0, TAG(PT_PLUCK)},
    {"HOLLOW BAMBOO", {FIMBA_BAMBOO, 60, 65, 48, FIMBA_GOURD, 13, 24, 47},
        {0, 55, 114, 58}, 0, 0, TAG(PT_PLUCK)},
    {"GLASS DROPS", {FIMBA_GLASS, 91, 60, 99, FIMBA_BARE, 0, 0, 0},
        {0, 74, 116, 80}, 0, 0, FX(0, 0, 0, 25), TAG(PT_KEYS)},
    {"RATTLE BOARD", {FIMBA_BRONZE, 73, 54, 78, FIMBA_BOARD, 91, 44, 0},
        {0, 64, 110, 66}, 0, 0, TAG(PT_PLUCK)},
};

static const preset_t MONK_PRESETS[] = {
    {"OPEN VOWEL", {63, 55, 19, 15, 60}, {13, 66, 115, 64}, 0, 0, TAG(PT_KEYS)},
    {"LOW CHANT", {24, 24, 11, 11, 40}, {33, 73, 119, 76}, 0, 0, FX(0, 0, 0, 18), TAG(PT_PAD)},
    {"AIR CHOIR", {94, 63, 72, 24, 53}, {46, 82, 106, 95}, 0, 0, FX(0, 22, 0, 25), TAG(PT_PAD)},
    {"BRIGHT SOLO", {113, 97, 33, 38, 72}, {5, 55, 118, 50}, 0, 1, TAG(PT_LEAD)},
};

static const engine_t ENG_FIMBA = {
    .name = "FIMBA", .page_title = {"TINES", "BODY"},
    .edit = {
        {"MATERIAL", FIMBA_MATERIAL_NAMES, 0, FIMBA_MATERIALS - 1, FIMBA_STEEL, F_ENUM, 0},
        {"HARDNESS", 0, 0, 127, 49, F_PCT, 0},
        {"DECAY", 0, 0, 127, 62, F_PCT, 0},
        {"TONE", 0, 0, 127, 69, F_PCT, 0},
        {"BODY", FIMBA_BODY_NAMES, 0, FIMBA_BODIES - 1, FIMBA_BOX, F_ENUM, 0},
        {"BUZZ", 0, 0, 127, 0, F_PCT, 0},
        {"WEAR", 0, 0, 127, 8, F_PCT, 0},
        {"HOLE", 0, 0, 127, 7, F_PCT, 0},
    },
    .presets = FIMBA_PRESETS, .npresets = NELEM(FIMBA_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3},
    .poly = NVOICE, .note_on = fimba_note_on, .render = fimba_render_voice,
};

static const engine_t ENG_MONK = {
    .name = "MONK", .page_title = {"SINGER", "VIBRATO"},
    .edit = {
        {"VOWEL", 0, 0, 127, 63, F_PCT, 0},
        {"HEAD", 0, 0, 127, 55, F_PCT, 0},
        {"BREATH", 0, 0, 127, 19, F_PCT, 0},
        {"VIBRATO", 0, 0, 127, 15, F_PCT, 0},
        {"RATE", 0, 0, 127, 60, F_PCT, 0},
    },
    .presets = MONK_PRESETS, .npresets = NELEM(MONK_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3},
    .poly = MONK_POLY, .note_on = monk_note_on, .render = monk_render_voice,
};
