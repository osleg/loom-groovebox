# Synthex

The voice DSP comes from Joakim Eriksson's Scintilla, as vendored by [FM1-Dock at `41ce801428f8b97793406d46a29aa451ffd9ece0`](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/scintilla). The five DSP files are unchanged. Their GPL-3.0-only licensing and original attribution accompany them in `thirdparty`.

Each Skiff note has its own oscillator, ring modulation and filter state. The bridge gives Skiff control of pitch, velocity, envelopes, modulation and note allocation. WIDTH responds to Skiff's SHAPE modulation and CUTOFF responds to FILTER modulation. The voice exposes independent wave choices, oscillator 2 transposition, sync, ring, and five filter modes. The four presets use original patch settings. Scintilla's shared scratch buffer is reused inside the instrument's serialized audio callback.
