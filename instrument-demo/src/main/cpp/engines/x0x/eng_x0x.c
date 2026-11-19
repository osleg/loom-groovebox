/* SPDX-License-Identifier: GPL-3.0-only
 * FM-1 Instrument bridge for the X0X 808 and 909 synthesis cores. */
#include "thirdparty/drum808.h"
#include "thirdparty/drum909.h"
#include <math.h>

_Static_assert(CTL <= DR_NZ_BLOCK, "909 stem buffer must fit a control block");

typedef struct {
    union {
        drum808_t d808;
        drum909_t d909;
    } kit;
    float stem[DV_NLANE][CTL];
    float tone[DV_NLANE];
    uint8_t owner[DV_NLANE];
    uint8_t instrument[DV_NLANE];
    uint8_t ready;
    uint8_t rendered;
    uint8_t sounding;
} x0x_part_t;

static x0x_part_t *x0x_part(track_t *t)
{
    return (x0x_part_t *)eng_part(t);
}

static voice_t *x0x_reuse(track_t *t, uint32_t note)
{
    const x0x_part_t *part = x0x_part(t);
    const uint32_t lane = drum_lane(note), owner = part->owner[lane];
    if (!owner || owner > NVOICE)
        return 0;
    voice_t *v = &t->v[owner - 1u];
    return v->active && (uint32_t)v->s[0] == lane ? v : 0;
}

static int x0x_is_909(const track_t *t)
{
    return t->engine == ENGI_909;
}

static uint32_t x0x_tom(uint32_t note)
{
    if (note <= 43u || note == 64u)
        return 0;
    if (note >= 48u && note != 62u && note != 63u)
        return 2;
    return 1;
}

static void x0x_note_on(track_t *t, voice_t *v)
{
    static const uint8_t kit808[DV_NLANE] = {D8_BD, D8_SD, D8_CP, D8_CH, D8_OH, D8_MT, D8_RS, D8_CB};
    static const uint8_t kit909[DV_NLANE] = {DR_BD, DR_SD, DR_CP, DR_CH, DR_OH, DR_MT, DR_RS, DR_RD};
    const uint32_t i = (uint32_t)(v - t->v), note = v->note;
    const uint32_t gm = note >= 35u && note <= 81u ? note : 36u + (note + 84u) % 12u;
    const uint32_t lane = drum_lane(gm), old = (uint32_t)v->s[0] & (DV_NLANE - 1u);
    const int is909 = x0x_is_909(t);
    x0x_part_t *part = x0x_part(t);
    int instrument = is909 ? kit909[lane] : kit808[lane], alternate = 0;
    if (!part->ready) {
        if (is909)
            drum909_init(&part->kit.d909);
        else
            drum808_init(&part->kit.d808);
        part->ready = 1;
    }
    if (part->owner[old] == i + 1u)
        part->owner[old] = 0;
    part->owner[lane] = (uint8_t)(i + 1u);
    part->tone[lane] = 0.0f;
    v->s[0] = (int32_t)lane;
    v->env_out = v->vel * 258;
    v->env = 1 << 24;    /* A key-up before the first rendered block preserves the hit. */

    if (lane == DV_TOM) {
        instrument = (is909 ? (int)DR_LT : (int)D8_LT) + (int)x0x_tom(gm);
        alternate = gm >= 60u && gm <= 64u;
    } else if (lane == DV_BELL) {
        if (is909)
            instrument = gm == 49u || gm == 57u ? DR_CR : DR_RD;
        else if (gm == 49u || gm == 51u || gm == 57u || gm == 59u)
            instrument = D8_CY;
    } else if (lane == DV_RIM) {
        alternate = gm == 75u;
    }
    part->instrument[lane] = (uint8_t)instrument;

    const int16_t *p = t->p;
    const int32_t semitones = lane == DV_TOM || lane == DV_BELL ? 0 : DRUM_GM[gm - 35u][1];
    const float pitch = exp2f((p[P_E0] - 64) / 64.0f + semitones / 12.0f);
    const float vel = v->vel / 127.0f;
    if (is909)
        drum909_trigger(&part->kit.d909, instrument, vel, pitch, exp2f((p[P_E2] - 64) / 40.0f),
                        p[P_E3] / 64.0f, p[P_E4]);
    else
        drum808_trigger(&part->kit.d808, instrument, alternate, vel, pitch, p[P_E2], p[P_E3], p[P_E4]);
}

static int x0x_voice_live(const track_t *t, const x0x_part_t *part, uint32_t lane)
{
    const int instrument = part->instrument[lane];
    if (x0x_is_909(t))
        return drum909_voice_active(&part->kit.d909, instrument);
    return drum808_voice_active(&part->kit.d808, instrument);
}

static int32_t x0x_amp(track_t *t, voice_t *v, int32_t adsr)
{
    const uint32_t lane = (uint32_t)v->s[0] & (DV_NLANE - 1u);
    const x0x_part_t *part = x0x_part(t);
    (void)adsr;
    if (!v->active)
        return 0;
    if (part->owner[lane] != (uint32_t)(v - t->v) + 1u ||
        !(part->rendered ? (part->sounding >> lane) & 1u : (uint32_t)x0x_voice_live(t, part, lane))) {
        v->active = v->gate = v->stage = 0;
        v->env = 0;
        return 0;
    }
    v->env = 1 << 24;
    return 32767;
}

static void x0x_block(track_t *t)
{
    x0x_part(t)->rendered = 0;
}

static void x0x_render_stems(track_t *t, x0x_part_t *part, uint32_t n)
{
    float *stem[D8_NUM > DR_NUM ? (int)D8_NUM : (int)DR_NUM] = {0};
    part->sounding = 0;
    for (uint32_t lane = 0; lane < DV_NLANE; lane++) {
        const uint32_t owner = part->owner[lane];
        if (!owner || owner > NVOICE || !t->v[owner - 1u].active || !x0x_voice_live(t, part, lane))
            continue;
        memset(part->stem[lane], 0, n * sizeof(float));
        stem[part->instrument[lane]] = part->stem[lane];
        part->sounding |= (uint8_t)(1u << lane);
    }
    if (part->sounding) {
        if (x0x_is_909(t))
            drum909_stems(&part->kit.d909, stem, (int)n);
        else
            drum808_stems(&part->kit.d808, stem, (int)n);
    }
    part->rendered = 1;
}

static void x0x_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    const uint32_t lane = (uint32_t)v->s[0] & (DV_NLANE - 1u);
    x0x_part_t *part = x0x_part(t);
    vmod_t ml;
    if (part->owner[lane] != (uint32_t)(v - t->v) + 1u)
        return;
    if (!part->rendered)
        x0x_render_stems(t, part, n);
    if (!((part->sounding >> lane) & 1u))
        return;

    const int16_t *p = t->p;
    const int32_t tone = clamp(p[P_E1] + (m->cutoff >> 9), 0, 127);
    const float pole = 0.92f - tone * (0.92f / 127.0f);
    const float drive = 1.0f + p[P_E5] * (2.8f / 127.0f);
    const float gain = (x0x_is_909(t) ? 12000.0f : 19000.0f) * drive;
    float low = part->tone[lane];
    const int muted = (drum_mute[(uint32_t)(t - trk) % NTRK] >> lane) & 1u;
    m = vmod_level(m, &ml, clamp(p[P_LN0 + lane], 0, 127));
    for (uint32_t i = 0; i < n; i++) {
        low = part->stem[lane][i] * (1.0f - pole) + low * pole;
        if (!muted) {
            const float level = low * gain;
            /* Bound the conversion before the fixed-point voice gain. */
            const int32_t s = level >= 32767.0f ? 32767 : level <= -32767.0f ? -32767 : (int32_t)level;
            out[i] += voice_amp2(soft_knee(s, 19000), m, i);
        }
    }
    part->tone[lane] = low;
}

static const preset_t X0X_808_PRESETS[] = {
    {"808 KIT", {64, 76, 64, 64, 72, 0}, {0, 100, 127, 100}, 0, 0, TAG(PT_DRUM)},
    {"DEEP 808", {51, 50, 82, 49, 85, 12}, {0, 100, 127, 100}, 0, 0, TAG(PT_DRUM)},
    {"TIGHT 808", {72, 97, 39, 82, 64, 20}, {0, 100, 127, 100}, 0, 0, TAG(PT_DRUM)},
};
static const preset_t X0X_909_PRESETS[] = {
    {"909 KIT", {64, 76, 64, 64, 72, 0}, {0, 100, 127, 100}, 0, 0, TAG(PT_DRUM)},
    {"HARD 909", {68, 98, 72, 92, 96, 44}, {0, 100, 127, 100}, 0, 0, TAG(PT_DRUM)},
    {"DUB 909", {54, 44, 53, 53, 66, 18}, {0, 100, 127, 100}, 0, 0, FX(0, 0, 27, 20), TAG(PT_DRUM)},
};

#define X0X_ENGINE(label, bank) { \
    .name = label, .page_title = {"VOICE", "MIX"}, \
    .edit = { \
        {"PITCH", 0, 0, 127, 64, F_ST16, 0}, \
        {"TONE", 0, 0, 127, 76, F_PCT, 0}, \
        {"DECAY", 0, 0, 127, 64, F_PCT, 0}, \
        {"SNAP", 0, 0, 127, 64, F_PCT, 0}, \
        {"ACCENT", 0, 0, 127, 72, F_PCT, 0}, \
        {"DRIVE", 0, 0, 127, 0, F_PCT, 0}, \
    }, \
    .presets = bank, .npresets = NELEM(bank), \
    .note_on = x0x_note_on, \
    .render = x0x_render, \
    .amp = x0x_amp, \
    .block = x0x_block, \
    .keys = drum_keys, \
    .knob = {P_E0, P_E1, P_E2, P_E3}, \
    .poly = DV_NLANE, .oneshot = 1, \
}

static const engine_t ENG_808 = X0X_ENGINE("808", X0X_808_PRESETS);
static const engine_t ENG_909 = X0X_ENGINE("909", X0X_909_PRESETS);
#undef X0X_ENGINE
