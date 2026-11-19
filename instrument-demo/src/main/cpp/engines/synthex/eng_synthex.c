/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff voice adapter for Scintilla's Synthex model. */
#include "thirdparty/sc_voice.h"

enum { SYNTHEX_WAVE_OFF = SC_WAVE_NOISE + 1 };

typedef struct {
    sc_voice_t voice[NVOICE];
    sc_layer_t layer;
    sc_prep_t prep;
    int16_t controls[8];
    uint8_t prepared;
} synthex_part_t;

static const char *const SYNTHEX_WAVES[] = {"TRI", "SAW", "PULSE", "SINE", "NOISE", "OFF"};
static const char *const SYNTHEX_MODES[] = {"MIX", "SYNC", "RING", "SYNC+RING"};
static const char *const SYNTHEX_FILTERS[] = {"LP24", "LP12", "BP12", "BP6", "HP12"};

static int32_t synthex_width(int32_t width)
{
    return 3277 + clamp(width, 0, 127 << 8) * (62259 - 3277) / (127 << 8);
}

static void synthex_prepare(track_t *t)
{
    static const sc_perf_t perf = {0};
    synthex_part_t *part = (synthex_part_t *)eng_part(t);
    const int16_t *p = &t->p[P_E0];
    if (part->prepared && !memcmp(part->controls, p, sizeof(part->controls))) return;
    memcpy(part->controls, p, sizeof(part->controls));

    int32_t w1 = clamp(p[0], 0, SYNTHEX_WAVE_OFF), w2 = clamp(p[1], 0, SYNTHEX_WAVE_OFF);
    int32_t mode = clamp(p[3], 0, 3);
    /* The chip's VCA opens without imposing a second patch envelope on Skiff. */
    part->layer = (sc_layer_t){
        .o1_wave = (int8_t)(w1 == SYNTHEX_WAVE_OFF ? SC_WAVE_TRI : w1),
        .o2_wave = (int8_t)(w2 == SYNTHEX_WAVE_OFF ? SC_WAVE_TRI : w2),
        .o1_pwm = synthex_width(p[2] * 256), .o2_pwm = synthex_width(p[2] * 256),
        .o2_sync = (int8_t)(mode & 1), .ring = (int8_t)(mode >> 1),
        .o2_detune = SC_Q16(100) * clamp(p[7], -24, 24),
        .mix_o1 = w1 == SYNTHEX_WAVE_OFF ? 0 : SC_Q16(0.72),
        .mix_o2 = w2 == SYNTHEX_WAVE_OFF ? 0 : SC_Q16(0.72),
        .flt_mode = (int8_t)clamp(p[4], 0, SC_FLT_HP12),
        .cutoff = SC_Q16(1) * clamp(p[5], 0, 127) / 127,
        .reso = SC_Q16(1) * clamp(p[6], 0, 127) / 127,
        .env_f = {7, 7, SC_Q16(1), SC_Q16(1)},
        .env_a = {7, 7, SC_Q16(1), SC_Q16(1)},
    };
    sc_prep(&part->prep, &part->layer, &perf);
    part->prepared = 1u;
}

static void synthex_note_on(track_t *t, voice_t *v)
{
    synthex_part_t *part = (synthex_part_t *)eng_part(t);
    sc_voice_t *voice = &part->voice[v - t->v];
    synthex_prepare(t);
    if (!v->env_out || sc_voice_idle(voice)) sc_voice_init(voice, v->age | 1u);
    sc_voice_note_on(voice, &part->prep, v->note, SC_Q16(1));
}

static void synthex_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    synthex_part_t *part = (synthex_part_t *)eng_part(t);
    sc_voice_t *voice = &part->voice[v - t->v];
    sc_prep_t prep = part->prep;
    int32_t cutoff = clamp(t->p[P_E5] * 256 + m->cutoff, 0, 127 << 8);
    prep.c_base = SC_Q16(24) + cutoff * (96 * 256) / 127;
    prep.pw1 = prep.pw2 = synthex_width(t->p[P_E2] * 256 + m->shape - (64 << 8));
    voice->cur = voice->target = (int64_t)m->pitch16 * (1LL << 28);
    voice->bend = m->fine * 277;

    for (uint32_t at = 0; at < n;) {
        int32_t samples[32] = {0};
        uint32_t count = n - at;
        if (count > NELEM(samples)) count = NELEM(samples);
        sc_voice_render(voice, &prep, samples, count);
        for (uint32_t i = 0; i < count; i++)
            out[at + i] += voice_amp2(clamp(samples[i] >> 5, -32768, 32767), m, at + i);
        at += count;
    }
}

static const preset_t SYNTHEX_PRESETS[] = {
    {"PRISM STRINGS", {1, 2, 47, 0, 0, 77, 19, 0}, {42, 85, 116, 65}, 9, 0, TAG(PT_PAD)},
    {"ARC SYNC", {0, 1, 58, 1, 0, 94, 45, 19}, {0, 66, 106, 24}, 16, 1, TAG(PT_LEAD)},
    {"RING MALLETS", {3, 2, 37, 2, 2, 84, 29, 7}, {1, 69, 27, 49}, 8, 0, TAG(PT_KEYS)},
    {"LOW CURRENT", {2, 1, 70, 0, 0, 45, 52, -12}, {0, 73, 109, 24}, 32, 1, TAG(PT_BASS)},
};

static const engine_t ENG_SYNTHEX = {
    .name = "SYNTHEX",
    .page_title = {"OSCILLATORS", "FILTER"},
    .edit = {
        {"OSC 1", SYNTHEX_WAVES, 0, SYNTHEX_WAVE_OFF, 1, F_ENUM, 0},
        {"OSC 2", SYNTHEX_WAVES, 0, SYNTHEX_WAVE_OFF, 2, F_ENUM, 0},
        {"WIDTH", 0, 0, 127, 47, F_PCT, 0},
        {"MODE", SYNTHEX_MODES, 0, 3, 0, F_ENUM, 0},
        {"FILTER", SYNTHEX_FILTERS, 0, SC_FLT_HP12, SC_FLT_LP24, F_ENUM, 0},
        {"CUTOFF", 0, 0, 127, 77, F_PCT, 0},
        {"RESO", 0, 0, 127, 19, F_PCT, 0},
        {"OSC 2 TUNE", 0, -24, 24, 0, F_SEMI, 0},
    },
    .presets = SYNTHEX_PRESETS, .npresets = NELEM(SYNTHEX_PRESETS),
    .knob = {P_E5, P_E6, P_E2, P_E7},
    .poly = 8, .block = synthex_prepare, .note_on = synthex_note_on, .render = synthex_render,
};
