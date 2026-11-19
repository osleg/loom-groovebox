static void engine_fields_check(void)
{
    uint8_t b[3072]; uint32_t len;
    const uint8_t *rows, *cells;
    unsigned n;
    int16_t level = trk[0].p[P_LEVEL], pan = trk[0].p[P_PAN], mix = trk[0].p[P_E2];
    uint8_t record = song.rec, playing = song.playing;
    uint8_t eng = trk[0].eng_req;
    ui.home = 0; track_select(0u);
    trk[0].p[P_LEVEL] = 93; trk[0].p[P_PAN] = -21;
    ui.page = CAFA_SCREEN_MIXER;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] == 6u);
    {
        char name[20]; sound_name(&trk[0], name);
        assert(equals(rows + 1u, 16u, name[0] ? name : "--"));
        assert(cafa_native_wire_u14(rows + 1u + 41u) == 93u * 16383u / 127u);
        assert(cafa_native_wire_u14(rows + 1u + 43u) == 43u * 16383u / 127u);
        assert(cafa_native_wire_u14(rows + 1u + 45u) == 0u);
    }
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(cells && cafa_native_wire_u14(cells) == 6u);
    {
        unsigned k;
        for (k = 0; k < 128u; k++) {
            unsigned wire = k * 16383u / 127u;
            assert((wire * 127u + 8191u) / 16383u == k);
        }
    }
    song.rec = 1u; song.playing = 1u;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(rows && !(rows[1u + 40u] & 24u) && cells && (cells[4u] & 5u) == 5u);
    song.playing = 0u;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    cells = cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len);
    assert(rows && !(rows[1u + 40u] & 24u) && cells && (cells[4u] & 5u) == 1u);
    song.rec = 0u;
    ui.page = CAFA_SCREEN_GLOBAL;
    n = view(b);
    assert(cafa_native_section(b, n, CAFA_NATIVE_CELLS, &len));
    assert(cafa_native_section(b, n, CAFA_NATIVE_MARKERS, &len));
    trk[0].eng_req = ENGI_DIGITAL;
    ui.page = CAFA_SCREEN_OP_LEVEL;
    n = view(b); rows = cafa_native_section(b, n, CAFA_NATIVE_ROWS, &len);
    assert(rows && rows[0] == 4u);
    trk[0].eng_req = 0u; trk[0].p[P_E2] = 63;
    ui.page = CAFA_SCREEN_EDIT1;
    n = view(b);
    assert(cafa_native_section(b, n, CAFA_NATIVE_CURVE, &len));
    assert(cafa_native_section(b, n, CAFA_NATIVE_SERIES, &len));
    ui.page = CAFA_SCREEN_DRIVE;
    n = view(b); assert(cafa_native_section(b, n, CAFA_NATIVE_SERIES, &len));
    ui.page = CAFA_SCREEN_INSERT;
    n = view(b); assert(cafa_native_section(b, n, CAFA_NATIVE_SERIES, &len));
    trk[0].eng_req = eng; trk[0].p[P_LEVEL] = level; trk[0].p[P_PAN] = pan; trk[0].p[P_E2] = mix;
    song.rec = record; song.playing = playing;
}
