/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * CHIMERA's human anatomy at control rate, in C: articulators → the 2D midsagittal head
 * (crates/chimera/src/anatomy/human.rs HumanVocalTract::compute, the acoustic parts; the drawing
 * outlines are left out), the area function resampled into the pharynx and oral tubes (resample),
 * the slew limits and the network targets (voice/human_airway.rs commit) and the turbulence noise
 * sources (update_noise). Literal, like ch_voice.c: `ch_real` double matches CHIMERA. */
#ifndef CH_TRACT_H
#define CH_TRACT_H
#include "ch_voice.h"

#define CH_NARTIC 17
#define CH_MAXPTS 160     /* points in an outline (the longest, the inner wall, has ~100) */
#define CH_MAXGRID 64     /* grid lines: 12 pharyngeal, 31 oral, 6 front */
#define CH_MAXTUBE 48     /* sections of the pharynx or oral tube */
#define CH_MAXCROSS 64

/* HumanArtic, in ArticKey order */
enum {
    CH_JAW, CH_TB_X, CH_TB_Y, CH_TT_X, CH_TT_Y, CH_TT_CURL, CH_ROOT, CH_LIP_AP, CH_LIP_PROT, CH_LIP_WIDTH,
    CH_LABIODENTAL, CH_VELUM, CH_LARYNX, CH_EPILARYNX, CH_VENTRICULAR, CH_LATERAL, CH_DORSUM
};

typedef struct { ch_real x, y; } ch_v2;
#define CH_CHUNK 8         /* segments a bounding box covers (ray culling) */
typedef struct {
    ch_v2 p[CH_MAXPTS];
    int n;
    int nc;                                        /* boxes: chunk c covers points c*CH_CHUNK .. +CH_CHUNK */
    ch_real box[CH_MAXPTS / CH_CHUNK + 1][4];      /* min x, min y, max x, max y */
    /* each segment's 1 / its length² (0: too short to count) and its run over its rise (dx / dy, for the inside
     * test), once the line is built: the distance queries multiply instead of dividing (0.8.0: a divide costs tens
     * of cycles on the FM-1, and the contact model makes thousands a step) */
    ch_real il2[CH_MAXPTS], slope[CH_MAXPTS];
} ch_line;

typedef struct {
    ch_v2 inner, outer, mid;
    ch_real d, area, s;
    uint8_t region;    /* 1 pharynx, 2 oral (radial), 3 front (teeth/lips) */
} ch_grid_t;

typedef struct {
    /* the body */
    ch_real k, pharynx, tongue_radius, lip_width;
    ch_real law[8];    /* AreaLaw: pharynx a b, oral a b, front a b, ahead a b */
    /* fixed landmarks (body coordinates) */
    ch_v2 phar_wall[64], palate[64], pns, condyle, polar_c, polar_c2;
    int n_phar_wall, n_palate;

    /* the airway it feeds */
    ch_real fs, dx_stable;
    int n_ph, n_or;
    uint16_t tr_first, tr_branch, tr_n, ph_first, ph_branch, or_first, or_branch, glottis_port, velum_port;
    ch_real gain_glottis, gain_constriction, gain_teeth;
    ch_real max_area_rate, area_rate_gain, seal_rate, seal_lo, seal_hi, seal_ease;

    /* the last frame */
    ch_grid_t grid[CH_MAXGRID];
    int n_grid, split_index, teeth_idx;
    ch_real length, pharynx_length, teeth_s, lip_s, velum_port_area;
    ch_v2 tip;
    ch_real contact_tip, contact_body;
    ch_v2 contact_tip_n, contact_body_n;

    /* the tubes' areas and minima (after the slew limit), their anatomical lengths */
    ch_real ph_areas[CH_MAXTUBE], or_areas[CH_MAXTUBE], ph_min[CH_MAXTUBE], or_min[CH_MAXTUBE];
    ch_real ph_len, or_len;
    ch_real base[4][CH_MAXTUBE];   /* the areas the control step started from (slew baseline) */
    int have_base;
    ch_real base_dt;

    /* the noise sources for the network (ch_noise_t: src fwd bwd gain lp_coef hp_coef) */
    ch_noise_t noise[4];
    int n_noise;
    /* each source's places along the tract, cm from the glottis: where its flow is read, where it goes in for a flow
     * forward and for a flow backward (-1: below the glottis, the trachea). For a tract model without CHIMERA's
     * branch numbers (the waveguide, ch_wave.c) */
    ch_real noise_s[4][3];

    /* drawing (HumanFrame's drawing parts, body cm), filled in by an apply only while `draw` is set (the screen's
     * views; the audio's anatomy runs leave it off): the tongue outline, the lips' outer edges, the jaw bone, the
     * teeth, the glottis, the tongue body's circle; the jaw's rotation is kept always */
    uint8_t draw;
    ch_v2 d_tongue[64], d_ulip_out[6], d_llip_out[6], d_jaw_bone[7], d_upper_teeth[5], d_lower_teeth[4];
    int n_tongue;
    ch_v2 d_glottis, d_tongue_c;
    ch_real d_tongue_r, jaw_phi;

    /* scratch */
    ch_line outer, inner, roof, outline;
    ch_v2 vel_oral[11], upper_lip_in[7], lower_lip_in[7], root[9], arc_pts[32], blade[7], under[6], back[48], tip_pts[16];
    ch_real cum[CH_MAXPTS], tmp[CH_MAXCROSS], cross_t[CH_MAXCROSS];
    uint8_t cross_exit[CH_MAXCROSS];
} ch_tract_t;

/* what a tract model needs of one apply, copied out (another core renders from it while the anatomy computes the
 * next): the two tubes' areas and minima, their lengths, the velum port, the noise sources and their places */
typedef struct {
    ch_real ph_areas[CH_MAXTUBE], or_areas[CH_MAXTUBE], ph_min[CH_MAXTUBE], or_min[CH_MAXTUBE];
    ch_real ph_len, or_len, velum_port_area;
    int n_ph, n_or, n_noise;
    ch_noise_t noise[4];
    ch_real noise_s[4][3];
} ch_shape_t;

/* HumanVocalTract::new + the airway facts (filled in by the caller first: fs, dx_stable, n_ph,
 * n_or, the tube and port indices, the gains, the slew limits, law[]) */
void ch_tract_init(ch_tract_t *t, ch_real scale, ch_real pharynx, ch_real tongue_radius, ch_real lip_width);
/* template point -> body cm (HumanVocalTract::p), and the same carried by the jaw as the last apply left it */
ch_v2 ch_tract_point(const ch_tract_t *t, ch_real x, ch_real y);
ch_v2 ch_tract_jaw_point(const ch_tract_t *t, ch_real x, ch_real y);
int ch_catmull_rom(const ch_v2 *pts, int n, int per, ch_v2 *out);   /* returns the count: (n - 1) * per + 1 */
/* HumanAirway::apply(a, dt): dt > 0 slew-limits against the last areas */
void ch_tract_apply(ch_tract_t *t, const ch_real a[CH_NARTIC], ch_real dt);
/* HumanAirway::reapply(a): again within the same control step (after a contact correction moved an articulator),
 * slew-limited against the areas the step started from */
void ch_tract_reapply(ch_tract_t *t, const ch_real a[CH_NARTIC]);
/* the network targets of the last apply: areas, minima and lengths of the pharynx and oral nodes,
 * the velum port, the noise sources (into the arrays a ch_block_t points at) */
void ch_tract_targets(const ch_tract_t *t, ch_real *area_target, ch_real *amin_target, ch_real *dx_target,
                      ch_real *port_area_target);

/* the last apply's shape, for ch_wave_block */
void ch_tract_shape(const ch_tract_t *t, ch_shape_t *s);

#endif
