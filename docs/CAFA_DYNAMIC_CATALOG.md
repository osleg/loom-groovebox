# CAFA v5 runtime catalog proof of concept

This branch implements the phone-to-FM1 dynamic engine/control catalog path. The
FM1 firmware contains no Loom engine IDs, control IDs, ranges, enum strings or
engine list. The phone owns that information and sends only the visible page.

## Hardware workflow

Press SOUND (or FILTER). PRESET turns browse and immediately select entries in
the phone's current runtime catalog. SELECT turns page through four controls at
a time; the four parameter encoders edit the displayed controls. Track selection
continues using TRACK. Other physical page families retain the native v4 surface.
Switching an engine releases held hardware notes first. Engine switching uses the
same native setEngineType plus TrackState.engineType change as Loom's mixer and
preserves existing track parameters, patterns and sample assignments.

The initial registry contains the ten existing Loom backend engine types. Control
pages use the phone's current strip/knob assignments when present; otherwise they
use the previously implemented, known CAFA sound controls. CafaCatalog.register
accepts stable string IDs, names, backend adapters, control descriptors and an
optional activation callback. Registering additional engine/sound adapters or
changing descriptors does not require a new Skiff build. The tests register an
unknown ORBIT CUSTOM entry at runtime, browse it from hardware, select it, page
its nine controls, and edit parameter 908 through the real surface model.

Custom adapter IDs are remembered per track for the lifetime of the surface.
Persisting these IDs in saved projects is not implemented.

This is not a dynamic native DSP binary loader. Loom itself still has its existing
compiled EngineType/native backends. No filesystem sample/preset discovery,
plugin package installation, or universal sound-file loader is implemented here.
The activation callback supports future sound adapters, but this branch does not
claim the user's full drop-in engine/sample collection is finished.

## Bounded wire protocol

Version 5 uses exactly 156-byte STATE snapshots. Bytes 0–91 retain v4 layout;
SOUND uses generic page 9. Bytes 20–35 are engine name, 36–51 bank title, 52–83
four eight-character labels, and 84–91 four normalized u14 values. Extension:

- 92/94: zero-based control bank / bank count (u14 each).
- 96/98: zero-based catalog entry / entry count (u14 each).
- 100–147: four preformatted twelve-character display values.
- 148–151: absent/numeric/enum/toggle/action kinds, 0 through 4.
- 152–153: context token (u14), including native page contexts.
- 154–155: reserved, zero.

Native pages 0–8 carry zero index/count pairs. Page 9 requires positive counts.
All transmitted data is seven-bit safe. Catalogs are limited to 16383 entries and
16383 banks per engine. Firmware caches one page, not the catalog or sound data.

INPUT is 22 bytes: the v4 held-state/delta payload plus the context token from the
last page actually drawn on FM1. Changing track, page, engine, bank, registry or
control descriptor changes the token. Stale input still releases notes but cannot
edit the new context. Navigation and knob deltas in one scan do not edit the new
page. Session IDs, revision ordering, lease timeout and applied-state audio gating
remain in use. No standalone FM1 project writes or USB audio layout changes.

## Verification

Run tests/run-cafa-protocol.sh for pure Java framing/session checks. Run
tests/run-cafa-surface.sh with KOTLINC, JUNIT_JAR and HAMCREST_JAR set for the real
Kotlin GrooveboxState/CafaSurface tests without Android. These export actual v5
fixtures to build/cafa-model-vectors for the matching firmware tests. Results: 17,083 Java framing/session assertions, 25 real Kotlin surface tests,
and 1,976 real Java/C firmware mailbox interoperability checks passed. Existing
native AudioEngineCore and audio-route regression suites also passed.

The application identity is com.groovebox.cafa.v5, label Loom CAFA v5, preserving
the installed v4 app. An Android APK build, device launch, real USB playback,
round-trip knob latency, sample loading, and hardware listening are not verified
by these host tests. This proof of concept must not be presented as hardware-ready.
