/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * CHIMERA's Bestiary on the FM-1 (0.7.0: the seal). A creature voice as CHIMERA's (crates/chimera/src/creatures/
 * voice.rs, types.rs): its playable parameters, each smoothed toward its target (log ones geometrically); a gate with
 * the creature's attack and release; its calls, keyframed automation of the gate and the parameters played at a
 * pitch. The creature's own control turns the values into the FM-1 waveguide's inputs: the tract's shape (its tubes),
 * the glottis's parameters and the lungs' pressure (ch_seal.c). No allocation: it runs in the audio interrupt. */
#ifndef CH_CREATURE_H
#define CH_CREATURE_H
#include "ch_tract.h"

#define CH_CP_MAX 10                 /* parameters a creature has (the songbird 9) */
#define CH_CK_GATE (-1)              /* a call key's target: the gate (else a parameter's index) */
enum { CH_CURVE_LIN, CH_CURVE_EXP, CH_CURVE_STEP };

typedef struct {
    const char *name;                /* (the FM-1's label, upper case) */
    ch_real min, max, def, smooth;   /* smooth: the time constant (s) */
    uint8_t log, pitch;              /* log: glides geometrically; pitch: follows the keys and the calls' pitch */
    const char *unit;
} ch_cparam_t;

typedef struct {                     /* a call's keyframe: at t (s), these targets, reached by this curve */
    ch_real t;
    uint8_t curve, n;
    int8_t key[6];
    ch_real v[6];
} ch_ckey_t;

typedef struct {
    const char *name, *hint;
    ch_real pitch_ref;               /* the pitch it was written at (Hz; 0: it does not follow the keys) */
    uint8_t n;
    const ch_ckey_t *k;
} ch_ccall_t;

typedef struct {
    const char *name;
    uint8_t np, ncalls;
    const ch_cparam_t *p;
    const ch_ccall_t *calls;
    ch_real attack, release;         /* the gate's times (s) */
} ch_cdef_t;

typedef struct {
    const ch_cdef_t *def;
    ch_real performer[CH_CP_MAX], target[CH_CP_MAX], value[CH_CP_MAX];
    ch_real gate, gate_target, call_gate, clock;
    int call;                        /* the call playing, -1 none */
    ch_real call_t0, call_pitch, call_amp;
} ch_cvoice_t;

void ch_cvoice_init(ch_cvoice_t *v, const ch_cdef_t *def);
void ch_cvoice_set(ch_cvoice_t *v, int i, ch_real x);          /* a parameter, as the performer sets it */
void ch_cvoice_gate(ch_cvoice_t *v, ch_real g);
/* a call at a pitch (Hz; 0: its own), loudness amp; ends any call playing */
void ch_cvoice_call(ch_cvoice_t *v, int call, ch_real pitch, ch_real amp);
void ch_cvoice_stop_call(ch_cvoice_t *v);
/* one control step of dt (s): the call, the values' glides, the gate */
void ch_cvoice_step(ch_cvoice_t *v, ch_real dt);
/* a key's pitch for a pitch parameter (creatureKeyHz): the octave that centres the range on middle C, folded in */
ch_real ch_ckey_hz(const ch_cparam_t *p, ch_real midi);

/* ---------------------------------------------------------------------- the seal --- */
/* The Weddell seal (creatures/seal.rs): a laryngeal source with an extreme pitch range, a pulse oscillator on the lungs'
 * pressure, a short tract (pharynx 11 cm, mouth 12 cm, the last fifth its opening). On the FM-1's waveguide, without
 * CHIMERA's trachea and its nose (shut: the seal never opens it) */
enum { CH_SEAL_PITCH, CH_SEAL_PULSE_RATE, CH_SEAL_PULSE, CH_SEAL_DUTY, CH_SEAL_BRIGHT, CH_SEAL_ROUGH, CH_SEAL_PRESSURE,
       CH_SEAL_MOUTH, CH_SEAL_NP };
extern const ch_cdef_t CH_SEAL;
#define CH_SEAL_GAIN ((ch_real)6.4e-8)      /* its output gain (CHIMERA's) */
#define CH_SEAL_LEN ((ch_real)23.0)        /* its tract (cm) */

typedef struct {
    ch_real pulse_phase;
} ch_seal_t;
void ch_seal_init(ch_seal_t *s);
/* the seal's control step: from the voice's values and gate, the glottis's parameters, the lungs' pressure and the
 * tract's shape */
void ch_seal_control(ch_seal_t *s, const ch_cvoice_t *v, ch_real dt, ch_real fs, ch_glottis_params_t *g, ch_real *p_lung,
                     ch_shape_t *shape);

/* ------------------------------------------------------------------ the songbird --- */
/* The songbird (creatures/songbird.rs): two labial pairs in the syrinx (sources/syrinx.rs: the Gardner-Mindlin normal
 * form, its pitch exact through the limit cycle's tabulated period), one per bronchus, each with its own pitch; both
 * bronchi into the trachea, the oropharyngeal-oesophageal cavity (a Helmholtz resonator whose volume can track the
 * note), the beak (its gape sets the radiation). On a light waveguide at 2x (ch_creature.c) */
enum { CH_BIRD_PITCH, CH_BIRD_INTERVAL, CH_BIRD_VOICES, CH_BIRD_DRIVE, CH_BIRD_TENSION, CH_BIRD_PRESSURE, CH_BIRD_PURITY,
       CH_BIRD_OEC, CH_BIRD_GAPE, CH_BIRD_NP };
extern const ch_cdef_t CH_BIRD;
#define CH_BIRD_GAIN ((ch_real)4.8e-7)     /* its output gain (CHIMERA's) */

typedef struct {                           /* SyrinxLabia, played by phase (ch_syrinx_waves.h) */
    ch_real phase, drive, tension, f0, gate, gate_sm, fs, opening;
} ch_syrinx_t;
void ch_syrinx_init(ch_syrinx_t *s, ch_real fs, ch_real seed);
ch_real ch_syrinx_next(ch_syrinx_t *s);    /* one sample: the valve's opening 0..1 */

#define CH_BIRD_NT 9                       /* the trachea: 3.6 cm, a section a sample at 88.2 kHz (0.40 cm) */
#define CH_BIRD_NK 3                       /* the beak: 1.3 cm */
#define CH_BIRD_TAPS 49                    /* CHIMERA's decimator, 2 x 24 + 1 */
typedef struct {
    ch_syrinx_t l, r;
    ch_real fs, ps, gain;                  /* 88.2 kHz; the air sac's pressure; the level (lab) */
    ch_real uv[2], mv[2], lv[2];           /* the valves: their flows, momenta, inertances */
    ch_real tr[CH_BIRD_NT], tl[CH_BIRD_NT], fade_t, fade_nt;   /* the trachea's delay lines, its loss a section */
    int ti;
    ch_real mn, pc, oec_c, oec_a;          /* the neck's momentum, the cavity's pressure, compliance, area */
    ch_real ka[CH_BIRD_NK], kk[CH_BIRD_NK], kr[CH_BIRD_NK], kl[CH_BIRD_NK], fade_k;   /* the beak */
    ch_real rb0, rb1, ra1, r_in, r_out, rad_prev;   /* its radiation */
    ch_real h[CH_BIRD_TAPS], buf[2 * CH_BIRD_TAPS];  /* the decimator */
    int pos;
    ch_real dc_x, dc_y, dc_coef;
    /* once a control step: the neck's inertance, its b and losses; the cavity's three coefficients; the radiated
     * flow's factor; the valves' viscous constant */
    ch_real neck_l, neck_bq, neck_kf, neck_kb, cav_in, cav_beak, cav_den, rad_k, visc_v;
} ch_bird_t;
/* the songbird at rest, its geometry at the voice's values */
void ch_bird_init(ch_bird_t *b, const ch_cvoice_t *v);
/* a control step: the air sac's pressure, the labia's pitches, drive, tension and gates, the cavity's and the beak's
 * geometry */
void ch_bird_control(ch_bird_t *b, const ch_cvoice_t *v, int samples);
ch_real ch_bird_sample(ch_bird_t *b);      /* one output sample at 44.1 kHz (two of its airway's) */

#endif
