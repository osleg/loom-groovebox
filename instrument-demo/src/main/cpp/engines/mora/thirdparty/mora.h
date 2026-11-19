/* SPDX-License-Identifier: GPL-3.0-only */
/* Japanese mora table for lyrics. Index 0 = "no mora": the VOCAL step then sings the default vowel A (the vowel of a
 * VOCAL step always comes from its mora). Vowel codes match fvoice (A I U E O); moraic n has MV_N (a nasal hum, no vowel).
 * The consonant class (MC_*, same numbers as FC_* of fvoice) is sung by fv_note_on_mora. */
#ifndef MORA_H
#define MORA_H
#include <stdint.h>
#define MORA_N 73
enum { MV_A, MV_I, MV_U, MV_E, MV_O, MV_N };
enum { MC_NONE, MC_K, MC_S, MC_SH, MC_T, MC_CH, MC_TS, MC_N, MC_H, MC_F, MC_M, MC_Y, MC_R, MC_W, MC_G, MC_Z, MC_J, MC_D, MC_B, MC_P, MC_NN };
const char *mora_str(uint32_t i);       /* romaji, lower case; "" for 0 or out of range */
uint32_t mora_vowel(uint32_t i);        /* MV_*; 0 (A) for index 0 */
uint32_t mora_cons(uint32_t i);         /* MC_* */
uint32_t mora_find(const char *romaji); /* index (case-insensitive), 0 if not found */
#endif
