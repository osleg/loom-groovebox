/* SPDX-License-Identifier: BSD-3-Clause
 * Portable voice interface for the Melodee uPD933 port. See ../README.md. */
#ifndef SKIFF_CZ_VOICE_H
#define SKIFF_CZ_VOICE_H
#include <stdint.h>

enum { CZ_MIX, CZ_ALTERNATE, CZ_RING, CZ_NOISE };
#define CZ_SILENCE 3u

typedef struct {
    float level;
    uint8_t stage, gate;
} cz_native_env_t;

typedef struct {
    uint32_t phase[2], toggle[2], noise;
    float dc[2];
    cz_native_env_t dcw;
} cz_voice_t;

typedef struct {
    uint32_t inc[2];
    uint8_t wave[2], window, mode, brightness, contour, line_b, gate;
    float modulation;
} cz_voice_params_t;

void cz_voice_trigger(cz_voice_t *v, uint32_t seed, int fresh);
void cz_voice_render(cz_voice_t *v, const cz_voice_params_t *p, int32_t *out, uint32_t n);
#endif
