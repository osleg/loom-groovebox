/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from HAWK CHUA's synth.c and noise.c by Plieuw and contributors,
 * licensed upstream under GPL-3.0-only WITH GPL-3.0-linking-exception. */
#include "thirdparty/chua_tables.h"
#include "thirdparty/hawk_waveguide.inc"

#define HAWK_POLY 4u
#define HAWK_Q20 (1 << 20)
#define HAWK_M0 (-74898)
#define HAWK_M1 (-46811)
#define HAWK_E2 (3 * HAWK_Q20)
#define HAWK_G1 ((int32_t)(((int64_t)HAWK_M0 * HAWK_Q20) >> 16))
#define HAWK_G2 (HAWK_G1 + (int32_t)(((int64_t)HAWK_M1 * (HAWK_E2 - HAWK_Q20)) >> 16))
#define HAWK_STEP_MAX 335544u
#define HAWK_STEPS_MAX 4u
#define HAWK_LIMIT (16 * HAWK_Q20)
#define HAWK_LEVEL 7000

enum {
    HAWK_CIRCUIT, HAWK_RESONOISE, HAWK_ARRAY, HAWK_TRACT, HAWK_FLUTE, HAWK_REED, HAWK_BRASS,
};
static const char *const hawk_modes[] = {"CHUA", "RESONOISE", "ARRAY ROCKS", "TRACT", "FLUTE", "REED", "BRASS"};

typedef struct { int32_t x, y, z; } hawk_xyz_t;

typedef struct {
    hawk_xyz_t circuit;
    int32_t dc, hp1, hp2, smooth_low, tone;
    int32_t controls[3];
    uint32_t rng;
    uint8_t mode, started, fresh;
    union {
        struct {
            uint32_t phase, mod_phase, count;
            int32_t lo, band;
            int16_t cycle[64];
        } noise;
        hawk_wg_throat_t throat;
        hawk_wg_flute_t flute;
        hawk_wg_reed_t reed;
        hawk_wg_brass_t brass;
    } model;
} hawk_chua_voice_t;

typedef struct {
    hawk_chua_voice_t voice[HAWK_POLY];
} hawk_chua_part_t;

typedef struct {
    int32_t alpha, beta, period, gain[3];
} hawk_coeff_t;

static int32_t hawk_diode(int32_t x)
{
    int32_t a = x < 0 ? -x : x, g;
    if (a < HAWK_Q20)
        return px_m(HAWK_M0, x, 16);
    if (a < HAWK_E2)
        g = HAWK_G1 + px_m(HAWK_M1, a - HAWK_Q20, 16);
    else
        g = HAWK_G2 + (a - HAWK_E2) * 2;
    return x < 0 ? -g : g;
}

/* A midpoint step of the donor's three-state Chua circuit. */
static inline void hawk_step(hawk_xyz_t *s, int32_t h, int32_t ah, int32_t bh)
{
    int32_t x = s->x, y = s->y, z = s->z;
    int32_t mx = x + px_m(ah, y - x - hawk_diode(x), 24) / 2;
    int32_t my = y + px_m(h, x - y + z, 24) / 2;
    int32_t mz = z + px_m(-bh, y, 24) / 2;
    s->x = x + px_m(ah, my - mx - hawk_diode(mx), 24);
    s->y = y + px_m(h, mx - my + mz, 24);
    s->z = z + px_m(-bh, my, 24);
}

static inline int hawk_out_of_range(const hawk_xyz_t *s)
{
    return s->x > HAWK_LIMIT || s->x < -HAWK_LIMIT || s->y > HAWK_LIMIT || s->y < -HAWK_LIMIT
        || s->z > 4 * HAWK_LIMIT || s->z < -4 * HAWK_LIMIT;
}

static int32_t hawk_lerp(int32_t a, int32_t b, int32_t fraction)
{
    return a + px_m(b - a, fraction, 8);
}

static hawk_coeff_t hawk_coefficients(const int32_t *p)
{
    uint32_t k = (uint32_t)p[0], b = (uint32_t)p[1] * (CHUA_NB - 1u) / 127u;
    uint32_t r = b >> 8, r1 = r + (r < CHUA_NB - 1u), c = k >> 11;
    int32_t rf = (int32_t)(b & 255u), cf = (int32_t)((k >> 3) & 255u);
    int32_t alpha0 = hawk_lerp(CHUA_ALPHA[r][c], CHUA_ALPHA[r][c + 1u], cf);
    int32_t alpha1 = hawk_lerp(CHUA_ALPHA[r1][c], CHUA_ALPHA[r1][c + 1u], cf);
    int32_t period0 = hawk_lerp(CHUA_PERIOD[r][c], CHUA_PERIOD[r][c + 1u], cf);
    int32_t period1 = hawk_lerp(CHUA_PERIOD[r1][c], CHUA_PERIOD[r1][c + 1u], cf);
    int32_t wx = clamp(256 - (p[2] >> 6), 0, 256);
    int32_t wz = clamp((p[2] >> 6) - 256, 0, 256);
    int32_t weights[] = {wx, 256 - wx - wz, wz};
    hawk_coeff_t coeff = {
        hawk_lerp(alpha0, alpha1, rf), (10 << 16) + p[1] * 6144 / 127, hawk_lerp(period0, period1, rf), {0},
    };
    for (uint32_t i = 0; i < 3u; i++) {
        int32_t rms0 = hawk_lerp(CHUA_RMS[i][r][c], CHUA_RMS[i][r][c + 1u], cf);
        int32_t rms1 = hawk_lerp(CHUA_RMS[i][r1][c], CHUA_RMS[i][r1][c + 1u], cf);
        int32_t rms = hawk_lerp(rms0, rms1, rf);
        coeff.gain[i] = (HAWK_LEVEL * CHUA_RMS_Q / (rms < 16 ? 16 : rms)) * weights[i] >> 8;
    }
    return coeff;
}

static void hawk_reset_circuit(hawk_chua_voice_t *s, const hawk_coeff_t *coeff)
{
    int32_t x = HAWK_Q20 / 10 + (int32_t)(px_rand(&s->rng) & 65535u);
    if (px_rand(&s->rng) & 1u)
        x = -x;
    s->circuit = (hawk_xyz_t){x, 0, 0};
    /* Advance the oscillator towards its attractor before opening the envelope. */
    int32_t ah = px_m(coeff->alpha, (int32_t)HAWK_STEP_MAX, 16);
    int32_t bh = px_m(coeff->beta, (int32_t)HAWK_STEP_MAX, 16);
    for (uint32_t i = 0; i < 160u && !hawk_out_of_range(&s->circuit); i++)
        hawk_step(&s->circuit, (int32_t)HAWK_STEP_MAX, ah, bh);
    if (hawk_out_of_range(&s->circuit))
        s->circuit = (hawk_xyz_t){x, 0, 0};
    s->fresh = 1;
}

static uint32_t hawk_mode(const track_t *t)
{
    return (uint32_t)clamp(t->p[P_E0], HAWK_CIRCUIT, HAWK_BRASS);
}

static void hawk_voice_start(track_t *t, voice_t *v, hawk_chua_voice_t *s, uint32_t mode)
{
    uint32_t slot = (uint32_t)(v - t->v);
    *s = (hawk_chua_voice_t){0};
    s->started = 1;
    s->rng = (0xD36F29ADu ^ ((uint32_t)(t - trk) << 16) ^ (slot * 0x9e3779b9u) ^ v->note) | 1u;
    s->mode = (uint8_t)mode;
    for (uint32_t k = 0; k < 3u; k++)
        s->controls[k] = t->p[P_E1 + k] * 256;
    switch (mode) {
    case HAWK_ARRAY:
        for (uint32_t i = 0; i < 64u; i++)
            s->model.noise.cycle[i] = (int16_t)((int32_t)(px_rand(&s->rng) >> 16) - 32768);
        return;
    case HAWK_RESONOISE:
        return;
    case HAWK_FLUTE:
        s->model.flute.rng = px_rand(&s->rng);
        break;
    case HAWK_REED:
        s->model.reed.rng = px_rand(&s->rng);
        s->model.reed.ag = 4096;
        break;
    case HAWK_BRASS:
        s->model.brass.rng = px_rand(&s->rng);
        s->model.brass.ag = 4096;
        break;
    case HAWK_TRACT:
        s->model.throat.ag = 4096;
        break;
    }
    hawk_coeff_t coeff = hawk_coefficients(s->controls);
    hawk_reset_circuit(s, &coeff);
}

static void hawk_chua_note_on(track_t *t, voice_t *v)
{
    hawk_chua_part_t *part = eng_part(t);
    uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= HAWK_POLY)
        return;
    hawk_chua_voice_t *s = &part->voice[slot];
    uint32_t mode = hawk_mode(t);
    if (!s->started || s->mode != mode || (!v->env && !v->env_out))
        hawk_voice_start(t, v, s, mode);
}

static void hawk_circuit(hawk_chua_voice_t *s, uint32_t inc, int32_t *output, uint32_t n, uint32_t body)
{
    hawk_coeff_t coeff = hawk_coefficients(s->controls);
    uint32_t duration = (uint32_t)(((uint64_t)inc * (uint32_t)coeff.period) >> 24);
    if (duration > HAWK_STEP_MAX * HAWK_STEPS_MAX)
        duration = HAWK_STEP_MAX * HAWK_STEPS_MAX;
    uint32_t steps = (duration + HAWK_STEP_MAX - 1u) / HAWK_STEP_MAX;
    if (!steps)
        steps = 1;
    int32_t h = (int32_t)(duration / steps), ah = px_m(coeff.alpha, h, 16), bh = px_m(coeff.beta, h, 16);
    int32_t hp = (int32_t)(((uint64_t)inc * 144121u) >> 32); /* donor poles at 0.7 times the note */
    hp = clamp(hp, 0, 65535);
    if (s->controls[0] < 112 * 256 && (s->circuit.y > 2 * HAWK_Q20 || s->circuit.y < -2 * HAWK_Q20))
        hawk_reset_circuit(s, &coeff);

    for (uint32_t i = 0; i < n; i++) {
        for (uint32_t j = 0; j < steps; j++)
            hawk_step(&s->circuit, h, ah, bh);
        if (hawk_out_of_range(&s->circuit)) {
            s->circuit = (hawk_xyz_t){HAWK_Q20 / 10, 0, 0};
            s->fresh = 1;
        }
        int32_t sample = (int32_t)(((int64_t)(s->circuit.x >> 8) * coeff.gain[0]
            + (int64_t)(s->circuit.y >> 8) * coeff.gain[1] + (int64_t)(s->circuit.z >> 8) * coeff.gain[2]) >> 12);
        if (s->fresh) {
            s->dc = sample * 256;
            s->hp1 = s->hp2 = 0;
            s->fresh = 0;
        }
        s->dc += (sample * 256 - s->dc) >> 9;
        sample -= s->dc >> 8;
        s->hp1 += px_m(sample * 256 - s->hp1, hp, 16);
        sample -= s->hp1 >> 8;
        s->hp2 += px_m(sample * 256 - s->hp2, hp, 16);
        sample -= s->hp2 >> 8;
        output[i] = body ? clamp(sample * 96, -PX_ONE, PX_ONE) : sample;
    }
}

static uint32_t hawk_ratio(uint32_t inc, int32_t octaves)
{
    uint64_t scaled = (uint64_t)inc * px_exp2(octaves) >> 16;
    return scaled < 0x80000000u ? (uint32_t)scaled : 0x80000000u;
}

static uint32_t hawk_phase_fm(uint32_t inc, int32_t mod)
{
    return inc + (uint32_t)px_m((int32_t)inc, mod, 16); /* ±50 percent */
}

static int32_t hawk_triangle(uint32_t phase)
{
    uint32_t fold = phase & 0x80000000u ? 65535u : 0u;
    return (int32_t)(((phase >> 15) & 65535u) ^ fold) - 32768;
}

/* RESONOISE and ARRAY ROCKS, from HAWK CHUA's noise.c. */
static void hawk_noise(hawk_chua_voice_t *s, uint32_t inc, int32_t res, int32_t *y, uint32_t n)
{
    int32_t x = s->controls[0], shape = s->controls[1];
    uint32_t *phase = &s->model.noise.phase, *mod_phase = &s->model.noise.mod_phase, *count = &s->model.noise.count;
    const int32_t octave = s->mode == HAWK_ARRAY ? -2 : -1;
    uint32_t rate = hawk_ratio(inc, octave * 65536 + x * (2 - octave) * 256 / 127);
    if (s->mode == HAWK_ARRAY) {
        uint32_t interval = 4096u - (uint32_t)shape / 9u;
        for (uint32_t i = 0; i < n; i++) {
            int32_t mod = sine_i(*mod_phase);
            *mod_phase += rate;
            *phase += hawk_phase_fm(inc, mod);
            y[i] = s->model.noise.cycle[*phase >> 26] / 2;
            if (++*count >= interval) {
                *count = 0;
                uint32_t index = px_rand(&s->rng) & 63u;
                s->model.noise.cycle[index] = (int16_t)((int32_t)(px_rand(&s->rng) >> 16) - 32768);
            }
        }
        return;
    }
    int32_t fold = 32768 + shape * 8;
    const int32_t damping = 10500 - res * 50;
    int32_t lo = s->model.noise.lo, band = s->model.noise.band;
    for (uint32_t i = 0; i < n; i++) {
        int32_t mod = *mod_phase < 0x80000000u ? 32767 : -32767;
        *mod_phase += rate;
        *phase += hawk_phase_fm(inc, mod);
        int32_t folded = px_m(sine_i(*phase), fold, 15);
        int32_t in = hawk_triangle((uint32_t)folded * 65536u + 0x40000000u);
        uint32_t jitter = 32768u + (px_rand(&s->rng) >> 16);
        uint64_t jittered_inc = (uint64_t)inc * 3u * jitter >> 16;
        uint64_t freq = jittered_inc * 411775u >> 32;
        int32_t f = freq > 48000u ? 48000 : (int32_t)freq;
        lo += px_m(f, band, 16);
        int32_t hi = in - lo - px_m(damping, band, 16);
        band = clamp(band + px_m(f, hi, 16), -(1 << 20), 1 << 20);
        lo = clamp(lo, -(1 << 20), 1 << 20);
        y[i] = soft_knee(band, 16000);
    }
    s->model.noise.lo = lo;
    s->model.noise.band = band;
}

static void hawk_chua_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    hawk_chua_part_t *part = eng_part(t);
    uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= HAWK_POLY || !n)
        return;
    hawk_chua_voice_t *s = &part->voice[slot];
    uint32_t mode = hawk_mode(t);
    if (!s->started || s->mode != mode)
        hawk_voice_start(t, v, s, mode);
    const int16_t *p = t->p;
    int32_t ctrl[3] = {p[P_E1], clamp(p[P_E2] + (m->shape >> 8) - 64, 0, 127), p[P_E3]};
    for (uint32_t k = 0; k < 3u; k++) {
        int32_t delta = ctrl[k] * 256 - s->controls[k];
        s->controls[k] += delta > -24 && delta < 24 ? delta : delta / 16;
    }
    int32_t bright = clamp(p[P_E6] + (m->cutoff >> 9), 0, 127);
    int32_t model = p[P_E4], pos = p[P_E5], damping = p[P_E7];
    int32_t src[CTL], model_out[CTL];
    if (mode == HAWK_RESONOISE || mode == HAWK_ARRAY)
        hawk_noise(s, m->inc, damping, src, n);
    else
        hawk_circuit(s, m->inc, src, n, mode >= HAWK_TRACT);
    int32_t b = bright * 516, d = damping * 516;
    switch (mode) {
    case HAWK_TRACT:
        hawk_wg_throat_run(&s->model.throat, src, model_out, n, (uint32_t)model * 5u / 128u, pos, b, d);
        break;
    case HAWK_FLUTE:
        hawk_wg_flute_run(&s->model.flute, src, model_out, n, m->inc, model, pos * 516, b, d);
        break;
    case HAWK_REED:
        hawk_wg_reed_run(&s->model.reed, src, model_out, n, m->inc, model, pos, b, d);
        break;
    case HAWK_BRASS:
        hawk_wg_brass_run(&s->model.brass, src, model_out, n, m->inc, model, pos, b, d);
        break;
    default: {
        int32_t low_coef = 256 + pos * pos * 2;
        int32_t tone_coef = 1200 + bright * bright;
        int32_t gain = mode == HAWK_CIRCUIT ? damping : (s->controls[2] >> 8);
        if (mode == HAWK_ARRAY)
            tone_coef = clamp(tone_coef + damping * 75, 0, 32767);
        for (uint32_t i = 0; i < n; i++) {
            s->smooth_low += px_m(src[i] - s->smooth_low, low_coef, 16);
            int32_t high = src[i] - s->smooth_low;
            s->tone += px_m(high - s->tone, tone_coef, 15);
            int32_t driven = (int32_t)((int64_t)s->tone * (64 + model * 2) * gain / (64 * 96));
            out[i] += voice_amp2(soft_knee(driven, 16000), m, i);
        }
        return;
    }
    }
    int32_t gain = mode == HAWK_FLUTE ? 17000 : mode == HAWK_TRACT ? 4200 : 8000;
    for (uint32_t i = 0; i < n; i++)
        out[i] += voice_amp2(soft_knee(px_m(model_out[i], gain, 20), 17000), m, i);
}

static const param_desc_t hawk_circuit_desc[] = {
    {"DRIVE", 0, 0, 127, 25, F_PCT, 0},
    {"LOW CUT", 0, 0, 127, 18, F_PCT, 0},
    {"TONE", 0, 0, 127, 98, F_PCT, 0},
    {"LEVEL", 0, 0, 127, 90, F_PCT, 0},
};
static const param_desc_t hawk_noise_desc[] = {
    {"RATE", 0, 0, 127, 14, F_PCT, 0},
    {"FOLD", 0, 0, 127, 64, F_PCT, 0},
    {"LEVEL", 0, 0, 127, 64, F_PCT, 0},
    {"DRIVE", 0, 0, 127, 25, F_PCT, 0},
    {"LOW CUT", 0, 0, 127, 18, F_PCT, 0},
    {"TONE", 0, 0, 127, 98, F_PCT, 0},
    {"RESONANCE", 0, 0, 127, 90, F_PCT, 0},
};
static const param_desc_t hawk_scramble_desc = {"SCRAMBLE", 0, 0, 127, 64, F_PCT, 0};
static const param_desc_t hawk_noise_bright_desc = {"TOP", 0, 0, 127, 90, F_PCT, 0};
static const param_desc_t hawk_model_desc[][2] = {
    {{"SIZE", 0, 0, 127, 25, F_PCT, 0}, {"VOWEL", 0, 0, 127, 18, F_PCT, 0}},
    {{"JET", 0, 0, 127, 25, F_PCT, 0}, {"BREATH", 0, 0, 127, 18, F_PCT, 0}},
    {{"STIFFNESS", 0, 0, 127, 25, F_PCT, 0}, {"BORE", 0, 0, 127, 18, F_PCT, 0}},
    {{"LIP", 0, 0, 127, 25, F_PCT, 0}, {"MUTE", 0, 0, 127, 18, F_PCT, 0}},
};

static const param_desc_t *hawk_chua_desc(const track_t *t, uint32_t k)
{
    uint32_t mode = hawk_mode(t);
    if (mode == HAWK_RESONOISE || mode == HAWK_ARRAY) {
        if (mode == HAWK_ARRAY && k == 2u)
            return &hawk_scramble_desc;
        if (mode == HAWK_ARRAY && k == 7u)
            return &hawk_noise_bright_desc;
        return k > 0u ? &hawk_noise_desc[k - 1u] : 0;
    }
    if (mode == HAWK_CIRCUIT && k >= 4u)
        return &hawk_circuit_desc[k - 4u];
    if (mode >= HAWK_TRACT && (k == 4u || k == 5u))
        return &hawk_model_desc[mode - HAWK_TRACT][k - 4u];
    return 0;
}

static const preset_t hawk_chua_presets[] = {
    {"CHUA CYCLE", {HAWK_CIRCUIT, 14, 64, 64, 25, 18, 98, 90}, {4, 62, 116, 55}, 0, 0, TAG(PT_LEAD)},
    {"TWIN SCROLL", {HAWK_CIRCUIT, 93, 71, 24, 34, 19, 72, 88}, {3, 65, 115, 54}, 0, 0, TAG(PT_BASS)},
    {"RESONOISE", {HAWK_RESONOISE, 43, 68, 72, 19, 26, 88, 59}, {2, 51, 101, 47}, 0, 0, TAG(PT_FX)},
    {"ARRAY ROCKS", {HAWK_ARRAY, 55, 89, 70, 19, 21, 84, 91}, {2, 49, 106, 45}, 0, 0, TAG(PT_FX)},
    {"TRACT CHANT", {HAWK_TRACT, 18, 43, 68, 89, 59, 92, 105}, {12, 72, 115, 70}, 0, 0, TAG(PT_PAD)},
    {"JET FLUTE", {HAWK_FLUTE, 14, 58, 67, 12, 29, 78, 108}, {16, 74, 111, 68}, 0, 0, TAG(PT_LEAD)},
    {"CYLINDER REED", {HAWK_REED, 23, 62, 60, 55, 8, 76, 110}, {11, 66, 112, 63}, 0, 0, TAG(PT_LEAD)},
    {"CONE REED", {HAWK_REED, 13, 55, 64, 70, 85, 92, 113}, {10, 67, 111, 62}, 0, 0, TAG(PT_LEAD)},
    {"LIP BRASS", {HAWK_BRASS, 28, 71, 63, 82, 25, 89, 94}, {10, 62, 115, 57}, 0, 0, TAG(PT_LEAD)},
};

static const engine_t ENG_HAWK_CHUA = {
    .name = "CHUA", .page_title = {"CHAOS", "MODEL"},
    .edit = {
        {"MODE", hawk_modes, 0, HAWK_BRASS, HAWK_CIRCUIT, F_ENUM, 0},
        {"CHAOS", 0, 0, 127, 14, F_PCT, 0},
        {"SHAPE", 0, 0, 127, 64, F_PCT, 0},
        {"OUTPUT", 0, 0, 127, 64, F_PCT, 0},
        {"MODEL", 0, 0, 127, 25, F_PCT, 0},
        {"POSITION", 0, 0, 127, 18, F_PCT, 0},
        {"TONE", 0, 0, 127, 98, F_PCT, 0},
        {"DAMPING", 0, 0, 127, 90, F_PCT, 0},
    },
    .presets = hawk_chua_presets, .npresets = NELEM(hawk_chua_presets),
    .knob = {P_E0, P_E1, P_E2, P_E3}, .poly = HAWK_POLY,
    .note_on = hawk_chua_note_on, .render = hawk_chua_render, .desc = hawk_chua_desc,
};
