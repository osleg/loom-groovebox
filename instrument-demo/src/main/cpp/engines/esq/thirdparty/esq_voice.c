/* SPDX-License-Identifier: GPL-3.0-only
 * Derived from FM-1 ESQ (Joakim Eriksson), engine/esq_engine.c at
 * 7cae256074a0c3c24c3fab3d5df335fcb93ef419.
 * Adaptation to a mono, independently allocated Skiff note. The DOC clock,
 * sample reads, resonance loop, overdrive and FIR retain the donor's math. */
#include "esq_voice.h"
#include "esq_tables.h"
#include "esq_waves.h"

#if ESQ_WAVES_ROM || ESQ_ROM_BYTES != 65536
#error ESQ synthesis requires the computed ESQ stand-in tables
#endif

enum { RES_Q12 = 457, SAT_L = 739932, SAT_INV = 1451 };
_Static_assert(ESQ_FIR_TAPS == ESQ_VOICE_HISTORY, "native resampler history");

typedef struct {
    const int8_t *wave;
    uint32_t increment;
    uint8_t shift, level;
} esq_doc_osc_t;

typedef struct {
    int32_t g, a[4], resonance, gain, inverse;
} esq_filter_t;

static int32_t clampi(int32_t x, int32_t lo, int32_t hi)
{
    return x < lo ? lo : x > hi ? hi : x;
}

static int32_t mul28(int32_t a, int32_t b)
{
    return (int32_t)(((int64_t)a * b) >> 28);
}

/* Pitch in 1/256 semitone to the DOC's 16-bit frequency register. */
static uint32_t doc_fr(int32_t pitch)
{
    uint32_t p = (uint32_t)(clampi(pitch, -60 * 256, 127 * 256 + 255) + 60 * 256);
    uint32_t oct = p / 3072u, rest = p - oct * 3072u, i = rest >> 3;
    uint32_t v = ESQ_FR[i] + (((ESQ_FR[i + 1u] - ESQ_FR[i]) * (rest & 7u)) >> 3);
    uint32_t shift = 23u - oct;
    v = (v + (1u << (shift - 1u))) >> shift;
    return v > 65535u ? 65535u : v ? v : 1u;
}

static esq_doc_osc_t doc_osc(const esq_voice_control_t *control, uint32_t i)
{
    static const int8_t silent[2] = {0, 0};
    uint32_t wave = control->wave[i];
    int32_t transpose = control->transpose[i];
    if (!wave || wave > ESQ_VOICE_WAVES) return (esq_doc_osc_t){silent, 0u, 31u, 0u};
    uint32_t zone = (uint32_t)clampi(control->key + transpose, 0, 127) >> 3;
    const esq_raw_t *raw = &ESQ_RAW[ESQ_ZONE[wave - 1u][zone]];
    uint32_t freq = doc_fr(control->pitch + (transpose + raw->semi) * 256 + raw->fine);
    return (esq_doc_osc_t){ESQ_WAVE_ROM + (uint32_t)raw->page * 256u,
                           freq << (15u - raw->res), (uint8_t)(32u - raw->bits), control->level[i]};
}

static esq_filter_t filter_coeffs(const esq_voice_control_t *control)
{
    uint32_t dac = control->cutoff, i = dac >> 8;
    int32_t g = (int32_t)(ESQ_FC_G[i] + (((ESQ_FC_G[i + 1u] - ESQ_FC_G[i]) * (dac & 255u)) >> 8));
    int32_t g2 = mul28(g, g), g3 = mul28(g2, g), g4 = mul28(g3, g), one_minus_g = (1 << 28) - g;
    int32_t res = (int32_t)control->resonance * RES_Q12;
    esq_filter_t f = {g, {mul28(one_minus_g, g3), mul28(one_minus_g, g2),
                          mul28(one_minus_g, g), one_minus_g}, res,
                      4096 + ((res * 3) >> 3),
                      (int32_t)(0x80000000u / (uint32_t)(4096 + (((int64_t)res * g4) >> 28)))};
    return f;
}

static int16_t sat16(int32_t x)
{
    if (x > 24576) x = 24576 + ((x - 24576) >> 2);
    if (x < -24576) x = -24576 + ((x + 24576) >> 2);
    return (int16_t)clampi(x, -32767, 32767);
}

static void native_frames(esq_core_voice_t *voice, const esq_voice_control_t *control,
                          int16_t *out, uint32_t count)
{
    esq_doc_osc_t o0 = doc_osc(control, 0), o1 = doc_osc(control, 1), o2 = doc_osc(control, 2);
    esq_filter_t f = filter_coeffs(control);
    uint32_t ph0 = voice->phase[0], ph1 = voice->phase[1], ph2 = voice->phase[2];
    int32_t s0 = voice->integrator[0], s1 = voice->integrator[1];
    int32_t s2 = voice->integrator[2], s3 = voice->integrator[3], dc = voice->dc;
    int sync = control->mode & ESQ_VOICE_SYNC, am = control->mode & ESQ_VOICE_AM;
    for (uint32_t i = 0; i < count; i++) {
        int32_t d0 = o0.wave[ph0 >> o0.shift], d1 = o1.wave[ph1 >> o1.shift], d2 = o2.wave[ph2 >> o2.shift];
        uint32_t prev = ph0;
        ph0 += o0.increment;
        ph1 += o1.increment;
        ph2 += o2.increment;
        if (sync && ph0 < prev) ph1 = 0;

        int32_t x = d2 * o2.level + (am ? (d1 * (d0 + 128) * o1.level) >> 8 :
                                          d0 * o0.level + d1 * o1.level);
        int32_t u = x * 8, t;
        if (f.resonance) {
            int32_t sum = (int32_t)(((int64_t)f.a[0] * s0 + (int64_t)f.a[1] * s1 +
                                     (int64_t)f.a[2] * s2 + (int64_t)f.a[3] * s3) >> 28);
            u = (int32_t)(((int64_t)u * f.gain - (int64_t)f.resonance * sum) >> 12);
            u = (int32_t)(((int64_t)u * f.inverse) >> 19);
        }
        if (u >= SAT_L) u = SAT_L / 3 * 2;
        else if (u <= -SAT_L) u = -(SAT_L / 3 * 2);
        else {
            t = (u * SAT_INV) >> 15;
            u -= (int32_t)(((int64_t)u * (((t * t) >> 15) * 10923)) >> 30);
        }
        t = mul28(u - s0, f.g); u = t + s0; s0 = u + t;
        t = mul28(u - s1, f.g); u = t + s1; s1 = u + t;
        t = mul28(u - s2, f.g); u = t + s2; s2 = u + t;
        t = mul28(u - s3, f.g); u = t + s3; s3 = u + t;
        dc += (u - dc) >> 10;
        out[i] = sat16((u - dc) >> 3);
    }
    voice->phase[0] = ph0; voice->phase[1] = ph1; voice->phase[2] = ph2;
    voice->integrator[0] = s0; voice->integrator[1] = s1;
    voice->integrator[2] = s2; voice->integrator[3] = s3;
    voice->dc = dc;
}

void esq_voice_render(esq_core_voice_t *voice, const esq_voice_control_t *control,
                      int32_t *out, uint32_t count)
{
    int16_t native[ESQ_VOICE_HISTORY + ESQ_VOICE_BLOCK];
    const int16_t *history = native;
    uint32_t fraction = voice->fraction;
    uint32_t needed = (uint32_t)(((uint64_t)fraction + (uint64_t)ESQ_STEP_Q32 * count) >> 32);
    int32_t lp0 = voice->output_lp[0], lp1 = voice->output_lp[1];
    for (uint32_t i = 0; i < ESQ_VOICE_HISTORY; i++) native[i] = voice->history[i];
    native_frames(voice, control, native + ESQ_VOICE_HISTORY, needed);
    for (uint32_t i = 0; i < count; i++) {
        uint32_t next = fraction + ESQ_STEP_Q32;
        if (next < fraction) history++;
        fraction = next;
        const int16_t *kernel = ESQ_FIR[fraction >> 24];
        int32_t sample = 0;
        for (uint32_t tap = 0; tap < ESQ_FIR_TAPS; tap++) sample += kernel[tap] * history[tap];
        sample >>= 6;
        int32_t t = (int32_t)(((int64_t)(sample - lp0) * ESQ_OUT_G1) >> 15);
        sample = t + lp0; lp0 = sample + t;
        t = (int32_t)(((int64_t)(sample - lp1) * ESQ_OUT_G2) >> 15);
        sample = t + lp1; lp1 = sample + t;
        out[i] = sample >> 8;
    }
    voice->fraction = fraction;
    voice->output_lp[0] = lp0; voice->output_lp[1] = lp1;
    for (uint32_t i = 0; i < ESQ_VOICE_HISTORY; i++) voice->history[i] = native[needed + i];
}
