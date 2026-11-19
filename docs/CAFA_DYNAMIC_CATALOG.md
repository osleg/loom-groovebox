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

Custom adapter IDs are stored in TrackState.catalogEntryId and saved with the
project. Recreating the surface resolves the same registered ID and backend.
Older projects without an ID use their built-in backend entry. An unavailable ID
is preserved and shown as MISSING ENTRY; sound/envelope edits are disabled until
an available entry is selected. Selecting a backend explicitly on the phone
clears the custom ID. This restores adapter identity, not arbitrary activation
callback state or an absent adapter's implementation.

Hardware SAVE writes Loom's local last_session.gbx and briefly displays SAVED or
SAVE FAILED in the existing title field, according to the persistence result.
Normal Android startup restores the last session before trying Init.gbx. The
existing crash-recovery path still starts fresh.

This is not a dynamic native DSP binary loader. Loom itself still has its existing
compiled EngineType/native backends. No filesystem sample/preset discovery,
plugin package installation, or universal sound-file loader is implemented here.
The activation callback supports future sound adapters, but this branch does not
claim the user's full drop-in engine/sample collection is finished.

SOUND bank navigation, parameter domains/formatting and revision-checked edits now
use the shared `:companion` control module through `LoomParameterAdapter`. The
adapter retains LOOM's engine IDs and model/native updates. Phone-side value
changes invalidate old hardware edits; completed relative hardware turns keep
their gesture context so consecutive turns and multiple knobs remain usable.
See [the library boundary](../companion/README.md). The wire remains v5; this is
not yet the negotiated open protocol or a complete second-app integration.

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
For an unavailable entry, the index remains in bounds (zero), the text identifies
the missing entry, and all four control kinds are absent. No new wire sentinel is
introduced.
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
fixtures to build/cafa-model-vectors for the matching firmware tests. The branch
baseline recorded 17,083 Java framing/session assertions, 25 real Kotlin surface tests,
and 1,976 real Java/C firmware mailbox interoperability checks passed. Existing
native AudioEngineCore and audio-route regression suites also passed.

The catalog-persistence increment was verified on 2026-11-17 with 28 app surface
tests, 15 desktop tests (including file persistence) and 13 common Android unit
tests. Android lint, the debug APK build and the package verification checks
passed. The new regression covers adapter IDs across serialization/recreation;
missing-entry and save-result display behavior are also covered.

The application identity is com.groovebox.cafa.v5, label Loom CAFA v5, preserving
the installed v4 app. Device launch, real USB playback, round-trip knob latency,
sample loading and hardware listening are not verified by these host tests or the
APK build. This proof of concept must not be presented as hardware-ready.
