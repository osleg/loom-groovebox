/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * CHIMERA's audio-rate voice in C: the branched FDTD airway (crates/chimera/src/airway/network.rs),
 * the parametric glottis (sources/glottis.rs) and the per-sample part of HumanVoice::render
 * (voice/human_voice.rs: lung pressure ramp, glottis coupling, DC blocker, limiter).
 * Control rate (anatomy, gestures, articulators) is not here yet: ch_block() takes what one
 * HumanVoice::control() step leaves in the network.
 *
 * The port is literal: the same equations, the same order, the same constants and PRNG seeds.
 * `ch_real` is float, or double with -DCH_DOUBLE: double to check the port against CHIMERA sample
 * for sample, float for the FM-1. No allocation, no library calls but the math functions. */
#ifndef CH_VOICE_H
#define CH_VOICE_H
#include <stdint.h>

/* float unless CH_DOUBLE (the lab's check against CHIMERA builds with -DCH_DOUBLE) */
#if !defined(CH_DOUBLE) && !defined(CH_FLOAT)
#define CH_FLOAT 1
#endif
#ifdef CH_FLOAT
typedef float ch_real;
#else
typedef double ch_real;
#endif

#define CH_MAXN 96      /* pressure nodes (male at 44.1 kHz: 46) */
#define CH_MAXB 96      /* branches */
#define CH_MAXR 2       /* radiators: lips, nostrils */
#define CH_MAXNOISE 16  /* turbulence sources */

enum { CH_SERIES = 0, CH_PORT = 1 };

typedef struct {
    uint16_t src, fwd, bwd;
    ch_real gain, hp, lp1, lp2, lp_coef, hp_coef, level;
} ch_noise_t;

/* ch_fast.c: a branch as the per-sample flow loop reads it */
typedef struct {
    ch_real l, ibq, kf, kb;      /* inertance, 1 / (L/T + R), kinetic coefficients (forward, backward) */
    ch_real u, mom, l_prev, psrc;
    uint16_t a, b;
    uint8_t closed, pad;
} ch_fbr_t;

/* ch_fast.c: a node as the per-sample stages read it */
typedef struct {
    ch_real p, flow, kn;         /* pressure, the flow summed into it this sample, T / C */
    ch_real inj, dinj;           /* moving-wall injection and its ramp */
    ch_real wa, winv, wsd, wk, wv, wy, wrad;   /* wall: m/T - r/2, 1/(m/T + r/2), area term, stiffness, velocity, displacement, radiated share */
    int8_t rad;                  /* radiator index, -1 none */
    uint8_t fixed, sealed, wall;
} ch_fnd_t;

typedef struct {   /* GlottisParams, in its field order */
    ch_real f0, amplitude, abduction, chink, open_quotient, skew, closure_sharpness, effort, jitter,
        shimmer, vibrato_depth, vibrato_rate, drift, fry, diplophonia, closure, chaos;
} ch_glottis_params_t;

typedef struct {
    ch_glottis_params_t p;
    ch_real fs, phase, cycle_amp, cycle_period_scale, chaos_x, vib_phase;
    uint64_t cycle_index;
    uint32_t rng;
    uint32_t drift_rng;
    ch_real drift_y[3], drift_a[3];
    ch_real drift_value, pitch_factor, voicing, voicing_sm, dp_sm, dp_coef, voice_coef, area, f0_now,
        threshold, static_sm, closure_sm, amp_sm, sm_coef;
    int32_t drift_counter;
} ch_glottis_t;

typedef struct {
    /* network: sizes and switches */
    uint16_t n, nb, nr;
    ch_real fs, t, inv_t;
    ch_real closed_area, min_node_area, viscous_scale, viscous_omega, noise_scale, squeeze_area, vol_lp_coef;
    uint8_t enable_volume_source, enable_junction_floor, energy_safe, symmetric_kinetic;

    /* nodes */
    ch_real p[CH_MAXN], area[CH_MAXN], area_target[CH_MAXN], area_step[CH_MAXN];
    ch_real amin[CH_MAXN], amin_target[CH_MAXN], amin_step[CH_MAXN];
    ch_real dx[CH_MAXN], dx_target[CH_MAXN], dx_step[CH_MAXN];
    ch_real vol[CH_MAXN], inv_a[CH_MAXN], inv_amin[CH_MAXN], inv_lsum[CH_MAXN];
    ch_real half_l[CH_MAXN], half_r[CH_MAXN], sqrt_a[CH_MAXN], flow_sum[CH_MAXN];
    ch_real spill[CH_MAXN], vol_src1[CH_MAXN], vol_src2[CH_MAXN];
    ch_real wall_m[CH_MAXN], wall_r[CH_MAXN], wall_k[CH_MAXN], wall_perim[CH_MAXN], wall_rad[CH_MAXN];
    ch_real wall_y[CH_MAXN], wall_v[CH_MAXN], visc_f[CH_MAXN];
    int8_t rad_of[CH_MAXN];
    uint8_t fixed[CH_MAXN], has_wall[CH_MAXN], is_junction[CH_MAXN];
    uint16_t nbr_start[CH_MAXN + 1], nbr_list[2 * CH_MAXB];

    /* branches */
    uint16_t b_a[CH_MAXB], b_b[CH_MAXB];
    uint8_t b_kind[CH_MAXB], port_half[CH_MAXB];
    ch_real u[CH_MAXB], mom[CH_MAXB], l_prev[CH_MAXB], psrc[CH_MAXB], branch_area[CH_MAXB];
    ch_real port_area[CH_MAXB], port_area_target[CH_MAXB], port_area_step[CH_MAXB];
    ch_real port_len[CH_MAXB], port_kin[CH_MAXB], port_kex[CH_MAXB], port_slit[CH_MAXB], port_extra_r[CH_MAXB];

    /* radiation */
    ch_real rad_ul[CH_MAXR], rad_l_prev[CH_MAXR], rad_u[CH_MAXR], rad_prev_total;

    /* turbulence */
    ch_noise_t noise[CH_MAXNOISE];
    uint8_t n_noise;
    uint32_t rng;

    /* the voice around the network */
    ch_glottis_t glottis;
    uint16_t glottis_port, lungs, sub_node, supra;
    uint16_t ctrl_every;
    ch_real p_lung, p_lung_now, p_lung_step;
    ch_real output_gain, dc_x, dc_y, dc_coef, lim_env, lim_release;
    int32_t lim_hold, lim_hold_n;

    /* ch_fast.c only. What the per-sample stages read and write, one struct per branch and per
     * node, so each access is one load from one pointer (on the FM-1 a field of a separate array a
     * few kilobytes into the struct costs an address computation of its own) */
    uint16_t geo_every, geo_left;
    ch_fbr_t fb[CH_MAXB];
    ch_fnd_t fn[CH_MAXN];
    ch_real nd_vol_upd[CH_MAXN];                  /* each node's volume at the last update */
    ch_real nd_lsum_static[CH_MAXN];              /* sum of 1/L over the node's slow branches */
    ch_real rad_h[CH_MAXR], rad_g[CH_MAXR], rad_num[CH_MAXR], rad_iden[CH_MAXR];
    ch_real vol_lp_coef_n;                        /* the 2-pole low-pass of the injection at the update rate */
    /* the fast-moving geometry (closures, releases), computed every sample as CHIMERA does */
    uint8_t nd_dyn[CH_MAXN], br_dyn[CH_MAXB];
    uint16_t dyn_nodes[CH_MAXN], dyn_branches[CH_MAXB], dyn_coef[CH_MAXN];
    uint16_t n_dyn_nodes, n_dyn_branches, n_dyn_coef;
} ch_voice_t;

/* one control step's result, as HumanVoice::control() leaves it */
typedef struct {
    const ch_real *area_target, *amin_target, *dx_target, *port_area_target;   /* n, n, n, nb */
    const ch_noise_t *noise;   /* src fwd bwd gain lp_coef hp_coef */
    uint8_t n_noise;
    ch_glottis_params_t glottis;
    ch_real p_lung;
} ch_block_t;

void ch_glottis_init(ch_glottis_t *g, ch_real fs);   /* ParametricGlottis::new */
void ch_init_state(ch_voice_t *v);                   /* after the topology is filled in: zero the state */
void ch_block(ch_voice_t *v, const ch_block_t *b);    /* start a control block */
void ch_render(ch_voice_t *v, float *out, uint32_t n);   /* n <= samples left in the block */

/* ch_fast.c: the same voice, with the geometry's derived terms (inertances, losses, compliances,
 * wall and radiation coefficients) recomputed every `geo_every` samples instead of every sample */
void ch_fast_init_state(ch_voice_t *v, int geo_every);
void ch_fast_block(ch_voice_t *v, const ch_block_t *b);
void ch_fast_render(ch_voice_t *v, float *out, uint32_t n);

#endif
