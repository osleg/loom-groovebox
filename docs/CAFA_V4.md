# Loom CAFA v4 mapped-controls build

This version reconstructs expanded controls from the published v3 app after the
previous local workspace was lost. It is an experimental, not hardware-qualified
build. Matching **CAFA protocol-v4 Skiff firmware** is required. v3 firmware will
not be silently treated as v4.

## Install alongside the working version

The original private debug signing key was lost. To avoid uninstalling the old
app or deleting its projects, this build has a separate application ID,
`com.groovebox.cafa.v4`, label **Loom CAFA v4**, version `3.2.19-cafa.4`, code 390.
It can coexist with Loom CAFA and ordinary Loom. Existing projects are not
imported automatically: use Loom's export/import workflow and retain originals.
No signing key is included in GitHub or the source bundle.

## Physical controls

- **ALGORITHM:** selected Loom track, 1–8, on every page. Held-note releases
  retain their original track and pitch across selection and octave changes.
- **HOME / GLO:** mixer controls: volume, pan, mute, tempo. **GLO + SELECT**
  changes tempo regardless of the current family.
- **SEL / EDIT:** engine-aware sound controls. **ENV:** actual Loom amp/master
  attack, decay, sustain and release. Drums/MIDI explicitly show NO ADSR.
- **SEQ:** note grid. Press SEQ again or turn SELECT right for PATTERN; SELECT
  left returns to the grid. PRESETS scrolls four 16-step banks. Patterns remain
  4–64 steps, not 1024. Grid knobs set new-note pitch, length, drum/slice row
  where applicable, and tempo.
- **Step key tap:** toggle on release. **Hold white step key + knobs:** pitch,
  velocity, gate, probability. Chord intervals, individual note velocities,
  microtiming, ratchets and punch are retained. A consumed hold never toggles on
  release. Track/page/bank/lane/length changes and disconnect cancel pending
  taps. P-locked steps and step replacements during recording are refused.
- **PATTERN:** length, tempo, swing, humanize.
- **LFO:** four knobs edit rate, depth, shape and sync. SELECT chooses one of six
  global LFOs. Existing routing/target/intensity are retained, not reassigned.
  An unassigned LFO will not affect sound until routed on the phone.
- **FX:** choose one of eighteen buses, change selected-track SEND, inspect
  read-only UNITY, and enable/bypass the bus. SELECT also chooses the bus.
  Loom's native engine forces FX mix to unity; this version does not show a
  fictitious editable MIX value. SEND changes its wet contribution.
- **ARP:** mode, octaves, latch, rate. SELECT right opens inversion, probability,
  weird and strum; SELECT left returns. Existing rhythms, gates, mutation and
  chord-progression configuration are preserved.
- **PLAY:** real Loom transport. **OCT− / OCT+:** physical-key octave.
- **SAVE:** local Loom last session. It never saves or overwrites a standalone
  Skiff project. **REC is explicitly unavailable** until complete native
  recorded-step readback can preserve every detail in the phone project.
- Outside SEQ, PRESETS chooses the drum/slice row when applicable. Sound preset
  and engine loading remain on the phone.

SELECT stays in the physical button's family rather than cycling unrelated
pages. Touchscreen edits enter the next snapshot; hardware edits update both
Loom state and DSP. Some wire labels expose encoded units: OCT+3 and INV+2 use
nonnegative offsets; SWING+23 offsets −23…+23%; RATE x2 and GATE x10 are scaled.
The matching native renderer can show their musical values.

## Native display and remaining limits

The matching firmware reuses Skiff's real header/cards/footer and Loom-specific
ENV curve rather than pretending Loom uses Skiff envelope timing. ENV is an
illustrative parameter preview, not a measurement of the whole audible sound;
SoundFont instruments may also have envelopes inside their SF2 data. The wire
contains occupancy, not note pitches, so the sequence overview is not a full
native piano roll. Eight tracks are labeled explicitly; they are not wrapped
onto six track icons.

This is not full Skiff workflow parity. Engine/preset browsing, sample recording,
parameter-lock editing, full note-selection/tie workflows, automation and song
arrangement remain on the phone. Hardware REC is disabled; the existing phone
recording implementation is not repaired by this build. Audio gain has not been
changed to address the previously reported quieter level.

The matching firmware omits factory instrument sample sets to fit the expanded
UI. User sample slots are retained. This is not a storage erase or factory reset.
Standalone native synthesis remains available when disconnected.

## Verification

- Full Android build passed (109 tasks), including all **32 unit tests**.
- Lint: **0 errors**, 32 existing warnings, 1 hint.
- APK signature, alignment, separate identity and all four ABI/JNI checks pass.
- Protocol/session: **17,083 assertions**.
- Actual Java sender against firmware USB-MIDI assembly: **1,976 checks**,
  20.4-second sessions serviced every 34/102/170 ms, lost HELLO/ACK recovery,
  return-path failure, lease expiry and reconnect.
- Native core tests exercise all 144 track/effect sends, read-only unity,
  ARP values, humanize and detailed chord-step edits, alongside existing
  4/17/33/64-step and all-engine checks. Audio-route failure harness passes.
- The Android suite emits 77 real-model wire fixtures for matching firmware
  validation. Firmware build/sanitizer/UI/budget results are documented in its
  own branch and deliverable.

Logs are under `docs/verification/v4/`. No phone installation, firmware flash,
physical USB/audio test, stack measurement or listening qualification was done.
Keep a backup and the known-good firmware. Start at low volume with stable
power/direct USB, wait for FM1 synchronized, then test each mapped family and
unplug/reconnect before using valuable projects.
