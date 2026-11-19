/* SPDX-License-Identifier: GPL-3.0-only */
#include "dx386_runtime.h"
#include "thirdparty/dx/speech.h"

#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdbool.h>
#include <stdatomic.h>
#include <string.h>

#define DX386_ROOTS 5u
#define DX386_FIRST_ROOT 36u
#define DX386_SLOTS 2u
#define DX386_HZ 11025u

static const char *const DX386_FACTORY_LINES[DX386_FACTORY_COUNT] = {
    "we are awake", "hold the light", "voices rise", "sing through me",
};

static const uint8_t DX386_FACTORY_SOUND[DX386_FACTORY_COUNT][4] = {
    {50, 12, 24, 70}, {33, 6, 41, 54}, {62, 14, 12, 83}, {55, 36, 28, 77},
};

enum { DX386_FREE, DX386_PENDING, DX386_RENDERING, DX386_READY, DX386_FAILED };

typedef struct {
    dx386_prompt_t prompt;
    dx386_clip_t clip;
    atomic_uint state;
} dx386_slot_t;

static dx386_slot_t dx386_slots[DX386_TRACKS][DX386_POLY][DX386_SLOTS];
static dx386_clip_t dx386_factory[DX386_FACTORY_COUNT][DX386_ROOTS];
static char dx386_user_text[DX386_TEXT_CAPACITY];
static pthread_t dx386_worker;
static sem_t dx386_available;
static atomic_bool dx386_stopping;
static bool dx386_running;
static bool dx386_initialized;

void dx_speech_idle(void) {}

static unsigned dx386_root(unsigned note)
{
    if (note <= DX386_FIRST_ROOT)
        return DX386_FIRST_ROOT;
    unsigned index = (note - DX386_FIRST_ROOT + 6u) / 12u;
    if (index >= DX386_ROOTS)
        index = DX386_ROOTS - 1u;
    return DX386_FIRST_ROOT + index * 12u;
}

void dx386_make_prompt(dx386_prompt_t *prompt, unsigned phrase, unsigned syllable, unsigned body,
                       unsigned breath, unsigned edge, unsigned length, unsigned note)
{
    const char *text = phrase < DX386_FACTORY_COUNT ? DX386_FACTORY_LINES[phrase] :
        dx386_user_text[0] ? dx386_user_text : DX386_FACTORY_LINES[0];
    memset(prompt, 0, sizeof *prompt);
    memcpy(prompt->text, text, strlen(text));
    prompt->syllable = (uint8_t)syllable;
    prompt->body = (uint8_t)body;
    prompt->breath = (uint8_t)breath;
    prompt->edge = (uint8_t)edge;
    prompt->length = (uint8_t)length;
    prompt->root = (uint8_t)dx386_root(note);
}

int dx386_set_text(const char *text)
{
    if (!text)
        return -1;
    bool has_word = false;
    size_t n = 0;
    for (; text[n]; n++) {
        const unsigned char c = (unsigned char)text[n];
        if (n >= DX386_TEXT_CAPACITY - 1u || c < 32 || c > 126)
            return -1;
        has_word |= (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
    if (n != 0 && !has_word)
        return -1;
    memcpy(dx386_user_text, text, n + 1u);
    return 0;
}

/* Klatt sustains the selected nucleus during this render. Find its stable,
 * loudest interior so key-up can leave the loop and continue the ending. */
static void dx386_set_loop(const dx386_prompt_t *prompt, dx386_clip_t *clip)
{
    const float hz = 440.0f * exp2f(((float)prompt->root - 69.0f) * (1.0f / 12.0f));
    const unsigned period = (unsigned)(DX386_HZ / hz + 0.5f);
    const unsigned window = (unsigned)(DX386_HZ * 6.0f / hz + 0.5f);
    int32_t loudest = 0;
    unsigned start = clip->length / 3u;
    for (unsigned i = start; i + window < clip->length * 3u / 4u; i += period) {
        int32_t level = 0;
        for (unsigned j = 0; j < window; j++) {
            const int32_t sample = clip->pcm[i + j];
            level += sample < 0 ? -sample : sample;
        }
        if (level > loudest) {
            loudest = level;
            start = i;
        }
    }
    if (start < period || start + window >= clip->length || !loudest)
        return;
    clip->loop_start = (uint16_t)start;
    clip->loop_end = (uint16_t)(start + window);
    clip->crossfade = (uint16_t)period;
}

static bool dx386_render_clip(const dx386_prompt_t *prompt, dx386_clip_t *clip)
{
    const uint8_t sound[4] = {prompt->body, prompt->breath, prompt->edge, 0};
    const unsigned capacity = sizeof clip->pcm / sizeof clip->pcm[0];
    dx_sing_note_t note = {8820u + prompt->length * 110u, prompt->root};
    int code;
    unsigned syllable = prompt->syllable;
    clip->length = clip->loop_start = clip->loop_end = clip->crossfade = 0;
    dx_speech_sound(sound);
    const int count = dx_speech_scan(prompt->text);
    if (count <= 0)
        return false;
    syllable %= (unsigned)count;
    code = dx_speech_sing_into(0, prompt->text, syllable, &note, 1u, 0u, clip->pcm, capacity);
    if (code == -3) {
        note.samples = (uint32_t)capacity * 2u;
        code = dx_speech_sing_into(0, prompt->text, syllable, &note, 1u, 0u, clip->pcm, capacity);
    }
    if (code != 0 || !dx_speech_length[0])
        return false;
    clip->length = (uint16_t)dx_speech_length[0];
    dx386_set_loop(prompt, clip);
    return true;
}

static void *dx386_work(void *unused)
{
    (void)unused;
    for (;;) {
        int status;
        do {
            status = sem_wait(&dx386_available);
        } while (status != 0 && errno == EINTR);
        if (status != 0 || atomic_load_explicit(&dx386_stopping, memory_order_acquire))
            return NULL;
        for (unsigned t = 0; t < DX386_TRACKS; t++)
            for (unsigned v = 0; v < DX386_POLY; v++)
                for (unsigned i = 0; i < DX386_SLOTS; i++) {
                    if (atomic_load_explicit(&dx386_stopping, memory_order_relaxed))
                        return NULL;
                    dx386_slot_t *s = &dx386_slots[t][v][i];
                    unsigned state = DX386_PENDING;
                    if (atomic_compare_exchange_strong_explicit(&s->state, &state, DX386_RENDERING,
                                                                memory_order_acquire, memory_order_relaxed)) {
                        const bool ok = dx386_render_clip(&s->prompt, &s->clip);
                        atomic_store_explicit(&s->state, ok ? DX386_READY : DX386_FAILED, memory_order_release);
                    }
                }
    }
}

int dx386_start_worker(void)
{
    if (dx386_running)
        return 0;
    if (!dx386_initialized) {
        if (dx_speech_init())
            return -1;
        for (unsigned i = 0; i < DX386_FACTORY_COUNT; i++)
            for (unsigned j = 0; j < DX386_ROOTS; j++) {
                dx386_prompt_t p;
                const uint8_t *sound = DX386_FACTORY_SOUND[i];
                dx386_make_prompt(&p, i, 0, sound[0], sound[1], sound[2], sound[3], DX386_FIRST_ROOT + j * 12u);
                dx386_render_clip(&p, &dx386_factory[i][j]);
            }
        dx386_initialized = true;
    }
    if (sem_init(&dx386_available, 0, 0))
        return -1;
    atomic_store_explicit(&dx386_stopping, false, memory_order_relaxed);
    if (pthread_create(&dx386_worker, NULL, dx386_work, NULL)) {
        sem_destroy(&dx386_available);
        return -1;
    }
    dx386_running = true;
    return 0;
}

void dx386_stop_worker(void)
{
    if (!dx386_running)
        return;
    atomic_store_explicit(&dx386_stopping, true, memory_order_release);
    sem_post(&dx386_available);
    pthread_join(dx386_worker, NULL);
    sem_destroy(&dx386_available);
    for (unsigned t = 0; t < DX386_TRACKS; t++)
        for (unsigned v = 0; v < DX386_POLY; v++)
            for (unsigned i = 0; i < DX386_SLOTS; i++) {
                atomic_uint *state = &dx386_slots[t][v][i].state;
                if (atomic_load_explicit(state, memory_order_relaxed) == DX386_PENDING)
                    atomic_store_explicit(state, DX386_FREE, memory_order_relaxed);
            }
    dx386_running = false;
}

static const dx386_clip_t *dx386_factory_clip(const dx386_prompt_t *prompt)
{
    if (!dx386_initialized || prompt->syllable)
        return NULL;
    for (unsigned i = 0; i < DX386_FACTORY_COUNT; i++) {
        const uint8_t *p = DX386_FACTORY_SOUND[i];
        if (prompt->body == p[0] && prompt->breath == p[1] && prompt->edge == p[2] && prompt->length == p[3] &&
            strcmp(prompt->text, DX386_FACTORY_LINES[i]) == 0) {
            const dx386_clip_t *clip = &dx386_factory[i][(prompt->root - DX386_FIRST_ROOT) / 12u];
            return clip->length ? clip : NULL;
        }
    }
    return NULL;
}

const dx386_clip_t *dx386_find_clip(unsigned track, unsigned voice, const dx386_prompt_t *prompt)
{
    const dx386_clip_t *factory = dx386_factory_clip(prompt);
    if (factory || !dx386_running || track >= DX386_TRACKS || voice >= DX386_POLY)
        return factory;
    dx386_slot_t *free_slot = NULL;
    for (unsigned i = 0; i < DX386_SLOTS; i++) {
        dx386_slot_t *s = &dx386_slots[track][voice][i];
        const unsigned ss = atomic_load_explicit(&s->state, memory_order_acquire);
        if (ss != DX386_FREE && memcmp(&s->prompt, prompt, sizeof *prompt) == 0)
            return ss == DX386_READY ? &s->clip : NULL;
        if (ss != DX386_PENDING && ss != DX386_RENDERING && (!free_slot || ss == DX386_FREE))
            free_slot = s;
    }
    if (free_slot) {
        /* Only the audio owner replaces completed/unused slots. Each voice
         * stops reading its old clip before making a fresh request. */
        free_slot->prompt = *prompt;
        atomic_store_explicit(&free_slot->state, DX386_PENDING, memory_order_release);
        sem_post(&dx386_available);
    }
    return NULL;
}
