# FM-1 Instrument FiMba and MONK

The dry models are adapted from the portable sources in [FM1-Dock commit 41ce801428f8b97793406d46a29aa451ffd9ece0](https://github.com/joakimeriksson/fm1-dock/commit/41ce801428f8b97793406d46a29aa451ffd9ece0). The instrument supplies its own voice allocation, amplitude envelope, glide, MIDI and effects. The eight patch settings are original to FM-1 Instrument.

[FiMba-1](https://github.com/jadamsowers/fm1-fimba) supplies the GPL-3.0-only tine and body models from `modules/fimba/fimba/dsp/kalimba.c`. The port retains the material-dependent resonators, two close fundamental modes for beating, worn-tine variation, thumb and nail excitation, body radiation, sound-hole damping and buzz. FiMba-1's shared math comes from FoMni and X0X and is included under GPL-3.0-only.

[MONK-1](https://github.com/charlesvestal/fm1-monk) supplies the formant-grain model from `modules/monk/monk/dsp/monk.c`. Its synthesis is based on [MonkSynth](https://github.com/JonET/monksynth), copyright (c) 2026 Jonathan Taylor, MIT. The port retains the five interpolated vowels, head-size and breath shaping, pitch-period overlap-add and jittered vibrato. FM-1 Instrument supplies up to six separate notes, including polyphonic chords. The standard Skiff unison control shares this voice limit.

The license texts are in `thirdparty/`. Both voices synthesize at 44.1 kHz and the ports contain no recordings, donor presets, character art, arrangements or patterns.
