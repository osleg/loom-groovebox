/* SPDX-License-Identifier: GPL-3.0-only
 * Choir oscillator adapted from GR!MM's formant.c.
 * Copyright (C) 2026 GR!MM and Leo Kuroshita, Hügelton Instruments. */
#include "thirdparty/grimm_choir.h"

#define VOWEL_CHOIR_SINGERS 6u
#define VOWEL_CHOIR_POLY 6u

enum {
    CHOIR_CATHEDRAL, CHOIR_GREGORIAN, CHOIR_NUNS,
    CHOIR_M6, CHOIR_M3, CHOIR_M4, CHOIR_F7, CHOIR_F9, CHOIR_F4,
};

static const char *const vowel_choir_modes[] = {
    "CATHEDRAL", "GREGORIAN", "NUNS", "M6", "M3", "M4", "F7", "F9", "F4",
};
static const uint16_t vowel_choir_offsets[] = {0, 256, 384, 448};
static const uint32_t vowel_choir_vibrato[] = {
    16517421u, 17764019u, 15582473u, 18387318u, 14958174u, 17140708u,
};
static const struct {
    uint8_t women, octave;
    int8_t detune[VOWEL_CHOIR_SINGERS];
    uint8_t vibrato, drift;
} vowel_choir_groups[] = {
    {0x2A, 0x2A, {-7, -6, -3, 3, 6, 7}, 9, 5},
    {0x00, 0x00, {-9, -5, -2, 2, 5, 9}, 4, 6},
    {0x3F, 0x00, {-8, -5, -2, 2, 5, 8}, 12, 4},
};

typedef struct {
    uint32_t phase[VOWEL_CHOIR_SINGERS], vibrato[VOWEL_CHOIR_SINGERS], sway[VOWEL_CHOIR_SINGERS];
    int32_t drift[VOWEL_CHOIR_SINGERS], target[VOWEL_CHOIR_SINGERS];
    uint8_t level[VOWEL_CHOIR_SINGERS];
    uint32_t rng;
    int32_t low, air_low, vowel;
} vowel_choir_voice_t;

typedef struct {
    vowel_choir_voice_t voice[VOWEL_CHOIR_POLY];
} vowel_choir_part_t;

static uint32_t vowel_choir_rand(uint32_t *rng)
{
    uint32_t x = *rng;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    return *rng = x;
}

static void vowel_choir_note_on(track_t *t, voice_t *v)
{
    vowel_choir_part_t *part = eng_part(t);
    uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= VOWEL_CHOIR_POLY)
        return;
    vowel_choir_voice_t *s = &part->voice[slot];
    if (s->rng != 0 && (v->env || v->env_out))
        return;
    *s = (vowel_choir_voice_t){0};
    s->rng = (0x9E3779B9u ^ (slot * 0x85EBCA6Bu) ^ ((uint32_t)(t - trk) << 16) ^ v->note) | 1u;
    s->vowel = -1;
    for (uint32_t k = 0; k < VOWEL_CHOIR_SINGERS; k++) {
        s->phase[k] = vowel_choir_rand(&s->rng);
        s->vibrato[k] = vowel_choir_rand(&s->rng);
        s->sway[k] = vowel_choir_rand(&s->rng);
        s->level[k] = 255;
    }
}

typedef struct {
    const int8_t *from, *to;
    uint32_t phase, increment, shift, mask;
} vowel_choir_cycle_t;

static int32_t vowel_choir_read(const int8_t *table, uint32_t phase, uint32_t shift, uint32_t mask)
{
    uint32_t i = phase >> shift;
    int32_t fraction = (int32_t)(phase >> (shift - 8u) & 255u);
    int32_t a = table[i], b = table[(i + 1u) & mask];
    return a * 256 + (b - a) * fraction;
}

static void vowel_choir_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    vowel_choir_part_t *part = eng_part(t);
    uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= VOWEL_CHOIR_POLY || !part->voice[slot].rng)
        return;
    vowel_choir_voice_t *s = &part->voice[slot];
    const int16_t *p = t->p;
    uint32_t mode = (uint32_t)clamp(p[P_E0], 0, CHOIR_F4);
    uint32_t count = mode < CHOIR_M6 ? VOWEL_CHOIR_SINGERS : 1u;
    int32_t vowel = clamp(p[P_E1] + (m->shape >> 8) - 64, 0, 127) * 1024 / 127;
    const int32_t spread = p[P_E2], tone = clamp(p[P_E3] + (m->cutoff >> 9), 0, 127);
    const int32_t breath = p[P_E4], vibrato = p[P_E5];
    if ((vowel & 255) < 8)
        vowel &= ~255;
    else if ((vowel & 255) > 248)
        vowel = (vowel | 255) + 1;
    s->vowel = s->vowel < 0 ? vowel : s->vowel + clamp(vowel - s->vowel, -3, 3);
    uint32_t from = (uint32_t)s->vowel >> 8;
    int32_t fraction = s->vowel & 255;
    if (from == 4u) {
        from = 3;
        fraction = 256;
    }

    vowel_choir_cycle_t cycle[VOWEL_CHOIR_SINGERS];
    for (uint32_t k = 0; k < count; k++) {
        uint32_t women, singer;
        int32_t pitch = m->pitch16, tune;
        uint32_t noise = vowel_choir_rand(&s->rng);
        if (count == 1u) {
            uint32_t player = mode - CHOIR_M6;
            women = player / CHOIR_NS;
            singer = player % CHOIR_NS;
            tune = 0;
        } else {
            women = vowel_choir_groups[mode].women >> k & 1u;
            singer = k % CHOIR_NS;
            if (vowel_choir_groups[mode].octave >> k & 1u)
                pitch += 192;
            if (!(noise & 511u))
                s->target[k] = ((int32_t)(noise >> 16 & 255u) - 128) * vowel_choir_groups[mode].drift * 2;
            s->drift[k] += (s->target[k] - s->drift[k]) / 256;
            tune = vowel_choir_groups[mode].detune[k] * 256 + s->drift[k] + (sine_i(s->sway[k]) >> 5);
            tune = tune * spread / 12;
        }
        uint32_t step = vowel_choir_vibrato[k] * (uint32_t)n / CTL;
        s->vibrato[k] += step;
        s->sway[k] += step >> 4;
        int32_t vibrato_depth = count == 1u ? 8 : vowel_choir_groups[mode].vibrato;
        tune += (sine_i(s->vibrato[k]) * vibrato_depth >> 7) * vibrato / 64;

        uint32_t inc = cents_inc(pitch, 0, m->fine);
        uint32_t lv = 0;
        /* Choose a band-limited table, with hysteresis where nearby pitches meet. */
        while (lv < 3u && (inc >> 20) * (64u >> lv) > 1858u)
            lv++;
        if (lv < s->level[k] && s->level[k] < 4u && ((inc + (inc >> 5)) >> 20) * (64u >> lv) > 1858u)
            lv = s->level[k];
        s->level[k] = (uint8_t)lv;
        uint32_t zone = pitch >= (women ? 66 * 16 : 54 * 16);
        int32_t change = (int32_t)(((int64_t)inc * tune * 38) >> 24);
        uint32_t offset = vowel_choir_offsets[lv];
        cycle[k] = (vowel_choir_cycle_t){
            CHOIR_T[women][singer][zone][from] + offset,
            CHOIR_T[women][singer][zone][from + 1u] + offset,
            s->phase[k], inc + (uint32_t)change, 24u + lv, (256u >> lv) - 1u,
        };
    }

    const int32_t tone_coef = clamp(1500 + tone * tone * 2, 0, 32767);
    const int32_t voice_gain = 32767 - breath * breath * 2;
    const int32_t air_gain = breath * breath / 2;
    int32_t low = s->low, air_low = s->air_low;
    for (uint32_t i = 0; i < n; i++) {
        int32_t sum = 0;
        for (uint32_t k = 0; k < count; k++) {
            vowel_choir_cycle_t *c = &cycle[k];
            int32_t a = vowel_choir_read(c->from, c->phase, c->shift, c->mask);
            if (fraction) {
                int32_t b = vowel_choir_read(c->to, c->phase, c->shift, c->mask);
                a += (b - a) * fraction >> 8;
            }
            sum += a;
            c->phase += c->increment;
        }
        int32_t sample = count == 1u ? sum : (int32_t)(((int64_t)sum * 13377) >> 15);
        sample = (int32_t)(((int64_t)sample * voice_gain) >> 15);
        if (air_gain) {
            int32_t air = (int32_t)(vowel_choir_rand(&s->rng) >> 16) - 32768;
            air_low += (air - air_low) >> 3;
            sample += ((air - air_low) * air_gain) >> 13;
        }
        low += (int32_t)(((int64_t)(sample - low) * tone_coef) >> 15);
        out[i] += voice_amp2(soft_knee(low, 17000), m, i);
    }
    for (uint32_t k = 0; k < count; k++)
        s->phase[k] = cycle[k].phase;
    s->low = low;
    s->air_low = air_low;
}

static const preset_t vowel_choir_presets[] = {
    {"CHOIR A", {CHOIR_CATHEDRAL, 0, 12, 96, 12, 64}, {18, 65, 116, 75}, 0, 0, FX(0, 0, 0, 18), TAG(PT_PAD)},
    {"CHOIR E", {CHOIR_CATHEDRAL, 32, 15, 88, 19, 68}, {21, 64, 115, 79}, 0, 0, TAG(PT_PAD)},
    {"CHOIR I", {CHOIR_NUNS, 64, 14, 85, 19, 72}, {16, 65, 115, 74}, 0, 0, TAG(PT_PAD)},
    {"CHOIR O", {CHOIR_GREGORIAN, 95, 12, 101, 9, 45}, {14, 64, 117, 78}, 0, 0, TAG(PT_PAD)},
    {"CHOIR U", {CHOIR_GREGORIAN, 127, 15, 91, 22, 58}, {23, 67, 114, 81}, 0, 0, TAG(PT_PAD)},
    {"SOPRANO AIR", {CHOIR_F7, 31, 12, 103, 49, 77}, {13, 51, 116, 65}, 0, 0, TAG(PT_LEAD)},
    {"SOLO TENOR", {CHOIR_M3, 91, 12, 82, 13, 60}, {13, 53, 116, 62}, 0, 0, TAG(PT_LEAD)},
};

static const engine_t ENG_VOWEL_CHOIR = {
    .name = "CHOIR", .page_title = {"CHOIR", "AIR"},
    .edit = {
        {"SINGERS", vowel_choir_modes, 0, CHOIR_F4, CHOIR_CATHEDRAL, F_ENUM, 0},
        {"VOWEL", 0, 0, 127, 0, F_PCT, 0},
        {"SPREAD", 0, 0, 24, 12, F_INT, 0},
        {"TONE", 0, 0, 127, 96, F_PCT, 0},
        {"BREATH", 0, 0, 127, 12, F_PCT, 0},
        {"VIBRATO", 0, 0, 127, 64, F_PCT, 0},
    },
    .presets = vowel_choir_presets, .npresets = NELEM(vowel_choir_presets),
    .knob = {P_E0, P_E1, P_E2, P_E3},
    .poly = VOWEL_CHOIR_POLY, .note_on = vowel_choir_note_on, .render = vowel_choir_render,
};
