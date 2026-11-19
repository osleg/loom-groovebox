# KEYS voice in FM-1 Instrument

The clavinet, electric piano and acoustic piano models are from Joakim Eriksson’s KEYS module at FM1-Dock commit `41ce801428f8b97793406d46a29aa451ffd9ece0`. The model parameters and names of the ten corresponding presets come from the same module’s explicitly GPL-3.0-only `keys_module.c`. The DSP headers and the Plaits MIT notice are preserved. [Pinned KEYS module](https://github.com/joakimeriksson/fm1-dock/tree/41ce801428f8b97793406d46a29aa451ffd9ece0/modules/keys)

Skiff provides note allocation, sequencer, pan, envelopes and effects. Each track supports up to eight modeled-key voices within Skiff’s global voice budget. Tone, decay, model and pickup or hardness are read at the next note. Level changes apply to sounding notes. These models tune their delays or resonators when a note starts, and use the Skiff mix in place of the donor’s separate soundboard and output effects.
