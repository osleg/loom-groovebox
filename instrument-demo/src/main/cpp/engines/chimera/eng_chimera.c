/* SPDX-License-Identifier: GPL-3.0-only */
#include <math.h>
#include <string.h>
#include "thirdparty/ch_beat.h"
#include "thirdparty/ch_creature.h"
#include "thirdparty/ch_wave.h"
#include "thirdparty/voice_bodies.h"

#define CHIMERA_RATE 44100.0f
#define CHIMERA_BLOCK 64u
#define CHIMERA_WORD_TAG 1.0f
#define CHIMERA_WORD_GESTURES 32u

enum { CHIMERA_VOWEL, CHIMERA_ENGLISH, CHIMERA_BEAT, CHIMERA_SEAL, CHIMERA_BIRD };

typedef struct {
    ch_control_t control;
    ch_tract_t tract;
    ch_syl_t speech;
    ch_wave_t wave;
    ch_cvoice_t animal;
    union { ch_seal_t seal; ch_bird_t bird; } model;
    float beat_low, beat_air;
    uint8_t ready, mode, body, note, syllable, left, speech_gate, last_open, last_front;
} chimera_part_t;

static const char *const CHIMERA_MODES[] = {"VOWEL", "ENGLISH", "BEATBOX", "SEAL", "SONGBIRD"};
static const char *const CHIMERA_BODIES[] = {"MALE", "FEMALE", "CHILD"};
static const char *const CHIMERA_WORD_NAMES[] = {"AH", "HEY", "MOON", "GLOW", "DREAM", "BLUE", "SING", "YEAH", "HOME", "FLY", "SHH"};
static const char *const CHIMERA_WORDS[] = {"ah", "hey", "moon", "glow", "dream", "blue", "sing", "yeah", "home", "fly", "shh"};
static const char *const CHIMERA_BEATS[] = {
    "BY KEY", "KICK", "HAT", "OPEN HAT", "SNARE", "RIMSHOT", "INWARD", "CLICK", "LIP POP", "CRASH", "HUM",
};
_Static_assert(NELEM(CHIMERA_BEATS) == CH_BEAT_N + 1u, "beat labels must cover the donor modes");
static const char *const CHIMERA_SEAL_RATE_NAMES[] = {
    "2 HZ", "4 HZ", "6 HZ", "8 HZ", "10 HZ", "14 HZ", "18 HZ", "24 HZ", "32 HZ", "44 HZ", "60 HZ",
};
static const uint8_t CHIMERA_SEAL_RATES[] = {2, 4, 6, 8, 10, 14, 18, 24, 32, 44, 60};
static const char *const CHIMERA_BIRD_INTERVAL_NAMES[] = {
    "-12 ST", "-9 ST", "-7 ST", "-5 ST", "-3 ST", "UNISON", "+3 ST", "+5 ST", "+7 ST", "+9 ST", "+12 ST",
};
static const int8_t CHIMERA_BIRD_INTERVALS[] = {-12, -9, -7, -5, -3, 0, 3, 5, 7, 9, 12};

static void chimera_prepare(chimera_part_t *part, uint8_t mode, uint8_t body)
{
    memset(part, 0, sizeof(*part));
    part->mode = mode;
    part->body = body;
    part->ready = 1;
    part->syllable = UINT8_MAX;
    part->last_open = part->last_front = UINT8_MAX;
    if (mode == CHIMERA_BIRD) {
        ch_cvoice_init(&part->animal, &CH_BIRD);
        ch_bird_init(&part->model.bird, &part->animal);
    } else if (mode == CHIMERA_SEAL) {
        ch_cvoice_init(&part->animal, &CH_SEAL);
        ch_seal_init(&part->model.seal);
        const float scale = 1.16f - body * 0.16f;
        ch_wave_init(&part->wave, CHIMERA_RATE, CHIMERA_BLOCK, scale, CH_SEAL_LEN * scale);
        part->wave.output_gain = CH_SEAL_GAIN * 2.2f;
        part->wave.port_len = 0.4f;
        part->wave.port_slit = 1.8f;
        part->wave.wall_rad = 0.6f;
    } else {
        body_tract_setup(&part->tract, body);
        ch_control_init(&part->control, CHIMERA_RATE, CHIMERA_BLOCK, CH_BODIES[body].scale);
        part->control.defer_contacts = 1;
        part->control.anat_every = 2;
        ch_tract_apply(&part->tract, part->control.x, 0.0f);
        ch_wave_init(&part->wave, CHIMERA_RATE, CHIMERA_BLOCK, part->tract.k, part->tract.lip_s);
        part->wave.fade = body == 2u ? 0.98f : 0.995f;
        if (mode == CHIMERA_BEAT) {
            ch_real target[CH_NARTIC];
            ch_speech_body(body);
            ch_beat_ready(target);
            for (int i = 0; i < CH_NARTIC; i++)
                if (isfinite(target[i]))
                    part->control.target[i] = target[i];
        }
    }
}

static const char *chimera_pronunciation(const char *word, void *user)
{
    (void)user;
    if (!strcmp(word, "shh"))
        return "SH AH1";
    return 0;
}

static void chimera_append_score(ch_control_t *control, ch_plan_t *plan)
{
    for (int i = 0; i < plan->n; i++) {
        ch_gesture_t gesture = plan->g[i];
        gesture.t0 += control->clock;
        gesture.t1 += control->clock;
        ch_score_add(&control->score, &gesture);
    }
}

static void chimera_release_word(chimera_part_t *part)
{
    if (!part->speech_gate)
        return;
    ch_gesture_t gestures[CHIMERA_WORD_GESTURES];
    ch_plan_t plan = {.g = gestures, .cap = CHIMERA_WORD_GESTURES};
    ch_score_release(&part->control.score, CHIMERA_WORD_TAG, part->control.clock);
    ch_score_release(&part->control.score, CHIMERA_WORD_TAG + 1.0f, part->control.clock);
    ch_speech_body(part->body);
    ch_plan_note_off(&part->speech, part->note, &plan);
    chimera_append_score(&part->control, &plan);
    part->speech_gate = 0;
}

static void chimera_note_on(track_t *t, voice_t *v)
{
    chimera_part_t *part = eng_part(t);
    const int16_t *p = t->p;
    const uint8_t mode = (uint8_t)p[P_E0], body = (uint8_t)p[P_E1];
    if (!part->ready || part->mode != mode || part->body != body || (!v->env && !v->env_out))
        chimera_prepare(part, mode, body);

    if (mode == CHIMERA_ENGLISH) {
        const uint8_t word = (uint8_t)p[P_E7];
        chimera_release_word(part);
        ch_speech_body(body);
        if (part->syllable != word) {
            if (!ch_lyrics(CHIMERA_WORDS[word], chimera_pronunciation, 0, &part->speech, 1))
                ch_lyrics(CHIMERA_WORDS[0], chimera_pronunciation, 0, &part->speech, 1);
            part->syllable = word;
        }
        ch_gesture_t gestures[CHIMERA_WORD_GESTURES];
        ch_plan_t plan = {.g = gestures, .cap = CHIMERA_WORD_GESTURES};
        ch_plan_note_on(&part->speech, v->note, 21.0f, CHIMERA_WORD_TAG, 0, 0, &plan);
        chimera_append_score(&part->control, &plan);
        part->speech_gate = 1;
    } else if (mode == CHIMERA_BEAT) {
        ch_speech_body(body);
        const int hit = p[P_E7] ? p[P_E7] - 1 : v->note % CH_BEAT_N;
        ch_gesture_t gestures[CH_BEAT_MAX_G];
        ch_plan_t plan = {.g = gestures, .cap = CH_BEAT_MAX_G};
        const float accent = v->vel > 110 ? CH_BEAT_ACCENT : 1.0f;
        ch_beat_build(hit, ch_midi_hz(v->note), 0.18f, accent,
                      part->control.clock + ch_beat_lead_in(hit), &plan);
        for (int i = 0; i < plan.n; i++)
            ch_score_add(&part->control.score, &gestures[i]);
    }
    part->note = v->note;
}

/* Close-mic tone and gentle clipping for the otherwise quiet acoustic transients. */
static float chimera_beat_mic(chimera_part_t *part, float sample)
{
    part->beat_low += 0.018f * (sample - part->beat_low);
    part->beat_air += 0.38f * (sample - part->beat_air);
    const float boosted = 5.0f * (sample + 1.4f * part->beat_low + 0.28f * (sample - part->beat_air));
    return boosted / (1.0f + fabsf(boosted));
}

static void chimera_animal_step(chimera_part_t *part, const int16_t *p, float pitch, int gate)
{
    ch_cvoice_t *animal = &part->animal;
    const float step = (float)CHIMERA_BLOCK / CHIMERA_RATE;
    ch_cvoice_gate(animal, gate ? 1.0f : 0.0f);
    if (part->mode == CHIMERA_BIRD) {
        ch_cvoice_set(animal, CH_BIRD_PITCH, ch_ckey_hz(&CH_BIRD.p[CH_BIRD_PITCH], pitch));
        ch_cvoice_set(animal, CH_BIRD_GAPE, p[P_E2] / 127.0f);
        ch_cvoice_set(animal, CH_BIRD_VOICES, p[P_E3] / 127.0f);
        ch_cvoice_set(animal, CH_BIRD_PRESSURE, 0.1f + p[P_E4] * (1.9f / 127.0f));
        ch_cvoice_set(animal, CH_BIRD_PURITY, p[P_E5] / 127.0f);
        ch_cvoice_set(animal, CH_BIRD_DRIVE, p[P_E6] / 127.0f);
        ch_cvoice_set(animal, CH_BIRD_OEC, 0.58f - part->body * 0.18f);
        ch_cvoice_set(animal, CH_BIRD_INTERVAL, CHIMERA_BIRD_INTERVALS[p[P_E7]]);
        ch_cvoice_step(animal, step);
        ch_bird_control(&part->model.bird, animal, CHIMERA_BLOCK);
    } else {
        ch_cvoice_set(animal, CH_SEAL_PITCH, ch_ckey_hz(&CH_SEAL.p[CH_SEAL_PITCH], pitch));
        ch_cvoice_set(animal, CH_SEAL_MOUTH, p[P_E2] / 127.0f);
        ch_cvoice_set(animal, CH_SEAL_PULSE, p[P_E3] / 127.0f);
        ch_cvoice_set(animal, CH_SEAL_PRESSURE, 2.0f + p[P_E4] * (23.0f / 127.0f));
        ch_cvoice_set(animal, CH_SEAL_ROUGH, p[P_E5] * (0.8f / 127.0f));
        ch_cvoice_set(animal, CH_SEAL_BRIGHT, p[P_E6] / 127.0f);
        ch_cvoice_set(animal, CH_SEAL_PULSE_RATE, CHIMERA_SEAL_RATES[p[P_E7]]);
        ch_cvoice_step(animal, step);
        ch_glottis_params_t glottis;
        ch_real pressure;
        ch_shape_t shape;
        ch_seal_control(&part->model.seal, animal, step, CHIMERA_RATE, &glottis, &pressure, &shape);
        ch_wave_block(&part->wave, &shape, 1, &glottis, pressure);
    }
}

static void chimera_human_step(chimera_part_t *part, const int16_t *p, float pitch, int gate, const vmod_t *m)
{
    ch_control_t *control = &part->control;
    ch_source_t source = control->source;
    source.f0 = part->mode == CHIMERA_ENGLISH ? CH_BODIES[part->body].f0 : ch_midi_hz(pitch);
    source.pressure = 2.0f + p[P_E4] * (16.0f / 127.0f);
    source.breathiness = p[P_E5] / 127.0f;
    source.tension = p[P_E6] / 127.0f;
    source.brightness = part->mode == CHIMERA_VOWEL ? p[P_E7] / 10.0f : 0.25f;
    source.vibrato_depth = part->mode == CHIMERA_VOWEL ? 12.0f : 0.0f;
    ch_control_set_source(control, &source);
    ch_control_set_gate(control, part->mode == CHIMERA_VOWEL && gate ? 1.0f : 0.0f);
    if (part->mode == CHIMERA_VOWEL) {
        const uint8_t opening = (uint8_t)clamp(p[P_E2] + (m->shape >> 8) - 64, 0, 127);
        const uint8_t front = (uint8_t)p[P_E3];
        if (opening != part->last_open || front != part->last_front) {
            const ch_body_t *body = &CH_BODIES[part->body];
            const float f1 = body->pad_f1 * 230.0f * powf(950.0f / 230.0f, opening / 127.0f);
            const float f2 = body->pad_f2 * 2700.0f * powf(700.0f / 2700.0f, front / 127.0f);
            ch_speech_body(part->body);
            ch_vowel_pad(f1, f2, control->target);
            part->last_open = opening;
            part->last_front = front;
        }
    } else {
        const float jaw_offset = (p[P_E2] - 78) * (0.5f / 127.0f);
        control->target[CH_JAW] = (part->mode == CHIMERA_BEAT ? 0.4f : CH_NEUTRAL[CH_JAW]) + jaw_offset;
        control->target[CH_VELUM] = CH_NEUTRAL[CH_VELUM] + (p[P_E3] - 77) / 127.0f;
        if (part->mode == CHIMERA_ENGLISH && !gate)
            chimera_release_word(part);
    }

    ch_shape_t shape;
    const int changed = ch_control_step(control, &part->tract);
    if (changed)
        ch_tract_shape(&part->tract, &shape);
    ch_glottis_params_t glottis = control->glottis;
    if (part->mode == CHIMERA_ENGLISH)
        glottis.f0 *= exp2f((pitch - part->note) / 12.0f);
    ch_wave_block(&part->wave, changed ? &shape : 0, control->anat_every, &glottis, control->p_lung);
}

static void chimera_render_voice(track_t *t, voice_t *v, int32_t *out, uint32_t n, const vmod_t *m)
{
    chimera_part_t *part = eng_part(t);
    if (!part->ready)
        return;
    if (v->gate && (part->mode != t->p[P_E0] || part->body != t->p[P_E1]))
        chimera_note_on(t, v);
    const float pitch = m->pitch16 * (1.0f / 16.0f) + m->fine * (17.31234f / 4096.0f);

    for (uint32_t i = 0; i < n;) {
        if (!part->left) {
            if (part->mode >= CHIMERA_SEAL)
                chimera_animal_step(part, t->p, pitch, v->gate);
            else
                chimera_human_step(part, t->p, pitch, v->gate, m);
            part->left = CHIMERA_BLOCK;
        }
        const uint32_t count = n - i < part->left ? n - i : part->left;
        float samples[CTL];
        if (part->mode == CHIMERA_BIRD) {
            for (uint32_t j = 0; j < count; j++)
                samples[j] = ch_bird_sample(&part->model.bird);
        } else {
            ch_wave_render(&part->wave, samples, count);
        }
        for (uint32_t j = 0; j < count; j++) {
            float sample = samples[j];
            if (!isfinite(sample))
                sample = 0.0f;
            if (part->mode == CHIMERA_BEAT)
                sample = chimera_beat_mic(part, sample);
            const float scaled = sample * 26000.0f;
            const int32_t pcm = scaled > 32767.0f ? 32767 : scaled < -32767.0f ? -32767 : (int32_t)scaled;
            out[i + j] += voice_amp2(pcm, m, i + j);
        }
        part->left -= (uint8_t)count;
        i += count;
    }
}

static const preset_t CHIMERA_PRESETS[] = {
    {"OPEN THROAT", {CHIMERA_VOWEL, 0, 78, 77, 58, 19, 64, 3}, {3, 72, 124, 67}, 0, 1, TAG(PT_LEAD)},
    {"HEAD VOICE", {CHIMERA_VOWEL, 1, 47, 42, 53, 28, 47, 4}, {5, 72, 123, 71}, 0, 1, TAG(PT_LEAD)},
    {"SMALL SINGER", {CHIMERA_VOWEL, 2, 42, 55, 58, 16, 48, 5}, {3, 72, 118, 66}, 0, 1, TAG(PT_KEYS)},
    {"SING HEY", {CHIMERA_ENGLISH, 1, 78, 77, 65, 18, 54, 1}, {0, 73, 124, 89}, 0, 1, TAG(PT_LEAD)},
    {"BEAT BOX", {CHIMERA_BEAT, 0, 78, 77, 59, 18, 72, 0}, {0, 72, 127, 91}, 0, 1, TAG(PT_DRUM)},
    {"SEAL CALL", {CHIMERA_SEAL, 0, 44, 76, 46, 12, 76, 6}, {0, 70, 123, 67}, 0, 1, TAG(PT_FX)},
    {"SONGBIRD", {CHIMERA_BIRD, 0, 70, 61, 54, 105, 45, 8}, {1, 70, 123, 67}, 0, 1, TAG(PT_FX)},
};

static const param_desc_t *chimera_desc(const track_t *t, uint32_t k)
{
    static const param_desc_t syllable = {"SYLLABLE", CHIMERA_WORD_NAMES, 0, 10, 3, F_ENUM, 0};
    static const param_desc_t beat = {"HIT", CHIMERA_BEATS, 0, 10, 3, F_ENUM, 0};
    static const char *const animal_sizes[] = {"LARGE", "MEDIUM", "SMALL"};
    static const param_desc_t animal_body = {"SIZE", animal_sizes, 0, 2, 0, F_ENUM, 0};
    static const param_desc_t jaw = {"JAW", 0, 0, 127, 78, F_PCT, 0};
    static const param_desc_t velum = {"VELUM", 0, 0, 127, 77, F_PCT, 0};
    static const param_desc_t seal[] = {
        {"MOUTH", 0, 0, 127, 78, F_PCT, 0}, {"PULSE", 0, 0, 127, 77, F_PCT, 0},
        {"PRESSURE", 0, 0, 127, 58, F_PCT, 0}, {"ROUGH", 0, 0, 127, 19, F_PCT, 0},
        {"BRIGHT", 0, 0, 127, 64, F_PCT, 0}, {"PULSE RATE", CHIMERA_SEAL_RATE_NAMES, 0, 10, 3, F_ENUM, 0},
    };
    static const param_desc_t bird[] = {
        {"GAPE", 0, 0, 127, 78, F_PCT, 0}, {"VOICES", 0, 0, 127, 77, F_PCT, 0},
        {"PRESSURE", 0, 0, 127, 58, F_PCT, 0}, {"PURITY", 0, 0, 127, 19, F_PCT, 0},
        {"DRIVE", 0, 0, 127, 64, F_PCT, 0}, {"INTERVAL", CHIMERA_BIRD_INTERVAL_NAMES, 0, 10, 3, F_ENUM, 0},
    };
    if (k == 1u && t->p[P_E0] >= CHIMERA_SEAL)
        return &animal_body;
    if (k >= 2u && t->p[P_E0] == CHIMERA_SEAL)
        return &seal[k - 2u];
    if (k >= 2u && t->p[P_E0] == CHIMERA_BIRD)
        return &bird[k - 2u];
    if (k == 7u && t->p[P_E0] == CHIMERA_ENGLISH)
        return &syllable;
    if (k == 7u && t->p[P_E0] == CHIMERA_BEAT)
        return &beat;
    if (t->p[P_E0] == CHIMERA_ENGLISH || t->p[P_E0] == CHIMERA_BEAT) {
        if (k == 2u)
            return &jaw;
        if (k == 3u)
            return &velum;
    }
    return 0;
}

static const engine_t ENG_CHIMERA = {
    .name = "CHIMERA", .page_title = {"TRACT", "SOURCE"},
    .edit = {
        {"MODE", CHIMERA_MODES, 0, NELEM(CHIMERA_MODES) - 1, CHIMERA_VOWEL, F_ENUM, 0},
        {"BODY", CHIMERA_BODIES, 0, NBODIES - 1, 0, F_ENUM, 0},
        {"OPEN", 0, 0, 127, 78, F_PCT, 0}, {"FRONT", 0, 0, 127, 77, F_PCT, 0},
        {"PRESSURE", 0, 0, 127, 58, F_PCT, 0}, {"AIR", 0, 0, 127, 19, F_PCT, 0},
        {"TENSION", 0, 0, 127, 64, F_PCT, 0}, {"BRIGHT", 0, 0, 10, 3, F_INT, 0},
    },
    .presets = CHIMERA_PRESETS, .npresets = NELEM(CHIMERA_PRESETS),
    .knob = {P_E0, P_E1, P_E2, P_E3},
    .poly = 1, .note_on = chimera_note_on, .render = chimera_render_voice, .desc = chimera_desc,
};
