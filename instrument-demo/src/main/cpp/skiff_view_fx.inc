/* Live stock Skiff FX, keyboard and scope data for the native panel. */
static unsigned skiff_fx_curve(uint8_t *out, unsigned at, unsigned count,
                               int32_t (*sample)(unsigned, unsigned, void *), void *state)
{
    unsigned p = skiff_sec(out, at, CAFA_NATIVE_CURVE, 2u + count * 4u), i;
    if (!p) return at;
    skiff_u14(out + p, count);
    for (i = 0; i < count; i++) {
        int32_t y = sample(i, count - 1u, state);
        skiff_u14(out + p + 2u + 4u * i, i * 16383u / (count - 1u));
        skiff_u14(out + p + 4u + 4u * i, (uint32_t)clamp(y, 0, 16383));
    }
    return p + 2u + count * 4u;
}

static int32_t skiff_fx_reverb(unsigned i, unsigned end, void *unused)
{
    int32_t hall = song.g[G_RTYPE] == 2, len = (40 + song.g[G_RSIZE] * 144 / 127) * (hall ? 2 : 1);
    int32_t k = 32768 - 98304 / len, e = 32768, x;
    int32_t amp = ui.page < NPAGES && PAGES[ui.page].graph == GR_FXREV ? TSEL->p[P_REV] : 127;
    (void)unused;
    for (x = 0; x < (int32_t)(i * 184u / end); x++) e = e * k >> 15;
    return (84 * amp / 127) * e / 32768 * 16383 / 84;
}

static int32_t skiff_fx_chorus(unsigned i, unsigned end, void *state)
{
    unsigned second = *(unsigned *)state;
    unsigned mode = (unsigned)song.g[G_CMODE];
    int32_t y, a = song.g[G_CDEPTH] * 44 / 127;
    uint32_t inc = (0xFFFFFFFFu / 216u) * (uint32_t)(1 + song.g[G_CRATE] * 5 / 127);
    if (mode == 1u || mode == 2u)
        return clamp(pg_cho_resp(mode, (int32_t)(i * 216u / end)) * 16383 / 65536, 0, 16383);
    if (second) a = a * song.g[G_CFDBK] / 127;
    y = 74 + sine_i((uint32_t)(i * 216u / end) * inc - (second ? 0x30000000u : 0u)) * a / 32768;
    return clamp((120 - y) * 16383 / 100, 0, 16383);
}

static int32_t skiff_fx_transfer(unsigned i, unsigned end, void *state)
{
    const track_t *t = TSEL;
    int32_t x = (int32_t)(i * 65535u / end) - 32768, y;
    if (*(unsigned *)state == 8u) {
        y = pg_drive_at((uint32_t)clamp(t->p[P_DTYPE], 0, DT_N - 1), t->p[P_DIST], x);
    } else {
        pg_curve_t k = {(uint32_t)clamp(t->p[P_ITYPE], 0, IT_N - 1), 0,
                        t->p[P_IA], t->p[P_IB], t->p[P_IC], ins_mixq(t->p[P_IMIX])};
        y = pg_ins_curve(&k, x);
    }
    return (clamp(y, -32768, 32767) + 32768) / 4;
}

static unsigned skiff_fx_wave(uint8_t *out, unsigned at, unsigned screen)
{
    const track_t *t = TSEL;
    int32_t w[88]; unsigned i, p;
    if (screen == 8u) {
        unsigned ty = (uint32_t)clamp(t->p[P_DTYPE], 0, DT_N - 1);
        pg_drive_wave(t, ty, t->p[P_DIST], w);
    } else {
        unsigned ty = (uint32_t)clamp(t->p[P_ITYPE], 0, IT_N - 1);
        pg_curve_t k = {ty, 0, t->p[P_IA], t->p[P_IB], t->p[P_IC], ins_mixq(t->p[P_IMIX])};
        int32_t lp1 = 0, lp2 = 0, h = 0, kq = ins_lpk(ty == IT_CRUSH ? k.c : k.b);
        unsigned hold = INS_HOLD[clamp(k.b, 0, 127) >> 3];
        for (i = 0; i < 176u; i++) {
            int32_t x = sine_i(i * 48806447u) * 3 / 4, y;
            if (ty >= IT_PHASER)
                y = pg_ins_curve(&k, (int32_t)((i % 44u) * 65536u / 44u) - 32768);
            else {
                if (ty != IT_CRUSH || !(i % hold)) h = pg_ins_curve(&k, x);
                lp1 += mulq16(h - lp1, (uint32_t)kq) * 2;
                lp2 += mulq16(lp1 - lp2, (uint32_t)kq) * 2;
                y = ty ? lp2 : x;
            }
            if (i >= 88u) w[i - 88u] = y;
        }
    }
    p = skiff_sec(out, at, CAFA_NATIVE_SERIES, 1u + 3u + 88u * 6u);
    if (!p) return at;
    out[p] = 1u; out[p + 1u] = 1u; out[p + 3u] = 88u;
    for (i = 0; i < 88u; i++) {
        unsigned q = p + 4u + 6u * i;
        skiff_u14(out + q, i * 16383u / 87u);
        skiff_u14(out + q + 2u, (uint32_t)clamp(8192 + w[i] * 8191 / 32768, 0, 16383));
    }
    return p + 4u + 88u * 6u;
}

static int32_t skiff_fx_insert_filter(unsigned i, unsigned end, void *unused)
{
    const track_t *t = TSEL;
    int32_t fc = clamp(t->p[P_IA], 0, 127) * 1000 / 127;
    int32_t res = clamp(t->p[P_IB], 0, 127), shape = clamp(t->p[P_IC], 0, 127);
    (void)unused;
    return flt_level(fc - (int32_t)(i * 1000u / end), res, shape) * 16383 / 1000;
}

static int32_t skiff_fx_scope(unsigned i, unsigned end, void *unused)
{
    unsigned at = (scope_w + i * (SCOPE_N - 1u) / end) & (SCOPE_N - 1u);
    (void)unused;
    return ((int32_t)scope_buf[at] + 32768) / 4;
}

typedef struct { uint32_t bar, rel, hit[NSTEP], count, depth; } skiff_duck_t;
static int32_t skiff_fx_duck(unsigned i, unsigned end, void *state)
{
    const skiff_duck_t *d = (const skiff_duck_t *)state;
    uint32_t tau = i * d->bar / end, j = d->count, dip = 0;
    while (j && d->hit[j - 1u] > tau) j--;
    if (d->count && d->rel) {
        uint32_t dt = j ? tau - d->hit[j - 1u] : tau + d->bar - d->hit[d->count - 1u];
        if (dt < d->rel)
            dip = 62u * d->depth * ((d->rel - dt) / (d->rel / 1024u + 1u)) / (127u * 1024u);
    }
    return (int32_t)((62u - dip) * 16383u / 62u);
}

static void skiff_fx_key(uint8_t *keys, unsigned note)
{
    if (note < 128u) keys[note / 7u] |= (uint8_t)(1u << (note % 7u));
}

static unsigned skiff_fx_keyboard(uint8_t *out, unsigned at, unsigned screen)
{
    unsigned p = skiff_sec(out, at, CAFA_NATIVE_KEYBOARD, 20u), i;
    const track_t *t = TSEL;
    unsigned mode = 0u, lo = 0u, by = 0u, ms = 0u, dir = 0u, source = NTRK;
    char caption[24] = "", line1[25] = "", line2[25] = "";
    if (!p) return at;
    if (screen == 12u) {
        uint32_t mask = scale_mask(t), root = (uint32_t)t->p[P_ROOT] % 12u;
        for (i = 60u; i < 72u; i++)
            if ((mask >> ((i + 12u - root) % 12u)) & 1u) skiff_fx_key(out + p, i);
        out[p + 19u] = (uint8_t)root;
    } else if (screen == 13u) {
        unsigned k = trk_index(t);
        uint8_t nn[CHORD_MAX]; uint32_t n = 0; int32_t root = t->p[P_ROOT]; uint16_t mask = 0;
        char name[12];
        if (!t->p[P_CHRD]) {
            str_cpy(line1, "CHORD KEYS OFF", sizeof line1);
            str_cpy(line2, "[K1] DIA3: IN-KEY CHORDS", sizeof line2);
        } else if (chord_kit(t)) {
            mode = 1u;
            str_cpy(line1, "NO CHORDS ON KITS", sizeof line1);
        } else {
            mode = 3u;
            if (chord_last[k].n) {
                n = chord_last[k].n; root = chord_last[k].root; mask = chord_last[k].mask;
                for (i = 0; i < n; i++) nn[i] = chord_last[k].note[i];
                str_cpy(caption, "LAST", sizeof caption);
            } else {
                n = chord_make(t, (uint32_t)(60 + root), nn, &root, &mask);
                str_cpy(caption, "ON ROOT", sizeof caption);
            }
            if (trk_vmode(t) != V_POLY && !t->p[P_AMODE])
                str_cpy(caption, "ROOT ONLY", sizeof caption);
            else if (t->p[P_STRUM] && trk_vmode(t) == V_POLY) {
                mode = 4u; ms = (unsigned)clamp(t->p[P_STRUM], 0, 100);
                dir = (unsigned)clamp(t->p[P_SDIR], 0, 2);
            }
        }
        for (i = 0; i < n; i++) skiff_fx_key(out + p, nn[i]);
        out[p + 19u] = (uint8_t)(root % 12);
        if (n) {
            lo = nn[0] - nn[0] % 12u;
            chord_name(name, (uint32_t)root, mask);
            skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, name);
        }
    } else {
        uint32_t src = (uint32_t)t->p[P_FOLLOW];
        if (!src || src > NTRK || src - 1u == trk_index(t)) {
            str_cpy(line1, "FOLLOW OFF", sizeof line1);
            str_cpy(line2, "[K1] A TRACK TO FOLLOW", sizeof line2);
        } else {
            source = src - 1u;
            str_cpy(caption, N_DKSRC[src], sizeof caption);
            if (chord_kit(&trk[source])) {
                mode = 1u;
                str_cpy(line1, caption, sizeof line1);
                str_cpy(line1 + str_len(line1), " PLAYS A KIT", sizeof line1 - str_len(line1));
                str_cpy(line2, "NO CHORDS TO FOLLOW", sizeof line2);
            } else if (!fol_src[source].pc) {
                mode = 2u;
                str_cpy(line1, caption, sizeof line1);
                str_cpy(line1 + str_len(line1), ": NO CHORD YET", sizeof line1 - str_len(line1));
                str_cpy(line2, "PLAYS AS WRITTEN", sizeof line2);
            }
        }
        if (source < NTRK && mode == 0u) {
            const fol_src_t *s = &fol_src[src - 1u];
            if (s->pc) {
                char name[12];
                mode = 3u;
                uint32_t relative = (s->pc >> s->root | (uint32_t)s->pc << (12u - s->root)) & 0xFFFu;
                for (i = s->lo; i <= s->hi && i < 128u; i++)
                    if ((s->pc >> (i % 12u)) & 1u) skiff_fx_key(out + p, i);
                by = t->p[P_FMODE] == FM_FIT || t->p[P_FNOTE] == FN_ROOT ? 128u + s->root :
                     t->p[P_FNOTE] == FN_LOW ? s->lo : t->p[P_FNOTE] == FN_HIGH ? s->hi : s->last;
                out[p + 19u] = (uint8_t)((by >= 128u ? by - 128u : by) % 12u);
                lo = s->lo - s->lo % 12u;
                if (relative == 1u) str_cpy(name, N_NOTE[s->root % 12u], sizeof name);
                else chord_name(name, s->root, (uint16_t)relative);
                skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, name);
                str_cpy(caption + str_len(caption), " ", sizeof caption - str_len(caption));
                str_cpy(caption + str_len(caption), N_FMODE[t->p[P_FMODE] % 3], sizeof caption - str_len(caption));
                if (t->p[P_FMODE] != FM_FIT) {
                    int32_t sh = fol_shift(t, s);
                    str_cpy(caption + str_len(caption), sh > 0 ? " +" : " ", sizeof caption - str_len(caption));
                    fmt_int(caption + str_len(caption), sh);
                }
            }
        }
    }
    at = p + 20u;
    if (screen == 13u || screen == 14u) {
        unsigned count = mode == 3u ? 1u : 2u;
        unsigned rp = skiff_sec(out, at, CAFA_NATIVE_ROWS, 1u + 48u * count);
        if (rp) {
            uint8_t *r = out + rp + 1u;
            out[rp] = (uint8_t)count;
            skiff_text(r, 16u, screen == 13u ? "CHORD VIEW V1" : "FOLLOW VIEW V1");
            skiff_text(r + 16u, 16u, caption);
            skiff_text(r + 32u, 8u, source < NTRK ? N_DKSRC[source + 1u] : "");
            r[40] = (uint8_t)(source < NTRK ? source + 1u : 0u);
            skiff_u14(r + 41u, mode); skiff_u14(r + 43u, lo); skiff_u14(r + 45u, by);
            if (count == 2u) {
                r += 48u;
                skiff_text(r, 40u, "");
                if (mode == 4u) {
                    skiff_text(r, 16u, "STRUM");
                    skiff_u14(r + 41u, ms); skiff_u14(r + 43u, dir);
                } else {
                    skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, line1);
                    skiff_text(r + 16u, 24u, line2);
                }
            }
            at = rp + 1u + 48u * count;
        }
    }
    return at;
}

static unsigned skiff_fx_detail(uint8_t *out, unsigned at, uint8_t screen)
{
    unsigned i, p, state = screen;
    if (screen == CAFA_SCREEN_GLOBAL) {
        p = skiff_sec(out, at, CAFA_NATIVE_CELLS, 2u + 16u * 7u);
        if (!p) return at;
        skiff_u14(out + p, 16u);
        for (i = 0; i < 16u; i++) {
            uint8_t *q = out + p + 2u + 7u * i;
            unsigned h = i % 4u == 0u ? 74u : i % 2u == 0u ? 52u : 40u;
            skiff_u14(q, h * 16383u / 76u);
            skiff_text(q + 3u, 4u, "");
        }
        {
            char sw[24];
            fmt_int(sw, 50 + song.g[G_SWING] * 50 / 250);
            str_cpy(sw + str_len(sw), ":", sizeof sw - str_len(sw));
            fmt_int(sw + str_len(sw), 50 - song.g[G_SWING] * 50 / 250);
            skiff_text(out + CAFA_NATIVE_SUBTITLE, 24u, sw);
        }
        at = p + 2u + 16u * 7u;
        p = skiff_sec(out, at, CAFA_NATIVE_MARKERS, 14u);
        if (p) {
            out[p] = 1u;
            skiff_u14(out + p + 1u, (uint32_t)clamp(song.g[G_SWING], 0, 250) * 16383u / 250u);
            skiff_text(out + p + 6u, 8u, "SWING");
            at = p + 14u;
        }
        return at;
    }
    if (screen == 12u || screen == 13u || screen == 14u)
        return skiff_fx_keyboard(out, at, screen);
    if (screen == 5u) {
        static const char *const names[4] = {"DRIVE", "CHORUS", "DELAY", "REVERB"};
        const char *values[4]; char numbers[4][12];
        for (i = 0; i < 4u; i++) {
            fmt_int(numbers[i], TSEL->p[P_DIST + i]);
            values[i] = numbers[i];
        }
        return skiff_rows(out, at, 4u, names, values, 16u);
    }
    if (screen == CAFA_SCREEN_SCOPE) {
        const char *labels[SKIFF_VIEW_TRACKS], *values[SKIFF_VIEW_TRACKS];
        char names[SKIFF_VIEW_TRACKS][8];
        unsigned first = skiff_track_window(out);
        for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
            unsigned k = first + i;
            fmt_int(names[i], (int32_t)k + 1); labels[i] = names[i];
            values[i] = ENGINES[trk[k].eng_req % NENGINES]->name;
        }
        at = skiff_rows(out, at, SKIFF_VIEW_TRACKS, labels, values, song.sel - first);
        for (i = 0; i < SKIFF_VIEW_TRACKS; i++) {
            const track_t *t = &trk[first + i];
            uint8_t *r = out + at - SKIFF_VIEW_TRACKS * 48u + i * 48u;
            int32_t lv = t->p[P_MUTE] ? 0 : meter_px(t->peak) * 16383 / (TS_MH - 2);
            skiff_u14(r + 41u, (uint32_t)clamp(lv, 0, 16383));
            if (t->p[P_MUTE]) r[40] |= 4u;
        }
        return skiff_fx_curve(out, at, 241u, skiff_fx_scope, 0);
    }
    if (screen == 10u) {
        uint32_t pat = sl_pattern(TSEL), mode = (uint32_t)TSEL->p[P_SLCR];
        int32_t open = 70 - TSEL->p[P_SLDEPTH] * 70 / 127;
        p = skiff_sec(out, at, CAFA_NATIVE_CELLS, 2u + 16u * 7u);
        if (!p) return at;
        skiff_u14(out + p, 16u);
        for (i = 0; i < 16u; i++) {
            uint8_t *q = out + p + 2u + 7u * i;
            skiff_u14(q, (uint32_t)clamp(open * 16383 / 70, 0, 16383));
            q[2] = (uint8_t)(((pat >> i) & 1u) | (mode == SL_OFF ? 64u : mode == SL_STUT ? 16u : 0u));
            skiff_text(q + 3u, 4u, "");
        }
        skiff_u14(out + 17u, 0u);
        skiff_u14(out + 22u, song.playing ? sl[TSEL - trk].idx : 16383u);
        return p + 2u + 16u * 7u;
    }
    if (screen == 11u) {
        uint32_t src = (uint32_t)TSEL->p[P_DKSRC], selected = trk_index(TSEL);
        skiff_duck_t d = {0};
        if (src && src <= NTRK && src - 1u != selected) {
            const track_t *s = &trk[src - 1u];
            uint32_t per = div_samples((uint32_t)s->p[P_SDIV]);
            uint32_t len = s->p[P_SLEN] > 0 ? (uint32_t)s->p[P_SLEN] : 1u;
            uint32_t nb;
            d.bar = 4u * beat_samples();
            d.rel = TIME_MS_X10[TSEL->p[P_DKREL] & 127] * 441u / 100u;
            d.depth = (uint32_t)clamp(TSEL->p[P_DKAMT], 0, 127);
            nb = per ? d.bar / per : 0u;
            for (i = 0; i < nb && i < NSTEP; i++) {
                uint32_t lanes = step_lanes(&s->step[i % len]);
                if ((drum_track(s) ? lanes & 1u : lanes) && d.count < 32u)
                    d.hit[d.count++] = i * per;
            }
        }
        if (!d.bar) d.bar = 1u;
        at = skiff_fx_curve(out, at, 113u, skiff_fx_duck, &d);
        if (!d.count) return at;
        p = skiff_sec(out, at, CAFA_NATIVE_MARKERS, 1u + 13u * d.count);
        if (!p) return at;
        out[p] = (uint8_t)d.count;
        for (i = 0; i < d.count; i++) {
            uint8_t *q = out + p + 1u + 13u * i;
            skiff_u14(q, d.hit[i] * 16383u / d.bar);
            skiff_u14(q + 2u, 16383u); q[4] = 1u;
            skiff_text(q + 5u, 8u, "HIT");
        }
        return p + 1u + 13u * d.count;
    }
    if (screen == 9u && TSEL->p[P_ITYPE] == IT_FILTER) {
        at = skiff_fx_curve(out, at, 65u, skiff_fx_insert_filter, 0);
        p = skiff_sec(out, at, CAFA_NATIVE_MARKERS, 14u);
        if (!p) return at;
        out[p] = 1u;
        skiff_u14(out + p + 1u, (uint32_t)clamp(TSEL->p[P_IA], 0, 127) * 16383u / 127u);
        skiff_u14(out + p + 3u, 16383u); out[p + 5u] = 1u;
        skiff_text(out + p + 6u, 8u, "CUT");
        return p + 14u;
    }
    if (screen == 8u || screen == 9u) {
        at = skiff_fx_curve(out, at, 65u, skiff_fx_transfer, &state);
        return skiff_fx_wave(out, at, screen);
    }
    if (screen == 6u || screen == 32u) {
        uint32_t tap = DLY_6TH[song.g[G_DTIME] % 13], win = tap * 4u > 96u ? tap * 4u : 96u;
        int32_t send = screen == 6u ? TSEL->p[P_DLY] : 127, level = 1000;
        unsigned count = 0, offset = 0;
        p = skiff_sec(out, at, CAFA_NATIVE_SERIES, 1u + 3u + 9u * 6u);
        if (!p) return at;
        out[p] = 1u; out[p + 1u] = 0u;
        for (i = 0; i < 9u && level >= 40; i++) {
            uint8_t *q = out + p + 4u + 6u * i;
            skiff_u14(q, (uint32_t)clamp((int32_t)(i * tap * 16383u / win), 0, 16383));
            skiff_u14(q + 2u, (uint32_t)clamp(level * 16383 / 1000, 0, 16383));
            level = i ? level * song.g[G_DFDBK] * 230 / 32768 : song.g[G_DMIX] * 1000 / 127 * send / 127;
            count++;
        }
        out[p + 3u] = (uint8_t)count;
        offset = p + 4u + 6u * count;
        skiff_u14(out + p - 2u, 4u + 6u * count);
        return offset;
    }
    if (screen == 7u || screen == 33u) {
        static const uint8_t early[5] = {84u, 60u, 72u, 46u, 52u};
        unsigned send = screen == 7u ? (unsigned)TSEL->p[P_REV] : 127u;
        at = skiff_fx_curve(out, at, 65u, skiff_fx_reverb, 0);
        p = skiff_sec(out, at, CAFA_NATIVE_SERIES, 1u + 3u + 5u * 6u);
        if (!p) return at;
        out[p] = 1u; out[p + 1u] = 1u; out[p + 3u] = 5u;
        for (i = 0; i < 5u; i++) {
            skiff_u14(out + p + 4u + 6u * i, i * 16383u / 4u);
            skiff_u14(out + p + 6u + 6u * i, early[i] * send * 16383u / (127u * 84u));
        }
        return p + 4u + 5u * 6u;
    }
    if (screen == 34u) {
        state = 0u; at = skiff_fx_curve(out, at, 73u, skiff_fx_chorus, &state);
        if (!song.g[G_CFDBK]) return at;
        p = skiff_sec(out, at, CAFA_NATIVE_SERIES, 1u + 3u + 73u * 6u);
        if (!p) return at;
        out[p] = 1u; out[p + 1u] = 1u; out[p + 3u] = 73u; state = 1u;
        for (i = 0; i < 73u; i++) {
            skiff_u14(out + p + 4u + 6u * i, i * 16383u / 72u);
            skiff_u14(out + p + 6u + 6u * i,
                       (uint32_t)skiff_fx_chorus(i, 72u, &state));
        }
        return p + 4u + 73u * 6u;
    }
    return at;
}
