# FM-1 Instrument MACRO

MACRO uses the 24 portable synthesis models by Emilie Gillet from [the DSP vendored by GATOR in FM1-Dock](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/gator/gator/eurorack), pinned to commit `41ce801428f8b97793406d46a29aa451ffd9ece0`. The vendor pins [Eurorack](https://github.com/pichenettes/eurorack) at `08460a69a7e1f7a81c5a2abcc7189c9a6b7208d4` and [stmlib](https://github.com/pichenettes/stmlib) at `d18def816c51d1da0c108236928b2bbd25c17481`. The source retains Emilie Gillet’s MIT notices.

The original eight FM-1 Instrument model IDs and presets retain their order. The expansion adds virtual analog with VCF, phase distortion, three six-operator FM banks, wave terrain, string machine, chiptune, grain, wavetable, chord, speech, particle noise, kick, snare, and hi-hat. Each model has an FM-1 Instrument preset.

`gen_macro_assets.py` regenerates the 192 mathematical wave cycles used by wavetable and wave terrain, the 96 six-operator patches organized into keys, bells, and pads, and five banks of synthetic LPC phrases. The port uses the donor DSP’s phoneme coefficients and excitation table. The synthetic phrases replace the donor word banks extracted from TI ROMs. The port bundles no recorded waveforms, hardware patch dumps, hardware speech recordings, board support, or GATOR assets.

The adapter and Skiff bridge run the models at the instrument’s 44.1 kHz with four independently playable voices per track. Each voice has its own model, bounded scratch storage, and random state. FM-1 Instrument handles note allocation, amplitude envelopes, and effects. The OUT MIX control blends each model’s primary and auxiliary outputs. Six-operator FM renders both internal voices in the current block to support the instrument’s block sizes. The wavetable port sizes its lookup map for four banks and bounds interpolation at the edges.

The bridge, generator, and generated assets are GPL-3.0-only. See `thirdparty/` for both license texts. FM-1 Instrument is an independent instrument.
