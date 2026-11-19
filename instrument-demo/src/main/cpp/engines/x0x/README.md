# FM-1 Instrument 808 and 909

These are modified ports of Charles Vestal’s [X0X drum DSP](https://github.com/charlesvestal/fm1-x0x/tree/512c0daaf25f82c5b70e9eceee195864e7da378c/firmware/src/dsp) under GPL-3.0-only. The files in `thirdparty/` retain the X0X synthesis and shared math with adaptations for the Android instrument's mixer, note mapping, and controls. The 808 metal oscillator includes the Optimist changes credited in the original source. See [X0X’s licensing notes](https://github.com/charlesvestal/fm1-x0x/blob/512c0daaf25f82c5b70e9eceee195864e7da378c/LICENSING.md) for the full provenance.

- 8W8 by A Thousand Details - https://github.com/athousanddetails/schwung-8W8
- 9W9 by A Thousand Details - https://github.com/athousanddetails/schwung-9W9

8W8 and 9W9 copyright (C) 2026 A Thousand Details. These adaptations are independent of A Thousand Details. Their full notices and the GPL-3.0 license are in `thirdparty/`. The 909 circuits originate in [ER-99 by Matthew Cieplak](https://github.com/matthewcieplak/er-99). The 808 rim-shot provenance and MIT text are in `NOTICE-SC808.txt`.

Both engines synthesize their voices at 44.1 kHz. The 909 hats, ride, and crash use original inharmonic oscillators and band-filtered noise. The ride adds a pitched attack and the crash uses a broader group of modes. The numeric tanh table encodes the rounding of the X0X transfer function. The port contains no recorded audio or sampled kit assets.

Each track plays the eight native drum lanes. Its last lane defaults to the 808 cowbell or 909 ride. Cymbal and tom variants are available through General MIDI pitches. A closed hat chokes an open hat on the same track and releasing a key lets the hit ring. The phone’s drum sequencer and mixer control lane level, mute and pan.

Pitch, decay, transient shape and accent take effect on the next hit, including values locked to a step. Tone and drive also affect sounding hits. The six included kits are original FM-1 Instrument patches.
