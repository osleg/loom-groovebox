/* SPDX-License-Identifier: GPL-3.0-only
 * Modified for FM-1 Instrument, 2026-11-19: mono voice stems, hit controls,
 * and synthesis to replace the 909 sample players.
 * 9W9 by A Thousand Details - https://github.com/athousanddetails/schwung-9W9
 * 9W9 copyright (C) 2026 A Thousand Details. */
/* The 9W9 TR-909 voices on the FM-1. A port of 9W9's er99_engine.c,
 * er99_circuit.h, er99_tom909.h and er99_perc909.h (GPL-3.0): the circuit models,
 * the fitted constants, the defaults and the per-trigger pinning are 9W9's, line
 * for line; only the arithmetic substrate changed (see drum909_dsp.h). Comments
 * that explain WHY a constant is what it is live in 9W9 and are not repeated. */
#define X0X_DRUM_TABLES_DEFINE              /* the shared lookup tables live here (x0x_drum_tables.h) */
#include "drum909.h"
#include <string.h>

#define D9_VCA_VT 0.0002f
#define D9_VCA_NORM (1.0f / (1.0f - D9_VCA_VT))
#define D9_SNAPPY_MIX 1.95f
#define D9_BD_BASE_HZ 49.0f
#define D9_BD_DF_PER_MS 4.6f
#define D9_BD_PHOLD_MS 16.0f
#define D9_INV_TANH_1_6 1.0849887132644653f    /* 1 / tanhf(1.6f) */
#define D9_INV_TANH_2_0 1.037314772605896f     /* 1 / tanhf(2.0f) */
#define D9_PH_QUARTER 0x40000000u              /* 9W9 starts every hit at phase 0.25 */

/* 9W9's back-to-back diode rounding at the two fixed drives the circuits use */
static inline float d9_diode16(float x) { return d9_tanh(1.6f * x) * D9_INV_TANH_1_6; }
static inline float d9_diode20(float x) { return d9_tanh(2.0f * x) * D9_INV_TANH_2_0; }

static void d9_rim_retune(d9_rim_t *v)
{
    d9_biquad_set(&v->bp1, D9_BP, v->tune, v->res);
    d9_biquad_set(&v->bp2, D9_BP, v->tune2, v->res * 1.1f);
}

static void d9_clap_retune(d9_clap_t *v) { d9_biquad_set(&v->bp, D9_BP, v->tune, v->res); }

static void d9_bt_init(d9_bt_t *v)
{
    d9_biquad_set(&v->dc_block, D9_HP, 20.0f, 0.7071f);
    d9_env_set(&v->pitch, v->tune);
    d9_env_set(&v->amp, 0.0f);
    d9_env_set(&v->click_env, 0.0f);
    d9_env_set(&v->noise_env, 0.0f);
    d9_biquad_set(&v->click_lp, D9_LP, v->click_tone > 0.0f ? v->click_tone : 3000.0f, 0.7071f);
    d9_biquad_set(&v->noise_hpf, D9_HP, v->noise_hp > 0.0f ? v->noise_hp : 800.0f, 0.7071f);
    d9_biquad_set(&v->noise_lpf, D9_LP, 6500.0f, 0.7071f);
    if (v->pitch_mod <= 0.0f)
        v->pitch_mod = 1.0f;
    d9_shape_prep(&v->shape, v->drive, v->dist_type);
}

void drum909_init(drum909_t *d)
{
    uint8_t *p = (uint8_t *)d;
    for (unsigned i = 0; i < sizeof(*d); ++i)
        p[i] = 0;
    d->noise = 0xC0FFEEu;

    for (int i = 0; i < 5; ++i) {
        d9_bt_t *b = &d->bt[i];
        b->drive = 2.0f; b->level = 0.8f; b->click_tone = 3000.0f;
        switch (i) {
        case 0:
            b->tune = 13.0f; b->sweep_depth = 0.0f; b->pitch_mod = 1.0f;
            b->decay = 1380.0f; b->amp_hold = 40.0f;
            b->attack = 0.10f; b->click_tone = 2500.0f;
            b->drive = 0.2f; b->level = 1.0f;
            break;
        case 1:
            b->tune = 205.0f; b->tune2 = 325.0f; b->osc2_mix = 0.40f;
            b->sweep_depth = 1.045f; b->sweep_time = 30.0f;
            b->decay = 340.0f; b->attack = 0.15f;
            b->snappy = 0.5f; b->noise_decay = 1200.0f; b->noise_hp = 1000.0f;
            b->drive = 1.8f; b->level = 0.62f;
            break;
        case 2:
            b->tune = 68.0f; b->sweep_depth = 1.12f; b->sweep_time = 200.0f;
            b->decay = 1700.0f; b->attack = 0.18f; b->level = 0.55f;
            break;
        case 3:
            b->tune = 102.0f; b->sweep_depth = 1.12f; b->sweep_time = 180.0f;
            b->decay = 1050.0f; b->attack = 0.18f; b->level = 0.55f;
            break;
        default:
            b->tune = 132.0f; b->sweep_depth = 1.12f; b->sweep_time = 160.0f;
            b->decay = 1100.0f; b->attack = 0.18f; b->level = 0.55f;
            break;
        }
        d9_bt_init(b);
    }
    for (int i = 0; i < 3; ++i) {
        d9_tom_t *t = &d->tom[i];
        for (int j = 0; j < 3; ++j)
            d9_env_set(&t->env[j], 0.0f);
        d9_env_set(&t->pitch, 100.0f);
        d9_env_set(&t->noise_env, 0.0f);
        d9_biquad_set(&t->noise_bp, D9_BP, 1200.0f, 1.2f);
        d9_biquad_set(&t->dc_block, D9_HP, 20.0f, 0.7071f);
    }

    d9_rim_t *r = &d->rim;
    r->tune = 210.0f; r->tune2 = 480.0f; r->res = 10.0f;
    r->decay = 200.0f; r->noise_mix = 0.35f;
    r->drive = 1.4f; r->dist_type = 0; r->level = 1.1f;
    d9_biquad_set(&r->bp1, D9_BP, r->tune, r->res);
    d9_biquad_set(&r->bp2, D9_BP, r->tune2, r->res * 1.1f);
    d9_biquad_set(&r->hp, D9_HP, 120.0f, 0.7071f);
    d9_env_set(&r->amp, 0.0f);
    r->accent = 1.0f;
    d9_shape_prep(&r->shape, r->drive, r->dist_type);

    d9_clap_t *c = &d->clap;
    c->tune = 950.0f; c->res = 2.0f;
    c->spread = 12.0f; c->burst_decay = 50.0f;
    c->tail_decay = 345.0f; c->tail_level = 0.58f;
    c->drive = 1.6f; c->dist_type = 0; c->level = 1.3f;
    d9_biquad_set(&c->bp, D9_BP, c->tune, c->res);
    d9_biquad_set(&c->hp, D9_HP, 400.0f, 0.7071f);
    d9_env_set(&c->burst, 0.0f);
    d9_env_set(&c->tail, 0.0f);
    c->pulse_index = 4;
    c->accent = 1.0f;
    d9_shape_prep(&c->shape, c->drive, c->dist_type);

}

/* ===================================================================== */
/* Triggers (er99_engine_trigger and the voices' *_trigger)                */
/* ===================================================================== */
static void d9_bt_trigger(d9_bt_t *v, int bd, float accent, float ratio)
{
    if (v->click_stale && v->mute > 0)
        v->click_warm = 1;
    v->click_stale = 0;
    v->ph = v->ph2 = D9_PH_QUARTER;
    d9_biquad_reset(&v->dc_block);
    if (bd) {
        const float tau = v->tune < 6.0f ? 6.0f : (v->tune > 32.0f ? 32.0f : v->tune);
        const float m = v->sweep_depth < 0.0f ? 0.0f : (v->sweep_depth > 1.0f ? 1.0f : v->sweep_depth);
        const float pm = v->pitch_mod > 0.0f ? v->pitch_mod : 1.0f;
        v->bd_base = D9_BD_BASE_HZ * (1.0f + m * (pm - 1.0f)) * ratio;
        v->bd_df = D9_BD_DF_PER_MS * tau * (1.0f + 1.2f * m) * ratio;
        v->bd_mult = d9_exp_small(-1.0f / (tau * D9_MS));
        v->bd_phold = (int32_t)(D9_BD_PHOLD_MS * D9_MS);
        d9_env_set(&v->pitch, v->bd_base);
    } else {
        d9_env_set(&v->pitch, v->tune * v->sweep_depth);
        d9_env_exp(&v->pitch, v->tune, v->sweep_time * D9_MS);
    }
    d9_env_set(&v->amp, 1.0f);
    v->amp_hold_left = (int32_t)(v->amp_hold * D9_MS);
    if (v->amp_hold_left <= 0)
        d9_env_exp(&v->amp, 0.00001f, v->decay * D9_MS);
    d9_env_set(&v->click_env, 1.0f);
    d9_env_exp(&v->click_env, 0.00001f, 3.0f * D9_MS);
    if (v->snappy > 0.0f) {
        d9_env_set(&v->noise_env, 1.0f);
        v->noise_hold = (int32_t)(24.0f * D9_MS);
        v->noise_gated = 0;
    }
    v->impulse = 2;
    v->out_gain = v->level * accent;
    const float longest = v->decay > v->noise_decay ? v->decay : v->noise_decay;
    v->mute = d9_ceil((longest + 20.0f) * D9_MS);
}

static void d9_tom_trigger(d9_tom_t *t, const d9_bt_t *p, float accent)
{
    if (t->stick_stale && t->mute > 0)
        t->stick_warm = 1;
    t->stick_stale = 0;
    for (int i = 0; i < 3; ++i)
        t->ph[i] = D9_PH_QUARTER;
    d9_biquad_reset(&t->dc_block);
    d9_env_set(&t->pitch, p->tune * p->sweep_depth);
    d9_env_exp(&t->pitch, p->tune, p->sweep_time * D9_MS);
    const float d1 = p->decay;
    d9_env_set(&t->env[0], 1.0f);
    d9_env_exp(&t->env[0], 0.00001f, d1 * D9_MS);
    d9_env_set(&t->env[1], 0.55f);
    d9_env_exp(&t->env[1], 0.00001f, 110.0f * D9_MS);
    d9_env_set(&t->env[2], 0.45f);
    d9_env_exp(&t->env[2], 0.00001f, 70.0f * D9_MS);
    t->noise_level = p->attack;
    d9_env_set(&t->noise_env, 1.0f);
    d9_env_exp(&t->noise_env, 0.00001f, 8.0f * D9_MS);
    t->out_gain = p->level * accent;
    t->mute = d9_ceil((d1 + 60.0f) * D9_MS);
}

/* Original metal synthesis for the four 909 voices supplied as samples
 * upstream. Each piece blends struck inharmonic modes with a filtered noise
 * transient. The ride keeps a pitched bell, the crash spreads more widely,
 * and the closed and open hats share the same modes with different decays. */
static void d9_metal_trigger(d9_metal_t *m, int voice, float gain, float pitch, float decay)
{
    static const float hat[3] = {2947.0f, 4765.0f, 7211.0f};
    static const float crash[3] = {1847.0f, 3689.0f, 6817.0f};
    static const float ride[3] = {2423.0f, 4111.0f, 5939.0f};
    static const float tail_ms[4] = {105.0f, 420.0f, 1450.0f, 1900.0f};
    static const float level[4] = {0.65f, 0.55f, 0.48f, 0.46f};
    const float *modes = voice <= DR_OH ? hat : voice == DR_CR ? crash : ride;
    const int type = voice - DR_CH;
    for (int i = 0; i < 3; i++) {
        const float f = modes[i] * pitch;
        m->phase[i] = (uint32_t)i << 29;
        m->step[i] = d9_inc(f);
        d9_biquad_reset(&m->bands[i]);
        d9_biquad_set(&m->bands[i], D9_BP, f, voice == DR_RD ? 2.6f : 1.25f);
    }
    const float length = tail_ms[type] * decay * D9_MS;
    d9_env_set(&m->out, level[type] * gain);
    d9_env_exp(&m->out, 0.00001f, length);
    d9_env_set(&m->strike, 1.0f);
    d9_env_exp(&m->strike, 0.00001f,
               (voice <= DR_OH ? 18.0f : voice == DR_CR ? 80.0f : 165.0f) * D9_MS);
    m->mute = d9_ceil(length);
}

void drum909_trigger(drum909_t *d, int voice, float vel, float pitch, float decay,
                     float snap, int accent)
{
    const float gain = 1.0f + vel * ((float)accent / 127.0f);
    switch (voice) {
    case DR_BD: {
        d9_bt_t *b = &d->bt[DR_BD];
        b->decay = 1380.0f * decay;
        b->attack = 0.10f * snap;
        d9_bt_trigger(b, 1, gain, pitch);
        break;
    }
    case DR_SD: {
        d9_bt_t *b = &d->bt[DR_SD];
        b->tune = 205.0f * pitch;
        b->tune2 = b->tune * 1.585f;
        b->sweep_depth = 1.045f;
        b->sweep_time = 30.0f;
        b->decay = 340.0f * decay;
        b->noise_decay = 1200.0f * decay;
        b->snappy = 0.50f * snap;
        d9_bt_trigger(b, 0, gain, pitch);
        break;
    }
    case DR_LT: case DR_MT: case DR_HT: {
        static const float tune[3] = {68.0f, 102.0f, 132.0f};
        static const float duration[3] = {1700.0f, 1050.0f, 1100.0f};
        const int tom = voice - DR_LT;
        d9_bt_t *p = &d->bt[voice];
        p->tune = tune[tom] * pitch;
        p->decay = duration[tom] * decay;
        p->attack = 0.18f * snap;
        d9_tom_trigger(&d->tom[tom], p, gain);
        break;
    }
    case DR_RS: {
        d9_rim_t *r = &d->rim;
        r->tune = 210.0f * pitch;
        r->tune2 = r->tune * 2.286f;
        d9_rim_retune(r);
        r->decay = 200.0f * decay;
        d9_env_set(&r->amp, 1.0f);
        d9_env_exp(&r->amp, 0.00001f, r->decay * D9_MS);
        r->impulse = 2;
        r->accent = gain;
        r->mute = d9_ceil((r->decay + 20.0f) * D9_MS);
        break;
    }
    case DR_CP: {
        d9_clap_t *c = &d->clap;
        c->tune = 950.0f * pitch;
        d9_clap_retune(c);
        c->tail_decay = 345.0f * decay;
        c->pulse_index = 0;
        c->next_pulse = 0.0f;
        d9_env_set(&c->tail, 0.0f);
        c->accent = gain;
        c->mute = d9_ceil((c->tail_decay + c->spread * 4.0f + 40.0f) * D9_MS);
        break;
    }
    default:
        if (voice == DR_CH) {
            d9_metal_t *open = &d->metal[DR_OH - DR_CH];
            const int fade = d9_ceil(3.0f * D9_MS);
            if (open->mute > fade) {
                d9_env_exp(&open->out, 0.00001f, (float)fade);
                open->mute = fade;
            }
        }
        d9_metal_trigger(&d->metal[voice - DR_CH], voice, gain, pitch, decay);
        break;
    }
}

/* ===================================================================== */
/* Rendering                                                              */
/* ===================================================================== */
/* A filter on the shared noise that a voice stopped running (its envelope had
 * run out) is brought back to the state 9W9's would have, by running it over
 * the noise it missed. Both filters forget fast (the click LP's poles are at
 * r = 0.78, the stick BP's at 0.93: r^DR_NZ_HIST < 2e-8), so the last
 * DR_NZ_HIST samples are all that matter. `end` points one past the last
 * sample the filter should have seen; the DR_NZ_HIST before it are readable. */
static void d9_warm(d9_biquad_t *f, const float *end)
{
    d9_biquad_reset(f);
    for (int i = -DR_NZ_HIST; i < 0; ++i)
        d9_biquad_tick(f, end[i]);
}

/* Kick (er99_bt_render with the stock sweep, one shell) */
static void d9_render_bd(d9_bt_t *v, const float *nz, float *out, int n)
{
    const int m = n < v->mute ? n : v->mute;
    v->mute -= m;
    d9_env_anchor(&v->amp);
    d9_env_anchor(&v->click_env);
    const float attack = v->attack;
    if (v->click_warm) {                 /* retriggered while its click filter was skipped */
        d9_warm(&v->click_lp, nz);
        v->click_warm = 0;
    }
    for (int i = 0; i < m; ++i) {
        const float f = v->bd_base + v->bd_df;
        if (v->bd_phold > 0)
            --v->bd_phold;
        else
            v->bd_df *= v->bd_mult;
        if (v->amp_hold_left > 0 && --v->amp_hold_left == 0)
            d9_env_exp(&v->amp, 0.00001f, v->decay * 0.001f * D9_SR);
        const float amp = d9_env_tick(&v->amp);

        v->ph += d9_inc(f);
        float o = d9_diode16(d9_tri(v->ph));
        o += 0.05f * o * o;
        o = d9_biquad_tick(&v->dc_block, o);
        const float body = d9_shape(&v->shape, o, v->crush_st) * amp;

        /* the beater click; once its 3 ms envelope has run out it sits at -100 dB
         * for the rest of the note, and is skipped */
        float click = 0.0f;
        if (v->impulse > 0 || v->click_env.left) {
            if (v->impulse > 0) { click += 1.0f; v->impulse--; }
            click += d9_biquad_tick(&v->click_lp, nz[i]);
            click *= d9_env_tick(&v->click_env) * attack;
        }
        out[i] = (body + click) * v->out_gain;
    }
    if (!(v->impulse > 0 || v->click_env.left))
        v->click_stale = 1;
    if (v->click_stale && v->mute == 0) {  /* note over: freeze where 9W9's froze */
        d9_warm(&v->click_lp, nz + m);
        v->click_stale = 0;
    }
}

/* Snare (er99_bt_render, two shells + ENV4 noise). Its click path is pinned
 * off (attack 0 at every trigger), so it is not computed: 0 either way. */
static void d9_render_sd(d9_bt_t *v, const float *nz, float *out, int n)
{
    const int m = n < v->mute ? n : v->mute;
    v->mute -= m;
    d9_env_anchor(&v->pitch);
    d9_env_anchor(&v->amp);
    d9_env_anchor(&v->noise_env);
    const float ratio = v->tune > 1.0f ? v->tune2 / v->tune : 1.0f;
    const float mix2 = v->osc2_mix, inv_mix = 1.0f / (1.0f + v->osc2_mix);
    for (int i = 0; i < m; ++i) {
        const float f = d9_env_tick(&v->pitch);
        float amp = d9_env_tick(&v->amp);
        amp = amp > D9_VCA_VT ? (amp - D9_VCA_VT) * D9_VCA_NORM : 0.0f;

        v->ph += d9_inc(f);
        v->ph2 += d9_inc(f * ratio);
        float o = d9_diode20(d9_tri(v->ph));
        o += d9_diode20(d9_tri(v->ph2)) * mix2;
        o *= inv_mix;
        float y = d9_shape(&v->shape, o, v->crush_st) * amp;

        if (v->snappy > 0.0f && !v->noise_gated) {
            if (v->noise_hold > 0 && --v->noise_hold == 0)
                d9_env_exp(&v->noise_env, 0.00001f,
                           (v->noise_decay > 0.0f ? v->noise_decay : 120.0f) * 0.7f * 0.001f * D9_SR);
            float env = d9_env_tick(&v->noise_env);
            if (v->noise_hold <= 0) {
                env = env > D9_VCA_VT ? (env - D9_VCA_VT) * D9_VCA_NORM : 0.0f;
                if (env <= 0.0f)
                    v->noise_gated = 1;
            }
            y += d9_biquad_tick(&v->noise_lpf, d9_biquad_tick(&v->noise_hpf, nz[i])) * env * v->snappy
               * D9_SNAPPY_MIX;
        }
        out[i] = y * v->out_gain;
    }
}

/* Toms (er99_tom909_render): three VCOs on their own envelopes + stick noise.
 * The two upper partials and the stick are attack features (110 / 70 / 8 ms);
 * once their envelopes have run out (-100 dB) they are skipped. */
static void d9_render_tom(d9_tom_t *t, const d9_bt_t *p, const float *nz, float *out, int n)
{
    const int m = n < t->mute ? n : t->mute;
    t->mute -= m;
    d9_env_anchor(&t->pitch);
    d9_env_anchor(&t->env[0]);
    d9_env_anchor(&t->env[1]);
    d9_env_anchor(&t->env[2]);
    d9_env_anchor(&t->noise_env);
    const float stick_g = t->noise_level * 1.5f;
    if (t->stick_warm) {
        d9_warm(&t->noise_bp, nz);
        t->stick_warm = 0;
    }
    for (int i = 0; i < m; ++i) {
        const float f = d9_env_tick(&t->pitch);
        float o = 0.0f;
        t->ph[0] += d9_inc(f);
        o += d9_diode20(d9_tri(t->ph[0])) * d9_env_tick(&t->env[0]);
        if (t->env[1].left) {
            t->ph[1] += d9_inc(f * 1.50f);
            o += d9_diode20(d9_tri(t->ph[1])) * d9_env_tick(&t->env[1]);
        }
        if (t->env[2].left) {
            t->ph[2] += d9_inc(f * 2.75f);
            o += d9_diode20(d9_tri(t->ph[2])) * d9_env_tick(&t->env[2]);
        }
        o *= 0.55f;
        float stick = 0.0f;
        if (t->noise_env.left)
            stick = d9_biquad_tick(&t->noise_bp, nz[i]) * d9_env_tick(&t->noise_env) * stick_g;
        float y = d9_shape(&p->shape, o + stick, t->crush_st);
        y = d9_biquad_tick(&t->dc_block, y);
        out[i] = y * t->out_gain;
    }
    if (!t->noise_env.left)
        t->stick_stale = 1;
    if (t->stick_stale && t->mute == 0) {
        d9_warm(&t->noise_bp, nz + m);
        t->stick_stale = 0;
    }
}

/* Rim shot (er99_rim909_render) */
static void d9_render_rim(d9_rim_t *v, const float *nz, float *out, int n)
{
    const int m = n < v->mute ? n : v->mute;
    v->mute -= m;
    d9_env_anchor(&v->amp);
    const float g = v->level;
    for (int i = 0; i < m; ++i) {
        float exc = 0.0f;
        if (v->impulse > 0) {
            exc += 1.0f + nz[i] * v->noise_mix;
            v->impulse--;
        }
        const float a = d9_env_tick(&v->amp);
        float y = d9_biquad_tick(&v->bp1, exc) * 60.0f + d9_biquad_tick(&v->bp2, exc) * 32.0f;
        y = d9_shape(&v->shape, y * a, v->crush_st);
        y = d9_biquad_tick(&v->hp, y);
        out[i] = y * g * v->accent;
    }
}

/* Hand clap (er99_clap909_render) */
static void d9_render_clap(d9_clap_t *v, const float *nz, float *out, int n)
{
    static const float amp[4] = { 1.22f, 1.35f, 1.68f, 1.0f };
    const int m = n < v->mute ? n : v->mute;
    v->mute -= m;
    d9_env_anchor(&v->burst);
    d9_env_anchor(&v->tail);
    for (int i = 0; i < m; ++i) {
        if (v->pulse_index < 4) {
            if (v->next_pulse <= 0.0f) {
                d9_env_set(&v->burst, amp[v->pulse_index]);
                d9_env_exp(&v->burst, 0.00001f, v->burst_decay * D9_MS);
                if (v->pulse_index == 3) {
                    d9_env_set(&v->tail, v->tail_level);
                    d9_env_exp(&v->tail, 0.00001f, v->tail_decay * D9_MS);
                }
                v->pulse_index++;
                v->next_pulse = v->spread * D9_MS;
            }
            v->next_pulse -= 1.0f;
        }
        const float env = d9_env_tick(&v->burst) + d9_env_tick(&v->tail);
        float y = d9_biquad_tick(&v->bp, nz[i]) * env;
        y = d9_shape(&v->shape, y, v->crush_st);
        y = d9_biquad_tick(&v->hp, y);
        out[i] = y * v->level * v->accent;
    }
}

/* Hats, ride, crash (render_sampler). Past the sample's end and at a whole-number rate: after Optimist
 * (github.com/w0ts/optimist 537fbf00, GPL-3.0-only), the same samples */
static void d9_render_metal(d9_metal_t *m, int voice, const float *noise, float *out, int n)
{
    const int count = n < m->mute ? n : m->mute;
    m->mute -= count;
    d9_env_anchor(&m->out);
    d9_env_anchor(&m->strike);
    for (int i = 0; i < count; i++) {
        float wash = 0.0f, partial[3];
        for (int j = 0; j < 3; j++) {
            wash += d9_biquad_tick(&m->bands[j], noise[i]);
            m->phase[j] += m->step[j];
            partial[j] = d9_tri(m->phase[j]);
        }
        const float attack = d9_env_tick(&m->strike);
        const float shimmer = partial[0] * partial[1] * partial[2];
        const float ping = voice == DR_RD ? partial[0] * 0.20f : 0.0f;
        out[i] = (wash * 0.50f + (shimmer * 0.22f + ping) * attack) * d9_env_tick(&m->out);
    }
}

int drum909_voice_active(const drum909_t *d, int voice)
{
    if (voice < DR_LT) return d->bt[voice].mute > 0;
    if (voice <= DR_HT) return d->tom[voice - DR_LT].mute > 0;
    if (voice == DR_RS) return d->rim.mute > 0;
    if (voice == DR_CP) return d->clap.mute > 0;
    return d->metal[voice - DR_CH].mute > 0;
}

void drum909_stems(drum909_t *d, float *const stem[DR_NUM], int n)
{
    if (n > DR_NZ_BLOCK)
        n = DR_NZ_BLOCK;
    float *noise = d->nz_buf + DR_NZ_HIST;
    for (int i = 0; i < n; i++)
        noise[i] = d9_noise(&d->noise);
    for (int v = 0; v < DR_NUM; v++) {
        float *out = stem[v];
        if (!out || !drum909_voice_active(d, v))
            continue;
        switch (v) {
        case DR_BD: d9_render_bd(&d->bt[v], noise, out, n); break;
        case DR_SD: d9_render_sd(&d->bt[v], noise, out, n); break;
        case DR_LT: case DR_MT: case DR_HT:
            d9_render_tom(&d->tom[v - DR_LT], &d->bt[v], noise, out, n);
            break;
        case DR_RS: d9_render_rim(&d->rim, noise, out, n); break;
        case DR_CP: d9_render_clap(&d->clap, noise, out, n); break;
        default: d9_render_metal(&d->metal[v - DR_CH], v, noise, out, n); break;
        }
    }
    memmove(d->nz_buf, d->nz_buf + n, DR_NZ_HIST * sizeof(float));
}
