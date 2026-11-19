/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * CHIMERA's control rate in C: the articulators' dynamics (crates/chimera/src/control/articulators.rs), the gestural
 * score (control/score.rs), the voice source's settings (voice/human_voice.rs VoiceSource, apply_source) and the
 * control step (HumanVoice::control and enforce_contacts): gestures in, each control step's articulation (through
 * the anatomy, ch_tract.c), the glottis's parameters and the lungs' pressure out. Literal, like ch_voice.c: as
 * `double` it is checked against CHIMERA's recording (lab/port/replay --control). */
#ifndef CH_CONTROL_H
#define CH_CONTROL_H
#include "ch_tract.h"

#define CH_NSOURCE 5          /* abduction, closure, breath, f0, vibrato */
#define CH_NCONTROL (CH_NARTIC + CH_NSOURCE)
enum { CH_ABDUCTION = CH_NARTIC, CH_CLOSURE, CH_BREATH, CH_F0, CH_VIBRATO };
#ifndef CH_SCORE_CAP
#define CH_SCORE_CAP 256      /* gestures the score holds (CHIMERA: 16384; a spoken sentence ~100); past it one is dropped, counted */
#endif

typedef struct {
    ch_real t0, t1, ramp_in, ramp_out, strength;
    ch_real speed, release_hold, tag;       /* NaN: none */
    ch_real targets[CH_NCONTROL];           /* NaN: the key is not this gesture's */
} ch_gesture_t;

typedef struct {
    ch_real value, weight, speed;
} ch_blended_t;

typedef struct {
    ch_gesture_t g[CH_SCORE_CAP];
    int n;
    ch_real end;
    uint32_t dropped;
} ch_score_t;

typedef struct {                            /* VoiceSource */
    ch_real f0, glide, pressure, breathiness, tension, fry, roughness, vibrato_depth, vibrato_rate, drift;
    uint8_t voiced;
    ch_real brightness;
} ch_source_t;

typedef struct {
    /* the articulators (ArticulatorBank): position, velocity, the performer's target, the last effective target */
    ch_real x[CH_NARTIC], v[CH_NARTIC], target[CH_NARTIC], speed[CH_NARTIC], effective[CH_NARTIC];
    uint8_t tip_free;
    ch_score_t score;
    ch_blended_t blend[CH_NCONTROL];
    ch_source_t source;
    ch_glottis_params_t glottis;            /* what the glottis is told, each control step */
    ch_real k;                              /* the body's scale */
    ch_real fs, dt, clock, max_penetration;
    ch_real gate, gate_target, score_breath_hold, p_lung, f0, score_f0;
    uint8_t has_score_f0;
    ch_real base_abduction, base_closure;
    int anat_every, anat_count;
    uint32_t reapplies;                     /* anatomy runs repeated after a contact correction (a cost) */
    uint8_t defer_contacts;                 /* 1: a contact correction moves the articulators but the anatomy sees it
                                             * at its next run, not again now (the FM-1: half the worst step's cost) */             /* the anatomy (and the contacts) every anat_every steps (default 1) */
} ch_control_t;

/* CHIMERA's neutral (schwa-like) posture, template coordinates */
extern const ch_real CH_NEUTRAL[CH_NARTIC];

/* a voice's control at rest: the neutral articulation, the default source (HumanVoice::new); then set_source */
void ch_control_init(ch_control_t *c, ch_real fs, int ctrl_every, ch_real body_scale);
void ch_control_set_source(ch_control_t *c, const ch_source_t *s);      /* set_source (the whole source) */
void ch_control_set_gate(ch_control_t *c, ch_real level);                 /* set_gate: breath on (1) / off (0) */
void ch_score_add(ch_score_t *s, const ch_gesture_t *g);
void ch_score_release(ch_score_t *s, ch_real tag, ch_real t);
void ch_score_clear(ch_score_t *s);
/* one control step (HumanVoice::control): the articulators, the anatomy (apply, and reapply after a contact
 * correction), the breath and the pitch. After it: c->x the articulation, c->glottis, c->p_lung. 1 when the anatomy
 * ran this step (anat_every): its shape is new, to be glided to over anat_every steps */
int ch_control_step(ch_control_t *c, ch_tract_t *t);

#endif
