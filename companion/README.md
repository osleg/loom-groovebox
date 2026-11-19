# FM-1 companion library

This Android library implements the first layers of the project's Open FM-1 Companion HLD: the existing **legacy CAFA v5** connection used by LOOM, shared parameter and sequence controls, and a **wire-6 instrument API** with negotiation, physical controls and operation results. Generic sequence views, preset/project and complete pocket workflows remain unfinished.

## Implemented boundary

| Library (`org.fm1.companion.legacy`) | LOOM adapter (`com.groovebox.cafa`) |
|---|---|
| `Protocol`: bounded SysEx framing and decoding | `CafaSurface`: existing hardware workflows and snapshots |
| `CafaSession`: session ID, revisions, acknowledgement and lease | `CafaCatalog`: LOOM backends and control mappings |
| `LegacyMidiConnection`: Android USB MIDI ports, receive decoding, session timer and connection cleanup | `CafaConnection`: native-engine/model binding, persistence, audio-route verification and UI status |
| `LegacySurface`: snapshot, input and reset boundary | Catalog IDs and saved musical data remain in LOOM |
| `control.ParameterDescriptor`: domains, stepping, normalization and formatting | `CafaCatalog`: maps LOOM parameter IDs to shared descriptors |
| `control.ParameterBanks`: four-slot bank navigation and revision-checked edits | `LoomParameterAdapter`: current values, revision tracking and native/model updates |

The library has no dependency on LOOM, Compose, native synthesis or an audio output. LOOM continues to require its explicitly selected USB audio route; moving the transport does not change that policy. Existing firmware, v5 bytes, save feedback and catalog persistence are preserved.

`LegacySurface` intentionally exposes v5 payloads during migration. New instrument integrations can implement `instrument.InstrumentAdapter` and use `InstrumentController` with `OpenMidiConnection`, following the [open contract draft](OPEN_PROTOCOL.md). The [reference app](../instrument-demo/README.md) demonstrates this without importing LOOM's model, sequencer or native engine.

## Instrument application adapter

`InstrumentAdapter.read()` returns the authoritative selected instrument, stable references, revision, available instruments, ordered controls and note range. The adapter receives semantic NoteOn, NoteOff, SetParameter and SelectInstrument operations. It does not decode hardware scans or rebuild button/knob navigation. The application compares the target/revision and applies an edit on its model-owner thread, preserving properties that operation does not address.

`OperationDispatcher` assigns session-local operation IDs, allows eight pending ordinary operations and retains 64 terminal receipts. Identical retries return the existing result; conflicting IDs are invalid and evicted old IDs are stale. Note release remains available when ordinary admission is full. A new session cancels its predecessor and ignores its late completions. Note operations are synchronous; selection/parameter operations can complete asynchronously on the same owner thread. A successful result includes the published revision and the controller reads back authoritative state.

`InstrumentController` owns held-note identity, instrument selection, octave, sound banks, revision checks and a 32-entry feedback queue. It handles actual drawn context and retains feedback until a matching state acknowledgement. Accepted value-only relative turns can share a context lineage; changed targets/descriptors or external edits retire it. Status views retire the old edit token too. Unsupported sequencer/transport controls show unavailable feedback.

```kotlin
val controller = InstrumentController(adapter)
val connection = OpenMidiConnection(applicationContext, controller, listener)
connection.connect(device, adapter.namespace, Wire6.PHONE,
    Wire6.Capability(Wire6.INSTRUMENT, 1, 0, true))
```

The controller exposes instrument capability 1, optional transport capability 2 and optional parts/mixing capability 3. It does not implement preset storage, expression or sequence editing. Callbacks and model access stay on the main thread; the app separately owns audio rendering and route policy. The reference app offers explicit phone/wired or FM-1 USB output and remains instrument-only. Its foreground playback service owns the session across activity and screen changes. Version 0.1 received user-reported hardware-control and phone-audio smoke acceptance; version 0.2 is now installed, with its USB/background features still requiring device acceptance.

## Optional transport workflow

Supply a `transport.TransportAdapter` to `InstrumentController` and request transport 1.0 in `OpenMidiConnection.connect`. The adapter shares the instrument namespace and exposes authoritative play/record state, tempo with edit bounds, optional position and Continue support. Successful HELLO negotiation reaches `Surface.capabilitiesAccepted(Set<Int>)` before the first snapshot; audio readiness still waits for display acknowledgement. Without acceptance, the controller does not read or execute the transport adapter. Accepting transport without supplying an adapter closes the session.

The shared `TransportControls` workflow issues semantic Start, Stop, Continue and SetTempo through the owner's operation dispatcher. Start restarts at the beginning; Continue is explicitly optional. All transport operations complete synchronously. Stop bypasses ordinary pending admission and ignores stale editable revisions. Accepted-profile cancellation stops application playback, including playback started on the phone while connected.

In wire 6, PLAY toggles playback, GLO+PLAY restarts and GLO+SELECT changes tempo. Position-only updates keep the current edit context; external state/limit changes retire it. The overview shows real playing/recording state, decimal BPM and an optional unit-labelled position. LOOM adopts this shared workflow through `LoomTransportAdapter` while preserving its existing v5 pages and gestures. Its native Stop resets position, so Continue is unavailable; its current public native API has no global playhead, so the new adapter does not invent one.

## Optional parts and mixing

Supply `parts.PartsAdapter` as the controller's third argument and request capability `Wire6.PARTS` version 1.0. It shares the application's namespace and exposes stable part references, the selected part and authoritative mix controls. `PartsControls` handles relative selection and four mixer slots through the controller's operation dispatcher. Optional controls are absent rather than simulated. Selection and all mix edits are synchronous and revision checked; ending a session leaves the saved mix unchanged.

ALGORITHM selects parts, GLO opens the mixer, and the knobs control level, pan, mute and solo. The bounded mixer shows up to eight part indicators and the selected part's formatted values. Original voices release before selection; stale context cannot redirect an edit or note. Parts and transport negotiate independently. LOOM uses this adapter in its v5 pages: its existing MIX bank is preserved, and SELECT reaches a second bank with SOLO on the fourth knob.

The earlier development reference APK accepted only capability IDs 1 and 2 in OFFER. Install the rebuilt reference APK with firmware advertising parts; the updated parser ignores unknown optional offers so later extensions do not repeat that discovery incompatibility. Legacy v5 remains supported.

## Negotiated wire-6 transport

The `org.fm1.companion.protocol` package is separate from the legacy codec:

- `Wire6` validates bounded frames, capability records and four generic views (parameter bank, overview, operation status and mixer).
- `OpenSession` owns discovery, exact capability acceptance, state acknowledgements, the progress deadline and ordered input. Discovery and rejected negotiation do not send an ownership-ending BYE. Missing replies never trigger a legacy fallback.
- `OpenMidiConnection` owns Android USB MIDI ports and main-thread delivery. Both the transport callback backlog and session input queue are bounded. Overflow, input gaps, loss of display progress or failed route readiness end the connection and reset its surface.

Call `OpenMidiConnection.connect(device, namespace, audioMode, capabilities...)` on the main thread with an explicit `Wire6.PHONE` or `Wire6.USB_RETURN` route and required instrument capability 1.0. `opened()` only reports open ports. `synchronizedDisplay()` follows acknowledgement of a sent state; the application must also verify its chosen audio route before enabling output. Returning false from this connection's `beforeTick()` closes it. `Surface.reset()` and `stopped()` must release application-owned voices and stop playback. The library does not synthesize or route audio.

This is the lower transport boundary: `Surface.snapshot()` supplies a validated generic view, `Surface.input()` receives the 24-byte physical scan, and `Surface.acknowledged(context)` identifies delivered state for feedback retention. `InstrumentController` implements that boundary for instrument adapters. Remaining capability workflows and LOOM's transition to wire 6 are separate work. LOOM continues to use v5 so its existing pages remain available.

## Shared parameter-bank workflow

`ParameterBanks` and `ParameterDescriptor` have no Android or LOOM imports. An adapter supplies a `State` with an opaque session target, a nonnegative revision and ordered parameter controls. A null control occupies an empty slot; duplicate assignments retain their positions. Up to 65,532 controls form four-slot pages, with at least one empty page for an unavailable instrument.

The adapter increments the revision whenever the selected target, descriptors or parameter values change. Its `apply(Edit)` rechecks the target and expected revision before mutation, updates the application through its existing APIs, and returns the actual result. The library does not optimistically change application values.

```kotlin
val banks = ParameterBanks(adapter)
val displayed = banks.page()
// After presenting displayed.controls, apply a turn against that context:
val result = banks.adjust(slot = 0, delta = 1, expected = displayed.context)
```

`selectBank(delta)` and `resetBank()` own bank navigation. Numeric descriptors define minimum, maximum, step, fallback and unit; enum descriptors also provide display choices. Values are clamped and non-finite values use the lower bound. The controller returns STALE if its displayed target/revision/bank no longer matches, UNSUPPORTED for an empty slot, and passes application failure results back without inventing success.

Calls run on the application's single model-owner thread. `State.targetId` is a session-scoped control target; it does not implement the draft's persistent `ObjectRef`. This lower component remains synchronous for existing LOOM callers. The instrument API uses its control/descriptor model with stable references and the operation dispatcher described above.

LOOM uses this controller for SOUND banks, including its preset-owned first bank. Its v5 bridge advances the display context after phone-side value changes, while completed relative hardware turns update the known authoritative revision so consecutive turns and multiple knobs in one scan keep working.

## Shared Skiff sequence editor

`sequence.SequenceAdapter` exposes the selected pattern/lane, revision, notes, feel values, transport state, scale and four effective step-lock controls. `SkiffSequenceEditor` owns PRESETS cursor/range navigation, SELECT note/feel pages, piano-key selection and audition, stopped step entry, drum-grid gestures and step-held locks. The adapter applies synchronous semantic operations through `OperationDispatcher`; it must validate an entire multi-note or multi-step change before writing anything. `NoteAddress` indices are scoped to the supplied revision, not persistent note identities.

Pitch, start, gate and velocity edits preserve unselected chord notes. `AdjustLocks` changes each selected step relative to its own prior lock or effective base value, without changing the base sound. Clearing locks does not erase notes. Normal sound assignments and step-held assignments are separate four-slot contexts supplied by the application; empty slots and duplicate controls retain their positions. Audition releases retain the original target even after navigation or cancellation. Live recording is unavailable until an application can publish complete recorded state.

LOOM's `LoomSequenceAdapter` maps these operations to its saved model and exact native step updates, including independent note gates and drum-lane locks. Its v5 bridge shows the selection as text, occupancy and the real playhead, plus four native value cards. It cannot display the complete Skiff piano roll or a persistent cursor highlight. Wire-6 Sequence capability 4 and view kind 5 remain reserved and unnegotiated; this semantic editor is not yet connected to `InstrumentController` or the instrument-only reference app.

## Android integration lifecycle

Use API 26 or later. Add `implementation project(':companion')` in a checkout containing this module. There is no published Maven artifact yet; this module retains the repository's GPL-3.0-only license.

Create `LegacyMidiConnection` with an application context, a `LegacySurface` and a listener. Call `connect(selectedUsbMidiDevice)` and `close()` on the main thread. Its callbacks also run on the main thread:

- `opened()` means the ports are open, before the handshake begins.
- `applied()` means firmware acknowledged a state; opening ports alone does not permit playback.
- `beforeTick(now)` lets the application enforce readiness or route checks. Returning false defers a tick; call `close()` to end the connection. This check must be bounded, not an indefinite pause of the lease.
- `stopped(reason)` requires the application to stop playback, release application-owned gestures and enforce its chosen audio policy. The library cannot mute an application's audio engine.

The owner must close the connection when it is no longer needed and prevent its ordinary MIDI path from using the same endpoint simultaneously. LOOM retains its MIDI reservation and its persistent muted-output policy. Activity-independent playback lifetime is later work.

## Build and verification

Use the repository's pinned Gradle wrapper and documented Android environment:

```sh
./gradlew :companion:testDebugUnitTest :companion:lintDebug :companion:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :instrument-demo:testDebugUnitTest :instrument-demo:lintDebug :instrument-demo:assembleDebug
bash tests/run-cafa-protocol.sh
```

The workspace's `tools/build-loom.sh` also runs the shared Android and desktop tests with the existing Windows toolchain. The library artifact is `companion/build/outputs/aar/companion-debug.aar`.

Existing protocol/session tests preserve legacy behavior; LOOM surface tests exercise the shared controller through real model edits and v5 snapshots. `OpenSessionTest` covers the new host boundary. The workspace's `tools/verify-open-protocol.sh` connects the production Java session to Skiff's actual USB mailbox, ownership, display and input code, using a host HAL. Run it after `tools/build-skiff.sh` generates firmware tables.

Shared-controller tests use a small independent instrument model with no tracks, sequencer or audio route. The separate `instrument-demo` app uses the public adapter and controller with its own oscillator; its unit tests check real PCM output, stale edits, original-note release and voice limits. The workspace's `tools/verify-instrument-apk.sh` checks its independent package identity, signature, alignment and absence of native engine dependencies. These checks establish implementation reuse, while Android MIDI port lifecycle and physical USB/audio operation still require phone/FM-1 acceptance.
