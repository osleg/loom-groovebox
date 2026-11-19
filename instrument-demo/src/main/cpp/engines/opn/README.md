# YM2612 and SN76489

The portable chip cores come from [OPN-1 commit
9651acd3b415ef64dfbf201bd23950c2f4a43393](https://github.com/putchdev/opn-1/tree/9651acd3b415ef64dfbf201bd23950c2f4a43393/firmware/src).
The donor's [licensing manifest](https://github.com/putchdev/opn-1/blob/9651acd3b415ef64dfbf201bd23950c2f4a43393/LICENSING.md)
identifies these four files as BSD-3-Clause.

| Local file | Donor path | Provenance and changes |
| --- | --- | --- |
| `thirdparty/opn2.c` | `firmware/src/opn2.c` | OPN-1's C port of Aaron Giles's ymfm. Scratch is local to each render, the sine lookup is immutable, and a mono channel-zero renderer reuses the chip's operators, envelopes, and DAC ladder. |
| `thirdparty/opn2.h` | `firmware/src/opn2.h` | OPN-1's ymfm port header, with a declaration for the mono renderer. |
| `thirdparty/psg.c` | `firmware/src/psg.c` | OPN-1's C port of the Sega VDP PSG path in MAME, credited to Nicola Salmoria and the MAME contributors. Unmodified. |
| `thirdparty/psg.h` | `firmware/src/psg.h` | OPN-1's Sega VDP PSG port header. Unmodified. |
| `eng_opn.c` | | Skiff adapter and original factory presets written for this integration, GPL-3.0-or-later. |

`thirdparty/LICENSE-BSD-3-Clause.txt` contains the notices for binary distributions.
The integration includes these cores and eight original patches.

YM2612 uses one four-operator channel per Skiff voice, with native clocking at
53,267 Hz and linear interpolation into Skiff's 44.1 kHz stream. Its editable
register controls cover algorithm, feedback, brightness, three operator
multipliers, attack and decay. The pitch tracks Skiff's bend, glide, tuning and
unison detune within the chip's FNUM and block range.

SN76489 uses the donor's Sega PSG variant. Each Skiff voice has independent tone
and noise counters. The source selects square, white noise, periodic noise, or a
tone and white noise mix. The auxiliary tone and pitch-clocked noise have
separate transposes. Bass notes below the native divider range use a lower
effective PSG clock. This also slows fixed-rate noise within a mixed voice.
Both engines use Skiff's voice allocation, amplitude envelope, and effects.
