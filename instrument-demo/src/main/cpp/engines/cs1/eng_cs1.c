/* SPDX-License-Identifier: GPL-3.0-only */
#include "cs1_filter.inc"

typedef struct {
    uint32_t phase;
    int32_t high1, high2, low1, low2;
} cs1_layer_t;

typedef struct { cs1_layer_t voice[NVOICE][2]; } cs1_part_t;

static void cs1_strike(track_t *t, voice_t *v)
{
    cs1_part_t *part = (cs1_part_t *)eng_part(t);
    if (v->env_out == 0)
        memset(part->voice[v - t->v], 0, sizeof part->voice[0]);
}

static void cs1_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    cs1_part_t *part = (cs1_part_t *)eng_part(t);
    cs1_layer_t *state = part->voice[v - t->v];
    const int16_t *p = t->p;
    const int32_t mix = p[P_E3];
    const int32_t tone = m->cutoff / 16 + m->pitch16 - 960 + (t->at * 3);
    const uint32_t pw = 0x80000000u + (uint32_t)((m->shape - (64 << 8)) * 42000);
    static const int16_t FOOT[] = {-192, 0, 112, 192, 304, 384};
    const uint32_t step[] = {m->inc, cents_inc(m->pitch16 + FOOT[p[P_E2]], 7, m->fine)};
    cs1_svf_t hp[2], lp[2];
    uint32_t ch, i;
    for (ch = 0; ch < 2u; ch++) {
        const int32_t resonance = cs1_SVF_K(p[P_E6]);
        cs1_svf_coef(&hp[ch], 128 + p[P_E4] * 15 + tone + (int32_t)ch * 16, resonance);
        cs1_svf_coef(&lp[ch], 256 + p[P_E5] * 15 + tone - (int32_t)ch * 40, resonance);
    }
    for (i = 0; i < n; i++) {
        int32_t sum = 0;
        for (ch = 0; ch < 2u; ch++) {
            cs1_layer_t *s = &state[ch];
            int32_t wave = p[P_E0 + ch];
            int32_t saw = osc_saw(s->phase, step[ch]), x = saw, low, high;
            int32_t gain = ch ? clamp(mix * 2, 0, 127) : clamp((127 - mix) * 2, 0, 127);
            if (wave != 0) {
                int32_t pulse = (saw - osc_saw(s->phase + pw, step[ch])) / 2;
                x = wave == 1 ? pulse : (saw + pulse) / 2;
            }
            cs1_svf_step(&hp[ch], x, &s->high1, &s->high2, &low, &high);
            cs1_svf_step(&lp[ch], clamp(high, -65535, 65535), &s->low1, &s->low2, &low, &high);
            x = softclip(low) - mulq15(osc_sine(s->phase), p[P_E7] * 258);
            s->phase += step[ch];
            sum += mulq15(x / 2, gain * 258);
        }
        out[i] += voice_amp2(softclip(sum), m, i);
    }
}

static const char *const CS1_WAVES[] = {"SAW", "PULSE", "BOTH"};
static const char *const CS1_FEET[] = {"16'", "8'", "5 1/3'", "4'", "2 2/3'", "2'"};
static const preset_t CS1_PRESETS[] = {
    {"AURORA BRASS", {0, 2, 1, 64, 25, 70, 30, 18}, {56, 90, 100, 74}, 43, 0, FX(0, 47, 15, 64), TAG(PT_LEAD)},
    {"RIBBON AIR", {1, 0, 2, 47, 47, 74, 54, 30}, {30, 86, 90, 67}, 26, 0, FX(0, 54, 25, 78), TAG(PT_PAD)},
    {"LOW TWIN", {0, 1, 0, 46, 11, 61, 55, 20}, {0, 80, 75, 35}, 25, 1, FX(4, 0, 0, 15), TAG(PT_BASS)},
    {"VELVET KEYS", {0, 1, 1, 66, 26, 54, 18, 63}, {9, 87, 72, 65}, 30, 0, FX(0, 49, 35, 60), TAG(PT_KEYS)},
};

static const engine_t ENG_CS1 = {
    .name = "CS-1",
    .page_title = {"LAYERS", "FILTERS"},
    .edit = {
        {"WAVE I", CS1_WAVES, 0, 2, 0, F_ENUM, 0},
        {"WAVE II", CS1_WAVES, 0, 2, 2, F_ENUM, 0},
        {"FEET II", CS1_FEET, 0, 5, 1, F_ENUM, 0},
        {"MIX", 0, 0, 127, 64, F_PCT, 0},
        {"HPF", 0, 0, 127, 25, F_PCT, 0},
        {"LPF", 0, 0, 127, 70, F_PCT, 0},
        {"RESO", 0, 0, 127, 30, F_PCT, 0},
        {"SINE", 0, 0, 127, 18, F_PCT, 0},
    },
    .presets = CS1_PRESETS, .npresets = NELEM(CS1_PRESETS),
    .knob = {P_E5, P_E6, P_E3, P_E4}, .poly = 8,
    .note_on = cs1_strike, .render = cs1_render,
};
