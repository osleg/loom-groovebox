/* SPDX-License-Identifier: GPL-3.0-only */
package org.fm1.companion.demo

import org.fm1.companion.protocol.Wire6

enum class AudioDestination(val title: String, val wireMode: Int) {
    PHONE("Phone", Wire6.PHONE),
    FM1("FM-1 · Android audio (shared)", Wire6.USB_RETURN),
    FM1_DIRECT("FM-1 · direct USB (synth only)", Wire6.USB_RETURN)
}
