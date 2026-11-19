/* Host contract check against the compiled stock Skiff UI, not a copied model.
 * From the workspace root:
 * .toolchains/skiff/host-cc -O2 -ffp-contract=off -w \
 *   -I .cache/skiff-2.5.1/source/build/gen \
 *   -I .cache/skiff-2.5.1/source/firmware/src \
 *   -I .cache/skiff-2.5.1/source/web/emu -I skiff/firmware/src \
 *   -o .cache/skiff_view_check \
 *   loom-groovebox/instrument-demo/src/main/cpp/skiff_view_check.c -lm
 * .cache/skiff_view_check
 */
#include "felucca_web.c"
#include "skiff_view.inc"
#include <assert.h>
#include <stdio.h>

static int equals(const uint8_t *p, unsigned n, const char *s)
{
    unsigned i;
    for (i = 0; i < n; i++) {
        unsigned c = s[i] ? (unsigned char)s[i] : ' ';
        if (p[i] != c) return 0;
        if (!s[i]) {
            for (; i < n; i++) if (p[i] != ' ') return 0;
            return 1;
        }
    }
    return 1;
}
static unsigned view(uint8_t b[3072])
{
    unsigned n = skiff_make_view(b);
    if (n < CAFA_NATIVE_HEADER) fprintf(stderr, "invalid stock screen %u page %u\n", skiff_screen(), ui.page);
    assert(n >= CAFA_NATIVE_HEADER && n <= 3040u);
    assert(cafa_native_view_valid(b, n));
    return n;
}
#include "skiff_browser_layer_check.inc"
#include "skiff_engine_fields_check.inc"
int main(void)
{
    uint8_t b[3072]; uint32_t len, context; unsigned n, i;
    int16_t *vp;
    const uint8_t *rows;
    web_nor_erase(); web_boot();
    while (web_boot_ms) web_ms();
    n = view(b); assert(b[6] == CAFA_SCREEN_HOME);
    assert(equals(b + 112u, 12u, home_param(0u, &vp)->label));
    assert(equals(b + CAFA_NATIVE_FOOTER, 31u, "HOLD [HOME] SHIFT"));
    const uint8_t *chrome = cafa_native_section(b, n, CAFA_NATIVE_CHROME, &len);
    assert(chrome && len == 56u && cafa_native_wire_u14(chrome) == 16383u);
    { char name[20]; sound_name(TSEL, name);
      assert(equals(chrome + 29u, 8u, ENGINES[TSEL->eng_req % NENGINES]->name));
      assert(equals(chrome + 37u, 16u, name)); }
    rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] == 6u);
    for (i = 0; i < rows[0]; i++) {
        char name[20];
        const uint8_t *r = rows + 1u + 48u * i;
        sound_name(&trk[i], name);
        assert(equals(r, 16u, name[0] ? name : "--"));
        assert(equals(r + 16u, 16u, ""));
        assert(equals(r + 32u, 8u, ENGINES[trk[i].eng_req % NENGINES]->name));
    }
    {
        const engine_t *e = ENGINES[trk[0].eng_req % NENGINES];
        char original[20];
        assert(e->npresets > 1u);
        sound_name(&trk[0], original);
        context = skiff_edit_context();
        trk[0].preset = (uint8_t)((trk[0].preset + 1u) % e->npresets);
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && equals(rows + 1u, 16u, e->presets[trk[0].preset].name));
        assert(!equals(rows + 1u, 16u, original));
        assert(equals(rows + 1u + 32u, 8u, e->name));
        assert(skiff_edit_context() == context);
        chrome = cafa_native_section(b, n, CAFA_NATIVE_CHROME, &len);
        assert(chrome && equals(chrome + 37u, 16u, e->presets[trk[0].preset].name));
    }
    track_select(2u);
    trk[2].p[P_SLEN] = 40; trk[2].seq_idx = 35u; trk[2].p[P_MUTE] = 1;
    trk[3].p[P_SLEN] = 20; trk[3].seq_idx = 18u;
    midi_at[2] = fm1_ms | 1u;
    song.playing = 1;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] == 6u);
    assert((rows[1u + 2u * 48u + 40u] & 7u) == 7u);
    assert((rows[1u + 3u * 48u + 40u] & 7u) == 0u);
    assert(cafa_native_wire_u14(rows + 1u + 2u * 48u + 41u) == 32u);
    assert(cafa_native_wire_u14(rows + 1u + 2u * 48u + 43u) == 35u);
    assert(cafa_native_wire_u14(rows + 1u + 3u * 48u + 41u) == 16u);
    assert(cafa_native_wire_u14(rows + 1u + 3u * 48u + 43u) == 18u);
    song.playing = 0;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(cafa_native_wire_u14(rows + 1u + 2u * 48u + 43u) == 16383u);
    midi_at[2] = 0u; trk[2].p[P_MUTE] = 0;
    track_select(0u);
    const uint8_t *cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(cells && cafa_native_wire_u14(cells) == 6u * 16u);
    ui.home_scope = 1u;
    n = view(b); assert(b[6] == CAFA_SCREEN_SCOPE);
    assert(cafa_native_section(b, n, CAFA_NATIVE_CURVE, &len));
    ui.home_scope = 0u;

    {
        int16_t chord = TSEL->p[P_CHRD], voice = TSEL->p[P_VOICE];
        int16_t strum = TSEL->p[P_STRUM], direction = TSEL->p[P_SDIR];
        int16_t follow = TSEL->p[P_FOLLOW], fmode = TSEL->p[P_FMODE], fnote = TSEL->p[P_FNOTE];
        uint8_t engine = trk[1].eng_req, own_engine = trk[0].eng_req;
        __typeof__(chord_last[0]) saved_chord = chord_last[0];
        fol_src_t saved_source = fol_src[1];
        const uint8_t *keys;
        ui.home = 0; ui.page = CAFA_SCREEN_CHORD; TSEL->p[P_CHRD] = CH_OFF;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && rows[0] == 2u && equals(rows + 1u, 16u, "CHORD VIEW V1"));
        assert(cafa_native_wire_u14(rows + 1u + 41u) == 0u);
        assert(equals(b + CAFA_NATIVE_SUBTITLE, 24u, "CHORD KEYS OFF"));
        TSEL->p[P_CHRD] = CH_MAJ; TSEL->p[P_VOICE] = V_POLY;
        trk[0].eng_req = ENGI_DRUM;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && cafa_native_wire_u14(rows + 1u + 41u) == 1u);
        trk[0].eng_req = own_engine;
        chord_last[0].n = 3u; chord_last[0].root = 48u; chord_last[0].mask = 1u;
        chord_last[0].note[0] = 48u; chord_last[0].note[1] = 60u; chord_last[0].note[2] = 84u;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        keys = cafa_native_section(b, n, CAFA_NATIVE_KEYBOARD, &len);
        assert(rows && rows[0] == 1u && cafa_native_wire_u14(rows + 1u + 41u) == 3u);
        assert(cafa_native_wire_u14(rows + 1u + 43u) == 48u);
        assert(keys && (keys[84u / 7u] & (1u << (84u % 7u))));
        TSEL->p[P_STRUM] = 40; TSEL->p[P_SDIR] = 2;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && rows[0] == 2u && cafa_native_wire_u14(rows + 1u + 41u) == 4u);
        assert(cafa_native_wire_u14(rows + 1u + 48u + 41u) == 40u);
        assert(cafa_native_wire_u14(rows + 1u + 48u + 43u) == 2u);
        ui.page = CAFA_SCREEN_FOLLOW; TSEL->p[P_FOLLOW] = 0;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && equals(rows + 1u, 16u, "FOLLOW VIEW V1") &&
               cafa_native_wire_u14(rows + 1u + 41u) == 0u);
        TSEL->p[P_FOLLOW] = 2; trk[1].eng_req = ENGI_DRUM;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && cafa_native_wire_u14(rows + 1u + 41u) == 1u);
        trk[1].eng_req = engine; fol_src[1].pc = 0u;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && cafa_native_wire_u14(rows + 1u + 41u) == 2u);
        fol_src[1].pc = (1u << 0u) | (1u << 4u) | (1u << 7u);
        fol_src[1].root = 0u; fol_src[1].lo = 48u; fol_src[1].hi = 84u; fol_src[1].last = 84u;
        TSEL->p[P_FMODE] = FM_KEY; TSEL->p[P_FNOTE] = FN_HIGH;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        keys = cafa_native_section(b, n, CAFA_NATIVE_KEYBOARD, &len);
        assert(rows && rows[0] == 1u && cafa_native_wire_u14(rows + 1u + 41u) == 3u);
        assert(cafa_native_wire_u14(rows + 1u + 45u) == 84u);
        assert(keys && (keys[84u / 7u] & (1u << (84u % 7u))));
        TSEL->p[P_FNOTE] = FN_ROOT;
        n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && cafa_native_wire_u14(rows + 1u + 45u) == 128u);
        TSEL->p[P_CHRD] = chord; TSEL->p[P_VOICE] = voice;
        TSEL->p[P_STRUM] = strum; TSEL->p[P_SDIR] = direction;
        TSEL->p[P_FOLLOW] = follow; TSEL->p[P_FMODE] = fmode; TSEL->p[P_FNOTE] = fnote;
        chord_last[0] = saved_chord; fol_src[1] = saved_source;
    }

    ui.home = 0; ui.page = CAFA_SCREEN_ENV;
    n = view(b); assert(b[6] == CAFA_SCREEN_ENV);
    chrome = cafa_native_section(b, n, CAFA_NATIVE_CHROME, &len);
    assert(chrome && len == 56u && cafa_native_wire_u14(chrome) == 16383u);
    assert(equals(b + CAFA_NATIVE_SUBTITLE, 24u, ENGINES[TSEL->eng_req % NENGINES]->name));
    TSEL->seq_idx = 11u; song.playing = 1;
    n = view(b); chrome = cafa_native_section(b, n, CAFA_NATIVE_CHROME, &len);
    assert(chrome && cafa_native_wire_u14(chrome) == seq_at(TSEL));
    assert(cafa_native_wire_u14(b + 22u) == 16383u);
    ui.layer = LAYER_PERF;
    n = view(b);
    assert(b[6] == CAFA_SCREEN_ARP_LAYER && cafa_native_wire_u14(b + 22u) == (seq_at(TSEL) & ~3u));
    song.playing = 0;
    n = view(b);
    assert(cafa_native_wire_u14(b + 22u) == 16383u);
    ui.layer = 0;
    n = view(b);
    const uint8_t *prefs = cafa_native_section(b, n, CAFA_NATIVE_PREFS, &len);
    assert(prefs && len == 2u && prefs[0] == settings.palette % NPALETTES);
    assert((prefs[1] & 11u) == (8u | ((ui_prefs & PREF_LARGE) ? 1u : 0u) |
                                ((ui_prefs & PREF_ANIM_OFF) ? 2u : 0u)));
    assert(equals(b + 112u, 12u, TP[P_ATK].label));
    assert(equals(b + 148u, 12u, TP[P_DEC].label));
    assert(equals(b + 184u, 12u, TP[P_SUS].label));
    assert(equals(b + 220u, 12u, TP[P_REL].label));
    for (i = 0; i < 47u; i++) {
        ui.page = (uint8_t)i;
        view(b); assert(b[6] == i);
    }
    for (unsigned track = 0; track < NTRK; track++) {
        track_select(track);
        for (i = 0; i < 47u; i++) {
            if (!page_visible(i)) continue;
            ui.home = 0; ui.page = (uint8_t)i;
            view(b); assert(b[6] == i);
        }
    }
    track_select(0);

    ui.page = CAFA_SCREEN_NOTES; ui.cursor = 0;
    TSEL->p[P_SLEN] = 16; TSEL->step[0].n = 1;
    TSEL->step[0].time = ST_NOTE; TSEL->step[0].note[0] = 60;
    TSEL->step[0].vel = 96;
    n = view(b);
    const uint8_t *notes = cafa_native_section(b, n, CAFA_NATIVE_NOTES, &len);
    assert(notes && len >= 9u && cafa_native_wire_u14(notes) >= 1u);
    assert(notes[2] == 60u && cafa_native_wire_u14(notes + 5u) >= 128u);
    context = skiff_edit_context();
    TSEL->step[0].note[0] = 62;
    assert(skiff_edit_context() == context);
    song.playing = 1; TSEL->seq_idx = 3;
    assert(skiff_edit_context() == context);
    song.playing = 0;
    view(b); notes = cafa_native_section(b, n, CAFA_NATIVE_NOTES, &len);
    assert(notes && notes[2] == 62u);
    ui.cursor = 1; assert(skiff_edit_context() != context);
    {
        step_t previous = TSEL->step[15], next = TSEL->step[16];
        TSEL->p[P_SLEN] = 32;
        TSEL->step[15].time = ST_NOTE;
        TSEL->step[15].n = 1;
        TSEL->step[15].note[0] = 71;
        TSEL->step[16].time = ST_TIE;
        TSEL->step[16].n = 0;
        ui.bank = 1; ui.cursor = 16;
        n = view(b);
        notes = cafa_native_section(b, n, CAFA_NATIVE_NOTES, &len);
        assert(notes && cafa_native_wire_u14(notes) >= 1u);
        assert(notes[2] == 71u && cafa_native_wire_u14(notes + 3u) == 0u && (notes[7] & 2u));
        TSEL->step[15] = previous; TSEL->step[16] = next;
        TSEL->p[P_SLEN] = 16;
        ui.bank = 0; ui.cursor = 0;
    }
    {
        const uint8_t *markers;
        ui.page = CAFA_SCREEN_LFO; TSEL->lfo_t = LFO_WIN / 2u;
        n = view(b);
        markers = cafa_native_section(b, n, CAFA_NATIVE_MARKERS, &len);
        assert(markers && len == 14u && markers[0] == 1u);
        assert(cafa_native_wire_u14(markers + 1u) == 8191u);
        TSEL->lfo_t = 0;
    }

    ui.page = CAFA_SCREEN_MOD; mod_ui_route = 8u;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] == 12u && equals(rows + 1u, 16u, "PITCH"));
    assert(equals(rows + 1u + 7u * 48u + 16u, 16u, "-"));
    assert(equals(rows + 1u + 8u * 48u, 16u,
                  N_MSRC[clamp(TSEL->p[P_M1SRC], 0, MS_N - 1)]));
    assert(equals(rows + 1u + 8u * 48u + 16u, 16u,
                  mod_dst_name(TSEL, TSEL->p[P_M1SRC + 1u])));
    assert(cafa_native_wire_u14(b + 24u) == mod_ui_route + 1u &&
           rows[1u + 9u * 48u + 40u] & 1u);
    ui.confirm = CF_CLEAR_SEQ;
    n = view(b); assert(b[6] == CAFA_SCREEN_CONFIRM);
    ui.confirm = 0;
    nm.kind = NK_PROJ_SAVE; nm.cur = 2u;
    str_cpy(nm.s, "TEST", sizeof nm.s);
    n = view(b); assert(b[6] == CAFA_SCREEN_NAME);
    const uint8_t *lines = cafa_native_section(b, n, CAFA_NATIVE_TEXT, &len);
    assert(lines && equals(lines + 1u, 24u, "TEST"));
    nm.kind = NK_NONE;
    cin_total = 4u; cin_left = 2u;
    n = view(b); assert(b[6] == CAFA_SCREEN_COUNT_IN);
    cin_left = cin_total = 0;

    ui.menu = 1; ui.menu_sel = MI_CLOCK;
    n = view(b); assert(b[6] == CAFA_SCREEN_APP_MENU);
    rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] > 0u && cafa_native_wire_u14(b + 11u) == MTAB_COUNT);
    for (i = 0; i < rows[0]; i++) {
        unsigned id = mtab_first(menu_tab()) + mr_top() + i;
        assert(equals(rows + 1u + 48u * i, 16u, MI_NAME[id]));
        if (id < MI_VALUES)
            assert(equals(rows + 17u + 48u * i, 16u, menu_vname(id, menu_get(id))));
    }
    ui.menu = 2; n = view(b);
    assert(b[6] == CAFA_SCREEN_ABOUT && b[1] == 8u && n == CAFA_NATIVE_HEADER);
    ui.menu = 1; skiff_device_screen = CAFA_SCREEN_CALIBRATION;
    n = view(b); assert(b[6] == CAFA_SCREEN_CALIBRATION && b[1] == 8u && n == CAFA_NATIVE_HEADER);
    skiff_device_screen = 0;
    ui.menu = 0; ui.home = 0; ui.page = CAFA_SCREEN_ENV;
    CPAL_BYTE |= 3u; CPAL_COLS[0] = 0x34u; CPAL_COLS[1] = 0x12u;
    n = view(b); prefs = cafa_native_section(b, n, CAFA_NATIVE_PREFS, &len);
    assert(prefs && len == 35u && (prefs[1] & 4u));
    assert((prefs[2] | (uint32_t)prefs[3] << 7 | (uint32_t)prefs[4] << 14) == 0x1234u);
    CPAL_BYTE &= (uint8_t)~3u;
    ui.menu = 0; ui.home = 1;
    for (i = LAYER_FX; i < LAYER_N; i++) {
        ui.layer = (uint8_t)i;
        n = view(b);
        assert(b[6] >= CAFA_SCREEN_FX_LAYER && b[6] <= CAFA_SCREEN_ARP_LAYER);
        assert(n > CAFA_NATIVE_HEADER);
        if (i == LAYER_SCL || i == LAYER_EDIT || i == LAYER_SEQ) {
            unsigned selected = 0;
            rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
            assert(rows);
            for (unsigned row = 0; row < rows[0]; row++) {
                unsigned flags = rows[1u + 48u * row + 40u];
                assert(!(flags & 2u));
                selected += flags & 1u;
            }
            assert(selected == (i == LAYER_SEQ ? 0u : 1u));
        }
    }
    {
        const uint8_t *card = b + CAFA_NATIVE_CONTROLS + 36u;
        assert(b[6] == CAFA_SCREEN_ARP_LAYER);
        assert(equals(card, 12u, "REVERB") && equals(card + 12u, 14u, "+0"));
        assert(equals(card + 36u, 12u, "DELAY") && equals(card + 72u, 12u, "DIST"));
        assert(card[32] == 1u && cafa_native_wire_u14(card + 30u) == 8191u);
        rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert(rows && equals(rows + 1u, 16u, "1"));
        assert(equals(b + CAFA_NATIVE_TITLE, 24u, "PERFORM LOOP"));

        TSEL->p[P_REV] = 10;
        throw_turn(song.sel, 1u, -3);
        trk[1].p[P_MUTE] = 1;
        mute_arm[1] = 1;
        ui_perf_keys = 1u;
        ui_launch = LAUNCH_BAR;
        n = view(b);
        assert(equals(card + 12u, 14u, "-3") && cafa_native_wire_u14(card + 30u) < 8191u);
        rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
        assert((rows[1u + 48u + 40u] & 68u) == 68u && cafa_native_wire_u14(rows + 42u) == 1u);
        assert(equals(b + CAFA_NATIVE_TITLE, 24u, "PERFORM BAR"));
        throw_turn(song.sel, 1u, 3);
        TSEL->p[P_REV] = 0;
        trk[1].p[P_MUTE] = mute_arm[1] = ui_perf_keys = ui_launch = 0u;
    }
    ui.layer = 0; ui.shift = 1;
    n = view(b); assert(b[6] == CAFA_SCREEN_SHIFT);
    rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] == 12u && !equals(rows + 1u, 16u, ""));
    assert(equals(b + CAFA_NATIVE_SUBTITLE, 24u, "HOME"));
    {
        unsigned ordinary = 0, combos = 0;
        for (i = 0; i < rows[0]; i++) {
            unsigned kind = rows[1u + 48u * i + 40u];
            ordinary += kind == 0u;
            combos += kind == 2u;
        }
        assert(ordinary && combos);
    }
    ui.shift = 0; ui.layer = LAYER_SEQ; TSEL->p[P_SLEN] = 20;
    eu.on = 1u; eu.hits = 3u; eu.steps = 7u; eu.rot = 2u;
    context = skiff_edit_context();
    n = view(b); assert(b[6] == CAFA_SCREEN_SEQ_LAYER);
    rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && (rows[1u + 40u] & 64u) &&
           cafa_native_wire_u14(rows + 1u + 41u) == 20u &&
           cafa_native_wire_u14(rows + 1u + 43u) == 7u &&
           cafa_native_wire_u14(rows + 1u + 45u) == 2u);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(cells && cafa_native_wire_u14(cells) == 20u);
    for (i = 0; i < 20u; i++) {
        unsigned flags = cells[2u + i * 7u + 2u];
        assert((flags & 1u) == (unsigned)eu_hit(i));
        assert(!!(flags & 32u) == (eu_hit(i) && i < eu.steps));
    }
    eu.rot = 3u; n = view(b);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    for (i = 0; i < 20u; i++)
        assert((cells[2u + i * 7u + 2u] & 1u) == (unsigned)eu_hit(i));
    eu.on = 0u;
    assert(skiff_edit_context() != context);
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(!(rows[1u + 40u] & 64u) &&
           !cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len));
    assert(equals(b + CAFA_NATIVE_CONTROLS, 12u, TP[P_SLEN].label));
    for (i = 0; i < NTRK && !drum_track(&trk[i]); i++) {}
    assert(i < NTRK);
    track_select(i); ui.layer = LAYER_SEQ; ui.lane = 3u;
    TSEL->p[P_SLEN] = 18; eu.on = 1u; eu.hits = 4u; eu.steps = 9u; eu.rot = 3u;
    n = view(b);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(b[7] == CAFA_NATIVE_VARIANT_DRUM && cells &&
           cafa_native_wire_u14(cells) == 18u + NLANE);
    assert((cells[2u + (18u + 3u) * 7u + 2u] & 2u) &&
           equals(cells + 2u + (18u + 3u) * 7u + 3u, 4u,
                  drum_lane_abbr(TSEL, 3u)));
    eu.on = 0u; track_select(0);
    ui.layer = 0;
    ui.shift = 0; ui.home = 0; ui.page = CAFA_SCREEN_ENV;
    take_box.trk = 2u; take_box.kind = 0u; take_box.gen++;
    str_cpy(take_box.title, "TAKE KEPT", sizeof take_box.title);
    str_cpy(take_box.line, "14 NOTES", sizeof take_box.line);
    ui.take_t = 3u;
    n = view(b);
    const uint8_t *box = cafa_native_section(b, n, CAFA_NATIVE_TRANSIENT_BOX, &len);
    assert(box && len == 67u && box[0] == 2u && !box[1] && equals(box + 2u, 24u, "TAKE KEPT"));
    context = skiff_edit_context(); ui.take_t--;
    assert(skiff_edit_context() == context);
    ui.take_t = 0u;
    assert(skiff_edit_context() != context);
    n = view(b);
    assert(!cafa_native_section(b, n, CAFA_NATIVE_TRANSIENT_BOX, &len));
    take_box.kind = 1u; take_box.gen++;
    ui.take_t = 2u; undo_n = 3u; undo_back = 1u;
    n = view(b); box = cafa_native_section(b, n, CAFA_NATIVE_TRANSIENT_BOX, &len);
    assert(box && box[1] == 1u && box[66] == 3u);
    engine_fields_check();
    skiff_browser_layer_check();
    puts("stock native view projection ok");
    return 0;
}
