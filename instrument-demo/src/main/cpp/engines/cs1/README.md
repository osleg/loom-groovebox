# CS-1 voice in FM-1 Instrument

Two voice layers follow the CS-1 design by twochannels, as vendored by FM1-Dock at `41ce801428f8b97793406d46a29aa451ffd9ece0`. Each layer has a VCO, resonant high-pass and low-pass stages, and a post-filter sine. The filter code and tables from CS-1 carry GPL-3.0-only attribution, including their Felucca DSP origin. [Pinned CS-1 source](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/cs1)

The four original sounds use Skiff’s envelopes, performance pitch, pressure, modulation and effects. Layer II offers a footage control and a small detune. This voice port exposes a common filter and resonance control for both layers. The standalone per-layer envelopes, ribbon, ring modulator and CS-1 patch banks are outside the port.
