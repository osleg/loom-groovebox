# ESQ

The ESQ engine adapts the DOC oscillators, four-pole resonant filter, and output resampler in [FM-1 ESQ at `7cae256074a0c3c24c3fab3d5df335fcb93ef419`](https://github.com/joakimeriksson/fm1-esq/tree/7cae256074a0c3c24c3fab3d5df335fcb93ef419). The adapted code and its donor tables and generators are GPL-3.0-only. The license is in `thirdparty/LICENSE-GPL-3.0.txt`.

The bridge gives each Skiff note its own three oscillators, filter and resampler. It uses Skiff's envelope, velocity, tuning, note allocation and modulation. The DOC runs at its native rate and uses 8-bit waves, 16-bit pitch registers, hard sync, and amplitude modulation. OSC 2 TUNE sets the second oscillator's semitone offset. The third oscillator plays an octave below the key. LAYERS controls the levels of oscillators 2 and 3 and responds to Skiff's SHAPE modulation. CUTOFF responds to FILTER modulation. The five presets were made for this engine.

All 32 selectable waves use the donor's computed ESQ-family stand-in tables. The 64 KiB table can be regenerated with the vendored script from `thirdparty` using `python3 -B gen_waves.py --stand-in -o esq_waves.h`. The build requires this stand-in header. Additional SQ-80 waves need their original sample data and are outside the selectable set. The programs here use original patch settings.
