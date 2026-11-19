/* SPDX-License-Identifier: GPL-3.0-only
 * Use the release's own column formatter, including conditional action labels,
 * MIDI-learn targets, note ranges and named engine parameters. The adapter is
 * invoked with the same serialized runtime lock as physical input and audio. */
static uint8_t *stock_card_destination;
static uint8_t *stock_card_chrome;
static uint8_t stock_wire_icon(uint32_t icon, const char *label)
{
    if (icon == ICON_AUTO) icon = icon_for_label(label);
    switch (icon) {
    case ICON_PITCH: return CAFA_NATIVE_ICON_NOTE;
    case ICON_STEPS: return CAFA_NATIVE_ICON_STEP;
    case ICON_WAVE: return CAFA_NATIVE_ICON_WAVE;
    case ICON_ENV: return CAFA_NATIVE_ICON_ENV;
    case ICON_LFO_WAVE: return CAFA_NATIVE_ICON_LFO;
    case ICON_MOD: return CAFA_NATIVE_ICON_FX;
    case ICON_MIX: return CAFA_NATIVE_ICON_MIX;
    case ICON_SAVE: return CAFA_NATIVE_ICON_SAVE;
    case ICON_X_PLAY: return CAFA_NATIVE_ICON_PLAY;
    case ICON_X_REC: return CAFA_NATIVE_ICON_REC;
    case ICON_X_LOCK: return CAFA_NATIVE_ICON_LOCK;
    case ICON_CHIP: return CAFA_NATIVE_ICON_MENU;
    default: return CAFA_NATIVE_ICON_NONE;
    }
}
static void stock_card_output(uint32_t c,const char *label,const char *value,const char *unit,
                              uint32_t role,int32_t ratio,uint32_t icon)
{
    if (c >= 4u) return;
    uint8_t *q = stock_card_destination + CAFA_NATIVE_CONTROLS + 36u*c;
    skiff_text(q,12u,label); skiff_text(q+12u,14u,value);
    skiff_text(q+26u,4u,unit && !str_eq(label,unit) ? unit : "");
    skiff_u14(q+30u,ratio >= 0 ? (uint32_t)clamp(ratio,0,1000)*16383u/1000u : 0u);
    q[32]=label[0] || value[0] ? (ratio < 0 ? 2u : 1u) : 0u;
    if (!q[32]) { skiff_u14(q+30u,0); skiff_text(q+26u,4u,""); }
    q[33]=q[32] ? (uint8_t)((role != VR_DIM) | ((ui.hot_t && ui.hot_col==c) ? 2u : 0u)) : 0u;
    if (stock_card_chrome) {
        unsigned at = 53u + c / 2u, shift = 3u * (c % 2u);
        uint8_t named_role = role == VR_ACCENT ? 1u : role == VR_MID ? 2u : role == VR_THEME ? 3u : 0u;
        stock_card_chrome[at] = (uint8_t)((stock_card_chrome[at] & ~(3u << shift)) |
                                          (named_role << shift));
    }
    q[34]=q[32] ? stock_wire_icon(icon,label) : 0u;
    q[35]=0;
    fmt_named=card_mot_next=0;
    card_span.fn=0;
}

#define draw_act_column stock_action_output
#define draw_column stock_card_output
#define engine_columns stock_engine_outputs
#define fr_columns stock_take_outputs
#define draw_columns stock_format_columns
#include "skiff_stock_columns.inc"
#undef draw_columns
#undef fr_columns
#undef engine_columns
#undef draw_column
#undef draw_act_column

static void skiff_capture_cards(uint8_t *view, unsigned length)
{
    if (length < CAFA_NATIVE_HEADER || (view[6] > CAFA_SCREEN_SCOPE &&
        view[6] != CAFA_SCREEN_SHIFT && view[6] != CAFA_SCREEN_TAKE_PICKER)) return;
    /* Sheets format through stock function pointers into the physical LCD.
     * Their dedicated view providers already captured those controls. */
    if (!ui.home && !fr.on && sheet_of(cur_page()->graph)) return;
    stock_card_destination=view;
    stock_card_chrome=(uint8_t *)cafa_native_section(view,length,CAFA_NATIVE_CHROME,0);
    stock_format_columns();
    stock_card_chrome=0;
    stock_card_destination=0;
}
