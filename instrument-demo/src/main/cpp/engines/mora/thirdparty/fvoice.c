/* SPDX-License-Identifier: GPL-3.0-only */
#include "fvoice.h"
#include "fvoice_tables.h"
/* Sharp-closure glottal wavetable -> pre-emphasis -> brightness one-pole (+ glottal-gated breath noise)
   -> 6 parallel 2-pole resonators (F1, nasal, F2, F3, singer's formant, air; alternating sign) -> gain.
   All 32-bit; coefficient, LFO, glide, envelope, vowel-morph and "human" work runs once per FV_BLK frames.
   Morph: coefficients move by a convex combination (cur += (tgt-cur)*k, 0<k<1) and the
   2nd-order stability region {|a2|<1, |a1|<1-a2} is convex, so every in-between filter is stable.
   The high-pitch zone table is blended in by the same convex rule.
   Consonants (fv_note_on_mora): a per-class table of up to 4 phases (fv_cph/fv_cpn) drives, once per block, the formant
   shape (vowels + voice bar, nasals, flap), voicing (glottal gain vg), output level cl, a noise band (LFSR through one
   2-pole resonator, added after the envelope: bursts, frication) and aspiration (LFSR through the vowel formants).
   A release burst reaches its level in one block and decays exponentially to the next phase's noise level (a flat block of noise
   at vowel level sounds like a crackle of white noise).
   Every level is eased toward its phase target and ramped linearly inside a block. Durations are counted in blocks, so
   they do not depend on pitch or tempo. Cost: vg 1 + aspiration 1 multiplies always, noise band 4 only while it sounds. */
#define CPH(c, i) (fv_cph + ((c) * FV_NPH + (i)) * 6)    /* phase rows of the generated tables */
#define CPN(c, i) (fv_cpn + ((c) * FV_NPH + (i)) * 3)
#define PMAX 45055
#define P_C5 24576              /* pitch units of MIDI 72 */
static void quiet(FvVoice *v) { v->lp = v->sp = v->np = v->ny1 = v->ny2 = 0; for (int i = 0; i < FV_NR; i++) v->y1[i] = v->y2[i] = 0; }
static int32_t clampi(int32_t x, int32_t lo, int32_t hi) { return x < lo ? lo : x > hi ? hi : x; }
static uint32_t xs(uint32_t x) { x ^= x << 13; x ^= x >> 17; x ^= x << 5; return x; }
static int32_t decay(int32_t x, int32_t k) {   /* x *= 1-k/32768, never sticks, never crosses zero */
    int32_t d = ((x < 0 ? -x : x) * k) >> 15; if (!d) d = 1;
    return x > 0 ? x - d : x < 0 ? x + d : 0;
}
static void load(FvVoice *v, int snap, int wz) {
    int sh = v->cshp == FV_SHV ? v->vsh : v->cshp, c = v->chr < FV_NCHR ? v->chr : FV_CHR0;   /* c: voice character (formant size) */
    for (int i = 0; i < FV_NR; i++) for (int j = 0; j < 3; j++) {
        int32_t t0 = fv_vow[c][0][sh][i][j], t1 = fv_vow[c][1][sh][i][j];
        if (v->chf && c + 1 < FV_NCHR) {          /* fine formant shift: toward the next character (a convex mix: stable) */
            t0 += ((fv_vow[c + 1][0][sh][i][j] - t0) * v->chf) / 100;
            t1 += ((fv_vow[c + 1][1][sh][i][j] - t1) * v->chf) / 100;
        }
        int32_t t = (t0 + (((t1 - t0) * wz) >> 4)) * 16;
        int32_t d = t - v->cur[i][j], k = ((d >> 4) * (int32_t)v->km) >> 8;
        v->cur[i][j] = snap ? t : v->cur[i][j] + (k ? k : d > 0 ? 1 : d < 0 ? -1 : 0);   /* arrives exactly */
    }
}
static int32_t ease(int32_t x, int32_t t, int32_t k) {   /* x moves toward t by k/32768 of the gap; close enough = arrived */
    int32_t d = t - x;
    return d > -32 && d < 32 ? t : x + ((d * k) >> 15);
}
static void ramp(int32_t *x, int32_t *s, int32_t t, int32_t k) {   /* x -> t eased per block, as a per-sample step */
    int32_t d = t - *x, e = ((d * k) >> 15) >> 4;
    if (d > -32 && d < 32) { *x = t; e = 0; } else if (!e) e = d > 0 ? 1 : -1;
    *s = e;
}
static void fv_pop(FvVoice *v, uint8_t *q, uint8_t *n) {   /* the next queued consonant class becomes the current one */
    v->cons = q[0]; v->cph = 0;
    for (int i = 1; i < *n; i++) q[i - 1] = q[i];
    (*n)--;
}
static void enter(FvVoice *v) {                 /* load phase v->cph of class v->cons; past the last phase the consonant is over */
    const uint8_t *p = CPH(0, 0);
    const uint16_t *q = CPN(0, 0);
    int32_t vs = 8192 + v->velg * 193;
    for (;;) {
        const uint8_t *r = v->cph < FV_NPH && v->cons < FV_NCLS ? CPH(v->cons, v->cph) : 0;
        if (r && r[0]) {
            if (v->oqn && r[2] >> 3 & 1) { fv_pop(v, v->oq, &v->oqn); continue; }      /* a cluster: the next onset, not the vowel */
            if (v->kqn && r[0] == 255) { fv_pop(v, v->kq, &v->kqn); continue; }        /* a coda cluster: on to the next */
            p = r; q = CPN(v->cons, v->cph); v->cleft = p[0];
            if (p[0] != 255 && v->cscale != 100) { int32_t n = (p[0] * v->cscale + 50) / 100; v->cleft = (uint8_t)(n < 1 ? 1 : n > 254 ? 254 : n); }
            if (v->coda && p[0] == 255) { v->gated = 0; v->coda = 0; }               /* the coda's held end: release */
            break;
        }
        if (v->oqn) { fv_pop(v, v->oq, &v->oqn); continue; }
        if (v->kqn) { fv_pop(v, v->kq, &v->kqn); continue; }
        if (v->coda) { v->gated = 0; v->coda = 0; }
        v->cons = 0; break;
    }
    v->cshp = p[1]; v->lip = p[2] >> 2 & 1; v->tail = p[2] >> 3 & 1; v->km = fv_kmt[p[2] & 3];
    if (v->vscale != 100) { int32_t k = v->km * v->vscale / 100; v->km = (uint16_t)(k < 1 ? 1 : k > 2048 ? 2048 : k); }
    v->vgt = p[3] << 9; v->clt = p[4] << 8;
    if (v->cshp == FV_NAS0 || v->cshp == FV_NAS1) { v->clt = (v->clt * fv_nbo[clampi((v->ptgt * 3) >> 10, 0, 127)]) >> 12; if (v->clt > 40000) v->clt = 40000; }
    v->nlt = (int32_t)(q[0] * vs) >> 14; v->apt = q[1] << 1; v->nkd = q[2]; v->nab = q[2] != 0;
    if (v->clevel != 100) { v->nlt = v->nlt * v->clevel / 100; v->apt = v->apt * v->clevel / 100; }
    if (p[5]) for (int i = 0; i < 3; i++) v->nbc[i] = fv_nbt[((p[5] - 1) * 5 + v->vowel) * 3 + i];
}
void fv_init(FvVoice *v) {
    for (uint8_t *b = (uint8_t *)v, *e = b + sizeof *v; b < e; b++) *b = 0;
    v->vib_rate = 55; v->vib_delay = 150; v->porta = 60; v->attack = 8; v->release = 60;
    v->breath = 6; v->bright = 100; v->noise = 0x12345678u; v->rng = 0x9E3779B9u; v->velg = 100;
    v->human = 80; v->scoop = 40; v->scoop_ms = 75; v->chr = FV_CHR0;
    v->tension = 50; v->cscale = v->vscale = 100; v->clevel = 100;
    v->vg = v->vgt = 65536; v->cl = v->clt = 32768; v->km = FV_KM; v->cshp = FV_SHV; v->vsh2 = 255;
    load(v, 1, 0);
}
void fv_set_vowel(FvVoice *v, uint8_t vw) { v->vowel = vw > FV_O ? FV_O : vw; v->vsh = v->vowel; v->vsh2 = 255; v->dgl = 0; }
#define FV_DGL_BLK 413             /* a diphthong glides to its second shape 150 ms after the vowel starts (or at the coda) */
#define FV_KM_GLIDE 25             /* its morph: tau ~60 ms (4096 * (1 - exp(-block / 60 ms))) */
static void fv_start(FvVoice *v, uint8_t note, uint8_t cons, uint8_t vel) {
    int32_t n = (int32_t)(note > 127 ? 127 : note) + v->transpose;
    int32_t p = fv_notep[clampi(n, 0, 127)];
    int sounding = v->gated || v->env > 0;
    v->velg = (uint8_t)(vel > 127 ? 127 : vel);
    v->coda = 0; v->kqn = 0;
    v->ptgt = p; v->cons = cons < FV_NCLS ? cons : 0; v->cph = 0; enter(v);
    if (!sounding) {
        v->pcur = p; quiet(v); v->nl = v->nls = 0; v->vg = v->vgt; v->cl = v->clt; v->vgs = v->aps = 0; v->ap = v->apt;
        load(v, 1, clampi((p - P_C5) >> 8, 0, 16));
    }
    if (!v->gated) {
        v->vt = 0; v->lfo = 0; v->ovt = v->ovo = v->ova = 0; v->ovd = 0;
        v->sc = -(((int32_t)(v->scoop > 100 ? 100 : v->scoop) * 873) >> 8);   /* onset scoop from below */
    } else {                                    /* legato leap: arm a small overshoot that fires as the glide arrives */
        int32_t d = p - v->pcur, m = d < 0 ? -d : d;
        if (m >= 1024 && v->human) {
            m = clampi(m >> 6, 0, 60) * v->human >> 7;
            v->ova = d < 0 ? -m : m; v->ovd = (uint16_t)((v->porta * 1693) >> 10); v->ovt = 0;
            if (!v->ovd) { v->ovt = v->ova; v->ova = 0; }
        }
    }
    v->ptgt = p; v->gated = 1;
}
void fv_note_on_mora(FvVoice *v, uint8_t note, uint8_t cons, uint8_t vw, uint8_t vel) {
    fv_set_vowel(v, vw); v->oqn = 0; fv_start(v, note, cons, vel);
}
void fv_note_on(FvVoice *v, uint8_t note, uint8_t vw, uint8_t vel) { fv_note_on_mora(v, note, FC_NONE, vw, vel); }
static uint8_t fv_ep_on(uint8_t e) { return e < FV_NEP ? fv_epon[e] : 0; }
static uint8_t fv_ep_co(uint8_t e) { return e < FV_NEP ? fv_epco[e] : 0; }
void fv_note_on_syl(FvVoice *v, uint8_t note, const FvSyl *s, uint8_t vel) {
    uint8_t sh = s->v < FV_NEP ? fv_epv[s->v] : 255, c[3], n = 0;
    if (sh == 255) sh = fv_epv[FV_EP_AX];                  /* no vowel (a syllabic consonant): a schwa carries it */
    v->vsh = sh; v->vowel = fv_shcls[sh];
    v->vsh2 = s->v < FV_NEP ? fv_epv2[s->v] : 255; v->dgl = v->vsh2 != 255 ? FV_DGL_BLK : 0;
    for (int i = 0; i < 3; i++) { uint8_t k = fv_ep_on(s->on[i]); if (k) c[n++] = k; }
    v->oqn = 0;
    for (int i = 1; i < n; i++) v->oq[v->oqn++] = c[i];
    fv_start(v, note, n ? c[0] : FC_NONE, vel);
}
static void fv_glide(FvVoice *v) {                 /* a diphthong's second shape */
    if (v->vsh2 != 255) { v->vsh = v->vsh2; v->vowel = fv_shcls[v->vsh]; v->vsh2 = 255; if (!v->cons) v->km = FV_KM_GLIDE; }
    v->dgl = 0;
}
void fv_note_off_syl(FvVoice *v, const FvSyl *s) {
    uint8_t c[4], n = 0;
    for (int i = 0; i < 4; i++) { uint8_t k = fv_ep_co(s->co[i]); if (k) c[n++] = k; }
    if (!v->gated) return;
    fv_glide(v);
    if (!n) { v->gated = 0; return; }
    v->oqn = 0; v->kqn = 0;
    for (int i = 1; i < n; i++) v->kq[v->kqn++] = c[i];
    v->coda = 1; v->cons = c[0]; v->cph = 0; enter(v);
}
static uint32_t fv_blocks_ms(uint32_t b) { return (b * FV_BLK * 1000 + 22050) / 44100; }
uint32_t fv_syl_onset_ms(const FvSyl *s) {
    uint32_t t = 0;
    for (int i = 0; i < 3; i++) { uint8_t k = fv_ep_on(s->on[i]); if (k) t += fv_cons_ms(k); }
    return t;
}
uint32_t fv_syl_coda_ms(const FvSyl *s) {
    uint32_t b = 0;
    for (int i = 0; i < 4; i++) {
        uint8_t k = fv_ep_co(s->co[i]);
        for (int j = 0; k && j < FV_NPH; j++) { const uint8_t *p = CPH(k, j); if (!p[0] || p[0] == 255) break; b += p[0]; }
    }
    return fv_blocks_ms(b);
}
uint32_t fv_cons_ms(uint8_t cons) {
    uint32_t b = 0;
    for (int i = 0; cons < FV_NCLS && i < FV_NPH; i++) {
        const uint8_t *p = CPH(cons, i);
        if (!p[0] || p[0] == 255 || p[2] >> 3 & 1) break;
        b += p[0];
    }
    return fv_blocks_ms(b);
}
void fv_note_off(FvVoice *v) { v->gated = 0; }
static void block(FvVoice *v) {
    int32_t h = v->human;
    if (v->cons && v->cleft != 255 && !--v->cleft) { v->cph++; enter(v); }
    if (v->dgl && !v->cons && !--v->dgl) fv_glide(v);
    /* deterministic slow drift / jitter / shimmer: three smoothed LFSR random walks */
    v->rng = xs(v->rng); v->dr[0] += ((int32_t)(int16_t)(v->rng >> 16) - v->dr[0]) >> 8;
    v->rng = xs(v->rng); v->dr[1] += ((int32_t)(int16_t)(v->rng >> 16) - v->dr[1]) >> 5;
    v->rng = xs(v->rng); v->dr[2] += ((int32_t)(int16_t)(v->rng >> 16) - v->dr[2]) >> 5;
    int32_t jit = clampi(((v->dr[0] * h) >> 14) + ((v->dr[1] * h) >> 16), -34, 34);
    int32_t wob = (v->dr[0] * h) >> 14, dwb = (v->dr[1] * h) >> 14;   /* vibrato rate / depth wander */
    /* vibrato: sine LFO in pitch units, delayed then faded in over 512 blocks (~190 ms) */
    v->lfo += (uint32_t)(((uint32_t)FV_LFOK * v->vib_rate) >> 8) * (uint32_t)(256 + wob);
    if (v->vt < 65535) v->vt++;
    int32_t gv = clampi((int32_t)v->vt - ((v->vib_delay * 2822) >> 10), 0, 512);
    int32_t vd = (((int32_t)v->vib_depth * 873) >> 8) * (256 + dwb) >> 8;
    int32_t vib = (((int32_t)fv_sin[v->lfo >> 24] * vd) >> 15) * gv >> 9;
    /* portamento: exponential glide in the log-pitch domain */
    int32_t d = v->ptgt - v->pcur, st = (d * (int32_t)fv_kpor[v->porta > 510 ? 255 : v->porta >> 1]) >> 15;
    if (!st) st = (d > 0) - (d < 0);
    v->pcur += st;
    /* onset scoop and leap overshoot */
    if (v->sc) v->sc = decay(v->sc, fv_kpor[v->scoop_ms > 510 ? 255 : v->scoop_ms >> 1]);
    if (v->ovd && !--v->ovd) { v->ovt = v->ova; v->ova = 0; }
    if (v->ovt) v->ovt = decay(v->ovt, fv_kpor[20]);
    v->ovo += (v->ovt - v->ovo) >> 2;
    int32_t p = clampi(v->pcur + vib + jit + v->sc + v->ovo + v->tune, 0, PMAX);
    int32_t i = (p >> 4) & 255;
    v->inc = (uint32_t)(fv_inc[i] + (((int32_t)(fv_inc[i + 1] - fv_inc[i]) * (p & 15)) >> 4)) << (p >> 12);
    int32_t wz = clampi((v->pcur - P_C5) >> 8, 0, 16);
    load(v, 0, wz);
    for (int a = 0; a < FV_NR; a++) { v->r[a].g = v->cur[a][0] >> 4; v->r[a].a1 = v->cur[a][1] >> 4; v->r[a].a2 = v->cur[a][2] >> 4; }
    /* envelope: linear attack, exponential release (floor of 1/block ends the tail) */
    if (v->gated) { if (v->env < 32767) { v->env += fv_katk[v->attack > 510 ? 255 : v->attack >> 1]; if (v->env > 32767) v->env = 32767; } }
    else if (v->env > 0) {
        uint32_t k = ((uint32_t)v->env * fv_krel[v->release > 510 ? 255 : v->release >> 1]) >> 16;
        v->env -= k ? (int32_t)k : 1;
    }
    int32_t tg = (v->env * (8192 + v->velg * 193)) >> 16;                 /* half scale: pgain carries the other 6 dB */
    tg = (tg * (32768 + ((v->dr[2] * h) >> 9))) >> 15;                    /* shimmer */
    v->cl = ease(v->cl, v->clt, FV_KL); tg = (tg * v->cl) >> 15;          /* consonant level */
    if (v->nab) { v->nls = (v->nlt - v->nl) >> 4; v->nab = 0; }        /* release burst: full level within one block, */
    else { if (v->nkd) v->nlt -= ((v->nlt >> 4) * v->nkd) >> 11; ramp(&v->nl, &v->nls, v->nlt, FV_KN); }   /* then it decays */
    ramp(&v->vg, &v->vgs, v->vgt, FV_KV); ramp(&v->ap, &v->aps, v->apt, FV_KN);
    v->nzon = v->nl || v->nls; if (!v->nzon) v->ny1 = v->ny2 = 0;
    tg = clampi((tg * fv_pgain[clampi((v->pcur * 3) >> 10, 0, 127)]) >> 12, 0, 49152);
    /* Evaluation-only lift: voiced vowel path, never the separate consonant noise band.
       Gate calibration to its measured character; the default (0) executes the old gain exactly. */
    if (v->level_comp && v->chr == FV_CHR0 && !v->chf && !fv_cons(v)) {
        int32_t cp = clampi(v->pcur - P_C5, 0, 8192), ni = cp * 3 >> 10;
        int32_t frac = cp * 3 - (ni << 10), gain = fv_comp[ni * 5 + v->vowel];
        if (ni < 24) gain += ((fv_comp[(ni + 1) * 5 + v->vowel] - gain) * frac) >> 10;
        gain = 4096 + (gain - 4096) * (v->level_comp > 100 ? 100 : v->level_comp) / 100;
        tg = clampi((tg * gain) >> 12, 0, 98304);
    }
    int32_t df = tg - v->gcur;
    if (df > -16 && df < 16) { v->gcur = tg; v->gstep = 0; } else v->gstep = df >> 4;
}
void fv_render(FvVoice *v, int32_t *out, uint32_t n) {
    while (n) {
        if (!v->left) {
            if (!v->gated && !v->env && !v->gcur && !v->nl && !v->nlt) { quiet(v); v->cons = 0; v->cph = 0; enter(v); return; }
            block(v); v->left = FV_BLK;
        }
        uint32_t m = n < v->left ? n : v->left;
        v->left = (uint8_t)(v->left - m); n -= m;
        /* brightness and pre-emphasis fall with pitch above C5 (less aliasing, no harshness up high) */
        int32_t sw = clampi((v->pcur - P_C5) >> 8, 0, 32);
        int32_t mx = clampi((v->pcur - P_C5) >> 7, 0, 32);   /* sharp -> soft pulse crossfade */
        int32_t kb = 8000 + v->bright * 190, bg = v->breath, pe = v->bright >> 2;
        kb -= (kb * sw) >> 7; pe -= (pe * sw) >> 6;
        if (v->tension < 50) { int32_t t = 50 - v->tension; mx = clampi(mx + t * 32 / 50, 0, 32); kb -= kb * t / 100; }   /* softer, darker */
        else if (v->tension > 50) { int32_t t = v->tension - 50; pe += t * 2; kb = clampi(kb + t * 200, 0, 32767); }   /* pressed: more edge */
        int32_t fa = 32768 - v->fry * 229, wv = 32768 - v->whisper * 327, wg = v->whisper * 58;   /* fry: odd pulses x0.3 at 100; whisper */
        uint8_t fc = v->fcyc;
        uint32_t ph = v->ph, inc = v->inc, ns = v->noise;
        int32_t lp = v->lp, g = v->gcur, sp = v->sp, np = v->np, vg = v->vg, vgs = v->vgs, ap = v->ap, aps = v->aps;
        int32_t nl = v->nl, nls = v->nls, ny1 = v->ny1, ny2 = v->ny2, nz0 = v->nbc[0], nz1 = v->nbc[1], nz2 = v->nbc[2], nzon = v->nzon;
        for (uint32_t f = 0; f < m; f++, out += 2) {
            ph += inc;
            if (ph < inc) fc ^= 1;                       /* a new glottal period */
            uint32_t gi = ph >> 24; int32_t fr = (int32_t)((ph >> 16) & 255);
            int32_t a = fv_glot[gi], s = a + (((fv_glot[gi + 1] - a) * fr) >> 8);
            int32_t b2 = fv_glot2[gi], s2 = b2 + (((fv_glot2[gi + 1] - b2) * fr) >> 8);
            s = (s + (((s2 - s) * mx) >> 5)) >> 2;
            if (fc && fa != 32768) s = (s * fa) >> 15;
            lp += ((s + (((s - sp) * pe) >> 6) - lp) * kb) >> 15; sp = s;
            ns ^= ns << 13; ns ^= ns >> 17; ns ^= ns << 5;
            int32_t nz = ((int16_t)(ns >> 16) * (a < 0 ? -a : a)) >> 15;   /* noise gated by the glottal phase, */
            int32_t wn = (int16_t)(ns >> 16);
            int32_t vo = (lp * (vg >> 4)) >> 12;
            if (wg) vo = ((vo * wv) >> 15) + ((wn * wg) >> 15);   /* whisper: voicing out, breath in */
            int32_t x = vo + (((nz - np) * bg) >> 9) + ((wn * (ap >> 4)) >> 12), sum = 0; np = nz;   /* first-difference high-passed; voicing; aspiration */
            vg += vgs; ap += aps;
            for (int q = 0; q < FV_NR; q++) {
                int32_t y = (v->r[q].g * x + v->r[q].a1 * v->y1[q] + v->r[q].a2 * v->y2[q]) >> 14;
                y = clampi(y, -32767, 32767);
                v->y2[q] = v->y1[q]; v->y1[q] = y; sum += y;
            }
            g += v->gstep;
            int32_t bounded = clampi(sum, -32767, 32767);
            /* The experimental +6 dB path can exceed Q16 unity: halve gain before
               multiplying to keep the signed product inside 32 bits. */
            int32_t o = clampi(g > 65535 ? (bounded * (g >> 1)) >> 14 : (bounded * g) >> 15, -32767, 32767);
            if (nzon) {                                 /* consonant noise band, after the envelope */
                int32_t fn = clampi((nz0 * wn + nz1 * ny1 + nz2 * ny2) >> 14, -32767, 32767);
                ny2 = ny1; ny1 = fn; nl += nls;
                o = clampi(o + ((fn * (nl >> 4)) >> 12), -32767, 32767);
            }
            out[0] += o; out[1] += o;
        }
        v->fcyc = fc;
        v->ph = ph; v->noise = ns; v->lp = lp; v->gcur = g; v->sp = sp; v->np = np; v->vg = vg; v->ap = ap; v->ny1 = ny1; v->ny2 = ny2;
        if (nzon) v->nl = nl;
    }
}
uint8_t fv_level(const FvVoice *v) { return (uint8_t)(v->env >> 7); }
uint8_t fv_mouth(const FvVoice *v) { return v->env < 768 || v->lip ? 5 : v->vowel; }
uint8_t fv_cons(const FvVoice *v) { return (v->gated || v->env) && !v->tail ? v->cons : 0; }
uint8_t fv_viseme(const FvVoice *v) {
    if (v->env < 768 && !v->nzon) return FVV_REST;
    if (v->lip) return FVV_MBP;
    uint8_t c = fv_cons(v);
    if (c && fv_clvis[c] != 255) return fv_clvis[c];
    uint8_t sh = v->cshp == FV_SHV ? v->vsh : v->cshp;
    return sh < FV_NSH ? fv_shvis[sh] : FVV_REST;
}
