# Loom CAFA v4 reconstruction

This branch reconstructs expanded controls after the previous local workspace
was lost. It starts from the published, user-tested protocol-v3 app. Intermediate
commits are checkpoints, not a matched hardware-qualified release.

## Implemented checkpoint

- Explicit protocol v4 negotiation, eight tracks, nine page types and octave
  state. v3 firmware is not silently treated as v4.
- Physical LFO, FX and ARP keys now select controls of the real Loom model/DSP.
  All six global LFOs and eighteen effect buses are addressable. Existing LFO
  routing, ARP rhythms, note details and unselected tracks remain unchanged.
- SELECT stays within the selected family; GLO + SELECT changes tempo.
  SEQ toggles between note grid and pattern controls. PRESETS scrolls its four
  16-step banks; on other pages it selects a drum/slice row where applicable.
- Holding a white step key edits pitch, velocity, gate and probability. A tap
  toggles on release. Holds consumed by editing never toggle on release; track,
  page, bank and disconnect changes cancel pending taps. Complex chords,
  microtiming, ratchets and punch are retained. P-locked steps are refused.
- PATTERN controls length, tempo, swing and humanize. Touchscreen changes are
  read into the next snapshot. Labels explicitly show any encoded-unit scale
  (OCT+3, INV+2, SWING+23, RATE x2, GATE x10).

## Deliberate limits at this checkpoint

REC is not enabled until complete recorded-step readback can preserve the phone
project. Preset/engine loading, sample recording, parameter-lock editing and
arrangement workflows remain on the phone. Patterns stay 4–64 steps.
A bitmap does not represent pitches, so no claim of full native piano-roll
parity is made. Matching firmware and final APK validation are still pending.

## Verification

First checkpoint: Kotlin app compilation and 18 surface tests passed; Java
protocol/session suite passed 17,083 assertions. Full baseline build of commit
648fe716 passed before changes. Final aggregate tests must be repeated.
No phone installation or firmware flashing was performed.
