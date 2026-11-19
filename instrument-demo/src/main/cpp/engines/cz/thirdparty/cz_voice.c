/* SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) Devin Acker
 * Modifications Copyright (c) 2026 Kerem Kilic (Ellic Studio)
 * License: LICENSE-BSD-3-Clause-uPD933.txt
 * Native CZ-1 tone playback. Parameter encoding: Casio MIDI specification.
 * Chip envelope rate, phase functions and logarithmic DCA law follow the
 * independently documented uPD933 model by Devin Acker (MAME, BSD-3-Clause).
 * See docs/CZ1_SYSEX.md for sources and remaining calibration limits. */
#include "cz_voice.h"
#include "cz_sine.h"

#define CZ_AUDIO_RATE 44100.0f

typedef struct {
    float rate[3], level[3];
    uint8_t end, sustain;
} cz_native_def_t;

static inline float cz_clamp(float x, float lo, float hi)
{
    return x < lo ? lo : x > hi ? hi : x;
}

static const uint16_t CZ_AMP[128] = {
    0,4,5,5,5,6,6,7,7,8,8,9,9,10,11,12,
    12,13,14,15,16,18,19,20,22,23,25,27,29,31,33,36,
    38,41,44,47,51,54,58,63,67,72,77,83,89,96,103,110,
    118,127,136,146,157,168,180,194,208,223,239,257,275,296,317,340,
    365,392,421,451,484,520,558,598,642,689,739,793,851,914,980,1052,
    1129,1212,1300,1395,1497,1606,1724,1850,1985,2130,2286,2453,2632,2824,3031,3252,
    3490,3745,4019,4313,4628,4966,5329,5718,6136,6585,7066,7582,8136,8731,9369,10054,
    10789,11577,12423,13331,14305,15351,16473,17676,18968,20355,21842,23438,25151,26990,28962,31079
};
/* Native control domains: DCO semitones, DCW 11-bit phase units, DCA 0..127.
 * Preserve the uPD933's rate and target laws, using fractional control time. */
static float cz_hw_rate(uint32_t raw,uint32_t e)
{
    uint32_t chip=(8u|(raw&7u))<<((raw&127u)>>3);
    float scale=e==0u ? 1.0f/(8.0f*131072.0f) : e==1u ? 1.0f/(4.0f*16384.0f) : 1.0f/(2.0f*131072.0f);
    return (float)chip*(40000.0f/CZ_AUDIO_RATE)*scale;
}
static float cz_hw_target(uint32_t raw,uint32_t e)
{
    raw&=127u;
    if(e==0u)return (float)(raw&63u)*(raw&64u?2.0f:0.0625f);
    return (float)raw*(e==1u?8.0f:1.0f);
}
static __attribute__((noinline)) float cz_native_env_scaled(cz_native_env_t *e,const cz_native_def_t *d,uint32_t gate,float scale)
{
    float left=1.0f;
    if(e->gate && !gate && d->sustain<=d->end && e->stage<=d->sustain)e->stage=d->sustain+1u;
    e->gate=(uint8_t)gate;
    for(uint32_t k=0;k<8u && e->stage<=d->end;k++){
        uint32_t st=e->stage;float rate=d->rate[st]*scale,target=st==d->end?0.0f:d->level[st];
        float delta=target-e->level,dist=(delta < 0.0f ? -delta : delta),step=rate*left;
        if(dist>step){e->level+=delta<0.0f?-step:step;break;}
        e->level=target;if(gate && st==d->sustain)break;e->stage++;
        if(dist>0.0f && rate>0.0f)left=left-dist/rate;
        if(left<=0.0f)break;
    }
    return e->level;
}
static __attribute__((noinline)) float cz_native_amp(float level,uint32_t atten,uint32_t sense,uint32_t vel)
{
    float x=cz_clamp(level,0.0f,127.0f);uint32_t i=(uint32_t)x;
    float a=(float)CZ_AMP[i];
    if(i<127u)a+=(float)(CZ_AMP[i+1u]-CZ_AMP[i])*(x-(float)i);
    return a*(1.0f/32768.0f)*(float)(15u-atten)*(1.0f/15.0f)*
        (1.0f-(float)((127u-vel)*sense)*(1.0f/(127.0f*15.0f)));
}
/* Continuous evaluation of the chip's phase functions, with fp32 slopes.
 * uint32 phase retains tuning precision; no 11-bit truncation between samples. */
typedef struct { uint32_t wave[2],window; float dcw,pivot,k0,k1; } cz_hw_pd_t;
static __attribute__((noinline)) void cz_native_pd(cz_hw_pd_t *p,uint32_t word,float dcw)
{
    p->wave[0]=(word>>13)&7u;p->wave[1]=word&512u?(word>>10)&7u:p->wave[0];
    p->window=(word>>6)&7u;p->dcw=cz_clamp(dcw,0.0f,1023.0f);
    p->pivot=1024.0f-p->dcw;p->k0=1024.0f/p->pivot;p->k1=1024.0f/(2048.0f-p->pivot);
}
static __attribute__((noinline)) float cz_native_wave(const cz_hw_pd_t *b,uint32_t ph,uint32_t toggle)
{
    float pos=(float)ph*(2048.0f/4294967296.0f),half=pos>=1024.0f?pos-1024.0f:pos;
    float pivot=b->pivot,phase=0.0f,window=0.0f;
    switch(b->wave[toggle&1u]){
    case 0:phase=pos<pivot?pos*b->k0:1024.0f+(pos-pivot)*b->k1;break;
    case 1:phase=(half<pivot?half*b->k0:1024.0f)+(pos>=1024.0f?1024.0f:0.0f);break;
    case 2:phase=pos<pivot*2.0f?pos*b->k0:2048.0f;break;
    case 3:return 0.0f;
    case 4:phase=pos<pivot?pos*b->k0*2.0f:(pos-pivot)*b->k1*2.0f;break;
    case 5:phase=pos<1024.0f?pos:pos<pivot+1024.0f?1024.0f+half*b->k0:2048.0f;break;
    case 6:phase=pos*(1.0f+b->dcw*(1.0f/64.0f));break;
    default:phase=half<pivot?half*b->k0:2048.0f;break;
    }
    if(phase>=2048.0f)phase-=(float)(uint32_t)(phase*(1.0f/2048.0f))*2048.0f;
    switch(b->window){
    case 0:break;
    case 1:window=pos;break;
    case 2:window=pos<1024.0f?2048.0f-half*2.0f:half*2.0f;break;
    case 3:if(pos>=1024.0f)window=half*2.0f;break;
    case 4:window=pos<1024.0f?pos*2.0f:2048.0f;break;
    default:window=(1024.0f-half)*2.0f;break;
    }
    /* Interpolate the phase into the normalized sine table's cosine quadrant. */
    float x=phase*0.5f+256.0f;uint32_t i=(uint32_t)x;float f=x-(float)i;
    i&=1023u;
    float carrier=1.0f-(CZ_SIN[i]+(CZ_SIN[i+1u]-CZ_SIN[i])*f);
    return carrier*(1.0f-window*(1.0f/2048.0f))-1.0f;
}

void cz_voice_trigger(cz_voice_t *v, uint32_t seed, int fresh)
{
    if (fresh) *v = (cz_voice_t){0};
    v->noise = seed;
    v->dcw = (cz_native_env_t){.gate = 1};
}

static float cz_line(cz_voice_t *v, const cz_hw_pd_t *pd, uint32_t line, uint32_t delta)
{
    uint32_t old = v->phase[line];
    float raw = cz_native_wave(pd, old, v->toggle[line]);
    v->phase[line] += delta;
    if (v->phase[line] < old) v->toggle[line] ^= 1u;
    v->dc[line] += (raw - v->dc[line]) * (1.0f / 1024.0f);
    return (raw - v->dc[line]) * 0.5f;
}

void cz_voice_render(cz_voice_t *v, const cz_voice_params_t *p, int32_t *out, uint32_t n)
{
    cz_native_def_t env = {
        .rate = {cz_hw_rate(110, 1), cz_hw_rate(81, 1), cz_hw_rate(72, 1)},
        .level = {cz_hw_target(p->contour, 1), cz_hw_target(p->contour >> 2, 1), 0.0f},
        .end = 2, .sustain = 1,
    };
    float begin = v->dcw.level;
    float end = cz_native_env_scaled(&v->dcw, &env, p->gate, (float)n);
    float depth = 8.0f * (float)p->brightness + (begin + end) * 0.5f + p->modulation;
    uint32_t word_a = (uint32_t)p->wave[0] << 13;
    uint32_t word_b = (uint32_t)p->wave[1] << 13;
    uint32_t window = (uint32_t)p->window << 6;
    int alternate = p->mode == CZ_ALTERNATE;
    int have_b = !alternate && p->wave[1] != CZ_SILENCE && p->line_b != 0;
    float a_gain = cz_native_amp(127.0f, 0, 0, 127);
    float b_gain = cz_native_amp(95.0f + (32.0f / 127.0f) * p->line_b, 0, 0, 127);
    cz_hw_pd_t a, b;

    if (alternate) word_a |= 512u | ((uint32_t)p->wave[1] << 10);
    cz_native_pd(&a, word_a | window, depth);
    if (have_b) cz_native_pd(&b, word_b | window, depth);
    for (uint32_t i = 0; i < n; i++) {
        float s = a_gain * cz_line(v, &a, 0, p->inc[0]);
        if (have_b) {
            uint32_t step = p->inc[1];
            if (p->mode == CZ_NOISE) {
                v->noise ^= v->noise << 13;
                v->noise ^= v->noise >> 17;
                v->noise ^= v->noise << 5;
                if (v->noise & 1u)
                    step = (uint32_t)cz_clamp((float)step * (1625.0f / 256.0f), 0.0f, 2147483520.0f);
            }
            float other = b_gain * cz_line(v, &b, 1, step);
            if (p->mode == CZ_RING) s = s * other * 2.0f;
            else if (p->wave[0] == CZ_SILENCE) s = other;
            else s = (s + other) * 0.5f;
        }
        out[i] = (int32_t)cz_clamp(s * 32768.0f, -32768.0f, 32767.0f);
    }
}
