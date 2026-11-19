# FM-106 voice in FM-1 Instrument

The FM-106 voice uses PatttF’s Juno-style DCO, ladder filter and high-pass circuitry from the FM1-Dock source at `41ce801428f8b97793406d46a29aa451ffd9ece0`. The copied DSP headers carry GPL-3.0-only SPDX notices. Four original FM-1 Instrument sounds use Skiff’s envelopes, chorus, sends and sequencer. [Pinned FM-106 DSP](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/fm106/fm106)

The module’s 128 Roland hardware patches and the donor’s separate oscillator, filter and VCA control application are outside this port. The DCO model, pulse-width behavior, filter and bass boost remain in the voice signal path. Default presets use Skiff’s stock chorus in place of the standalone FM-106 effects rack.
