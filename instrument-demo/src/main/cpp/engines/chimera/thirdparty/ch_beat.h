/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * CHIMERA's beatboxer in C: its ten sounds, each a short gestural score on the human vocal tract made with the
 * airstream mechanisms beatboxers use (crates/chimera/src/phonetics/beatbox.rs: pulmonic snares and hats, an ejective
 * kick and rimshot, velaric clicks, an ingressive snare, a hummed bass), its preset beats, and its step sequencer
 * (control/beat_player.rs), run on the voice's clock so the hits are sample-accurate. One throat makes one sound at a
 * time: the beats are monophonic, as a real beatboxer's. No allocation: it runs in the audio interrupt. */
#ifndef CH_BEAT_H
#define CH_BEAT_H
#include "ch_control.h"
#include "ch_speech.h"

enum { CH_BEAT_KICK, CH_BEAT_HAT, CH_BEAT_OPEN_HAT, CH_BEAT_SNARE, CH_BEAT_RIMSHOT, CH_BEAT_INWARD, CH_BEAT_CLICK,
       CH_BEAT_LIP_POP, CH_BEAT_CRASH, CH_BEAT_HUM, CH_BEAT_N };
enum { CH_ROLE_KICK, CH_ROLE_SNARE, CH_ROLE_HAT, CH_ROLE_PERC, CH_ROLE_BASS };
enum { CH_AIR_PULMONIC, CH_AIR_EJECTIVE, CH_AIR_CLICK, CH_AIR_INGRESSIVE, CH_AIR_VOICED };

typedef struct {
    const char *id;              /* the pattern symbol (Standard Beatbox Notation where it has one) */
    const char *name;
    uint8_t role, air;
    ch_real latency;             /* the planned hit to the acoustic onset (s): scheduled this much earlier */
} ch_beat_info_t;
extern const ch_beat_info_t CH_BEAT_SOUNDS[CH_BEAT_N];
/* each sound's breath on the FM-1, a factor (lab-tuned; ch_beat.c) */
extern ch_real ch_beat_breath[CH_BEAT_N];

#define CH_BEAT_MAX_G 16         /* the most gestures a sound builds */
#define CH_BEAT_NOTE 40          /* the hum's note when a step has none (MIDI E2) */
#define CH_BEAT_ACCENT ((ch_real)1.3)  /* an accented step's loudness */

/* a sound's gestures, its acoustic hit at `hit` (s, on the plan's clock), appended to `o`: f0 the hum's pitch (Hz),
 * len how long a sustained sound may last (s), accent its loudness (0.5..1.5) */
void ch_beat_build(int id, ch_real f0, ch_real len, ch_real accent, ch_real hit, ch_plan_t *o);
/* the set-up time a sound needs before its hit (s): a pad sounds this long after its press */
ch_real ch_beat_lead_in(int id);
/* a beatboxer's ready posture (beat_ready): jaw nearly closed, lips just apart, the tongue relaxed; NaN: a key it
 * leaves alone */
void ch_beat_ready(ch_real out[CH_NARTIC]);

/* a beat's step: a sound (-1 a rest), its note (0: CH_BEAT_NOTE), accented or not */
typedef struct {
    int8_t id;
    uint8_t note, accent;
} ch_beat_step_t;

#define CH_BEAT_MAX_STEPS 64
/* a beat written as text (parse_beat): sound symbols separated by spaces or "|" (bar lines); "." or "-" a rest; a hum
 * may carry a MIDI note ("m:43"); a trailing ">" accents. Returns the step count */
int ch_beat_parse(const char *text, ch_beat_step_t *out, int cap);

typedef struct {
    const char *name;
    ch_real bpm, swing;
    const char *text;
} ch_beat_preset_t;
#define CH_BEAT_NPRESETS 7
extern const ch_beat_preset_t CH_BEAT_PRESETS[CH_BEAT_NPRESETS];

/* the step sequencer (BeatPlayer) */
#define CH_BEAT_MARKS 32
typedef struct {
    ch_beat_step_t steps[CH_BEAT_MAX_STEPS];
    int n;
    ch_real bpm, swing;          /* swing 0..0.6: the off-beat sixteenths late by swing x half a step */
    uint8_t playing;
    ch_real anchor_time;
    long anchor_step, next;
    long mark_n[CH_BEAT_MARKS];  /* recently scheduled steps and their hits (-1: empty) */
    ch_real mark_t[CH_BEAT_MARKS];
    int mark_pos;
} ch_beat_player_t;

#define CH_BEAT_HORIZON ((ch_real)0.2) /* a step is scheduled this long before its hit: every sound's set-up time */
void ch_beat_player_init(ch_beat_player_t *p);
void ch_beat_set_steps(ch_beat_player_t *p, const ch_beat_step_t *steps, int n);
void ch_beat_set_tempo(ch_beat_player_t *p, ch_real bpm, ch_real swing);   /* no jump: the next step keeps its time */
void ch_beat_start(ch_beat_player_t *p, ch_real clock);
void ch_beat_stop(ch_beat_player_t *p);
ch_real ch_beat_step_dur(const ch_beat_player_t *p);
/* the step under the playhead at `clock` (-1: none) */
int ch_beat_step_at(const ch_beat_player_t *p, ch_real clock);
/* every step whose hit falls within the horizon of `clock`: its gestures into the score (times on the voice's clock) */
void ch_beat_tick(ch_beat_player_t *p, ch_real clock, ch_score_t *score);

#endif
