/* SPDX-License-Identifier: GPL-3.0-only WITH GPL-3.0-linking-exception
 * Source: HAWK CHUA's firmware/src/waveguide.c by Plieuw and contributors.
 * Adapted for Skiff's per-voice rendering. Keeps the VOICE, FLUTE, BRASS
 * and REED routines with their support functions. Uses Skiff math
 * adapters and a fixed source contribution in place of HAWK's SCREAM. */
/* ------------------------------------------------------------------- VOICE --- */
#define HAWK_WG_NSEC 12                               /* sections at most (half the junctions of 24: the CPU) */
#define HAWK_WG_KMAX 2                                /* samples per section (a section: k samples each way) */
enum { HAWK_WV_CHILD, HAWK_WV_SOPRANO, HAWK_WV_ALTO, HAWK_WV_TENOR, HAWK_WV_BASS, HAWK_WV_COUNT };
static const struct {
    uint8_t n, k;                                /* sections, samples each: the tube n k / 44100 s one way (x 350 m/s) */
    uint8_t wah;                                 /* how much the mouth follows the loudness, /128 */
    uint8_t nasal;                               /* the glottis' reflection's floor, /128 (a "closed", nasal tone) */
} HAWK_WG_VS[HAWK_WV_COUNT] = {
    {8, 2, 40, 60},                          /* a child: 12 cm */
    {9, 2, 40, 60},                          /* soprano: 14 cm */
    {10, 2, 40, 60},                         /* alto: 15.5 cm */
    {11, 2, 40, 60},                         /* tenor: 17 cm */
    {12, 2, 40, 60},                         /* bass: 18.5 cm */
};
/* the area along the tube, glottis to lips, per vowel (relative, Q4; after Fant's measured shapes, simplified) */
#define HAWK_WG_NV 5
static const uint8_t HAWK_WG_SHAPE[HAWK_WG_NV][16] = {
    {19, 26, 38, 51, 58, 51, 38, 19, 10, 13, 26, 38, 26, 10, 5, 3},       /* U: lips rounded, nearly closed */
    {16, 22, 32, 38, 38, 32, 22, 16, 16, 26, 38, 48, 42, 29, 16, 10},     /* O */
    {16, 13, 10, 8, 8, 10, 14, 22, 35, 48, 58, 64, 67, 64, 58, 51},       /* A: the pharynx narrow, the mouth wide */
    {19, 29, 42, 51, 54, 48, 38, 29, 22, 16, 13, 13, 16, 22, 29, 32},     /* E */
    {22, 42, 58, 67, 70, 64, 48, 32, 16, 8, 5, 4, 5, 10, 19, 26},         /* I: the tongue high, front narrow */
};

typedef struct {
    int32_t f[HAWK_WG_NSEC][HAWK_WG_KMAX], b[HAWK_WG_NSEC][HAWK_WG_KMAX];   /* each section: its forward and backward waves */
    uint32_t p;                                  /* the place in the sections' lines (0 .. k - 1) */
    int32_t r[HAWK_WG_NSEC];                          /* the junctions' reflections (Q15), this block */
    int32_t lip, rad, glo;                       /* the lips' low-pass, the mouth's softening, the glottis' */
    int32_t env;                                 /* the input's loudness, Q20 */
    int32_t iav, oav, ag;                        /* the level: in, out (Q14 averages), the gain (Q12) */
    int32_t yh[3];                               /* the last half-rate outputs (the interpolation) */
} hawk_wg_throat_t;

/* a one-pole's coefficient (Q15) at the full rate -> the same in hertz at half the rate: 1 - (1 - a)^2 */
static inline int32_t hawk_wg_half(int32_t a) { return 2 * a - ((a * a) >> 15); }

/* the loudness of n samples of in (Q20) into *env: ~2 ms up, ~80 ms down (per sample) */
/* back to the full rate: the half-rate output t into y[i], y[i + 1]: the one before it, and the point halfway between
 * the two before it by a cubic (-1 9 9 -1) / 16 (the images over 11 kHz ~30 dB down, where halving the last two left
 * them: a grain on bowed and blown notes); a half-rate sample late */
static inline void hawk_wg_up(int32_t *h, int32_t t, int32_t *y, uint32_t i, uint32_t n)
{
    y[i] = (9 * (h[1] + h[2]) - h[0] - t) >> 4;
    if (i + 1u < n)
        y[i + 1u] = h[2];
    h[0] = h[1];
    h[1] = h[2];
    h[2] = t;
}
static int32_t hawk_wg_follow(int32_t *env, const int32_t *in, uint32_t n)
{
    uint32_t i;
    int32_t e = *env;
    for (i = 0; i < n; i++) {
        int32_t a = in[i] < 0 ? -in[i] : in[i];
        e += a > e ? (a - e) / 64 : -((e - a) / 4096) - (e > 0);
    }
    return *env = e < 0 ? 0 : e;
}

static void hawk_wg_throat_run(hawk_wg_throat_t *T, const int32_t *in, int32_t *y, uint32_t n, uint32_t animal, int32_t vowel,
                          int32_t bright, int32_t damp)
{
    uint32_t an = animal % HAWK_WV_COUNT, ns = HAWK_WG_VS[an].n, k = HAWK_WG_VS[an].k, i, j;
    int32_t env = hawk_wg_follow(&T->env, in, n), A[HAWK_WG_NSEC + 1], open, rg, rl, lp, ro;
    {   /* the areas: the vowel (0..127 across U O A E I), resampled to the sections; the mouth (the last quarter)
         * opened by the loudness as much as the animal does it */
        int32_t pos = vowel * 4 * 256 / 127, v = pos >> 8, fr = pos & 255;
        int32_t e = env > PX_ONE / 2 ? 256 : env / (PX_ONE / 512);   /* 0..256: loud at half scale */
        v = v > HAWK_WG_NV - 2 ? HAWK_WG_NV - 2 : v;
        if (pos >= (HAWK_WG_NV - 1) * 256)
            fr = 256;
        open = (128 - HAWK_WG_VS[an].wah) * 2 + HAWK_WG_VS[an].wah * (40 + e * 3 / 2) / 128;   /* /256 */
        for (j = 0; j < ns; j++) {
            uint32_t s = j * 15u * 16u / (ns - 1u), s0 = s >> 4, s1 = s0 < 15u ? s0 + 1u : 15u, sf = s & 15u;
            int32_t a0 = HAWK_WG_SHAPE[v][s0] * (16 - (int32_t)sf) + HAWK_WG_SHAPE[v][s1] * (int32_t)sf;
            int32_t a1 = HAWK_WG_SHAPE[v + 1][s0] * (16 - (int32_t)sf) + HAWK_WG_SHAPE[v + 1][s1] * (int32_t)sf;
            int32_t a = (a0 * (256 - fr) + a1 * fr) >> 8;
            if (j * 4u >= ns * 3u)
                a = a * open >> 8;
            A[j] = a < 40 ? 40 : a;              /* (never shut: a closed mouth would trap a whistle) */
        }
        for (j = 0; j + 1u < ns; j++)            /* Kelly-Lochbaum: r = (A_j - A_j+1) / (A_j + A_j+1) */
            T->r[j] = (A[j] - A[j + 1]) * 32767 / (A[j] + A[j + 1]);
    }
    rg = 22000 + HAWK_WG_VS[an].nasal * 40 + damp / 11;   /* the glottis: ~0.67 .. 0.99 (Q15) */
    rg = rg > 32440 ? 32440 : rg;
    rl = -(26000 + damp / 10);                   /* the lips (open end: inverted), 0.79 .. 0.99 */
    rl = rl < -32440 ? -32440 : rl;
    lp = hawk_wg_half(2500 + bright / 5);             /* the lips' low-pass, Q15: what reflects (the lows), ~0.4 .. 4 kHz */
    ro = hawk_wg_half(6800 + bright / 3);             /* the mouth's softening, Q15 */
    k = (k + 1u) / 2u;                           /* (half the rate: half the samples a section) */
    for (i = 0; i < n; i += 2u) {
        int32_t fo[HAWK_WG_NSEC], bo[HAWK_WG_NSEC], lo, x = i + 1u < n ? (in[i] + in[i + 1u]) >> 1 : in[i];
        uint32_t p = T->p;
        for (j = 0; j < ns; j++) {
            fo[j] = T->f[j][p];
            bo[j] = T->b[j][p];
        }
        for (j = 0; j + 1u < ns; j++) {          /* the junctions */
            int32_t w = px_m(T->r[j], fo[j] - bo[j + 1u], 15);
            T->f[j + 1u][p] = fo[j] + w;         /* (lossless junctions between lossy ends: bounded, unclamped) */
            T->b[j][p] = bo[j + 1u] + w;
        }
        T->glo += px_m(22041, bo[0] - T->glo, 15);   /* the glottis: soft (the highs lost there, ~3 kHz; hawk_wg_half(14000)) */
        T->f[0][p] = clamp((x >> 1) + px_m(rg, T->glo, 15), -16 * PX_ONE, 16 * PX_ONE);
        lo = fo[ns - 1u];                        /* the lips: the lows reflect (inverted), the highs radiate */
        T->lip += px_m(lp, lo - T->lip, 15);
        T->b[ns - 1u][p] = px_m(rl, T->lip, 15);
        /* out at the mouth: the wave arriving there (the radiation's loss of the lows left out: it would leave a
         * voice below its first formant nearly silent), its top softened (BRIGHTNESS: ~1.5 .. 12 kHz) */
        T->rad += px_m(ro, lo - T->rad, 15);
        hawk_wg_up(T->yh, T->rad, y, i, n);   /* (back to the full rate) */
        T->p = p + 1u >= k ? 0u : p + 1u;
    }
    {   /* the level: the tube's resonances lift some vowels and animals far above others; a slow automatic gain
         * (~0.1 s) holds the mouth at 3.6 x the input's level (the dry's, out of the FX: RZ_GAIN), x 1/8 .. 24 */
        uint32_t si = 0, so = 0;
        int32_t gt;
        for (i = 0; i < n; i++) {
            si += (uint32_t)(in[i] < 0 ? -in[i] : in[i]) >> 6;
            so += (uint32_t)(y[i] < 0 ? -y[i] : y[i]) >> 6;
        }
        T->iav += ((int32_t)(si / n) - T->iav) / 8;
        T->oav += ((int32_t)(so / n) - T->oav) / 8;
        gt = T->oav > 0 ? (int32_t)((uint32_t)T->iav * 14746u / (uint32_t)(T->oav > 0 ? T->oav : 1)) : 4096;   /* Q12 (32 bits; never / 0) */
        gt = gt < 512 ? 512 : gt > 98304 ? 98304 : gt;
        T->ag += (gt - T->ag) / 16;
        for (i = 0; i < n; i++)
            y[i] = clamp(px_m(y[i], T->ag, 12), -64 * PX_ONE, 64 * PX_ONE);
    }
}

/* ---------------------------------------------------- the jet (FLUTE, SYRINX) --- */
/* (the lines are clipped at +-5, not 4: -4.0 in Q20 is 0xFFC00000, which tools/build.py takes for a ROM address) */
static inline int32_t hawk_wg_jet(int32_t x)          /* STK's JetTable: x (x^2 - 1), the output clipped to +-1 (Q20) */
{
    int32_t c = clamp(x, -2 * PX_ONE, 2 * PX_ONE);
    return clamp(px_m(c, px_m(c, c, 20) - PX_ONE, 20), -PX_ONE, PX_ONE);
}
static inline int32_t hawk_wg_tap(const int32_t *l, uint32_t mask, uint32_t w, uint32_t d)   /* d samples back, whole */
{
    return l[(w - 1u - (d >> 16)) & mask];
}
static inline int32_t hawk_wg_read(const int32_t *l, uint32_t mask, uint32_t w, uint32_t d)   /* d samples back, Q16 */
{
    uint32_t t = w - (d >> 16);
    int32_t a = l[(t - 1u) & mask], b = l[(t - 2u) & mask];
    return a + px_m(b - a, (int32_t)(d & 0xFFFFu), 16);
}

/* ------------------------------------------------------------------- FLUTE --- */
#define HAWK_WG_BORE 1024u                            /* the bore's line (1.5 periods): 66 Hz and up (lower: up) */
#define HAWK_WG_JETL 512u
typedef struct {
    int32_t bore[HAWK_WG_BORE], jet[HAWK_WG_JETL];
    uint32_t w, rng;
    int32_t lp, dcx, dcy, env;
    int32_t ox, oy;                              /* the output's DC blocker (the breath's pressure is DC in the bore) */
    int32_t yh[3];                                  /* the last half-rate output */
} hawk_wg_flute_t;

/* f0 the note (cycles Q32); jet 0..127 its length (long .. overblowing); breath the noise; bright, damp Q16 */
static void hawk_wg_flute_run(hawk_wg_flute_t *F, const int32_t *in, int32_t *y, uint32_t n, uint32_t f0, int32_t jet,
                         int32_t breath, int32_t bright, int32_t damp)
{
    uint32_t i, per = f0 < 65536u ? 0xFFFFFFFFu : px_period(f0 > 0x40000000u ? 0x40000000u : f0) >> 1, bd, jd;   /* Q16,
                                                  * half-rate samples */
    int32_t env = hawk_wg_follow(&F->env, in, n), pr, g, loss, nz;
    while (per > (HAWK_WG_BORE * 2u / 3u - 8u) << 16)  /* fold up an octave */
        per >>= 1;
    bd = per > (4u << 16) ? per - (2u << 16) : 2u << 16;   /* the bore: a period (less the filters' delay) */
    jd = (uint32_t)(((uint64_t)bd * (uint32_t)(16384 - jet * 90)) >> 15);   /* 0.5 .. 0.15 of the bore: the
                                                  * fundamental (bending sharp), the octave, the twelfth */
    jd = jd > (HAWK_WG_JETL - 4u) << 16 ? (HAWK_WG_JETL - 4u) << 16 : jd < (2u << 16) ? 2u << 16 : jd;
    pr = env * 16 > PX_ONE * 13 / 10 ? PX_ONE * 13 / 10 : env * 16;   /* the breath: the loudness (STK blows at ~1.1 ..
                                                  * 1.3: it speaks from ~1/16 of full scale up) */
    g = hawk_wg_half(4900 + bright / 5);              /* the bore's one-pole (STK: pole 0.65): 0.85 .. 0.45, Q15 */
    loss = 30500 + (int32_t)(((int64_t)damp * 2260) >> 16);   /* 0.93 .. 1.0 */
    nz = breath / 2;                             /* the noise: 0 .. 0.5 of the pressure (STK: 0.15), Q16 */
    for (i = 0; i < n; i += 2u) {
        int32_t b = hawk_wg_read(F->bore, HAWK_WG_BORE - 1u, F->w, bd), t, pd, j, o;
        int32_t noise = px_m(px_m((int32_t)(px_rand(&F->rng) >> 11) - (1 << 20), pr, 20), nz, 16);
        int32_t x = i + 1u < n ? (in[i] + in[i + 1u]) >> 1 : in[i];
        F->lp += px_m(g, b - F->lp, 15);         /* the open end's reflection: low-passed, its DC blocked */
        t = F->lp;
        o = t - F->dcx + px_m(F->dcy, 32440, 15);   /* (32604 at the full rate) */
        F->dcx = t;
        F->dcy = o;
        t = px_m(o, loss, 15);
        pd = pr + noise + (x >> 3) - (t >> 1);   /* the breath (the voices on it), less the jet's reflection */
        F->jet[F->w & (HAWK_WG_JETL - 1u)] = clamp(pd, -5 * PX_ONE, 5 * PX_ONE);
        j = hawk_wg_jet(hawk_wg_tap(F->jet, HAWK_WG_JETL - 1u, F->w + 1u, jd));   /* (the jet: a whole-sample delay) */
        F->bore[F->w & (HAWK_WG_BORE - 1u)] = clamp(j + (t >> 1), -5 * PX_ONE, 5 * PX_ONE);
        F->w++;
        o = b - F->ox + px_m(F->oy, 32440, 15); /* out: the bore, its DC blocked (the breath is DC in it) */
        F->ox = b;
        F->oy = o;
        hawk_wg_up(F->yh, o, y, i, n);   /* (back to the full rate) */
    }
}


/* ------------------------------------------------------- the level (AGC) --- */
/* a slow automatic gain (~0.1 s), as the THROAT's: the output held at 3.6 x the input's level (the dry's, out of the
 * FX: RZ_GAIN), x 1/8 .. 24 (a self-oscillating model's level hangs on its breath and register, not on its input) */
static void hawk_wg_level(int32_t *iav, int32_t *oav, int32_t *ag, const int32_t *in, int32_t *y, uint32_t n)
{
    uint32_t si = 0, so = 0, i;
    int32_t gt;
    for (i = 0; i < n; i++) {
        si += (uint32_t)(in[i] < 0 ? -in[i] : in[i]) >> 6;
        so += (uint32_t)(y[i] < 0 ? -y[i] : y[i]) >> 6;
    }
    *iav += ((int32_t)(si / n) - *iav) / 8;
    *oav += ((int32_t)(so / n) - *oav) / 8;
    gt = *oav > 0 ? (int32_t)((uint32_t)*iav * 14746u / (uint32_t)(*oav > 0 ? *oav : 1)) : *ag;   /* Q12 (no
                                                  * output yet: as it is, no jump at the onset) */
    gt = gt < 512 ? 512 : gt > 98304 ? 98304 : gt;
    *ag += (gt - *ag) / 16;
    for (i = 0; i < n; i++)
        y[i] = clamp(px_m(y[i], *ag, 12), -64 * PX_ONE, 64 * PX_ONE);
}

/* the half-rate period of the note (Q16 samples), folded up by octaves until it fits in max samples */
static __attribute__((noinline)) uint32_t hawk_wg_per(uint32_t f0, uint32_t max)   /* (one division, not three) */
{
    uint32_t per = f0 < 65536u ? 0xFFFFFFFFu : px_period(f0 > 0x40000000u ? 0x40000000u : f0) >> 1;
    while (per > max << 16)
        per >>= 1;
    return per;
}

/* ------------------------------------------------------------------- BRASS --- */
/* Lips buzzing into a bore. The lips (after the measured lip-reed waveforms: a short pulse of air a period, the
 * shorter the harder they are blown, so a loud note is a bright note: brass's own sound) are a pulse at the note,
 * its width 0.5 of a period at a breath and down to ~0.1 at full breath x LIP (STRUCTURE: loose, mellow, a horn
 * .. tight, brassy, a trumpet); the bore a tube a period long that the pulse rings (a feedback comb: its modes the
 * harmonics; DAMPING its loss, BRIGHTNESS its walls' loss of the highs and the bell's radiation); MUTE
 * (POSITION): open bell .. a harmon mute (a narrow band-pass, the nasal "wah"). The voices' loudness is the
 * breath, their sound on it. (A hybrid: STK's lip-reed loop, tried first, drifted in pitch and jumped registers
 * at this rate; the pulse keeps every note in tune and the bore and the bell keep the brass.) */
#define HAWK_WG_BR 1024u
typedef struct {
    int32_t bore[HAWK_WG_BR];
    uint32_t w, ph, rng;
    int32_t lp, env, jit;
    int32_t bell, prv, mlo, mbd;                 /* the bell's low-pass; the mute's band-pass (Chamberlin) */
    int32_t ox, oy, iav, oav, ag, yh[3];
} hawk_wg_brass_t;

/* n / 127, truncated as C does, |n| < 2^37 (here < 2^35), without a 64-bit division (a library call on this core, every sample):
 * the high and low halves in turn, 32 bits each, each a multiply */
static inline int32_t hawk_wg_div127(int64_t n)
{
    uint64_t a = n < 0 ? (uint64_t)-n : (uint64_t)n;
    uint32_t hi = (uint32_t)(a >> 16), q1 = hi / 127u, q0 = ((hi - q1 * 127u) << 16 | ((uint32_t)a & 65535u)) / 127u;
    int32_t q = (int32_t)((q1 << 16) + q0);
    return n < 0 ? -q : q;
}
static void hawk_wg_brass_run(hawk_wg_brass_t *B, const int32_t *in, int32_t *y, uint32_t n, uint32_t f0, int32_t lip,
                         int32_t mute, int32_t bright, int32_t damp)
{
    uint32_t i, per = hawk_wg_per(f0, HAWK_WG_BR - 8u), bd = per > (3u << 16) ? per - (1u << 15) : 2u << 16;
    uint32_t inc = f0 > 0x20000000u ? 0x40000000u : f0 * 2u, wq;   /* (the half rate's increment) */
    int32_t env = hawk_wg_follow(&B->env, in, n), pr, br, R, g, gb, mf;
    pr = env * 16 > PX_ONE ? PX_ONE : env * 16;  /* the breath, 0..1 */
    br = pr >> 5;                                /* 0..32768 */
    wq = 0x80000000u - (uint32_t)((((int64_t)br * (9830 + lip * 128)) >> 15) << 16);   /* the pulse's width: a turn
                                                  * x 0.5 .. ~0.1 */
    R = 16000 + (int32_t)(((int64_t)damp * 13000) >> 16);   /* the bore's feedback: 0.49 .. 0.89 */
    g = hawk_wg_half(9000 + bright / 4);              /* the walls' low-pass in the loop */
    gb = hawk_wg_half(7000 + bright / 4);             /* the bell */
    mf = 9000;                                   /* the mute: 2 sin(pi f / FS_half), ~1 kHz */
    for (i = 0; i < n; i += 2u) {
        int32_t x = i + 1u < n ? (in[i] + in[i + 1u]) >> 1 : in[i], lips, b, o, t;
        uint32_t ph = B->ph;
        B->ph += inc + (uint32_t)(B->jit >> 4);  /* (a little jitter: the lips are not a clock) */
        if (B->ph < ph)                          /* (a new period: the jitter's next value) */
            B->jit = (int32_t)(px_rand(&B->rng) >> 20) - 2048;
        lips = 0;
        if (ph < wq) {                           /* the puff: a raised cosine over wq */
            uint32_t q = (ph / (wq >> 16)) << 16;   /* (32 bits: ph < wq) */
            lips = (PX_ONE - (px_sin(q + 0x40000000u) >> 10)) >> 1;
        }
        lips = px_m(lips, pr, 20) + x / 8;
        b = hawk_wg_read(B->bore, HAWK_WG_BR - 1u, B->w, bd);
        B->lp += px_m(g, b - B->lp, 15);
        o = lips + px_m(B->lp, R, 15);
        B->bore[B->w & (HAWK_WG_BR - 1u)] = clamp(o, -8 * PX_ONE, 8 * PX_ONE);
        B->w++;
        B->bell += px_m(gb, o - B->bell, 15);    /* out: the bell, radiating the change more the brighter */
        o = clamp(B->bell / 2 + (B->bell - B->prv) * (1 + bright / 8192), -32 * PX_ONE, 32 * PX_ONE);
        B->prv = B->bell;
        {   /* the mute: a band-pass (Q ~4) mixed in as MUTE turns, the open bell out */
            int32_t hi;
            B->mlo += px_m(mf, B->mbd, 15);
            hi = o - B->mlo - px_m(8192, B->mbd, 15);
            B->mbd = clamp(B->mbd + px_m(mf, hi, 15), -16 * PX_ONE, 16 * PX_ONE);
            B->mlo = clamp(B->mlo, -16 * PX_ONE, 16 * PX_ONE);
            if (mute)                            /* (MUTE 0: the open bell as it is) */
                o = clamp(hawk_wg_div127((int64_t)o * (127 - mute) + (int64_t)B->mbd * mute * 2), -32 * PX_ONE,
                             32 * PX_ONE);
        }
        t = o - B->ox + px_m(B->oy, 32440, 15);
        B->ox = o;
        B->oy = t;
        hawk_wg_up(B->yh, t, y, i, n);   /* (back to the full rate) */
    }
    hawk_wg_level(&B->iav, &B->oav, &B->ag, in, y, n);
}


/* -------------------------------------------------------------------- REED --- */
/* A single or double reed on a bore (after STK's Clarinet and Saxofony, Cook / Scavone): the reed a valve, its
 * opening offset + slope x the pressure across it (REED, STRUCTURE: soft .. hard: dark .. bright, buzzing). BORE
 * (POSITION): below 16 a cylinder half a period long (the clarinet: closed at the reed, odd harmonics); above, a
 * cone a period long (sax, oboe, bassoon: all the harmonics), the reed blowing into it at 0.06 .. 0.45 of its
 * length (the conical bore's shape: low, a sax; high, the narrow double reeds). The voices' loudness is the
 * breath, their sound on it; BRIGHTNESS the bell; DAMPING the bore's loss */
#define HAWK_WG_RD 512u
typedef struct {
    int32_t d0[HAWK_WG_RD], d1[HAWK_WG_RD];
    uint32_t w, rng;
    int32_t lp, env, bell, prv;
    int32_t ox, oy, iav, oav, ag, yh[3];
} hawk_wg_reed_t;

static void hawk_wg_reed_run(hawk_wg_reed_t *D, const int32_t *in, int32_t *y, uint32_t n, uint32_t f0, int32_t reed,
                        int32_t bore, int32_t bright, int32_t damp)
{
    uint32_t i, cyl = bore < 16, per = hawk_wg_per(f0, HAWK_WG_RD - 8u), base, l0, l1;
    int32_t env = hawk_wg_follow(&D->env, in, n), pr, R, g, gb, slope;
    base = cyl ? per / 2u : per;
    base = base > (4u << 16) ? base - (cyl ? 1u << 16 : 269u << 9) : 2u << 16;   /* (less the filters' lag:
                                                  * measured) */
    l1 = cyl ? 0u : (uint32_t)(((uint64_t)base * (uint32_t)(4000 + (bore - 16) * 233)) >> 16);   /* 0.06 .. 0.45 */
    l1 = !cyl && l1 < (1u << 16) ? 1u << 16 : l1;
    l0 = base - l1;
    pr = env * 16 > PX_ONE ? PX_ONE : env * 16;
    pr = pr * (cyl ? 12 : 10) / 16;             /* (STK blows them at 0.55 .. 0.85) */
    slope = (cyl ? -1 : 1) * (int32_t)(PX_ONE * 22 / 100 + (uint32_t)reed * (PX_ONE / 700));   /* 0.22 .. 0.4 */
    R = 30000 + (int32_t)(((int64_t)damp * 2600) >> 16);   /* 0.92 .. 0.995 (STK: 0.95) */
    g = hawk_wg_half(22000 + bright / 10);             /* the reflection's low-pass */
    gb = hawk_wg_half(9000 + bright / 4);             /* the bell */
    for (i = 0; i < n; i += 2u) {
        int32_t x = i + 1u < n ? (in[i] + in[i + 1u]) >> 1 : in[i];
        int32_t noise = px_m((int32_t)(px_rand(&D->rng) >> 11) - (1 << 20), pr, 24);   /* (the breath: 1/16) */
        int32_t p = pr + noise + x / 8, b = hawk_wg_read(D->d0, HAWK_WG_RD - 1u, D->w, l0), o, pd, rt, t;
        D->lp += px_m(g, b - D->lp, 15);
        if (cyl) {                               /* STK Clarinet */
            pd = -px_m(D->lp, R, 15) - p;
            rt = clamp(PX_ONE * 7 / 10 + px_m(slope, pd, 20), -PX_ONE, PX_ONE);
            D->d0[D->w & (HAWK_WG_RD - 1u)] = clamp(p + px_m(pd, rt, 20), -5 * PX_ONE, 5 * PX_ONE);
            o = b;
        } else {                                 /* STK Saxofony */
            int32_t tmp = -px_m(D->lp, R, 15);
            o = tmp - hawk_wg_read(D->d1, HAWK_WG_RD - 1u, D->w, l1);
            pd = p - o;
            rt = clamp(PX_ONE * 7 / 10 + px_m(slope, pd, 20), -PX_ONE, PX_ONE);
            D->d1[D->w & (HAWK_WG_RD - 1u)] = clamp(tmp, -5 * PX_ONE, 5 * PX_ONE);
            D->d0[D->w & (HAWK_WG_RD - 1u)] = clamp(p - px_m(pd, rt, 20) - tmp, -5 * PX_ONE, 5 * PX_ONE);
        }
        D->w++;
        t = o - D->prv;                          /* the bell radiates the pressure's change (a high-pass: the
                                                  * reed's square wave's harmonics heard) */
        D->prv = o;
        o = t * 4;
        D->bell += px_m(gb, o - D->bell, 15);
        o = D->bell;
        t = o - D->ox + px_m(D->oy, 32440, 15);
        D->ox = o;
        D->oy = t;
        hawk_wg_up(D->yh, t, y, i, n);   /* (back to the full rate) */
    }
    hawk_wg_level(&D->iav, &D->oav, &D->ag, in, y, n);
}
