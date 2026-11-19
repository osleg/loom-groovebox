/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * The FM-1's tract: a Kelly-Lochbaum waveguide driven by CHIMERA's anatomy (ch_tract.c), its glottis and its noise
 * sources, in place of CHIMERA's FDTD airway (ch_voice.c, ch_fast.c), which is about twice what the FM-1 can run
 * (docs/full-physics-on-fm1.md).
 *
 *   tract    pressure waves, one section a sample (c/fs: 0.79 cm at 44.1 kHz), the section count fixed per body;
 *            each section's area from the anatomy's pharynx and oral tubes (as CHIMERA resamples them, slew limits
 *            included) spread over the tract's current length
 *   nose     a second waveguide from the velopharyngeal junction, joined by a three-port junction weighted by the
 *            velum port's area (closed: no coupling)
 *   glottis  CHIMERA's parametric glottis gives the area; the flow through it solves CHIMERA's glottal port (inertance,
 *            slit-viscous resistance, entrance and exit losses) against the lungs' pressure and the pressure just
 *            above the glottis, so the source still feels the tract: a closure chokes the flow
 *   noise    CHIMERA's turbulence sources (aspiration, the constriction's jet, the teeth), at the same places, with
 *            the same Reynolds-number law and filters, injected as series pressure sources at the junctions
 *   output   the radiated flow's time derivative at the lips and the nostrils, then CHIMERA's DC blocker and limiter
 *
 * Lumped against the FDTD: the yielding walls (one wall for the whole tract) and the moving-wall volume sources (one
 * source, at the glottis's end). Lost: the tract's length changing the section count (the length is spread over a fixed count), the sinus and the
 * piriform fossae. */
#ifndef CH_WAVE_H
#define CH_WAVE_H
#include "ch_voice.h"
#include "ch_tract.h"

#define CH_WMAX 40      /* tract sections (the adult male: ~22) */
#define CH_WJET_AREA 1.0f   /* cm²: a junction narrower than this gets the jet's loss */
#define CH_WNMAX 24     /* nose sections */

/* a tract section: its waves in two buffers (the sample reads one and writes the other: ch_wave_t.p), its area and
 * the reflection of the junction at its left end, both ramped over a control step. One struct a section: on the FM-1
 * a field of a separate array cost an address computation of its own (docs/full-physics-on-fm1.md) */
typedef struct {
    ch_real R[2], L[2];         /* the right-going wave at the section's right end, the left-going at its left end */
    ch_real k, dk;              /* the junction at its left end (to section i - 1) */
    ch_real area, darea;
} ch_wsec_t;

typedef struct {
    ch_real R[2], L[2], k, area;
} ch_wnsec_t;

/* a sealed pocket (ch_wave_t.pock) */
typedef struct {
    int16_t c0, c1, a, b;               /* its anatomy cells (pharynx then mouth, one index), its tract sections */
    uint8_t glottis;                    /* bounded by the glottis (sealed) rather than a closure on its left */
    ch_real p, dp;                      /* its pressure (dyn/cm²) and its ramp a sample */
} ch_wpock_t;

typedef struct {
    ch_real fs, rho_c, closed_area;
    int n, nn, jv;                          /* tract and nose sections; the nose joins at junction jv */
    ch_real dx;                             /* a section's length, c / fs */
    uint8_t var_len;                        /* 1: the section count follows the tract's length each control step */
    uint8_t len_changed;                    /* the count changed at this step: its first sample skips the output's jump */
    int ctrl_every;
    /* the tract and the nose; p: the wave buffer this sample reads */
    ch_wsec_t sec[CH_WMAX];
    ch_wnsec_t nsec[CH_WNMAX];
    int p;
    ch_real fade, fade_n;
    /* the junction where the nose joins: its three weights 2A/sum(A), ramped */
    ch_real w1, w2, w3, dw1, dw2, dw3;
    ch_real nk1_d;                          /* the velum port's junction (nsec[1].k): its ramp, as the tract's */
    ch_real r_lip, r_nose, r_glottis;      /* (r_lip, r_nose: the radiation load's reflection when rad_load is 0) */
    /* the radiation load at the lips [0] and the nostrils [1]: CHIMERA's (Flanagan's) parallel R-L against the end
     * section's characteristic impedance, as a reflection filter refl = (b0 + b1 z^-1) / (1 + a1 z^-1) (bilinear),
     * its coefficients from the end's area each control step; its last input and output */
    uint8_t rad_load;
    ch_real rb0[2], rb1[2], ra1[2], r_in[2], r_out[2];
    ch_real noise_gain;                    /* on CHIMERA's noise sources (calibrated against its renders) */
    /* the jets: at the junctions narrower than CH_WJET_AREA a series resistance, CHIMERA's kinetic (Borda-Carnot)
     * loss times the junction's flow a sample before: the flow out of a constriction (a release, a fricative) is
     * limited as in CHIMERA. Per jet: its junction, the loss coefficients each way, the flow */
    uint8_t n_jet;
    int16_t jet_m[8];
    ch_real jet_kf[8], jet_kb[8], jet_u[8];
    ch_real jet_dkf[8], jet_dkb[8];         /* their ramps over the control step, from the areas then to the target's */
    /* the yielding walls, lumped: CHIMERA's soft-tissue mass, resistance and stiffness per cm² over the tract's wall
     * area, moved by the pressure above the glottis; their motion draws air (the voice bar through a closure) and a
     * quarter of it radiates (CHIMERA's wall_radiation) */
    /* the sections closed during this control step (shut now or by its end): a sealed cell holds no air, so its waves
     * are emptied every sample (CHIMERA: a sealed cell has no pressure of its own). Without it the waves trapped in a
     * shut section doubled at each reflection and came out as a burst at the release */
    uint8_t n_shut;
    int16_t shut[CH_WMAX];
    uint8_t walls;
    ch_real wall_s, wall_v, wall_y, wall_u;
    ch_real wall_rad;                       /* the share of the walls' motion that radiates (CHIMERA's wall_radiation);
                                             * a fifth of it while the glottis is sealed (an ejective's compressed throat
                                             * thumped ~12 dB louder than CHIMERA's before the hit; no voice bar then) */
    /* the moving walls' volume source (CHIMERA's vol_src), lumped: the volume the tract behind its first closure (the
     * anatomy's cells) loses per second as the tongue, jaw and lips move, pushed in at the glottis's end. Behind a
     * closure it is what builds the pressure that stops the voicing in a stop (without it a /g/ stayed voiced ~80 ms,
     * CHIMERA's devoices in ~15) */
    uint8_t vol_src;                        /* 0 off, 1 behind a closure, 2 behind a closure or a constriction under vol_narrow */
    ch_real vol_narrow, vol_smooth;
    /* a section's area: its narrowest anatomy cell's (1), its middle's (0), its middle's or a shorter cell's centred in
     * it (2, from 0.8.0: with 1 a constriction spread over every section it touched, a /s/ 1.1 cm long grew to 2.4 cm
     * and filled the space behind the teeth, so its hiss rang at 4-5 kHz where CHIMERA's rings at 2-3) */
    uint8_t narrowest;
    ch_real vol_u, vol_goal;
    ch_real vcell[2 * CH_MAXTUBE];          /* each anatomy cell's volume at the last shape (pharynx, then mouth) */
    uint8_t vol_ok;
    int vol_cells;
    /* sealed pockets (0.7.0): air shut in at both ends (between two closures, or between a sealed glottis and a
     * closure, the nose not open to it) keeps the pressure its volume gives it (CHIMERA's continuity: dp = -dV over
     * the air's compliance V / rho c^2 and its yielding walls'): the hollowed pocket of a click, the compressed throat
     * of an ejective. The waveguide's losses would bleed that pressure away in ~5 ms, so it is held on the pocket's
     * sections each sample until a closure opens and the waves carry it out. Per pocket: its anatomy cells, its
     * sections, its pressure ramped over the step */
    uint8_t n_pock;
    ch_wpock_t pock[2];
    ch_real pock_k, pock_wall, pock_max, pock_max_oral;   /* the air's bulk modulus (rho c^2); the walls' compliance
                                             * a cm² of wall; the most a pocket holds (dyn/cm²): one the glottis seals, one
                                             * in the mouth */
    uint8_t glot_sealed;                    /* this step's glottis is sealed (its closure >= 0.5) */
    /* a pocket's release snaps: a closure opening beside a sealed pocket glides to its opening in one control step,
     * not over the anatomy's (every 4th step on the FM-1: a click's tip opening across two of them bled its pocket out
     * through a slit, 15 dB quieter, CHIMERA's anatomy runs every step); its glide stops at the next step */
    uint8_t n_fast;
    int16_t fast[4];
    /* the glottis: CHIMERA's area model and its port's constants (HumanAirway's glottis_port) */
    ch_glottis_t glottis;
    ch_real port_len, port_slit, port_kin, port_kex;
    ch_real u_g, l_prev, mom, p_supra, a_g;
    ch_real p_lung, p_lung_now, p_lung_step;
    /* the noise sources: CHIMERA's (ch_noise_t), and the junctions they read and go in at */
    ch_noise_t noise[4];
    int16_t n_src[4], n_fwd[4], n_bwd[4];
    uint8_t n_noise;
    uint32_t rng;
    /* the output: CHIMERA's gain, DC blocker and limiter */
    ch_real rad_prev, output_gain, dc_x, dc_y, dc_coef, lim_env, lim_release;
    /* a treble shelf on the output (0.8.0, off unless set): x + shelf_g (x - lowpass(x)), the lowpass at shelf_c.
     * Over 3 kHz the waveguide is 3-9 dB brighter than CHIMERA's airway (its sentences' spectra, all bodies) */
    ch_real shelf_g, shelf_c, shelf_lp;
    int32_t lim_hold, lim_hold_n;
    uint32_t resets;                        /* times the state stopped being a number and was cleared (should stay 0) */
} ch_wave_t;

/* a body's waveguide: section counts from its neutral tract length (cm), the nose's from its length */
void ch_wave_init(ch_wave_t *w, ch_real fs, int ctrl_every, ch_real body_scale, ch_real tract_len);
/* a control step: the glottis's parameters and the lungs' pressure, and when the anatomy has a new shape
 * (ch_tract_shape of an apply) that shape, glided to over `steps` control steps (the anatomy runs every `steps`
 * steps); with t NULL the tract keeps gliding to the last one */
void ch_wave_block(ch_wave_t *w, const ch_shape_t *t, int steps, const ch_glottis_params_t *g, ch_real p_lung);
void ch_wave_render(ch_wave_t *w, float *out, uint32_t n);

#endif
