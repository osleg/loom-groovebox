/* SPDX-License-Identifier: GPL-3.0-or-later
 * Android Skiff instrument bridges for OPN-1's portable YM2612 and Sega PSG cores. */
#include "thirdparty/opn2.h"
#include "thirdparty/psg.h"

#define OPN_SKIFF_RATE 44100u

typedef struct {
    opn2_t chip;
    uint32_t frac;
    int32_t previous, sample, dc;
    uint8_t gate;
} ym2612_voice_t;

typedef struct {
    ym2612_voice_t voice[NVOICE];
} ym2612_part_t;

typedef struct {
    psg_t chip;
    int32_t dc, color;
    uint8_t ready;
} sn76489_voice_t;

typedef struct {
    sn76489_voice_t voice[NVOICE];
} sn76489_part_t;

/* Remove the DC bias of the chip's unipolar output or DAC ladder. The state is Q8. */
static int32_t opn_center(int32_t sample, int32_t *dc)
{
    *dc += (sample * 256 - *dc) / 1024;
    return sample - (*dc >> 8);
}

static ym2612_voice_t *ym2612_slot(track_t *t, voice_t *v)
{
    return &((ym2612_part_t *)eng_part(t))->voice[v - t->v];
}

static void ym2612_note_on(track_t *t, voice_t *v)
{
    ym2612_voice_t *y = ym2612_slot(t, v);
    opn2_reset(&y->chip);
    y->frac = 0;
    y->previous = y->sample = y->dc = 0;
    y->gate = 1;
    opn2_write_reg(&y->chip, 0, 0x28, 0xF0);
}

static void ym2612_set(opn2_t *c, uint8_t reg, uint8_t value)
{
    if (c->regs[reg] != value)
        opn2_write_reg(c, 0, reg, value);
}

static void ym2612_pitch(opn2_t *c, uint32_t inc)
{
    /* The chip's phase advances FNUM * 2^block / 2 in Q20 per native sample.
     * Choose the smallest block that fits to retain as many FNUM bits as possible. */
    uint32_t fnum_q8 = (uint32_t)((uint64_t)inc * OPN_SKIFF_RATE / (OPN2_RATE_NTSC * 8u));
    uint32_t fnum, block = 0;
    while (fnum_q8 >= (2048u << 8) && block < 7u) {
        fnum_q8 >>= 1;
        block++;
    }
    fnum = (uint32_t)clamp((int32_t)((fnum_q8 + 128u) >> 8), 1, 2047);
    if (c->regs[0xA4] != ((block << 3) | (fnum >> 8)) || c->regs[0xA0] != (fnum & 255u)) {
        opn2_write_reg(c, 0, 0xA4, (uint8_t)((block << 3) | (fnum >> 8)));
        opn2_write_reg(c, 0, 0xA0, (uint8_t)fnum);
    }
}

static void ym2612_controls(opn2_t *c, const int16_t *p, const vmod_t *m)
{
    static const uint8_t SLOT[4] = {0, 8, 4, 12};
    static const uint8_t CARRIERS[8] = {8, 8, 8, 8, 10, 14, 14, 15};
    uint32_t algorithm = (uint32_t)clamp(p[P_E0], 0, 7);
    int32_t brightness = clamp(p[P_E2] + (m->cutoff >> 8), 0, 127);
    int32_t feedback = clamp(p[P_E1] + ((m->shape - (64 << 8)) >> 12), 0, 7);
    uint32_t i;

    ym2612_set(c, 0xB0, (uint8_t)((feedback << 3) | algorithm));
    ym2612_pitch(c, m->inc);
    for (i = 0; i < 4u; i++) {
        uint8_t slot = SLOT[i];
        uint32_t carrier = (CARRIERS[algorithm] >> i) & 1u;
        int32_t multiple = i < 3u ? clamp(p[P_E3 + i], 0, 15) : 1;
        int32_t level = i == 3u ? 0 : carrier && algorithm != 7u ? 18 : 76 - brightness * 64 / 127;
        int32_t attack = clamp(p[P_E6] + (carrier ? 0 : 3), 1, 31);
        int32_t decay = clamp(p[P_E7] + (carrier ? 0 : 4), 0, 31);
        int32_t sustain = carrier ? 2 : 5;
        ym2612_set(c, (uint8_t)(0x30u + slot), (uint8_t)multiple);
        ym2612_set(c, (uint8_t)(0x40u + slot), (uint8_t)level);
        ym2612_set(c, (uint8_t)(0x50u + slot), (uint8_t)attack);
        ym2612_set(c, (uint8_t)(0x60u + slot), (uint8_t)decay);
        ym2612_set(c, (uint8_t)(0x80u + slot), (uint8_t)((sustain << 4) | 8));
    }
}

static void ym2612_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    ym2612_voice_t *y = ym2612_slot(t, v);
    uint32_t at = 0;
    if (y->gate != v->gate) {
        y->gate = v->gate;
        opn2_write_reg(&y->chip, 0, 0x28, v->gate ? 0xF0 : 0);
    }
    ym2612_controls(&y->chip, t->p, m);
    while (at < n) {
        int32_t native[32];
        uint32_t i, k = 0, count = n - at;
        /* At most 31 native samples are needed for 25 output samples. */
        if (count > 25u) count = 25u;
        opn2_render_voice(&y->chip, native, (y->frac + count * OPN2_RATE_NTSC) / OPN_SKIFF_RATE);
        for (i = 0; i < count; i++) {
            int32_t s;
            y->frac += OPN2_RATE_NTSC;
            while (y->frac >= OPN_SKIFF_RATE) {
                y->previous = y->sample;
                y->sample = native[k++];
                y->frac -= OPN_SKIFF_RATE;
            }
            s = y->previous + (y->sample - y->previous) * (int32_t)y->frac / (int32_t)OPN_SKIFF_RATE;
            s = opn_center(s, &y->dc);
            out[at + i] += voice_amp2(clamp(s * 2, -32768, 32767), m, at + i);
        }
        at += count;
    }
}

static const preset_t YM2612_PRESETS[] = {
    {"IRON BASS", {0, 3, 102, 1, 1, 1, 31, 14}, {0, 70, 105, 24}, 0, 1, TAG(PT_BASS)},
    {"GLASS KEYS", {4, 1, 85, 7, 2, 3, 28, 20}, {1, 58, 90, 47}, 0, 0, TAG(PT_KEYS)},
    {"TWIN REED", {5, 4, 91, 1, 1, 2, 29, 12}, {3, 73, 105, 34}, 0, 1, TAG(PT_LEAD)},
    {"SOFT ORBIT", {7, 0, 110, 0, 2, 3, 15, 10}, {31, 80, 112, 55}, 0, 0, TAG(PT_PAD)},
};

static const engine_t ENG_YM2612 = {
    .name = "YM2612",
    .page_title = {"FM PATCH", "OPERATORS"},
    .edit = {
        {"ALG", 0, 0, 7, 0, F_INT, 0},
        {"FEEDBACK", 0, 0, 7, 3, F_INT, 0},
        {"BRIGHT", 0, 0, 127, 102, F_PCT, 0},
        {"OP1 MULT", 0, 0, 15, 1, F_INT, 0},
        {"OP2 MULT", 0, 0, 15, 1, F_INT, 0},
        {"OP3 MULT", 0, 0, 15, 1, F_INT, 0},
        {"FM ATTACK", 0, 1, 31, 31, F_INT, 0},
        {"FM DECAY", 0, 0, 31, 14, F_INT, 0},
    },
    .presets = YM2612_PRESETS, .npresets = NELEM(YM2612_PRESETS),
    .knob = {P_E2, P_E1, P_E6, P_E7},
    .poly = NVOICE, .note_on = ym2612_note_on, .render = ym2612_render,
};

enum { SN_SQUARE, SN_WHITE, SN_PERIODIC, SN_MIX };

static sn76489_voice_t *sn76489_slot(track_t *t, voice_t *v)
{
    return &((sn76489_part_t *)eng_part(t))->voice[v - t->v];
}

static void sn76489_note_on(track_t *t, voice_t *v)
{
    sn76489_voice_t *s = sn76489_slot(t, v);
    if (!s->ready) {
        psg_reset(&s->chip);
        s->ready = 1;
    }
}

static uint32_t sn76489_inc(const vmod_t *m, int32_t offset)
{
    uint32_t inc;
    if (!offset)
        return m->inc;
    inc = pitch_inc(clamp(m->pitch16 + offset * 16, 0, 2047));
    return inc + (uint32_t)((int32_t)(inc >> 12) * m->fine);
}

static void sn76489_period(psg_t *p, uint32_t channel, uint32_t inc, uint32_t divider)
{
    /* period = chip clock / (32 * Hz). Use a slower clock for bass notes below the native 10-bit range. */
    uint64_t denominator = (uint64_t)OPN_SKIFF_RATE * (inc ? inc : 1u) << divider;
    uint64_t rounded = (((uint64_t)PSG_CLOCK << 27) + denominator / 2u) / denominator;
    uint32_t period = rounded > 1023u ? 1023u : rounded ? (uint32_t)rounded : 1u;
    uint32_t reg = channel * 2u;
    if (p->reg[reg] != (int32_t)period) {
        psg_write(p, (uint8_t)(0x80u | (reg << 4) | (period & 15u)));
        psg_write(p, (uint8_t)(period >> 4));
    }
}

static void sn76489_level(psg_t *p, uint32_t channel, int32_t level)
{
    uint32_t reg = channel * 2u + 1u;
    int32_t attenuation = 15 - clamp(level, 0, 15);
    if (p->reg[reg] != attenuation)
        psg_write(p, (uint8_t)(0x80u | (reg << 4) | (uint32_t)attenuation));
}

static uint32_t sn76489_controls(psg_t *chip, const int16_t *p, const vmod_t *m)
{
    static const uint8_t CLOCK[4] = {3, 0, 1, 2};
    uint32_t mode = (uint32_t)clamp(p[P_E0], SN_SQUARE, SN_MIX);
    uint32_t clock = CLOCK[clamp(p[P_E4], 0, 3)];
    uint32_t div = 0, lowest = UINT32_MAX;
    int32_t tones = mode == SN_SQUARE || mode == SN_MIX;
    int32_t noise = mode != SN_SQUARE;
    uint32_t aux_inc = sn76489_inc(m, p[P_E6]);
    uint32_t noise_inc = sn76489_inc(m, p[P_E5]);

    if (tones && p[P_E1])
        lowest = m->inc;
    if (tones && p[P_E2] && aux_inc < lowest)
        lowest = aux_inc;
    if (noise && clock == 3u && noise_inc < lowest)
        lowest = noise_inc;
    while (div < 4u && ((uint64_t)lowest * (OPN_SKIFF_RATE * 1023u) << div) < ((uint64_t)PSG_CLOCK << 27))
        div++;

    sn76489_level(chip, 0, tones ? p[P_E1] : 0);
    sn76489_level(chip, 1, tones ? p[P_E2] : 0);
    sn76489_level(chip, 3, noise ? p[P_E3] : 0);
    if (tones) {
        sn76489_period(chip, 0, m->inc, div);
        sn76489_period(chip, 1, aux_inc, div);
    }
    if (noise) {
        uint32_t noise_reg = clock | (mode == SN_PERIODIC ? 0u : 4u);
        if (clock == 3u)
            sn76489_period(chip, 2, noise_inc, div);
        if (chip->reg[6] != (int32_t)noise_reg || chip->period[3] == 0)
            psg_write(chip, (uint8_t)(0xE0u | noise_reg));
    }
    return (uint32_t)(((uint64_t)PSG_CLOCK << 16) / (16u * OPN_SKIFF_RATE)) >> div;
}

static void sn76489_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    sn76489_voice_t *s = sn76489_slot(t, v);
    uint32_t step = sn76489_controls(&s->chip, t->p, m), at = 0;
    int32_t color = clamp(16 + 2 * (t->p[P_E7] + (m->cutoff >> 8)), 16, 256);

    while (at < n) {
        int32_t samples[64] = {0};
        uint32_t i, count = n - at;
        if (count > 32u) count = 32u;
        psg_render(&s->chip, samples, count, step);
        for (i = 0; i < count; i++) {
            int32_t sample = opn_center(samples[2u * i], &s->dc);
            s->color += (sample - (s->color >> 8)) * color;
            out[at + i] += voice_amp2(clamp((s->color >> 8) * 2, -32768, 32767), m, at + i);
        }
        at += count;
    }
}

static const char *const SN_TIMBRE[] = {"SQUARE", "WHITE", "PERIODIC", "MIX"};
static const char *const SN_CLOCK[] = {"KEY", "HIGH", "MID", "LOW"};
static const preset_t SN76489_PRESETS[] = {
    {"PIXEL SQUARE", {SN_SQUARE, 15, 0, 0, 0, 0, 12, 120}, {1, 75, 105, 25}, 0, 0, TAG(PT_LEAD)},
    {"OCTAVE BASS", {SN_SQUARE, 15, 11, 0, 0, 0, 12, 77}, {0, 55, 109, 25}, 0, 1, TAG(PT_BASS)},
    {"STATIC SNAP", {SN_WHITE, 0, 0, 15, 1, 0, 12, 127}, {0, 25, 0, 14}, 0, 0, TAG(PT_FX)},
    {"DUSTY ARP", {SN_MIX, 15, 8, 10, 0, 24, 7, 104}, {0, 39, 77, 30}, 0, 0, TAG(PT_PLUCK)},
};

static const engine_t ENG_SN76489 = {
    .name = "SN76489",
    .page_title = {"PSG MIX", "PSG PITCH"},
    .edit = {
        {"SOURCE", SN_TIMBRE, 0, 3, SN_SQUARE, F_ENUM, 0},
        {"TONE", 0, 0, 15, 15, F_INT, 0},
        {"AUX", 0, 0, 15, 0, F_INT, 0},
        {"NOISE", 0, 0, 15, 0, F_INT, 0},
        {"CLOCK", SN_CLOCK, 0, 3, 0, F_ENUM, 0},
        {"NOISE TUNE", 0, -24, 24, 0, F_SEMI, 0},
        {"AUX TUNE", 0, -24, 24, 12, F_SEMI, 0},
        {"COLOR", 0, 0, 127, 120, F_PCT, 0},
    },
    .presets = SN76489_PRESETS, .npresets = NELEM(SN76489_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E7},
    .poly = NVOICE, .note_on = sn76489_note_on, .render = sn76489_render,
};
