/* Focused stock host check; compile against the same pinned tree as Android. */
#define SKIFF_HOST_TEST 1
#include "skiff_runtime.c"
#include <assert.h>
#include <stdio.h>
#if __has_include("windjammer_marker_detector.h")
#include "windjammer_marker_detector.h"
#define skiff_marker_detector_t windjammer_marker_detector_t
#define skiff_marker_reset windjammer_marker_reset
#define skiff_marker_feed windjammer_marker_feed
#else
#include "skiff_marker_detector.h"
#endif
static uint8_t saved_a[sizeof nor], saved_b[sizeof nor];
static int16_t marker_source[8u * 2048u], marker_48k[18000], marker_return[8u * 2048u];

static void check_short_probe_taps(void)
{
    static const int gains[] = {4096, 64, 4, 0};
    int32_t turns[7] = {0};
    int16_t pcm[256];
    uint32_t g;
    for (g = 0; g < sizeof gains / sizeof gains[0]; g++) {
        uint32_t first = UINT32_MAX, frame;
        skiff_boot(0, 0);
        skiff_start_diagnostic_pattern();
        skiff_master_gain(gains[g]);
        for (frame = 0; frame < 160; frame++) skiff_render(pcm, 128);
        skiff_input_serial(0, 1u, turns, 101u + g);
        skiff_input(0, 0u, turns);       /* tap ends before the next 128-frame pump */
        for (frame = 0; frame < 8; frame++) {
            uint32_t sample;
            skiff_render(pcm, 128);
            for (sample = 0; sample < 128; sample++)
                if (pcm[2u * sample + 1u] && first == UINT32_MAX) {
                    first = 128u * frame + sample;
                    assert(diagnostic_onset_count == 1);
                    assert(diagnostic_onsets[0] >> 32 == 101u + g);
                    assert((uint32_t)diagnostic_onsets[0] == sample);
                }
        }
        if (gains[g]) {
            assert(first < 128u && diagnostic_probe_marks == 1);
            for (frame = 0; frame < 120; frame++) skiff_render(pcm, 128);
            skiff_input_serial(0, 1u, turns, 201u + g);
            skiff_render(pcm, 128);
            assert(diagnostic_probe_marks == 2); /* a distinct attack after ~350 ms */
            assert(diagnostic_onset_count == 1);
            assert(diagnostic_onsets[0] >> 32 == 201u + g);
            skiff_input(0, 0u, turns);
        } else {
            assert(first == UINT32_MAX && diagnostic_probe_marks == 0);
            skiff_master_gain(4096); /* the old silent tap cannot be dated now */
            for (frame = 0; frame < 8; frame++) skiff_render(pcm, 128);
            assert(diagnostic_probe_marks == 0);
        }
        skiff_stop();
    }
    /* A quiet low-gain press cannot become a late "onset" when MASTER rises. */
    skiff_boot(0, 0);
    skiff_start_diagnostic_pattern();
    skiff_master_gain(1);
    for (g = 0; g < 160; g++) skiff_render(pcm, 128);
    skiff_input(0, 1u, turns);
    skiff_render(pcm, 128);
    assert(diagnostic_probe_marks == 0);
    skiff_master_gain(4096);
    for (g = 0; g < 8; g++) skiff_render(pcm, 128);
    assert(diagnostic_probe_marks == 0);
    skiff_input(0, 0u, turns);
    for (g = 0; g < 160; g++) skiff_render(pcm, 128);
    skiff_input(0, 1u, turns);
    skiff_render(pcm, 128);
    assert(diagnostic_probe_marks == 1);
    skiff_stop();
}

/* Plausible Android float-mixer path: linear 44.1 -> 48 -> 44.1 kHz with
 * low media volume. Feed the resulting PCM to the actual controller detector. */
static uint32_t resample_mono(const int16_t *in, uint32_t in_count, uint32_t in_rate,
                              int16_t *out, uint32_t capacity, uint32_t out_rate, float volume)
{
    uint32_t j, count = (uint32_t)((uint64_t)in_count * out_rate / in_rate);
    assert(count <= capacity);
    for (j = 0; j < count; j++) {
        uint64_t position = (uint64_t)j * in_rate;
        uint32_t base = (uint32_t)(position / out_rate);
        uint32_t fraction = (uint32_t)(position % out_rate);
        float sample = in[base] + (base + 1u < in_count ?
            (in[base + 1u] - in[base]) * (float)fraction / out_rate : 0.0f);
        out[j] = (int16_t)(sample * volume);
    }
    return count;
}
static int view_has(const uint8_t *view, unsigned len, unsigned tag)
{
    unsigned at;
    for (at = CAFA_NATIVE_HEADER; at + 3u <= len;) {
        unsigned n = cafa_native_wire_u14(view + at + 1u);
        if (view[at] == tag) return 1;
        at += 3u + n;
    }
    return 0;
}

static void check_held_layers(void)
{
    static const struct { unsigned button, screen; } cases[] = {
        {B_FX, CAFA_SCREEN_FX_LAYER}, {B_GLO, CAFA_SCREEN_GLO_LAYER},
        {B_SCL, CAFA_SCREEN_SEL_LAYER}, {B_EDIT, CAFA_SCREEN_EDIT_LAYER},
        {B_SEQ, CAFA_SCREEN_SEQ_LAYER}, {B_ARP, CAFA_SCREEN_ARP_LAYER},
        {B_HOME, CAFA_SCREEN_SHIFT},
    };
    int16_t audio[2048];
    int32_t turns[7] = {0};
    uint8_t view[3072];
    for (unsigned i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        unsigned buttons = 1u << cases[i].button, length;
        skiff_boot(0, 0);
        skiff_input(buttons, 0, turns);
        for (unsigned block = 0; block < 40u; block++) skiff_render(audio, 1024);
        skiff_input(buttons, 0, turns);
        length = skiff_make_view(view);
        skiff_capture_cards(view, length);
        assert(cafa_native_view_valid(view, length) && view[6] == cases[i].screen);
        assert(view[CAFA_NATIVE_CONTROLS + 32u] != 0u);
        if (i + 1u == sizeof cases / sizeof cases[0]) {
            skiff_input(buttons | (1u << B_REC), 0, turns);
            skiff_input(0, 0, turns);
            length = skiff_make_view(view);
            assert(cafa_native_view_valid(view, length) && view[6] == CAFA_SCREEN_REC_LAYER);
            assert(view[CAFA_NATIVE_CONTROLS + 32u] != 0u);
        }
        skiff_stop();
    }
}

int main(void)
{
    int32_t turns[7] = {0};
    int16_t audio[4096];
    uint32_t i, live = 0, revision, before_stop;
    uint32_t packets[MQO], count;
    uint64_t timed[MQO];
    uint32_t clocks, first_clock;
    skiff_boot(0, 0);
    assert(runtime_booted && (NTRK == 6 || NTRK == 8) && NVOICE == 16);
    assert(web_sample_rate() == 44100);
    assert(web_now_ms() >= 430 && !web_playing());
    memcpy(saved_a, nor, sizeof saved_a);

    skiff_input(0, 1u << 5, turns);
    skiff_render(audio, 2048);
    for (i = 0; i < 4096; i++) assert(audio[i] == 0); /* awaiting the first physical scan */
    skiff_master_gain(0);
    assert(song.master_q12 == 0);
    skiff_render(audio, 2048);
    for (i = 0; i < 4096; i++) assert(audio[i] == 0);
    skiff_master_gain(4096);
    assert(song.master_q12 == 4096);
    skiff_render(audio, 2048);
    live = 0;
    for (i = 0; i < 4096; i++) live |= audio[i] != 0;
    assert(live);                        /* full stock gain reaches both audio channels */
    skiff_input(0, 0, turns);
    skiff_render(audio, 2048);
    (void)skiff_midi_out(packets, MQO);

    /* Two edges arrive before a render. They must occupy separate audio
     * halves, preserving a real note attack and release. */
    skiff_input(0, 1u << 7, turns);
    skiff_input(0, 0, turns);
    skiff_render(audio, 2048);
    for (i = 0; i < 4096; i++)
        live |= audio[i] != 0;
    assert(live && key_read == key_write && fm1_in.notes == 0);
    count = skiff_midi_out(packets, MQO);
    live = 0;
    for (i = 0; i < count; i++)
        live |= ((packets[i] >> 8) & 0xf0u) == 0x90u;
    assert(live);                         /* the panel key went out */

    /* The stock MIDI parser hears an external note, without echoing it. */
    trk[0].p[P_VOICE] = V_POLY;
    assert(skiff_midi_in(0x09u | 0x90u << 8 | 60u << 16 | 100u << 24, 1));
    skiff_render(audio, 2048);
    assert(trk[0].v[0].active || trk[0].v[1].active);
    for (i = 1; i < 16; i++) {
        assert(skiff_midi_in(0x09u | 0x90u << 8 | (60u + i) << 16 | 100u << 24, 1));
        skiff_render(audio, 256);
    }
    live = 0;
    for (i = 0; i < NVOICE; i++)
        live += trk[0].v[i].active != 0;
    assert(live == 16);
    count = skiff_midi_out(packets, MQO);
    for (i = 0; i < count; i++)
        assert(((packets[i] >> 8) & 0xf0u) != 0x90u);
    assert(skiff_midi_in(0x08u | 0x80u << 8 | 60u << 16, 1));
    skiff_render(audio, 2048);
    (void)skiff_midi_out(packets, MQO);

    /* Local transport emits Start, then the stock clock's pulses; stop
     * emits Stop and any owed note releases. */

    song.g[G_BPM] = 240;
    skiff_input(1u << B_PLAY, 0, turns);
    skiff_input(0, 0, turns);
    skiff_render(audio, 2048);
    assert(song.playing);
    count = skiff_midi_out_timed(timed, MQO);
    live = clocks = first_clock = 0;
    for (i = 0; i < count; i++) {
        uint32_t status = (uint32_t)(timed[i] >> 8) & 0xffu;
        int32_t offset = (int32_t)(timed[i] >> 32);
        assert(offset >= 0 && offset < 2048);
        live |= status == 0xFAu;
        if (status == 0xF8u) {
            if (clocks) assert((uint32_t)offset > first_clock);
            first_clock = (uint32_t)offset;
            clocks++;
        }
    }
    assert(live && clocks >= 2);       /* two stock clock pulses within one buffer */
    skiff_render(audio, 512);
    count = skiff_midi_out_timed(timed, MQO);
    clocks = 0;
    for (i = 0; i < count; i++)
    {
        assert((int32_t)(timed[i] >> 32) >= 0 && (int32_t)(timed[i] >> 32) < 512);
        clocks += (((uint32_t)timed[i] >> 8) & 0xffu) == 0xF8u;
    }
    assert(clocks >= 1);
    before_stop = skiff_revision();
    skiff_stop();
    count = skiff_midi_out(packets, MQO);
    live = 0;
    for (i = 0; i < count; i++)
        live |= ((packets[i] >> 8) & 0xffu) == 0xFCu;
    assert(live);
    assert(!song.playing && !fm1_in.notes && !fm1_in.buttons);
    for (i = 0; i < NTRK; i++) {
        uint32_t j;
        for (j = 0; j < NVOICE; j++)
            assert(!trk[i].v[j].active);
    }

    revision = skiff_revision();
    assert(revision > before_stop && nor[0] != 0);
    memcpy(saved_b, nor, sizeof saved_b);
    assert(skiff_saved_nor_valid(saved_b, sizeof saved_b));
    assert(!skiff_saved_nor_valid(saved_b, sizeof saved_b - 1u));
    memset(saved_b, 0, sizeof saved_b);
    assert(!skiff_saved_nor_valid(saved_b, sizeof saved_b));
    memcpy(saved_b, nor, sizeof saved_b);
    skiff_boot(0, 0);                   /* attaching again keeps the session */
    assert(skiff_revision() == revision);
    song.g[G_CLOCK] = 1;                 /* USB external clock */
    assert(skiff_midi_in(0x0Fu | 0xFAu << 8, 1));
    for (i = 0; i < 24; i++)
        assert(skiff_midi_in(0x0Fu | 0xF8u << 8, 1));
    skiff_render(audio, 2048);
    assert(song.playing);
    assert(skiff_midi_in(0x0Fu | 0xFCu << 8, 1));
    skiff_render(audio, 2048);
    assert(!song.playing);
    skiff_stop();
    skiff_boot(saved_a, sizeof saved_a);
    assert(trk[0].p[P_VOICE] != V_POLY && !song.playing);
    skiff_stop();
    skiff_boot(saved_b, sizeof saved_b);
    assert(trk[0].p[P_VOICE] == V_POLY && !song.playing);
    assert(!skiff_master_ready && song.master_q12 == 0);
    skiff_master_gain(2048);
    ui.menu = 1;ui.menu_sel = MI_PANEL;
    skiff_input(1u << B_OCTUP, 0, turns);
    assert(skiff_device_screen == 70 && ui.menu == 1);
    skiff_exit_device_menu();
    assert(!skiff_device_screen && ui.menu == 1 && ui.menu_sel == MI_PANEL);
    skiff_input(1u << B_OCTUP, 0, turns); /* same held press cannot enter panel_setup */
    assert(!skiff_device_screen && ui.menu == 1);
    skiff_input(0, 0, turns);
    song.g[G_CLOCK] = 2;                 /* TRS external clock */
    assert(skiff_midi_in(0x0Fu | 0xFAu << 8, 2));
    skiff_render(audio, 2048);
    assert(song.playing);
    assert(skiff_midi_in(0x0Fu | 0xFCu << 8, 2));
    skiff_render(audio, 2048);
    assert(!song.playing);
    {
        uint8_t view[3072]; unsigned len, screen;
        ui.menu = ui.home = ui.layer = ui.shift = ui.confirm = 0;
        trk[song.sel].eng_req = ENGI_FM6;
        for (screen = 15u; screen <= 18u; screen++) {
            ui.page = (uint8_t)screen; len = skiff_make_view(view);
            assert(cafa_native_view_valid(view, len));
            assert(view_has(view, len, CAFA_NATIVE_ROWS) && view_has(view, len, CAFA_NATIVE_EDGES) &&
                   view_has(view, len, CAFA_NATIVE_SERIES));
        }
        trk[song.sel].eng_req = 0;
        for (screen = 15u; screen <= 16u; screen++) {
            ui.page = (uint8_t)screen; len = skiff_make_view(view);
            assert(cafa_native_view_valid(view, len) && view_has(view, len, CAFA_NATIVE_CURVE));
        }
        trk[song.sel].eng_req = ENGI_DRUMX;
        ui.page = CAFA_SCREEN_SOUND; len = skiff_make_view(view);
        assert(cafa_native_view_valid(view, len) && view_has(view, len, CAFA_NATIVE_CELLS) &&
               view_has(view, len, CAFA_NATIVE_ROWS));
        ui.page = CAFA_SCREEN_LANE_MIX; len = skiff_make_view(view);
        assert(cafa_native_view_valid(view, len) && view_has(view, len, CAFA_NATIVE_ROWS));
        trk[song.sel].eng_req = ENGI_DRUM;
        ui.page = CAFA_SCREEN_LANES; len = skiff_make_view(view);
        assert(cafa_native_view_valid(view, len) && view_has(view, len, CAFA_NATIVE_ROWS));
        trk[song.sel].eng_req = ENGI_SAMPLE;
        ui.page = CAFA_SCREEN_SAMPLES; len = skiff_make_view(view);
        assert(cafa_native_view_valid(view, len));
        trk[song.sel].eng_req = ENGI_SLICE;
        ui.page = CAFA_SCREEN_SLICES; len = skiff_make_view(view);
        assert(cafa_native_view_valid(view, len));
        trk[song.sel].eng_req = ENGI_DIGITAL;
        for (screen = 24u; screen <= 27u; screen++) {
            ui.page = (uint8_t)screen; len = skiff_make_view(view);
            assert(cafa_native_view_valid(view, len) && view_has(view, len, CAFA_NATIVE_CURVE));
        }
        trk[song.sel].eng_req = 0;
        for (screen = 29u; screen <= 30u; screen++) {
            ui.page = (uint8_t)screen; len = skiff_make_view(view);
            assert(cafa_native_view_valid(view, len));
        }
    }
    /* The diagnostic records a stock key attack amid the running pattern.
     * The reserved right channel must stay clean until that key sounds. */
    skiff_stop();
    skiff_boot(0, 0);
    skiff_start_diagnostic_pattern();
    skiff_master_gain(4096);
    assert(song.g[G_BPM] == 128 && song.sel == 0 && !ui.home &&
           PAGES[ui.page].fam == FAM_EDIT);
    for (i = 1; i < 6; i++) assert(trk[i].seq_active);
    {
        uint8_t before[3072], after[3072];
        int32_t value = trk[0].p[P_E0];
        (void)skiff_make_view(before);
        turns[EN_K1] = 3;
        skiff_input(0, 0, turns);
        turns[EN_K1] = 0;
        (void)skiff_make_view(after);
        assert(trk[0].p[P_E0] != value);
        assert(memcmp(before + CAFA_NATIVE_CONTROLS,
                      after + CAFA_NATIVE_CONTROLS, 36u) != 0);
    }
    {
    uint32_t sounding = 0;
    for (i = 0; i < 12; i++) {
        uint32_t f, busy = 0;
        skiff_render(audio, 2048);
        for (f = 0; f < 2048; f++) {
            busy |= audio[2u * f] != 0;
            assert(audio[2u * f + 1u] == 0);
        }
        assert(busy);
        for (f = 1; f < NTRK; f++) sounding |= (trk[f].peak > 0) << f;
    }
    assert((sounding & 0x3eu) == 0x3eu);
    }
    assert(diagnostic_probe_marks == 0);
    {
        skiff_marker_detector_t detector;
        uint32_t found = 0;
        skiff_marker_reset(&detector);
        for (i = 0; i < 2048; i++)
            assert(!skiff_marker_feed(&detector, i, audio[2u * i + 1u], &found));
    }
    skiff_input(0, 1u, turns);
    {
        uint32_t marker = 0;
        for (i = 0; i < 8 && marker < 32u; i++) {
            uint32_t f;
            skiff_render(audio, 2048);
            for (f = 0; f < 2048; f++) {
                int16_t right = audio[2u * f + 1u];
                marker_source[2048u * i + f] = right;
                if (!right) continue;
                assert(marker < 32u);
                assert(right == ((marker & 8u) ? -24576 : 24576));
                marker++;
            }
        }
        assert(marker == 32u);
        {
            skiff_marker_detector_t detector;
            uint32_t n48, n44, f, found = 0, hits = 0;
            n48 = resample_mono(marker_source, (i + 1u) * 2048u, 44100u,
                                marker_48k, 18000u, 48000u, 1.0f);
            n44 = resample_mono(marker_48k, n48, 48000u,
                                marker_return, 8u * 2048u, 44100u, 0.005f);
            skiff_marker_reset(&detector);
            /* The verified silent right-channel block immediately before
             * key receipt precedes this rendered marker on the USB stream. */
            for (f = 0; f < 8; f++)
                assert(!skiff_marker_feed(&detector, f, 0, &found));
            for (f = 0; f < n44; f++)
                hits += skiff_marker_feed(&detector, f + 8u, marker_return[f], &found);
            assert(hits == 1);
        }
    }
    assert(diagnostic_probe_marks == 1);
    skiff_input(0, 0, turns);
    skiff_input(0, 1u, turns);
    skiff_render(audio, 2048);
    assert(diagnostic_arm_rejected == 1 && diagnostic_probe_marks == 1);
    skiff_input(0, 0, turns);
    for (i = 0; i < 8; i++) skiff_render(audio, 2048);
    assert(diagnostic_probe_marks == 1);
    skiff_input(0, 1u, turns);
    for (i = 0; i < 4; i++) skiff_render(audio, 2048);
    assert(diagnostic_probe_marks == 2);
    skiff_stop();
    check_short_probe_taps();
    check_held_layers();
    printf("stock runtime: notes, sequencer, stop, NOR revision OK\n");
    return 0;
}
