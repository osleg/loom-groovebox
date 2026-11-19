/* SPDX-License-Identifier: GPL-3.0-only
 * Android Skiff bridges for FM1-Dock's ARP 2600 and Eminent 310 DSP. */
#include <string.h>
#include "thirdparty/arp_engine.h"
#include "thirdparty/em_engine.h"

typedef struct {
    arp_t voice[NVOICE];
} arp2600_part_t;

typedef em_t eminent_part_t;

_Static_assert(NVOICE == EM_VOICES, "one Eminent oscillator stack per Skiff voice");

static uint8_t dock_percent(int32_t value)
{
    return (uint8_t)((clamp(value, 0, 127) * 100 + 63) / 127);
}

enum { ARP_NORMAL, ARP_SUB, ARP_RING, ARP_SYNC };
enum { ARP2600_ACCENT = 34 };

static arp_t *arp2600_slot(track_t *t, voice_t *v)
{
    return &((arp2600_part_t *)eng_part(t))->voice[v - t->v];
}

static void arp2600_note_on(track_t *t, voice_t *v)
{
    arp_t *a = arp2600_slot(t, v);
    if (!v->env) {
        arp_init(a);
        a->seed += (uint32_t)(v - t->v) * 0x9e3779b9u;
        a->patch.accent = ARP2600_ACCENT;
    }
    arp_play(a, v->note, v->vel > 110 ? ARP_PLAY_ACCENT : 0);
}

static void arp2600_controls(arp_t *a, const int16_t *p, const vmod_t *m)
{
    uint8_t patch = (uint8_t)p[P_E3];
    arp_patch_t settings = {
        .vco1_wave = (uint8_t)p[P_E0], .vco1_oct = 2,
        .vco1_pw = (uint8_t)clamp(50 + ((m->shape - (64 << 8)) >> 9), 5, 95), .vco1_lvl = 83,
        .vco2_wave = (uint8_t)p[P_E1], .vco2_oct = patch == ARP_SYNC ? 3 : 2,
        .vco2_detune = (uint8_t)(p[P_E2] + 50), .vco2_lvl = patch == ARP_RING ? 15 : 65,
        .noise_lvl = patch == ARP_RING ? 8 : 0,
        .ring_lvl = patch == ARP_RING ? 90 : 0, .sync = patch == ARP_SYNC, .sub_lvl = patch == ARP_SUB ? 55 : 0,
        .cutoff = dock_percent(p[P_E4] + (m->cutoff >> 8)), .reso = dock_percent(p[P_E5]),
        .env_amt = dock_percent(p[P_E6]), .kbd = 33, .drive = dock_percent(p[P_E7]),
        .a1 = 8, .d1 = 51, .s1 = 18, .r1 = 49,
        .s2 = 100, .r2 = 100,
        .volume = 85, .accent = ARP2600_ACCENT,
    };
    if (memcmp(&a->patch, &settings, sizeof settings))
        arp_set_patch(a, &settings);
}

static void arp2600_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    arp_t *a = arp2600_slot(t, v);
    uint32_t at = 0;
    arp2600_controls(a, t->p, m);
    /* Pitch is 1/16 semitone in Skiff and Q16 semitones in the donor.
     * The fractional frequency multiplier converts to 277 Q16 units per step near unity. */
    a->target_q16 = m->pitch16 * 4096 + m->fine * 277;
    if (!v->gate && a->gate)
        arp_release(a);
    while (at < n) {
        int32_t samples[64] = {0};
        uint32_t i, count = n - at;
        if (count > 32u) count = 32u;
        arp_render(a, samples, count);
        for (i = 0; i < count; i++)
            out[at + i] += voice_amp2(samples[2u * i], m, at + i);
        at += count;
    }
}

static const char *const ARP_WAVE_NAME[] = {"SAW", "PULSE", "TRI"};
static const char *const ARP_PATCH_NAME[] = {"NORMAL", "SUB", "RING", "SYNC"};
static const preset_t ARP2600_PRESETS[] = {
    {"PATCH BASS", {ARP_SAW, ARP_PULSE, 6, ARP_SUB, 57, 37, 59, 103}, {1, 63, 104, 34}, 0, 1, TAG(PT_BASS)},
    {"CABLE LEAD", {ARP_PULSE, ARP_SAW, 15, ARP_SYNC, 85, 43, 48, 80}, {3, 72, 105, 41}, 0, 1, TAG(PT_LEAD)},
    {"RING GLASS", {ARP_TRI, ARP_PULSE, -19, ARP_RING, 91, 63, 35, 69}, {0, 45, 74, 58}, 0, 0, TAG(PT_PLUCK)},
    {"HUSH WAVES", {ARP_TRI, ARP_SAW, -12, ARP_NORMAL, 74, 21, 19, 53}, {30, 81, 104, 63}, 0, 0, TAG(PT_PAD)},
};

static const engine_t ENG_ARP2600 = {
    .name = "ARP2600",
    .page_title = {"OSCILLATORS", "4072 FILTER"},
    .edit = {
        {"VCO 1", ARP_WAVE_NAME, 0, 2, ARP_SAW, F_ENUM, 0},
        {"VCO 2", ARP_WAVE_NAME, 0, 2, ARP_PULSE, F_ENUM, 0},
        {"VCO 2 TUNE", 0, -50, 50, 6, F_INT, U_CT},
        {"PATCH", ARP_PATCH_NAME, 0, 3, ARP_SUB, F_ENUM, 0},
        {"CUTOFF", 0, 0, 127, 57, F_PCT, 0},
        {"RESO", 0, 0, 127, 37, F_PCT, 0},
        {"FILTER ENV", 0, 0, 127, 59, F_PCT, 0},
        {"DRIVE", 0, 0, 127, 103, F_PCT, 0},
    },
    .presets = ARP2600_PRESETS, .npresets = NELEM(ARP2600_PRESETS),
    .knob = {P_E4, P_E5, P_E6, P_E7},
    .poly = NVOICE, .note_on = arp2600_note_on, .render = arp2600_render,
};

static em_voice_t *eminent_slot(track_t *t, voice_t *v)
{
    return &((eminent_part_t *)eng_part(t))->v[v - t->v];
}

static void eminent_note_on(track_t *t, voice_t *v)
{
    em_voice_t *s = eminent_slot(t, v);
    if (!s->age) {
        s->ph8 = (uint32_t)(v - t->v) * 0x9e3779b9u;
        s->ph16 = s->ph8 >> 1;
        s->ph4 = s->ph8 << 1;
        s->age = 1;
    }
}

static void eminent_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    em_voice_t *s = eminent_slot(t, v);
    int32_t transpose = t->p[P_E7];
    uint32_t inc = m->inc, at = 0;
    if (transpose) {
        inc = pitch_inc(clamp(m->pitch16 + transpose * 16, 0, 2047));
        inc += (uint32_t)((int32_t)(inc >> 12) * m->fine);
    }
    while (at < n) {
        int32_t samples[32];
        uint32_t i, count = n - at;
        if (count > 32u) count = 32u;
        em_render_footages(s, (uint32_t)t->p[P_E0], inc, samples, count);
        for (i = 0; i < count; i++)
            out[at + i] += voice_amp(samples[i] >> 2, m, at + i);
        at += count;
    }
}

static void eminent_post(track_t *t, int32_t *out, uint32_t n)
{
    eminent_part_t *e = (eminent_part_t *)eng_part(t);
    const int16_t *p = t->p;
    int32_t mix = p[P_E4] * 258, rolloff = 4200 + p[P_E5] * 220, body = p[P_E6] * 512;
    uint32_t at = 0;
    e->p.ensemble = dock_percent(p[P_E1]);
    e->p.phaser = dock_percent(p[P_E2]);
    e->p.color = (uint8_t)p[P_E3];
    while (at < n) {
        int32_t mono[32], wet[64] = {0};
        uint32_t i, count = n - at;
        if (count > 32u) count = 32u;
        for (i = 0; i < count; i++)
            mono[i] = out[at + i];
        em_render_effects(e, mono, wet, count, 65536, body, rolloff);
        for (i = 0; i < count; i++) {
            int32_t stereo_mono = (wet[2u * i] + wet[2u * i + 1u]) >> 1;
            int32_t blend = (int32_t)(((int64_t)(stereo_mono - mono[i]) * mix) >> 15);
            out[at + i] = mono[i] + blend;
        }
        at += count;
    }
}

static const char *const EMINENT_REG_NAME[] = {"STRINGS", "CELLO", "VIOLIN", "FULL"};
static const char *const EMINENT_COLOR_NAME[] = {"SOFT", "RESONANT"};
static const preset_t EMINENT_PRESETS[] = {
    {"STRING VEIL", {EM_STRINGS, 85, 30, 1, 127, 71, 78, 0}, {43, 90, 111, 61}, 0, 0, FX(0, 23, 0, 31), TAG(PT_PAD)},
    {"AMBER CELLO", {EM_CELLO, 60, 0, 0, 97, 56, 52, 0}, {30, 87, 115, 54}, 0, 0, FX(0, 16, 0, 26), TAG(PT_PAD)},
    {"HIGH RIBBON", {EM_VIOLIN, 90, 68, 1, 119, 92, 65, 0}, {46, 93, 115, 63}, 0, 0, FX(0, 26, 0, 29), TAG(PT_PAD)},
    {"ENSEMBLE BED", {EM_FULL, 112, 50, 1, 110, 70, 85, 0}, {58, 91, 113, 68}, 0, 0, FX(0, 24, 0, 39), TAG(PT_PAD)},
};

static const engine_t ENG_EMINENT = {
    .name = "EMINENT",
    .page_title = {"ENSEMBLE", "FORMANT"},
    .edit = {
        {"REGISTER", EMINENT_REG_NAME, 0, 3, EM_STRINGS, F_ENUM, 0},
        {"ENSEMBLE", 0, 0, 127, 85, F_PCT, 0},
        {"PHASER", 0, 0, 127, 30, F_PCT, 0},
        {"COLOR", EMINENT_COLOR_NAME, 0, 1, 1, F_ENUM, 0},
        {"FX MIX", 0, 0, 127, 127, F_PCT, 0},
        {"TONE", 0, 0, 127, 71, F_PCT, 0},
        {"BODY", 0, 0, 127, 78, F_PCT, 0},
        {"TRANSPOSE", 0, -12, 12, 0, F_SEMI, 0},
    },
    .presets = EMINENT_PRESETS, .npresets = NELEM(EMINENT_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E4},
    .poly = NVOICE, .note_on = eminent_note_on, .render = eminent_render, .post = eminent_post,
};
