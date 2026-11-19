/* The same performance is built twice: stock web_render and Android wrapper. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define C1_ON() 1
#define C1_RUN(fn, arg) ((fn)(arg), 1)
#define C1_WAIT() 0
#define C1_SPLIT() 0
#ifdef SKIFF_ORACLE
#include "felucca_web.c"
#else
#define SKIFF_HOST_TEST 1
#include "skiff_runtime.c"
#endif

static uint32_t h = 2166136261u;
static uint32_t nonzero;
static void hash_byte(uint8_t b) { h = (h ^ b) * 16777619u; }
static void hash_word(uint32_t x)
{ hash_byte((uint8_t)x); hash_byte((uint8_t)(x >> 8)); hash_byte((uint8_t)(x >> 16)); hash_byte((uint8_t)(x >> 24)); }
static void render_hash(unsigned n)
{
    unsigned i;
#ifdef SKIFF_ORACLE
    web_render(n);
    for (i = 0; i < n; i++) {
        float l = out_l[i] * 32768.0f, r = out_r[i] * 32768.0f;
        int32_t a = l > 32767.0f ? 32767 : l < -32768.0f ? -32768 : (int32_t)l;
        int32_t b = r > 32767.0f ? 32767 : r < -32768.0f ? -32768 : (int32_t)r;
        nonzero += a != 0 || b != 0;
        hash_word((uint16_t)a | (uint32_t)(uint16_t)b << 16);
    }
#else
    int16_t out[2048];
    skiff_render(out, n);
    for (i = 0; i < n; i++) {
        nonzero += out[2u * i] != 0 || out[2u * i + 1u] != 0;
        hash_word((uint16_t)out[2u * i] | (uint32_t)(uint16_t)out[2u * i + 1u] << 16);
    }
#endif
}
static void midi(uint8_t status, uint8_t note, uint8_t vel)
{
    uint32_t packet = 0x09u | (uint32_t)status << 8 | (uint32_t)note << 16 | (uint32_t)vel << 24;
#ifdef SKIFF_ORACLE
    midi_enqueue(packet, 1);
#else
    skiff_midi_in(packet, 1);
#endif
}
static void realtime(uint8_t status)
{
    uint32_t packet = 0x0Fu | (uint32_t)status << 8;
#ifdef SKIFF_ORACLE
    midi_enqueue(packet, 1);
#else
    skiff_midi_in(packet, 1);
#endif
}
static void button(uint32_t mask)
{
#ifdef SKIFF_ORACLE
    web_buttons(mask); ui_input();
#else
    int32_t turns[7] = {0}; skiff_input(mask, 0, turns);
#endif
}
static void midi_hash(void)
{
    while (mo_r != mo_w) {
        hash_word(midi_out_q[mo_r % MQO]);
        mo_r++;
    }
}
int main(void)
{
    static const unsigned frames[] = {127u, 257u, 512u, 64u};
    unsigned e, p, i, total = 0, shown = 0, audible = 0, engines = 0;
#ifdef SKIFF_ORACLE
    web_nor_erase(); web_boot();
    usb.config = 1;
    while (web_boot_ms) web_ms();
    ring_r = ring_w; mo_r = mo_w;
#else
    skiff_boot(0, 0); skiff_master_gain(master_of_pot(700u));
#endif
    midi(0x90u, 60u, 100u);
    render_hash(127u); render_hash(512u);
    midi(0x80u, 60u, 0u); render_hash(257u); midi_hash();
    printf("default %08x\n", h);
    for (e = 0; e < NENGINES; e++) {
        if (!eng_ok(e)) continue;
        engines++;
        shown += preset_shown(e);
        for (p = 0; p < ENGINES[e]->npresets; p++) {
            h = 2166136261u;
            nonzero = 0;
            trk_all_off(&trk[0]);
            host_preset(&trk[0], e, p);
            midi(0x90u, 60u, 100u);
            for (i = 0; i < 4u; i++) render_hash(frames[i]);
            midi(0x80u, 60u, 0u);
            render_hash(257u);
            hash_word(trk[0].eng_req);
            hash_word(trk_nvoice(&trk[0]));
            for (i = 0; i < NVOICE; i++) hash_word(trk[0].v[i].active);
            midi_hash();
            printf("%u/%u %08x %u\n", e, p, h, nonzero);
            audible += nonzero != 0;
            total++;
        }
    }
    h = 2166136261u;
    button(1u << B_PLAY); button(0u);
    render_hash(512u); render_hash(127u); midi_hash(); hash_word(song.playing);
    button(1u << B_PLAY); button(0u);
    render_hash(257u); midi_hash(); hash_word(song.playing);
    printf("transport %08x\n", h);
    song.g[G_CLOCK] = 1;
    h = 2166136261u;
    realtime(0xFAu);
    for (i = 0; i < 24u; i++) realtime(0xF8u);
    render_hash(512u); hash_word(song.playing); midi_hash();
    realtime(0xFCu); render_hash(127u); hash_word(song.playing); midi_hash();
    printf("external-clock %08x\ncount %u shown %u engines %u audible %u\n", h, total, shown, engines, audible);
    return 0;
}
