/* Stock Skiff 2.5.1 state -> wire6 native UI. Included after felucca_web.c. */
#include "cafa_native_view.h"
/* Runtime sets this only while a firmware-owned physical action is open. */
static uint8_t skiff_device_screen;

/* The edit context deliberately excludes the playhead, meters and note events. */
static uint32_t skiff_edit_context(void)
{
    uint32_t h = 2166136261u;
#define SKIFF_CTX(v) do { h = (h ^ (uint32_t)(v)) * 16777619u; } while (0)
    SKIFF_CTX(ui.menu); SKIFF_CTX(ui.menu_sel); SKIFF_CTX(ui.confirm); SKIFF_CTX(ui.confirm_trk);
    SKIFF_CTX(ui.take_t != 0u);
    if (ui.take_t) { SKIFF_CTX(take_box.gen); SKIFF_CTX(take_box.kind); }
    SKIFF_CTX(skiff_device_screen);
    SKIFF_CTX(nm.kind); SKIFF_CTX(nm.slot); SKIFF_CTX(nm.cur);
    SKIFF_CTX(fr.on); SKIFF_CTX(fr.ci); SKIFF_CTX(fr.bi); SKIFF_CTX(fr.start);
    SKIFF_CTX(ui.home); SKIFF_CTX(ui.home_scope); SKIFF_CTX(ui.shift); SKIFF_CTX(ui.layer);
    SKIFF_CTX(ui.page); SKIFF_CTX(song.sel); SKIFF_CTX(TSEL->eng_req);
#if defined(ENGI_CHIMERA)
    /* Old scans cannot turn a knob whose meaning changed with a vocal mode. */
    if (TSEL->eng_req == ENGI_CHIMERA || TSEL->eng_req == ENGI_HAWK_CHUA)
        SKIFF_CTX(TSEL->p[P_E0]);
    if (TSEL->eng_req == ENGI_SPEAK37)
        SKIFF_CTX(TSEL->p[P_E4]);
#endif
    SKIFF_CTX(ui.cursor); SKIFF_CTX(ui.lane); SKIFF_CTX(page_lane()); SKIFF_CTX(nsel.a1); SKIFF_CTX(nsel.picked);
    if (ui.layer == LAYER_SEQ) SKIFF_CTX(eu.on);
    SKIFF_CTX(mod_ui_route); SKIFF_CTX(ui.ev_top); SKIFF_CTX(ui.ev_row);
    SKIFF_CTX(ui.ev_step); SKIFF_CTX(ui.ev_id);
    SKIFF_CTX(ui.uslot); SKIFF_CTX(ui.bslot); SKIFF_CTX(ui.bact);
    SKIFF_CTX(ui.act); SKIFF_CTX(ui.act2); SKIFF_CTX(sg.entry); SKIFF_CTX(sg.pick);
    if (!ui.home && ui.page < NPAGES) {
        const sheet_t *sh = sheet_of(PAGES[ui.page].graph);
        if (sh) SKIFF_CTX(*sh->win);
    }
#undef SKIFF_CTX
    return h & 0x0fffffffu;
}

static void skiff_u14(uint8_t *p, uint32_t v)
{
    if (v > 16383u) v = 16383u;
    p[0] = (uint8_t)(v & 127u); p[1] = (uint8_t)((v >> 7) & 127u);
}
static void skiff_u28(uint8_t *p, uint32_t v)
{
    unsigned i; for (i = 0; i < 4; i++) p[i] = (uint8_t)((v >> (i * 7u)) & 127u);
}
static void skiff_text(uint8_t *p, unsigned n, const char *s)
{
    unsigned i; memset(p, ' ', n);
    if (!s) return;
    for (i = 0; i < n && s[i]; i++) {
        unsigned char c = (unsigned char)s[i];
        p[i] = (uint8_t)(c >= 32u && c <= 126u ? c : ' ');
    }
}
static uint32_t skiff_norm(const param_desc_t *d, int32_t v)
{
    int32_t r = d->max - d->min;
    if (r <= 0) return 0;
    if (v < d->min) v = d->min;
    if (v > d->max) v = d->max;
    return (uint32_t)((v - d->min) * 16383 / r);
}
static void skiff_card(uint8_t *b, unsigned k, const char *label,
                       const char *value, const char *unit, uint32_t norm,
                       uint8_t kind, uint8_t flags, uint8_t icon)
{
    uint8_t *q = b + CAFA_NATIVE_CONTROLS + 36u * k;
    skiff_text(q, 12u, label); skiff_text(q + 12u, 14u, value);
    skiff_text(q + 26u, 4u, unit); skiff_u14(q + 30u, norm);
    q[32] = kind; q[33] = flags; q[34] = icon; q[35] = 0;
}
static void skiff_param_card(uint8_t *b, unsigned k, const char *label,
                             const param_desc_t *d, int32_t value, uint8_t flags)
{
    char v[20]; const char *unit;
    param_format(d, value, v, &unit);
    skiff_card(b, k, label ? label : d->label, v, unit, skiff_norm(d, value),
               d->fmt == F_ENUM ? 2u : 1u, flags, 0u);
}
static unsigned skiff_sec(uint8_t *b, unsigned at, unsigned tag, unsigned n)
{
    /* Leave the largest PREFS section and the caller's 32-byte LED section. */
    if (at + 3u + n > 3002u) return 0;
    b[at] = (uint8_t)tag; skiff_u14(b + at + 1u, n);
    memset(b + at + 3u, 0, n); return at + 3u;
}
static unsigned skiff_rows(uint8_t *b, unsigned at, unsigned count,
                           const char *const *labels, const char *const *values,
                           unsigned selected)
{
    unsigned p = skiff_sec(b, at, CAFA_NATIVE_ROWS, 1u + 48u * count), i;
    if (!p) return at;
    b[p] = (uint8_t)count;
    for (i = 0; i < count; i++) {
        uint8_t *r = b + p + 1u + 48u * i;
        skiff_text(r, 16u, labels ? labels[i] : "");
        skiff_text(r + 16u, 16u, values ? values[i] : "");
        skiff_text(r + 32u, 8u, "");
        r[40] = (uint8_t)(i == selected ? 3u : 2u);
    }
    return p + 1u + 48u * count;
}
#ifdef TRACK_KEY_COUNT
#define SKIFF_VIEW_TRACKS TRACK_KEY_COUNT
static unsigned skiff_first_visible_track(void)
{
    return track_key_origin();
}
#else
#define SKIFF_VIEW_TRACKS NTRK
static unsigned skiff_first_visible_track(void)
{
    return 0u;
}
#endif

static unsigned skiff_track_window(uint8_t *b)
{
    unsigned first = skiff_first_visible_track();
    skiff_u14(b + 24u, song.sel);
    skiff_u14(b + 26u, first);
    skiff_u14(b + 28u, NTRK);
    return first;
}

static unsigned skiff_home_rows(uint8_t *b, unsigned at, unsigned first_track)
{
    unsigned p = skiff_sec(b, at, CAFA_NATIVE_ROWS, 1u + 48u * SKIFF_VIEW_TRACKS), i;
    if (!p) return at;
    b[p] = SKIFF_VIEW_TRACKS;
    for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
        unsigned k = first_track + i;
        const track_t *t = &trk[k];
        uint8_t *r = b + p + 1u + 48u * i;
        uint32_t count, first = hl_bar(t, &count);
        char name[20];
        sound_name(t, name);
        skiff_text(r, 16u, name[0] ? name : "--");
        skiff_text(r + 16u, 16u, "");
        skiff_text(r + 32u, 8u, ENGINES[t->eng_req % NENGINES]->name);
        r[40] = (uint8_t)((k == song.sel ? 1u : 0u) |
                          (midi_mark(k) ? 2u : 0u) |
                          (t->p[P_MUTE] ? 4u : 0u));
        skiff_u14(r + 41u, first);
        skiff_u14(r + 43u, song.playing ? t->seq_idx : 16383u);
    }
    return p + 1u + 48u * SKIFF_VIEW_TRACKS;
}
static unsigned skiff_lines(uint8_t *b, unsigned at, unsigned count,
                            const char *const *lines)
{
    unsigned p = skiff_sec(b, at, CAFA_NATIVE_TEXT, 1u + 24u * count), i;
    if (!p) return at;
    b[p] = (uint8_t)count;
    for (i = 0; i < count; i++) skiff_text(b + p + 1u + 24u * i, 24u, lines[i]);
    return p + 1u + 24u * count;
}

/* Physical family identifiers are intentionally explicit; FAM_TRK is GLO's mixer. */
static uint8_t skiff_family(uint32_t fam)
{
    switch (fam) {
    case FAM_FX: return 0u; case FAM_SCL: return 1u;
    case FAM_ENV: return 2u; case FAM_LFO: return 3u;
    case FAM_EDIT: return 4u; case FAM_GLO: case FAM_TRK: return 5u;
    case FAM_SAVE: return 7u; case FAM_ARP: return 8u;
    case FAM_SEQ: return 9u; default: return 6u;
    }
}
static uint8_t skiff_screen(void)
{
    if (ui.uboot) return CAFA_SCREEN_UPDATE;
    if (skiff_device_screen) return skiff_device_screen;
    if (ui.menu) {
        if (ui.menu == 2u) return CAFA_SCREEN_ABOUT;
        return 72u; /* APP_MENU, capability 15: host-owned stock musical settings. */
    }
    if (seq_counting() && !ui.confirm && !name_on()) return CAFA_SCREEN_COUNT_IN;
    if (ui.confirm) return CAFA_SCREEN_CONFIRM;
    if (name_on()) return CAFA_SCREEN_NAME;
    if (fr.on) return CAFA_SCREEN_TAKE_PICKER;
    if (ui.shift) return CAFA_SCREEN_SHIFT;
    if (ui.layer) {
        switch (ui.layer) {
        case LAYER_FX: return CAFA_SCREEN_FX_LAYER;
        case LAYER_GLO: return CAFA_SCREEN_GLO_LAYER;
        case LAYER_SCL: return CAFA_SCREEN_SEL_LAYER;
        case LAYER_EDIT: return CAFA_SCREEN_EDIT_LAYER;
        case LAYER_SEQ: return CAFA_SCREEN_SEQ_LAYER;
        case LAYER_REC: return CAFA_SCREEN_REC_LAYER;
        case LAYER_PERF: return CAFA_SCREEN_ARP_LAYER;
        default: break;
        }
    }
    if (ui.home) return ui.home_scope ? CAFA_SCREEN_SCOPE : CAFA_SCREEN_HOME;
    if (ui.page >= NPAGES) return CAFA_SCREEN_HOME;
    return (uint8_t)ui.page; /* This stock PAGES order matches catalogue 0..46. */
}

static void skiff_basic_cards(uint8_t *b, const page_t *pg)
{
    unsigned k;
    if (pg->graph == GR_SMIX) return; /* page_desc marks sample mix for persistence. */
    if (pg->graph == GR_TRK) {
        skiff_param_card(b, 0u, "LEVEL", &TP[P_LEVEL], TSEL->p[P_LEVEL], 1u);
        skiff_param_card(b, 1u, "PAN", &TP[P_PAN], TSEL->p[P_PAN], 1u);
        skiff_param_card(b, 2u, "REVERB", &TP[P_REV], TSEL->p[P_REV], 1u);
        skiff_param_card(b, 3u, "MUTE", &TP[P_MUTE], TSEL->p[P_MUTE], 1u);
        return;
    }
    if (pg->graph == GR_LANES || pg->graph == GR_LMIX)
        skiff_card(b, 0u, "LANE", drum_lane_name(TSEL, page_lane()), "",
                   page_lane() * 16383u / 7u, 2u, 1u, 0u);
    if (pg->graph == GR_MOD) {
        char number[12]; fmt_int(number, (int32_t)mod_ui_route + 1);
        skiff_card(b, 0u, "ROUTE", number, "/11",
                   mod_ui_route * 16383u / (MOD_ROUTES - 1u), 2u, 1u, 0u);
    }
    for (k = 0; k < 4u; k++) {
        int16_t *vp = 0;
        const param_desc_t *d = page_desc(pg, k, &vp);
        if (d && d->label && d->label[0] != '-' && vp)
            skiff_param_card(b, k, 0, d, *vp, (uint8_t)(card_dead(pg, k) ? 0u : 1u));
    }
}

static void skiff_special_cards(uint8_t *b, const page_t *pg)
{
    char value[24], unit[16];
    uint32_t a, k;
    if (pg->graph == GR_BROWSE) {
        uint32_t total, pos = preset_pos(&total);
        if (pos < total) fmt_int(value, (int32_t)pos + 1); else str_cpy(value, "--", sizeof value);
        fmt_kn(unit, -1, (int32_t)total);
        skiff_card(b, 0u, "No.", value, unit, total ? pos * 16383u / total : 0u, 2u, 1u, 0u);
        skiff_card(b, 1u, "ENG", ENGINES[TSEL->eng_req % NENGINES]->name, "", 0u, 2u, 1u, 0u);
        skiff_card(b, 2u, "FAV", preset_favorite() ? "ON" : "OFF", "", 0u, 3u, 1u, 0u);
        skiff_card(b, 3u, "LIST", LIST_NAME[list_mode()], "", 0u, 2u, 1u, 0u);
    } else if (pg->graph == GR_USER) {
        up_slot_label(value, ui.uslot);
        skiff_card(b, 0u, "SLOT", value, "", ui.uslot * 16383u / (UP_SLOTS - 1u), 2u, 1u, 0u);
        skiff_card(b, 1u, "LOAD", "GO", "", 0u, 4u, up_used(ui.uslot) ? 1u : 0u, 0u);
        skiff_card(b, 2u, ui.act == 3u && ui.act2 ? "NAME" : "ERASE", "GO", "", 0u, 4u,
                   up_used(ui.uslot) ? 1u : 0u, 0u);
        skiff_card(b, 3u, "SAVE", "GO", "", 0u, 4u, 1u, 0u);
    } else if (pg->graph == GR_BANK) {
        uint32_t id = ui.bslot % NPAT, length = pool_len(id), refs = length ? pool_refs(id) : 0u;
        pat_num(value, id);
        skiff_card(b, 0u, "PATTERN", value, "", (id + 1u) * 16383u / NPAT, 2u, 1u, 0u);
        if (length) fmt_int(value, (int32_t)length); else str_cpy(value, "--", sizeof value);
        skiff_card(b, 1u, "LENGTH", value, "", 0u, 1u, length ? 4u : 0u, 0u);
        if (length) fmt_int(value, (int32_t)refs); else str_cpy(value, "--", sizeof value);
        skiff_card(b, 2u, "USED", value, length ? "x" : "", 0u, 1u, length ? 4u : 0u, 0u);
        skiff_card(b, 3u, "ACTION", BA_NAME[ui.bact % BA_N], "", 0u, 4u, act_ready() ? 1u : 0u, 0u);
    } else if (pg->graph == GR_SLOTS) {
        a = (uint32_t)song.g[G_SLOT];
        skiff_param_card(b, 0u, "PROJECT", &GP[G_SLOT], song.g[G_SLOT], 1u);
        skiff_card(b, 1u, ui.act == 2u && ui.act2 ? "NAME" : "DELETE", "GO", "", 0u, 4u,
                   project_used(a - 1u) ? 1u : 0u, 0u);
        a = proj_k3();
        skiff_card(b, 2u, a >= PK3_SONG ? "CLEAR" : a == PK3_LOAD ? "LOAD" : "NEW",
                   a == PK3_SONG ? "SONG" : a == PK3_TRKS ? "TRACKS" : "GO", "", 0u,
                   4u, 1u, 0u);
    } else if (pg->graph == GR_SONG) {
        uint32_t e = sg.entry % NENT, p = song_pick(), slot = p ? p : lists.ent[song.sel % NTRK][e][0];
        value[0] = (char)('A' + e); value[1] = 0;
        skiff_card(b, 0u, "ENTRY", value, "", e * 16383u / (NENT - 1u), 2u, 1u, 0u);
        if (slot == PAT_REST) str_cpy(value, "REST", sizeof value);
        else if (slot >= SPICK_NEW)
            str_cpy(value, slot == SPICK_NEW ? "NEW" : slot == SPICK_COPY ? "COPY" :
                         slot == SPICK_COPYP ? "COPY+" : slot == SPICK_CLEAR ? "CLEAR" :
                         slot == SPICK_SOUND ? "SOUND" : "NO SOUND", sizeof value);
        else if (slot) pat_num(value, slot - 1u);
        else str_cpy(value, "--", sizeof value);
        skiff_card(b, 1u, "PATTERN", value, "", 0u, 2u, slot ? 1u : 0u, 0u);
        a = (uint32_t)lists.ent[song.sel % NTRK][e][1] + 1u;
        if (a > 16u) str_cpy(value, "CHAIN", sizeof value); else fmt_int(value, (int32_t)a);
        skiff_card(b, 2u, "REPS", value, a > 16u ? "" : "x", 0u, 2u, 1u, 0u);
        skiff_card(b, 3u, "SONG", lists.song == SONG_ONCE ? "ONCE" : lists.song ? "ON" : "OFF",
                   "", 0u, 2u, 1u, 0u);
    } else if (pg->graph == GR_MOD) {
        static const uint8_t FDST[MOD_SLOT0] = {MD_PITCH, MD_CUT, MD_SHP, MD_AMP, MD_PITCH, MD_CUT, MD_SHP};
        uint32_t route = mod_ui_route, fixed = route < MOD_SLOT0;
        uint32_t id = fixed ? MOD_FIXED[route] : P_M1SRC + 3u * (route - MOD_SLOT0) + 2u;
        int32_t source = fixed ? (route < 4u ? MS_LFO : MS_ENV) : TSEL->p[id - 2u];
        int32_t dest = fixed ? FDST[route] : TSEL->p[id - 1u];
        skiff_card(b, 1u, "SOURCE", N_MSRC[clamp(source, 0, MS_N - 1)], "", 0u, 2u,
                   source && !fixed ? 1u : 4u, 0u);
        skiff_card(b, 2u, "DEST", mod_dst_name(TSEL, dest), "", 0u, 2u,
                   dest && !fixed ? 1u : 4u, 0u);
        skiff_param_card(b, 3u, "AMOUNT", &TP[id], TSEL->p[id], 1u);
    } else if (pg->scope == SC_STEP && drum_track(TSEL)) {
        const step_t *st = &TSEL->step[ui.cursor % NSTEP];
        uint32_t lane = ui.lane % NLANE, first = sel_first(TSEL);
        a = (step_lanes(st) >> lane) & 1u;
        skiff_card(b, 0u, "LANE", drum_lane_name(TSEL, lane), "", lane * 16383u / 7u, 2u, 1u, 0u);
        skiff_card(b, 1u, "HIT", a ? "ON" : "--", "", 0u, 3u, a ? 1u : 0u, 0u);
        a = (step_accents(st) >> lane) & 1u;
        skiff_card(b, 2u, "ACC", a ? "ON" : "--", "", 0u, 3u, a ? 1u : 0u, 0u);
        if (first < NSTEP) fmt_int(value, (int32_t)step_vel(&TSEL->step[first]));
        else str_cpy(value, "--", sizeof value);
        skiff_card(b, 3u, "VELOCITY", value, "", first < NSTEP ? step_vel(&TSEL->step[first]) * 128u : 0u,
                   1u, first < NSTEP ? 1u : 0u, 0u);
    } else if (pg->scope == SC_STEP) {
        uint32_t first = sel_first(TSEL), lo = 128u, j, count = 0;
        for (k = sel_lo(); k <= sel_hi() && k < NSTEP; k++)
            for (j = 0; j < TSEL->step[k].n; j++)
                if (sel_step(TSEL, k) && sel_voice(k, j) && !step_tied(&TSEL->step[k], j)) {
                    count++; if (k == first && TSEL->step[k].note[j] < lo) lo = TSEL->step[k].note[j];
                }
        if (lo < 128u) note_name(value, lo); else str_cpy(value, "--", sizeof value);
        if (count > 1u) { unit[0] = '+'; fmt_int(unit + 1u, (int32_t)count - 1); }
        else unit[0] = 0;
        skiff_card(b, 0u, "PITCH", value, unit, 0u, 2u, first < NSTEP ? 1u : 0u, 0u);
        if (first < NSTEP) fmt_int(value, (int32_t)first + 1); else str_cpy(value, "--", sizeof value);
        skiff_card(b, 1u, "START", value, "", 0u, 1u, first < NSTEP ? 1u : 0u, 0u);
        a = first < NSTEP ? len_get(TSEL, first) : 0u;
        if (a >= LEN_STEPS - 1u) {
            fmt_int(value, (int32_t)a - LEN_STEPS + 2);
            str_cpy(unit, a >= LEN_STEPS ? "STEPS" : "STEP", sizeof unit);
        } else if (a) {
            const char *u;
            param_format(track_desc(TSEL, P_SGATE), (int16_t)a, value, &u);
            str_cpy(unit, u, sizeof unit);
        } else { str_cpy(value, TSEL->step[ui.cursor].time == ST_TIE ? "TIE" : "--", sizeof value); unit[0] = 0; }
        skiff_card(b, 2u, "LENGTH", value, unit, 0u, 1u, first < NSTEP ? 1u : 0u, 0u);
        if (first < NSTEP) fmt_int(value, (int32_t)step_vel(&TSEL->step[first]));
        else str_cpy(value, "--", sizeof value);
        skiff_card(b, 3u, "VELOCITY", value, "", first < NSTEP ? step_vel(&TSEL->step[first]) * 128u : 0u,
                   1u, first < NSTEP ? 1u : 0u, 0u);
    }
}

static void skiff_sheet_cards(uint8_t *b, const page_t *pg)
{
    unsigned k;
    if (pg->graph == GR_FMOP || pg->graph == GR_FMALL) {
        unsigned which = pg->graph == GR_FMALL, w = fm_ui[1u + which] % FM_NWIN;
        const uint8_t *patch = fm6_patch[fm_tr()];
        for (k = 0; k < 4u; k++) {
            const fm_field_t *f = &FM_F[which][w][k];
            char value[16]; const char *unit; int32_t gauge;
            if (!f->label) continue;
            gauge = fm_val(f, fm_at(which, f), patch, TSEL, value, &unit);
            skiff_card(b, k, f->label, value, unit,
                       gauge < 0 ? 0u : (uint32_t)gauge * 16383u / 1000u,
                       1u, 1u, 0u);
        }
    } else if (pg->graph == GR_DXSND) {
        const dx_lane_t *lane = &dx_kit[dx_ui_lane & 7u];
        unsigned w = dx_ui_win % DX_NWIN;
        for (k = 0; k < 4u; k++) {
            uint32_t kind = DX_WIN[w][k][0], id = DX_WIN[w][k][1], mode = lane->mode;
            char label[16], value[16];
            switch (kind) {
            case DXK_A: case DXK_B: {
                uint32_t v = kind == DXK_A ? lane->a[id] : lane->b[id];
                str_cpy(label, DX_VAL[id], sizeof label);
                str_cpy(label + str_len(label), kind == DXK_A ? " A" : " B", sizeof label - str_len(label));
                fmt_int(value, (int32_t)v);
                skiff_card(b, k, label, value, "", v * 16383u / 127u, 1u, 1u, 0u);
                break;
            }
            case DXK_LEVEL:
                skiff_param_card(b, k, "LEVEL", &TP[P_LN0 + (dx_ui_lane & 7u)],
                                 TSEL->p[P_LN0 + (dx_ui_lane & 7u)], 1u);
                break;
            case DXK_DRV:
                skiff_card(b, k, "DRIVE", mode & DX_DRIVE ? "ON" : "OFF", "", 0u, 3u, 1u, 0u);
                break;
            case DXK_WAVE:
                skiff_card(b, k, "WAVE", DX_WAVE[mode & 3u], "", 0u, 2u, 1u, 0u);
                break;
            case DXK_FILT:
                skiff_card(b, k, "FILTER", DX_FILT[((mode >> 2) & 3u) % 3u], "", 0u, 2u, 1u, 0u);
                break;
            case DXK_SNAP:
                skiff_card(b, k, "SNAP", mode & 0x10u ? "ON" : "OFF", "", 0u, 3u, 1u, 0u);
                break;
            default:
                skiff_card(b, k, "P SHAPE", DX_PMOD[(mode >> 5) & 3u], "", 0u, 2u, 1u, 0u);
                break;
            }
        }
    }
}

static unsigned skiff_steps(uint8_t *b, unsigned at, uint8_t screen)
{
    const track_t *t = TSEL;
    unsigned first = (screen == CAFA_SCREEN_NOTES ? ui.bank : ui.cursor / 16u) * 16u;
    unsigned n = 16u, i, p;
    uint32_t length = (uint32_t)t->p[P_SLEN];
    if (length > NSTEP) length = NSTEP;
    if (first >= length) first = length ? ((length - 1u) / 16u) * 16u : 0u;
    if (first + n > length) n = length > first ? length - first : 0u;
    skiff_u14(b + 17u, first); b[19] = (uint8_t)n;
    skiff_u14(b + 20u, ui.cursor); skiff_u14(b + 22u, song.playing ? seq_at(t) : 16383u);
    if (screen == CAFA_SCREEN_NOTES && !drum_track(t)) {
        unsigned count = 0, j, end = first + n;
        p = skiff_sec(b, at, CAFA_NATIVE_NOTES, 2u + 7u * 256u);
        if (!p) return at;
        for (i = 0; i < end; i++)
            for (j = 0; j < t->step[i].n && count < 256u; j++) {
                const step_t *st = &t->step[i];
                unsigned start, stop, visible_stop;
                uint8_t *q;
                if (step_tied(st, j)) continue;
                stop = i + note_span(t, i, j);
                if (stop <= first) continue;
                start = i < first ? first : i;
                visible_stop = stop > end ? end : stop;
                q = b + p + 2u + 7u * count++;
                q[0] = st->note[j] & 127u;
                skiff_u14(q + 1u, (start - first) * 128u);
                skiff_u14(q + 3u, (visible_stop - start) * 128u);
                q[5] = (uint8_t)((sel_step(t, i) && sel_voice(i, j) ? 1u : 0u) |
                                 (i < first ? 2u : 0u) | (stop > end ? 4u : 0u) |
                                 (st->flags & SF_ACCENT ? 32u : 0u));
                q[6] = (uint8_t)step_vel(st);
            }
        skiff_u14(b + at + 1u, 2u + 7u * count);
        skiff_u14(b + p, count);
        return p + 2u + 7u * count;
    }
    p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + 7u * n);
    if (!p) return at;
    skiff_u14(b + p, n);
    for (i = 0; i < n; i++) {
        uint32_t st = first + i; uint8_t *q = b + p + 2u + 7u * i;
        uint32_t on = drum_track(t) ? (step_lanes(&t->step[st]) >> ui.lane) & 1u :
                      t->step[st].n || t->step[st].hit;
        uint32_t accent = drum_track(t) ? (step_accents(&t->step[st]) >> ui.lane) & 1u :
                          (t->step[st].flags & SF_ACCENT) != 0u;
        skiff_u14(q, on ? step_vel(&t->step[st]) * 128u : 0u);
        q[2] = (uint8_t)((on ? 1u : 0u) | (st == ui.cursor ? 2u : 0u) |
                         (song.playing && st == seq_at(t) ? 4u : 0u) | (accent ? 32u : 0u));
        skiff_text(q + 3u, 4u, "");
    }
    return p + 2u + 7u * n;
}

static unsigned skiff_stock_curve(uint8_t *b, unsigned at, const page_t *pg)
{
    int16_t level[217]; int32_t xa = 0, xd = 0, xr = 0;
    unsigned i, p, count = 55u, env = pg->graph == GR_ADSR;
    if (!env && pg->graph != GR_LFO) return at;
    if (env)
        env_fill4(TSEL->p[pg->id[0]], TSEL->p[pg->id[1]],
                  TSEL->p[pg->id[2]], TSEL->p[pg->id[3]], level, 216, &xa, &xd, &xr);
    p = skiff_sec(b, at, CAFA_NATIVE_CURVE, 2u + count * 4u);
    if (!p) return at;
    skiff_u14(b + p, count);
    for (i = 0; i < count; i++) {
        uint32_t x = i * 4u, y;
        int32_t v = env ? level[x] : lfo_view_at(TSEL, x, 216);
        y = env ? (uint32_t)clamp(v, 0, 1000) * 16383u / 1000u :
                  (uint32_t)clamp(v + 32768, 0, 65535) / 4u;
        skiff_u14(b + p + 2u + i * 4u, x * 16383u / 216u);
        skiff_u14(b + p + 4u + i * 4u, y);
    }
    at = p + 2u + count * 4u;
    if (env) {
        p = skiff_sec(b, at, CAFA_NATIVE_MARKERS, 1u + 3u * 13u);
        if (p) {
            static const char *const names[3] = {"ATTACK", "DECAY", "RELEASE"};
            uint32_t pos[3] = {(uint32_t)xa, (uint32_t)xd, (uint32_t)xr};
            b[p] = 3u;
            for (i = 0; i < 3u; i++) {
                uint8_t *q = b + p + 1u + i * 13u;
                skiff_u14(q, pos[i] * 16383u / 216u);
                skiff_u14(q + 2u, (uint32_t)level[pos[i]] * 16383u / 1000u);
                q[4] = 1u; skiff_text(q + 5u, 8u, names[i]);
            }
            at = p + 1u + 3u * 13u;
        }
    } else {
        p = skiff_sec(b, at, CAFA_NATIVE_MARKERS, 14u);
        if (p) {
            b[p] = 1u;
            skiff_u14(b + p + 1u, lfo_view_now(TSEL, 16383));
            skiff_text(b + p + 6u, 8u, "PHASE");
            at = p + 14u;
        }
    }
    return at;
}

static unsigned skiff_mod_content(uint8_t *b, unsigned at)
{
    static const char *const col[4] = {"PITCH", "CUT", "SHAPE", "AMP"};
    unsigned p = skiff_sec(b, at, CAFA_NATIVE_ROWS, 1u + 12u * 48u), q;
    if (!p) return at;
    b[p] = 12u;
    for (q = 0; q < 12u; q++) {
        uint8_t *r = b + p + 1u + 48u * q;
        const char *unit;
        char value[20], amount[20];
        uint32_t route = q < 8u ? q : q - 1u;
        skiff_text(r, 40u, "");
        if (q < 4u) skiff_text(r, 16u, col[q]);
        if (q < MOD_SLOT0) {
            param_format(&TP[MOD_FIXED[q]], TSEL->p[MOD_FIXED[q]], value, &unit);
            skiff_text(r + 16u, 16u, value);
            r[40] = (uint8_t)((mod_ui_route == q ? 1u : 0u) |
                              (!mod_dead(TSEL, q) ? 2u : 0u));
        } else if (q == 7u) {
            skiff_text(r + 16u, 16u, "-"); r[40] = 16u;
        } else {
            uint32_t offset = P_M1SRC + 3u * (q - 8u);
            int16_t src = TSEL->p[offset], dst = TSEL->p[offset + 1u];
            skiff_text(r, 16u, N_MSRC[clamp(src, 0, MS_N - 1)]);
            skiff_text(r + 16u, 16u, mod_dst_name(TSEL, dst));
            param_format(&TP[P_M1AMT], TSEL->p[offset + 2u], amount, &unit);
            if (unit && *unit) {
                unsigned n = str_len(amount);
                if (n + 1u < sizeof amount) {
                    amount[n++] = ' ';
                    str_cpy(amount + n, unit, sizeof amount - n);
                }
            }
            skiff_text(r + 32u, 8u, amount);
            r[40] = (uint8_t)((mod_ui_route == route ? 1u : 0u) |
                              (src && dst && TSEL->p[offset + 2u] ? 2u : 16u));
        }
    }
    skiff_u14(b + 24u, mod_ui_route < MOD_SLOT0 ? mod_ui_route : mod_ui_route + 1u);
    skiff_u14(b + 28u, 12u);
    return p + 1u + 12u * 48u;
}

static unsigned skiff_home_steps(uint8_t *b, unsigned at, unsigned first_track)
{
    unsigned p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + SKIFF_VIEW_TRACKS * 16u * 7u);
    unsigned k, i;
    if (!p) return at;
    skiff_u14(b + p, SKIFF_VIEW_TRACKS * 16u);
    for (k = 0; k < SKIFF_VIEW_TRACKS; k++) {
        const track_t *t = &trk[first_track + k];
        uint32_t count, first = hl_bar(t, &count);
        for (i = 0; i < 16u; i++) {
            uint8_t *q = b + p + 2u + (k * 16u + i) * 7u;
            const step_t *st = &t->step[first + (i < count ? i : 0u)];
            uint32_t on = i < count && (step_on(st) || st->hit);
            skiff_u14(q, on ? step_vel(st) * 128u : 0u);
            q[2] = (uint8_t)((on ? 1u : 0u) |
                             (i < count && step_tie(st) ? 8u : 0u) |
                             (song.playing && i < count && t->seq_idx == first + i ? 4u : 0u) |
                             (i >= count ? 64u : 0u));
            skiff_text(q + 3u, 4u, "");
        }
    }
    skiff_u14(b + 17u, hl_bar(TSEL, &i));
    b[19] = (uint8_t)i;
    skiff_u14(b + 22u, song.playing ? TSEL->seq_idx : 16383u);
    return p + 2u + SKIFF_VIEW_TRACKS * 16u * 7u;
}

static unsigned skiff_save_content(uint8_t *b, unsigned at, uint8_t screen)
{
    unsigned i, count;
    uint32_t selected = 16383u, total = 0u, first = 0u;
    char names[16][20], tags[16][16];
    uint8_t project_used[4] = {0};
    const char *labels[16], *values[16];
    if (screen == CAFA_SCREEN_PATTERNS) {
        unsigned p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + NPAT * 7u);
        if (!p) return at;
        skiff_u14(b + p, NPAT);
        for (i = 0; i < NPAT; i++) {
            uint8_t *q = b + p + 2u + i * 7u;
            uint32_t used = pool_used(i);
            skiff_u14(q, used ? pool_refs(i) : 0u);
            q[2] = (uint8_t)((used ? 1u : 0u) | (i == ui.bslot % NPAT ? 2u : 0u));
            pat_num(names[0], i); skiff_text(q + 3u, 4u, names[0]);
        }
        skiff_u14(b + 24u, ui.bslot % NPAT); skiff_u14(b + 28u, NPAT);
        return p + 2u + NPAT * 7u;
    }
    if (screen == CAFA_SCREEN_PRESETS) {
        selected = preset_pos(&total); count = 7u;
        if (total) first = preset_visible(selected, total, 0u);
        for (i = 0; i < count; i++) {
            uint32_t k, e, index = preset_visible(selected, total, i);
            tags[i][0] = names[i][0] = 0;
            if (index < total) {
                e = preset_at(index, &k);
                if (e == NENGINES) {
                    up_slot_label(tags[i], k); up_name(k, names[i]);
                } else {
                    str_cpy(tags[i], eng_abbr(ENGINES[e]->name), sizeof tags[i]);
                    str_cpy(names[i], ENGINES[e]->presets[k].name, sizeof names[i]);
                }
            }
            labels[i] = names[i]; values[i] = "";
        }
    } else if (screen == CAFA_SCREEN_USER) {
        total = UP_SLOTS; selected = ui.uslot;
        first = (uint32_t)clamp((int32_t)selected - 3, 0, UP_SLOTS - 7);
        count = 7u;
        for (i = 0; i < count; i++) {
            uint32_t slot = first + i;
            up_slot_label(tags[i], slot);
            if (up_used(slot)) up_name(slot, names[i]);
            else str_cpy(names[i], "--", sizeof names[i]);
            labels[i] = names[i]; values[i] = "";
        }
    } else if (screen == CAFA_SCREEN_PROJECT) {
        total = count = 4u; selected = (uint32_t)song.g[G_SLOT] - 1u;
        for (i = 0; i < count; i++) {
            tags[i][0] = (char)('A' + i); tags[i][1] = 0;
            project_used[i] = (uint8_t)!!project_name(i, names[i]);
            if (!project_used[i]) str_cpy(names[i], "--", sizeof names[i]);
            else if (!names[i][0]) str_cpy(names[i], "USED", sizeof names[i]);
            labels[i] = names[i]; values[i] = "";
        }
    } else return at;
    at = skiff_rows(b, at, count, labels, values, 16u);
    for (i = 0; i < count; i++) {
        uint32_t id = screen == CAFA_SCREEN_PRESETS ? preset_visible(selected, total, i) : first + i;
        uint8_t *r = b + at - count * 48u + i * 48u;
        unsigned present = id < total;
        unsigned favorite = 0u;
        if (present && screen == CAFA_SCREEN_PRESETS) {
            uint32_t slot, engine = preset_at(id, &slot);
            favorite = !!favorite_has(engine, slot);
        }
        if (present && screen == CAFA_SCREEN_USER) present = !!up_used(id);
        if (present && screen == CAFA_SCREEN_PROJECT) present = project_used[i];
        skiff_text(r + 32u, 8u, tags[i]);
        r[40] = (uint8_t)((id == selected ? 1u : 0u) |
                          (favorite ? 2u : 0u) | (present ? 0u : 16u));
        skiff_u14(r + 41u, id);
    }
    skiff_u14(b + 24u, selected < total ? selected : 16383u);
    skiff_u14(b + 26u, first < total ? first : 0u);
    skiff_u14(b + 28u, total);
    return at;
}

static unsigned skiff_menu_rows(uint8_t *b, unsigned at)
{
    uint32_t tab = menu_tab(), top = mr_top(), total = mtab_rows(tab);
    unsigned count = total - top < MTAB_ROWS ? total - top : MTAB_ROWS, i;
    unsigned p = skiff_sec(b, at, CAFA_NATIVE_ROWS, 1u + 48u * count);
    if (!p) return at;
    b[p] = (uint8_t)count;
    skiff_u14(b + 9u, tab);
    skiff_u14(b + 11u, MTAB_COUNT);
    skiff_u14(b + 24u, ui.menu_sel - mtab_first(tab));
    skiff_u14(b + 26u, top);
    skiff_u14(b + 28u, total);
    for (i = 0; i < count; i++) {
        uint32_t id = mtab_first(tab) + top + i;
        uint8_t *r = b + p + 1u + 48u * i;
        const char *name = id < MI_COUNT ? MI_NAME[id] : "";
        const char *value = id < MI_VALUES ? menu_vname(id, menu_get(id)) : "";
        skiff_text(r, 16u, name); skiff_text(r + 16u, 16u, value);
        skiff_text(r + 32u, 8u, "");
        r[40] = (uint8_t)((id == ui.menu_sel ? 1u : 0u) |
                          (menu_act(id) ? 32u : 2u));
        skiff_u14(r + 41u, menu_get(id));
        skiff_u14(r + 43u, menu_n(id) - 1u);
    }
    return p + 1u + 48u * count;
}

#if NENGINES <= 15u
/* The unexpanded stock source fits its entire picker on one page. */
static uint32_t ly_edit_top(void) { return 0u; }
static uint32_t ly_edit_engine(uint32_t key)
{
    return key < LY_INIT ? eng_vis(key) : NENGINES;
}
#endif

static unsigned skiff_layer_view(uint8_t *b, unsigned at, uint8_t screen)
{
    unsigned i, count;
    char value[20], unit[8];
    const char *labels[16], *values[16];
    if (screen == CAFA_SCREEN_FX_LAYER) {
        static const char *const names[PF_M1] = {"REPEAT 8", "REPEAT 16", "REPEAT 32", "REVERSE",
            "LOW PASS", "HIGH PASS", "TAPE STOP", "FREEZE", "OCT UP", "OCT DN",
            "GATE", "DUB", "FLANGE", "VINYL"};
        uint32_t held = perf_kill ? 0u : perf_held | perf_latched;
        uint32_t active = perf_kill ? 0u : perf_act;
        char keys[PF_M1][2];
        for (i = 0; i < PF_M1; i++) {
            keys[i][0] = W_NOTE[i]; keys[i][1] = 0;
            labels[i] = names[i]; values[i] = keys[i];
        }
        at = skiff_rows(b, at, PF_M1, labels, values, 16u);
        for (i = 0; i < PF_M1; i++) {
            uint8_t *r = b + at - PF_M1 * 48u + i * 48u;
            r[40] = (uint8_t)(((held >> i) & 1u ? 1u : 0u) |
                               ((active >> i) & 1u ? 2u : 0u) |
                               ((held & ~active) >> i & 1u ? 32u : 0u) |
                               ((perf_avail() >> i) & 1u ? 0u : 16u));
        }
        unsigned first = skiff_track_window(b);
        unsigned p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + SKIFF_VIEW_TRACKS * 7u);
        if (p) {
            skiff_u14(b + p, SKIFF_VIEW_TRACKS);
            for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
                unsigned k = first + i;
                uint8_t *q = b + p + 2u + i * 7u;
                char name[3] = {'T', (char)('1' + k), 0};
                skiff_u14(q, k);
                q[2] = (uint8_t)((trk[k].p[P_MUTE] || trk_cut(k)) ? 1u : 0u);
                skiff_text(q + 3u, 4u, name);
            }
            at = p + 2u + SKIFF_VIEW_TRACKS * 7u;
        }
        for (i = 0; i < 4u; i++) {
            int32_t v = perf_k[i];
            fmt_int(value, i == 0u && v < 0 ? -v : i == 3u && !perf_harm_on() ? 100 - v : v);
            skiff_card(b, i, i == 0u ? "FILTER" : i == 1u ? "CRUSH" : i == 2u ? "THROW" :
                       perf_harm_on() ? "SHIMR" : "DEPTH", v || i == 3u ? value : "OFF",
                       i == 0u ? v < 0 ? "LP" : v > 0 ? "HP" : "" : v || i == 3u ? "%" : "",
                       i == 0u ? (uint32_t)(v + 100) * 16383u / 200u :
                       i == 3u && !perf_harm_on() ? (uint32_t)(100 - v) * 16383u / 100u :
                       (uint32_t)v * 16383u / 100u, 1u,
                       (uint8_t)(v || (i == 3u && !perf_harm_on())),
                       i == 0u ? CAFA_NATIVE_ICON_ENV : i == 1u ? CAFA_NATIVE_ICON_WAVE :
                       i == 2u ? CAFA_NATIVE_ICON_FX : CAFA_NATIVE_ICON_MIX);
        }
    } else if (screen == CAFA_SCREEN_GLO_LAYER) {
        unsigned first = skiff_track_window(b);
        for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
            labels[i] = "SOLO"; values[i] = (perf_solo >> (first + i)) & 1u ? "SOLO" : "LIVE";
        }
        at = skiff_rows(b, at, SKIFF_VIEW_TRACKS, labels, values, song.sel - first);
        for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
            unsigned k = first + i;
            uint8_t *r = b + at - SKIFF_VIEW_TRACKS * 48u + i * 48u;
            r[40] = (uint8_t)((k == song.sel ? 1u : 0u) |
                              ((perf_solo >> k) & 1u ? 2u : 0u) |
                              (trk[k].p[P_MUTE] || trk_cut(k) ? 4u : 0u));
            skiff_text(r + 32u, 8u, trk[k].p[P_MUTE] || trk_cut(k) ? "MUTE" : "");
        }
        for (i = 0; i < 4u; i++) {
            uint32_t k = trk_top() + i;
            skiff_param_card(b, i, "LEVEL", &TP[P_LEVEL], trk[k].p[P_LEVEL], glo_sounding(k) ? 1u : 0u);
        }
        unsigned p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + 3u * 7u);
        if (p) {
            static const char *const names[3] = {"ALL", "FILL", "TAP"};
            skiff_u14(b + p, 3u);
            for (i = 0; i < 3u; i++) {
                uint8_t *q = b + p + 2u + i * 7u;
                q[2] = (uint8_t)((i == 1u && seq_fill ? 1u : 0u) |
                                 (i == 2u && song.g[G_CLOCK] ? 64u : 0u));
                skiff_text(q + 3u, 4u, names[i]);
            }
            at = p + 23u;
        }
    } else if (screen == CAFA_SCREEN_SEL_LAYER) {
        unsigned rows_at = at;
        for (i = 0; i < 16u; i++) { labels[i] = TP[P_SCALE].names[i]; values[i] = ""; }
        at = skiff_rows(b, at, 16u, labels, values, (uint32_t)TSEL->p[P_SCALE] % 16u);
        for (i = 0; at != rows_at && i < 16u; i++)
            b[rows_at + 4u + i * 48u + 40u] &= 1u;
        skiff_param_card(b, 0u, 0, &TP[P_ROOT], TSEL->p[P_ROOT], 1u);
        skiff_param_card(b, 1u, 0, &TP[P_SCALE], TSEL->p[P_SCALE], 1u);
        skiff_param_card(b, 2u, 0, &TP[P_CHRD], TSEL->p[P_CHRD], 1u);
        skiff_param_card(b, 3u, 0, &TP[P_VOIC], TSEL->p[P_VOIC], 1u);
    } else if (screen == CAFA_SCREEN_EDIT_LAYER) {
        uint32_t total, pos, top = ly_edit_top();
        unsigned rows_at = at;
        b[7] = drum_track(TSEL) ? CAFA_NATIVE_VARIANT_DRUM : CAFA_NATIVE_VARIANT_NORMAL;
        count = LY_INIT + 1u;
        for (i = 0; i < count; i++) {
            uint32_t e = ly_edit_engine(i);
            if (i == LY_INIT) { labels[i] = "INIT"; values[i] = ""; continue; }
            if (e >= NENGINES) { labels[i] = ""; values[i] = "UNAVAILABLE"; continue; }
            labels[i] = ENGINES[e]->name; values[i] = eng_allowed(song.sel, e) ? "" : "UNAVAILABLE";
        }
        at = skiff_rows(b, at, count, labels, values, eng_rank(TSEL->eng_req % NENGINES) - top);
        for (i = 0; at != rows_at && i < count; i++) {
            uint8_t *flags = b + rows_at + 4u + i * 48u + 40u;
            *flags &= 1u;
            if (i != LY_INIT && (ly_edit_engine(i) >= NENGINES ||
                                 !eng_allowed(song.sel, ly_edit_engine(i))))
                *flags |= 16u;
        }
        if (drum_track(TSEL)) {
            unsigned p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + NLANE * 7u);
            if (p) {
                skiff_u14(b + p, NLANE);
                for (i = 0; i < NLANE; i++) {
                    uint8_t *q = b + p + 2u + i * 7u;
                    q[2] = (uint8_t)((drum_mute[song.sel] >> i) & 1u);
                    skiff_text(q + 3u, 4u, drum_lane_abbr(TSEL, i));
                }
                at = p + 2u + NLANE * 7u;
            }
        }
        skiff_card(b, 0u, "ENG", ENGINES[TSEL->eng_req % NENGINES]->name, "", 0u, 2u, 1u, 0u);
        pos = eng_list_pos(&total); fmt_int(value, (int32_t)pos + 1); fmt_kn(unit, -1, (int32_t)total);
        skiff_card(b, 1u, "No.", value, unit, total > 1u ? pos * 16383u / (total - 1u) : 0u, 1u, 1u, 0u);
        skiff_card(b, 2u, "FAV", preset_favorite() ? "ON" : "OFF", "", 0u, 3u, 1u, 0u);
        if (!drum_track(TSEL)) {
            char tag[8], sound[20];
            uint32_t user = user_of(TSEL);
            if (user < UP_SLOTS) { up_slot_label(tag, user); up_name(user, sound); }
            else {
                const engine_t *e = ENGINES[TSEL->eng_req % NENGINES];
                tag[0] = (char)('0' + (pos + 1u) / 10u % 10u);
                tag[1] = (char)('0' + (pos + 1u) % 10u); tag[2] = 0;
                str_cpy(sound, e->npresets ? e->presets[TSEL->preset % e->npresets].name : "", sizeof sound);
            }
            char line[24];
            str_cpy(line, tag, sizeof line);
            str_cpy(line + str_len(line), " ", sizeof line - str_len(line));
            str_cpy(line + str_len(line), sound, sizeof line - str_len(line));
            labels[0] = line; at = skiff_lines(b, at, 1u, labels);
        }
    } else if (screen == CAFA_SCREEN_SEQ_LAYER) {
        char keys[TL_N][2];
        char tool_names[TL_N][16];
        count = TL_N < 16u ? TL_N : 16u;
        for (i = 0; i < count; i++) {
            keys[i][0] = W_NOTE[i]; keys[i][1] = 0;
            str_cpy(tool_names[i], TL_NAME[i], sizeof tool_names[i]);
            if (drum_track(TSEL) && i >= TL_LCLR && i < TL_DOUBLE) {
                str_cpy(tool_names[i] + str_len(tool_names[i]), " ",
                        sizeof tool_names[i] - str_len(tool_names[i]));
                str_cpy(tool_names[i] + str_len(tool_names[i]), drum_lane_abbr(TSEL, ui.lane),
                        sizeof tool_names[i] - str_len(tool_names[i]));
            }
            labels[i] = tool_names[i]; values[i] = keys[i];
        }
        at = skiff_rows(b, at, count, labels, values, 16u);
        for (i = 0; i < count; i++)
            b[at - count * 48u + i * 48u + 40u] = (uint8_t)(!tl_cell(TSEL, i) || eu.on ? 16u : 0u);
        b[7] = drum_track(TSEL) ? CAFA_NATIVE_VARIANT_DRUM : CAFA_NATIVE_VARIANT_NORMAL;
        unsigned eu_len = eu.on ? tl_len(TSEL) : 0u;
        if (eu_len > 64u) eu_len = 64u;
        unsigned lanes = drum_track(TSEL) ? NLANE : 0u;
        if (eu.on) {
            uint8_t *r = b + at - count * 48u;
            r[40] |= 64u;
            skiff_u14(r + 41u, eu_len);
            skiff_u14(r + 43u, eu.steps);
            skiff_u14(r + 45u, eu.rot);
        }
        if (eu_len || lanes) {
            unsigned p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + (eu_len + lanes) * 7u);
            if (p) {
                skiff_u14(b + p, eu_len + lanes);
                for (i = 0; i < eu_len; i++) {
                    uint8_t *q = b + p + 2u + i * 7u;
                    q[2] = (uint8_t)(eu_hit(i) ? 1u | (i < eu.steps ? 32u : 0u) : 0u);
                    skiff_text(q + 3u, 4u, "");
                }
                for (i = 0; i < lanes; i++) {
                    uint8_t *q = b + p + 2u + (eu_len + i) * 7u;
                    q[2] = (uint8_t)(i == ui.lane ? 2u : 0u);
                    skiff_text(q + 3u, 4u, drum_lane_abbr(TSEL, i));
                }
                at = p + 2u + (eu_len + lanes) * 7u;
            }
        }
        if (eu.on) {
            fmt_int(value, eu.hits); skiff_card(b, 0u, "HITS", value, "", eu.steps ? eu.hits * 16383u / eu.steps : 0u, 1u, 1u, 0u);
            fmt_int(value, eu.steps); skiff_card(b, 1u, "STEPS", value, "", eu.steps * 16383u / NSTEP, 1u, 1u, 0u);
            fmt_int(value, eu.rot); skiff_card(b, 2u, "ROTATE", value, "", eu.steps > 1u ? eu.rot * 16383u / (eu.steps - 1u) : 0u, 1u, (uint8_t)(eu.rot != 0u), 0u);
            if (drum_track(TSEL)) skiff_card(b, 3u, "LANE", drum_lane_name(TSEL, ui.lane), "", 0u, 2u, 1u, 0u);
        } else {
            skiff_param_card(b, 0u, 0, &TP[P_SLEN], TSEL->p[P_SLEN], 1u);
            skiff_param_card(b, 1u, 0, &TP[P_SDIV], TSEL->p[P_SDIV], 1u);
            skiff_param_card(b, 2u, 0, &TP[P_SSWING], TSEL->p[P_SSWING], 1u);
            skiff_param_card(b, 3u, 0, &TP[P_SGATE], TSEL->p[P_SGATE], 1u);
        }
    } else if (screen == CAFA_SCREEN_REC_LAYER) {
        char clear[16]; clear[0] = 'T'; clear[1] = (char)('1' + song.sel);
        str_cpy(clear + 2, rec_clearable() ? " SEQUENCE" : " EMPTY", sizeof clear - 2u);
        labels[0] = "CLEAR"; values[0] = clear;
        for (i = 1; i < 4u; i++) { labels[i] = RL_LABEL[i - 1u]; values[i] = rl_name(i - 1u, rp_get(RL_F[i - 1u])); }
        at = skiff_rows(b, at, 4u, labels, values, 16u);
        if (!rec_clearable()) b[at - 4u * 48u + 40u] |= 16u;
        for (i = 1; i < 4u; i++)
            skiff_u14(b + at - (4u - i) * 48u + 41u, rp_get(RL_F[i - 1u]));
        for (i = 0; i < 3u; i++)
            skiff_card(b, i, RL_LABEL[i], rl_name(i, rp_get(RL_F[i])), "", rp_get(RL_F[i]) * 16383u / 2u, 1u,
                       (uint8_t)(i == 2u || rp_get(RL_F[i]) != 0u), 0u);
        skiff_card(b, 3u, "FEEL", rec_feel ? "ON" : "OFF", "", rec_feel ? 16383u : 0u, 1u,
                   (uint8_t)(rec_feel != 0u), 0u);
        for (i = 0; i < 9u; i++) labels[i] = rl_name(i / 3u, i % 3u);
        at = skiff_lines(b, at, 9u, labels);
    } else if (screen == CAFA_SCREEN_ARP_LAYER) {
        static const char *const send_labels[4] = {0, "REVERB", "DELAY", "DIST"};
        char numbers[SKIFF_VIEW_TRACKS][2];
        unsigned p, j, first = skiff_track_window(b);
        for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
            numbers[i][0] = (char)('1' + first + i);
            numbers[i][1] = 0;
            labels[i] = numbers[i];
        }
        p = at;
        at = skiff_rows(b, at, SKIFF_VIEW_TRACKS, labels, 0, song.sel - first);
        if (at != p) {
            uint8_t *row = b + p + 4u;
            skiff_u14(row + 41u, perf_scenes());
            for (i = 0; i < SKIFF_VIEW_TRACKS; i++, row += 48u) {
                unsigned k = first + i;
                row[40] |= (uint8_t)((trk[k].p[P_MUTE] ? 4u : 0u) | (mute_arm[k] ? 64u : 0u));
            }
        }
        for (i = 0; i < 4u; i++) {
            unsigned id = throw_id(TSEL, i);
            const param_desc_t *d = track_desc(TSEL, id);
            int active;
            int32_t offset = throw_value(song.sel, i, &active) - TSEL->p[id];
            int32_t range = d->max - d->min;
            int32_t ratio = range ? 500 + offset * 500 / range : 500;
            value[0] = offset < 0 ? '-' : '+';
            fmt_int(value + 1, offset < 0 ? -offset : offset);
            skiff_card(b, i, i ? send_labels[i] : d->label, value, "",
                       (uint32_t)clamp(ratio, 0, 1000) * 16383u / 1000u,
                       1u, (uint8_t)(9u | ((ui.hot_t && ui.hot_col == i) ? 2u : 0u)),
                       i == 0u ? CAFA_NATIVE_ICON_NONE : CAFA_NATIVE_ICON_FX);
        }
        p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + (SKIFF_VIEW_TRACKS + 1u) * BANK_SLOTS * 7u);
        if (p) {
            skiff_u14(b + p, (SKIFF_VIEW_TRACKS + 1u) * BANK_SLOTS);
            for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
                unsigned k = first + i;
                for (j = 0; j < BANK_SLOTS; j++) {
                    uint8_t *q = b + p + 2u + (i * BANK_SLOTS + j) * 7u;
                    char name[2] = {(char)('A' + j), 0};
                    q[2] = (uint8_t)((ent_on(k, j) ? 1u : 0u) |
                                     (bank_cur(k) == j ? 2u : 0u) |
                                     (bank_next(k) == j ? 4u : 0u));
                    skiff_text(q + 3u, 4u, name);
                }
            }
            for (i = 0; i < BANK_SLOTS; i++) {
                uint8_t *q = b + p + 2u + (SKIFF_VIEW_TRACKS * BANK_SLOTS + i) * 7u;
                q[2] = (uint8_t)(scene_used(i) ? 1u |
                                 (scene_now() == i ? stage_pending() ? 4u : 2u : 0u) : 0u);
                fmt_int(value, (int32_t)i + 1); skiff_text(q + 3u, 4u, value);
            }
            at = p + 2u + (SKIFF_VIEW_TRACKS + 1u) * BANK_SLOTS * 7u;
        }
    }
    return at;
}

static unsigned skiff_prefs(uint8_t *b, unsigned at)
{
    unsigned custom = cpal_on() ? 1u : 0u, i;
    unsigned len = custom ? 35u : 2u, p = at + 3u;
    if (p + len > 3040u) return at;
    b[at] = CAFA_NATIVE_PREFS; skiff_u14(b + at + 1u, len);
    memset(b + p, 0, len);
    b[p] = (uint8_t)(settings.palette % NPALETTES);
    b[p + 1u] = (uint8_t)(((ui_prefs & PREF_LARGE) ? 1u : 0u) |
                          ((ui_prefs & PREF_ANIM_OFF) ? 2u : 0u) |
                          (custom ? 4u : 0u) | 8u);
    if (custom)
        for (i = 0; i < 11u; i++) {
            uint32_t color = CPAL_COLS[2u * i] | (uint32_t)CPAL_COLS[2u * i + 1u] << 8;
            b[p + 2u + 3u * i] = (uint8_t)(color & 127u);
            b[p + 3u + 3u * i] = (uint8_t)((color >> 7) & 127u);
            b[p + 4u + 3u * i] = (uint8_t)((color >> 14) & 3u);
        }
    return p + (custom ? 35u : 2u);
}

/* Stock draws this timed notice over the current graph, leaving its page intact. */
static unsigned skiff_transient_box(uint8_t *b, unsigned at, uint8_t screen)
{
    unsigned p;
    if (!ui.take_t || screen > CAFA_SCREEN_SCOPE || screen == CAFA_SCREEN_MIXER ||
        (int32_t)graph_h() < TB_Y + TB_H + 1) return at;
    p = skiff_sec(b, at, CAFA_NATIVE_TRANSIENT_BOX, 67u);
    if (!p) return at;
    b[p] = take_box.trk % NTRK;
    b[p + 1u] = take_box.kind ? 1u : 0u;
    skiff_text(b + p + 2u, 24u, take_box.title);
    skiff_text(b + p + 26u, 40u, take_box.line);
    b[p + 66u] = take_box.kind ? (uint8_t)((undo_back < undo_n ? 1u : 0u) |
                                                (undo_back ? 2u : 0u)) : 0u;
    return p + 67u;
}

static unsigned skiff_chrome(uint8_t *b, unsigned at, uint8_t screen)
{
    unsigned p;
    uint32_t mark;
    char name[20], label[16];
    if (screen > CAFA_SCREEN_SCOPE) return at;
    p = skiff_sec(b, at, CAFA_NATIVE_CHROME, 56u);
    if (!p) return at;
    skiff_u14(b + p, song.playing ? seq_at(TSEL) : 16383u);
    b[p + 2u] = !song.rec ? 0u : (song.rec >> song.sel) & 1u ? 2u : 1u;
    label[0] = 0;
    if (song.octave) {
        str_cpy(label, "OCT ", sizeof label);
        if (song.octave > 0) str_cpy(label + str_len(label), "+", sizeof label - str_len(label));
        fmt_int(label + str_len(label), song.octave);
    }
    skiff_text(b + p + 3u, 8u, label);
    mark = project_mark();
    b[p + 11u] = mark ? (uint8_t)(mark & 127u) : ' ';
    b[p + 12u] = mark >> 8 ? 1u : 0u;
    label[0] = 0;
    if (screen <= CAFA_SCREEN_SONG) head_mark(label);
    skiff_text(b + p + 13u, 16u, label);
    skiff_text(b + p + 29u, 8u, ENGINES[TSEL->eng_req % NENGINES]->name);
    sound_name(TSEL, name);
    skiff_text(b + p + 37u, 16u, name);
    return p + 56u;
}

#include "skiff_view_engines.inc"
#include "skiff_view_fx.inc"
#include "skiff_view_sequence.inc"

/* The returned length leaves 32 bytes of caller scratch but is itself a complete view. */
static unsigned skiff_make_view(uint8_t out[3072])
{
    unsigned at = CAFA_NATIVE_HEADER, i, page = ui.page < NPAGES ? ui.page : 0u;
    uint8_t screen = skiff_screen(), family = 6u;
    const page_t *pg = &PAGES[page];
    char title[32], subtitle[32], v[24], u[16];
    const char *labels[16], *values[16];
    memset(out, 0, CAFA_NATIVE_HEADER);
    out[0] = CAFA_NATIVE_KIND; out[1] = 0u;
    skiff_u28(out + 2u, skiff_edit_context());
    out[6] = screen; out[7] = CAFA_NATIVE_VARIANT_NORMAL;
    if (screen <= CAFA_SCREEN_SONG) family = skiff_family(pg->fam);
    else if (screen >= CAFA_SCREEN_FX_LAYER && screen <= CAFA_SCREEN_ARP_LAYER)
        family = (uint8_t)cafa_native_family(screen);
    else if (screen == CAFA_SCREEN_SHIFT || screen >= CAFA_SCREEN_CONFIRM && screen <= CAFA_SCREEN_UPDATE)
        family = ui.home ? 6u : skiff_family(pg->fam);
    if (screen == 72u) family = 6u;
    out[8] = family;
    skiff_u14(out + 9u, 0u); skiff_u14(out + 11u, 1u);
    skiff_u14(out + 13u, song.sel % NTRK); skiff_u14(out + 15u, NTRK);
    skiff_u14(out + 20u, 16383u); skiff_u14(out + 22u, 16383u);
    skiff_u14(out + 24u, 16383u);
    if (song.playing && screen >= CAFA_SCREEN_FX_LAYER && screen <= CAFA_SCREEN_SHIFT)
        skiff_u14(out + 22u, seq_at(TSEL) & ~3u);
    skiff_u14(out + 30u, (uint32_t)song.g[G_BPM] * 10u);
    out[32] = (uint8_t)((song.playing ? 1u : 0u) | (song.rec ? 2u : 0u));
    skiff_text(out + CAFA_NATIVE_TITLE, 24u, "");
    skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, "");
    skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "");
    for (i = 0; i < 4u; i++) {
        uint8_t *q = out + CAFA_NATIVE_CONTROLS + 36u * i;
        skiff_text(q, 30u, "");
    }
    title[0] = subtitle[0] = v[0] = u[0] = 0;
    if (screen <= CAFA_SCREEN_SONG) {
        uint32_t eng = TSEL->eng_req % NENGINES;
        if (screen == CAFA_SCREEN_NOTES || screen == CAFA_SCREEN_FEEL)
            out[7] = drum_track(TSEL) ? CAFA_NATIVE_VARIANT_DRUM : CAFA_NATIVE_VARIANT_NORMAL;
        else if (screen == CAFA_SCREEN_LANES || screen == CAFA_SCREEN_LANE_MIX)
            out[7] = CAFA_NATIVE_VARIANT_DRUM_LANES;
        else if (screen == CAFA_SCREEN_SOUND)
            out[7] = CAFA_NATIVE_VARIANT_DRUM_SOUND;
        else if (screen == CAFA_SCREEN_SLICES || screen == CAFA_SCREEN_SAMPLES
#if defined(ENGI_LOFI_SMP)
                 || ((screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2) &&
                     (eng == ENGI_LOFI_SMP || eng == ENGI_CLOUD))
#endif
        )
            out[7] = CAFA_NATIVE_VARIANT_SAMPLE_WAVE;
        else if (screen == CAFA_SCREEN_OPERATOR || screen == CAFA_SCREEN_ALL_OPS ||
                 screen >= CAFA_SCREEN_OP1_ENV && screen <= CAFA_SCREEN_OP_LEVEL ||
                 (screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2) &&
                 (eng == ENGI_FM6 || eng == ENGI_DIGITAL))
            out[7] = CAFA_NATIVE_VARIANT_FM_OPERATOR;
        else if ((screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2) && !eng)
            out[7] = CAFA_NATIVE_VARIANT_ANALOG;
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, page_head());
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, ENGINES[TSEL->eng_req % NENGINES]->name);
        skiff_basic_cards(out, pg);
        skiff_special_cards(out, pg);
        if (screen < CAFA_SCREEN_EDIT1 || screen > CAFA_SCREEN_VOICE2)
            skiff_sheet_cards(out, pg);
        if (pg->graph == GR_FEEL) {
            static const char *const FL[4] = {"CHANCE", "RATCHET", "NUDGE", "TYPE"};
            const step_t *st = &TSEL->step[ui.cursor % NSTEP];
            for (i = 0; i < 4u; i++) {
                const char *unit = feel_text(v, st, i);
                skiff_card(out, i, FL[i], v, unit, 0u, 2u, 1u, 0u);
            }
        }
        /* Real page/window counts; hidden engine pages are not advertised. */
        for (i = 0; i < NPAGES; i++)
            if (page_group(i) == page_group(page) && page_visible(i)) {
                if (page_order(i) < page_order(page)) skiff_u14(out + 9u, cafa_native_wire_u14(out + 9u) + 1u);
                skiff_u14(out + 11u, cafa_native_wire_u14(out + 11u) + (i != page));
            }
        if (screen <= CAFA_SCREEN_LFO2 && (pg->graph == GR_ADSR || pg->graph == GR_LFO))
            at = skiff_stock_curve(out, at, pg);
        if (screen == CAFA_SCREEN_MOD) at = skiff_mod_content(out, at);
        if (screen >= CAFA_SCREEN_PRESETS && screen <= CAFA_SCREEN_PROJECT)
            at = skiff_save_content(out, at, screen);
        if (sheet_of(pg->graph)) skiff_u14(out + 20u, *sheet_of(pg->graph)->win);
        if (pg->graph == GR_MOD) {
            skiff_u14(out + 24u, mod_ui_route < MOD_SLOT0 ? mod_ui_route : mod_ui_route + 1u);
            skiff_u14(out + 28u, 12u);
        }
        if (pg->graph == GR_LANES || pg->graph == GR_LMIX || pg->graph == GR_DXSND)
            { skiff_u14(out + 24u, ui.lane % NLANE); skiff_u14(out + 28u, NLANE); }
        if (screen == CAFA_SCREEN_MIXER) {
            unsigned first = skiff_track_window(out);
            char names[SKIFF_VIEW_TRACKS][20];
            for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
                unsigned k = first + i;
                sound_name(&trk[k], names[i]);
                labels[i] = names[i][0] ? names[i] : "--";
                values[i] = ENGINES[trk[k].eng_req % NENGINES]->name;
            }
            at = skiff_rows(out, at, SKIFF_VIEW_TRACKS, labels, values, song.sel - first);
            for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
                unsigned k = first + i;
                uint8_t *r = out + at - SKIFF_VIEW_TRACKS * 48u + i * 48u;
                r[40] = (uint8_t)((k == song.sel ? 1u : 0u) |
                                  (midi_mark(k) ? 2u : 0u) |
                                  (trk[k].p[P_MUTE] ? 4u : 0u));
                skiff_u14(r + 41u, (uint32_t)clamp(trk[k].p[P_LEVEL], 0, 127) * 16383u / 127u);
                skiff_u14(r + 43u, (uint32_t)(clamp(trk[k].p[P_PAN], -64, 63) + 64) * 16383u / 127u);
            }
            {
                unsigned p = skiff_sec(out, at, CAFA_NATIVE_CELLS, 2u + 7u * SKIFF_VIEW_TRACKS);
                if (p) {
                    skiff_u14(out + p, SKIFF_VIEW_TRACKS);
                    for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
                        unsigned k = first + i;
                        uint8_t *cell = out + p + 2u + 7u * i;
                        skiff_u14(cell, (uint32_t)clamp(trk[k].p[P_MUTE] ? 0 : meter_px(trk[k].peak) * 16383 / (TS_MH - 2), 0, 16383));
                        cell[2] = (uint8_t)((song.rec >> k & 1u ? 1u : 0u) |
                                            (song.rec >> k & 1u && song.playing ? 4u : 0u));
                        skiff_text(cell + 3u, 4u, "");
                    }
                    at = p + 2u + 7u * SKIFF_VIEW_TRACKS;
                }
            }
        }
        if (screen >= CAFA_SCREEN_EDIT1 && screen <= CAFA_SCREEN_VOICE2)
            at = skiff_engine_detail(out, at, screen);
        if (screen >= CAFA_SCREEN_FX && screen <= CAFA_SCREEN_FOLLOW ||
            screen >= CAFA_SCREEN_DELAY && screen <= CAFA_SCREEN_GLOBAL)
            at = skiff_fx_detail(out, at, screen);
        if (screen >= CAFA_SCREEN_ARP && screen <= CAFA_SCREEN_SONG)
            at = skiff_sequence_detail(out, at, screen);
    } else if (screen == CAFA_SCREEN_HOME || screen == CAFA_SCREEN_SCOPE) {
        project_cur_name(title);
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, title);
        for (i = 0; i < 4u; i++) {
            int16_t *vp;
            const param_desc_t *d = home_param(i, &vp);
            skiff_param_card(out, i, 0, d, *vp, 1u);
        }
        if (screen == CAFA_SCREEN_HOME) {
            unsigned first = skiff_track_window(out);
            at = skiff_home_rows(out, at, first);
            at = skiff_home_steps(out, at, first);
        } else at = skiff_fx_detail(out, at, screen);
    } else if (screen == 72u) {
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, "MENU");
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, MTAB_NAME[menu_tab()]);
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "PRESETS: ROW  ALGO: TAB");
        at = skiff_menu_rows(out, at);
    } else if (screen >= CAFA_SCREEN_MENU_DISPLAY && screen <= CAFA_SCREEN_ABOUT ||
               screen == CAFA_SCREEN_CALIBRATION || screen == CAFA_SCREEN_RESET_SETTINGS) {
        out[1] = 8u; skiff_text(out + CAFA_NATIVE_TITLE, 24u, "DEVICE MENU");
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u,
                   screen == CAFA_SCREEN_ABOUT ? "ABOUT" :
                   screen == CAFA_SCREEN_CALIBRATION ? "CALIBRATION" : "RESET SETTINGS");
        at = CAFA_NATIVE_HEADER; /* Device menu owns its contents and cursor. */
    } else if (screen == CAFA_SCREEN_NAME) {
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, "NAME");
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u,
                   nm.kind == NK_USER_SAVE || nm.kind == NK_USER_RENAME ? "USER SOUND" : "PROJECT");
        skiff_u14(out + 20u, nm.cur);
        labels[0] = nm.s; labels[1] = nm.ph;
        at = skiff_lines(out, at, 2u, labels);
        labels[0] = "NAME"; values[0] = nm.num ? "123" : "ABC";
        at = skiff_rows(out, at, 1u, labels, values, 0u);
        if (at > CAFA_NATIVE_HEADER) {
            uint8_t *r = out + at - 48u;
            skiff_u14(r + 41u, nm.num);
            skiff_u14(r + 43u, nm.key);
            skiff_u14(r + 45u, nm.tap);
        }
    } else if (screen == CAFA_SCREEN_CONFIRM) {
        char q[24], detail[40];
        confirm_text(q, detail);
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, q);
        skiff_u14(out + 20u, ui.confirm);
        labels[0] = q; labels[1] = detail;
        labels[2] = str_len(detail) > 24u ? detail + 24u : "";
        labels[3] = "OCT- BACK"; labels[4] = "OCT+ CONFIRM";
        at = skiff_lines(out, at, 5u, labels);
    } else if (screen == CAFA_SCREEN_COUNT_IN) {
        unsigned beat = cin_total - cin_left, p;
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, "COUNT IN");
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, cin_total > 4u ? "2 BARS" : "1 BAR");
        skiff_u14(out + 20u, beat);
        p = skiff_sec(out, at, CAFA_NATIVE_CELLS, 2u + 4u * 7u);
        if (p) {
            skiff_u14(out + p, 4u);
            for (i = 0; i < 4u; i++) {
                uint8_t *q = out + p + 2u + i * 7u;
                char number[2] = {(char)('4' - i), 0};
                q[2] = (uint8_t)((i == beat % 4u ? 2u : 0u) |
                                 (i < beat % 4u ? 16u : 1u));
                skiff_text(q + 3u, 4u, number);
            }
            at = p + 30u;
        }
        labels[0] = "RECORDING STARTS AFTER 1";
        labels[1] = "PLAY: STOP";
        at = skiff_lines(out, at, 2u, labels);
    } else if (screen == CAFA_SCREEN_SHIFT) {
        smap_t map[12];
        shift_map(map);
        if (!ui.home) {
            skiff_basic_cards(out, pg);
            skiff_special_cards(out, pg);
            skiff_sheet_cards(out, pg);
        }
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, "SHIFT");
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, ui.home ? "HOME" : page_head());
        for (i = 0; i < 12u; i++) { labels[i] = map[i].key; values[i] = map[i].job; }
        at = skiff_rows(out, at, 12u, labels, values, 16u);
        for (i = 0; i < 12u; i++)
            out[at - 12u * 48u + i * 48u + 40u] = map[i].kind;
    } else if (screen == CAFA_SCREEN_UPDATE) {
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, "UPDATE");
        skiff_u14(out + 20u, ui.uboot);
        labels[0] = "UPDATE MODE"; at = skiff_lines(out, at, 1u, labels);
    } else if (screen >= CAFA_SCREEN_FX_LAYER && screen <= CAFA_SCREEN_ARP_LAYER) {
        if (screen == CAFA_SCREEN_ARP_LAYER) {
            str_cpy(title, "PERFORM ", sizeof title);
            str_cpy(title + 8, LAUNCH_NAME[launch_mode()], sizeof title - 8u);
            skiff_text(out + CAFA_NATIVE_TITLE, 24u, title);
        } else
            skiff_text(out + CAFA_NATIVE_TITLE, 24u, layer_head());
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, layer_locked() ? "LOCKED" : "HELD");
        at = skiff_layer_view(out, at, screen);
    } else if (screen == CAFA_SCREEN_TAKE_PICKER) {
        skiff_text(out + CAFA_NATIVE_TITLE, 24u, "FREE TAKE");
        fmt_int(v, fr.bpm[fr.ci]); skiff_card(out, 0u, "GUESS", v, "BPM", 0u, 1u, 1u, 0u);
        fmt_int(v, FR_BARS[fr.bi]); skiff_card(out, 1u, "BARS", v, "", 0u, 2u, 1u, 0u);
        fmt_int(v, fr.start + 1u); skiff_card(out, 2u, "FROM", v, "", 0u, 1u, 1u, 0u);
        skiff_card(out, 3u, "TEMPO", fr.set ? "SET" : "KEEP", "", 0u, 2u, 1u, 0u);
        labels[0] = "OCT+ KEEP  OCT- BACK";
        at = skiff_lines(out, at, 1u, labels);
        at = skiff_steps(out, at, screen);
        skiff_u14(out + 20u, fr.start);
    }
    if (screen == CAFA_SCREEN_HOME && !ui_hints_off && large_kind() != LK_TALL)
#if BOARD_SMK37
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "HOLD [KNOB BANK] SHIFT");
#else
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "HOLD [HOME] SHIFT");
#endif
    else if (screen <= CAFA_SCREEN_SONG && act_cols()) {
        char a[24], b[16], hint[32];
        foot_hint(a, b);
        str_cpy(hint, a, sizeof hint);
        if (str_len(hint) + str_len(b) + 2u < sizeof hint) {
            str_cpy(hint + str_len(hint), "  ", sizeof hint - str_len(hint));
            str_cpy(hint + str_len(hint), b, sizeof hint - str_len(hint));
        }
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, hint);
    }
    if (ui.msg_t) skiff_text(out + CAFA_NATIVE_FOOTER, 31u, ui.msg);
    else if (screen == CAFA_SCREEN_SHIFT)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "LET GO:MENU");
    else if (screen == CAFA_SCREEN_FX_LAYER)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, !perf_latch_on && mom_held(1) ?
                   ret_mode() == 1u ? "LET GO:BACK 1 BEAT OCT+:KEEP" :
                   ret_mode() == 2u ? "LET GO:BACK 1 BAR OCT+:KEEP" : "LET GO:BACK OCT+:KEEP" :
                   perf_latch_on ? "WHITE:ON/OFF BLACK:MUTE OCT-:OFF" :
                   "WHITE FX BLACK MUTE K1-4 MACROS");
    else if (screen == CAFA_SCREEN_GLO_LAYER)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, ui.lock == LAYER_GLO ?
                   "OCT-:UNDO GLO:DONE" : "PLAY:RESTART OCT-:UNDO GLO:DONE");
    else if (screen == CAFA_SCREEN_SEL_LAYER)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "KEYS:ROOT OCT-:UNDO SEL:DONE");
    else if (screen == CAFA_SCREEN_EDIT_LAYER)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "KEYS:ENGINE OCT-:UNDO EDIT:DONE");
    else if (screen == CAFA_SCREEN_SEQ_LAYER)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, eu.on ?
                   "OCT+:APPLY OCT-:CANCEL" : "KEYS:TOOLS OCT-:UNDO SEQ:DONE");
    else if (screen == CAFA_SCREEN_REC_LAYER)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, "KEYS:REC OCT-:UNDO REC:DONE");
    else if (screen == CAFA_SCREEN_ARP_LAYER)
        skiff_text(out + CAFA_NATIVE_FOOTER, 31u, (thr_out >> (4u * song.sel) & 15u) ?
                   ret_mode() == 1u ? "LET GO: BACK 1 BEAT  OCT+: KEEP" :
                   ret_mode() == 2u ? "LET GO: BACK 1 BAR  OCT+: KEEP" : "LET GO: BACK  OCT+: KEEP" :
                   perf_scenes() ? "WHITE: SCENE  SAVE+WHITE: STORE" : "WHITE: PATTERN  BLACK: MUTE");
    at = skiff_transient_box(out, at, screen);
    if (!(out[1] & 8u)) at = skiff_prefs(out, at);
    at = skiff_chrome(out, at, screen);
    return cafa_native_view_valid(out, at) ? at : 0u;
}
