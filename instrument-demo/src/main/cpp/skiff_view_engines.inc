/* Stock engine graphics, projected into native view sections. */
static unsigned skiff_engine_curve(uint8_t *b, unsigned at, const int32_t *v,
                                   unsigned n, int32_t lo, int32_t hi)
{
    unsigned p = skiff_sec(b, at, CAFA_NATIVE_CURVE, 2u + 4u * n), i;
    if (!p) return at;
    skiff_u14(b + p, n);
    for (i = 0; i < n; i++) {
        skiff_u14(b + p + 2u + 4u * i, i * 16383u / (n - 1u));
        skiff_u14(b + p + 4u + 4u * i,
                  (uint32_t)(clamp(v[i], lo, hi) - lo) * 16383u / (uint32_t)(hi - lo));
    }
    return p + 2u + 4u * n;
}

static unsigned skiff_engine_fm(uint8_t *b, unsigned at, uint8_t screen)
{
    static fm_sim_t s;
    const uint8_t *patch = fm6_patch[fm_tr()];
    uint8_t routes[6], *r;
    unsigned i, j, p, n = 0, alg = fm6_alg_of(TSEL), carrier = fm6_car_ops(alg);
    char labels[6][17], values[6][17];
    const char *lp[6], *vp[6];
    fm_sim(&s, TSEL);
    skiff_u14(b + 24u, fm_ui[0] % 6u); skiff_u14(b + 28u, 6u);
    for (i = 0; i < 6u; i++) {
        const uint8_t *op = patch + (5u - i) * FP_OP;
        labels[i][0] = 'O'; labels[i][1] = 'P'; labels[i][2] = (char)('1' + i); labels[i][3] = 0;
        fmt_int(values[i], op[FP_OL]); lp[i] = labels[i]; vp[i] = values[i];
    }
    at = skiff_rows(b, at, 6u, lp, vp, fm_ui[0] % 6u);
    p = at - (1u + 6u * 48u);
    for (i = 0; i < 6u; i++) {
        r = b + p + 1u + 48u * i;
        r[40] = (uint8_t)((i == fm_ui[0] % 6u ? 1u : 0u) |
                          (carrier >> i & 1u ? 2u : 0u) |
                          (s.live >> i & 1u ? 4u : 16u));
        skiff_u14(r + 41u, FM6_CELL[alg][i] & 7u);
        skiff_u14(r + 43u, FM6_CELL[alg][i] >> 4);
    }
    fm6_routes(alg, routes);
    for (i = 0; i < 6u; i++) for (j = 0; j < 6u; j++) n += routes[i] >> j & 1u;
    if (s.fb) n++;
    p = skiff_sec(b, at, CAFA_NATIVE_EDGES, 1u + 5u * n);
    if (p) {
        unsigned k = 0; b[p] = (uint8_t)n;
        for (i = 0; i < 6u; i++) for (j = 0; j < 6u; j++) if (routes[i] >> j & 1u) {
            uint8_t *e = b + p + 1u + 5u * k++;
            e[0] = (uint8_t)j; e[1] = (uint8_t)i; e[2] = 1u; skiff_u14(e + 3u, 16383u);
        }
        if (s.fb) {
            uint8_t *e = b + p + 1u + 5u * k;
            e[0] = e[1] = (uint8_t)fm6_fb_op(alg); e[2] = 1u;
            skiff_u14(e + 3u, s.fb * 16383u / 7u);
        }
        at = p + 1u + 5u * n;
    }
    n = 0;
    for (i = 0; i < 6u; i++) n += 3u + 6u * s.op[i].n;
    p = skiff_sec(b, at, CAFA_NATIVE_SERIES, 1u + n);
    if (p) {
        unsigned q = p + 1u; b[p] = 6u;
        for (i = 0; i < 6u; i++) {
            const fm_env_line_t *l = &s.op[i];
            b[q++] = (uint8_t)i;
            b[q++] = (uint8_t)((carrier >> i & 1u ? 2u : 0u) | (s.live >> i & 1u ? 4u : 0u));
            b[q++] = l->n;
            for (j = 0; j < l->n; j++) {
                skiff_u14(b + q, (uint32_t)clamp(l->x[j], 0, FM_XW - 1) * 16383u / (FM_XW - 1u));
                skiff_u14(b + q + 2u, (uint32_t)clamp(l->v[j] - (FM_TOP - FM_SPAN), 0, FM_SPAN) * 16383u / FM_SPAN);
                skiff_u14(b + q + 4u, 0u); q += 6u;
            }
        }
        at = q;
    }
    if (screen == CAFA_SCREEN_OPERATOR || screen == CAFA_SCREEN_ALL_OPS) {
        unsigned which = screen == CAFA_SCREEN_ALL_OPS, w = fm_ui[1u + which] % FM_NWIN, k;
        skiff_u14(b + 20u, w);
        for (k = 0; k < 4u; k++) {
            const fm_field_t *f = &FM_F[which][w][k];
            char value[16]; const char *unit; int32_t gauge;
            if (!f->label) continue;
            gauge = fm_val(f, fm_at(which, f), patch, TSEL, value, &unit);
            skiff_card(b, k, f->label, value, unit,
                       gauge < 0 ? 0u : (uint32_t)gauge * 16383u / 1000u, 1u, 1u, 0u);
        }
    }
    return at;
}

static unsigned skiff_engine_lanes(uint8_t *b, unsigned at, uint8_t screen)
{
    const char *labels[8], *values[8]; char v[8][16];
    unsigned i, p, lane = page_lane() % 8u;
    for (i = 0; i < 8u; i++) {
        labels[i] = drum_lane_name(TSEL, i);
        fmt_int(v[i], TSEL->p[P_LN0 + i]); values[i] = v[i];
    }
    at = skiff_rows(b, at, 8u, labels, values, lane);
    p = at - (1u + 8u * 48u);
    for (i = 0; i < 8u; i++) {
        uint8_t *r = b + p + 1u + i * 48u;
        skiff_u14(r + 41u, (uint32_t)clamp(TSEL->p[P_LN0 + i], 0, 127) * 16383u / 127u);
        if (screen == CAFA_SCREEN_LANE_MIX)
            skiff_u14(r + 43u, (uint32_t)(clamp(TSEL->p[P_LPAN0 + i], -64, 63) + 64) * 16383u / 127u);
    }
    skiff_u14(b + 24u, lane); skiff_u14(b + 28u, 8u);
    return at;
}

static unsigned skiff_engine_digital(uint8_t *b, unsigned at)
{
    static const uint8_t cells[8][4] = {
        {0x00, 0x10, 0x20, 0x30}, {0x01, 0x11, 0x20, 0x22},
        {0x01, 0x10, 0x20, 0x12}, {0x01, 0x12, 0x10, 0x20},
        {0x00, 0x10, 0x02, 0x12}, {0x00, 0x02, 0x04, 0x12},
        {0x00, 0x02, 0x04, 0x14}, {0x00, 0x02, 0x04, 0x06}
    };
    static const uint16_t routes[8] = {0x0842, 0x00C2, 0x004A, 0x0806,
                                        0x0802, 0x0888, 0x0800, 0x0000};
    const char *labels[4], *values[4]; char names[4][8], levels[4][12];
    unsigned i, j, n = 0, alg = (uint32_t)TSEL->p[P_E0] & 7u, p;
    uint32_t mods = routes[alg];
    for (i = 0; i < 4u; i++) {
        names[i][0] = 'O'; names[i][1] = 'P'; names[i][2] = (char)('1' + i); names[i][3] = 0;
        fmt_int(levels[i], TSEL->p[P_FM1_LEVEL + i * 5u]);
        labels[i] = names[i]; values[i] = levels[i];
    }
    at = skiff_rows(b, at, 4u, labels, values, 16u);
    p = at - (1u + 4u * 48u);
    for (i = 0; i < 4u; i++) {
        uint8_t *r = b + p + 1u + i * 48u;
        r[40] = (uint8_t)((cells[alg][i] < 0x10u ? 2u : 0u) |
                          (TSEL->p[P_FM1_LEVEL + i * 5u] ? 4u : 16u));
        skiff_u14(r + 41u, cells[alg][i] & 7u);
        skiff_u14(r + 43u, cells[alg][i] >> 4);
    }
    for (i = 0; i < 4u; i++) for (j = 0; j < 4u; j++) n += mods >> (4u * i + j) & 1u;
    n++;
    p = skiff_sec(b, at, CAFA_NATIVE_EDGES, 1u + 5u * n);
    if (p) {
        unsigned k = 0; b[p] = (uint8_t)n;
        for (i = 0; i < 4u; i++) for (j = 0; j < 4u; j++) if (mods >> (4u * i + j) & 1u) {
            uint8_t *e = b + p + 1u + 5u * k++;
            e[0] = (uint8_t)j; e[1] = (uint8_t)i; e[2] = 1u;
            skiff_u14(e + 3u, (uint32_t)clamp(TSEL->p[P_E4], 0, 127) * 16383u / 127u);
        }
        b[p + 1u + 5u * k] = b[p + 2u + 5u * k] = 3u;
        b[p + 3u + 5u * k] = 1u;
        skiff_u14(b + p + 4u + 5u * k, (uint32_t)clamp(TSEL->p[P_E6], 0, 127) * 16383u / 127u);
        at = p + 1u + 5u * n;
    }
    skiff_u14(b + 24u, 16383u); skiff_u14(b + 28u, 4u);
    return at;
}

static unsigned skiff_engine_dx(uint8_t *b, unsigned at)
{
    const dx_lane_t *lane = &dx_kit[dx_ui_lane & 7u];
    unsigned i, p, w = dx_ui_win % DX_NWIN;
    p = skiff_sec(b, at, CAFA_NATIVE_CELLS, 2u + 8u * 7u);
    if (p) {
        skiff_u14(b + p, 8u);
        for (i = 0; i < 8u; i++) {
            uint8_t *q = b + p + 2u + i * 7u;
            q[2] = (uint8_t)(i == (dx_ui_lane & 7u) ? 2u : 0u);
            skiff_text(q + 3u, 4u, DX_LANE_ABBR[i]);
        }
        at = p + 2u + 8u * 7u;
    }
    p = skiff_sec(b, at, CAFA_NATIVE_ROWS, 1u + 7u * 48u);
    if (p) {
        b[p] = 7u;
        for (i = 0; i < 7u; i++) {
            uint8_t *r = b + p + 1u + i * 48u;
            char a[16], z[16];
            if (i < DXP_N) {
                skiff_text(r, 16u, DX_VAL[i]);
                fmt_int(a, lane->a[i]); fmt_int(z, lane->b[i]);
                skiff_text(r + 16u, 16u, a); skiff_text(r + 32u, 8u, z);
                skiff_u14(r + 41u, lane->a[i] * 16383u / 127u);
                skiff_u14(r + 43u, lane->b[i] * 16383u / 127u);
            } else if (i == 5u) {
                skiff_text(r, 16u, "LEVEL");
                fmt_int(a, TSEL->p[P_LN0 + (dx_ui_lane & 7u)]);
                skiff_text(r + 16u, 16u, a);
                skiff_text(r + 32u, 8u, lane->mode & DX_DRIVE ? "DRIVE ON" : "DRIVE OFF");
                skiff_u14(r + 41u, (uint32_t)clamp(TSEL->p[P_LN0 + (dx_ui_lane & 7u)], 0, 127) * 16383u / 127u);
            } else {
                skiff_text(r, 16u, "MODE");
                skiff_text(r + 16u, 16u, DX_WAVE[lane->mode & 3u]);
                skiff_text(r + 32u, 8u, DX_FILT[((lane->mode >> 2) & 3u) % 3u]);
            }
            r[40] = (uint8_t)(i >= DX_ROW0[w] && i < DX_ROW0[w] + DX_NROW[w] ? 1u : 0u);
        }
        at = p + 1u + 7u * 48u;
    }
    skiff_u14(b + 20u, w); skiff_u14(b + 24u, DX_ROW0[w]);
    for (i = 0; i < 4u; i++) {
        uint32_t kind = DX_WIN[w][i][0], id = DX_WIN[w][i][1], mode = lane->mode;
        char label[16], value[16];
        if (kind == DXK_A || kind == DXK_B) {
            uint32_t v = kind == DXK_A ? lane->a[id] : lane->b[id];
            str_cpy(label, DX_VAL[id], sizeof label);
            str_cpy(label + str_len(label), kind == DXK_A ? " A" : " B", sizeof label - str_len(label));
            fmt_int(value, v); skiff_card(b, i, label, value, "", v * 16383u / 127u, 1u, 1u, 0u);
        } else if (kind == DXK_LEVEL) skiff_param_card(b, i, "LEVEL", &TP[P_LN0 + (dx_ui_lane & 7u)], TSEL->p[P_LN0 + (dx_ui_lane & 7u)], 1u);
        else if (kind == DXK_DRV) skiff_card(b, i, "DRIVE", mode & DX_DRIVE ? "ON" : "OFF", "", 0u, 3u, 1u, 0u);
        else if (kind == DXK_WAVE) skiff_card(b, i, "WAVE", DX_WAVE[mode & 3u], "", 0u, 2u, 1u, 0u);
        else if (kind == DXK_FILT) skiff_card(b, i, "FILTER", DX_FILT[((mode >> 2) & 3u) % 3u], "", 0u, 2u, 1u, 0u);
        else if (kind == DXK_SNAP) skiff_card(b, i, "SNAP", mode & 0x10u ? "ON" : "OFF", "", 0u, 3u, 1u, 0u);
        else skiff_card(b, i, "P SHAPE", DX_PMOD[(mode >> 5) & 3u], "", 0u, 2u, 1u, 0u);
    }
    return at;
}

static unsigned skiff_engine_sample(uint8_t *b, unsigned at, uint8_t screen)
{
    unsigned i, p, count = SAMPLE_WAVE_COLS;
    if (screen == CAFA_SCREEN_SLICES) {
#if FELUCCA_SLICE
        uint32_t a, end, len, n = slice_count(), selected = slice_sel();
        const slc_src_t *src; uint32_t source, div;
        src = slice_src(&source, &div);
        if (!src || !n) return at;
        slice_view(&a, &end, &len); slice_env(a, end);
        count = 108u;
        p = skiff_sec(b, at, CAFA_NATIVE_CURVE, 2u + 8u * count);
        if (p) {
            skiff_u14(b + p, count * 2u);
            for (i = 0; i < count; i++) {
                unsigned col = i * (SP_COLS - 1u) / (count - 1u), q = p + 2u + 8u * i;
                skiff_u14(b + q, i * 16383u / (count - 1u));
                skiff_u14(b + q + 2u, (uint32_t)clamp(8192 + (int32_t)sp.lo[col] * 128, 0, 16383));
                skiff_u14(b + q + 4u, i * 16383u / (count - 1u));
                skiff_u14(b + q + 6u, (uint32_t)clamp(8192 + (int32_t)sp.hi[col] * 128, 0, 16383));
            }
            at = p + 2u + 8u * count;
        }
        {
        unsigned count = n + 1u > 32u ? 32u : n + 1u;
        p = skiff_sec(b, at, CAFA_NATIVE_MARKERS, 1u + 13u * count);
        if (p) {
            b[p] = (uint8_t)count;
            for (i = 0; i < count; i++) {
                unsigned marker = i == count - 1u && selected >= count ? selected : i;
                uint8_t *q = b + p + 1u + 13u * i;
                skiff_u14(q, (uint32_t)clamp((int32_t)(((int64_t)slice_mark(marker) - a) * 16383 / (int64_t)(end - a)), 0, 16383));
                skiff_u14(q + 2u, (uint32_t)((uint64_t)slice_mark(marker) * 16383u / (len ? len : 1u)));
                q[4] = (uint8_t)(marker == selected ? 1u : 0u);
                skiff_text(q + 5u, 8u, marker == n ? "END" : "SLICE");
            }
            at = p + 1u + 13u * count;
        }
        }
        skiff_u14(b + 24u, selected); skiff_u14(b + 28u, slice_count() + 1u);
        {
            const char *labels[1] = {"LEN"}, *values[1];
            char duration[16];
            slice_time(duration, selected < n ? slice_mark(selected + 1u) - slice_mark(selected) : len - slice_mark(n));
            values[0] = duration;
            at = skiff_rows(b, at, 1u, labels, values, 0u);
            skiff_text(b + at - 48u + 32u, 8u, N_SLC_SRC[source]);
        }
#endif
        return at;
    }
    sample_wave_tick(TSEL);
    if (!sample_wave.zone || !sample_wave.zone->n) return at;
    p = skiff_sec(b, at, CAFA_NATIVE_CURVE, 2u + 8u * count);
    if (!p) return at;
    skiff_u14(b + p, count * 2u);
    for (i = 0; i < count; i++) {
        unsigned q = p + 2u + 8u * i, x = i * 16383u / (count - 1u);
        skiff_u14(b + q, x);
        skiff_u14(b + q + 2u, (uint32_t)clamp(8192 + sample_wave.lo[i] * 8191 / (sample_wave.peak ? sample_wave.peak : 1), 0, 16383));
        skiff_u14(b + q + 4u, x);
        skiff_u14(b + q + 6u, (uint32_t)clamp(8192 + sample_wave.hi[i] * 8191 / (sample_wave.peak ? sample_wave.peak : 1), 0, 16383));
    }
    at = p + 2u + 8u * count;
#if defined(ENGI_LOFI_SMP)
    if (TSEL->eng_req == ENGI_CLOUD) {
        p = skiff_sec(b, at, CAFA_NATIVE_MARKERS, 14u);
        if (!p) return at;
        uint32_t position = (uint32_t)clamp(TSEL->p[P_E1], 0, 127) * 16383u / 127u;
        b[p] = 1u;
        skiff_u14(b + p + 1u, position);
        skiff_u14(b + p + 3u, position);
        skiff_text(b + p + 6u, 8u, "POSITION");
        return p + 14u;
    }
    int whole = TSEL->eng_req == ENGI_LOFI_SMP && TSEL->p[P_E3] == 2;
#else
    int whole = 0;
#endif
    if (whole || (sample_wave.zone->looped && TSEL->p[P_E3])) {
        p = skiff_sec(b, at, CAFA_NATIVE_MARKERS, 27u);
        if (p) {
            b[p] = 2u;
            skiff_u14(b + p + 1u, whole ? 0u : (uint32_t)((uint64_t)sample_wave.zone->ls * 16383u / sample_wave.zone->n));
            skiff_u14(b + p + 14u, whole ? 16383u : (uint32_t)((uint64_t)sample_wave.zone->le * 16383u / sample_wave.zone->n));
            skiff_text(b + p + 6u, 8u, "LOOP"); skiff_text(b + p + 19u, 8u, "END");
            at = p + 27u;
        }
    }
    return at;
}

static unsigned skiff_engine_detail(uint8_t *out, unsigned at, uint8_t screen)
{
    const page_t *pg = &PAGES[screen];
    unsigned eng = TSEL->eng_req % NENGINES;
    if ((screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2) && eng == ENGI_FM6 ||
        screen == CAFA_SCREEN_OPERATOR || screen == CAFA_SCREEN_ALL_OPS)
        return skiff_engine_fm(out, at, screen);
    if ((screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2 || screen == CAFA_SCREEN_OP_LEVEL) &&
        eng == ENGI_DIGITAL)
        return skiff_engine_digital(out, at);
    if ((screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2) && eng == 0u) {
        int32_t v[73]; unsigned i;
        if (screen == CAFA_SCREEN_EDIT1) {
            uint32_t cyc = 0xFFFFFFFFu / 72u, wave = (uint32_t)TSEL->p[P_E0] % 5u;
            for (i = 0; i < 73u; i++) v[i] = 1000 + osc_shape(wave, i * cyc);
            at = skiff_engine_curve(out, at, v, 73u, 0, 2000);
            if (TSEL->p[P_E2] > 0) {
                unsigned p = skiff_sec(out, at, CAFA_NATIVE_SERIES, 1u + 3u + 73u * 6u);
                if (p) {
                    out[p] = 1u; out[p + 1u] = 1u; out[p + 3u] = 73u;
                    for (i = 0; i < 73u; i++) {
                        unsigned q = p + 4u + 6u * i;
                        uint32_t det = (uint32_t)clamp(TSEL->p[P_E1], 0, 127);
                        skiff_u14(out + q, i * 16383u / 72u);
                        skiff_u14(out + q + 2u, (uint32_t)clamp(8192 + osc_shape(wave, i * (cyc + cyc / 1000u * det)) * 8191 / 1000, 0, 16383));
                        skiff_u14(out + q + 4u, (uint32_t)clamp(TSEL->p[P_E2], 0, 127) * 16383u / 127u);
                    }
                    at = p + 4u + 73u * 6u;
                }
            }
            return at;
        }
        for (i = 0; i < 73u; i++)
            v[i] = flt_level(clamp(TSEL->p[P_E4], 0, 127) * 1000 / 127 - (int32_t)i * 1000 / 72,
                             clamp(TSEL->p[P_E5], 0, 127), 0);
        at = skiff_engine_curve(out, at, v, 73u, 0, 1000);
        {
            unsigned p = skiff_sec(out, at, CAFA_NATIVE_MARKERS, 14u);
            if (p) {
                out[p] = 1u; skiff_u14(out + p + 1u, (uint32_t)clamp(TSEL->p[P_E4], 0, 127) * 16383u / 127u);
                skiff_text(out + p + 6u, 8u, "CUTOFF");
                at = p + 14u;
            }
        }
        return at;
    }
    if (screen == CAFA_SCREEN_LANES || screen == CAFA_SCREEN_LANE_MIX)
        return skiff_engine_lanes(out, at, screen);
    if (screen == CAFA_SCREEN_SOUND) return skiff_engine_dx(out, at);
    if ((screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2) && ENGINES[eng] == &ENG_WHEEL) {
        static const char *const names[9] = {"16", "5.3", "8", "4", "2.7", "2", "1.6", "1.3", "1"};
        const char *values[9]; char v[9][4]; unsigned i;
        for (i = 0; i < 9u; i++) {
            unsigned level = TSEL->p[P_E4] && i == 8u ? 0u : (unsigned)drw_level(TSEL->p, i);
            fmt_int(v[i], level); values[i] = v[i];
        }
        at = skiff_rows(out, at, 9u, names, values, 0u);
        for (i = 0; i < 9u; i++) {
            uint8_t *r = out + at - 9u * 48u + 48u * i;
            skiff_u14(r + 41u, (uint32_t)(v[i][0] - '0') * 16383u / 8u);
        }
        skiff_text(out + at - 9u * 48u + 32u, 8u, "DRAWBARS");
        return at;
    }
    if (screen == CAFA_SCREEN_SLICES || screen == CAFA_SCREEN_SAMPLES ||
        ((screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2) && (eng == ENGI_SAMPLE
#if defined(ENGI_LOFI_SMP)
         || eng == ENGI_LOFI_SMP || eng == ENGI_CLOUD
#endif
        )))
        return skiff_engine_sample(out, at, screen == CAFA_SCREEN_EDIT1 || screen == CAFA_SCREEN_EDIT2 ? CAFA_SCREEN_SAMPLES : screen);
    if (screen >= CAFA_SCREEN_OP1_ENV && screen <= CAFA_SCREEN_OP4_ENV)
        return skiff_stock_curve(out, at, pg);
    if (screen == CAFA_SCREEN_VOICE2) {
        const char *labels[16], *values[16]; char v[16][4];
        unsigned i, uni = trk_vmode(TSEL) == V_UNISON;
        unsigned n = uni ? uni_nvoice(TSEL) : trk_nvoice(TSEL);
        unsigned rot = trk_vmode(TSEL) == V_POLY && !TSEL->p[P_ALLOC] && !TSEL->p[P_GLIDE];
        char caption[24];
        if (n > 16u) n = 16u;
        for (i = 0; i < n; i++) {
            fmt_int(v[i], i + 1u); labels[i] = v[i]; values[i] = "";
        }
        at = skiff_rows(out, at, n, labels, values, TSEL->rr % (n ? n : 1u));
        for (i = 0; i < n; i++) {
            uint8_t *r = out + at - (1u + n * 48u) + 1u + 48u * i;
            int32_t k = 2 * (int32_t)i - (int32_t)(uni ? n - 1u : 0u);
            if (uni && n > 1u && n < UNI_NV) k = k * (UNI_NV - 1) / (int32_t)(n - 1u);
            r[40] = (uint8_t)((rot && i == TSEL->rr % n ? 1u : 0u) | (TSEL->v[i].active ? 2u : 0u) | (uni ? 4u : 0u));
            skiff_u14(r + 41u, (uint32_t)clamp((95 + k * TSEL->p[P_DETUNE] * 40 / 889 - 55) * 16383 / 80, 0, 16383));
        }
        if (n) skiff_text(out + at - n * 48u + 32u, 8u, "DETUNE");
        if (rot && n) { str_cpy(caption, "NEXT NOTE: VOICE ", sizeof caption); fmt_int(caption + 17, TSEL->rr % n + 1u); }
        else str_cpy(caption, trk_vmode(TSEL) != V_POLY ? "ALLOC: ONLY IN POLY" : "NEXT NOTE: THE NEAREST", sizeof caption);
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, caption);
    } else if (screen == CAFA_SCREEN_VOICE) {
        int32_t v[55]; unsigned i, glide = (unsigned)clamp(TSEL->p[P_GLIDE], 0, 127);
        unsigned w = glide ? 6u + glide * 90u / 127u : 0u;
        for (i = 0; i < 55u; i++) {
            unsigned x = i * 216u / 54u;
            v[i] = x <= 88u ? 0 : x >= 88u + w ? 16383 : (int32_t)(x - 88u) * 16383 / w;
        }
        at = skiff_engine_curve(out, at, v, 55u, 0, 16383);
        {
            unsigned p = skiff_sec(out, at, CAFA_NATIVE_MARKERS, 14u);
            if (p) {
                out[p] = 1u; skiff_u14(out + p + 1u, w * 16383u / 96u);
                skiff_text(out + p + 6u, 8u, "GLIDE");
                at = p + 14u;
            }
        }
        skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u,
                   TSEL->p[P_VOICE] == V_POLY ? "POLY: A VOICE A NOTE" :
                   TSEL->p[P_VOICE] == V_MONO ? "MONO: NEW ATTACK" :
                   TSEL->p[P_VOICE] == V_LEGATO ? "LEG: NO NEW ATTACK" : "UNI: 8 VOICES");
    }
    return at;
}
