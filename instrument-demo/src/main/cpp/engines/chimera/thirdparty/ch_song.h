/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * CHIMERA's Sing mode in C: its tunes (src/song/tunes.ts, two of them its demo songs, crates/chimera/src/phonetics/
 * songs.rs), anyone's words fitted to a tune (src/song/fit.ts: the lyrics' lines onto the tune's phrases, each line
 * onto its phrase's notes without changing its length in beats) and a song planned (sing.rs plan_song: each phrase
 * one utterance with its own breath, the vowels on the beats). The FM-1 plans a phrase at a time, just before it is
 * due (a whole song is more gestures than its mailbox carries); each phrase is planned as plan_song plans it. */
#ifndef CH_SONG_H
#define CH_SONG_H
#include "ch_speech.h"

typedef struct {
    uint8_t midi;                /* (the tunes' notes are whole MIDI notes) */
    uint8_t half_beats;          /* its length in half beats (the tunes' shortest note is half a beat) */
} ch_tnote_t;

typedef struct {
    const char *name;
    ch_real bpm;
    int8_t oct_male, oct_female, oct_child;   /* the tune's octave for each body (its notes are the female's) */
    uint8_t n_phrases;
    const uint8_t *phrase_len;                /* notes a phrase */
    const ch_tnote_t *notes;                  /* all the phrases' notes, one after another */
} ch_tune_t;
#define CH_NTUNES 6
extern const ch_tune_t CH_TUNES[CH_NTUNES];

/* a note of the fitted song: its pitch, its length, the lyric syllable it sings (held: the vowel of the note before
 * carried on, a melisma), and whether it ends its phrase */
typedef struct {
    uint8_t midi;
    ch_real beats;
    int16_t syl;
    uint8_t held, phrase_end;
} ch_sung_t;
#define CH_SONG_MAX 256

/* fitSong: the syllables (ch_lyrics) on tune `t`, `shift` semitones from its pitch; returns the notes */
int ch_song_fit(const ch_tune_t *t, const ch_syl_t *syls, int n_syls, int shift, ch_sung_t *out, int cap);
/* each note's beat (s from the song's start, at `bpm`) into times[n + 1] (times[n]: the song's end) */
void ch_song_times(const ch_sung_t *notes, int n, ch_real bpm, ch_real *times);
/* plan_song for the phrase starting at note i: its gestures into `out`, its times on the plan's clock from `t0` (the
 * song's first beat). Returns the next phrase's first note (n: the song is planned) */
int ch_song_plan_phrase(const ch_sung_t *notes, int n, int i, const ch_syl_t *syls, const ch_real *times, ch_real t0,
                        ch_real vibrato, ch_plan_t *out);

#endif
