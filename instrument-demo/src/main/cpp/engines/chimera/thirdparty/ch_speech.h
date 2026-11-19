/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * CHIMERA's English front end in C: text to phonemes (crates/chimera/src/phonetics/english.rs: a pronouncing
 * dictionary when there is one, the letter-to-sound rules otherwise, numbers spelled out, American flaps and dark
 * /l/, phrases and their intonation), the Klatt durations (durations.rs), the gestural planner (planner.rs:
 * plan_utterance, plan_phrases, oral_gestures), English syllables and lyrics (syllables.rs) and live singing
 * (sing.rs: plan_note_on, plan_note_off). Out come gestures for the control (ch_control.c's score).
 *
 * English only, the adult male's calibrated tables (ch_phon_data.h, generated from CHIMERA's): the planner's paths
 * for marks no English word carries (aspiration or VOT given by a front end, nasalized, palatalized and breathy
 * segments) are left out. Fixed-size buffers, no allocation: it runs in the FM-1's main loop. */
#ifndef CH_SPEECH_H
#define CH_SPEECH_H
#include "ch_control.h"

#define CH_IPA 8                 /* an IPA symbol, UTF-8, with its terminator ("ɔɪ": 5 bytes) */
#define CH_SEG_MAX 192           /* segments in a text (64 characters: ~60) */
#define CH_PHRASE_MAX 16
#define CH_SYL_MAX 64            /* syllables in a lyric */
#define CH_ONSET_MAX 4
#define CH_CODA_MAX 5
#define CH_SYL_TEXT 12

typedef struct {
    char ipa[CH_IPA];
    ch_real dur;                 /* NaN: the planner's */
    int8_t stress;               /* -1: none; 0 unstressed, 1 primary, 2 secondary */
    uint8_t word_end;
    ch_real f0;                  /* 0: none (a sung pitch, Hz) */
} ch_seg_t;

enum { CH_INTON_STATEMENT, CH_INTON_QUESTION, CH_INTON_FLAT };

typedef struct {
    int first, n;                /* its segments in the text's array */
    uint8_t intonation;
    ch_real pause;               /* after it (s) */
} ch_phrase_t;

typedef struct {
    ch_real t0;                  /* the plan's start, on the score's clock (s) */
    ch_real rate;                /* speaking rate (1) */
    ch_real f0;                  /* the baseline pitch (Hz, 110) */
    ch_real accent;              /* stressed syllables' pitch excursion (0.14) */
    uint8_t intonation, open_end, sung;
} ch_plan_opts_t;

typedef struct {
    ch_gesture_t *g;             /* where the gestures go, and how many fit */
    int cap, n;
    uint32_t dropped;            /* gestures that did not fit */
    ch_real end;                 /* the plan's end (s) */
    ch_real last_t0, last_t1;    /* the last segment's span (a held note's vowel) */
    int exhale;                  /* the end of the breath group's abduction (CHIMERA's EXHALE_LABEL), -1: none */
} ch_plan_t;

/* A pronunciation for a word the dictionary has, as CMUdict's ARPAbet ("HH AH0 L OW1"); NULL: none (the letter-to-
 * sound rules read it). The built-in table (ch_dict_data.h) is asked when this returns NULL. */
typedef const char *(*ch_lookup_fn)(const char *word, void *user);

/* text -> phrases of segments (EnglishG2P::to_phrases); returns the phrase count */
int ch_text_to_phrases(const char *text, ch_lookup_fn lookup, void *user, ch_seg_t *segs, int seg_cap, int *n_segs,
                       ch_phrase_t *ph, int ph_cap);
/* one word -> segments with stress (EnglishG2P::word); `reduce`: weak forms of function words. Returns the count */
int ch_word(const char *word, int reduce, ch_lookup_fn lookup, void *user, ch_seg_t *out, int cap);
int ch_letter_to_sound(const char *word, ch_seg_t *out, int cap);
/* 1: the FM-1's letter-to-sound additions before CHIMERA's rules (no dictionary on the device); 0: CHIMERA's alone */
extern int ch_lts_device;
/* the body whose calibrated phonemes and vowels the front end, the planner and the vowel pad use: 0 the adult male
 * (the start), 1 the adult female, 2 the child (CHIMERA's BodyMix presets) */
void ch_speech_body(int body);
/* the built-in pronunciation list's words (ch_dict_data.h, ~480 common English words), by index 0..count-1 */
int ch_dict_count(void);
const char *ch_dict_word(int i);
int ch_arpabet(const char *phones, ch_seg_t *out, int cap);

/* the beatbox's building blocks (ch_beat.c): a consonant's oral gestures from a to b at `speed`, with `n` targets
 * (keys, vals) over its own, into the plan (returns where they start); a vowel's articulation (NULL: none). Both in
 * the body's tables (ch_speech_body) */
int ch_oral(const char *ipa, ch_real a, ch_real b, ch_real speed, const int *keys, const ch_real *vals, int n, ch_plan_t *o);
const ch_real *ch_vowel_artic(const char *ipa);

void ch_plan_defaults(ch_plan_opts_t *o);
void ch_plan_utterance(const ch_seg_t *segs, int n, const ch_plan_opts_t *o, ch_plan_t *out);
/* the phrases one after another from o->t0, each its own breath group and intonation */
void ch_plan_phrases(const ch_seg_t *segs, const ch_phrase_t *ph, int n_ph, const ch_plan_opts_t *o, ch_plan_t *out);

/* singing */
typedef struct {
    char text[CH_SYL_TEXT];      /* its part of the word's spelling ("twin", "kle") */
    ch_seg_t onset[CH_ONSET_MAX], nucleus, coda[CH_CODA_MAX];
    uint8_t n_onset, n_coda, melisma, word_end, phrase_end;
} ch_syl_t;

/* lyrics -> sung syllables (lyrics_to_syllables, English): returns the count */
int ch_lyrics(const char *text, ch_lookup_fn lookup, void *user, ch_syl_t *out, int cap);
ch_real ch_midi_hz(ch_real midi);
/* the English vowel nearest an articulation (the tongue body's place, the lips' rounding), as an ASCII name (ARPAbet's:
 * "AA", "IY", ...): the flat view's TONGUE readout (CHIMERA's shows IPA, which the FM-1's fonts lack) */
const char *ch_vowel_name(const ch_real *x);
/* CHIMERA's vowel pad: the articulation for formants F1, F2 (Hz) into out (the tongue body, the jaw, the lips, the root,
 * the larynx, the tip's curl; the other keys untouched); a calibrated vowel's formants (0: no such vowel) */
void ch_vowel_pad(ch_real f1, ch_real f2, ch_real *out);
int ch_vowel_formants(const char *ipa, ch_real *f1, ch_real *f2);
/* a key pressed: (the previous syllable's glide and coda when `legato_from` still sounds), the onset, the vowel held
 * open-ended; times from now (add the score's clock). Its gestures are tagged `tag`, the pitch and vibrato tag + 1 */
void ch_plan_note_on(const ch_syl_t *syl, ch_real midi, ch_real vibrato, ch_real tag, const ch_syl_t *legato_from,
                     ch_real legato_midi, ch_plan_t *out);
/* the key released (release the note's tags first): the diphthong's glide and the coda */
void ch_plan_note_off(const ch_syl_t *syl, ch_real midi, ch_plan_t *out);
/* melisma: the held vowel moves to a new pitch (release tag + 1 first) */
void ch_plan_melisma(ch_real midi, ch_real vibrato, ch_real tag, ch_plan_t *out);

#endif
