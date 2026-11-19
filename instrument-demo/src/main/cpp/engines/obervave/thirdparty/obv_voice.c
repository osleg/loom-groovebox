/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 zvenson
 * Oscillator, mixer and filter path adapted from OberVave's obv_engine.h. */
#include "obv_voice.h"
#include "obv_dsp.h"

void obv_voice_render(obv_voice_t *v, const obv_voice_params_t *p, int32_t *out, uint32_t n)
{
    uint32_t i, inc1 = p->inc1, inc2 = p->inc2, d2 = inc2 >> 15;
    int32_t o2n = v->o2.prev;
    int32_t l1 = 100, l2 = 78, ln = (p->noise * p->noise) >> 7;
    int32_t drv = p->drive ? 4096 + p->drive * p->drive * 7 * 4096 / 16129 : 0;
    uint32_t ft = p->filter, xq = (uint32_t)((p->xmod * p->xmod) >> 7);
    uint32_t ww1 = p->wave1, ww2 = p->wave2;
    int32_t hw1 = (ww1 & OSC_SAW ? -65536 : 0) + (ww1 & OSC_PLS ? -65536 : 0);
    int32_t hw2 = (ww2 & OSC_SAW ? -65536 : 0) + (ww2 & OSC_PLS ? -65536 : 0);
    svf_coef_t c1, c2;

    svf_coef(&c1, p->cutoff_q8, 8192 - (int32_t)p->resonance * 8400 / 127);
    if (ft == OBV_LP24)
        svf_coef(&c2, p->cutoff_q8, 5793);
    for (i = 0; i < n; i++) {
        int32_t s1, s2, mix, lp, bp, hp, y, rt = -1;
        uint32_t ie = inc1, d1;
        if (xq) {                               /* OSC2 -> OSC1 linear FM */
            uint32_t amount = inc1 >> 8;
            int64_t m;
            if (amount > 0x200000u) amount = 0x200000u;
            m = inc1 + (int64_t)amount * (((o2n >> 8) * (int32_t)xq) >> 6);
            if (m < (int64_t)(inc1 >> 4)) m = inc1 >> 4;
            if (m > 0x7FFFFFFF) m = 0x7FFFFFFF;
            ie = (uint32_t)m;
        }
        d1 = ie >> 15;
        s1 = osc_step(&v->o1, ie, d1, p->pw1, ww1, hw1, -1);
        if (p->sync && v->o1.ph < ie && d1)
            rt = clampi((int32_t)(v->o1.ph / d1), 0, 32767);
        s2 = osc_step(&v->o2, inc2, d2, p->pw2, ww2, hw2, rt);
        o2n = v->o2.prev;
        mix = s1 * l1 + s2 * l2;
        if (ln) mix += ((int32_t)(xorshift(&v->noise) >> 16) - 32768) * ln;
        mix >>= 7;
        if (drv) mix = softclip(((mix >> 3) * drv) >> 9);
        else mix = clampi(mix, -65535, 65535);
        svf_step(&c1, &v->f1, mix, &lp, &bp, &hp);
        switch (ft) {
        case OBV_HP12: y = hp; break;
        case OBV_BP12: y = bp; break;
        case OBV_NOTCH: y = hp + lp; break;
        case OBV_LP24: y = svf_lp(&c2, &v->f2, lp); break;
        case OBV_SEM: y = lp + (((hp - lp) * p->morph) >> 7); break;
        default: y = lp; break;
        }
        out[i] = clampi(y, -65000, 65000) >> 1;
    }
}
