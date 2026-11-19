/* SPDX-License-Identifier: GPL-3.0-only
 * One voice extracted from zvenson's OberVave. See ../README.md. */
#ifndef OBV_VOICE_H
#define OBV_VOICE_H

#include <stdint.h>

enum { OBV_OFF, OBV_SAW, OBV_PULSE, OBV_BOTH };
enum { OBV_LP12, OBV_HP12, OBV_BP12, OBV_NOTCH, OBV_LP24, OBV_SEM };

typedef struct { uint32_t ph; int32_t prev; } obv_osc_t;
typedef struct { int32_t ic1, ic2; } obv_svf_state_t;

typedef struct {
    obv_osc_t o1, o2;
    obv_svf_state_t f1, f2;
    uint32_t noise;
} obv_voice_t;

typedef struct {
    uint32_t inc1, inc2, pw1, pw2;
    uint8_t wave1, wave2, sync, xmod, noise;
    uint8_t filter, morph, drive, resonance;
    int32_t cutoff_q8;
} obv_voice_params_t;

void obv_voice_render(obv_voice_t *v, const obv_voice_params_t *p, int32_t *out, uint32_t n);

#endif
