# FMini-1 voice in FM-1 Instrument

This port uses Ruppert’s GPL-3.0-only FMini-1 oscillator, mixer, filter contour and calibrated ladder DSP. It includes the donor’s original 200 factory patches and generated filter tables. The Android runtime hosts the voice on each Skiff track and keeps patch selection in Skiff’s existing sound and project storage. [Pinned FMini-1 source and licensing](https://github.com/ruppert/fmini-1/tree/74375d3311889d8e28e6b2601d30f0df4faf70ab)

- `eng_fmini.c` adapts `firmware/src/eng_fmini.c` to Skiff’s mixer, voice allocation, modulation and storage.
- `fmini_filter.h` is the donor’s unmodified `firmware/src/eng_fmini_filter.h`.
- `fmini_presets.h` retains the donor’s oscillator settings, preset values and FX sends. It maps the donor’s categories to Skiff’s tags and omits the standalone tempo, transport, and rack settings.

Select a complete preset with PRESETS. EDIT’s BANK and PATCH controls access the 200 factory oscillator configurations and their modulation depth in two groups of 100. They can be combined with the current filter and envelope settings. Skiff provides the sequencer and effects. The donor’s separate patch editor and sequencer are outside this port.
