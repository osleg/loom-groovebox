/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff voice adapter for the FM-1 SID chip model. */
#include "thirdparty/sid.h"

#define SID_SKIFF_RATE 44100u
#define SID_MODULATOR  (2u * 7u)

typedef struct {
    sid_t chip;
    uint8_t ready;
} sid_slot_t;

typedef struct {
    sid_slot_t voice[NVOICE];
} sid_part_t;

/* Build the donor's shared waveform tables before the audio callback. */
static void sid_prepare_models(void)
{
    sid_t chip;
    sid_init(&chip, SID_6581, SID_CLOCK_PAL, SID_SKIFF_RATE);
    sid_init(&chip, SID_8580, SID_CLOCK_PAL, SID_SKIFF_RATE);
}

static const char *const SID_WAVE_NAMES[] = {
    "TRI", "SAW", "PULSE", "NOISE", "SAW+TRI", "PULSE+TRI", "PULSE+SAW",
    "SYNC SAW", "SYNC PULSE", "RING TRI",
};
static const uint8_t SID_WAVES[] = {
    SID_TRI, SID_SAW, SID_PULSE, SID_NOISE, SID_SAW | SID_TRI, SID_PULSE | SID_TRI,
    SID_PULSE | SID_SAW, SID_SYNC | SID_SAW, SID_SYNC | SID_PULSE, SID_RING | SID_TRI,
};
static const char *const SID_RATIOS[] = {"0.5X", "1X", "1.25X", "1.5X", "2X", "2.5X", "3X", "4X"};
static const uint8_t SID_RATIO_Q2[] = {2, 4, 5, 6, 8, 10, 12, 16};
static const char *const SID_MODELS[] = {"6581", "8580"};
static const char *const SID_FILTERS[] = {"OFF", "LOW", "BAND", "HIGH", "NOTCH"};
static const uint8_t SID_FILTER_MODE[] = {0, SID_LP, SID_BP, SID_HP, SID_LP | SID_HP};

static sid_t *sid_voice_chip(track_t *t, const voice_t *v)
{
    sid_slot_t *slot = &((sid_part_t *)eng_part(t))->voice[v - t->v];
    sid_model_t model = t->p[P_E3] == SID_8580 ? SID_8580 : SID_6581;
    if (!slot->ready || slot->chip.model != model) {
        sid_init(&slot->chip, model, SID_CLOCK_PAL, SID_SKIFF_RATE);
        /* Skiff's ENV controls level and voice lifetime. The chip opens quickly
         * and stays near full level while Skiff completes a release. */
        sid_write(&slot->chip, SID_SR, 0xffu);
        slot->ready = 1u;
    }
    return &slot->chip;
}

static void sid_note_on(track_t *t, voice_t *v)
{
    (void)sid_voice_chip(t, v);
}

static void sid_set(sid_t *chip, unsigned reg, uint8_t value)
{
    if (chip->reg[reg] != value)
        sid_write(chip, reg, value);
}

static void sid_pitch(sid_t *chip, unsigned base, uint32_t freq)
{
    if (freq > 65535u) freq = 65535u;
    sid_set(chip, base + SID_FREQ_LO, (uint8_t)freq);
    sid_set(chip, base + SID_FREQ_HI, (uint8_t)(freq >> 8));
}

static void sid_controls(sid_t *chip, const int16_t *p, const voice_t *v, const vmod_t *m)
{
    const uint32_t pitch_scale = SID_CLOCK_PAL * 256u;
    uint32_t freq = (uint32_t)(((uint64_t)m->inc * SID_SKIFF_RATE + pitch_scale / 2u) / pitch_scale);
    uint32_t wave = SID_WAVES[clamp(p[P_E0], 0, NELEM(SID_WAVES) - 1u)];
    uint32_t ratio = SID_RATIO_Q2[clamp(p[P_E2], 0, NELEM(SID_RATIO_Q2) - 1u)];
    uint32_t width = (uint32_t)clamp(p[P_E1] * 32 + ((m->shape - (64 << 8)) >> 3), 32, 4064);
    uint32_t mode = SID_FILTER_MODE[clamp(p[P_E4], 0, NELEM(SID_FILTER_MODE) - 1u)];
    uint32_t cutoff = (uint32_t)clamp(p[P_E5] * 16 + (m->cutoff >> 4), 0, 2047);
    uint32_t res = (uint32_t)clamp(p[P_E6], 0, 15);

    /* Oscillator 3 is the silent source for oscillator 1's native sync and ring. */
    sid_pitch(chip, 0u, wave & SID_SYNC ? freq * ratio / 4u : freq);
    sid_pitch(chip, SID_MODULATOR, wave & SID_RING ? freq * ratio / 4u : freq);
    sid_set(chip, SID_PW_LO, (uint8_t)width);
    sid_set(chip, SID_PW_HI, (uint8_t)(width >> 8));
    sid_set(chip, SID_CTRL, (uint8_t)(wave | (v->gate ? SID_GATE : 0u)));

    sid_set(chip, SID_FC_LO, (uint8_t)(cutoff & 7u));
    sid_set(chip, SID_FC_HI, (uint8_t)(cutoff >> 3));
    sid_set(chip, SID_RES_FILT, (uint8_t)(res << 4 | (mode ? 1u : 0u)));
    sid_set(chip, SID_MODE_VOL, (uint8_t)(mode | SID_3OFF | (uint32_t)clamp(p[P_E7], 0, 15)));
}

static void sid_voice_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    sid_t *chip = sid_voice_chip(t, v);
    sid_controls(chip, t->p, v, m);
    for (uint32_t at = 0; at < n;) {
        int32_t samples[32];
        uint32_t count = n - at;
        if (count > NELEM(samples)) count = NELEM(samples);
        sid_render(chip, samples, count);
        for (uint32_t i = 0; i < count; i++)
            out[at + i] += voice_amp2(clamp(samples[i] * 2, -32768, 32767), m, at + i);
        at += count;
    }
}

static const preset_t SID_PRESETS[] = {
    {"CHIP BASS", {2, 47, 1, 0, 1, 38, 10, 15}, {0, 67, 103, 25}, 23, 1, TAG(PT_BASS)},
    {"LASER SYNC", {7, 64, 5, 1, 1, 83, 8, 15}, {0, 77, 100, 33}, 0, 1, TAG(PT_LEAD)},
    {"RING BELLS", {9, 64, 2, 1, 2, 71, 7, 15}, {0, 60, 26, 45}, 0, 0, TAG(PT_KEYS)},
    {"PULSE KEYS", {5, 69, 1, 1, 0, 100, 0, 15}, {2, 80, 113, 49}, 0, 0, TAG(PT_KEYS)},
    {"BIT NOISE", {3, 64, 1, 0, 3, 50, 6, 15}, {0, 44, 0, 14}, 8, 1, TAG(PT_FX)},
};

static const engine_t ENG_SID = {
    .name = "SID",
    .page_title = {"OSCILLATOR", "FILTER"},
    .edit = {
        {"WAVE", SID_WAVE_NAMES, 0, NELEM(SID_WAVE_NAMES) - 1u, 2, F_ENUM, 0},
        {"WIDTH", 0, 0, 127, 47, F_PCT, 0},
        {"RATIO", SID_RATIOS, 0, NELEM(SID_RATIOS) - 1u, 1, F_ENUM, 0},
        {"CHIP", SID_MODELS, 0, 1, 0, F_ENUM, 0},
        {"FILTER", SID_FILTERS, 0, NELEM(SID_FILTERS) - 1u, 1, F_ENUM, 0},
        {"CUTOFF", 0, 0, 127, 38, F_PCT, 0},
        {"RESO", 0, 0, 15, 10, F_INT, 0},
        {"VOLUME", 0, 0, 15, 15, F_INT, 0},
    },
    .presets = SID_PRESETS, .npresets = NELEM(SID_PRESETS),
    .knob = {P_E5, P_E6, P_E1, P_E2},
    .poly = NVOICE, .note_on = sid_note_on, .render = sid_voice_render,
};
