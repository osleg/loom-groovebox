/* SPDX-License-Identifier: GPL-3.0-only */

/* Phone engines over Skiff's existing SAMPLE ADPCM banks and user slots. */

#define SAMPLER_RATE 44100u
#define SAMPLER_USER1 SMP_NSETS

enum { LOFI_PLAYING, LOFI_ENDED, LOFI_MISSING, LOFI_TAIL };
enum { LOFI_LOOP_OFF, LOFI_LOOP_FILE, LOFI_LOOP_WHOLE };

static const char *const LOFI_LOOP_NAMES[] = {"OFF", "FILE", "WHOLE"};
static const char *const LOFI_RATE_NAMES[] = {"26.04K", "27.5K"};
static const uint32_t LOFI_RATE_HZ[] = {26040u, 27500u};
static const char *const LOFI_ROOT_NAMES[] = {
    "SLOT",
    "C2", "C#2", "D2", "D#2", "E2", "F2", "F#2", "G2", "G#2", "A2", "A#2", "B2",
    "C3", "C#3", "D3", "D#3", "E3", "F3", "F#3", "G3", "G#3", "A3", "A#3", "B3",
    "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4",
    "C5", "C#5", "D5", "D#5", "E5", "F5", "F#5", "G5", "G#5", "A5", "A#5", "B5",
    "C6",
};

static int32_t lofi_quantize(int32_t s)
{
    int32_t rounded = (clamp(s + 8, -32768, 32767) + 32768) / 16;
    return rounded * 16 - 32768;
}

static void lofi_smp_note_on(track_t *t, voice_t *v)
{
    sample_note_on(t, v);
    v->ph[2] = 2u * SAMPLER_RATE;   /* unprimed clock */
    v->s[5] = 0;                  /* held 12-bit value */
}

static int32_t lofi_smp_read(const smp_zone_t *z, voice_t *v, uint32_t loop)
{
    uint32_t pos = v->ph[0];
    if (pos >= z->n)
        return 0;
    adpcm_next(z, &pos, v->s);
    int32_t sample = v->s[0];
    if (loop == LOFI_LOOP_FILE && z->looped && pos > z->le) {
        pos = z->ls;
        v->s[0] = z->pred;
        v->s[1] = z->idx;
    } else if (loop == LOFI_LOOP_WHOLE && pos == z->n) {
        pos = 0;
        v->s[0] = v->s[1] = 0;
    }
    v->ph[0] = pos;
    return sample;
}

static void lofi_smp_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    const int16_t *p = t->p;
    const smp_zone_t *z = smp_zone((uint32_t)v->s[4]);
    uint32_t loop = (uint32_t)p[P_E3];
    uint32_t frac = v->ph[1], clock = v->ph[2], step = 0;
    uint32_t hz = LOFI_RATE_HZ[p[P_E4] != 0];
    int32_t cutoff = clamp(p[P_E5] * 256 + m->cutoff, 0, 127 * 256);
    int32_t lp = 4096 + cutoff * 28672 / (127 * 256);
    int32_t pitch = m->pitch16 + p[P_E1] * 16;
    int32_t held = v->s[5], y = v->s[7];

    if (v->s[6] == LOFI_ENDED || (v->s[6] == LOFI_PLAYING && !z->n)) {
        v->active = 0;
        return;
    }
    if (v->s[6] == LOFI_MISSING) {
        step = pitch_inc((uint32_t)clamp(pitch, 0, 2047));
    } else if (v->s[6] == LOFI_PLAYING) {
        int32_t root = p[P_E2] ? (35 + p[P_E2]) * 16 : z->root16;
        uint32_t ratio = pow2_q16(clamp(pitch - root, -1536, 576));
        step = (ratio >> 8) * (z->rate >> 8);
        step = (uint32_t)((int64_t)step * clamp(4096 + m->fine, 1, 8192) / 4096);
        if (clock == 2u * SAMPLER_RATE) {
            v->s[2] = lofi_smp_read(z, v, loop);
            v->s[3] = lofi_smp_read(z, v, loop);
        }
    }
    if (clock == 2u * SAMPLER_RATE)
        clock = SAMPLER_RATE;

    for (uint32_t i = 0; i < n; i++) {
        int32_t s;
        if (v->s[6] == LOFI_TAIL) {
            s = held * v->s[0] / 32;
        } else {
            if (clock >= SAMPLER_RATE) {
                if (v->s[6] == LOFI_MISSING)
                    s = sine_i(v->ph[0]) >> 1;
                else
                    s = v->s[2] + (((v->s[3] - v->s[2]) * (int32_t)(frac >> 1)) >> 15);
                if (p[P_E6])
                    s = softclip(s + (((s >> 2) * (p[P_E6] * 150)) >> 11));
                held = lofi_quantize(s);
                clock -= SAMPLER_RATE;
            }
            clock += hz;
            s = held;
        }
        y += mulq15(s - y, lp);
        out[i] += voice_amp2(y, m, i);

        if (v->s[6] == LOFI_MISSING) {
            v->ph[0] += step;
        } else if (v->s[6] == LOFI_TAIL) {
            if (--v->s[0] == 0) {
                v->s[6] = LOFI_ENDED;
                v->active = 0;
                break;
            }
        } else {
            frac += step;
            while (frac >= 65536u) {
                frac -= 65536u;
                v->s[2] = v->s[3];
                if (v->ph[0] >= z->n && !v->s[2]) {
                    v->s[6] = LOFI_TAIL;
                    v->s[0] = 32;
                    break;
                }
                v->s[3] = lofi_smp_read(z, v, loop);
            }
        }
    }
    v->ph[1] = frac;
    v->ph[2] = clock;
    v->s[5] = held;
    v->s[7] = y;
}

static const preset_t LOFI_SMP_PRESETS[] = {
    {"LOFI SAMPLE", {SAMPLER_USER1, 0, 0, LOFI_LOOP_FILE, 0, 127, 0, 0}, {0, 80, 127, 20}, 0, 0, TAG(PT_KEYS)},
    {"WORN LOOP", {SAMPLER_USER1, 0, 0, LOFI_LOOP_WHOLE, 1, 96, 22, 0}, {4, 80, 127, 35}, 0, 0, TAG(PT_FX)},
    {"DUSTY PIANO", {0, 0, 0, LOFI_LOOP_FILE, 0, 100, 12, 0}, {0, 64, 95, 40}, 0, 0, TAG(PT_KEYS)},
};

static const engine_t ENG_LOFI_SMP = {
    .name = "LOFI SMP",
    .page_title = {"SAMPLE", "COLOR"},
    .edit = {
        {"SOURCE", SMP_ALL_NAMES, 0, SMP_PICK_MAX, SAMPLER_USER1, F_ENUM, 0},
        {"PITCH", 0, -24, 24, 0, F_SEMI, 0},
        {"ROOT", LOFI_ROOT_NAMES, 0, NELEM(LOFI_ROOT_NAMES) - 1, 0, F_ENUM, 0},
        {"LOOP", LOFI_LOOP_NAMES, LOFI_LOOP_OFF, LOFI_LOOP_WHOLE, LOFI_LOOP_FILE, F_ENUM, 0},
        {"RATE", LOFI_RATE_NAMES, 0, 1, 0, F_ENUM, 0},
        {"CUTOFF", 0, 0, 127, 127, F_PCT, 0},
        {"DRIVE", 0, 0, 127, 0, F_PCT, 0},
        {"-", 0, 0, 0, 0, F_INT, 0},
    },
    .presets = LOFI_SMP_PRESETS,
    .npresets = NELEM(LOFI_SMP_PRESETS),
    .knob = {P_E0, P_E5, P_E6, P_REL},
    .sampled = 1,
    .note_on = lofi_smp_note_on,
    .render = lofi_smp_render,
};

/* Cloud reuses GRAIN's bounded ADPCM seek, grain pool, and Hann-windowed
 * readers. Its moving playhead and independent density give it a different
 * control response across a sustained note. */
static void cloud_reset_voice(uint32_t source, voice_t *v)
{
    v->s[0] = gr_find(source, v->note);
    v->s[1] = v->s[2] = 0;
    v->ph[0] = v->ph[1] = 0;
}

static void cloud_note_on(track_t *t, voice_t *v)
{
    gr_part_t *part = eng_part(t);
    uint32_t owner = (uint32_t)(v - t->v) + 1u;
    for (uint32_t i = 0; i < GR_NG; i++)
        if (part->g[i].owner == owner)
            part->g[i].owner = 0;
    cloud_reset_voice((uint32_t)t->p[P_E0] % SMP_NALL, v);
}

static void cloud_block(track_t *t)
{
    gr_part_t *part = eng_part(t);
    uint32_t source = (uint32_t)t->p[P_E0] % SMP_NALL;
    uint32_t stamp = gr_stamp(source);
    if (!part->rng)
        part->rng = 0x2545F491 ^ (int32_t)(t - trk);
    if (part->src != source + 1u || part->stamp != stamp) {
        gr_reset(part, source, stamp);
        for (uint32_t i = 0; i < GR_POLY; i++)
            if (t->v[i].active)
                cloud_reset_voice(source, &t->v[i]);
    }
    for (uint32_t i = 0; i < GR_NG; i++) {
        uint32_t owner = part->g[i].owner;
        if (owner && !t->v[owner - 1u].active)
            part->g[i].owner = 0;
    }
    gr_build(part, t);
}

static void cloud_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    gr_part_t *part = eng_part(t);
    const int16_t *p = t->p;
    uint32_t owner = (uint32_t)(v - t->v) + 1u;
    int32_t zone = v->s[0];
    int32_t lp = 4096 + clamp(112 * 256 + m->cutoff, 0, 127 * 256) * 28672 / (127 * 256);
    int32_t acc[CTL] = {0};
    uint32_t mine = 0, active = 0;
    int32_t y = v->s[2];

    if (n > CTL)
        n = CTL;
    if (zone == GR_SINE) {
        smp_sine(out, n, m, p[P_E4] * 16, lp, &v->ph[1], &v->s[2]);
        return;
    }
    if (zone < 0) {
        v->active = 0;
        return;
    }
    if (zone >= part->nz || part->src != (uint32_t)p[P_E0] % SMP_NALL + 1u)
        return;

    for (uint32_t i = 0; i < GR_POLY; i++)
        active += t->v[i].active != 0;
    for (uint32_t i = 0; i < GR_NG; i++)
        if (part->g[i].owner == owner)
            mine += gr_run(part, &part->g[i], acc, n);

    /* 4 to approximately 160 grain starts/s, with timing jitter from spray. */
    uint32_t interval = ((SAMPLER_RATE / 4u) * pow2_q16(-p[P_E3] * 8)) >> 16;
    v->s[1] -= (int32_t)n;
    if (v->s[1] <= 0) {
        int32_t jitter = (((int32_t)(gr_rnd(part) & 0xffffu) - 32768) * p[P_E5]) / 254;
        v->s[1] += (int32_t)interval + mulq15((int32_t)interval, jitter);
        if (mine < GR_NG / (active ? active : 1u)) {
            vmod_t sweep = *m;
            int32_t pos = p[P_E1] * 256;
            /* Unsigned phase wraps after one full-sample traversal. */
            uint32_t moving = ((uint32_t)pos + (v->ph[0] >> 17)) & 32767u;
            sweep.shape += (int32_t)moving - pos;
            gr_spawn(part, t, owner - 1u, (uint32_t)zone, interval, &sweep);
            if (!mine && part->zdone[zone] < part->zcnt[zone])
                v->s[1] = 0;   /* retry while the desired position is being indexed */
        }
    }
    v->ph[0] += (uint32_t)((int32_t)n * p[P_E7] * 737);
    for (uint32_t i = 0; i < n; i++) {
        y += mulq15(clamp(acc[i], -32768, 32767) - y, lp);
        out[i] += voice_amp2(y, m, i);
    }
    v->s[2] = y;
}

static const preset_t CLOUD_PRESETS[] = {
    {"DRIFT CLOUD", {SAMPLER_USER1, 34, 82, 64, 0, 32, 22, 12}, {24, 90, 120, 88}, 0, 0, FX(0, 25, 15, 55), TAG(PT_PAD)},
    {"FROZEN AIR", {SAMPLER_USER1, 53, 103, 80, 0, 0, 10, 0}, {35, 90, 120, 99}, 0, 0, FX(0, 20, 15, 65), TAG(PT_PAD)},
    {"GLASS SPRAY", {0, 32, 43, 100, 12, 82, 75, -18}, {8, 85, 115, 65}, 0, 0, FX(0, 15, 50, 70), TAG(PT_FX)},
};

static const engine_t ENG_CLOUD = {
    .name = "CLOUD",
    .page_title = {"CLOUD", "MOTION"},
    .edit = {
        {"SOURCE", SMP_ALL_NAMES, 0, SMP_PICK_MAX, SAMPLER_USER1, F_ENUM, 0},
        {"POSITION", 0, 0, 127, 34, F_PCT, 0},
        {"SIZE", 0, 0, 127, 82, F_PCT, 0},
        {"DENSITY", 0, 0, 127, 64, F_PCT, 0},
        {"PITCH", 0, -24, 24, 0, F_SEMI, 0},
        {"SPRAY", 0, 0, 127, 32, F_PCT, 0},
        {"RANDOM", 0, 0, 127, 22, F_PCT, 0},
        {"DRIFT", 0, -64, 63, 12, F_BIPCT, 0},
    },
    .presets = CLOUD_PRESETS,
    .npresets = NELEM(CLOUD_PRESETS),
    .knob = {P_E1, P_E2, P_E3, P_E5},
    .poly = GR_POLY,
    .sampled = 1,
    .note_on = cloud_note_on,
    .render = cloud_render,
    .block = cloud_block,
};
