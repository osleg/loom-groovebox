/* Focused stock browser and held-layer semantics. Run after the general view checks. */
static void skiff_browser_layer_check(void)
{
    uint8_t b[3072]; uint32_t len; unsigned n, i, total, selected;
    const uint8_t *rows, *lines, *cells;
    uint32_t k, e;
    char name[20], tag[16];

    ui.home = 0; ui.layer = 0; ui.shift = 0; ui.page = CAFA_SCREEN_PRESETS;
    ui_ptag = 0; favorites.filter = 0;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    selected = preset_pos(&total);
    assert(rows && rows[0] == 7u && total > 0u);
    for (i = 0; i < 7u; i++) {
        unsigned index = preset_visible(selected, total, i);
        const uint8_t *r = rows + 1u + i * 48u;
        e = preset_at(index, &k);
        if (e == NENGINES) { up_slot_label(tag, k); up_name(k, name); }
        else { str_cpy(tag, eng_abbr(ENGINES[e]->name), sizeof tag);
               str_cpy(name, ENGINES[e]->presets[k].name, sizeof name); }
        assert(equals(r, 16u, name) && equals(r + 32u, 8u, tag));
        assert(!!(r[40] & 1u) == (index == selected));
        assert(!!(r[40] & 2u) == !!favorite_has(e, k));
    }
    preset_go(0u);
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && cafa_native_wire_u14(b + 26u) == total - 3u);
    assert(cafa_native_wire_u14(rows + 1u + 41u) == total - 3u);
    assert((rows[1u + 3u * 48u + 40u] & 1u) != 0u);
    if (selected < total) preset_go(selected);
    favorites.filter = 1;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    selected = preset_pos(&total);
    assert(cafa_native_wire_u14(b + 24u) == (selected < total ? selected : 16383u));
    for (i = 0; i < 7u; i++) {
        unsigned index = preset_visible(selected, total, i);
        const uint8_t *r = rows + 1u + i * 48u;
        assert(cafa_native_wire_u14(r + 41u) == index);
        assert(!!(r[40] & 16u) == (index >= total));
    }
    favorites.filter = 0;
    ui_ptag = 1u;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    selected = preset_pos(&total);
    assert(rows && cafa_native_wire_u14(b + 28u) == total);
    for (i = 0; i < 7u; i++)
        assert(cafa_native_wire_u14(rows + 1u + i * 48u + 41u) ==
               preset_visible(selected, total, i));
    ui_ptag = 0u;

    ui.page = CAFA_SCREEN_USER; ui.uslot = 0u;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    up_slot_label(tag, 0u);
    assert(rows && equals(rows + 1u + 32u, 8u, tag));
    assert(equals(rows + 1u, 16u, up_used(0u) ? (up_name(0u, name), name) : "--"));
    assert(!!(rows[1u + 40u] & 16u) == !up_used(0u));
    ui.page = CAFA_SCREEN_PROJECT;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && equals(rows + 1u + 32u, 8u, "A"));
    assert(!!(rows[1u + 40u] & 16u) == !project_name(0u, name));

    ui.layer = LAYER_GLO; ui.home = 1; track_select(0u);
    perf_solo = 1u; trk[0].p[P_MUTE] = 1;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && equals(rows + 1u, 16u, "SOLO"));
    assert((rows[1u + 40u] & 7u) == 7u);
    assert(equals(rows + 1u + 16u, 16u, "SOLO"));
    assert(equals(rows + 1u + 32u, 8u, "MUTE"));
    assert(equals(b + CAFA_NATIVE_FOOTER, 31u, "PLAY:RESTART OCT-:UNDO GLO:DONE"));
    perf_solo = 0u; trk[0].p[P_MUTE] = 0;

    ui.layer = LAYER_EDIT;
    n = view(b); lines = cafa_native_section(b, n, CAFA_NATIVE_TEXT, &len);
    assert(lines && lines[0] >= 1u);
    assert(equals(lines + 1u + 3u, 20u,
                  ENGINES[TSEL->eng_req % NENGINES]->presets[TSEL->preset].name));

    ui.layer = LAYER_SEQ; eu.on = 0; track_select(0u);
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] == TL_N);
    for (i = 0; i < TL_N; i++)
        assert(!!(rows[1u + i * 48u + 40u] & 16u) == !tl_cell(TSEL, i));
    eu.on = 1u; n = view(b);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(cells && equals(b + CAFA_NATIVE_FOOTER, 31u, "OCT+:APPLY OCT-:CANCEL"));
    eu.on = 0u;
    for (i = 0; i < NTRK && !drum_track(&trk[i]); i++) {}
    assert(i < NTRK);
    track_select(i); ui.lane = 2u;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && equals(rows + 1u + TL_LCLR * 48u, 4u, "CLR "));
    assert(equals(rows + 1u + TL_LCLR * 48u + 4u, 4u, drum_lane_abbr(TSEL, ui.lane)));
    eu.on = 0u; ui.layer = 0u; ui.home = 0u; ui.page = CAFA_SCREEN_SONG;
    {
        uint8_t old_sound = lists.snd[0][0][0];
        lists.snd[0][0][0] = 1u;
        n = view(b); cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
        assert(cells && (cells[2u + 2u] & 8u));
        lists.snd[0][0][0] = old_sound;
    }
    track_select(0u);
    const unsigned source = 8u, single = source + 1u, chain = source + 3u;
    assert(song_put(0u, source, SPICK_NEW) == 0u);
    lists.snd[0][source][0] = 1u;
    lists.ent[0][source][1] = 3u;
    sg.entry = source; sg.pick = SPICK_COPYP; sg.pick_at = source + 1u;
    n = view(b);
    rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(rows && cells && cafa_native_wire_u14(rows + 44u) == single &&
           cafa_native_wire_u14(rows + 46u) == 1u);
    assert((cells[2u + single * 7u + 2u] & 9u) == 9u &&
           cafa_native_wire_u14(cells + 2u + single * 7u) == 4u);
    assert(!ent_on(0u, single));

    assert(song_put(0u, source + 1u, PAT_REST) == 0u);
    assert(song_put(0u, source + 2u, SPICK_NEW) == 0u);
    lists.ent[0][source][1] = lists.ent[0][source + 1u][1] = REPS_CHAIN;
    lists.ent[0][source + 2u][1] = 2u;
    n = view(b);
    rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(rows && cells && cafa_native_wire_u14(rows + 44u) == chain &&
           cafa_native_wire_u14(rows + 46u) == 3u);
    assert((cells[2u + chain * 7u + 2u] & 105u) == 105u);
    assert(equals(cells + 2u + (chain + 1u) * 7u + 3u, 4u, "-"));
    assert(cafa_native_wire_u14(cells + 2u + (chain + 2u) * 7u) == 3u);
    for (i = chain; i < chain + 3u; i++) assert(!ent_on(0u, i));

    assert(song_put(0u, source, SPICK_COPYP) == 0u);
    for (i = chain; i < chain + 3u; i++) {
        if (ent_rest(0u, i)) strcpy(name, "-");
        else pat_num(name, ent_pat(0u, i));
        assert(equals(cells + 2u + 7u * i + 3u, 4u, name));
    }
    sg.pick = 0u;
    n = view(b);
    rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(!rows || cafa_native_wire_u14(rows + 46u) == 0u);
    ui.page = CAFA_SCREEN_ENV;
}
