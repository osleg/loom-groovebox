/* SPDX-License-Identifier: GPL-3.0-only */
#include <math.h>
#include <stdbool.h>
#include "dx386_runtime.h"

typedef struct {
    dx386_prompt_t prompt;
    const dx386_clip_t *clip;
    float pos, pitch;
    uint8_t started;
} dx386_voice_t;

typedef struct {
    dx386_voice_t voices[DX386_POLY];
    uint8_t last_note, note_seen;
} dx386_part_t;

static void dx386_note_on(track_t *t, voice_t *v)
{
    dx386_part_t *part = (dx386_part_t *)eng_part(t);
    const uint32_t slot = (uint32_t)(v - t->v);
    if (slot >= DX386_POLY)
        return;
    dx386_voice_t *voice = &part->voices[slot];
    memset(voice, 0, sizeof *voice);
    dx386_make_prompt(&voice->prompt, (unsigned)t->p[P_E0], (unsigned)t->p[P_E1],
                     (unsigned)t->p[P_E2], (unsigned)t->p[P_E3], (unsigned)t->p[P_E4],
                     (unsigned)t->p[P_E7], v->note);
    voice->pitch = part->note_seen && t->p[P_E5] ? part->last_note : v->note;
    voice->clip = dx386_find_clip((unsigned)(t - trk), slot, &voice->prompt);
    voice->started = 1;
    part->last_note = v->note;
    part->note_seen = 1;
}

static float dx386_sample(const dx386_clip_t *clip, float pos)
{
    const uint32_t at = (uint32_t)pos;
    const float a = clip->pcm[at];
    const float b = at + 1u < clip->length ? clip->pcm[at + 1u] : 0;
    return a + (b - a) * (pos - (float)at);
}

static void dx386_render_voice(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    const uint32_t slot = (uint32_t)(v - t->v);
    dx386_part_t *part = (dx386_part_t *)eng_part(t);
    if (slot >= DX386_POLY || !part->voices[slot].started)
        return;
    dx386_voice_t *voice = &part->voices[slot];
    if (!voice->clip)
        voice->clip = dx386_find_clip((unsigned)(t - trk), slot, &voice->prompt);
    const dx386_clip_t *clip = voice->clip;
    if (!clip)
        return;
    const float target = m->pitch16 * (1.0f / 16.0f) + m->fine * (17.31234f / 4096.0f);
    const int32_t glide = t->p[P_E5];
    const float smoothing = glide ? 1.0f - expf(-(float)n / (441.0f + 2.4f * (float)(glide * glide))) : 1.0f;
    voice->pitch += (target - voice->pitch) * smoothing;
    const float ratio = 0.25f * exp2f((voice->pitch - voice->prompt.root) * (1.0f / 12.0f));
    const bool hold = v->gate && t->p[P_E6] == 0 && clip->loop_end != 0;
    const float end = clip->loop_end, start = clip->loop_start, fade = clip->crossfade;
    float pos = voice->pos;
    for (uint32_t i = 0; i < n && pos < clip->length; i++) {
        const float loop = end - start;
        if (hold && pos >= end)
            pos = start + fmodf(pos - end, loop);
        float value = dx386_sample(clip, pos);
        if (hold && pos >= end - fade) {
            const float crossfade = pos - (end - fade);
            value += (dx386_sample(clip, start - fade + crossfade) - value) * (crossfade / fade);
        }
        out[i] += voice_amp2((int32_t)(0.55f * value), m, i);
        pos += ratio;
    }
    voice->pos = pos;
}

static const char *const DX386_PHRASES[] = {"AWAKE", "THE LIGHT", "VOICES", "SING", "USER"};
static const char *const DX386_HOLD[] = {"SUSTAIN", "ONE SHOT"};

static const preset_t DX386_PRESETS[] = {
    {"WE ARE AWAKE", {0, 0, 50, 12, 24, 16, 0, 70}, {0, 60, 118, 64}, 0, 0, TAG(PT_LEAD)},
    {"HOLD THE LIGHT", {1, 0, 33, 6, 41, 19, 0, 54}, {1, 59, 115, 68}, 0, 0, TAG(PT_KEYS)},
    {"VOICES RISE", {2, 0, 62, 14, 12, 12, 0, 83}, {4, 74, 117, 70}, 0, 0, FX(0, 6, 0, 22), TAG(PT_PAD)},
    {"SING THROUGH", {3, 0, 55, 36, 28, 23, 1, 77}, {1, 50, 118, 71}, 0, 0, TAG(PT_KEYS)},
};

static const engine_t ENG_DX386 = {
    .name = "DX386", .page_title = {"SING", "ARTICULATION"},
    .edit = {
        {"PHRASE", DX386_PHRASES, 0, DX386_USER_LINE, 0, F_ENUM, 0},
        {"SYLLABLE", 0, 0, 63, 0, F_INT, 0},
        {"BODY", 0, 0, 100, 50, F_INT, 0},
        {"BREATH", 0, 0, 100, 12, F_INT, 0},
        {"EDGE", 0, 0, 100, 24, F_INT, 0},
        {"GLIDE", 0, 0, 100, 16, F_INT, 0},
        {"HOLD", DX386_HOLD, 0, NELEM(DX386_HOLD) - 1, 0, F_ENUM, 0},
        {"LENGTH", 0, 0, 100, 70, F_INT, 0},
    },
    .presets = DX386_PRESETS, .npresets = NELEM(DX386_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3}, .poly = DX386_POLY,
    .note_on = dx386_note_on, .render = dx386_render_voice,
};
