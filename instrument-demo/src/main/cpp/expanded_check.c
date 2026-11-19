/* SPDX-License-Identifier: GPL-3.0-only */
#define SKIFF_HOST_TEST 1
#include "skiff_runtime.c"
#include <assert.h>
#include <stdio.h>

static void populate(unsigned part, unsigned length, unsigned position, unsigned pitch)
{
    track_t *track = &trk[part];
    track_select(part);
    assert(set_engine_of(track, 0) == 0);
    track->p[P_SLEN] = (int16_t)length;
    step_t *step = &track->step[position];
    step_clear(step);
    step->n = 1;
    step->time = ST_NOTE;
    step->note[0] = (uint8_t)pitch;
    step->vel = 100;
    track->seq_active = 1;
    assert(motion_put(track, position, P_LEVEL, 93, MOTION_LOCK) == 0);
}

static void check_pattern(unsigned part, unsigned length, unsigned position, unsigned pitch)
{
    track_t *track = &trk[part];
    int16_t value = 0;
    assert(track->p[P_SLEN] == length);
    assert(track->step[position].n == 1 && track->step[position].note[0] == pitch);
    assert(motion_lock_get(track, position, P_LEVEL, &value) && value == 93);
}

#if defined(ENGI_ATEK303)
static void check_engine_catalog(uint8_t *image)
{
    uint8_t view[3072];
    int16_t pcm[1024];
    int32_t turns[7] = {0};
    unsigned pressed = 1u << B_EDIT, length;
    const uint8_t *rows;
    unsigned loud = 0;

    skiff_boot(0, 0);
    track_select(7);
    assert(set_engine_of(TSEL, 0) == 0);
    skiff_master_gain(4096);
    skiff_input(pressed, 0, turns);
    for (unsigned block = 0; block < 70u; block++) skiff_render(pcm, 512);
    turns[EN_K1] = -1;
    for (unsigned i = 0; i < NENGINES && TSEL->eng_req != ENGI_ATEK303; i++)
        skiff_input(pressed, 0, turns);
    turns[EN_K1] = 0;
    length = skiff_make_view(view);
    rows = cafa_native_section(view, length, CAFA_NATIVE_ROWS, 0);
    assert(cafa_native_view_valid(view, length) && view[6] == CAFA_SCREEN_EDIT_LAYER);
    assert(rows && rows[0] == 16u && !memcmp(rows + 1u, "ATEK303 ", 8u));
    assert((rows[1u + 40u] & 1u) && !memcmp(rows + 1u + 15u * 48u, "INIT", 4u));
    skiff_input(pressed, 1u, turns); /* physical white key under the first tile */
    skiff_input(pressed, 0, turns);
    skiff_input(0, 0, turns);
    assert(TSEL->eng_req == ENGI_ATEK303);

    skiff_input(0, 1u, turns);
    for (unsigned block = 0; block < 8u; block++) {
        skiff_render(pcm, 512);
        for (unsigned sample = 0; sample < 1024u; sample++)
            if (pcm[sample] > 128 || pcm[sample] < -128) loud++;
    }
    assert(loud > 64u);
    skiff_input(0, 0, turns);

    assert(favorite_set(ENGI_ATEK303, 0, 1));
    settings_save();
    assert(project_save_as(0, "ACID PROJECT") == 0);
    memcpy(image, nor, sizeof nor);
    skiff_stop();
    skiff_boot(image, sizeof nor);
    project_load(0);
    assert(trk[7].eng_req == ENGI_ATEK303 && favorite_has(ENGI_ATEK303, 0));

    assert(set_engine_of(TSEL, ENGI_DIGITAL) == 0);
    ui.home = ui.menu = ui.layer = 0;
    ui.page = CAFA_SCREEN_EDIT1;
    length = skiff_make_view(view);
    assert(cafa_native_view_valid(view, length) && view[7] == CAFA_NATIVE_VARIANT_FM_OPERATOR);
    assert(cafa_native_section(view, length, CAFA_NATIVE_EDGES, 0));
    skiff_stop();
    puts("ATEK303 held EDIT selection, audio, project and favorite reload, and four-op FM native view: OK");
}

#if defined(ENGI_FMINI)
static void check_donor_engines(uint8_t *image)
{
    int16_t pcm[1024];
    int32_t turns[7] = {0};
    uint8_t view[3072];
    skiff_boot(0, 0);
    track_select(7);
    skiff_master_gain(4096);
    for (unsigned id = ENGI_FMINI; id < NENGINES; id++) {
#if defined(ENGI_LOFI_SMP)
        // The sample flow below supplies a real user sample to these engines.
        if (id == ENGI_LOFI_SMP || id == ENGI_CLOUD)
            continue;
#endif
        const engine_t *engine = ENGINES[id];
        unsigned loud = 0;
        assert(set_engine_of(TSEL, id) == 0);
        TSEL->p[P_CHOR] = TSEL->p[P_DLY] = TSEL->p[P_REV] = 0;
        for (unsigned block = 0; block < 16u; block++) skiff_render(pcm, 512);
        ui.home = 1;
        skiff_input(0, 1u, turns);
        for (unsigned block = 0; block < 56u; block++) {
            skiff_render(pcm, 512);
            for (unsigned sample = 0; sample < 1024u; sample++)
                loud += pcm[sample] > 128 || pcm[sample] < -128;
        }
        skiff_input(0, 0, turns);
        fprintf(stderr, "%s first preset: %u audible samples\n", engine->name, loud);
        assert(loud > 64u);
        assert(project_save_as(0, "DONOR CHECK") == 0);
        ui.home = 0;
        ui.page = drum_track(TSEL) ? CAFA_SCREEN_LANES : CAFA_SCREEN_EDIT1;
        unsigned size = skiff_make_view(view);
        assert(size && cafa_native_view_valid(view, size));
        if (drum_track(TSEL)) assert(view[7] == CAFA_NATIVE_VARIANT_DRUM_LANES);
#if defined(ENGI_CHIMERA)
        if (id == ENGI_CHIMERA) {
            const uint32_t context = skiff_edit_context();
            assert(!memcmp(view + CAFA_NATIVE_CONTROLS + 72u, "OPEN ", 5u));
            apply_preset_to(TSEL, 5u); /* SEAL CALL changes knob 3 from OPEN to MOUTH. */
            size = skiff_make_view(view);
            assert(cafa_native_view_valid(view, size) && skiff_edit_context() != context);
            assert(!memcmp(view + CAFA_NATIVE_CONTROLS + 72u, "MOUTH", 5u));
        }
#endif
        skiff_stop();
        skiff_boot(0, 0);
        skiff_master_gain(4096);
    }
    assert(set_engine_of(TSEL, ENGI_FMINI) == 0);
    apply_preset_to(TSEL, 199u);
    assert(TSEL->preset == 199u && TSEL->p[P_E6] == 1 && TSEL->p[P_E7] == 99);
    assert(favorite_set(ENGI_FMINI, 199u, 1));
    settings_save();
    assert(project_save_as(0, "FMINI HIGH SLOT") == 0);
#if defined(ENGI_HAWK_CHUA)
    /* Instrument 0.6 saved a version-1 favorite extension with 25 rows. */
    sks_t old_settings = {0};
    settings_export(&old_settings);
    old_settings.ext_version = 1u;
    old_settings.ext_hash = 2166136261u;
    for (unsigned i = 0; i < 800u; i++)
        old_settings.ext_hash = (old_settings.ext_hash ^ ((uint8_t *)old_settings.ext_fav)[i]) * 16777619u;
    assert(st_save(OBJ_SETTINGS, &old_settings, 1468u) == 0);
#endif
    memcpy(image, nor, sizeof nor);
    skiff_stop();
    skiff_boot(image, sizeof nor);
    project_load(0);
    assert(trk[7].eng_req == ENGI_FMINI && trk[7].preset == 199u);
    assert(trk[7].p[P_E6] == 1 && trk[7].p[P_E7] == 99);
    assert(favorite_has(ENGI_FMINI, 199u));
#if defined(ENGI_HAWK_CHUA)
    assert(set_engine_of(&trk[7], ENGI_HAWK_CHUA) == 0);
    unsigned preset = ENG_HAWK_CHUA.npresets - 1u;
    apply_preset_to(&trk[7], preset);
    assert(favorite_set(ENGI_HAWK_CHUA, preset, 1));
    settings_save();
    assert(project_save_as(0, "CHUA FAVORITE") == 0);
    memcpy(image, nor, sizeof nor);
    skiff_stop();
    skiff_boot(image, sizeof nor);
    project_load(0);
    assert(trk[7].eng_req == ENGI_HAWK_CHUA && trk[7].preset == preset);
    assert(favorite_has(ENGI_HAWK_CHUA, preset) && favorite_has(ENGI_FMINI, 199u));
#endif
    skiff_stop();
    puts("Donor engines render through track eight and save; FMini preset 199 and favorite survive reload: OK");
}
#endif
#if defined(ENGI_LOFI_SMP)
static void check_sampler_flow(uint8_t *image)
{
    static int16_t source[8192];
    static uint8_t encoded[SMP_USER_DATA + sizeof source / 4u];
    int32_t turns[7] = {0};
    int16_t stereo[1024];
    uint8_t view[3072];
    const unsigned sample_offset = SMP_USER_BASE + SMP_USER_SIZE;
    for (unsigned i = 0; i < NELEM(source); i++)
        source[i] = i % 135u < 68u ? 12000 : -12000;
    assert(skiff_encode_sample(encoded, sizeof encoded, source, NELEM(source), "Tape", 60));

    /* A sample-only NOR is a valid first save. A torn first slot does not
     * make a nonblank image look valid. */
    memset(image, 0xff, sizeof nor);
    memcpy(image + sample_offset, encoded, sizeof encoded);
    assert(skiff_saved_nor_valid(image, sizeof nor));
    image[sample_offset + SMP_USER_DATA] ^= 1u;
    assert(!skiff_saved_nor_valid(image, sizeof nor));
    memset(image + sample_offset, 0xff, sizeof encoded);

    skiff_boot(image, sizeof nor);
    song.playing = 1;
    assert(skiff_store_sample(1, encoded, sizeof encoded) == 2 && !usr_nz[1]);
    song.playing = 0;
    assert(skiff_store_sample(1, encoded, sizeof encoded) == 0 && usr_nz[1] == 1);
    memcpy(image, nor, sizeof nor);
    skiff_stop();
    skiff_boot(image, sizeof nor);
    assert(usr_nz[1] == 1);
    skiff_master_gain(4096);
    track_select(7);
    for (unsigned engine = ENGI_LOFI_SMP; engine <= ENGI_CLOUD; engine++) {
        assert(set_engine_of(TSEL, engine) == 0);
        TSEL->p[P_E0] = SMP_NSETS + 1u;
        TSEL->p[P_CHOR] = TSEL->p[P_DLY] = TSEL->p[P_REV] = 0;
        for (unsigned block = 0; block < 16u; block++) skiff_render(stereo, 512);
        ui.home = 0;
        ui.page = CAFA_SCREEN_EDIT1;
        unsigned size = skiff_make_view(view);
        skiff_capture_cards(view, size);
        assert(size && cafa_native_view_valid(view, size));
        assert(view[7] == CAFA_NATIVE_VARIANT_SAMPLE_WAVE);
        assert(cafa_native_section(view, size, CAFA_NATIVE_CURVE, 0));
        assert(!memcmp(view + CAFA_NATIVE_CONTROLS + 12u, "TAPE", 4u));

        assert(skiff_start_sample_capture());
        skiff_input(0, 1u, turns);
        unsigned heard = 0;
        for (unsigned block = 0; block < 24u; block++) {
            uint32_t offset = skiff_capture_count;
            skiff_render(stereo, 512);
            assert(skiff_capture_count == offset + 512u);
            for (unsigned frame = 0; frame < 512u; frame++) {
                int32_t mono = ((int32_t)stereo[2u * frame] + stereo[2u * frame + 1u]) / 2;
                assert(skiff_capture[offset + frame] == mono);
                heard += mono > 128 || mono < -128;
            }
        }
        assert(heard > 64u);
        skiff_cancel_sample_capture();
        skiff_input(0, 0, turns);
    }
    assert(skiff_start_sample_capture());
    for (unsigned block = 0; block < (SKIFF_CAPTURE_FRAMES + 511u) / 512u + 1u; block++)
        skiff_render(stereo, 512);
    assert(!skiff_capturing && skiff_capture_count == SKIFF_CAPTURE_FRAMES);
    skiff_stop();
    assert(skiff_capture_count == 0);
    puts("User sample CRC, stopped save/reload, both sources and native waveforms, and bounded mix capture: OK");
}
#endif
#endif

int main(int argc, char **argv)
{
    assert(argc == 2);
    if (NTRK == 6) {
        skiff_boot(0, 0);
        populate(5, 64, 63, 64);
        assert(project_save_as(1, "OLD PROJECT") == 0);
        FILE *file = fopen(argv[1], "wb");
        assert(file && fwrite(nor, 1, sizeof nor, file) == sizeof nor && fclose(file) == 0);
        return 0;
    }

    static uint8_t image[sizeof nor], old_image[0x100000u];
    FILE *file = fopen(argv[1], "rb");
    assert(file);
    uint32_t old_size = (uint32_t)fread(old_image, 1, sizeof old_image, file);
    assert(fclose(file) == 0 && old_size == 0x100000u);
    assert(skiff_saved_nor_valid(old_image, old_size));
    skiff_boot(old_image, old_size);
    project_load(1);
    check_pattern(5, 64, 63, 64);
    assert(song.sel == 5 && !trk[7].seq_active);
    assert(scene_trk(0, 7) == SCENE_NOT);

    populate(7, 256, 255, 72);
    trk[7].p[P_MUTE] = 1;
    scene_store(0);
    trk[7].p[P_MUTE] = 0;
    assert(project_save_as(2, "SIXTEEN BARS") == 0);

    /* Open the old image and then the new one, forcing the full boot restore. */
    memcpy(image, nor, sizeof image);
    skiff_stop();
    skiff_boot(old_image, old_size);
    project_load(1);
    assert(song.sel == 5 && trk[7].step[255].n == 0);
    skiff_stop();
    skiff_boot(image, sizeof image);
    project_load(2);
    check_pattern(5, 64, 63, 64);
    check_pattern(7, 256, 255, 72);
    assert(song.sel == 7);
    assert(scene_used(0) && (scene_trk(0, 7) & 32u));

    uint8_t view[3072];
    size_t length;
    ui.home = 1;
    ui.home_scope = ui.menu = ui.layer = 0;
    length = skiff_make_view(view);
    assert(cafa_native_view_valid(view, length) && view[6] == CAFA_SCREEN_HOME);
    assert(cafa_native_wire_u14(view + 13u) == 7u && cafa_native_wire_u14(view + 15u) == 8u);
    assert(cafa_native_wire_u14(view + 26u) == 2u && cafa_native_wire_u14(view + 28u) == 8u);
    const uint8_t *rows = cafa_native_section(view, length, CAFA_NATIVE_ROWS, 0);
    assert(rows && rows[0] == 6u && (rows[1u + 5u * 48u + 40u] & 1u));

    /* Cursor and playback reach the last step through the regular runtime. */
    ui.home = ui.menu = ui.layer = 0;
    for (unsigned i = 0; i < NPAGES; i++)
        if (PAGES[i].graph == GR_ROLL) ui.page = (uint8_t)i;
    cursor_set(255);
    length = skiff_make_view(view);
    assert(cafa_native_view_valid(view, length));
    assert(view[6] == CAFA_SCREEN_NOTES);
    assert(cafa_native_wire_u14(view + 17u) == 240 && view[19] == 16u);
    assert(cafa_native_wire_u14(view + 20u) == 255);
    const uint8_t *notes = cafa_native_section(view, length, CAFA_NATIVE_NOTES, 0);
    assert(notes && cafa_native_wire_u14(notes) && notes[2] == 72);
    for (unsigned i = 0; i < NPAGES; i++)
        if (PAGES[i].graph == GR_STEPS) ui.page = (uint8_t)i;
    length = skiff_make_view(view);
    assert(cafa_native_view_valid(view, length) && view[6] == CAFA_SCREEN_PATTERN);
    assert(cafa_native_wire_u14(view + 17u) == 192u);
    const uint8_t *cells = cafa_native_section(view, length, CAFA_NATIVE_CELLS, 0);
    assert(cells && cafa_native_wire_u14(cells) == 64u && (cells[2u + 63u * 7u + 2u] & 3u) == 3u);

    trk[7].p[P_SLEN] = 32;
    cursor_set(31);
    length = skiff_make_view(view);
    cells = cafa_native_section(view, length, CAFA_NATIVE_CELLS, 0);
    assert(cafa_native_view_valid(view, length) && cells && cafa_native_wire_u14(cells) == 32u);
    trk[7].p[P_SLEN] = 256;

    trk[7].p[P_DIR] = DIR_REV;
    song.g[G_CLOCK] = 0;
    int32_t turns[7] = {0};
    skiff_master_gain(4096);
    skiff_input(1u << B_PLAY, 0, turns);
    skiff_input(0, 0, turns);
    int16_t pcm[1024];
    skiff_render(pcm, 512);
    assert(song.playing && trk[7].seq_idx == 255);
    skiff_stop();
    puts("Legacy project, eighth track, and step 256 note/lock save, reopen and play: OK");
#if defined(ENGI_ATEK303)
    check_engine_catalog(image);
#endif
#if defined(ENGI_FMINI)
    check_donor_engines(image);
#endif
#if defined(ENGI_LOFI_SMP)
    check_sampler_flow(image);
#endif
    return 0;
}
