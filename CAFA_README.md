**v5 dynamic catalog proof of concept:** [implementation, hardware controls, validation and limits](docs/CAFA_DYNAMIC_CATALOG.md). Requires matching v5 firmware; this branch is not hardware-qualified.

<!-- SPDX-License-Identifier: GPL-3.0-only -->
# Loom CAFA: FM1 integration prototype 1

**v4 reconstruction branch:** see [current work and limits](docs/CAFA_V4.md).
The original v3 baseline documentation below is retained for provenance.

This is an experimental fork of **Loom Groovebox**, not the previous small CAFA
synth app. The phone runs Loom's existing synthesizers, sampler, effects and
sequencer. FM1 is the connected controller/display and selected USB audio output.

Upstream source: https://github.com/Co-oscillator/loom-groovebox

Pinned base: `9937566f052fcceeceba1e8b0004a70e1b557adc`.

Android identity: `com.groovebox.cafa`, **Loom CAFA**, version `3.2.19-cafa.1`.
It installs separately from ordinary Loom and from `dev.cafa`. It does not
import their projects automatically. Do not uninstall those apps to test this.

## What is implemented

- An explicit USB MIDI device and USB audio output picker in **FM1 LINK**.
- A dedicated, selected MIDI endpoint, isolated from Loom's ordinary MIDI
  broadcast, echo filter and pending-message queue.
- CAFA **protocol v3**, bound to a fresh session, with acknowledged state,
  input ordering, duplicate suppression and a 1.5-second fail-closed lease.
- All **eight Loom tracks** can be selected on FM1. Its ALGORITHM encoder
  chooses tracks 1–8; the original standalone firmware remains six-track.
- Physical note keys trigger/release the actual selected Loom engine. Releases
  retain the note-on track/pitch across track changes and octave changes.
- PLAY starts/stops the real Loom transport. SELECT chooses pages, or one of
  four visible 16-step banks on SEQ. OCT-/OCT+ transpose physical melodic keys.
- White keys toggle the selected sequence bank. Existing note chords, per-note
  velocities and microtiming, ratchets, probability, gate and skip state are
  retained. P-locked steps are explicitly refused to avoid an upstream native
  replacement race. PRESETS chooses the drum/slice row, not a new engine.
- HOME/MIX knobs change track volume, pan, mute and tempo. SEQ knobs change
  new-step note, exact pattern length, drum/slice row (where applicable), tempo.
  Hardware tempo matches Loom's touch controls at 12–240 BPM. The wire can also
  show external MIDI-clock tempos through 300 BPM. Tempo display rounds to an
  integer BPM; unsupported/non-finite tempo fails closed rather than lying.
- SOUND has engine-aware timbre controls; ENV maps actual Loom amp-envelope
  parameters for subtractive, FM, sampler, granular, wavetable, audio input and
  SoundFont. Values are explicitly normalized **percentages**. Drums and MIDI
  have no track ADSR and display **NO ADSR** instead of a fabricated envelope.
- Touchscreen state changes are reflected in the FM1 snapshots. Hardware
  changes update both Loom's Kotlin model and native engine, so the phone UI
  and local project model follow the hardware.
- USB output is explicit AAudio stereo at 44.1 kHz. It starts muted, verifies
  the selected actual route and waits for an applied firmware state ACK before
  enabling audio. Errors, route loss, stream restarts and lease failure stop
  transport and mute. There is no automatic speaker fallback while held.
- The output hold survives activity/process recreation. Reconnect explicitly
  or choose **Restore normal audio** to return to ordinary Loom output.

## What this is not yet

This is **not complete native Skiff UI parity**, nor an extended-pattern Loom
release. FM1 still uses the proxy page renderer for this prototype. HOME,
piano-roll/NOTES, browser, mixer gestures, hold/shift workflows, effects and
parameter locks have not been ported into native Skiff drawing/commands.

Loom's engine/model/persistence remains **64 steps per pattern**. Hardware
length editing is 4–64; chaining is the existing Loom feature, not a new
1024-step pattern implementation. All 8 track engines remain in the phone.

FM1 REC, LFO, ARP, engine/preset loading, sampling/recording controls, FX editing,
velocity/gate editing and advanced arrangement stay on the phone. SAVE writes
Loom's local last session, not any FM1 standalone project. This fork does not
claim to fix every pre-existing Loom recording/persistence issue: native live
overdub details are not fully exposed by its getters. Back up important work.
Physical sampling input routing is not newly implemented by this output adapter.

Drum engines have eight actual voices; chopped sampler mode has sixteen rows.
For drums use the first eight white keys; for slices, all sixteen white keys.
Melodic engines use the full 27-key range. The displayed playhead is the engine
position, not a measured latency-compensated audible position.

## Why ENV is different

Skiff r3 native ENV renders Skiff integer coefficients and native timing units.
Loom's ADSR uses different time curves: normalized attack is cubed into a roughly
0–2-second linear ramp; decay/release use cubed values in roughly 0–3-second
exponential time constants. A Skiff value 64 is not the same time/shape as Loom
50%. Operator envelopes and sampler/granular envelopes also have engine-specific
parameter IDs. Changing Loom to Skiff's math would alter existing presets.

Therefore v3 carries honest percentage labels and never enters the v2 native
Skiff graph. The next native ENV implementation should reuse Skiff's page
layout with **Loom-specific descriptors, units and graph calculations**. It
must not reuse Skiff's coefficient-to-time display for different audio behavior.

## Matching firmware and careful owner testing

Requires the separately built **Loom CAFA v3 firmware**, not the previous r3
native-ENV firmware. v1 and v2 firmware behavior is retained on the new firmware;
this APK deliberately does not silently downgrade to either version.

No device was flashed or Android APK installed by this development task.
Host checks and Android compilation do not qualify physical audio or latency.

1. Preserve an Editor backup and a known-good firmware package. Follow the
   matching firmware's normal interactive installation instructions. Never
   factory-reset/format to try this integration.
2. Turn master volume down, disconnect amplification/headphones initially, and
   use stable power and a direct data-capable USB cable.
3. Install **Loom CAFA** alongside existing apps. Start a disposable project.
4. Open **FM1 LINK**, select the actual FM1 MIDI endpoint and USB audio output,
   and connect. Only play after **FM1 synchronized** appears.
5. Check tracks 1–8, held-note release after track changes, transport, all four
   banks, touchscreen-to-FM1 updates and FM1-to-phone knob updates. Check ENV
   on several engines, expecting percentages/proxy UI, not native Skiff units.
6. At low volume test unplug/replug, reconnect, background/foreground, activity
   recreation and app restart. Output should stay muted after failure until an
   explicit reconnect/restore. Verify standalone FM1 projects remain intact.

Physical USB permission/driver behavior, DSP CPU load, audio glitches, latency,
screen layout on the user's phone, pause/recreate races and speaker-leak timing
remain device qualification items. A failure should be treated as a prototype
bug, not solved by turning safety gates off.

## Build, tests and licensing

See [Android build instructions](docs/ANDROID_BUILD_CAFA.md).

- `tests/run-cafa-protocol.sh`: pure Java framing/session checks and v3 fixtures.
- `tests/cafa-audio-route/run.sh`: actual Android AudioEngine implementation with
  a stubbed Oboe route harness; not physical Android-driver validation.
- `scripts/build-android.sh`: shared/app unit tests, lint, four-ABI debug APK.
- `tests/run-firmware-link.sh /path/to/link-peer`: actual Java CafaSession against
  production firmware USB-MIDI assembly and its one-frame mailbox.

The fork retains Loom's GPLv3 license and third-party notices. CAFA-specific
files are GPL-3.0-only. Java protocol/session work is adapted from the existing
GPL Skiff/CAFA prototype. Distribution must include the matching complete source,
build scripts, license and notices. No private signing keys, app databases,
samples recorded by users, credentials or recovery files belong in the source
bundle. The supplied debug APK is for testing, not an upstream or store release.
