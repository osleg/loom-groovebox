/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef SKIFF_DX386_RUNTIME_H
#define SKIFF_DX386_RUNTIME_H

#include <stdint.h>

#define DX386_POLY 4u
#define DX386_TRACKS 8u
#define DX386_FACTORY_COUNT 4u
#define DX386_USER_LINE DX386_FACTORY_COUNT
#define DX386_TEXT_CAPACITY 64u

typedef struct {
    char text[DX386_TEXT_CAPACITY];
    uint8_t syllable, body, breath, edge, length, root;
} dx386_prompt_t;

typedef struct {
    uint16_t length, loop_start, loop_end, crossfade;
    int16_t pcm[11025];
} dx386_clip_t;

int dx386_start_worker(void);
void dx386_stop_worker(void);
int dx386_set_text(const char *text);

void dx386_make_prompt(dx386_prompt_t *prompt, unsigned phrase, unsigned syllable, unsigned body,
                       unsigned breath, unsigned edge, unsigned length, unsigned note);
const dx386_clip_t *dx386_find_clip(unsigned track, unsigned voice, const dx386_prompt_t *prompt);

#endif
