/* SPDX-License-Identifier: MIT
 * Copyright (c) 2026 Jameson Hodge (CHIMERA, https://github.com/jamesondh/chimera-vocal-synth)
 * C port for the M-VAVE FM-1: Copyright (c) 2026 Shan-Sherif.
 *
 * What the literal port (ch_voice.c) and the fast one (ch_fast.c) share: the math, CHIMERA's PRNG,
 * the glottis, the noise sources and the control-step bookkeeping, all as CHIMERA has them.
 * Included by both; everything here is static. */
#ifndef CH_COMMON_H
#define CH_COMMON_H
#include "ch_voice.h"
#if defined(CH_FM1)       /* the FM-1 (fm1-base): no libm; fm1-base's single-precision maths (MPL-2.0) */
#include "fastmath.h"
#elif defined(CH_FREESTANDING)   /* the cost estimate's compile: the target provides these */
float sqrtf(float), expf(float), powf(float, float), sinf(float), cosf(float), tanhf(float), fabsf(float);
float atan2f(float, float), acosf(float), hypotf(float, float);
#else
#include <math.h>
#endif

/* FM-1 builds: hot code in RAM, out of the way of the XIP cache's misses (the code otherwise runs from the
 * flash). CH_IN_RAM puts a function there (fm1-base's .c1_text: copied to RAM at boot); a unit that defines CH_RAM
 * before including this file has CH_HOT do so for its own hot code and these shared helpers. RAM and flash are
 * farther apart than a direct call reaches, so code in the flash calls a RAM function through CH_FAR(fn)(...): a
 * pointer the compiler cannot turn back into a direct call (as fm1-base's PLAT_FAR). Host builds: plain code. */
#if defined(CH_FM1) && !defined(PLAT_HOST)
static inline __attribute__((always_inline)) void *ch_far_ptr(void *fn)
{
    void *volatile held = fn;           /* read back from memory: the compiler no longer knows the target */
    return held;
}
#define CH_FAR(fn) ((__typeof__(&(fn)))ch_far_ptr((void *)&(fn)))
#define CH_IN_RAM __attribute__((section(".c1_text")))
#else
#define CH_FAR(fn) (&(fn))
#define CH_IN_RAM
#endif
#ifdef CH_RAM
#define CH_HOT CH_IN_RAM
#else
#define CH_HOT
#endif
#define CH_INLINE static inline __attribute__((always_inline))

#if defined(CH_FM1)
#define R(x) ((ch_real)(x##f))
/* atan for |z| <= 1 (a minimax polynomial, error < 1e-5 rad), then the quadrant */
CH_INLINE float ch_atan2f(float y, float x)
{
    float ax = fm_fabsf(x), ay = fm_fabsf(y), z, z2, r;
    if (ax == 0.0f && ay == 0.0f)
        return 0.0f;
    z = ax >= ay ? ay / ax : ax / ay;
    z2 = z * z;
    r = z * (0.9998660f + z2 * (-0.3302995f + z2 * (0.1801410f + z2 * (-0.0851330f + z2 * 0.0208351f))));
    if (ay > ax)
        r = 1.5707963f - r;
    if (x < 0.0f)
        r = 3.1415927f - r;
    return y < 0.0f ? -r : r;
}
/* acos(x) = 2 atan2(sqrt(1 - x), sqrt(1 + x)), exact as an identity */
CH_INLINE float ch_acosf(float x)
{
    x = fm_clampf(x, -1.0f, 1.0f);
    return 2.0f * ch_atan2f(fm_sqrtf(1.0f - x), fm_sqrtf(1.0f + x));
}
/* fm_powf wants x > 0; the area law takes pow(0, 1.4) = 0 */
CH_INLINE float ch_powf(float x, float y) { return x > 0.0f ? fm_powf(x, y) : 0.0f; }
CH_INLINE float ch_hypotf(float x, float y) { return fm_sqrtf(x * x + y * y); }
/* The audio rate runs from RAM (CH_RAM), where every helper it calls must be inlined: a helper the compiler keeps out
 * of line lands in the flash, out of a direct call's reach. These are always inlined (fastmath.h's may not be). */
CH_INLINE float ch_absf(float x)
{
    union { float f; uint32_t u; } b;
    b.f = x;
    b.u &= 0x7fffffffu;
    return b.f;
}
/* x * rsqrt(x): the bit-level first guess, three Newton steps (relative error ~1e-7) */
CH_INLINE float ch_sqrt_in(float x)
{
    union { float f; uint32_t u; } b;
    float y;
    if (x <= 0.0f)
        return 0.0f;
    b.f = x;
    b.u = 0x5f375a86u - (b.u >> 1);
    y = b.f;
    y = y * (1.5f - 0.5f * x * y * y);
    y = y * (1.5f - 0.5f * x * y * y);
    y = y * (1.5f - 0.5f * x * y * y);
    return x * y;
}
/* sin: reduced to [-pi, pi], folded to [-pi/2, pi/2], then its Taylor series to x^11 (error < 1e-7) */
CH_INLINE float ch_sin_in(float x)
{
    float k = x * 0.15915494f, y, y2;
    int32_t n = (int32_t)(k + (k >= 0.0f ? 0.5f : -0.5f));
    y = x - (float)n * 6.2831853f;
    if (y > 1.5707963f)
        y = 3.1415927f - y;
    else if (y < -1.5707963f)
        y = -3.1415927f - y;
    y2 = y * y;
    return y * (1.0f + y2 * (-1.6666667e-1f + y2 * (8.3333333e-3f + y2 * (-1.9841270e-4f + y2 * (2.7557319e-6f - y2 * 2.5052108e-8f)))));
}
CH_INLINE float ch_cos_in(float x) { return ch_sin_in(x + 1.5707963f); }
/* 2^x: the integer part into the exponent, the fraction from a degree-6 polynomial (relative error ~2e-7) */
CH_INLINE float ch_exp2_in(float x)
{
    union { float f; uint32_t u; } b;
    float f, p;
    int32_t i;
    if (x < -126.0f)
        return 0.0f;
    if (x > 127.0f)
        x = 127.0f;
    i = (int32_t)x;
    if ((float)i > x)
        i--;
    f = x - (float)i;
    p = 1.0f + f * (0.69314718f + f * (0.24022651f + f * (0.05550411f + f * (0.00961813f + f * (0.00133336f + f * 0.00015403f)))));
    b.u = (uint32_t)(i + 127) << 23;
    return p * b.f;
}
/* tanh = 1 - 2 / (e^2x + 1), for the output's soft clip */
CH_INLINE float ch_tanh_in(float x) { return 1.0f - 2.0f / (ch_exp2_in(2.8853901f * x) + 1.0f); }
#define EXP fm_expf
#define POW ch_powf
#define ATAN2 ch_atan2f
#define ACOS ch_acosf
#define HYPOT ch_hypotf
#define FABS ch_absf
#define SQRT ch_sqrt_in
#ifdef CH_RAM
#define SIN ch_sin_in
#define COS ch_cos_in
#define EXP2 ch_exp2_in
#define TANH ch_tanh_in
#else
#define SIN fm_sinf
#define COS fm_cosf
#define EXP2 fm_exp2f
#define TANH fm_tanhf
#endif
#elif defined(CH_FLOAT)
#define R(x) ((ch_real)(x##f))
#define EXP expf
#define POW powf
#define SIN sinf
#define COS cosf
#define TANH tanhf
#define ATAN2 atan2f
#define ACOS acosf
#define HYPOT hypotf
/* |x| by clearing the sign bit (fabsf is a call in a -fno-builtin build) */
static inline float ch_fabsf(float x)
{
    union { float f; uint32_t u; } b;
    b.f = x;
    b.u &= 0x7fffffffu;
    return b.f;
}
#define FABS ch_fabsf
#ifdef CH_FASTMATH
/* sqrt as x * rsqrt(x): the classic bit-level first guess, then three Newton steps (rel. error ~1e-7).
 * The FM-1's FPU multiplies and divides but has no square root. */
static inline float ch_sqrtf(float x)
{
    union { float f; uint32_t u; } b;
    float y;
    if (x <= 0.0f)
        return 0.0f;
    b.f = x;
    b.u = 0x5f375a86u - (b.u >> 1);
    y = b.f;
    y = y * (1.5f - 0.5f * x * y * y);
    y = y * (1.5f - 0.5f * x * y * y);
    y = y * (1.5f - 0.5f * x * y * y);
    return x * y;
}
#define SQRT ch_sqrtf
/* cos(x) for |x| <= pi (the glottal pulse's arguments are in [0, pi]): sin(pi/2 - |x|) from its
 * Taylor series to x^11, absolute error < 1e-7 */
static inline float ch_cosf(float x)
{
    float y = 1.5707963f - ch_fabsf(x), y2 = y * y;
    return y * (1.0f + y2 * (-1.6666667e-1f + y2 * (8.3333333e-3f + y2 * (-1.9841270e-4f + y2 * (2.7557319e-6f - y2 * 2.5052108e-8f)))));
}
#undef COS
#define COS ch_cosf
#else
#define SQRT sqrtf
#endif
#else
#define R(x) ((ch_real)(x))
#define SQRT sqrt
#define EXP exp
#define POW pow
#define SIN sin
#define COS cos
#define TANH tanh
#define FABS fabs
#define ATAN2 atan2
#define ACOS acos
#define HYPOT hypot
#endif
#ifndef EXP2
#define EXP2(x) POW(R(2.0), (x))   /* CHIMERA's pow(2, x): the double build stays exact */
#endif

/* physics.rs, CGS */
#define RHO R(1.14e-3)
#define C_SOUND R(35000.0)
#define MU R(1.86e-4)
#define NU (MU / RHO)
#define RHO_C2 (RHO * C_SOUND * C_SOUND)
#define RE_CRIT R(1800.0)
#define CH_PI R(3.14159265358979323846)
#define CH_TAU R(6.28318530717958647692)
#define TINY R(1e-30)

CH_INLINE ch_real mn(ch_real a, ch_real b) { return a < b ? a : b; }
CH_INLINE ch_real mx(ch_real a, ch_real b) { return a > b ? a : b; }
CH_INLINE ch_real clampr(ch_real x, ch_real lo, ch_real hi) { return x < lo ? lo : x > hi ? hi : x; }
/* JavaScript's Math.round (halves up), as CHIMERA's js_round */
CH_INLINE long js_round_c(ch_real x)
{
    ch_real y = x + R(0.5);
    long i = (long)y;
    if ((ch_real)i > y)
        i--;
    return i;
}
CH_INLINE void tiny(ch_real *x) { if (FABS(*x) < TINY) *x = 0; }
CH_INLINE ch_real smoothstep(ch_real e0, ch_real e1, ch_real x)
{
    ch_real t = clampr((x - e0) / (e1 - e0), 0, 1);
    return t * t * (R(3.0) - R(2.0) * t);
}

/* ------------------------------------------------------------ math.rs Rng (mulberry32) --- */
CH_INLINE ch_real rng_next(uint32_t *s)
{
    uint32_t t;
    *s += 0x6d2b79f5u;
    t = *s;
    t = (t ^ (t >> 15)) * (t | 1u);
    t ^= t + (t ^ (t >> 7)) * (t | 61u);
#ifdef CH_FLOAT
    /* the top 24 bits, which a float holds exactly (no double arithmetic on the FM-1) */
    return (ch_real)(int32_t)((t ^ (t >> 14)) >> 8) * R(5.9604645e-8);
#else
    return (ch_real)((double)(t ^ (t >> 14)) / 4294967296.0);
#endif
}
CH_INLINE ch_real rng_bipolar(uint32_t *s) { return rng_next(s) * R(2.0) - R(1.0); }
CH_INLINE ch_real rng_gauss(uint32_t *s)
{
    ch_real a = rng_next(s), b = rng_next(s), c = rng_next(s), d = rng_next(s);
    return (a + b + c + d - R(2.0)) * R(1.7320508);
}

/* ------------------------------------------------------------------- sources/glottis.rs --- */
static const ch_glottis_params_t DEFAULT_GLOTTIS = {
    110.0, 0.16, 0.0, 0.006, 0.62, 0.62, 0.6, 0.7, 0.004, 0.03, 0.0, 6.0, 6.0, 0.0, 0.0, 0.0, 0.0,
};

static void glottis_init(ch_glottis_t *g, ch_real fs)
{
    int i;
    ch_real f[3];
    g->p = DEFAULT_GLOTTIS;
    g->fs = fs;
    g->phase = 0;
    g->cycle_amp = 1;
    g->cycle_period_scale = 1;
    g->cycle_index = 0;
    g->chaos_x = R(0.37);
    g->vib_phase = 0;
    g->rng = 777;
    /* Drift::new(fs / 32, 0.3, 4242) */
    g->drift_rng = 4242;
    f[0] = R(0.3);
    f[1] = R(0.3) * R(3.0);
    f[2] = R(0.3) * R(9.0);
    for (i = 0; i < 3; i++) {
        g->drift_y[i] = 0;
        g->drift_a[i] = EXP((-CH_TAU * f[i]) / (fs / R(32.0)));
    }
    g->drift_value = 0;
    g->drift_counter = 0;
    g->pitch_factor = 1;
    g->voicing = 0;
    g->voicing_sm = 0;
    g->dp_sm = 0;
    g->dp_coef = R(1.0) - EXP(-CH_TAU * R(30.0) / fs);
    g->voice_coef = R(1.0) - EXP(-CH_TAU * R(25.0) / fs);
    g->area = 0;
    g->f0_now = 110;
    g->threshold = R(2.5) * R(980.665);
    g->static_sm = 0;
    g->closure_sm = 0;
    g->amp_sm = 0;
    g->sm_coef = R(1.0) - EXP(R(-1.0) / (R(0.004) * fs));
}

CH_HOT static ch_real drift_step(ch_glottis_t *g)
{
    ch_real s = 0;
    int i;
    for (i = 0; i < 3; i++) {
        ch_real a = g->drift_a[i];
        g->drift_y[i] = a * g->drift_y[i] + SQRT(R(1.0) - a * a) * rng_gauss(&g->drift_rng);
        s += g->drift_y[i] / ((ch_real)i + R(1.0));
    }
    return s / R(1.4);
}

CH_HOT static ch_real shape(ch_real ph, ch_real oq, ch_real skew, ch_real sharp)
{
    ch_real t, s, u, soft, hard;
    if (ph >= oq)
        return 0;
    t = ph / oq;
    s = clampr(skew, R(0.3), R(0.9));
    if (t < s)
        return R(0.5) - R(0.5) * COS((CH_PI * t) / s);
    u = (t - s) / (R(1.0) - s);
    soft = R(0.5) + R(0.5) * COS(CH_PI * u);
    hard = COS(R(0.5) * CH_PI * u);
    return soft + (hard - soft) * sharp;
}

CH_HOT static inline ch_real glottis_next(ch_glottis_t *g, ch_real dp)
{
    const ch_glottis_params_t p = g->p;
    ch_real f0, pressure_gate, adduct_gate, target, oq, pulse, effort_scale, sc, vib, statics, a;
    g->dp_sm += g->dp_coef * (dp - g->dp_sm);

    g->drift_counter -= 1;
    if (g->drift_counter <= 0) {
        ch_real cents;
        g->drift_counter = 32;
        g->drift_value = drift_step(g);
        g->vib_phase += ((p.vibrato_rate * R(32.0)) / g->fs) * (R(1.0) + R(0.04) * g->drift_value);
        if (g->vib_phase > R(1.0))
            g->vib_phase -= R(1.0);
        cents = p.vibrato_depth * SIN(CH_TAU * g->vib_phase) + p.drift * g->drift_value;
        g->pitch_factor = EXP2(cents / R(1200.0));
    }
    f0 = p.f0 * g->pitch_factor;
    if (p.fry > 0)
        f0 = f0 * (R(1.0) - p.fry) + mn(f0, R(48.0)) * p.fry;
    g->f0_now = f0;

    pressure_gate = smoothstep(g->threshold * R(0.8), g->threshold * R(1.6), g->dp_sm);
    adduct_gate = R(1.0) - smoothstep(R(0.08), R(0.22), g->static_sm - p.chink);
    target = pressure_gate * adduct_gate * (R(1.0) - g->closure_sm);
    g->voicing_sm += g->voice_coef * (target - g->voicing_sm);
    g->voicing = g->voicing_sm;

    g->phase += (f0 / g->fs) / g->cycle_period_scale;
    if (g->phase >= R(1.0)) {
        ch_real alt, chaos_p = 0, chaos_a = 0;
        g->phase -= R(1.0);
        g->cycle_index += 1;
        alt = (g->cycle_index & 1) ? R(1.0) : R(-1.0);
        if (p.chaos > 0) {
            ch_real r = R(2.9) + R(1.1) * mn(R(1.0), p.chaos), dev;
            g->chaos_x = r * g->chaos_x * (R(1.0) - g->chaos_x);
            if (!(g->chaos_x > 0 && g->chaos_x < R(1.0)))
                g->chaos_x = R(0.37);
            dev = g->chaos_x - R(0.5);
            chaos_p = R(0.45) * p.chaos * dev;
            chaos_a = R(1.1) * p.chaos * dev;
        }
        g->cycle_period_scale = R(1.0) + p.jitter * rng_gauss(&g->rng) + p.diplophonia * R(0.08) * alt + chaos_p;
        g->cycle_period_scale = clampr(g->cycle_period_scale, R(0.55), R(1.8));
        g->cycle_amp = mx(R(0.12), R(1.0) + p.shimmer * rng_gauss(&g->rng) - p.diplophonia * R(0.35) * (alt > 0 ? R(1.0) : R(0.0)) + chaos_a);
    }

    oq = clampr(p.open_quotient - R(0.18) * (p.effort - R(0.5)), R(0.25), R(0.98));
    if (p.fry > 0)
        oq = oq * (R(1.0) - p.fry) + R(0.28) * p.fry;
    pulse = shape(g->phase, oq, p.skew, p.closure_sharpness);
    if (p.fry > R(0.3)) {
        ch_real ph2 = g->phase - R(0.38);
        if (ph2 > 0)
            pulse += R(0.55) * smoothstep(R(0.3), R(0.8), p.fry) * shape(ph2, oq * R(0.8), p.skew, p.closure_sharpness);
    }
    effort_scale = R(0.45) + R(0.75) * p.effort;
    sc = g->sm_coef;
    g->amp_sm += sc * (p.amplitude * effort_scale - g->amp_sm);
    g->closure_sm += sc * (p.closure - g->closure_sm);
    g->static_sm += sc * (p.abduction + p.chink - g->static_sm);
    vib = g->amp_sm * g->cycle_amp * g->voicing_sm * pulse;
    statics = g->static_sm * (R(1.0) - R(0.92) * g->closure_sm);
    a = mx(0, vib + statics);
    g->area = a;
    return a;
}

CH_HOT static void glottis_flush_tiny(ch_glottis_t *g)
{
    tiny(&g->dp_sm);
    tiny(&g->voicing_sm);
    tiny(&g->amp_sm);
    tiny(&g->closure_sm);
    tiny(&g->static_sm);
}

/* -------------------------------------------------------------- airway/network.rs: control --- */
static inline void init_state(ch_voice_t *v)
{
    int k, j;
    v->t = R(1.0) / v->fs;
    v->inv_t = v->fs;
    for (k = 0; k < v->n; k++) {
        v->p[k] = 0;
        v->area_target[k] = v->area[k];
        v->amin_target[k] = v->amin[k];
        v->dx_target[k] = v->dx[k];
        v->area_step[k] = v->amin_step[k] = v->dx_step[k] = 0;
        v->inv_amin[k] = v->inv_lsum[k] = v->half_l[k] = v->half_r[k] = v->sqrt_a[k] = 0;
        v->flow_sum[k] = v->spill[k] = v->vol_src1[k] = v->vol_src2[k] = 0;
        v->wall_y[k] = v->wall_v[k] = 0;
    }
    for (j = 0; j < v->nb; j++) {
        v->u[j] = v->mom[j] = v->l_prev[j] = v->psrc[j] = v->branch_area[j] = 0;
        v->port_area_target[j] = v->port_area[j];
        v->port_area_step[j] = 0;
    }
    for (j = 0; j < v->nr; j++)
        v->rad_ul[j] = v->rad_l_prev[j] = v->rad_u[j] = 0;
    v->rad_prev_total = 0;
    v->n_noise = 0;
    v->rng = 12345;
    v->p_lung = v->p_lung_now = v->p_lung_step = 0;
    v->dc_x = v->dc_y = v->lim_env = 0;
    v->lim_hold = 0;
    glottis_init(&v->glottis, v->fs);
}

/* AirwayNetwork::set_noise_sources: filter states of a source whose (src, fwd) persists are kept */
CH_HOT static void set_noise_sources(ch_voice_t *v, const ch_noise_t *list, int n)
{
    ch_noise_t next[CH_MAXNOISE];
    int i, j;
    if (n > CH_MAXNOISE)
        n = CH_MAXNOISE;
    for (i = 0; i < n; i++) {
        const ch_noise_t *prev = 0;
        for (j = 0; j < v->n_noise; j++)
            if (v->noise[j].src == list[i].src && v->noise[j].fwd == list[i].fwd) {
                prev = &v->noise[j];
                break;
            }
        next[i] = list[i];
        next[i].hp = prev ? prev->hp : 0;
        next[i].lp1 = prev ? prev->lp1 : 0;
        next[i].lp2 = prev ? prev->lp2 : 0;
        next[i].level = 0;
    }
    for (i = 0; i < n; i++)
        v->noise[i] = next[i];
    v->n_noise = (uint8_t)n;
}

CH_HOT static void flush_state(ch_voice_t *v)
{
    int k, j;
    for (k = 0; k < v->n; k++) {
        if (!v->fixed[k])
            v->p[k] = 0;
        v->wall_v[k] = 0;
        if (FABS(v->wall_y[k]) < R(1e-12))
            v->wall_y[k] = 0;
    }
    for (j = 0; j < v->nb; j++)
        v->u[j] = v->mom[j] = 0;
    for (j = 0; j < v->nr; j++)
        v->rad_ul[j] = 0;
}

CH_HOT static void flush_tiny(ch_voice_t *v)
{
    int k, j;
    for (k = 0; k < v->n; k++) {
        if (!v->fixed[k])
            tiny(&v->p[k]);
        tiny(&v->wall_v[k]);
        tiny(&v->wall_y[k]);
        tiny(&v->vol_src1[k]);
        tiny(&v->vol_src2[k]);
    }
    for (j = 0; j < v->nb; j++) {
        tiny(&v->u[j]);
        tiny(&v->mom[j]);
    }
    for (j = 0; j < v->nr; j++) {
        tiny(&v->rad_ul[j]);
        tiny(&v->rad_u[j]);
    }
    tiny(&v->rad_prev_total);
    for (j = 0; j < v->n_noise; j++) {
        tiny(&v->noise[j].hp);
        tiny(&v->noise[j].lp1);
        tiny(&v->noise[j].lp2);
        tiny(&v->noise[j].level);
    }
}

CH_HOT static void begin_block(ch_voice_t *v, int samples)
{
    ch_real inv = R(1.0) / mx(R(1.0), (ch_real)samples), max_p = 0;
    int k, j;
    for (k = 0; k < v->n; k++) {
        v->area_step[k] = (v->area_target[k] - v->area[k]) * inv;
        v->amin_step[k] = (v->amin_target[k] - v->amin[k]) * inv;
        v->dx_step[k] = (v->dx_target[k] - v->dx[k]) * inv;
        if (!v->fixed[k]) {
            ch_real ap = FABS(v->p[k]);
            if (ap > max_p)
                max_p = ap;
        }
    }
    if (max_p < R(1e-9)) {
        ch_real max_u = 0;
        for (j = 0; j < v->nb; j++)
            max_u = mx(max_u, FABS(v->u[j]));
        if (max_u < R(1e-9))
            flush_state(v);
    }
    for (j = 0; j < v->nb; j++)
        if (v->b_kind[j] == CH_PORT)
            v->port_area_step[j] = (v->port_area_target[j] - v->port_area[j]) * inv;
    flush_tiny(v);
}

/* the network part of HumanVoice::control() (airway.apply's targets, begin_block), then its tail */
CH_HOT static inline void block_common(ch_voice_t *v, const ch_block_t *b)
{
    int k, j;
    for (k = 0; k < v->n; k++) {
        v->area_target[k] = b->area_target[k];
        v->amin_target[k] = b->amin_target[k];
        v->dx_target[k] = b->dx_target[k];
    }
    for (j = 0; j < v->nb; j++)
        v->port_area_target[j] = b->port_area_target[j];
    set_noise_sources(v, b->noise, b->n_noise);
    begin_block(v, v->ctrl_every);
    glottis_flush_tiny(&v->glottis);
    tiny(&v->dc_x);
    tiny(&v->dc_y);
    tiny(&v->lim_env);
    v->glottis.p = b->glottis;
    v->p_lung = b->p_lung;
    if (v->p_lung == 0)
        tiny(&v->p_lung_now);
    v->p_lung_step = (v->p_lung - v->p_lung_now) / (ch_real)v->ctrl_every;
}

/* -------------------------------------------------------------- airway/network.rs: step --- */
CH_HOT static inline void compute_noise(ch_voice_t *v)
{
    ch_real scale = v->noise_scale * SQRT(R(96000.0) / v->fs);
    ch_real sat = R(12000.0) * R(12000.0) - RE_CRIT * RE_CRIT;
    int i;
    for (i = 0; i < v->n_noise; i++) {
        ch_noise_t *s = &v->noise[i];
        ch_real a = v->branch_area[s->src], u = v->u[s->src], amp = 0, w, hp_out, val;
        if (a > v->closed_area) {
            /* Re = v·d_h/ν with d_h = 2√(A/π) → Re = |U|·2/(ν·√(πA)) */
            ch_real re = (FABS(u) * R(2.0)) / (NU * SQRT(CH_PI * a));
            if (re > RE_CRIT) {
                ch_real x = re * re - RE_CRIT * RE_CRIT;
                amp = (s->gain * scale * x) / (R(1.0) + x / sat);
            }
        }
        /* white → high-pass (1st order) → 2nd-order low-pass */
        w = rng_bipolar(&v->rng) * R(1.732);
        s->hp += (R(1.0) - s->hp_coef) * (w - s->hp);
        hp_out = w - s->hp;
        s->lp1 += s->lp_coef * (hp_out - s->lp1);
        s->lp2 += s->lp_coef * (s->lp1 - s->lp2);
        s->level = amp;
        if (amp == 0)
            continue;
        val = amp * s->lp2;
        if (u >= 0)
            v->psrc[s->fwd] += val;
        else
            v->psrc[s->bwd] -= val;
    }
}

#endif
