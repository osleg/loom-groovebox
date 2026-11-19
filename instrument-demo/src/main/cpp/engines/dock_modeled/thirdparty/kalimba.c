/* SPDX-License-Identifier: GPL-3.0-only
 * FiMba-1 dry tine and body model by jadamsowers and contributors.
 * Adapted for FM-1 Instrument, 2026-11-19: per-voice state, native envelope,
 * note allocation and mix. See README.md for the pinned donor. */
#include "kalimba.h"
#include "fastmath.h"
#include <string.h>

#define KM_SR 44100.0f
#define CONTACT_SAMPLES 192
#define CONTACT_D 0.99689f

typedef struct {
    const char *name;
    float ratio[FIMBA_MODES - 1], amp[FIMBA_MODES - 1];
    float t60, loss, pitch_k, beat, beat_amp, click;
} material_t;

/* Ratios: a uniform clamped-free bar has its bending modes at 1, 6.27, 17.55, 34.39 (Euler-Bernoulli,
 * (beta_n / beta_1)^2); a real tine is thinner at the tip and bent up, which pulls them down a little,
 * more so in soft brass. Bronze and glass are played as bars free at both ends (1, 2.76, 5.40, 8.93),
 * a bell's brighter spectrum. Bamboo tines are short-lived and woody (high internal loss). */
static const material_t MATS[FIMBA_MATERIALS] = {
    {"Steel", {1.0028f, 5.90f, 16.2f, 31.5f}, {0.35f, 0.30f, 0.10f, 0.040f}, 5.0f, 0.10f, 0.55f, 0.0028f, 0.35f, 0.50f},
    {"Brass", {1.0020f, 5.60f, 15.3f, 29.8f}, {0.30f, 0.22f, 0.06f, 0.020f}, 3.6f, 0.20f, 0.50f, 0.0020f, 0.30f, 0.30f},
    {"Bronze", {1.0015f, 2.76f, 5.40f, 8.93f}, {0.40f, 0.45f, 0.25f, 0.120f}, 4.5f, 0.06f, 0.60f, 0.0015f, 0.40f, 0.60f},
    {"Alumin.", {1.0040f, 6.27f, 17.55f, 34.4f}, {0.30f, 0.50f, 0.22f, 0.100f}, 2.6f, 0.12f, 0.50f, 0.0040f, 0.30f, 0.70f},
    {"Bamboo", {1.0000f, 4.60f, 11.8f, 22.3f}, {0.00f, 0.30f, 0.12f, 0.050f}, 0.55f, 0.90f, 0.35f, 0.0f, 0.0f, 0.25f},
    {"Glass", {1.0010f, 3.01f, 6.24f, 10.4f}, {0.20f, 0.55f, 0.35f, 0.200f}, 2.0f, 0.01f, 0.70f, 0.0010f, 0.20f, 0.90f},
};
/* Worn: an old, played-in instrument. Each tine has its own quirks, the same each time it's plucked
 * (they come from its pitch): a little out of tune (up to +-12 cents), a sustain longer or shorter than
 * its neighbours', its two bending planes further apart or closer (a faster or slower beat), its upper
 * modes brighter or duller, and a little louder or softer. */
static float wear(int cents, int i)                   /* the tine's quirk i, -1..1 */
{
    uint32_t h = (uint32_t)cents * 2654435761u ^ (uint32_t)i * 0x9E3779B9u;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    return (float)(h & 0xFFFFu) * (2.0f / 65535.0f) - 1.0f;
}

/* Simper's SVF (trapezoidal), the band-pass output: g = tan(pi f / sr), k = 1 / q,
 * a1 = 1 / (1 + g (g + k)), a2 = g a1, a3 = g a2 */
static inline float svf_bp(fimba_filter_t *s, float x)
{
    float v3 = x - s->s2, v1 = s->a1 * s->s1 + s->a2 * v3, v2 = s->s2 + s->a2 * s->s1 + s->a3 * v3;
    s->s1 = 2.0f * v1 - s->s1;
    s->s2 = 2.0f * v2 - s->s2;
    return v1;
}
/* the same, its low-pass output (the hand over the hole) */
static inline float svf_lp(fimba_filter_t *s, float x)
{
    float v3 = x - s->s2, v1 = s->a1 * s->s1 + s->a2 * v3, v2 = s->s2 + s->a2 * s->s1 + s->a3 * v3;
    s->s1 = 2.0f * v1 - s->s1;
    s->s2 = 2.0f * v2 - s->s2;
    return v2;
}
/* The bodies. What the ear tells them apart by: how the direct sound is coloured (a plank radiates
 * little bass and sounds thin; a box warms it; a gourd is hollow and dark), the body's own resonances
 * among the tines' frequencies, and the knock of each pluck through the wood (the thumb pushes the
 * instrument as well as the tine). Per body:
 *   modes  four band-pass resonances: the air mode first (the sound hole: Helmholtz, which the wah
 *          moves), then the plate or shell modes; hz, q, level (peak gain)
 *   tone   the direct sound: low cut and high cut (Hz; 0 none)
 *   knock  how much of the pluck's force reaches the body
 *   out    the level, so that a body changes the character and not the volume */
typedef struct {
    float m[4][3];
    float lo_cut, hi_cut, knock, out;
} body_t;
static const body_t BODIES[FIMBA_BODIES] = {
    {{{0}}, 0.0f, 0.0f, 0.0f, 1.0f},                                                       /* None: the bare tine */
    {{{0.0f, 1.0f, 0.0f}, {380.0f, 3.0f, 0.55f}, {980.0f, 4.0f, 0.45f}, {2300.0f, 5.0f, 0.35f}},
     260.0f, 0.0f, 0.6f, 1.05f},                                                           /* Board: a plank, thin and bright */
    {{{210.0f, 7.0f, 2.6f}, {460.0f, 5.0f, 0.9f}, {1120.0f, 6.0f, 0.4f}, {2700.0f, 6.0f, 0.18f}},
     0.0f, 6500.0f, 1.0f, 0.58f},                                                          /* Box: warm, a bloom at the hole */
    {{{135.0f, 6.0f, 2.2f}, {330.0f, 4.0f, 1.1f}, {760.0f, 5.0f, 0.8f}, {1650.0f, 4.0f, 0.25f}},
     0.0f, 3800.0f, 1.4f, 0.5f},                                                           /* Gourd: hollow, boomy, dark */
};
static float onepole(float hz) { return hz > 0.0f ? 1.0f - fm_expf(-FM_TWO_PI * hz / KM_SR) : 1.0f; }

static void svf_set(fimba_filter_t *f, float hz, float qq, float gain)
{
    float g = fm_tanf(FM_PI * hz / KM_SR), k = 1.0f / qq;
    f->a1 = 1.0f / (1.0f + g * (g + k));
    f->a2 = g * f->a1;
    f->a3 = g * f->a2;
    f->gain = gain * k;                                 /* the band-pass peaks at 1/k: level by gain */
}

static float rnds(fimba_voice_t *v)
{
    uint32_t x = v->rng;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    v->rng = x;
    return (float)(x >> 8) * (1.0f / 8388608.0f) - 1.0f;
}

/* The original modal ratios, decay law, pitch scaling and repeatable wear
 * are independent per tine. Contact damps a ringing tine before re-plucking. */
static void tine_coefs(fimba_voice_t *v, const fimba_patch_t *p, float pitch)
{
    const material_t *m = &MATS[p->material];
    const float worn = p->wear / 127.0f;
    float f0 = 8.17579892f * fm_exp2f(pitch / 12.0f);
    const float decay_scale = fm_exp2f((p->decay * (100.0f / 127.0f) - 50.0f) * 0.04f)
                            * (1.0f + 0.35f * worn * wear(v->note * 100, 1));
    const float tone = fm_exp2f((50.0f - p->tone * (100.0f / 127.0f)) * 0.04f);
    const float spread = 1.0f + 1.5f * worn * wear(v->note * 100, 2);
    const float upper = 1.0f + 0.5f * worn * wear(v->note * 100, 3);
    const float level = 1.0f + 0.2f * worn * wear(v->note * 100, 4);
    f0 *= fm_exp2f(worn * wear(v->note * 100, 0) * 0.01f);
    const float t1 = m->t60 * decay_scale * fm_powf(f0 / 261.63f, -m->pitch_k);
    for (int k = 0; k < FIMBA_MODES; k++) {
        float ratio = k ? m->ratio[k - 1] : 1.0f;
        float amp = k ? m->amp[k - 1] : 1.0f;
        if (k == 1)
            ratio = 1.0f + (ratio - 1.0f) * spread;
        if (k >= 2)
            amp *= upper;
        const float f = f0 * ratio * (1.0f + v->tension * 0.000577791f);
        if (f >= 0.45f * KM_SR || amp <= 0.0f || (k == 1 && m->beat_amp <= 0.0f)) {
            v->gain[k] = v->y1[k] = v->y2[k] = 0.0f;
            continue;
        }
        const float t60 = fm_maxf(0.01f, k <= 1 ? t1 : t1 / (1.0f + m->loss * tone * (ratio - 1.0f)));
        const float damp = v->contact_samples > 0 ? CONTACT_D : 1.0f;
        const float r = fm_expf(-6.9077553f / (t60 * KM_SR)) * damp;
        const float w = FM_TWO_PI * f / KM_SR;
        v->b1[k] = 2.0f * r * fm_cosf(w);
        v->b2[k] = -r * r;
        v->gain[k] = amp * level * fm_sinf(w);
    }
    v->pitch = pitch;
    v->material = p->material;
    v->decay = p->decay;
    v->tone = p->tone;
    v->wear = p->wear;
}

static void body_coefs(fimba_voice_t *v, const fimba_patch_t *p)
{
    const body_t *body = &BODIES[p->body];
    if (v->body_type != p->body) {
        memset(v->body, 0, sizeof v->body);
        memset(&v->muffle, 0, sizeof v->muffle);
        v->low = v->high = 0.0f;
    }
    const float cover = p->hole / 127.0f;
    for (int i = 0; i < 4; i++) {
        float hz = body->m[i][0], q = body->m[i][1], gain = body->m[i][2];
        if (i == 0 && hz > 0.0f) {
            const float open = 1.0f - 0.9f * cover;
            hz *= fm_sqrtf(open);
            q *= 1.0f + 0.8f * (1.0f - open);
            gain *= 0.35f + 0.65f * open;
        }
        if (hz > 0.0f)
            svf_set(&v->body[i], hz, q, gain);
    }
    if (body->m[0][0] > 0.0f) {
        const float u = 1.0f - cover;
        svf_set(&v->muffle, 400.0f * fm_exp2f(5.49f * u * fm_sqrtf(u)), 0.707f + 1.5f * cover, 1.0f);
    }
    v->body_type = p->body;
    v->hole = p->hole;
}

void fimba_pluck(fimba_voice_t *v, const fimba_patch_t *p, int note, int velocity)
{
    const int again = v->ready && v->note == note;
    if (!again) {
        memset(v, 0, sizeof *v);
        v->rng = 0x12345678u ^ ((uint32_t)note * 0x9E3779B9u);
    }
    const material_t *m = &MATS[p->material];
    const float hard = fm_clampf(p->hard / 127.0f + (velocity - 90) * (0.35f / 127.0f), 0.0f, 1.0f);
    const float n = fm_maxf(1.0f, 0.0012f * fm_exp2f(-4.5f * hard) * KM_SR);
    const float vel = velocity / 127.0f;
    const float amp = 0.42f + 0.13f * vel;
    v->pulse_step = 0.5f / n;
    v->pulse = again ? -CONTACT_SAMPLES * v->pulse_step : 0.0f;
    v->pulse_amp = amp * (FM_PI * 0.5f) / n;
    v->click = amp * m->click * hard * hard * 0.12f;
    v->note = note;
    v->tension = 12.0f * vel * vel * (0.4f + 0.6f * hard);
    v->contact_samples = again ? CONTACT_SAMPLES : 0;
    v->ready = 1;
    body_coefs(v, p);
    tine_coefs(v, p, (float)note);
}

void fimba_render(fimba_voice_t *v, const fimba_patch_t *p, float pitch, float *out, uint32_t n)
{
    const body_t *body = &BODIES[p->body];
    float tension = v->tension;
    if (v->contact_samples <= 0)
        v->tension = tension > 0.05f ? tension * (1.0f - (float)n / (0.045f * KM_SR)) : 0.0f;
    if (v->pitch != pitch || v->material != p->material || v->decay != p->decay ||
        v->tone != p->tone || v->wear != p->wear || tension != v->tension)
        tine_coefs(v, p, pitch);
    if (v->body_type != p->body || v->hole != p->hole)
        body_coefs(v, p);
    const float lo = body->lo_cut > 0.0f ? onepole(body->lo_cut) : 0.0f;
    const float hi = onepole(body->hi_cut);
    const float buzz = p->buzz / 127.0f;
    const float threshold = 0.05f + 0.2f * (1.0f - buzz);
    const float click_decay = 0.9909707f; /* exp(-1/(0.0025 * 44100)) */
    for (uint32_t i = 0; i < n; i++) {
        float excitation = 0.0f, x = 0.0f;
        if (v->pulse < 0.5f) {
            if (v->pulse >= 0.0f)
                excitation = v->pulse_amp * fm_sin_turns(v->pulse);
            v->pulse += v->pulse_step;
        }
        for (int k = 0; k < FIMBA_MODES; k++) {
            if (v->gain[k] == 0.0f)
                continue;
            float y = v->b1[k] * v->y1[k] + v->b2[k] * v->y2[k] + v->gain[k] * excitation;
            v->y2[k] = v->y1[k];
            v->y1[k] = y;
            x += y;
        }
        if (v->click > 1.0e-6f && v->pulse >= 0.0f) {
            x += v->click * rnds(v);
            v->click *= click_decay;
        }
        if (p->body != FIMBA_BARE) {
            const float knock = x + 6.0f * body->knock * excitation;
            float resonance = 0.0f;
            for (int k = 0; k < 4; k++)
                if (v->body[k].gain > 0.0f)
                    resonance += svf_bp(&v->body[k], knock) * v->body[k].gain;
            v->high += hi * (x - v->high);
            v->low += lo * (v->high - v->low);
            x = (v->high - v->low + resonance) * body->out;
            if (body->m[0][0] > 0.0f)
                x = svf_lp(&v->muffle, x) * (1.0f - p->hole * (0.5f / 127.0f));
        }
        if (buzz > 0.0f) {
            const float excess = x - fm_clampf(x, -threshold, threshold);
            const float rattle = fm_fabsf(excess) * (rnds(v) + (excess > 0.0f ? 0.6f : -0.6f));
            v->buzz_state += 0.25f * (rattle - v->buzz_state);
            x += (rattle - v->buzz_state) * buzz * 2.5f;
        }
        out[i] = x;
    }
    if (v->contact_samples > 0) {
        v->contact_samples -= (int)n;
        if (v->contact_samples <= 0)
            tine_coefs(v, p, pitch);
    }
    for (int k = 0; k < FIMBA_MODES; k++) {
        v->y1[k] = fm_flush(v->y1[k]);
        v->y2[k] = fm_flush(v->y2[k]);
    }
}
