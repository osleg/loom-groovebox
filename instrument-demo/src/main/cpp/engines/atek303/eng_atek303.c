/* SPDX-License-Identifier: GPL-3.0-or-later
 * Android Skiff engine bridge for the ATEK303 voice. Donor code is in thirdparty/. */
#include "thirdparty/atek_voice.h"
#include <math.h>

/* eng_part() points to the first member of either engine-memory union. */
typedef struct {
    at_voice_t voice[NVOICE];
    uint8_t ready[NVOICE];
    uint8_t key[NVOICE];
} atek_part_t;

static at_voice_t *atek_slot(track_t *t, voice_t *v, atek_part_t **part)
{
    uint32_t k = (uint32_t)(v - t->v);
    *part = (atek_part_t *)eng_part(t);
    return k < NVOICE ? &(*part)->voice[k] : 0;
}

static void atek_controls(track_t *t, at_voice_t *a, const vmod_t *m)
{
    const int16_t *p = t->p;
    int32_t cut = p[P_E1] + (m ? (m->cutoff >> 8) : 0);
    if (cut < 0) cut = 0;
    if (cut > 127) cut = 127;
    at_set(a, AT_WAVE, (float)p[P_E0] / 13.0f);
    at_set(a, AT_CUTOFF, (float)cut / 127.0f);
    at_set(a, AT_RESONANCE, (float)p[P_E2] / 127.0f);
    at_set(a, AT_ENVMOD, (float)p[P_E3] / 127.0f);
    at_set(a, AT_DECAY, (float)p[P_E4] / 127.0f);
    at_set(a, AT_ACCENT, (float)p[P_E5] / 127.0f);
    at_option(a, AT_OPT_SLIDE, p[P_E6]);
    at_option(a, AT_OPT_DRIVE, p[P_E7]);
}

static void atek_note_on(track_t *t, voice_t *v)
{
    atek_part_t *part;
    at_voice_t *a = atek_slot(t, v, &part);
    uint32_t k = (uint32_t)(v - t->v);
    int slide = t->slide_glide || t->p[P_GLIDE] != 0;
    if (!a) return;
    if (!part->ready[k]) {
        at_init(a, 44100.0f);
        at_option(a, AT_OPT_LEGATO, 1);
        part->ready[k] = 1;
    }
    atek_controls(t, a, 0);
    if (a->gate && !slide) at_gate(a, 0, v->note, 0, 0);
    at_gate(a, 1, v->note, v->vel > 110, slide);
    part->key[k] = v->note;
}

static void atek_render(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    atek_part_t *part;
    at_voice_t *a = atek_slot(t, v, &part);
    uint32_t k = (uint32_t)(v - t->v), at = 0;
    float samples[32];
    if (!a || !part->ready[k]) return;
    atek_controls(t, a, m);
    if (v->gate) {
        if (!a->gate || part->key[k] != v->note) {
            at_gate(a, 1, v->note, v->vel > 110, t->slide_glide || t->p[P_GLIDE] != 0);
            part->key[k] = v->note;
        }
    } else if (a->gate) {
        at_gate(a, 0, v->note, 0, 0);
    }
    /* Skiff's tune and bend follow the pitch; the donor handles note glide. */
    a->pitch_wheel = exp2f((float)(m->pitch16 - v->pitch_cur) / 192.0f)
                   * (1.0f + (float)m->fine / 4096.0f);
    while (at < n) {
        uint32_t count = n - at, i;
        if (count > 32u) count = 32u;
        at_render(a, samples, count);
        for (i = 0; i < count; i++) {
            float s = samples[i] * 16384.0f;
            int32_t q = s > 32767.0f ? 32767 : s < -32768.0f ? -32768 : (int32_t)s;
            out[at + i] += voice_amp2(q, m, at + i);
        }
        at += count;
    }
}

static const preset_t ATEK_PRESETS[] = {
    {"CIRCUIT BASS", {0, 48, 72, 68, 45, 80, 1, 1}, {0, 72, 110, 28}, 0, 1, TAG(PT_BASS)},
    {"SQUARE ACID", {13, 62, 95, 84, 36, 105, 1, 1}, {0, 60, 100, 20}, 0, 1, TAG(PT_BASS)},
    {"SOFT 303", {0, 35, 42, 40, 70, 50, 2, 0}, {0, 82, 100, 42}, 0, 1, TAG(PT_BASS)},
    {"BITE LEAD", {13, 85, 108, 110, 30, 120, 0, 2}, {0, 70, 110, 35}, 0, 1, TAG(PT_LEAD)},
};
static const engine_t ENG_ATEK303 = {
    .name = "ATEK303",
    .page_title = {"CIRCUIT", "VOICE"},
    .edit = {
        {"WAVE", AT_OSC_NAME, 0, 13, 0, F_ENUM, 0},
        {"CUTOFF", 0, 0, 127, 48, F_PCT, 0},
        {"RESO", 0, 0, 127, 72, F_PCT, 0},
        {"ENV MOD", 0, 0, 127, 68, F_PCT, 0},
        {"DECAY", 0, 0, 127, 45, F_PCT, 0},
        {"ACCENT", 0, 0, 127, 80, F_PCT, 0},
        {"SLIDE", 0, 0, 2, 1, F_INT, 0},
        {"DRIVE", 0, 0, 2, 1, F_INT, 0},
    },
    .presets = ATEK_PRESETS, .npresets = NELEM(ATEK_PRESETS),
    .knob = {P_E1, P_E2, P_E3, P_E4},
    .poly = NVOICE, .note_on = atek_note_on, .render = atek_render,
};
