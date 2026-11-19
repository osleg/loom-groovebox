# Prophet voice in FM-1 Instrument

The oscillators, Poly-Mod pitch math and two four-pole filter characters come from Kerem Kilic’s Melodee Prophet engine as vendored in FM1-Dock at `41ce801428f8b97793406d46a29aa451ffd9ece0`. The extracted signal code and coefficient tables carry the GPL-3.0-only notice. The host bridge applies Skiff’s envelopes, modulation, sequencer and effects to four original patches. [Pinned Melodee DSP in FM1-Dock](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/prophet/melodee)

This voice exposes pulse and saw, oscillator B triangle, detune, audio-rate Poly-Mod, sync and filter choice. The standalone Melodee patch editor, its full Rev-4 parameter map and Sequential hardware program bank are outside this port.
