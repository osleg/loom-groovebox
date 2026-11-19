/* SPDX-License-Identifier: GPL-3.0-only
 * X0X's 9W9 TR-909 circuit voices (athousanddetails, after Matthew Cieplak's
 * ER-99), adapted for FM-1 Instrument on 2026-11-19 with mono stems and
 * original synthesized metal percussion. Fixed sample rate: 44.1 kHz. */
#pragma once
#include <stdint.h>
#include "drum909_dsp.h"

enum { DR_BD, DR_SD, DR_LT, DR_MT, DR_HT, DR_RS, DR_CP, DR_CH, DR_OH, DR_CR, DR_RD, DR_NUM };

#define DR_NZ_HIST 256           /* noise history kept across blocks (filter warm-up) */
#define DR_NZ_BLOCK 32
#define DR_NZ_BUF (DR_NZ_HIST + DR_NZ_BLOCK)

/* BD and SD: 9W9's er99_bt_t */
typedef struct {
    /* panel values (engineering units) */
    float tune, sweep_depth, sweep_time, decay, attack, click_tone, level;
    float tune2, osc2_mix, snappy, noise_decay, noise_hp, amp_hold, pitch_mod;
    float drive;
    int32_t dist_type;
    d9_shape_t shape;
    /* runtime */
    uint32_t ph, ph2;
    d9_env_t pitch, amp, click_env, noise_env;
    d9_biquad_t click_lp, noise_hpf, noise_lpf, dc_block;
    int32_t noise_hold, noise_gated, amp_hold_left, bd_phold, impulse, mute;
    int32_t click_stale, click_warm;   /* skipped click filter: see d9_render_bd */
    float bd_df, bd_mult, bd_base, out_gain;
    float crush_st[2];
} d9_bt_t;

/* LT/MT/HT: 9W9's er99_tom_t (three VCOs); reads the panel from a d9_bt_t */
typedef struct {
    uint32_t ph[3];
    d9_env_t env[3], pitch, noise_env;
    d9_biquad_t noise_bp, dc_block;
    float out_gain, noise_level;
    float crush_st[2];
    int32_t mute;
    int32_t stick_stale, stick_warm;   /* skipped stick filter: see d9_render_tom */
} d9_tom_t;

typedef struct {
    float tune, tune2, res, decay, noise_mix, drive, level, accent;
    int32_t dist_type;
    d9_shape_t shape;
    d9_biquad_t bp1, bp2, hp;
    d9_env_t amp;
    float crush_st[2];
    int32_t impulse, mute;
} d9_rim_t;

typedef struct {
    float tune, res, spread, burst_decay, tail_decay, tail_level, drive, level, accent;
    int32_t dist_type;
    d9_shape_t shape;
    d9_biquad_t bp, hp;
    d9_env_t burst, tail;
    float crush_st[2];
    float next_pulse;
    int32_t pulse_index, mute;
} d9_clap_t;

typedef struct {
    d9_biquad_t bands[3];
    d9_env_t out, strike;
    uint32_t phase[3], step[3];
    int32_t mute;
} d9_metal_t;

struct drum909 {
    d9_bt_t bt[5];                /* BD SD LT MT HT panels; BD and SD voices */
    d9_tom_t tom[3];              /* LT MT HT voices */
    d9_rim_t rim;
    d9_clap_t clap;
    d9_metal_t metal[4];           /* closed hat, open hat, crash, ride */
    uint32_t noise;
    float nz_buf[DR_NZ_BUF];       /* prior noise followed by the current block */
};
typedef struct drum909 drum909_t;

void drum909_init(drum909_t *d);
void drum909_trigger(drum909_t *d, int voice, float vel, float pitch, float decay,
                     float snap, int accent);
void drum909_stems(drum909_t *d, float *const stem[DR_NUM], int n);
int drum909_voice_active(const drum909_t *d, int voice);
