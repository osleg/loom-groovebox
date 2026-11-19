/* SPDX-License-Identifier: GPL-3.0-or-later
 * Local integration adapter for the donor's untracked fastmath.h dependency.
 * Uses the platform C math library; this file is not part of the donor snapshot. */
#ifndef ATEK303_FASTMATH_H
#define ATEK303_FASTMATH_H
#include <math.h>
#define FM_PI 3.14159265358979323846f
#define FM_TWO_PI 6.28318530717958647692f
#define FM_LN2 0.69314718055994530942f
#define fm_cosf cosf
#define fm_exp2f exp2f
#define fm_expf expf
#define fm_fabsf fabsf
#define fm_floorf floorf
#define fm_logf logf
#define fm_powf powf
#define fm_sinf sinf
#define fm_sqrtf sqrtf
#define fm_tanf tanf
#define fm_tanhf tanhf
static inline float fm_minf(float a, float b) { return a < b ? a : b; }
static inline float fm_maxf(float a, float b) { return a > b ? a : b; }
static inline float fm_sin_pi(float x) { return sinf(x); }
static inline float fm_sin_turns(float x) { return sinf(FM_TWO_PI * x); }
#endif
