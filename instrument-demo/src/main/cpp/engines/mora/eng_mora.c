/* SPDX-License-Identifier: GPL-3.0-only */
#include "thirdparty/fvoice.h"
#include "thirdparty/mora.h"

#define MORA_POLY 6u

typedef struct {
    FvVoice lead, harmony;
    uint8_t ready, mora, note;
} mora_slot_t;

typedef struct {
    mora_slot_t slots[MORA_POLY];
} mora_part_t;

/* Keep names in the donor's persistent mora order, including its default A at 0. */
static const char *const MORA_NAMES[] = {
    "AUTO A", "A", "I", "U", "E", "O",
    "KA", "KI", "KU", "KE", "KO", "SA", "SHI", "SU", "SE", "SO",
    "TA", "CHI", "TSU", "TE", "TO", "NA", "NI", "NU", "NE", "NO",
    "HA", "HI", "FU", "HE", "HO", "MA", "MI", "MU", "ME", "MO",
    "YA", "YU", "YO", "RA", "RI", "RU", "RE", "RO", "WA", "WO", "N",
    "GA", "GI", "GU", "GE", "GO", "ZA", "JI", "ZU", "ZE", "ZO",
    "DA", "DE", "DO", "BA", "BI", "BU", "BE", "BO", "PA", "PI", "PU", "PE", "PO",
    "SHA", "JA", "RYA",
};
_Static_assert(NELEM(MORA_NAMES) == MORA_N, "mora labels must cover the donor table");

static const char *const MORA_CHARACTER[] = {"DEEP", "LOW", "CLEAR", "YOUNG", "TINY"};
static const char *const MORA_HARMONY[] = {"OFF", "MINOR 3", "MAJOR 3", "FOURTH", "FIFTH", "OCT UP", "OCT DOWN"};
static const int8_t MORA_INTERVAL[] = {0, 3, 4, 5, 7, 12, -12};

static int32_t mora_pitch_units(int32_t note)
{
    return (note * 1024 + 1) / 3; /* the donor's 4096 units per octave */
}

static void mora_settings(FvVoice *voice, const int16_t *p)
{
    voice->chr = (uint8_t)p[P_E1];
    voice->porta = (uint16_t)(p[P_E7] * 4);
    voice->breath = (uint8_t)p[P_E6];
    voice->fry = (uint8_t)p[P_E4];
    voice->whisper = (uint8_t)p[P_E5];
    voice->vib_depth = 22;
}

static void mora_trigger(FvVoice *voice, uint8_t note, uint8_t syllable)
{
    /* Skiff's amplitude ramp already applies the note velocity. */
    fv_note_on_mora(voice, note, (uint8_t)mora_cons(syllable), (uint8_t)mora_vowel(syllable), 112);
}

static void mora_note_on(track_t *t, voice_t *v)
{
    mora_part_t *part = eng_part(t);
    mora_slot_t *slot = &part->slots[v - t->v];
    const int16_t *p = t->p;
    if (!slot->ready || (!v->env && !v->env_out)) {
        fv_init(&slot->lead);
        fv_init(&slot->harmony);
        const uint32_t voice_seed = ((uint32_t)(t - trk) * MORA_POLY + (uint32_t)(v - t->v) + 1u) * 0x45d9f3bu;
        slot->lead.rng ^= voice_seed;
        slot->harmony.rng = 0x2545f491u ^ voice_seed;
        slot->harmony.noise ^= voice_seed;
        slot->ready = 1;
    }
    slot->mora = (uint8_t)p[P_E0];
    slot->note = v->note;
    mora_settings(&slot->lead, p);
    mora_trigger(&slot->lead, v->note, slot->mora);
    if (p[P_E2]) {
        mora_settings(&slot->harmony, p);
        slot->harmony.transpose = MORA_INTERVAL[p[P_E2]];
        mora_trigger(&slot->harmony, v->note, slot->mora);
    } else if (slot->harmony.gated) {
        fv_note_off(&slot->harmony);
    }
}

static void mora_render_voice(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    mora_part_t *part = eng_part(t);
    mora_slot_t *slot = &part->slots[v - t->v];
    if (!slot->ready)
        return;

    const int16_t *p = t->p;
    FvVoice *lead = &slot->lead;
    FvVoice *harmony = &slot->harmony;
    const int32_t pitch_delta = ((m->pitch16 - v->note * 16) * 64) / 3 + m->fine * 3 / 2;
    mora_settings(lead, p);
    if (slot->note != v->note) {
        /* Stock mono legato can change pitch without starting a new note. */
        slot->note = v->note;
        lead->ptgt = mora_pitch_units(v->note);
    }
    lead->tune = (int16_t)clamp(pitch_delta, -32768, 32767);
    if (!v->gate && lead->gated)
        fv_note_off(lead);
    int32_t samples[CTL * 2] = {0};
    fv_render(lead, samples, n);

    if (p[P_E2]) {
        const int32_t base_note = v->note;
        const int32_t harmony_note = clamp(base_note + MORA_INTERVAL[p[P_E2]], 0, 127);
        mora_settings(harmony, p);
        /* Put interval edits in the pitch target so the harmony follows its glide. */
        harmony->ptgt = mora_pitch_units(harmony_note);
        harmony->tune = lead->tune;
        if (v->gate && !harmony->gated) {
            harmony->transpose = MORA_INTERVAL[p[P_E2]];
            mora_trigger(harmony, v->note, slot->mora);
        }
    }
    if ((!v->gate || !p[P_E2]) && harmony->gated)
        fv_note_off(harmony);
    int32_t backing[CTL * 2] = {0};
    if (harmony->gated || harmony->env)
        fv_render(harmony, backing, n);

    for (uint32_t i = 0; i < n; i++) {
        const int32_t sample = samples[i * 2] + backing[i * 2] * p[P_E3] / 127;
        out[i] += voice_amp2(clamp(sample, -32767, 32767), m, i);
    }
}

static const preset_t MORA_PRESETS[] = {
    {"CLEAR KA", {6, 2, 0, 55, 0, 0, 12, 15}, {0, 70, 119, 66}, 0, 0, TAG(PT_KEYS)},
    {"FIFTH ECHO", {38, 3, 4, 68, 0, 0, 15, 27}, {2, 72, 117, 76}, 0, 0, FX(0, 0, 21, 39), TAG(PT_PAD)},
    {"LOW FRY", {40, 0, 0, 55, 82, 0, 12, 10}, {0, 65, 120, 62}, 0, 1, TAG(PT_LEAD)},
    {"TINY WHISPER", {12, 4, 2, 38, 0, 91, 36, 23}, {2, 73, 119, 71}, 0, 0, FX(0, 0, 0, 31), TAG(PT_PAD)},
    {"LOW HUM", {46, 1, 5, 33, 18, 0, 12, 24}, {0, 73, 122, 75}, 0, 0, TAG(PT_PAD)},
};

static const engine_t ENG_MORA = {
    .name = "MORA", .page_title = {"SYLLABLE", "VOICE"},
    .edit = {
        {"MORA", MORA_NAMES, 0, MORA_N - 1, 6, F_ENUM, 0},
        {"CHARACTER", MORA_CHARACTER, 0, NELEM(MORA_CHARACTER) - 1, 2, F_ENUM, 0},
        {"HARMONY", MORA_HARMONY, 0, NELEM(MORA_HARMONY) - 1, 0, F_ENUM, 0},
        {"HARM MIX", 0, 0, 127, 55, F_PCT, 0},
        {"FRY", 0, 0, 100, 0, F_INT, 0},
        {"WHISPER", 0, 0, 100, 0, F_INT, 0},
        {"BREATH", 0, 0, 127, 12, F_PCT, 0},
        {"GLIDE", 0, 0, 127, 15, F_PCT, 0},
    },
    .presets = MORA_PRESETS, .npresets = NELEM(MORA_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3},
    .poly = MORA_POLY, .note_on = mora_note_on, .render = mora_render_voice,
};
