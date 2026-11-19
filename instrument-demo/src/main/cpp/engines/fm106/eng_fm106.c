/* SPDX-License-Identifier: GPL-3.0-only
 * Skiff bridge for PatttF's FM-106 DCO and ladder filter. */
#include "thirdparty/fm106_osc.h"
#include "thirdparty/fm106_vcf.h"

typedef struct {
    fm106_dco_t dco;
    fm106_vcf_t vcf;
    fm106_hpf_t hpf;
    int64_t osc_dc, filter_dc;
    int32_t cutoff_cv;
} fm106_voice_state_t;

typedef struct {
    fm106_voice_state_t voice[NVOICE];
} fm106_part_t;

static fm106_voice_state_t *fm106_voice_slot(track_t *t, const voice_t *v)
{
    return &((fm106_part_t *)eng_part(t))->voice[v - t->v];
}

static void fm106_strike(track_t *t, voice_t *v)
{
    fm106_voice_state_t *s = fm106_voice_slot(t, v);
    if (s->dco.noise_seed) return; /* A DCO's phase and filter continue over a retrigger. */
    fm106_dco_init(&s->dco, (uint32_t)(t - trk) * NVOICE + (uint32_t)(v - t->v));
    fm106_vcf_init(&s->vcf);
    fm106_hpf_init(&s->hpf);
}

static void fm106_render_skiff(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    fm106_voice_state_t *s = fm106_voice_slot(t, v);
    const int16_t *p = t->p;
    int32_t lfo = mulq15(t->lfo_val, t->lfo_fade);
    int32_t cut = clamp(p[P_E4] * 128 + m->cutoff / 2, 0, 127 * 128);
    uint16_t dac = fm106_calc_vcf_freq((uint16_t)cut, 0, 0, 0, 64, 0, 0, 1, 0,
                                     (uint16_t)(m->pitch16 * 16));
    uint32_t pw = (uint32_t)p[P_E1];
    uint32_t i;
    s->dco.saw_on = (p[P_E0] & 1) == 0;
    s->dco.pulse_on = p[P_E0] != 0;
    s->dco.sub_on = p[P_E2] != 0;
    s->dco.sub_level = (uint8_t)p[P_E2];
    s->dco.noise_level = (uint8_t)p[P_E3];
    s->hpf.mode = (uint8_t)p[P_E6];
    if (p[P_E7] == 2) pw = (pw * (uint32_t)m->envq15) >> 15;
    if (!s->cutoff_cv) s->cutoff_cv = dac;
    for (i = 0; i < n; i++) {
        int32_t x = fm106_dco_process(&s->dco, m->inc, (uint8_t)pw, p[P_E7] != 1,
                                       lfo, m->envq15);
        x = fm106_dc_block(x, &s->osc_dc);
        s->cutoff_cv += ((dac - s->cutoff_cv) * 1393) >> 15;
        x = fm106_vcf_process(&s->vcf, x, (uint16_t)s->cutoff_cv, (uint8_t)p[P_E5]);
        x = fm106_dc_block(x, &s->filter_dc);
        x = fm106_hpf_process(&s->hpf, x);
        out[i] += voice_amp2(fm106_soft_limit(x), m, i);
    }
}

static const char *const FM106_WAVE[] = {"SAW", "PULSE", "BOTH"};
static const char *const FM106_HPF[] = {"BOOST", "FLAT", "236 HZ", "754 HZ"};
static const char *const FM106_PWM[] = {"MANUAL", "LFO", "ENV"};
static const preset_t FM106_SK_PRESETS[] = {
    {"SATIN PAD", {2, 60, 44, 0, 69, 20, 1, 1}, {63, 79, 102, 80}, 9, 0, FX(0, 92, 10, 70), TAG(PT_PAD)},
    {"PULSE FLOOR", {1, 38, 100, 0, 54, 32, 0, 0}, {0, 65, 75, 27}, 26, 1, FX(9, 0, 0, 12), TAG(PT_BASS)},
    {"SILVER BRASS", {2, 36, 52, 2, 69, 36, 1, 2}, {38, 78, 89, 57}, 29, 0, FX(0, 62, 8, 42), TAG(PT_LEAD)},
    {"HOLLOW PLUCK", {1, 61, 30, 10, 54, 77, 2, 2}, {0, 69, 0, 53}, 36, 0, FX(0, 44, 35, 30), TAG(PT_PLUCK)},
};

static const engine_t ENG_FM106 = {
    .name = "FM-106",
    .page_title = {"DCO", "FILTER"},
    .edit = {
        {"WAVE", FM106_WAVE, 0, 2, 2, F_ENUM, 0},
        {"WIDTH", 0, 0, 105, 60, F_INT, 0},
        {"SUB", 0, 0, 127, 44, F_PCT, 0},
        {"NOISE", 0, 0, 127, 0, F_PCT, 0},
        {"CUTOFF", 0, 0, 127, 69, F_PCT, 0},
        {"RESO", 0, 0, 127, 20, F_PCT, 0},
        {"HPF", FM106_HPF, 0, 3, 1, F_ENUM, 0},
        {"PWM", FM106_PWM, 0, 2, 1, F_ENUM, 0},
    },
    .presets = FM106_SK_PRESETS, .npresets = NELEM(FM106_SK_PRESETS),
    .knob = {P_E4, P_E5, P_E1, P_E2}, .poly = 6,
    .note_on = fm106_strike, .render = fm106_render_skiff,
};
