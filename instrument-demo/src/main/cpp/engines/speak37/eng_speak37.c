/* SPDX-License-Identifier: GPL-3.0-only
 * Based on Speak37 by M. Geisler (genkigenki/makkuro), copyright 2026.
 * The LPC frame reader, excitation and lattice are adapted from sp_dsp.c.
 */
#include <math.h>
#include "thirdparty/sp_vocabulary.h"

#define SPEAK37_POLY 6u
#define SPEAK37_COMB_SIZE 512u
#define SPEAK37_LATTICE_HZ 8000.0f
#define SPEAK37_FRAME_SAMPLES 441u

enum { SP_EX_PULSE, SP_EX_CHIRP, SP_EX_GLOT, SP_EX_NOISE };
enum { SPEAK37_SHOT, SPEAK37_HOLD, SPEAK37_LOOP };

typedef struct {
    uint8_t excitation, lofi, metal, layers, steps;
    int8_t formant, speed;
    uint16_t de;
} speak37_profile_t;

static const speak37_profile_t SPEAK37_PROFILE[] = {
    {SP_EX_PULSE, 1,  0, 1, 0,  0,   0, 22000}, /* classic */
    {SP_EX_CHIRP, 1,  0, 1, 0,  0,   0, 16000}, /* vintage */
    {SP_EX_PULSE, 0,  0, 1, 0, -3,  -4, 26000}, /* deep */
    {SP_EX_PULSE, 0,  0, 1, 0,  4,   4, 22000}, /* child */
    {SP_EX_NOISE, 0,  0, 1, 0,  0,  -4, 20000}, /* whisper */
    {SP_EX_PULSE, 1, 80, 1, 0,  0,  -2, 18000}, /* robot */
    {SP_EX_GLOT,  0, 10, 1, 1,  2,  -2,  8000}, /* lab AI */
    {SP_EX_PULSE, 0,  0, 1, 0, -6, -10, 28000}, /* giant */
    {SP_EX_GLOT,  0,  0, 3, 0,  0,  -4, 26000}, /* choir */
};

typedef struct {
    uint32_t bit, phase, chirp_pos, frame_pos, resample;
    uint16_t frame, noise;
    uint8_t period, voiced, held, done;
    int32_t coeff_from[10], coeff[10], delay[10];
    int32_t energy_from, energy, pending, de, prev, sample;
} speak37_layer_t;

typedef struct {
    speak37_layer_t layer[3];
    int16_t comb[SPEAK37_COMB_SIZE];
    uint16_t word, comb_write;
    uint8_t profile, ready;
} speak37_note_t;

typedef struct { speak37_note_t notes[SPEAK37_POLY]; } speak37_part_t;

static uint32_t speak37_bank(const track_t *t)
{
    return (unsigned)clamp(t->p[P_E4], 0, (int)NELEM(SPEAK37_BANKS) - 1);
}

static const param_desc_t *speak37_desc(const track_t *t, uint32_t k)
{
    return k == 1 ? &SPEAK37_WORD_DESC[speak37_bank(t)] : 0;
}

static uint32_t speak37_bits(speak37_layer_t *voice, uint32_t n)
{
    uint32_t result = 0;
    while (n--) {
        uint32_t bit = voice->bit++;
        result = (result << 1) | ((SP_LPC[bit >> 3] >> (7u - (bit & 7u))) & 1u);
    }
    return result;
}

static void speak37_frame(speak37_layer_t *layer, const speak37_word_t *word,
                          const speak37_profile_t *profile, uint32_t holding, uint32_t gate)
{
    if (layer->held && gate)
        return;
    layer->held = 0;
    if (layer->frame >= word->nfr) {
        if (holding == SPEAK37_LOOP && gate) {
            layer->bit = word->off * 8u;
            layer->frame = 0;
        } else {
            layer->done = 1;
            layer->energy_from = layer->energy;
            layer->energy = 0;
            return;
        }
    }
    const uint32_t e = speak37_bits(layer, 4);
    const uint32_t was_voiced = layer->voiced;
    memcpy(layer->coeff_from, layer->coeff, sizeof layer->coeff);
    layer->energy_from = layer->energy;
    layer->voiced = 0;
    layer->energy = 0;
    if (e && e != 15) {
        const uint32_t repeated = speak37_bits(layer, 1), p = speak37_bits(layer, 6);
        if (!repeated) {
            layer->coeff[0] = SP_K1[speak37_bits(layer, 5)];
            layer->coeff[1] = SP_K2[speak37_bits(layer, 5)];
            for (uint32_t i = 2; i < 7; i++)
                layer->coeff[i] = p || i < 4 ? SP_K3_7[i - 2][speak37_bits(layer, 4)] : 0;
            for (uint32_t i = 7; i < 10; i++)
                layer->coeff[i] = p ? SP_K8_10[i - 7][speak37_bits(layer, 3)] : 0;
        }
        if (p)
            layer->period = (uint8_t)p;
        layer->voiced = p && profile->excitation != SP_EX_NOISE;
        layer->energy = (int32_t)(SP_ENERGY[e] * 24u * word->gain >> 8);
        if (p && !layer->voiced)
            layer->energy >>= 2;
        if (gate && holding == SPEAK37_HOLD && layer->frame == word->nuc)
            layer->held = 1;
    }
    if (layer->voiced != was_voiced || !layer->energy_from || !layer->energy)
        memcpy(layer->coeff_from, layer->coeff, sizeof layer->coeff);
    layer->frame++;
}

static int32_t speak37_noise(speak37_layer_t *v, int32_t energy)
{
    v->noise = (uint16_t)((v->noise >> 1) ^ ((v->noise & 1u) ? 0xB400u : 0u));
    return (v->noise & 1u) ? energy : -energy;
}

static int32_t speak37_lattice(speak37_layer_t *v, const speak37_profile_t *p, const int32_t *coeff,
                               int32_t energy, uint32_t inc, int32_t amp_q12, int32_t breath)
{
    int32_t excitation;
    if (v->voiced && energy) {
        const uint32_t prev = v->phase;
        v->phase += inc;
        if (p->excitation == SP_EX_GLOT) {
            excitation = (int32_t)((int64_t)energy * SP_GLOT[v->phase >> 24] >> 15);
        } else if (p->excitation == SP_EX_CHIRP) {
            if (v->phase < prev)
                v->chirp_pos = v->phase / ((inc >> 8) + 1u);
            else if (v->chirp_pos < sizeof SP_CHIRP * 256u)
                v->chirp_pos += 256u;
            const uint32_t i = v->chirp_pos >> 8;
            const int32_t c0 = i < sizeof SP_CHIRP ? SP_CHIRP[i] : 0;
            const int32_t c1 = i + 1 < sizeof SP_CHIRP ? SP_CHIRP[i + 1] : 0;
            const int32_t c = c0 * 256 + (c1 - c0) * (int32_t)(v->chirp_pos & 255u);
            excitation = (int32_t)((int64_t)c * energy * amp_q12 >> 28);
        } else {
            excitation = v->pending;
            v->pending = 0;
            if (v->phase < prev) {
                const int32_t amp = (int32_t)((int64_t)energy * amp_q12 >> 12);
                const int32_t frac = (int32_t)(((v->phase >> 8) << 7) / ((inc >> 8) + 1u));
                const int32_t carried = (int32_t)((int64_t)amp * frac >> 15);
                excitation += carried;
                v->pending = amp - carried;
            }
        }
        if (breath)
            excitation += speak37_noise(v, energy) * breath >> 9;
    } else {
        excitation = speak37_noise(v, energy);
        v->pending = 0;
    }
    int32_t filtered = excitation;
    for (int i = 9; i >= 0; i--) {
        filtered -= (int32_t)((int64_t)coeff[i] * v->delay[i] >> 15);
        if (i < 9)
            v->delay[i + 1] = clamp(v->delay[i] + (int32_t)((int64_t)coeff[i] * filtered >> 15), -32767, 32767);
    }
    v->delay[0] = clamp(filtered, -32767, 32767);
    v->de = clamp(v->delay[0] + (int32_t)((int64_t)p->de * v->de >> 15), -32767, 32767);
    return v->de * (32768 - p->de) >> 14;
}

static void speak37_note_on(track_t *t, voice_t *v)
{
    speak37_part_t *part = (speak37_part_t *)eng_part(t);
    const uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= SPEAK37_POLY)
        return;
    speak37_note_t *note = &part->notes[slot];
    const uint32_t bank = speak37_bank(t);
    const uint32_t first = bank * 64u < SPEAK37_WORD_COUNT - 64u ? bank * 64u : SPEAK37_WORD_COUNT - 64u;
    memset(note, 0, sizeof *note);
    note->word = (uint16_t)(first + (uint32_t)clamp(t->p[P_E1], 0, 63));
    note->profile = (uint8_t)clamp(t->p[P_E0], 0, (int)NELEM(SPEAK37_PROFILE) - 1);
    const speak37_word_t *word = &SP_WORD[note->word];
    const speak37_profile_t *profile = &SPEAK37_PROFILE[note->profile];
    for (uint32_t i = 0; i < profile->layers; i++) {
        speak37_layer_t *layer = &note->layer[i];
        layer->bit = word->off * 8u;
        layer->noise = (uint16_t)(0xACE1u + slot * 977u + i * 49u);
        layer->phase = 0xf0000000u - i * 0x10000000u;
        layer->chirp_pos = sizeof SP_CHIRP * 256u;
        layer->period = word->p0;
    }
    note->ready = 1;
}

static void speak37_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    static const float detune[] = {0.0f, 0.140625f, -0.140625f};
    static const float tract[] = {0.0f, 0.253f, -0.253f};
    const uint32_t slot = (uint32_t)(v - t->v);
    speak37_part_t *part = (speak37_part_t *)eng_part(t);
    if (slot >= SPEAK37_POLY || !part->notes[slot].ready)
        return;
    speak37_note_t *note = &part->notes[slot];
    const speak37_profile_t *profile = &SPEAK37_PROFILE[note->profile];
    const speak37_word_t *word = &SP_WORD[note->word];
    const float speed = (float)clamp(t->p[P_E2] + profile->speed, 0, 127);
    const uint32_t framelen = word->flags & SPEAK37_WORD_PERC ? SPEAK37_FRAME_SAMPLES :
        (uint32_t)(SPEAK37_FRAME_SAMPLES * exp2f((64.0f - speed) * (1.0f / 32.0f)));
    const uint32_t holding = word->flags & SPEAK37_WORD_PERC ? SPEAK37_SHOT : (uint32_t)t->p[P_E3];
    const float pitch = m->pitch16 * (1.0f / 16.0f) + m->fine * (17.31234f / 4096.0f);
    const float formant = (float)t->p[P_E5];
    int32_t mix[CTL] = {0};
    for (uint32_t i = 0; i < profile->layers; i++) {
        speak37_layer_t *layer = &note->layer[i];
        if (layer->done)
            continue;
        if (!layer->frame || layer->frame_pos >= framelen) {
            layer->frame_pos %= framelen;
            speak37_frame(layer, word, profile, holding, v->gate);
        }
        const uint32_t pos = layer->held ? framelen : layer->frame_pos;
        int32_t coeff[10];
        for (uint32_t k = 0; k < NELEM(coeff); k++)
            coeff[k] = layer->coeff_from[k] + (layer->coeff[k] - layer->coeff_from[k]) * (int32_t)pos / (int32_t)framelen;
        const uint32_t attack = framelen >> 3;
        const int32_t energy = pos >= attack ? layer->energy :
            layer->energy_from + (layer->energy - layer->energy_from) * (int32_t)pos / (int32_t)attack;
        float contour = (SP_PLOG[word->p0] - SP_PLOG[layer->period]) *
            ((float)t->p[P_E6] * (1.0f / 4096.0f));
        if (profile->steps)
            contour = roundf(contour);
        if (layer->held || word->flags & SPEAK37_WORD_PERC)
            contour = 0;
        const float lattice_rate = SPEAK37_LATTICE_HZ * exp2f((formant + profile->formant + tract[i]) / 12.0f);
        const float hz = 440.0f * exp2f((pitch + contour + detune[i] - 69.0f) / 12.0f);
        const float scaled = hz < lattice_rate * 0.4f ? hz : lattice_rate * 0.4f;
        const uint32_t inc = (uint32_t)(scaled * (4294967296.0f / lattice_rate));
        const uint32_t rstep = (uint32_t)(lattice_rate * (65536.0f / 44100.0f));
        const int32_t amp = (int32_t)(8192.0f * sqrtf(lattice_rate / (64.0f * hz)));
        const uint32_t comb_period = (uint32_t)clamp((int32_t)(lattice_rate / scaled), 8, SPEAK37_COMB_SIZE - 1);
        const int32_t breath = t->p[P_E7];
        for (uint32_t sample = 0; sample < n; sample++) {
            layer->resample += rstep;
            if (layer->resample >= 65536u) {
                layer->resample -= 65536u;
                layer->prev = layer->sample;
                const int32_t dry = speak37_lattice(layer, profile, coeff, energy, inc, amp, breath);
                if (i == 0u && profile->metal) {
                    const uint32_t pos = (note->comb_write - comb_period) & (SPEAK37_COMB_SIZE - 1);
                    layer->sample = clamp(dry + (note->comb[pos] * profile->metal * 230 >> 15), -32767, 32767);
                    note->comb[note->comb_write] = (int16_t)layer->sample;
                    note->comb_write = (uint16_t)((note->comb_write + 1) & (SPEAK37_COMB_SIZE - 1));
                } else
                    layer->sample = dry;
            }
            const int32_t value = profile->lofi ? layer->sample : layer->prev +
                (int32_t)((int64_t)(layer->sample - layer->prev) * layer->resample >> 16);
            mix[sample] += value;
        }
        layer->frame_pos += n;
    }
    for (uint32_t i = 0; i < n; i++)
        out[i] += voice_amp2(clamp(mix[i] / profile->layers, -32767, 32767), m, i);
}

static const char *const SPEAK37_VOICES[] = {
    "CLASSIC", "VINTAGE", "DEEP", "CHILD", "WHISPER", "ROBOT", "LAB AI", "GIANT", "CHOIR",
};
static const char *const SPEAK37_HOLDS[] = {"ONE SHOT", "SING", "LOOP"};

static const preset_t SPEAK37_PRESETS[] = {
    {"CLASSIC TALK", {0, 0, 64, SPEAK37_HOLD, 0, 0, 56, 6}, {1, 48, 121, 53}, 0, 0, TAG(PT_KEYS)},
    {"VINTAGE TOY", {1, 1, 64, SPEAK37_HOLD, 0, 0, 64, 0}, {1, 42, 110, 43}, 0, 0, TAG(PT_KEYS)},
    {"DEEP VOICE", {2, 0, 64, SPEAK37_HOLD, 0, 0, 40, 10}, {5, 56, 113, 54}, 0, 0, TAG(PT_LEAD)},
    {"CHILD VOICE", {3, 3, 64, SPEAK37_HOLD, 0, 0, 84, 8}, {1, 37, 112, 47}, 0, 0, TAG(PT_KEYS)},
    {"WHISPER", {4, 4, 64, SPEAK37_SHOT, 0, 0, 0, 0}, {10, 51, 127, 60}, 0, 0, TAG(PT_FX)},
    {"METAL ROBOT", {5, 0, 64, SPEAK37_HOLD, 0, 0, 0, 0}, {2, 46, 119, 45}, 0, 0, TAG(PT_LEAD)},
    {"LAB AI", {6, 2, 64, SPEAK37_HOLD, 0, 0, 72, 0}, {3, 52, 117, 60}, 0, 0, TAG(PT_KEYS)},
    {"GIANT VOICE", {7, 0, 64, SPEAK37_HOLD, 0, 0, 30, 12}, {10, 64, 121, 70}, 0, 0, TAG(PT_LEAD)},
    {"LATTICE CHOIR", {8, 0, 64, SPEAK37_HOLD, 0, 0, 20, 24}, {16, 68, 117, 81}, 0, 0, FX(0, 14, 0, 32), TAG(PT_PAD)},
};

static const engine_t ENG_SPEAK37 = {
    .name = "SPEAK37", .page_title = {"WORD", "CHARACTER"},
    .edit = {
        {"VOICE", SPEAK37_VOICES, 0, NELEM(SPEAK37_VOICES) - 1, 0, F_ENUM, 0},
        {"WORD", SPEAK37_WORDS, 0, 63, 0, F_ENUM, 0},
        {"SPEED", 0, 0, 127, 64, F_PCT, 0},
        {"HOLD", SPEAK37_HOLDS, 0, NELEM(SPEAK37_HOLDS) - 1, SPEAK37_HOLD, F_ENUM, 0},
        {"BANK", SPEAK37_BANKS, 0, NELEM(SPEAK37_BANKS) - 1, 0, F_ENUM, 0},
        {"FORMANT", 0, -12, 12, 0, F_SEMI, 0},
        {"INTONE", 0, 0, 127, 56, F_PCT, 0},
        {"BREATH", 0, 0, 127, 6, F_PCT, 0},
    },
    .presets = SPEAK37_PRESETS, .npresets = NELEM(SPEAK37_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3}, .poly = SPEAK37_POLY,
    .note_on = speak37_note_on, .render = speak37_render, .desc = speak37_desc,
};
