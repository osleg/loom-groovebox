/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff bridge for Joakim Eriksson's KEYS models and ten factory sounds. */
#include "thirdparty/keys_dsp.h"

#define KEYS_POLY 8u

typedef struct {
    float delay[2][KD_LEN];
    union {
        kd_clav_t clav;
        kd_ep_t ep;
        kd_piano_t piano;
    } model;
    float gain;
    uint8_t family;
} keys_voice_t;

typedef struct {
    keys_voice_t voice[KEYS_POLY];
} keys_part_t;

static const struct {
    uint8_t family;
    float shape[4], gain;
} KEYS_MODELS[] = {
    {FAM_CLAV, {-0.5f, 0.05f, 1.0f, 0.0f}, 1.4f},
    {FAM_CLAV, {0.0f, 0.10f, 0.6f, 0.0f}, 1.75f},
    {FAM_CLAV, {1.0f, 0.15f, 0.8f, 0.0f}, 1.15f},
    {FAM_CLAV, {-1.0f, 0.60f, 2.5f, 1.0f}, 1.3f},
    {FAM_EP, {0.3f, 0.2f, 0.2f, 0.25f}, 1.5f},
    {FAM_EP, {0.6f, 0.6f, 0.9f, 0.35f}, 1.3f},
    {FAM_EP, {1.0f, 0.9f, 0.3f, 0.5f}, 1.3f},
    {FAM_PIANO, {0.6f, 0.5f, 0.0f, 0.0f}, 0.85f},
    {FAM_PIANO, {1.5f, 0.7f, 0.0f, 0.0f}, 1.1f},
    {FAM_PIANO, {9.0f, 0.6f, 0.0f, 0.0f}, 0.95f},
};

static keys_voice_t *keys_slot(track_t *t, const voice_t *v)
{
    uint32_t slot = (uint32_t)(v - t->v);
    keys_part_t *part = (keys_part_t *)eng_part(t);
    return slot < KEYS_POLY ? &part->voice[slot] : 0;
}

static void keys_strike(track_t *t, voice_t *v)
{
    keys_voice_t *k = keys_slot(t, v);
    uint32_t model = (uint32_t)clamp(t->p[P_E4], 0, NELEM(KEYS_MODELS) - 1u);
    float note = v->note + song.g[G_TUNE] * 0.01f;
    float hz = 440.0f * kd_exp2((note - 69.0f) * (1.0f / 12.0f)) * (KD_SR / 44100.0f);
    float velocity = v->vel * (1.0f / 127.0f);
    kd_par_t patch = {
        .tone = t->p[P_E0] * (1.0f / 127.0f),
        .decay = t->p[P_E1] * (1.0f / 127.0f),
        .pick = t->p[P_E2] * (1.0f / 127.0f),
        .vol = 1.0f,
    };
    if (!k) return;
    memcpy(patch.x, KEYS_MODELS[model].shape, sizeof patch.x);
    k->family = KEYS_MODELS[model].family;
    k->gain = KEYS_MODELS[model].gain;
    switch (k->family) {
    case FAM_CLAV:
        k->model.clav.s.d = k->delay[0];
        kd_clav_on(&k->model.clav, hz, velocity, note, &patch, 1);
        break;
    case FAM_EP:
        kd_ep_on(&k->model.ep, hz, velocity, &patch, 1);
        break;
    default:
        k->model.piano.s[0].d = k->delay[0];
        k->model.piano.s[1].d = k->delay[1];
        kd_piano_on(&k->model.piano, hz, velocity, note, &patch, 1);
        break;
    }
}

static void keys_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    keys_voice_t *k = keys_slot(t, v);
    float level = t->p[P_E3] * (1.0f / 96.0f);
    float buf[CTL] = {0};
    uint32_t i;
    if (!k) return;
    if (n > CTL) n = CTL;
    switch (k->family) {
    case FAM_CLAV: kd_clav_run(&k->model.clav, v->gate, k->gain, buf, n); break;
    case FAM_EP: kd_ep_run(&k->model.ep, v->gate, k->gain, buf, n); break;
    default: kd_piano_run(&k->model.piano, v->gate, k->gain, buf, n); break;
    }
    for (i = 0; i < n; i++) {
        float sample = kd_clamp(buf[i] * level * level * 16000.0f, -32768.0f, 32767.0f);
        out[i] += voice_amp2((int32_t)sample, m, i);
    }
}

static const char *const KEYS_NAMES[] = {
    "BRIGHT", "MELLOW", "MUTED", "WAH", "SOFT", "BARK", "BELL", "GRAND", "UPRIGHT", "HONKY"
};

#define KEYS_PATCH(name, tone, decay, pick, level, model) \
    {name, {tone, decay, pick, level, model, 0, 0, 0}, {0, 75, 127, 75}, 0, 0, TAG(PT_KEYS)}
static const preset_t KEYS_PATCHES[] = {
    KEYS_PATCH("CLAV BRIGHT", 92, 64, 30, 96, 0),
    KEYS_PATCH("CLAV MELLOW", 54, 64, 92, 96, 1),
    KEYS_PATCH("CLAV MUTED", 60, 8, 46, 100, 2),
    KEYS_PATCH("CLAV WAH", 64, 50, 40, 96, 3),
    KEYS_PATCH("EP SOFT", 40, 80, 30, 96, 4),
    KEYS_PATCH("EP BARK", 70, 70, 100, 96, 5),
    KEYS_PATCH("EP BELL", 90, 90, 44, 96, 6),
    KEYS_PATCH("GRAND", 70, 76, 60, 96, 7),
    KEYS_PATCH("UPRIGHT", 48, 52, 40, 96, 8),
    KEYS_PATCH("HONKY", 84, 60, 90, 96, 9),
};
#undef KEYS_PATCH

static const param_desc_t *keys_desc(const track_t *t, uint32_t k)
{
    static const param_desc_t hardness = {"HARDNESS", 0, 0, 127, 30, F_PCT, 0};
    return k == 2u && t->p[P_E4] >= 7 ? &hardness : 0;
}

static const engine_t ENG_KEYS = {
    .name = "KEYS",
    .page_title = {"TONE", "MODEL"},
    .edit = {
        {"TONE", 0, 0, 127, 92, F_PCT, 0},
        {"DECAY", 0, 0, 127, 64, F_PCT, 0},
        {"PICKUP", 0, 0, 127, 30, F_PCT, 0},
        {"LEVEL", 0, 0, 127, 96, F_PCT, 0},
        {"MODEL", KEYS_NAMES, 0, NELEM(KEYS_NAMES) - 1u, 0, F_ENUM, 0},
        {"-", 0, 0, 0, 0, F_INT, 0},
        {"-", 0, 0, 0, 0, F_INT, 0},
        {"-", 0, 0, 0, 0, F_INT, 0},
    },
    .presets = KEYS_PATCHES, .npresets = NELEM(KEYS_PATCHES),
    .knob = {P_E0, P_E1, P_E2, P_E3}, .poly = KEYS_POLY,
    .note_on = keys_strike, .render = keys_render, .desc = keys_desc,
};
