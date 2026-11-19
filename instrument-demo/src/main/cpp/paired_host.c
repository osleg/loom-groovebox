/* Binary mailbox for the real stock runtime in paired-session-check. */
#define SKIFF_HOST_TEST 1
#include "skiff_runtime.c"
#include <stdio.h>

static uint32_t read32(void)
{
    uint8_t b[4];
    if (fread(b, 1, 4, stdin) != 4) _Exit(2);
    return b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24;
}
static void write32(uint32_t x)
{
    uint8_t b[4] = {(uint8_t)x, (uint8_t)(x >> 8), (uint8_t)(x >> 16), (uint8_t)(x >> 24)};
    fwrite(b, 1, 4, stdout);
}
/* Select stock state on the app side. The controller still sees only MIDI
 * frames produced by OpenSession, including for device-owned handoffs. */
static void select_view(unsigned screen)
{
    ui.home = ui.home_scope = ui.menu = ui.layer = ui.shift = 0;
    ui.confirm = ui.uboot = ui.take_t = nm.kind = fr.on = 0;
    cin_total = cin_left = skiff_device_screen = 0;
    if (screen < NPAGES) {
        ui.page = (uint8_t)screen;
        if (screen == CAFA_SCREEN_OPERATOR || screen == CAFA_SCREEN_ALL_OPS)
            set_engine(ENGI_FM6);
        else if (screen == CAFA_SCREEN_LANES || screen == CAFA_SCREEN_LANE_MIX)
            set_engine(ENGI_DRUM);
        else if (screen == CAFA_SCREEN_SOUND) set_engine(ENGI_DRUMX);
        else if (screen == CAFA_SCREEN_SAMPLES) set_engine(ENGI_SAMPLE);
        else if (screen == CAFA_SCREEN_SLICES) set_engine(ENGI_SLICE);
        else if (screen >= CAFA_SCREEN_OP1_ENV && screen <= CAFA_SCREEN_OP_LEVEL)
            set_engine(ENGI_DIGITAL);
        else set_engine(0u);
    } else if (screen <= CAFA_SCREEN_SCOPE) {
        ui.home = 1; ui.home_scope = screen == CAFA_SCREEN_SCOPE;
    } else if (screen <= CAFA_SCREEN_ARP_LAYER) {
        ui.layer = (uint8_t)(LAYER_FX + screen - CAFA_SCREEN_FX_LAYER);
    } else if (screen == CAFA_SCREEN_SHIFT) ui.shift = 1;
    else if (screen == CAFA_SCREEN_APP_MENU) ui.menu = 1;
    else if ((screen >= CAFA_SCREEN_MENU_DISPLAY && screen <= CAFA_SCREEN_ABOUT) ||
             screen == CAFA_SCREEN_CALIBRATION || screen == CAFA_SCREEN_RESET_SETTINGS)
        skiff_device_screen = (uint8_t)screen;
    else if (screen == CAFA_SCREEN_NAME) { nm.kind = NK_PROJ_SAVE; str_cpy(nm.s,"TEST SONG",sizeof nm.s); }
    else if (screen == CAFA_SCREEN_CONFIRM) ui.confirm = CF_CLEAR_SEQ;
    else if (screen == CAFA_SCREEN_TAKE_PICKER) fr.on = 1;
    else if (screen == CAFA_SCREEN_COUNT_IN) cin_total = cin_left = 4;
    else if (screen == CAFA_SCREEN_UPDATE) ui.uboot = 3;
    else if (screen == CAFA_SCREEN_TAKE || screen == CAFA_SCREEN_UNDO) {
        /* Stock shows these notices over the active page. */
        ui.home = 1; ui.take_t = 2; take_box.kind = screen == CAFA_SCREEN_UNDO;
        str_cpy(take_box.title,take_box.kind ? "UNDO" : "TAKE KEPT",sizeof take_box.title);
        str_cpy(take_box.line,"14 NOTES",sizeof take_box.line);
    } else _Exit(2);
}
int main(void)
{
    uint8_t view[3072];
    uint8_t cmd;
    skiff_boot(0, 0);
    while (fread(&cmd, 1, 1, stdin) == 1) {
        if (cmd == 'S') {
            unsigned n = skiff_make_view(view);
            skiff_capture_cards(view, n);
            n = skiff_append_led_view(view, n);
            write32(n); fwrite(view, 1, n, stdout);
        } else if (cmd == 'P') {
            select_view(read32());
        } else if (cmd == 'H') {
            uint32_t hash=2166136261u, y;
            ui.force=1; draw_graph(); ui.force=0;
            for(y=Y_GRAPH;y<Y_GRAPH+H_GRAPH;y++)
                hash=fnv(hash,web_fb+y*240u+28u,68u*sizeof *web_fb);
            write32(hash);
        } else if (cmd == '+') {
            int32_t turns[7]={0};turns[EN_PRESET]=1;
            skiff_input(0,0,turns);
        } else if (cmd == 'G') {
            skiff_master_gain((int32_t)read32());
            write32(song.master_q12);
        } else if (cmd == 'I') {
            uint32_t buttons = read32(), keys = read32();
            int32_t turns[7]; unsigned i;
            for (i = 0; i < 7; i++) turns[i] = (int32_t)read32();
            skiff_input(buttons, keys, turns);
            write32(fm1_in.notes);
        } else if (cmd == 'R') {
            int16_t out[2048];
            uint32_t n = read32(), count = 0, i;
            if (n > 1024u) _Exit(2);
            skiff_render(out, n);
            for (i = 0; i < 2u * n; i++) count += out[i] != 0;
            write32(count);
        } else if (cmd == 'Q') break;
        else _Exit(2);
        fflush(stdout);
    }
    return 0;
}
