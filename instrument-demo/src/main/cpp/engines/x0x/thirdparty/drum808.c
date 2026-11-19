/* SPDX-License-Identifier: GPL-3.0-only
 * Modified for FM-1 Instrument, 2026-11-19: mono voice stems and native hit controls.
 * 8W8 by A Thousand Details - https://github.com/athousanddetails/schwung-8W8
 * 8W8 copyright (C) 2026 A Thousand Details. */
/* The metal bank's kept step, its edge-free samples and bank_block: after Optimist (github.com/w0ts/optimist
 * e96d27bc, 9f71feec, GPL-3.0-only), cheaper per-sample code, the same samples (the comments marked Optimist). */
/* X0X 808 drum part: a C99 / float / no-libm port of 8W8 (see drum808.h).
 *
 * Every voice below is the 8W8 circuit class of the same name (sc808_*_circuit.h,
 * sc808_voices.h RimClave), statement for statement, with its constants. 8W8 runs
 * in double; the FM-1 has a single-precision FPU, so the float arithmetic here is
 * ARRANGED to follow the double result rather than merely approximate it:
 *
 *  - Resonators are TDF-II with delta-form denominators (drum808.h). Measured
 *    against 8W8: storing a1 = -2 cos(w) / a0 as a plain float biquad does puts
 *    the default kick 2.3e-3 off and the low tom at full decay 3.5e-2 off; the
 *    delta form is 1.3e-4 and 2.2e-4.
 *  - Decay multipliers a = exp(-1/(tau sr)) are 1 + expm1(), or literals rounded
 *    once from double: fm_expf's 6e-7 is 0.5 % of (1 - a) for a 0.2 s tau.
 *  - Pitches use a correctly rounded exp2 (float-float, set time only), so a tuned
 *    voice lands on the float 8W8's powf() gives; fm_powf is 1e-5.
 *  - The metal bank keeps 0.32 fixed-point phases (8W8: double); a float phase
 *    in [0, 1) would drift ~1e-7 of a cycle per sample (estimate, not measured),
 *    and the hats and cymbal live on the 10th-20th harmonics.
 *  - Linear discharges (cymbal shimmer, open hat) are start - n * step: repeated
 *    float subtraction of a small step rounds the same way every time (measured:
 *    the cymbal's envelope error goes from 0.002 dB to 0.019 dB).
 * tests/host/run_drum808.sh renders every sound against 8W8 itself. */
#include "drum808.h"
#include "fastmath.h"

#define SR 44100.0f
#define TWO_PI_SR 1.424758520e-4f              /* 2 pi / 44100 */
#define PI_SR 7.123792602e-5f                  /* pi / 44100 */
#define CHUNK 32

/* per-sample voice code; tools/target_obj_check.sh ... -DD8_TICK='static __attribute__((noinline))'
 * keeps each one a function of its own, so its instruction count can be read */
#ifndef D8_TICK
#define D8_TICK static inline
#endif

/* ---- fixed coefficients, exp() evaluated in double and rounded once ------- */
#define D8_BD_ATK_C 9.962278430e-01f           /* exp(-1/(6 ms sr)) */
#define D8_TOM_PITCH_C 9.994962217e-01f        /* exp(-1/(45 ms sr)) */
#define D8_TOM_SKIN_C 9.981121395e-01f         /* exp(-1/(12 ms sr)) */
#define D8_TOM_STRIKE_LPC 1.274096498e-02f     /* 1 - exp(-2 pi 90 / sr) */
#define D8_CP_TOOTH_D 9.949736221e-01f
#define D8_CP_MAIN_ATK 7.530084766e-03f
#define D8_MENV_UP 8.014310984e-01f
#define D8_OH_ATK_C 3.772156967e-03f
#define D8_CY_ATK_C 3.238866368e-04f
#define D8_CY_D2 9.998969338e-01f
#define D8_CY_D3 9.998920261e-01f
#define D8_CY_BODY_LPC 7.303897516e-01f
#define D8_CY_CRASH_LPC 4.580850379e-01f
#define D8_CY_FLOOR_LPC 7.021141105e-01f
#define D8_CY_TOP_LPC 8.639403658e-01f
#define D8_CB_CLANK_D 9.984894263e-01f
#define D8_HAT_LPC_OPEN 5.685516861e-01f
#define D8_HAT_LPC_CLOSED 7.759747481e-01f
#define D8_HP1_A_200 9.719069870e-01f
#define D8_HP1_A_5000 4.904758255e-01f
#define D8_HP1_A_2000 7.520505665e-01f
#define D8_HP1_A_1400 8.191679080e-01f

/* the pulse shaper (R162 4.7k, R163 100k, C40 15n) every circuit voice uses */
#define SH_A 7.140810433e-01f
#define SH_DC 4.489016237e-02f

/* ======================================================================= */
/* maths                                                                   */
/* ======================================================================= */

/* expm1 with full relative accuracy near 0, where 1 - exp(x) is what matters */
static float d8_expm1(float x)
{
    if (x > -0.5f && x < 0.5f) {
        float p = 1.0f / 362880.0f;
        p = p * x + 1.0f / 40320.0f;
        p = p * x + 1.0f / 5040.0f;
        p = p * x + 1.0f / 720.0f;
        p = p * x + 1.0f / 120.0f;
        p = p * x + 1.0f / 24.0f;
        p = p * x + 1.0f / 6.0f;
        p = p * x + 0.5f;
        p = p * x + 1.0f;
        return p * x;
    }
    return fm_expf(x) - 1.0f;
}

static float d8_exp(float x) { return (x > -0.5f && x < 0.5f) ? 1.0f + d8_expm1(x) : fm_expf(x); }

/* float-float (double-single) arithmetic, for set-time values that must match
 * a double or a correctly rounded libm result. Needs strict float evaluation and
 * no fused multiply-add (the FM-1 has none; the host builds -ffp-contract=off). */
typedef struct { float hi, lo; } d8ff_t;

static d8ff_t ff_qsum(float a, float b) { d8ff_t r; r.hi = a + b; r.lo = b - (r.hi - a); return r; }
static d8ff_t ff_tsum(float a, float b)
{
    d8ff_t r;
    float bb;
    r.hi = a + b;
    bb = r.hi - a;
    r.lo = (a - (r.hi - bb)) + (b - bb);
    return r;
}
static d8ff_t ff_tprod(float a, float b)
{
    d8ff_t r;
    float c, ah, al, bh, bl;
    r.hi = a * b;
    c = 4097.0f * a; ah = c - (c - a); al = a - ah;
    c = 4097.0f * b; bh = c - (c - b); bl = b - bh;
    r.lo = ((ah * bh - r.hi) + ah * bl + al * bh) + al * bl;
    return r;
}
static d8ff_t ff_mul(d8ff_t a, d8ff_t b)
{
    d8ff_t p = ff_tprod(a.hi, b.hi);
    p.lo += a.hi * b.lo + a.lo * b.hi;
    return ff_qsum(p.hi, p.lo);
}
static d8ff_t ff_add(d8ff_t a, d8ff_t b)
{
    d8ff_t s = ff_tsum(a.hi, b.hi);
    s.lo += a.lo + b.lo;
    return ff_qsum(s.hi, s.lo);
}
static d8ff_t ff_mulf(d8ff_t a, float b)
{
    d8ff_t p = ff_tprod(a.hi, b);
    p.lo += a.lo * b;
    return ff_qsum(p.hi, p.lo);
}

/* 1/k!, k = 0..11, and ln 2, each as hi + lo */
static const d8ff_t k_ifact[12] = {
    {1.000000000e+00f, 0.0f}, {1.000000000e+00f, 0.0f}, {5.000000000e-01f, 0.0f},
    {1.666666716e-01f, -4.967053879e-09f}, {4.166666791e-02f, -1.241763470e-09f},
    {8.333333768e-03f, -4.346172033e-10f}, {1.388888923e-03f, -3.363109444e-11f},
    {1.984127011e-04f, -2.725596875e-12f}, {2.480158764e-05f, -3.406996094e-13f},
    {2.755731884e-06f, 3.793571224e-14f}, {2.755731998e-07f, -7.575112209e-15f},
    {2.505210794e-08f, 4.417623045e-16f}};
static const d8ff_t k_ln2 = {6.931471825e-01f, -1.904654212e-09f};

/* 2^x, correctly rounded but for ties within ~1e-12: Taylor series of e^(f ln2)
 * in float-float. Set time only (~400 flops). */
static float d8_exp2_cr(float x)
{
    float n, f;
    d8ff_t r, s;
    fm_bits_t b;
    int k;
    if (x < -126.0f)
        return 0.0f;
    if (x > 127.0f)
        x = 127.0f;
    n = fm_floorf(x + 0.5f);
    f = x - n;                                    /* exact */
    r = ff_tprod(f, k_ln2.hi);
    r.lo += f * k_ln2.lo;
    r = ff_qsum(r.hi, r.lo);
    s = k_ifact[11];
    for (k = 10; k >= 0; k--)
        s = ff_add(ff_mul(s, r), k_ifact[k]);
    b.f = s.hi + s.lo;
    b.u += (uint32_t)(int32_t)n << 23;
    return b.f;
}

/* b^t for b > 0 (pot curves, set time) */
static float d8_pow(float b, float t) { return d8_exp2_cr(t * fm_log2f(b)); }

/* 8W8's midicps: 440 * powf(2, (note - 69) / 12), in float */
static float d8_midicps(float note) { return 440.0f * d8_exp2_cr((note - 69.0f) / 12.0f); }

/* an ff value (>= 0, < 2^32) rounded to an integer */
static uint32_t ff_to_u32(d8ff_t v)
{
    float fl = fm_floorf(v.hi);
    uint32_t u;
    float fr;
    if (v.hi >= 16777216.0f) {                    /* hi is already an integer */
        u = (uint32_t)v.hi;
        fr = v.lo;
    } else {
        u = (uint32_t)(int32_t)fl;
        fr = (v.hi - fl) + v.lo;
    }
    return u + (uint32_t)(int32_t)fm_floorf(fr + 0.5f);
}

/* floor(a * b) exactly, for positive a * b < 2^23 (EnvGen's segment length) */
static int32_t floor_mul(float a, float b)
{
    d8ff_t p = ff_tprod(a, b);
    float fl = fm_floorf(p.hi);
    int32_t r = (int32_t)fl;
    if (fl == p.hi && p.lo < 0.0f)
        r--;
    return r;
}

static float clampf(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }

/* ======================================================================= */
/* primitives                                                              */
/* ======================================================================= */

static void rng_seed(d8_rng_t *r, uint32_t s)
{
    r->s1 = 1243598713u ^ s; if (r->s1 < 2u) r->s1 = 1243598713u;
    r->s2 = 3093459404u ^ s; if (r->s2 < 8u) r->s2 = 3093459404u;
    r->s3 = 1821928721u ^ s; if (r->s3 < 16u) r->s3 = 1821928721u;
}

static inline float rng_frand2(d8_rng_t *r)
{
    fm_bits_t u;
    r->s1 = ((r->s1 & 0xFFFFFFFEu) << 12) ^ (((r->s1 << 13) ^ r->s1) >> 19);
    r->s2 = ((r->s2 & 0xFFFFFFF8u) << 4) ^ (((r->s2 << 2) ^ r->s2) >> 25);
    r->s3 = ((r->s3 & 0xFFFFFFF0u) << 17) ^ (((r->s3 << 3) ^ r->s3) >> 11);
    u.u = 0x40000000u | ((r->s1 ^ r->s2 ^ r->s3) >> 9);
    return u.f - 3.0f;
}

/* PulseShaper: high shelf, then a diode holding negative swings at one drop */
D8_TICK float shaper(float *st, float gate)
{
    float v;
    *st = gate + SH_A * (*st - gate);
    if (gate == 0.0f && fm_fabsf(*st) < 1e-20f)
        *st = 0.0f;                               /* 8W8 carries a -400 dB tail here */
    v = (gate - *st) + SH_DC * *st;
    if (v < 0.0f)
        v = 0.71f * d8_expm1(v > -20.0f ? v : -20.0f);
    return v;
}

static inline float opamp_clip(float v, float rail, float irail) { return rail * fm_tanhf(v * irail); }

/* BridgedT::set: constant-peak bandpass, clamps as 8W8 */
static void bp_set(d8_bp_t *f, float hz, float q)
{
    float w, s, sh, alpha, inv;
    hz = hz < 20.0f ? 20.0f : (hz > SR * 0.4f ? SR * 0.4f : hz);
    q = q < 0.3f ? 0.3f : (q > 60.0f ? 60.0f : q);
    w = TWO_PI_SR * hz;
    s = fm_sin_pi(w);
    sh = fm_sin_pi(0.5f * w);
    alpha = s / (2.0f * q);
    inv = 1.0f / (1.0f + alpha);
    f->b0 = alpha * inv;
    f->e1 = (2.0f * alpha + 4.0f * sh * sh) * inv;
    f->e2 = -2.0f * alpha * inv;
}

static inline float bp_run(d8_bp_t *f, float x)
{
    float bx = f->b0 * x;
    float y = bx + f->z1;
    f->z1 = (y + y + f->z2) - f->e1 * y;
    f->z2 = (-bx - y) - f->e2 * y;
    return y;
}

static inline float bq_run(d8_bq_t *f, float x)
{
    float y = f->n0 * x + f->z1;
    f->z1 = (f->n1 * x + (y + y + f->z2)) - f->e1 * y;
    f->z2 = (f->n2 * x - y) - f->e2 * y;
    return y;
}

/* SuperCollider HPF / LPF (2nd-order Butterworth, prewarped), coefficients only */
static void sc_hpf_set(d8_bq_t *f, float hz)
{
    float c = fm_tanf(PI_SR * hz), c2 = c * c, s2c = c * 1.41421356f;
    float a0 = 1.0f / (1.0f + s2c + c2);
    f->n0 = a0; f->n1 = -2.0f * a0; f->n2 = a0;
    f->e1 = 2.0f * a0 * (s2c + 2.0f * c2);
    f->e2 = -2.0f * s2c * a0;
}

static void sc_lpf_set(d8_bq_t *f, float hz)
{
    float c = 1.0f / fm_tanf(PI_SR * hz), s2c = c * 1.41421356f;
    float a0 = 1.0f / (1.0f + s2c + c * c);
    f->n0 = a0; f->n1 = 2.0f * a0; f->n2 = a0;
    f->e1 = 2.0f * a0 * (2.0f + s2c);
    f->e2 = -2.0f * s2c * a0;
}

/* SKHighpass: RBJ highpass with a gain */
static void sk_hp_set(d8_bq_t *f, float hz, float q, float g)
{
    float w = TWO_PI_SR * hz, s = fm_sin_pi(w), sh = fm_sin_pi(0.5f * w);
    float alpha = s / (2.0f * q), inv = 1.0f / (1.0f + alpha);
    float onepc = 2.0f - 2.0f * sh * sh;          /* 1 + cos w */
    f->n0 = g * onepc * 0.5f * inv;
    f->n1 = -g * onepc * inv;
    f->n2 = f->n0;
    f->e1 = (2.0f * alpha + 4.0f * sh * sh) * inv;
    f->e2 = -2.0f * alpha * inv;
}

/* SuperCollider BPeakEQ(freq, rq, db) */
static void sc_peak_set(d8_bq_t *f, float hz, float rq, float a)
{
    float w = TWO_PI_SR * hz, s = fm_sin_pi(w), sh = fm_sin_pi(0.5f * w);
    float alpha = s * 0.5f * rq, rz = 1.0f / (1.0f + alpha / a);
    f->n0 = (1.0f + alpha * a) * rz;
    f->n1 = -2.0f * rz * (1.0f - 2.0f * sh * sh);
    f->n2 = (1.0f - alpha * a) * rz;
    f->e1 = 2.0f * rz * (alpha / a + 2.0f * sh * sh);
    f->e2 = -2.0f * rz * alpha / a;
}

/* OnePoleHP: set() also clears the state, as 8W8's does */
static void hp1_set_a(d8_hp1_t *f, float a) { f->a = a; f->z = 0.0f; f->y = 0.0f; }
static void hp1_set(d8_hp1_t *f, float hz) { hp1_set_a(f, d8_exp(-TWO_PI_SR * hz)); }
static inline float hp1_run(d8_hp1_t *f, float x)
{
    f->y = f->a * (f->y + x - f->z);
    f->z = x;
    return f->y;
}

/* Werner's 3rd-order bandpass, coefficients from the component values (bilinear,
 * 44.1 kHz), evaluated in double: nb0..3, da1..3 */
static const float k_cy_bp1[7] = {-7.319914168e-01f, 7.136070782e-01f, 7.319914168e-01f, -7.136070782e-01f,
                                  -1.388193671e+00f, -1.157075547e-01f, 5.065353624e-01f};
static const float k_cy_bp2[7] = {-5.120760045e-01f, 4.815941287e-01f, 5.120760045e-01f, -4.815941287e-01f,
                                  -1.055101523e+00f, -5.886905868e-01f, 6.606506222e-01f};
static inline float bq3_run(d8_bq3_t *f, const float *c, float x)
{
    float y = c[0] * x + f->z[0];
    f->z[0] = c[1] * x - c[4] * y + f->z[1];
    f->z[1] = c[2] * x - c[5] * y + f->z[2];
    f->z[2] = c[3] * x - c[6] * y;
    return y;
}

/* ---- SuperCollider EnvGen.kr (3.11.2), as sc_ugens.h ---- */
#define CTRL_RATE 689.0625f                       /* 44100 / 64 */

static void env_arm(d8_env_t *e)
{
    float end, a1;
    if (e->seg >= e->count) {
        e->done = 1;
        return;
    }
    e->counter = floor_mul(e->dur[e->seg], CTRL_RATE);
    if (e->counter < 1)
        e->counter = 1;
    end = e->end[e->seg];
    if (e->counter == 1 || fm_fabsf(e->curve[e->seg]) < 0.001f) {
        e->linear = 1;
        e->grow = (end - e->level) / (float)e->counter;
    } else {
        e->linear = 0;
        a1 = (end - e->level) / (1.0f - d8_exp(e->curve[e->seg]));
        e->a2 = e->level + a1;
        e->b1 = a1;
        e->grow = d8_exp(e->curve[e->seg] / (float)e->counter);
    }
}

static float env_step(d8_env_t *e)
{
    if (e->done)
        return e->level;
    if (e->linear)
        e->level += e->grow;
    else {
        e->b1 *= e->grow;
        e->level = e->a2 - e->b1;
    }
    if (--e->counter <= 0) {
        e->seg++;
        env_arm(e);
    }
    return e->level;
}

D8_TICK float env_next(d8_env_t *e)
{
    float t;
    if (e->phase >= 64) {
        e->phase = 0;
        e->prev = e->cur;
        e->cur = env_step(e);
    }
    t = (float)e->phase * (1.0f / 64.0f);
    e->phase++;
    return e->prev + (e->cur - e->prev) * t;
}

/* ======================================================================= */
/* the voices                                                              */
/* ======================================================================= */

/* a voice ends after n samples below drum808_quiet (8W8: 3.2e-5, -90 dB); the engine's overload
 * guard raises it so that tails nobody hears in a dense mix stop costing CPU */
#ifndef D8_QUIET_INIT
#define D8_QUIET_INIT 3.2e-5f
#endif
float drum808_quiet = D8_QUIET_INIT;
#define QUIET(v, o, n)                                                    \
    do {                                                                  \
        if ((o) > drum808_quiet || (o) < -drum808_quiet) (v)->quiet = 0;  \
        else if (++(v)->quiet > (n)) (v)->active = 0;                     \
    } while (0)

/* ---- bass drum: sc808_bd_circuit.h ---- */
#define BD_Q 2.356f

static void bd_reset(d8_bd_t *v)
{
    v->bt.z1 = v->bt.z2 = 0.0f;
    v->shp = 0.0f;
    v->fb = 0.0f;
    v->gate = 0;
    v->atk_env = 0.0f;
    v->sigh_env = 0.0f;
    v->tone_z = 0.0f;
    v->dc_z = 0.0f;
    v->active = 0;
    v->quiet = 0;
    v->coef_age = 0;
    bp_set(&v->bt, v->f0, BD_Q);
}

static void bd_trigger(d8_bd_t *v, float hz, float decay_k, float tone, float attack, float accent_v)
{
    float w, raw, k, vr, t_hz;
    v->f0 = hz > 20.0f ? (hz < 400.0f ? hz : 400.0f) : 20.0f;
    /* forward gain 1 + R167 / (2 Reff), Reff = 1 / (w^2 R167 C41 C42) */
    w = 6.28318531f * v->f0;
    raw = 1.0f + w * w * 1.125e-4f;
    v->forward = raw;
    v->tune_trim = 1.210321549e+01f / raw;
    k = clampf(decay_k, 0.0f, 1.0f);
    vr = 500.0e3f * (k * 0.999f + 0.001f);
    v->loop_g = vr / (47.0e3f + vr);
    v->atk_amt = clampf(attack, 0.0f, 1.0f);
    v->accent_v = clampf(accent_v, 1.0f, 20.0f);
    t_hz = 300.0f * d8_pow(20.0f, clampf(tone, 0.0f, 1.0f));
    v->tone_c = -d8_expm1(-TWO_PI_SR * t_hz);
    v->gate = 44;
    v->atk_env = 1.0f;
    v->active = 1;
    v->quiet = 0;
    v->coef_age = 0;
}

D8_TICK float bd_tick(d8_bd_t *v)
{
    float gate = 0.0f, vplus, mag, x, y, click, out;
    if (v->gate > 0) {
        gate = v->accent_v;
        v->gate--;
    }
    vplus = shaper(&v->shp, gate);
    v->atk_env *= D8_BD_ATK_C;
    if (v->atk_env < 1e-6f)
        v->atk_env = 0.0f;
    mag = fm_fabsf(v->fb) * (1.0f / 1.35f);
    v->sigh_env += (mag - v->sigh_env) * 0.0016f;
    if (--v->coef_age <= 0) {
        float sigh = 1.0f + 0.16f * (v->sigh_env < 1.0f ? v->sigh_env : 1.0f);
        float lift = 1.0f + 2.0f * v->atk_amt * v->atk_env;
        v->coef_age = 16;
        bp_set(&v->bt, v->f0 * sigh * lift, BD_Q * lift);
    }
    x = vplus * v->forward + v->loop_g * opamp_clip(v->fb, 12.0f, 1.0f / 12.0f);
    y = bp_run(&v->bt, x);
    v->fb = y;
    click = vplus * v->atk_amt * 0.16f;
    v->tone_z += ((y + click) - v->tone_z) * v->tone_c;
    v->dc_z += (v->tone_z - v->dc_z) * 0.0009f;
    out = v->tone_z - v->dc_z;
    if (!(out > -50.0f && out < 50.0f)) {
        bd_reset(v);
        return 0.0f;
    }
    out *= 1.522f * v->tune_trim;
    QUIET(v, out, 400);
    return out;
}

/* ---- snare: sc808_sd_circuit.h ---- */
#define SD_F1 1.733336385e+02f
#define SD_F2 3.359763482e+02f
#define SD_Q1 1.736290840e+01f
#define SD_Q2 1.066003582e+01f
#define SD_G1 6.039411765e+02f
#define SD_G2 2.282727273e+02f
#define SD_DECAY_DEF 0.850393713f                 /* 108/127, as the float division */

static void sd_reset(d8_sd_t *v)
{
    v->bt1.z1 = v->bt1.z2 = v->bt2.z1 = v->bt2.z2 = 0.0f;
    v->shp = 0.0f;
    sc_hpf_set(&v->noise_hp, 1760.0f);
    v->noise_hp.z1 = v->noise_hp.z2 = 0.0f;
    sc_hpf_set(&v->out_hp, 30.0f);
    v->out_hp.z1 = v->out_hp.z2 = 0.0f;
    v->gate = 0;
    v->noise_env = 0.0f;
    v->active = 0;
    v->quiet = 0;
}

static void sd_trigger(d8_sd_t *v, float ratio, float decay01, float tone01, float snappy01, float accent_v)
{
    float r = ratio > 0.05f ? (ratio < 8.0f ? ratio : 8.0f) : 0.05f;
    float dq = 0.35f + 1.15f * clampf(decay01, 0.0f, 1.0f);
    float t = clampf(tone01, 0.0f, 1.0f) * (0.5f * FM_PI), dnorm;
    bp_set(&v->bt1, SD_F1 * r, SD_Q1 * dq);
    bp_set(&v->bt2, SD_F2 * r, SD_Q2 * dq);
    v->mix1 = fm_sin_pi(0.5f * FM_PI - t);
    v->mix2 = fm_sin_pi(t);
    v->accent_v = clampf(accent_v, 1.0f, 20.0f);
    v->noise_env = clampf(snappy01, 0.0f, 1.0f) * v->accent_v * 0.25f;
    dnorm = dq / (0.35f + 1.15f * SD_DECAY_DEF);
    v->noise_decay = d8_exp(-1.0f / (0.075f * dnorm * SR));
    v->gate = 44;
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float sd_tick(d8_sd_t *v)
{
    float gate = 0.0f, vplus, s1, s2, shells, n, vca, noise, out;
    if (v->gate > 0) {
        gate = v->accent_v;
        v->gate--;
    }
    vplus = shaper(&v->shp, gate);
    s1 = bp_run(&v->bt1, vplus * SD_G1);
    s2 = bp_run(&v->bt2, vplus * SD_G2);
    shells = opamp_clip(s1 * v->mix1 + s2 * v->mix2, 40.0f, 1.0f / 40.0f);
    v->noise_env *= v->noise_decay;
    n = rng_frand2(&v->rng);
    vca = (n > 0.0f ? n : 0.0f) * v->noise_env;
    noise = bq_run(&v->noise_hp, vca);
    out = shells * 0.1491f + noise * 1.4908f;
    out = bq_run(&v->out_hp, out);
    if (!(out > -50.0f && out < 50.0f)) {
        sd_reset(v);
        return 0.0f;
    }
    QUIET(v, out, 400);
    return out;
}

/* ---- tom / conga channel: sc808_tom_circuit.h ---- */
#define TOM_Q 1.081763729e+01f
#define TOM_LN100_Q_PI 1.585726294e+01f              /* ln(100) Q / pi */

static void tom_reset(d8_tom_t *v)
{
    v->bt.z1 = v->bt.z2 = 0.0f;
    v->skin_lp_z = v->strike_lp_z = 0.0f;
    v->fb = v->dc_z = 0.0f;
    v->gate = 0;
    v->active = 0;
    v->quiet = 0;
}

static void tom_trigger(d8_tom_t *v, int mode, float hz, float ring_s, float accent_v)
{
    float t, g, c2f;
    v->mode = (uint8_t)mode;
    v->f0 = hz < 20.0f ? 20.0f : (hz > SR * 0.25f ? SR * 0.25f : hz);
    t = ring_s > 0.02f ? ring_s : 0.02f;
    g = 1.0f - TOM_LN100_Q_PI / (v->f0 * t);
    if (g < 0.0f) g = 0.0f;
    if (g > 0.993f) g = 0.993f;
    v->loop_g = g;
    v->accent_v = accent_v;
    v->gate = 44;
    v->pitch_env = 1.0f;
    v->skin_env = mode == 0 ? 1.0f : 0.0f;
    c2f = -d8_expm1(-TWO_PI_SR * (v->f0 * 2.0f));
    v->skin_lp_c = c2f;
    v->strike_lp_c = mode == 0 ? D8_TOM_STRIKE_LPC : c2f;
    v->fwd = mode == 0 ? d8_pow(120.0f / v->f0, 0.224f) * 9.2f : d8_pow(120.0f / v->f0, 0.90f) * 6.7f;
    v->coef_age = 0;
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float tom_tick(d8_tom_t *v)
{
    float gate = 0.0f, strike, skin = 0.0f, x, y, out;
    if (v->gate > 0) {
        gate = v->accent_v;
        v->gate--;
    }
    strike = shaper(&v->shp, gate) * 4.0f * (v->mode == 0 ? 1.0f : 0.80f);
    v->strike_lp_z += (strike - v->strike_lp_z) * v->strike_lp_c;
    strike = v->strike_lp_z;
    if (v->skin_env > 1e-5f) {
        float n = rng_frand2(&v->rng) * v->skin_env * 0.095f * v->accent_v;
        v->skin_lp_z += (n - v->skin_lp_z) * v->skin_lp_c;
        skin = v->skin_lp_z;
        v->skin_env *= D8_TOM_SKIN_C;
    }
    v->pitch_env *= D8_TOM_PITCH_C;
    if (v->pitch_env < 1e-5f)
        v->pitch_env = 0.0f;
    if (--v->coef_age <= 0) {
        v->coef_age = 16;
        bp_set(&v->bt, v->f0 * (1.0f + (v->mode == 0 ? 0.085f : 0.03f) * v->pitch_env), TOM_Q);
    }
    x = strike + skin + v->loop_g * opamp_clip(v->fb, 12.0f, 1.0f / 12.0f);
    y = bp_run(&v->bt, x);
    v->fb = y;
    out = y * v->fwd;
    v->dc_z += (out - v->dc_z) * 0.0009f;
    out -= v->dc_z;
    if (!(out > -50.0f && out < 50.0f)) {
        tom_reset(v);
        return 0.0f;
    }
    QUIET(v, out, 400);
    return out;
}

/* ---- claves: sc808_rs_circuit.h ClaveCircuit ---- */
#define CL_F 2.526332590e+03f
#define CL_Q 1.431782106e+01f

static void cl_trigger(d8_clave_t *v, float ratio, float decay01, float accent_v)
{
    float r = ratio < 0.25f ? 0.25f : (ratio > 4.0f ? 4.0f : ratio);
    float dd = clampf(decay01, 0.0f, 1.0f);
    float stretch = d8_exp2_cr(2.0f * dd - 1.0f);  /* 4^(d - 0.5) */
    bp_set(&v->bt, CL_F * r, CL_Q);
    v->g = 1.0f - (1.0f - 0.74f) / stretch;
    if (v->g < 0.0f)
        v->g = 0.0f;
    v->accent_v = accent_v;
    v->gate = 44;
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float cl_tick(d8_clave_t *v)
{
    float gate = 0.0f, y0, y;
    if (v->gate > 0) {
        gate = v->accent_v;
        v->gate--;
    }
    y0 = bp_run(&v->bt, shaper(&v->shp, gate) + v->g * opamp_clip(v->fb, 12.0f, 1.0f / 12.0f));
    v->fb = y0;
    y = y0 * 0.55f;
    if (!(y > -50.0f && y < 50.0f)) {
        v->bt.z1 = v->bt.z2 = 0.0f;
        v->fb = 0.0f;
        v->gate = 0;
        v->active = 0;
        v->quiet = 0;
        return 0.0f;
    }
    QUIET(v, y, 300);
    return y;
}

/* ---- maracas: sc808_rs_circuit.h MaracasCircuit ---- */
#define MA_ATK_DEF 0.251968503f                   /* 32/127, as the float division */

static void ma_tune(d8_ma_t *v, float ratio)
{
    float r = ratio < 0.25f ? 0.25f : (ratio > 4.0f ? 4.0f : ratio);
    hp1_set(&v->hpa, 6300.0f * r > 15000.0f ? 15000.0f : 6300.0f * r);
    hp1_set(&v->hpb, 4600.0f * r > 12000.0f ? 12000.0f : 4600.0f * r);
}

static void ma_trigger(d8_ma_t *v, float ratio, float decay_s, float attack01, float accent_v)
{
    float t = decay_s > 0.008f ? decay_s : 0.008f;
    float a = clampf(attack01, 0.0f, 1.0f) / MA_ATK_DEF;
    float atk = 0.0012f * a * a;
    ma_tune(v, ratio);
    v->level = 0.0f;
    if (atk < 2.0e-4f)
        atk = 2.0e-4f;
    v->atk_c = d8_exp(-1.0f / (atk * SR));
    v->dec_c = d8_exp(-1.0f / ((t * 0.85f / 4.605170186f) * SR));
    v->peak = accent_v / 8.0f;
    v->rising = 1;
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float ma_tick(d8_ma_t *v)
{
    float n, y;
    if (v->rising) {
        v->level = v->peak + (v->level - v->peak) * v->atk_c;
        if (v->level > v->peak * 0.99f)
            v->rising = 0;
    } else
        v->level *= v->dec_c;
    n = rng_frand2(&v->rng);
    y = hp1_run(&v->hpb, hp1_run(&v->hpa, n)) * v->level * 1.9f;
    QUIET(v, y, 300);
    return y;
}

/* ---- rim shot: sc808_voices.h RimClave, mode 0 (the one sc808 voice) ---- */
static const d8ff_t k_pul_inc = {3.006253711e+04f, -2.567672927e-04f};   /* 2^(-22/12) 1.1 2^32 / sr */
#define PUL_DUTY 3435973837u                      /* phase < 0.8 */

static void rs_trigger(d8_rim_t *v, float hz, float decay, float hpf, float lpf)
{
    d8_env_t *e = &v->env;
    e->start = 1.0f;
    e->count = 2;
    e->end[0] = 1.0f; e->dur[0] = 0.00272f; e->curve[0] = -42.0f;
    e->end[1] = 0.0f; e->dur[1] = decay;    e->curve[1] = -42.0f;
    e->seg = 0;                                   /* Env::rewind */
    e->level = e->start;
    e->done = 0;
    env_arm(e);
    e->prev = e->cur = e->start;
    e->phase = 64;
    v->tri_inc = (4.0f / SR) * (hz * 1.1f);
    v->pul_inc = ff_to_u32(ff_mulf(k_pul_inc, hz));
    v->tri_ph = 1.0f;
    v->pul_ph = 0;
    v->pul_wrap = 0;
    sc_hpf_set(&v->hp, hpf);
    sc_lpf_set(&v->lp, lpf);
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float rs_tick(d8_rim_t *v)
{
    float env, tri, pul, sig;
    uint32_t nph;
    env = env_next(&v->env);
    tri = v->tri_ph > 1.0f ? 2.0f - v->tri_ph : v->tri_ph;           /* LFTri */
    v->tri_ph += v->tri_inc;
    if (v->tri_ph >= 3.0f)
        v->tri_ph -= 4.0f;
    if (v->pul_wrap) {                                                /* LFPulse, duty 0.8 */
        v->pul_wrap = 0;
        pul = 0.0f;
    } else
        pul = v->pul_ph < PUL_DUTY ? 1.0f : 0.0f;
    nph = v->pul_ph + v->pul_inc;
    if (nph < v->pul_ph)
        v->pul_wrap = 1;
    v->pul_ph = nph;
    sig = bq_run(&v->peak, tri * env + pul * env + rng_frand2(&v->rng) * env * 0.46f);
    sig = bq_run(&v->lp, bq_run(&v->hp, sig));
    if (v->env.done)
        QUIET(v, sig, 200);
    return sig;
}

/* ---- hand clap: sc808_cp_circuit.h ---- */
#define CP_F 8.743325448e+02f
#define CP_Q 1.290994449e+00f

static void cp_trigger(d8_cp_t *v, float ratio, float decay)
{
    float dd = decay < 0.05f ? 0.05f : (decay > 4.0f ? 4.0f : decay);
    bp_set(&v->bp, CP_F * ratio, CP_Q);
    v->main_d = d8_exp(-1.0f / (0.0385f * dd * SR));
    v->floor_d = d8_exp(-1.0f / (0.330f * dd * SR));
    v->tooth_d = D8_CP_TOOTH_D;
    v->spread = 441;                              /* (int)(0.010 sr + 0.5) */
    v->tooth = 0.80f;
    v->fired = 1;
    v->age = 0;
    v->main_level = 0.0f;
    v->main_target = 0.0f;
    v->floor_level = 0.0f;
    v->main_open = 1234;                          /* (int)(2.8 spread) */
    v->main_atk = D8_CP_MAIN_ATK;
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float cp_tick(d8_cp_t *v)
{
    float ctrl, n, y;
    if (v->fired < 3 && v->age >= v->fired * v->spread) {
        v->tooth = 0.80f;
        v->fired++;
    }
    if (v->age == v->main_open) {
        v->main_target = 1.0f;
        v->floor_level = 0.030f;
    }
    v->age++;
    ctrl = v->tooth + v->main_level + v->floor_level;
    v->tooth *= v->tooth_d;
    if (v->main_target > 0.0f) {
        v->main_level += (v->main_target - v->main_level) * v->main_atk;
        if (v->main_level > 0.98f)
            v->main_target = 0.0f;
    } else
        v->main_level *= v->main_d;
    v->floor_level *= v->floor_d;
    n = rng_frand2(&v->rng);
    y = bp_run(&v->bp, (n > 0.0f ? n : 0.0f) * ctrl) * 0.62f;
    y = bq_run(&v->hp, y);
    QUIET(v, y, 400);
    return y;
}

/* ---- the metal bank: sc808_mt_circuit.h SchmittBank ---- */
static const d8ff_t k_bank_inc[6] = {             /* oscHz / sr * 2^32 */
    {2.007239800e+07f, 1.792653054e-01f}, {3.585956800e+07f, 2.173968256e-01f},
    {2.979207400e+07f, 7.357460260e-01f}, {5.074099600e+07f, 8.529705405e-01f},
    {7.821515200e+07f, 7.305577993e-01f}, {5.275700000e+07f, 1.910276651e+00f}};
#define BANK_DUTY 2060725309u                     /* phase < 0.4798 */

/* Optimist: oscillator i's step, inc (1 + drift)(1 + jm1) rounded; the tick keeps it in dt[i] and works it out again
 * only when inc, drift or jm1 change (a ratio, every 256 samples, a wrap): the same value every sample */
static inline uint32_t bank_dt(const d8_bank_t *b, int i)
{
    float fm1 = b->drift[i] + b->jm1[i] + b->drift[i] * b->jm1[i];
    float p = (float)b->inc[i] * fm1;
    return b->inc[i] + (uint32_t)(int32_t)(p + (p >= 0.0f ? 0.5f : -0.5f));
}

static void bank_ratio(d8_bank_t *b, float r)
{
    int i;
    r = r < 0.25f ? 0.25f : (r > 4.0f ? 4.0f : r);
    for (i = 0; i < 6; i++) {
        b->inc[i] = ff_to_u32(ff_mulf(k_bank_inc[i], r));
        b->dt[i] = bank_dt(b, i);
    }
}

/* the two-sample polyBLEP edge correction, on fixed-point phases */
static inline float blep_fix(uint32_t t, uint32_t dt)
{
    float x;
    uint32_t r;
    if (t < dt) {
        x = (float)t / (float)dt;
        return x + x - x * x - 1.0f;
    }
    r = 0u - t;
    if (t != 0u && r < dt) {
        x = -(float)r / (float)dt;
        return x * x + x + x + 1.0f;
    }
    return 0.0f;
}

#ifdef D8_BANK_CHECK
/* the bank sample by sample, as 8W8 runs it: bank_block's reference (tests/host/drum808_bank_test.c) */
D8_TICK float bank_tick(d8_bank_t *b)
{
    float sum = 0.0f;
    int i;
    if (--b->drift_cnt <= 0) {
        b->drift_cnt = 256;
        for (i = 0; i < 6; i++) {
            b->drift[i] += 2.5e-4f * rng_frand2(&b->rng);
            b->drift[i] *= 0.98f;
            b->dt[i] = bank_dt(b, i);
        }
    }
    for (i = 0; i < 6; i++) {
        const uint32_t dt = b->dt[i], old = b->ph[i], ph = old + dt, pd = ph - BANK_DUTY;
        b->ph[i] = ph;
        if (ph < old || b->pend_wrap) {
            if (ph < old || (b->pend_wrap & (1u << i))) {
                b->pend_wrap &= (uint8_t)~(1u << i);
                b->jm1[i] = 0.0052f * rng_frand2(&b->rng);
                b->dt[i] = bank_dt(b, i);        /* (from the next sample) */
            }
        }
        /* Optimist: away from an edge (most samples) v is +-1 and both corrections are 0: +-2.5 exactly */
        if (ph < dt || 0u - ph < dt || pd < dt || 0u - pd < dt) {
            float v = ph < BANK_DUTY ? 1.0f : -1.0f;
            v += blep_fix(ph, dt);
            v -= blep_fix(pd, dt);
            sum += v * 2.5f;
        } else
            sum += ph < BANK_DUTY ? 2.5f : -2.5f;
    }
    return sum * (1.0f / 21.0f);
}
#endif

/* Optimist: bank_tick over a block, square by square. Each square runs on (its phase and step in registers) up to
 * its next wrap, where 8W8 draws its jitter; the draws are made in bank_tick's order (by sample, then square; the
 * drift's six before the squares of its sample), so every square gets the same numbers on the same samples. The
 * squares' parts are summed per sample in bank_tick's order: the same bus. pair: the CB pair, (o5 + o6) / 2. */
#define BANK_NONE 0x7FFF
#define BANK_SUB 16                               /* bank_block's pieces: its buffers on the second core's stack */
typedef struct { float c[6][BANK_SUB]; float sq[2][BANK_SUB]; } d8_bank_buf_t;

/* square i, samples [from, lim) on its present step: its part into c, and returns the sample it wraps on (its
 * jitter is due after it), or BANK_NONE */
static int bank_run(d8_bank_t *b, int i, int from, int lim, d8_bank_buf_t *w)
{
    const uint32_t dt = b->dt[i], dt2 = dt + dt;
    uint32_t ph = b->ph[i];
    float *c = w->c[i], *sq = i >= 4 ? w->sq[i - 4] : 0;
    int k = from, ev = BANK_NONE, pend = (b->pend_wrap >> i) & 1u;
    while (k < lim) {
        const uint32_t old = ph;
        uint32_t pd;
        ph = old + dt;
        pd = ph - BANK_DUTY;
        if (ph + dt < dt2 || pd + dt < dt2 || pend) {   /* within a step of an edge (a superset of blep_fix's != 0) */
            float v = ph < BANK_DUTY ? 1.0f : -1.0f;
            v += blep_fix(ph, dt);
            v -= blep_fix(pd, dt);
            c[k] = v * 2.5f;
            if (sq)
                sq[k] = ph < BANK_DUTY ? 2.5f : -2.5f;
            if (ph < old || pend) {               /* (a wrap is always near an edge) */
                ev = k;
                break;
            }
            k++;
        } else {
            /* a run of samples away from the edges: +-2.5 until the step that comes within dt of the next edge
             * e (the duty edge, or the wrap at 2^32); ph + r dt < e - dt for the r samples. dt > 0: inc >= 5e6
             * (k_bank_inc x 0.25), 1 + drift and 1 + jm1 near 1 */
            const uint32_t e = ph < BANK_DUTY ? BANK_DUTY : 0u;
            const float s = ph < BANK_DUTY ? 2.5f : -2.5f;
            uint32_t r = (e - dt - ph - 1u) / dt + 1u;
            int j;
            if (r > (uint32_t)(lim - k))
                r = (uint32_t)(lim - k);
            for (j = 0; j < (int)r; j++)
                c[k + j] = s;
            if (sq)
                for (j = 0; j < (int)r; j++)
                    sq[k + j] = s;
            ph = old + r * dt;
            k += (int)r;
        }
    }
    b->ph[i] = ph;
    return ev;
}

static void bank_sub(d8_bank_t *b, float *bus, float *pair, int n)   /* n <= BANK_SUB */
{
    d8_bank_buf_t w;
    int ev[6], i, k, sd = b->drift_cnt - 1;
    if (sd < 0)
        sd = 0;
    if (sd >= n)
        sd = BANK_NONE;                           /* no drift step in this block */
    for (i = 0; i < 6; i++)
        ev[i] = bank_run(b, i, 0, sd < n ? sd : n, &w);
    for (;;) {
        int j = -1;
        for (i = 0; i < 6; i++)                   /* the earliest jitter due (by sample, then square) */
            if (ev[i] != BANK_NONE && (j < 0 || ev[i] < ev[j]))
                j = i;
        if (j >= 0) {
            b->pend_wrap &= (uint8_t)~(1u << j);
            b->jm1[j] = 0.0052f * rng_frand2(&b->rng);
            b->dt[j] = bank_dt(b, j);
            ev[j] = bank_run(b, j, ev[j] + 1, sd < n ? sd : n, &w);
            continue;
        }
        if (sd >= n)
            break;
        for (i = 0; i < 6; i++) {                 /* the drift, at the start of sample sd */
            b->drift[i] += 2.5e-4f * rng_frand2(&b->rng);
            b->drift[i] *= 0.98f;
            b->dt[i] = bank_dt(b, i);
        }
        for (i = 0; i < 6; i++)
            ev[i] = bank_run(b, i, sd, n, &w);
        b->drift_cnt = 256 + 1 + sd;              /* (less the n below: 256 - (n - 1 - sd)) */
        sd = BANK_NONE;
    }
    b->drift_cnt -= n;
    for (k = 0; k < n; k++) {
        float sum = 0.0f;
        for (i = 0; i < 6; i++)
            sum += w.c[i][k];
        bus[k] = sum * (1.0f / 21.0f);
        pair[k] = (w.sq[0][k] + w.sq[1][k]) * 0.5f;
    }
}
/* in pieces of BANK_SUB (the squares' phases and steps, the drift's count and the pending wraps carry on from one to
 * the next as from one sample to the next: the same output). X0X: half the buffers Optimist's one pass of CHUNK needs
 * (1 KB on the 2.8 KB stack of the second core, where the 808 renders) */
static void bank_block(d8_bank_t *b, float *bus, float *pair, int n)
{
    int k, m;
    for (k = 0; k < n; k += m) {
        m = n - k < BANK_SUB ? n - k : BANK_SUB;
        bank_sub(b, bus + k, pair + k, m);
    }
}

/* ---- metal envelope ---- */
static inline float menv_tick(d8_menv_t *e)
{
    if (e->target > 0.0f) {
        e->v = e->target + (e->v - e->target) * e->up;
        if (e->v >= e->target * 0.995f)
            e->target = 0.0f;
    } else
        e->v *= e->down;
    return e->v;
}

#define DIODE_VON 0.5899f
static inline float knee_gate(float e)            /* the swing VCA's diode, knee 1.2 */
{
    float over = e - DIODE_VON;
    if (over <= 0.0f)
        return 0.0f;
    if (over < 1.2f)
        return over * over * (1.0f / 2.4f);
    return over - 0.6f;
}

/* ---- cymbal: sc808_mt_circuit.h CymbalCircuit ---- */
static void cy_init(d8_cy_t *v)
{
    bp_set(&v->sus1, 2944.0f, 75.0f);
    bp_set(&v->sus2, 3265.0f, 32.0f);
    bp_set(&v->body_res, 7150.0f, 2.6f);
    sk_hp_set(&v->body_sk, 7300.0f, 1.1f, 1.0f);
    sk_hp_set(&v->top_hp, 13500.0f, 1.2f, 1.0f);
    sk_hp_set(&v->out_hp, 1500.0f, 0.8f, 1.0f);
    hp1_set_a(&v->body_hpa, D8_HP1_A_5000);
    hp1_set_a(&v->crash_hp, D8_HP1_A_2000);
    hp1_set_a(&v->dc, D8_HP1_A_200);
    v->d1 = v->d2 = v->d3 = 1.0f;
}

static void cy_ratio(d8_cy_t *v, float r)
{
    r = r < 0.25f ? 0.25f : (r > 4.0f ? 4.0f : r);
    bp_set(&v->sus1, 2944.0f * r / 1.0055f, 75.0f);
    bp_set(&v->sus2, 3265.0f * r / 1.0055f, 32.0f);
}

static void cy_trigger(d8_cy_t *v, float decay_s, float accent_v)
{
    float a = accent_v / 8.0f;
    float t = decay_s > 0.1f ? decay_s : 0.1f;
    v->e1t = 8.0f * a;
    v->atk_c = D8_CY_ATK_C;
    v->d1 = (8.0f * a) / (t * SR);
    v->e1_ramp = 0;
    v->e2 = 8.0f * a; v->d2 = D8_CY_D2;
    v->e3 = 8.0f * a; v->d3 = D8_CY_D3;
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float cy_tick(d8_cy_t *v, float bus)
{
    float b1, b2, g1, g2, g3, sus, body_pre, body_dk, body, top_in, top, c1, y;
    b1 = opamp_clip(bq3_run(&v->bp1, k_cy_bp1, bus), 14.0f, 1.0f / 14.0f);
    b2 = opamp_clip(bq3_run(&v->bp2, k_cy_bp2, bus), 14.0f, 1.0f / 14.0f);
    if (v->e1t > 0.0f) {
        v->e1 += (v->e1t - v->e1) * v->atk_c;
        if (v->e1 > v->e1t * 0.99f)
            v->e1t = 0.0f;
    } else {                                      /* linear discharge, as start - n step */
        if (!v->e1_ramp) {
            v->e1_ramp = 1;
            v->e1_base = v->e1;
            v->e1_n = 0;
        }
        if (v->e1 > 0.0f) {
            v->e1 = v->e1_base - (float)(++v->e1_n) * v->d1;
            if (v->e1 < 0.0f)
                v->e1 = 0.0f;
        }
    }
    v->e2 *= v->d2;
    v->e3 *= v->d3;
    g1 = knee_gate(v->e1);
    g2 = knee_gate(v->e2);
    g3 = knee_gate(v->e3);
    sus = (1.4f * bp_run(&v->sus1, b1) + bp_run(&v->sus2, b1)) * g1;
    body_pre = bq_run(&v->body_sk, hp1_run(&v->body_hpa, b2));
    body_dk = bp_run(&v->body_res, body_pre);
    v->body_lp[0] += (body_dk - v->body_lp[0]) * D8_CY_BODY_LPC; body_dk = v->body_lp[0];
    v->body_lp[1] += (body_dk - v->body_lp[1]) * D8_CY_BODY_LPC; body_dk = v->body_lp[1];
    v->floor_lp[0] += (body_pre - v->floor_lp[0]) * D8_CY_FLOOR_LPC;
    v->floor_lp[1] += (v->floor_lp[0] - v->floor_lp[1]) * D8_CY_FLOOR_LPC;
    body = body_dk * g2 + v->floor_lp[1] * (0.065f * g1);
    top_in = bq_run(&v->top_hp, body_pre);
    v->top_lp += (top_in - v->top_lp) * D8_CY_TOP_LPC;
    top = v->top_lp * (g3 + 0.005f * g1);
    c1 = hp1_run(&v->crash_hp, b1);
    v->crash_lp[0] += (c1 - v->crash_lp[0]) * D8_CY_CRASH_LPC; c1 = v->crash_lp[0];
    v->crash_lp[1] += (c1 - v->crash_lp[1]) * D8_CY_CRASH_LPC; c1 = v->crash_lp[1];
    y = sus * 0.042f + body * 1.00f + top * 0.58f + c1 * (0.016f * g2);
    y = hp1_run(&v->dc, bq_run(&v->out_hp, y * 0.74f));
    if (y > 3.2e-5f || y < -3.2e-5f)
        v->quiet = 0;
    else if (++v->quiet > 400 && v->e1 < DIODE_VON * 0.5f && v->e2 < DIODE_VON && v->e3 < DIODE_VON)
        v->active = 0;
    return y;
}

/* ---- hi-hats: sc808_mt_circuit.h HatCircuit ---- */
static void hat_init(d8_hat_t *v, int mode)
{
    v->mode = (uint8_t)mode;
    if (mode == 0) {
        sk_hp_set(&v->ska, 5200.0f, 1.6f, 1.0f);
        sk_hp_set(&v->skb, 2700.0f, 0.75f, 1.0f);
    } else {
        sk_hp_set(&v->ska, 6200.0f, 0.9f, 1.0f);
        sk_hp_set(&v->skb, 5200.0f, 0.8f, 1.0f);
    }
    v->lp_c = mode == 1 ? D8_HAT_LPC_OPEN : D8_HAT_LPC_CLOSED;
    v->env.up = D8_MENV_UP;
    v->env.down = 1.0f;
    hp1_set_a(&v->dc, D8_HP1_A_200);
    v->out_scale = mode == 1 ? 5.0f : 8.0f;
}

static void hat_trigger(d8_hat_t *v, float decay_s, float accent_v)
{
    float a = accent_v / 8.0f;
    float t = decay_s > 0.02f ? decay_s : 0.02f;
    if (v->mode == 1) {
        v->lin_target = 8.0f * a;
        v->lin_level = 0.0f;
        v->lin_atk_c = D8_OH_ATK_C;
        v->lin_hold = (int32_t)(0.30f * t * SR);
        v->lin_step = v->lin_target / (0.70f * t * SR);
        v->ramp = 0;
        v->linear = 1;
    } else {
        v->env.target = 8.0f * a;
        v->env.down = d8_exp(-1.0f / ((t / 2.0f) * SR));
        v->linear = 0;
    }
    v->active = 1;
    v->quiet = 0;
}

static void hat_choke(d8_hat_t *v)
{
    v->env.v = 0.0f;
    v->env.target = 0.0f;
    v->lin_level = 0.0f;
    v->lin_base = 0.0f;
    v->lin_n = 0;
}

D8_TICK float hat_tick(d8_hat_t *v, float bus)
{
    float t1 = bus - v->d1, hp, e, sv;
    int k, np;
    v->d1 = bus;
    hp = (t1 - v->d2) * 30.0f;
    v->d2 = t1;
    if (v->linear) {
        e = v->lin_level;
        if (v->lin_target > 0.0f) {
            v->lin_level += (v->lin_target - v->lin_level) * v->lin_atk_c;
            if (v->lin_level > v->lin_target * 0.99f)
                v->lin_target = 0.0f;
        } else if (v->lin_hold > 0)
            v->lin_hold--;
        else {                                    /* C62's discharge, as start - n step */
            if (!v->ramp) {
                v->ramp = 1;
                v->lin_base = v->lin_level;
                v->lin_n = 0;
            }
            if (v->lin_level > 0.0f) {
                v->lin_level = v->lin_base - (float)(++v->lin_n) * v->lin_step;
                if (v->lin_level < 0.0f)
                    v->lin_level = 0.0f;
            }
        }
    } else
        e = menv_tick(&v->env);
    sv = bq_run(&v->skb, bq_run(&v->ska, hp * knee_gate(e)));
    np = v->mode == 1 ? 5 : 1;
    for (k = 0; k < np; k++) {
        v->lp[k] += (sv - v->lp[k]) * v->lp_c;
        sv = v->lp[k];
    }
    sv = hp1_run(&v->dc, sv * v->out_scale);
    if (sv > 3.2e-5f || sv < -3.2e-5f)
        v->quiet = 0;
    else if (++v->quiet > 400 &&
             (v->linear ? v->lin_level <= 0.0f : (v->env.target <= 0.0f && v->env.v < DIODE_VON * 0.5f)))
        v->active = 0;
    return sv;
}

/* ---- cowbell: sc808_mt_circuit.h CowbellCircuit ---- */
static void cb_init(d8_cb_t *v)
{
    bp_set(&v->bp, 812.0f, 5.5f);
    hp1_set_a(&v->hp, D8_HP1_A_200);
    hp1_set_a(&v->bla, D8_HP1_A_1400);
    hp1_set_a(&v->blb, D8_HP1_A_1400);
    v->clank_d = v->body_d = 1.0f;
    v->ratio = 1.0f;
}

static void cb_ratio(d8_cb_t *v, float r)
{
    r = r < 0.25f ? 0.25f : (r > 4.0f ? 4.0f : r);
    if (r != v->ratio) {
        bp_set(&v->bp, 812.0f * r, 5.5f);
        v->ratio = r;
    }
}

static void cb_trigger(d8_cb_t *v, float decay_s, float accent_v)
{
    float a = accent_v / 8.0f;
    float t = decay_s > 0.05f ? decay_s : 0.05f;
    v->clank = 0.77f * a;
    v->body = 0.23f * a;
    v->clank_d = D8_CB_CLANK_D;
    v->body_d = d8_exp(-1.0f / (0.140f * (t / 0.43f) * SR));
    v->active = 1;
    v->quiet = 0;
}

D8_TICK float cb_tick(d8_cb_t *v, float pair)  /* pair = (o5 + o6) / 2 */
{
    float x = bp_run(&v->bp, pair), env = v->clank + v->body, sv, y;
    v->clank *= v->clank_d;
    v->body *= v->body_d;
    sv = (x + 0.55f * hp1_run(&v->blb, hp1_run(&v->bla, pair))) * env;
    sv = sv + 2.0f * sv * sv;
    y = hp1_run(&v->hp, sv * 1.9f);
    if (y > 3.2e-5f || y < -3.2e-5f)
        v->quiet = 0;
    else if (++v->quiet > 400 && env < 1e-4f)
        v->active = 0;
    return y;
}

/* ======================================================================= */
/* Center settings and decay ranges from 8W8.                              */
/* ======================================================================= */

typedef struct { float min, max; uint8_t exponential, center; } d8_decay_t;
static const d8_decay_t k_decay[D8S_NUM] = {
    [D8S_BD] = {0.1f, 8.0f, 1, 87},
    [D8S_SD] = {0.1f, 8.0f, 1, 108},
    [D8S_LT] = {0.06f, 2.0f, 1, 68},
    [D8S_MT] = {0.06f, 2.0f, 1, 56},
    [D8S_HT] = {0.06f, 2.0f, 1, 53},
    [D8S_LC] = {0.06f, 2.0f, 1, 58},
    [D8S_MC] = {0.06f, 2.0f, 1, 36},
    [D8S_HC] = {0.06f, 2.0f, 1, 34},
    [D8S_RS] = {0.005f, 0.2f, 1, 40},
    [D8S_CL] = {0.0f, 1.0f, 0, 64},
    [D8S_MA] = {0.01f, 0.3f, 1, 51},
    [D8S_CP] = {0.35f, 2.8f, 1, 64},
    [D8S_CB] = {0.1f, 2.0f, 1, 62},
    [D8S_CH] = {0.03f, 0.5f, 1, 47},
    [D8S_OH] = {0.08f, 1.5f, 1, 74},
    [D8S_CY] = {0.4f, 6.0f, 1, 70},
};
static const uint8_t k_snap[D8S_NUM] = { [D8S_BD] = 24, [D8S_SD] = 89, [D8S_MA] = 32 };

/* the lanes 8W8 balanced the kit with, applied before the drive stage */
static const float k_trim[D8S_NUM] = {
    0.2964f, 0.1953f, 0.4110f, 0.4199f, 0.3917f, 0.3750f, 0.3899f, 0.3843f,
    0.2643f, 2.6005f, 0.6142f, 3.9640f, 0.1219f, 0.0021f, 0.0402f, 5.9505f};

/* base notes (sc808_engine.cpp kBaseNote) for the note-tuned sounds */
static const float k_base_note[D8S_NUM] = {
    34.0f, 65.0f, 42.02f, 48.58f, 54.83f, 55.88f, 62.49f, 67.55f, 91.62f, 99.2f, 113.0f, 71.0f};

#define SC808_FULL_VELOCITY_GAIN 1.9921260f

static float decay_value(int s, int pot)
{
    const d8_decay_t *p = &k_decay[s];
    const float t = (float)pot / 127.0f;
    if (p->exponential)
        return p->min * d8_pow(p->max / p->min, t);
    return p->min + (p->max - p->min) * t;
}

/* the sound a track plays, by its switch */
static int track_sound(const drum808_t *d, int t)
{
    static const uint8_t k_snd[D8_NUM][2] = {
        {D8S_BD, D8S_BD}, {D8S_SD, D8S_SD}, {D8S_LT, D8S_LC}, {D8S_MT, D8S_MC}, {D8S_HT, D8S_HC},
        {D8S_RS, D8S_CL}, {D8S_CP, D8S_MA}, {D8S_CB, D8S_CB}, {D8S_CY, D8S_CY}, {D8S_OH, D8S_OH},
        {D8S_CH, D8S_CH}};
    return k_snd[t][d->sw[t] & 1];
}

static int d8_scale_pot(int base, int value)
{
    return value <= 64 ? base * value / 64 : base + (127 - base) * (value - 64) / 63;
}

/* ======================================================================= */
/* the engine                                                              */
/* ======================================================================= */

enum { L_BD, L_SD, L_T0, L_T1, L_T2, L_RS, L_CL, L_MA, L_CP, L_CB, L_CH, L_OH, L_CY, L_NUM };

void drum808_init(drum808_t *d)
{
    int k;
    {
        uint8_t *z = (uint8_t *)d;                /* no libc headers on the target */
        unsigned i;
        for (i = 0; i < sizeof *d; i++)
            z[i] = 0;
    }
    rng_seed(&d->sd.rng, 0x808D51Eu);
    sd_reset(&d->sd);
    bd_reset(&d->bd);
    for (k = 0; k < 3; k++) {                     /* one channel per track, tom-side seed */
        rng_seed(&d->tom[k].rng, 0x808704Du);
        tom_reset(&d->tom[k]);
    }
    rng_seed(&d->rs.rng, 0x8081DDAu);
    sc_peak_set(&d->rs.peak, 464.0f, 0.44f, 1.584893192f);
    rng_seed(&d->cp.rng, 0x808C1A7u);
    bp_set(&d->cp.bp, CP_F, CP_Q);
    sc_hpf_set(&d->cp.hp, 720.0f);
    rng_seed(&d->ma.rng, 0x808AAAC5u);
    ma_tune(&d->ma, 1.0f);

    for (k = 0; k < 6; k++)                       /* SchmittBank::init: phases 0.25 i */
        d->bank.ph[k] = (uint32_t)(k & 3) << 30;
    d->bank.pend_wrap = (1u << 4) | (1u << 5);    /* 1.0 and 1.25 wrap on the first tick */
    rng_seed(&d->bank.rng, 0x808D51F7u);
    bank_ratio(&d->bank, 1.0f);
    cy_init(&d->cy);
    hat_init(&d->ch, 0);
    hat_init(&d->oh, 1);
    cb_init(&d->cb);

    for (k = 0; k < L_NUM; k++) {
        d->lane[k].hit = 1.0f;
        d->lane[k].cg = 1.0f;
    }
    d->lane[L_BD].snd = D8S_BD; d->lane[L_SD].snd = D8S_SD;
    d->lane[L_T0].snd = D8S_LT; d->lane[L_T1].snd = D8S_MT; d->lane[L_T2].snd = D8S_HT;
    d->lane[L_RS].snd = D8S_RS; d->lane[L_CL].snd = D8S_CL; d->lane[L_MA].snd = D8S_MA;
    d->lane[L_CP].snd = D8S_CP; d->lane[L_CB].snd = D8S_CB; d->lane[L_CH].snd = D8S_CH;
    d->lane[L_OH].snd = D8S_OH; d->lane[L_CY].snd = D8S_CY;
}

static void choke_lane(d8_lane_t *l)
{
    if (l->cg > 0.0f && l->cstep == 0.0f)
        l->cstep = -1.0f / (0.002f * SR);
}

static float lane_hz(int s, float tune) { return d8_midicps(k_base_note[s] + tune); }

void drum808_trigger(drum808_t *d, int track, int alternate, float vel,
                     float pitch, int decay_pot, int snap_pot, int accent)
{
    int s, lane;
    float vgain, volts, soft, tune, decay;
    int decay_setting, snap_setting;
    if (track < 0 || track >= D8_NUM || !(vel > 0.0f))
        return;                                   /* drums are one-shots: no note-off */
    if (vel > 1.0f)
        vel = 1.0f;
    d->sw[track] = (uint8_t)alternate;
    s = track_sound(d, track);
    tune = (s < D8S_CB ? 12.0f * fm_log2f(pitch) : pitch);
    decay_setting = d8_scale_pot(k_decay[s].center, decay_pot);
    decay = decay_value(s, decay_setting);
    snap_setting = d8_scale_pot(k_snap[s], snap_pot);


    /* 8W8's velocity line: a trigger voltage on the circuit lanes (4 V floor,
     * 7.3 V at full), the rest as gain */
    vgain = (1.0f + (SC808_FULL_VELOCITY_GAIN - 1.0f) * ((float)accent / 127.0f) * vel);
    volts = 4.0f + 10.0f * ((vgain > 1.0f ? vgain : 1.0f) - 1.0f) / 3.0f;
    if (volts < 4.0f) volts = 4.0f;
    if (volts > 14.0f) volts = 14.0f;
    soft = vgain < 1.0f ? vgain : 1.0f;

    switch (track) {
    case D8_LT: case D8_MT: case D8_HT: lane = L_T0 + (track - D8_LT); break;
    case D8_RS: lane = s == D8S_CL ? L_CL : L_RS; break;
    case D8_CP: lane = s == D8S_MA ? L_MA : L_CP; break;
    case D8_CB: lane = L_CB; break;
    case D8_CY: lane = L_CY; break;
    case D8_OH: lane = L_OH; break;
    case D8_CH: lane = L_CH; break;
    default: lane = track; break;                 /* BD, SD */
    }
    d->lane[lane].hit = vgain;
    d->lane[lane].pk = 0.0f;
    d->lane[lane].qn = 0;
    d->lane[lane].cg = 1.0f;
    d->lane[lane].cstep = 0.0f;
    d->lane[lane].snd = (uint8_t)s;

    /* The hats share one metal source. A closed hat chokes the open hat. */
    if (track == D8_CH) { choke_lane(&d->lane[L_OH]); hat_choke(&d->oh); }

    switch (s) {
    case D8S_BD:
        bd_trigger(&d->bd, lane_hz(s, tune), (float)decay_setting / 127.0f, 42.0f / 127.0f,
                   (float)snap_setting / 127.0f, volts);
        d->lane[lane].hit = soft;
        break;
    case D8S_SD:
        sd_trigger(&d->sd, d8_exp2_cr(tune / 12.0f), (float)decay_setting / 127.0f, 0.5f,
                   (float)snap_setting / 127.0f, volts);
        d->lane[lane].hit = soft;
        break;
    case D8S_LT: case D8S_MT: case D8S_HT: case D8S_LC: case D8S_MC: case D8S_HC:
        tom_trigger(&d->tom[lane - L_T0], s >= D8S_LC, lane_hz(s, tune), decay, volts);
        d->lane[lane].hit = soft;
        break;
    case D8S_RS:
        rs_trigger(&d->rs, lane_hz(s, tune), decay * 9.12f, d8_midicps(63.0f + tune), d8_midicps(118.0f + tune));
        break;
    case D8S_CL:
        cl_trigger(&d->cl, d8_exp2_cr(tune / 12.0f), (float)decay_setting / 127.0f, volts);
        d->lane[lane].hit = soft;
        break;
    case D8S_MA:
        ma_trigger(&d->ma, d8_exp2_cr(tune / 12.0f), decay, (float)snap_setting / 127.0f, volts);
        d->lane[lane].hit = soft;
        break;
    case D8S_CP:
        cp_trigger(&d->cp, d8_exp2_cr(tune / 12.0f), decay);
        break;
    case D8S_CB:
        bank_ratio(&d->bank, tune);
        cb_ratio(&d->cb, tune);
        cb_trigger(&d->cb, decay, volts);
        d->lane[lane].hit = soft;
        break;
    case D8S_CH:                                  /* hats and cymbal: velocity is a gain */
        bank_ratio(&d->bank, tune);
        hat_trigger(&d->ch, decay, 4.0f);
        break;
    case D8S_OH:
        bank_ratio(&d->bank, tune);
        hat_trigger(&d->oh, decay, 4.0f);
        break;
    default:                                      /* CY */
        bank_ratio(&d->bank, tune);
        cy_ratio(&d->cy, tune);
        cy_trigger(&d->cy, decay, 4.0f);
        break;
    }
}

static int voice_active(const drum808_t *d, int l)
{
    switch (l) {
    case L_BD: return d->bd.active;
    case L_SD: return d->sd.active;
    case L_T0: case L_T1: case L_T2: return d->tom[l - L_T0].active;
    case L_RS: return d->rs.active;
    case L_CL: return d->cl.active;
    case L_MA: return d->ma.active;
    case L_CP: return d->cp.active;
    case L_CB: return d->cb.active;
    case L_CH: return d->ch.active;
    case L_OH: return d->oh.active;
    default: return d->cy.active;
    }
}

/* X0X: a voice's tail ends once it has stayed 60 dB under the hit's own peak for 20 ms. 8W8 runs
 * each voice until its output is under -90 dBFS-ish (3.2e-5, or the metal voices' envelopes), so a
 * voice spends 12..87 % of its life (cymbal .. rim shot; cowbell 51 %) under -60 dB of its peak,
 * at full cost: the 808 was the biggest share of the renders that ran over. D8_TAIL_DB=0 keeps
 * 8W8's endings (the reference test). */
#ifndef D8_TAIL_DB
#define D8_TAIL_DB 1
#endif
#define D8_TAIL_K 1.0e-3f              /* -60 dB */
#define D8_TAIL_N 882                  /* 20 ms: the gaps between a clap's bursts are shorter */
#define D8_TAIL_FLOOR 1.0e-2f          /* the hit has sounded (voices peak at 0.2 .. 7) */
#if D8_TAIL_DB
static void voice_stop(drum808_t *d, int l)
{
    switch (l) {
    case L_BD: d->bd.active = 0; break;
    case L_SD: d->sd.active = 0; break;
    case L_T0: case L_T1: case L_T2: d->tom[l - L_T0].active = 0; break;
    case L_RS: d->rs.active = 0; break;
    case L_CL: d->cl.active = 0; break;
    case L_MA: d->ma.active = 0; break;
    case L_CP: d->cp.active = 0; break;
    case L_CB: d->cb.active = 0; break;
    case L_CH: d->ch.active = 0; break;
    case L_OH: d->oh.active = 0; break;
    default: d->cy.active = 0; break;
    }
}
static void lane_tail(drum808_t *d, int l, const float *buf, int m)
{
    d8_lane_t *ln = &d->lane[l];
    float pk = ln->pk;
    int32_t qn = ln->qn;
    int i;
    for (i = 0; i < m; i++) {
        float a = buf[i] < 0.0f ? -buf[i] : buf[i];
        if (a > pk)
            pk = a;
        if (a > pk * D8_TAIL_K)
            qn = 0;
        else
            qn++;
    }
    ln->pk = pk;
    ln->qn = qn;
    if (qn > D8_TAIL_N && pk > D8_TAIL_FLOOR)       /* (a metal voice opens late: it has to have sounded) */
        voice_stop(d, l);
}
#endif

static int lane_on(const drum808_t *d, int l) { return d->lane[l].cg > 0.0f && voice_active(d, l); }

/* Run one voice for up to m samples into buf; stops where 8W8 would stop calling
 * it (the voice went inactive). Returns the samples written. */
static int voice_run(drum808_t *d, int l, float *buf, int m, const float *bus, const float *pair)
{
    int i;
    switch (l) {
    case L_BD: for (i = 0; i < m && d->bd.active; i++) buf[i] = bd_tick(&d->bd); break;
    case L_SD: for (i = 0; i < m && d->sd.active; i++) buf[i] = sd_tick(&d->sd); break;
    case L_T0: case L_T1: case L_T2: {
        d8_tom_t *t = &d->tom[l - L_T0];
        for (i = 0; i < m && t->active; i++) buf[i] = tom_tick(t);
        break;
    }
    case L_RS: for (i = 0; i < m && d->rs.active; i++) buf[i] = rs_tick(&d->rs); break;
    case L_CL: for (i = 0; i < m && d->cl.active; i++) buf[i] = cl_tick(&d->cl); break;
    case L_MA: for (i = 0; i < m && d->ma.active; i++) buf[i] = ma_tick(&d->ma); break;
    case L_CP: for (i = 0; i < m && d->cp.active; i++) buf[i] = cp_tick(&d->cp); break;
    case L_CB: for (i = 0; i < m && d->cb.active; i++) buf[i] = cb_tick(&d->cb, pair[i]); break;
    case L_CH: for (i = 0; i < m && d->ch.active; i++) buf[i] = hat_tick(&d->ch, bus[i]); break;
    case L_OH: for (i = 0; i < m && d->oh.active; i++) buf[i] = hat_tick(&d->oh, bus[i]); break;
    default: for (i = 0; i < m && d->cy.active; i++) buf[i] = cy_tick(&d->cy, bus[i]); break;
    }
    return i;
}

/* Map each circuit voice to its X0X instrument. The selector can change
 * between hits, so old alternate voices must never leak into the new hit. */
static const uint8_t k_lane_track[L_NUM] = {
    D8_BD, D8_SD, D8_LT, D8_MT, D8_HT, D8_RS, D8_RS, D8_CP, D8_CP,
    D8_CB, D8_CH, D8_OH, D8_CY
};

int drum808_voice_active(const drum808_t *d, int track)
{
    const int sound = track_sound(d, track);
    for (int i = 0; i < L_NUM; i++)
        if (k_lane_track[i] == track && d->lane[i].snd == sound && lane_on(d, i))
            return 1;
    return 0;
}

void drum808_stems(drum808_t *d, float *const stem[D8_NUM], int n)
{
    float buf[CHUNK], bus[CHUNK], pair[CHUNK];
    for (int off = 0; off < n; off += CHUNK) {
        const int m = n - off < CHUNK ? n - off : CHUNK;
        if ((stem[D8_CB] && lane_on(d, L_CB)) || (stem[D8_CH] && lane_on(d, L_CH)) ||
            (stem[D8_OH] && lane_on(d, L_OH)) || (stem[D8_CY] && lane_on(d, L_CY)))
            bank_block(&d->bank, bus, pair, m);
        for (int l = 0; l < L_NUM; l++) {
            d8_lane_t *ln = &d->lane[l];
            const int track = k_lane_track[l];
            float *out = stem[track];
            if (!out || ln->snd != track_sound(d, track) || !lane_on(d, l))
                continue;
            const int count = voice_run(d, l, buf, m, bus, pair);
            const float gain = k_trim[ln->snd] * ln->hit * (200.0f * 64.0f / (127.0f * 127.0f));
            for (int i = 0; i < count; i++) {
                if (ln->cstep < 0.0f) {
                    ln->cg += ln->cstep;
                    if (ln->cg <= 0.0f) {
                        ln->cg = ln->cstep = 0.0f;
                        break;
                    }
                }
                out[off + i] = buf[i] * gain * ln->cg;
            }
#if D8_TAIL_DB
            lane_tail(d, l, buf, count);
#endif
        }
    }
}
