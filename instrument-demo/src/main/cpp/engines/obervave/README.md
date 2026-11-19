# OberVave

The fixed-point voice DSP is by zvenson, copyright 2026, GPL-3.0-only. The
source comes from [FM1-Dock commit
41ce801428f8b97793406d46a29aa451ffd9ece0](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/obervave/obervave).
The module's [licensing manifest](https://github.com/joakimeriksson/fm1-dock/blob/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/obervave/obervave/LICENSING.md)
credits the synth and its generated DSP tables to zvenson. The original license
text is in `thirdparty/LICENSE-GPL-3.0.txt`.

| Local file | Path under the pinned `modules/obervave/obervave/` | Changes |
| --- | --- | --- |
| `thirdparty/obv_tables.h` | `obv_tables.h` | Unmodified donor tables. |
| `thirdparty/obv_dsp.h` | `obv_dsp.h` | Share oscillator and filter state declarations with the portable interface, bound PolyBLEP event positions, and replace a signed shift. |
| `thirdparty/obv_voice.c`, `thirdparty/obv_voice.h` | `obv_engine.h` | Extract one DSP voice with explicit state and render parameters. Widen the linear FM calculation before limiting its frequency. |
| `eng_obervave.c` | | New GPL-3.0-only Skiff bridge and four original presets. |

The bridge uses the donor's event-corrected saw and pulse oscillators, hard
sync, linear cross-modulation, overdrive and multimode state-variable filter.
The SEM filter morphs through low-pass, notch and high-pass. Each Skiff voice
owns its oscillator and filter state. Skiff supplies notes, glide, pitch
modulation, amplitude envelopes and the mixer. Its shape modulation controls
the first oscillator's pulse width. The preset values use the Skiff format.
