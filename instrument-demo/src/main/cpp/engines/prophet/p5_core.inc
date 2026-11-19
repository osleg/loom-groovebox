/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) 2026 Kerem Kilic (Ellic Studio)
 * Extracted from Melodee eng_prophet_test.c as vendored by FM1-Dock. */
static inline __attribute__((always_inline)) float p5_fast_blep(uint32_t ph, uint32_t inc)
{
    if (!inc) return 0.0f;
    if (ph < inc) {
        float x = (float)ph / (float)inc;
        return x + x - x * x - 1.0f;
    }
    if (ph > 0xFFFFFFFFu - inc) {
        float x = -(float)(0xFFFFFFFFu - ph) / (float)inc;
        return x * x + x + x + 1.0f;
    }
    return 0.0f;
}
static inline __attribute__((always_inline)) float p5_fast_osc_saw(uint32_t ph, uint32_t inc)
{
    return (float)ph * (1.0f / 2147483648.0f) - 1.0f - p5_fast_blep(ph, inc);
}
/* A float table keeps the seven SSI saturation evaluations inexpensive.
 * Linear interpolation has <6e-6 error and no integer amplitude steps. */
static inline __attribute__((always_inline)) float p5_fast_softclip(float x)
{
    float a=fabsf(x)*128.0f;
    if(a>=1024.0f)return x<0.0f?-1.0f:1.0f;
    uint32_t i=(uint32_t)a;
    float y=P5_TANH[i]+(P5_TANH[i+1u]-P5_TANH[i])*(a-(float)i);
    return x<0.0f?-y:y;
}


static inline __attribute__((always_inline)) float p5_pitch_ratio(float x)
{
    int32_t octave=(int32_t)x;if((float)octave>x)octave--;
    float pos=(x-(float)octave)*512.0f;uint32_t i=(uint32_t)pos;
    float ratio=P5_EXP2[i]+(P5_EXP2[i+1u]-P5_EXP2[i])*(pos-(float)i);
    union { uint32_t u; float f; } scale;scale.u=(uint32_t)(octave+127)<<23;
    return ratio*scale.f;
}

/* Four trapezoidal one-poles with an algebraic feedback solution. Curtis
 * saturates the input; SSI saturates each stage. These remain provisional
 * character models. Coefficients, feedback and stage memory are all fp32. */
static inline __attribute__((always_inline)) float p5_filter_core(p5_voice_t *s, float input, float cutoff, float k, float gain, int curtis)
{
    cutoff=p5_limit(cutoff,0.0f,127.0f);
    float g,g2,g3,g4,reciprocal;
    if(s->coeff_valid && cutoff==s->coeff_cut && k==s->coeff_k){
        g=s->coeff_g[0];g2=s->coeff_g[1];g3=s->coeff_g[2];g4=s->coeff_g[3];reciprocal=s->coeff_inv;
    }else{
        uint32_t i=(uint32_t)cutoff;g=P5_TPT_G[i];
        if(i<127u)g+=(P5_TPT_G[i+1u]-g)*(cutoff-(float)i);
        g2=g*g;g3=g2*g;g4=g3*g;
        reciprocal=1.0f/(1.0f+k*g4);
        s->coeff_g[0]=g;s->coeff_g[1]=g2;s->coeff_g[2]=g3;s->coeff_g[3]=g4;
        s->coeff_cut=cutoff;s->coeff_k=k;s->coeff_inv=reciprocal;s->coeff_valid=1;
    }
    float sigma=(1.0f-g)*(g3*s->z[0]+g2*s->z[1]+g*s->z[2]+s->z[3]);
    float u=p5_fast_softclip((input*gain-k*sigma)*reciprocal);
#define P5_STAGE(j, clip) do { \
    float delta=g*(u-s->z[j]); \
    float y=s->z[j]+delta; \
    s->z[j]=p5_limit(y+delta,-1.83105469f,1.83105469f); \
    if(fabsf(s->z[j])<1e-20f)s->z[j]=0.0f; \
    u=clip(y); \
} while(0)
#define P5_LINEAR(y) (y)
    if(curtis){P5_STAGE(0,P5_LINEAR);P5_STAGE(1,P5_LINEAR);P5_STAGE(2,P5_LINEAR);P5_STAGE(3,P5_LINEAR);}
    else {P5_STAGE(0,p5_fast_softclip);P5_STAGE(1,p5_fast_softclip);P5_STAGE(2,p5_fast_softclip);P5_STAGE(3,p5_fast_softclip);}
#undef P5_LINEAR
#undef P5_STAGE
    return p5_fast_softclip(u);
}
static float p5_filter(p5_voice_t *s,float input,float cutoff,float resonance,int curtis)
{
    return p5_filter_core(s,input,cutoff,resonance*(curtis?19200.0f:18800.0f)/(127.0f*4096.0f),
                         1.0f-resonance*(curtis?100.0f:45.0f)/32768.0f,curtis);
}
static inline __attribute__((always_inline)) float p5_wave(uint32_t phase, uint32_t inc, uint32_t pw, int saw, int pulse, int tri, float *triangle)
{
    if(!saw && !pulse && !tri)return 0.0f;
    float y=0.0f,a=p5_fast_osc_saw(phase,inc),square=0.0f;
    if(saw)y+=a*0.5f;
    if(pulse){square=(a-p5_fast_osc_saw(phase+pw,inc))*0.5f;y+=square;}
    if(tri){
        if(!pulse || pw!=0x80000000u)square=(a-p5_fast_osc_saw(phase+0x80000000u,inc))*0.5f;
        *triangle=p5_limit(*triangle*(32767.0f/32768.0f)+square*(float)inc*(8.0f/4294967296.0f),-1.220703125f,1.220703125f);
        y+=*triangle*0.5f;
    }
    return y;
}
static inline __attribute__((always_inline)) float p5_sync_fraction(uint32_t distance,uint32_t inc)
{
    return inc ? fminf((float)distance/(float)inc,1.0f) : 0.0f;
}
static inline __attribute__((always_inline)) uint32_t p5_pw(float value)
{
    return (uint32_t)(p5_limit(value,0.03125f,31743.0f/32768.0f)*4294967296.0f);
}
/* Exponential Poly-Mod, x in octaves; cap below Nyquist before conversion. */
static inline __attribute__((always_inline)) uint32_t p5_inc(uint32_t base,float x)
{
    if(!x)return base;
    float y=(float)base*p5_pitch_ratio(p5_limit(x,-8.0f,6.0f));
    return (uint32_t)p5_limit(y,1.0f,1932735232.0f);
}
