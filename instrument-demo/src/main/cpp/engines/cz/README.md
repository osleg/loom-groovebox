# CZ-1

The native DSP comes from [Melodee commit
165d177a425944f3d30b0b669255a6ed0050c3a5](https://github.com/keremimo/melodee/tree/165d177a425944f3d30b0b669255a6ed0050c3a5/firmware/src).
The donor's [licensing manifest](https://github.com/keremimo/melodee/blob/165d177a425944f3d30b0b669255a6ed0050c3a5/LICENSING.md)
credits Devin Acker's MAME uPD933 model and Kerem Kilic's C port.
The license texts are `thirdparty/LICENSE-BSD-3-Clause-uPD933.txt` and
`thirdparty/LICENSE-GPL-3.0.txt`.

| Local file | Provenance |
| --- | --- |
| `thirdparty/cz_voice.c` | BSD-3-Clause port from `firmware/src/cz_native.c`. Preserves the phase and window functions, clock-derived envelope rates, logarithmic amplitude, phase alternation, DC removal, and ring/noise synthesis. |
| `thirdparty/cz_voice.h` | New explicit voice state and render parameters for the BSD port. |
| `thirdparty/cz_sine.h` | New mathematical sine table sampled at 1024 equally spaced angles. |
| `eng_cz.c` | New GPL-3.0-only Skiff adapter and four original factory patches. |

The engine has eight Skiff sound parameters. It gives each voice up to two
native waveform lines and a DCW contour. Skiff supplies the amplitude envelope,
pitch bend, tuning, modulation, sequencing and effects. The native oscillator
math runs at 44.1 kHz. The Skiff adapter uses compact patches and the native
phase-distortion algorithms, with the CZ-1 bank, tone formats and firmware UI
outside the integration.
