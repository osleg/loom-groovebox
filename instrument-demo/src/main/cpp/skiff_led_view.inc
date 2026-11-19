/* SPDX-License-Identifier: GPL-3.0-only */
/* Native panel payload: full/dim/breath/mid, in logical panel order. */
static unsigned skiff_append_led_view(uint8_t out[3072], unsigned length)
{
    const uint8_t *planes[] = {fm1_led, fm1_led_dim, fm1_led_breath, fm1_led_mid};
    if (length < 256 || length > 3040 || (out[1] & 8)) return length;
    uint8_t *p = out + length;
    *p++ = 10; *p++ = 29; *p++ = 0;
    for (unsigned i = 0; i < 4; i++) {
        uint32_t buttons = led_buttons(planes[i]);
        buttons |= ((planes[i][LED_PLAY_GREEN >> 3] >> (LED_PLAY_GREEN & 7)) & 1u) << 14;
        uint32_t keys = led_keys(planes[i]);
        for (unsigned k = 0; k < 3; k++) *p++ = (buttons >> (7 * k)) & 127u;
        for (unsigned k = 0; k < 4; k++) *p++ = (keys >> (7 * k)) & 127u;
    }
    *p = web_dim_level() ? 1 : 0;
    return length + 32;
}
