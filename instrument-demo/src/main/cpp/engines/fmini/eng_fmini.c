/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Ruppert's FMini-1 firmware/src/eng_fmini.c, commit
 * 74375d3311889d8e28e6b2601d30f0df4faf70ab. See README.md.
 * Skiff runs the donor's oscillators, mixer and zero-delay ladder with its own
 * notes, storage, modulation and effects. BANK and PATCH select the factory
 * oscillator configuration and its modulation depth. */
#define FG_SIZE 32u
#define FMINI_NFACTORY 200u
enum {                           /* the patch's bytes */
    FG_R1, FG_R2, FG_R3,         /* range: 0 LO, 1 32', 2 16', 3 8', 4 4', 5 2' */
    FG_W1, FG_W2, FG_W3,         /* wave: 0 TRI, 1 SHARK, 2 SAW, 3 SQR, 4 PLS W, 5 PLS N, 6 SIN */
    FG_F2, FG_F3,                /* OSC 2 / 3 frequency, 64 = 0, +-7 semitones */
    FG_L1, FG_L2, FG_L3, FG_LN,  /* mixer levels 0..127 (a squared curve) */
    FG_SW1,                      /* FS_ bits */
    FG_SW2,                      /* FT_ bits */
    FG_TUNE,                     /* 64 = A 440, +-100 cents */
    FG_MMIX,                     /* modulation source: 0 LFO .. 127 noise */
    FG_KBD,                      /* keyboard tracking of the cutoff, 0..127 = 0..1 */
    FG_GLID,                     /* glide time kept while the GLIDE switch is off (P_GLIDE holds it while on) */
    FG_DC2L, FG_DC2H,            /* DET 2 in cents: a signed 16-bit (low, high byte), -700 .. +700 (1.14.12) */
    FG_DC3L, FG_DC3H,            /* DET 3 in cents, the same */
    FG_BPML, FG_BPMH,            /* TEMPO in BPM, 7 bits each (lo, hi): 40 .. 240; 0 = none (the song's tempo stays) (1.14.25) */
    FG_NUM
};
enum { FS_O1 = 1, FS_O2 = 2, FS_O3 = 4, FS_NZ = 8, FS_PINK = 16, FS_MOSC = 32, FS_MFLT = 64 };   /* FG_SW1 */
enum { FT_SQR = 2, FT_HP = 4 };                                                      /* FG_SW2 (FT_HP: the high-pass output, 1.x) */


#define FGP(...) { __VA_ARGS__ }
#include "fmini_presets.h"
#undef FGP
#include "fmini_filter.h"

typedef struct {
    int32_t noise, pink[3], noise_block[CTL], mod_noise;
    int32_t filter[3];
    uint8_t filter_ready;
} fmini_part_t;

static fmini_part_t *fmini_part(track_t *t) { return (fmini_part_t *)eng_part(t); }

static uint32_t fmini_patch_index(const track_t *t)
{
    return (uint32_t)clamp(t->p[P_E6], 0, 1) * 100u +
           (uint32_t)clamp(t->p[P_E7], 0, 99);
}

static int32_t fmini_det(const uint8_t *patch, uint32_t lo)
{
    int32_t magnitude = patch[lo] | (int32_t)(patch[lo + 1] & 7) << 7;
    return patch[lo + 1] & 8 ? -magnitude : magnitude;
}

static void fmini_block(track_t *t)
{
    fmini_part_t *part = fmini_part(t);
    const uint8_t *patch = FMINI_FACTORY[fmini_patch_index(t)];
    uint32_t i;
    for (i = 0; i < 3u; i++) {
        int32_t want = clamp(t->p[P_E0 + i], 0, 127) << 16;
        if (!part->filter_ready) {
            part->filter[i] = want;
        } else {
            int32_t distance = want - part->filter[i];
            int32_t step = (int32_t)((int64_t)distance * 238 / 65536);
            part->filter[i] += step ? step : (distance > 0) - (distance < 0);
        }
    }
    part->filter_ready = 1;
    if (!part->noise) part->noise = 0x2f6e2b1 + (int32_t)(t - trk) * 7919;
    part->mod_noise = (int32_t)(noise32(&part->noise) >> 17) - 16384;
    if (!(patch[FG_SW1] & FS_NZ)) return;
    for (i = 0; i < CTL; i++) {
        int32_t white = (int32_t)(noise32(&part->noise) >> 16) - 32768;
        if (patch[FG_SW1] & FS_PINK) {
            part->pink[0] += ((white * 3245) >> 15) - ((part->pink[0] * 77) >> 15);
            part->pink[1] += ((white * 9716) >> 15) - ((part->pink[1] * 1212) >> 15);
            part->pink[2] += ((white * 34495) >> 15) - ((part->pink[2] * 14090) >> 15);
            white = (part->pink[0] + part->pink[1] + part->pink[2] + ((white * 6056) >> 15)) >> 1;
        }
        part->noise_block[i] = white;
    }
}

static inline int32_t fmini_shark(uint32_t ph)            /* a triangle that rises for 3/4 of the cycle */
{
    uint32_t x = ph >> 16;
    return x < 49152u ? (int32_t)((x * 43691u) >> 15) - 32768 : 32767 - (int32_t)((x - 49152u) * 4u);
}

/* one oscillator of the mix: mix[i] += its sample at level g (Q15); a loop per wave */
static void fmini_wave(uint32_t w, uint32_t *php, uint32_t inc, int32_t g, int32_t *mix, uint32_t n)
{
    uint32_t ph = *php, i;
    switch (w) {
    case 0:
        for (i = 0; i < n; i++) {
            mix[i] += mulq15(osc_tri(ph), g);
            ph += inc;
        }
        break;
    case 1:
        for (i = 0; i < n; i++) {
            mix[i] += mulq15(fmini_shark(ph), g);
            ph += inc;
        }
        break;
    case 2:
        for (i = 0; i < n; i++) {
            mix[i] += mulq15(osc_saw(ph, inc), g);
            ph += inc;
        }
        break;
    case 3:
        for (i = 0; i < n; i++) {
            mix[i] += mulq15(osc_pulse(ph, inc, 0x80000000u), g);
            ph += inc;
        }
        break;
    case 4:
        for (i = 0; i < n; i++) {
            mix[i] += mulq15(osc_pulse(ph, inc, 0x55555555u), g);
            ph += inc;
        }
        break;
    case 6:                                             /* SIN: the sine table (dsp.c), no harmonics to fold back */
        for (i = 0; i < n; i++) {
            mix[i] += mulq15(osc_sine(ph), g);
            ph += inc;
        }
        break;
    default:
        for (i = 0; i < n; i++) {
            mix[i] += mulq15(osc_pulse(ph, inc, 0x1C71C71Cu), g);
            ph += inc;
        }
        break;
    }
    *php = ph;
}

/* range of an oscillator in 1/16 semitones (LO: very low) */
static int32_t fmini_range16(uint32_t r)
{
    static const int16_t R[6] = {-48 * 16, -24 * 16, -12 * 16, 0, 12 * 16, 24 * 16};
    return R[r % 6u];
}

static void fmini_note_on(track_t *t, voice_t *v)
{
    fmini_part_t *part = fmini_part(t);
    if (!v->env && !v->env_out) {                          /* from silence: the ladder and the contour from 0 */
        v->s[0] = v->s[1] = v->s[2] = v->s[3] = 0;
        v->s[4] = 0;
        v->s[7] = 0;
        if (t->p[P_E1] >= 96)                                         /* it will sing: a nudge to start from (the hardware has its own noise) */
            v->s[0] = (noise32(&part->noise) & 0x80000000u) ? 700 * 256 : -700 * 256;
        v->s[5] = (int32_t)(noise32(&part->noise) >> 20) - 2048;   /* the VCOs start a little apart: drift, cents * 256 */
        v->s[6] = (int32_t)(noise32(&part->noise) >> 20) - 2048;
    }
    v->s[7] = (int32_t)(1u << 24) | (v->s[7] & 0xFFFFFF);  /* the contour attacks from where it is */
}

/* the contour, once a block: stage 1 attack, 2 decay to the sustain, 3 release (the DECAY time); level Q23 */
static int32_t fmini_contour(const int16_t *p, voice_t *v, int32_t kr)
{
    uint32_t st = (uint32_t)v->s[7] >> 24;
    int32_t e = v->s[7] & 0xFFFFFF, sus = (int32_t)p[P_E5] << 16, k = (int32_t)ENV_EXP[p[P_E4] & 127];
    if (!v->gate && st && st != 3u)
        st = 3;
    switch (st) {
    case 1:
        {   /* the capacitor charges toward a level above full: a concave rise that ends in a corner, as in the Minimoog */
            int32_t step = (int32_t)(ENV_LIN[p[P_E3] & 127] >> 1), d = (int32_t)(((int64_t)((5 << 21) - e) * step * 824) >> 32);
            e += d < 1 ? 1 : d;
        }
        if (e >= (1 << 23)) {
            e = 1 << 23;
            st = 2;
        }
        break;
    case 2:
        e += mulq16(sus - e, (uint32_t)k);                  /* the DECAY time is the drop from the peak to the SUSTAIN level, whatever it is */
        break;
    case 3:
        e -= mulq16(e, (uint32_t)kr);                       /* the RELEASE: its own time (E4 when the preset has none) */
        break;
    default:
        break;
    }
    v->s[7] = (int32_t)(st << 24) | (e & 0xFFFFFF);
    return e >> 8;                                          /* Q15 */
}

/* a * k / 65536 rounded to the nearest (mulq16 floors: four stages of it leave a bias that a quiet, low ladder cannot sing against) */
static inline int32_t fmini_mulr(int32_t a, uint32_t k)
{
    return (int32_t)(((a >> 16) * (int32_t)k) + (int32_t)(((uint32_t)(a & 0xFFFF) * k + 32768u) >> 16));
}

/* The filter, after the Minimoog's (tools/fmini_filter.py has the laws and the reasoning, the CA-72 project's calibration the
 * measurements): four equal one-pole stages, each a differential pair (tanh in front), the last stage's output fed back to
 * the first through a lag pole (the emphasis network), solved without a delay in the loop (zero-delay feedback: each stage
 * is a trapezoidal integrator, G = g / (1 + g), and the feedback is found from the stages' states before the sample is
 * processed). The lag pole puts the loop's 180-degree point at 0.686 of the stage pole: the filter sings at 1.578 times
 * its -3 dB corner, and it sings when the feedback k reaches 2.92. The knobs and the pitch work in 1/16 semitones of the
 * singing pitch (MIDI x 16): CUTOFF is the dial -5 .. +5 (FMINI_CUT), the keyboard adds 1 V/oct from F (MIDI 41) at KBD 127,
 * the contour up to 5 octaves; EMPHASIS is the dial 0 .. 10 (FMINI_K) with the hardware's passband loss (FMINI_TRIM). With
 * the mixer silent and EMPHASIS past 8 the ladder sings a sine at the singing pitch, 1 V/oct over the keyboard.
 * Q formats: signals +-32768 (tanh(1)); the states Q8 (a quiet ladder at a low cutoff moves less than a LSB a sample); G Q16; the prediction's coefficients c Q14; k Q12. */
static void fmini_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    const int16_t *p = t->p;
    fmini_part_t *part = fmini_part(t);
    uint32_t patch = fmini_patch_index(t);
    const uint8_t *g = FMINI_FACTORY[patch];
    int32_t mix[CTL];
    int32_t env15, mod, lfo, vib, lc, p16, fine, k12, G16, gx16, c0, c1, c2, c3, inv14, kinv12, trim12;
    int32_t l1 = 0, l2 = 0, l3 = 0, ln = 0, dr1, dr2, dr3;
    uint32_t hp = (uint32_t)(g[FG_SW2] & FT_HP) ? 1u : 0u;      /* the output: LP (the last stage) or HP ((1 - LP)^4 of the same stages) */
    uint32_t i, sw = g[FG_SW1];
    uint32_t inc1, inc2, inc3;
    int32_t s0 = v->s[0], s1 = v->s[1], s2 = v->s[2], s3 = v->s[3], sx = v->s[4];
    uint32_t ph0 = v->ph[0], ph1 = v->ph[1], ph2 = v->ph[2];
    if (n > CTL)
        n = CTL;
    env15 = fmini_contour(p, v, (int32_t)ENV_EXP[p[P_E4] & 127]);

    /* modulation: the LFO (a square when FT_SQR) or noise held for the block, MOD deep */
    lfo = mulq15(t->lfo_val, t->lfo_fade);
    if (g[FG_SW2] & FT_SQR)
        lfo = lfo >= 0 ? 32767 : -32767;
    mod = mulq15(lfo, 32767 - (int32_t)g[FG_MMIX] * 258) + mulq15(part->mod_noise * 2, (int32_t)g[FG_MMIX] * 258);
    mod = mulq15(mod, (int32_t)FMINI_MOD[patch] * 258);
    /* the mod wheel (CC1, t->mw) is the vibrato: the LFO on the pitch, +-1 semitone at full wheel, whatever MOD OSC is */
    vib = (mulq15(lfo, (int32_t)t->mw * 258) * 16) >> 15;

    /* oscillator pitches */
    p16 = m->pitch16 + vib + (sw & FS_MOSC ? (mod * 16) >> 14 : 0);    /* full depth: +-2 semitones */
    fine = m->fine + ((int32_t)g[FG_TUNE] - 64) * 237 / 64; /* host tuning is already in m */
    {   /* the VCOs wander a few cents (temperature, the integrators): a slow random walk per oscillator, about +-2 cents */
        int32_t d1 = v->s[5], d2 = v->s[6];
        d1 += ((int32_t)(noise32(&part->noise) >> 20) - 2048 >> 6) - (d1 >> 11);
        d2 += ((int32_t)(noise32(&part->noise) >> 20) - 2048 >> 6) - (d2 >> 11);
        v->s[5] = d1;
        v->s[6] = d2;
        dr1 = d1 >> 8;
        dr2 = d2 >> 8;
        dr3 = (d1 - d2) >> 9;
    }
    inc1 = cents_inc(clamp(p16 + fmini_range16(g[FG_R1]), 0, 2047), dr1, fine);
    inc2 = cents_inc(clamp(p16 + fmini_range16(g[FG_R2]), 0, 2047), fmini_det(g, FG_DC2L) + dr2, fine);
    inc3 = cents_inc(clamp(p16 + fmini_range16(g[FG_R3]), 0, 2047), fmini_det(g, FG_DC3L) + dr3, fine);

    /* mixer levels: a squared curve */
    if (sw & FS_O1) l1 = (int32_t)(g[FG_L1] * g[FG_L1] * 2) + 0;
    if (sw & FS_O2) l2 = (int32_t)(g[FG_L2] * g[FG_L2] * 2);
    if (sw & FS_O3) l3 = (int32_t)(g[FG_L3] * g[FG_L3] * 2);
    if (sw & FS_NZ) ln = (int32_t)(g[FG_LN] * g[FG_LN] * 2);
    for (i = 0; i < n; i++)
        mix[i] = 0;
    if (l1)
        fmini_wave(g[FG_W1], &ph0, inc1, l1, mix, n);
    else
        ph0 += inc1 * n;
    if (l2)
        fmini_wave(g[FG_W2], &ph1, inc2, l2, mix, n);
    else
        ph1 += inc2 * n;
    if (l3)
        fmini_wave(g[FG_W3], &ph2, inc3, l3, mix, n);
    else
        ph2 += inc3 * n;
    if (ln)
        for (i = 0; i < n; i++)
            mix[i] += mulq15(part->noise_block[i], ln);

    /* the singing pitch (MIDI x 16): the CUTOFF knob's dial, the contour, the keyboard, the modulation, the host's (LFO / matrix / ENV routings) */
    {
        int32_t kn = part->filter[0], i0 = kn >> 16, fr = (kn >> 8) & 255;
        lc = FMINI_CUT[i0];
        if (i0 < 127)
            lc += ((FMINI_CUT[i0 + 1] - lc) * fr) >> 8;
    }
    lc += (m->cutoff * 55) >> 10;                                                                           /* the host's steps (1/14 octave) in 1/16 semitones */
    lc += (int32_t)(((int64_t)env15 * part->filter[2] / (127 * 65536)) * 960) >> 15;                          /* the contour: up to 5 octaves */
    lc += ((v->pitch_cur - FMINI_NOTE0 * 16) * (int32_t)g[FG_KBD] * 516) >> 16;                              /* the keyboard: 1 V/oct at 127, 0 V on F */
    if (sw & FS_MFLT)
        lc += (mod * 547) >> 15;                                                                            /* +-2.85 octaves at full depth */
    lc = clamp(lc, FMINI_GM0 * 16, FMINI_FOSC_MAX16);
    {   /* the stage pole G and the lag pole's from the tables (one entry a semitone, linear between), the feedback solved ahead */
        uint32_t ix = (uint32_t)((lc >> 4) - FMINI_GM0), fr = (uint32_t)lc & 15u, gq, gxq, g2, g3, g4, om;
        gq = FMINI_G[ix] + (uint32_t)(((int32_t)(FMINI_G[ix + 1] - FMINI_G[ix]) * (int32_t)fr) >> 4);
        gxq = FMINI_GX[ix] + (uint32_t)(((int32_t)(FMINI_GX[ix + 1] - FMINI_GX[ix]) * (int32_t)fr) >> 4);
        G16 = (int32_t)((gq + 128u) >> 8);
        gx16 = (int32_t)((gxq + 128u) >> 8);
        om = 65536u - (uint32_t)G16;
        g2 = ((uint32_t)G16 * (uint32_t)G16) >> 16;
        g3 = (g2 * (uint32_t)G16) >> 16;
        g4 = (g3 * (uint32_t)G16) >> 16;
        c3 = (int32_t)(om >> 2);                                   /* y4 = G^4 u + c0 s0 + c1 s1 + c2 s2 + c3 s3 */
        c2 = (int32_t)(((uint32_t)G16 * om) >> 18);
        c1 = (int32_t)((g2 * om) >> 18);
        c0 = (int32_t)((g3 * om) >> 18);
        {   /* EMPHASIS: the feedback gain k and the passband's loss, from the knob (Q16) */
            int32_t kn = part->filter[1], i0 = kn >> 16, fk = (kn >> 8) & 255, den;
            k12 = FMINI_K[i0];
            trim12 = FMINI_TRIM[i0];
            if (i0 < 127) {
                k12 += ((FMINI_K[i0 + 1] - k12) * fk) >> 8;
                trim12 += ((FMINI_TRIM[i0 + 1] - trim12) * fk) >> 8;
            }
            den = 65536 + ((k12 * (int32_t)((((uint32_t)gxq >> 8) * g4) >> 16)) >> 12);        /* 1 + k Gx G^4: the loop around the whole sample */
            inv14 = (int32_t)((1u << 30) / (uint32_t)den);
            kinv12 = (k12 * inv14) >> 14;
        }
    }

    for (i = 0; i < n; i++) {
        int32_t x = softclip(mix[i]), S, fbc, u, xin, v1, v2, v3, v4, y1, y2, y3, y4, w, o;   /* the mixer overloads softly */
        S = (c0 * ((s0 + 128) >> 8) + c1 * ((s1 + 128) >> 8) + c2 * ((s2 + 128) >> 8) + c3 * ((s3 + 128) >> 8)) >> 14;   /* what the stages already hold */
        fbc = sx + fmini_mulr((S * 256) - sx, (uint32_t)gx16);              /* through the lag pole (Q8) */
        u = ((x * inv14) >> 14) - ((kinv12 * ((fbc + 128) >> 8)) >> 12);  /* the input less the feedback, the loop solved */
        xin = softclip(u) * 256;                                              /* the first stage's input (Q8) */
        v1 = fmini_mulr(xin - s0, (uint32_t)G16);                          /* each stage is a differential pair: tanh in front of it */
        y1 = v1 + s0;
        s0 = y1 + v1;
        v2 = fmini_mulr((softclip((y1 + 128) >> 8) * 256) - s1, (uint32_t)G16);
        y2 = v2 + s1;
        s1 = y2 + v2;
        v3 = fmini_mulr((softclip((y2 + 128) >> 8) * 256) - s2, (uint32_t)G16);
        y3 = v3 + s2;
        s2 = y3 + v3;
        v4 = fmini_mulr((softclip((y3 + 128) >> 8) * 256) - s3, (uint32_t)G16);
        y4 = v4 + s3;
        s3 = y4 + v4;
        w = fmini_mulr(y4 - sx, (uint32_t)gx16);                           /* the lag pole follows the output */
        sx += w + w;
        /* HP: the stages are one-pole lows H; (1 - H)^4 = 1 - 4H + 6H^2 - 4H^3 + H^4 on the stages' outputs (linear, the states as they are) */
        o = hp ? xin - 4 * y1 + 6 * y2 - 4 * y3 + y4 : y4;
        out[i] += voice_amp2((((o + 128) >> 8) * trim12) >> 12, m, i);
    }
    v->ph[0] = ph0;
    v->ph[1] = ph1;
    v->ph[2] = ph2;
    v->s[0] = s0;
    v->s[1] = s1;
    v->s[2] = s2;
    v->s[3] = s3;
    v->s[4] = sx;
}

static const char *const FMINI_BANK_NAMES[] = {"A", "B"};
static const engine_t ENG_FMINI = {
    .name = "FMINI",
    .page_title = {"FILTER", "PATCH"},
    .edit = {
        {"CUT", 0, 0, 127, 61, F_PCT, 0},
        {"EMPH", 0, 0, 127, 44, F_PCT, 0},
        {"CNTR", 0, 0, 127, 51, F_PCT, 0},
        {"F.ATK", 0, 0, 127, 0, F_TIME, 0},
        {"F.DEC", 0, 0, 127, 79, F_TIME, 0},
        {"F.SUS", 0, 0, 127, 100, F_PCT, 0},
        {"BANK", FMINI_BANK_NAMES, 0, 1, 0, F_ENUM, 0},
        {"PATCH", 0, 0, 99, 0, F_INT, 0},
    },
    .presets = FMINI_PRESETS, .npresets = NELEM(FMINI_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E4},
    .poly = 4, .keep = 0x93,
    .note_on = fmini_note_on, .render = fmini_render, .block = fmini_block,
};
