/* SPDX-License-Identifier: GPL-3.0-only */
/* Integer-only formant singing voice (no float/libm/heap/64-bit math; 44.1 kHz). */
#ifndef FVOICE_H
#define FVOICE_H
#include <stdint.h>
#define FV_NR 6                 /* resonators: F1 nasal F2 F3 singer air */
#define FV_BLK 16               /* frames per control block */
enum { FV_A, FV_I, FV_U, FV_E, FV_O };
/* consonant classes: same numbering as MC_* of firmware/song/mora.h (checked by tests/test_mora_voice.c) */
enum { FC_NONE, FC_K, FC_S, FC_SH, FC_T, FC_CH, FC_TS, FC_N, FC_H, FC_F, FC_M, FC_Y, FC_R, FC_W, FC_G, FC_Z, FC_J, FC_D, FC_B, FC_P, FC_NN };
/* English (0.9): a syllable as phoneme codes, 0 = none. Codes are firmware/song/english.h's EP_* (ARPAbet order,
   1..15 vowels, 16..39 consonants) plus 40 = AX, the schwa (an unstressed AH); tests check the numbering. */
#define FV_EP_AX 40
typedef struct { uint8_t on[3], v, co[4]; } FvSyl;
/* mouth pictures (fv_viseme) */
enum { FVV_REST, FVV_MBP, FVV_FV, FVV_TH, FVV_EE, FVV_EH, FVV_AH, FVV_OH, FVV_OO, FVV_UH, FVV_L, FVV_R, FVV_N };
typedef struct { int32_t g, a1, a2; } FvRes;
typedef struct {
    /* params: set directly (fv_init gives defaults) */
    uint16_t vib_depth;         /* cents 0..100 (peak) */
    uint16_t vib_rate;          /* 0.1 Hz units, ~30..80 */
    uint16_t vib_delay;         /* ms before the vibrato fades in */
    uint16_t porta;             /* ms, 0..510 */
    uint16_t attack, release;   /* ms, 0..510; release = time to -40 dB */
    uint8_t breath, bright;     /* 0..127 */
    uint8_t human;              /* 0..127 master amount of pitch drift/jitter, shimmer, vibrato wander, leap overshoot (0 = machine-exact) */
    uint8_t scoop;              /* cents below the target at a (non-legato) note onset, 0..100 */
    uint16_t scoop_ms;          /* time for the onset scoop to settle, ms (<=510) */
    int8_t transpose;           /* semitones added to incoming notes */
    uint8_t chr;                /* voice character 0..4: formant size DEEP LOW MIKU (default, 2) YOUNG TINY; changes glide like a vowel */
    uint8_t chf;                /* 0..99: fine formant shift, that many % of the way from chr to chr+1 */
    uint8_t tension;            /* 0..100, 50 neutral: below softer (the soft pulse, darker), above pressed (brighter edge) */
    uint8_t fry;                /* 0..100: every other glottal pulse weaker (subharmonic growl, vocal fry) */
    uint8_t whisper;            /* 0..100: the voiced source fades into breath noise through the same formants */
    uint8_t cscale, vscale;     /* 25..175 % (100): consonant phase lengths, vowel/formant morph speed */
    uint16_t clevel;            /* 0..200 % (100): consonant noise and aspiration level */
    int16_t tune;               /* pitch offset, 4096 units per octave (detune) */
    uint8_t level_comp;         /* EXPERIMENTAL 0..100: bounded pitch/vowel lift; 0 preserves baseline. MIKU character only. */
    /* state */
    uint32_t ph, inc, lfo, noise, rng;
    int32_t pcur, ptgt;         /* pitch, 4096 units/octave above MIDI 0 */
    int32_t lp, env, gcur, gstep, sp, np, sc, ovt, ovo, ova, dr[3];
    int32_t y1[FV_NR], y2[FV_NR], cur[FV_NR][3];
    FvRes r[FV_NR];
    uint16_t vt, ovd;
    uint8_t gated, vowel, velg, left;
    /* consonant state (fv_note_on_mora): smoothed voicing / level / noise / aspiration, noise band, phase machine */
    int32_t vg, vgs, vgt, nl, nls, nlt, ap, aps, apt, cl, clt, ny1, ny2, nbc[3];
    uint16_t km, nkd;
    uint8_t cons, cph, cleft, cshp, lip, nzon, tail, nab, fcyc;
    /* English: the vowel's formant shape (Japanese: = vowel), a diphthong's second shape (255: none) and the blocks
       until its glide; queued onset / coda consonant classes; coda: the release starts at the coda's held end */
    uint8_t vsh, vsh2, oqn, kqn, coda, oq[2], kq[3];
    uint16_t dgl;
} FvVoice;
void fv_init(FvVoice *v);
void fv_note_on(FvVoice *v, uint8_t midi_note, uint8_t vowel, uint8_t velocity); /* gated: legato */
/* A mora: the consonant `cons` (FC_*) is a pitch- and tempo-independent event that starts AT the note start
   (fv_cons_ms(cons) later the vowel is fully there, so the vowel onset lands that much after the beat; a sequencer may
   start the note that early to put the vowel on the beat). A gated voice (legato) still articulates the consonant but
   keeps its envelope and pitch glide. FC_NN (moraic n) is a hum for the whole note and ignores `vowel`. */
void fv_note_on_mora(FvVoice *v, uint8_t midi_note, uint8_t cons, uint8_t vowel, uint8_t velocity);
uint32_t fv_cons_ms(uint8_t cons);                  /* ms from the note start until the vowel phase begins (0 for FC_NONE, FC_NN) */
void fv_note_off(FvVoice *v);
void fv_set_vowel(FvVoice *v, uint8_t vowel);       /* 0..4 = A I U E O */
void fv_render(FvVoice *v, int32_t *out, uint32_t frames); /* ADDS stereo Q15 L R */
/* English: onset consonants (a cluster plays them in turn) then the vowel (a diphthong glides to its second shape);
   fv_note_off_syl closes into the coda, then releases. fv_syl_onset_ms / fv_syl_coda_ms: the time from the note start
   to the vowel, and the coda's length, so a sequencer can put the vowel on the beat and end the word on time. */
void fv_note_on_syl(FvVoice *v, uint8_t midi_note, const FvSyl *s, uint8_t velocity);
void fv_note_off_syl(FvVoice *v, const FvSyl *s);
uint32_t fv_syl_onset_ms(const FvSyl *s);
uint32_t fv_syl_coda_ms(const FvSyl *s);
uint8_t fv_viseme(const FvVoice *v);               /* FVV_*: the mouth picture for what sounds now */
uint8_t fv_mouth(const FvVoice *v);                 /* 0..4 vowel, 5 closed (silent, or lips shut: m b p closure, the n hum) */
uint8_t fv_cons(const FvVoice *v);                  /* FC_* while a consonant sounds, else FC_NONE */
uint8_t fv_level(const FvVoice *v);                 /* envelope 0..255 */
#endif
