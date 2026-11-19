# ARP 2600 and Eminent 310

The integer DSP comes from Joakim Eriksson's [FM1-Dock commit
41ce801428f8b97793406d46a29aa451ffd9ece0](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules).
See the donor's [licensing manifest](https://github.com/joakimeriksson/fm1-dock/blob/41ce801428f8b97793406d46a29aa451ffd9ece0/LICENSING.md)
and the GPL-3.0-only notices in the source files. The license text is in
`thirdparty/LICENSE-GPL-3.0.txt`.

| Local file | Donor path | Changes |
| --- | --- | --- |
| `thirdparty/arp_engine.c` | `modules/arp2600/arp_engine.c` | Initialize through `memset` for portable C aliasing. Rename the key-off symbol to avoid colliding with Skiff’s arpeggiator. The oscillator, filter and envelope routines are the donor's. |
| `thirdparty/arp_engine.h` | `modules/arp2600/arp_engine.h` | Declare the renamed key-off entry point. |
| `thirdparty/em_engine.c` | `modules/eminent/em_engine.c` | Expose the voice footages and shared effects passes. Each part owns one delay line. Scratch arrays are local. An ensemble depth of zero bypasses the delayed taps. |
| `thirdparty/em_engine.h` | `modules/eminent/em_engine.h` | Declare the separate render passes and store only the delay line the donor uses. |
| `eng_dock_analog.c` | | New GPL-3.0-only Skiff bridge and original presets. |

ARP2600 uses the donor's two PolyBLEP VCOs, 4072-style ladder, envelope,
suboscillator, ring modulator and oscillator sync. Each Skiff voice owns its DSP
state. Skiff supplies the notes, articulation, amplitude envelope and glide.
Skiff's shape modulation controls VCO 1 pulse width.

EMINENT renders the donor's 16′, 8′ and 4′ footages per Skiff voice. A single
formant filter, three-tap ensemble and phaser run on each part's voice sum.
The processed stereo signal folds into Skiff's mono part mix. The presets use
Skiff's stereo chorus and reverb sends, and the part retains Skiff's own
amplitude envelope, pan and effects.
