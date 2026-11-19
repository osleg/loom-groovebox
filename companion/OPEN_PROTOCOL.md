# Open FM-1 Companion contract — draft 0.3

Date: 2026-11-17. Status: draft contract with a local first-profile implementation in `protocol.Wire6`, `OpenSession`, `OpenMidiConnection` and Skiff's `cafa_wire6.h`/surface hooks. The updated firmware is installed on the development FM-1; its physical wire-6 discovery check passed. The current local LOOM implementation uses the negotiated native UI extension; the previously installed build still uses legacy v5. New native UI control/audio hardware acceptance remains open. The full semantic application contract below is not implemented by the transport layer alone.

The implementation contains the shared synchronous `control.ParameterBanks` workflow used by LOOM, the instrument semantic API in `instrument.InstrumentAdapter`, optional transport in `transport.TransportAdapter`/`TransportControls`, and parts/mixing in `parts.PartsAdapter`/`PartsControls`. `OperationDispatcher` handles all three intent families, and `InstrumentController` enables optional workflows only after negotiation. The independent `:instrument-demo` application remains instrument-only. LOOM uses the shared semantic workflows through `NativeLoomSurface` and `OpenMidiConnection`; its native presentation does not imply that every future semantic capability has been negotiated.

This document separates three contracts: musical application semantics, shared hardware workflows, and the USB MIDI wire binding. LOOM implements the first application adapter; it does not define the public object model.

## Required native UI coverage

The target is Skiff's actual native UI for all 12 function buttons and every reachable page, sheet, menu, layer and dialog. Skiff supplies layouts, widgets and interactions; the connected app supplies values, names, page/control labels, domains and musical state. The current v5 custom renderer and wire-6 generic kinds 1–4 do not satisfy this target, even when their controls behave like Skiff.

The workspace [native screen matrix](../../docs/skiff-ui-coverage.md) inventories all 47 current page descriptors, both HOME views and the additional overlays/menus. The protocol must describe each native screen's complete visible data, navigation/edit actions, selection, availability, owner scope and operation results. This includes engine and operator sheets, envelopes/LFO/modulation, effects and buses, scale/chord/follow, mixer, sequence/drum/automation/song, preset/project browsers, naming, confirmations, layers, takes and device-local screens. PLAY/REC retain native transport actions and contextual layers; they are not invented parameter pages.

An app's missing capability must appear through native unavailable/disabled presentation. Protocol coverage of a screen is separate from an app implementing its musical feature. Native renderers must use validated application data, never unrelated standalone project or engine state. Keep one navigation/context owner and preserve original-voice release and stale-input rules. Any multi-message visible view needs a specified bounded window and atomic publication; receipt of a partial page cannot authorize edits or count as a drawn view.

The implemented extension is Native UI capability 9 version 1.0, view kind 7 and multipart command 9. The [native wire specification](../../docs/native-ui-wire.md) defines all 72 screen IDs, typed sections, complete-view validation and device-owned settings. Previously implemented capabilities 1–3 and generic kinds 1–4 keep their meanings. Code/build qualification and physical acceptance are recorded separately in the workspace implementation status.

## 1. Version and ownership rules

- Wire versions 1–5 remain legacy CAFA with their existing layouts and behavior. Version 6 is reserved here for the first explicitly negotiated open profile. A wire-6 frame must never be passed to the v5 decoder or interpreted as a v5 snapshot.
- Core contract major/minor is `1.0` for this draft's proposed profile. Each optional capability has its own major/minor. Project formats and application versions are independent.
- An incompatible core major or required capability is rejected before takeover. An unknown optional capability is ignored and never enables a workflow. Minor versions may add optional behavior only; they cannot change existing field meanings.
- One application owns a session. Discovery does not acquire ownership. A second application receives BUSY while an owner has a live lease. Disconnect/expiry invalidates all session handles, operation IDs and pending display context.
- The application owns its musical model, sequencing, synthesis and saved data. The library owns physical input interpretation, navigation, synchronization and result presentation. Firmware owns physical held controls and one bounded display image.
- No adapter receives a Skiff page number, raw button bit or LOOM engine enum through the semantic API. The existing `legacy.LegacySurface` is a migration boundary, explicitly outside this API.

## 2. Public objects

An `ObjectRef` contains `(applicationNamespace, kind, stableId)`. Namespace is a stable ASCII identifier of at most 64 bytes; stable IDs are nonempty application-owned strings of at most 128 UTF-8 bytes. IDs must not depend on display order or names. Kinds include instrument, parameter, transport, preset, part, pattern, note and project. An instrument-only app can expose one instrument with no parts, transport or pattern objects.

Every editable object has an application-issued nonnegative 63-bit revision. Its revision changes when observable editable content changes, including a touchscreen edit. Revisions need not survive reopening a project; stable IDs do. An adapter must either compare and apply atomically against the expected revision or refuse the operation as STALE. It must preserve properties the operation does not address.

Public descriptors expose names, values, units, numeric bounds/steps, enum choices and permitted operations. Numeric values must be finite and within the advertised domain. Missing assets/instruments remain identifiable; a missing reference is never replaced by the first list entry.

Only bounded visible content is encoded for firmware. The library maps the current context to the relevant object references and revisions on the phone. Persistent object IDs do not need to fit in a firmware packet. A wire context token is an ephemeral selection handle, not an object ID or project revision.

## 3. Capabilities and operations

| ID | Capability | Semantic operations | Required limits and semantics |
|---|---|---|---|
| 1 | Instrument | NoteOn, NoteOff, Expression, SelectInstrument, SetParameter, SelectPreset | Note/velocity domain, voice identity, supported expression, parameter domains, preset availability |
| 2 | Transport | Start, Stop, Continue, SetTempo | Tempo range, position units, supported start/continue behavior |
| 3 | Parts/mixing | SelectPart, SetLevel, SetPan, SetMute, SetSolo | Part count, gain/pan domains, solo behavior |
| 4 | Sequence | ListPatterns, SelectPattern, CreateNote, DeleteNote, EditNote | Integer ticks per quarter, pattern bounds, overlapping-note support, supported note properties |
| 5 | Recording | Arm, BeginRecording, EndRecording | Arm targets, quantization/overdub semantics, captured-state readback |
| 6 | Automation | ListAutomation, SetLock, DeleteLock | Supported targets, time domain, interpolation and lock precedence |
| 7 | Effects/modulation | InspectRouting, SetParameter, SetRouting | Supported nodes, editable connections and parameter domains |
| 8 | Projects | ListProjects, LoadProject, SaveProject | Writable targets, current project identity, completion reporting |
| 9 | Native UI | PublishNativeView, physical input with last-drawn context | Skiff screen catalogue, bounded typed content, atomic publication and device-owned settings |
| 10 | Control bindings (reserved) | InspectBindings, AssignControl, ClearBinding | Assignable controls, target parameter references, preset ownership, read-only/unsupported targets |
| 11 | Native UI idle keepalive | HEARTBEAT and matching ACK | Maintains an acknowledged, unchanged native display. Requires capability 9. |
| 12 | Incremental native views | VIEW_DELTA and matching ACK | Atomic single-frame updates to a confirmed native page. Requires capabilities 9 and 11. |
| 13 | Native panel | Stock menu, live panel inputs, LED planes and appearance | Requires capability 9. |
| 14 | Panel MIDI | Forward external MIDI and clock | Physical MIDI port on supported controllers. |
| 15 | Native screen header, footer and card roles | Native view section 14 | Optional, requires capability 13. See the [field bindings](../../docs/native-ui-wire.md#screen-field-bindings). |

Capability presence alone is insufficient: descriptors must declare the specific supported operations and limits. A preset-less instrument does not expose a preset browser. Unsupported sequencer buttons show an unavailable result; the library must not pretend an instrument app has patterns.

Control bindings are musical data owned by the application/preset, not session navigation. A binding relates a stable parameter reference to a logical control slot; it is not a firmware engine or parameter number. The adapter reports the effective bindings and their revision, preserves empty slots, and saves/restores them with the preset when supported. The shared library presents the effective bound bank and invalidates its context when bindings change. Assigning a control must not create automation or overwrite the application's default bank. The pending binding profile will expose supported slots and assignment operations; it does not prescribe a touchscreen long-press implementation for other apps.

FM-1 Instrument's stock Skiff adapter requires capabilities 1, 9, 13 and 14. It requests 11, 12 and 15 as optional. Generic instrument clients may use the parameter-bank profile. LOOM requires capabilities 1, 2, 3 and 9 at 1.0 and requests optional capabilities 11 and 12 at 1.0. IDs 4–8 and 10 remain reserved semantic profiles; native presentation does not activate them. The unimplemented binding reservation moved from 9 to 10 before negotiation. LOOM retains application-owned normal and step-held bindings through its existing model. Live recording remains unavailable until captured-state readback is complete.

## 4. Operation identity and results

Every state-changing semantic request contains:

`sessionGeneration, operationId, targetRef, expectedRevision, operationKind, arguments`.

`operationId` is a monotonically increasing positive 63-bit value within the library session. It is allocated once for an accepted physical intent, including each operation in a multi-control scan. Retrying a request preserves its entire contents and ID. Performance notes also carry a library voice ID so navigation cannot redirect a note release to another part.

NoteOff, Stop and session cancellation are termination operations: an outdated editable-object revision must not prevent them from releasing their original voices or stopping playback. They use the recorded voice/session identity and remain idempotent; ordinary musical edits still require the expected revision check. Termination must remain available when ordinary operation admission is full.

The implemented instrument-only adapter supports NoteOn, NoteOff, SelectInstrument and SetParameter. Parameter IDs are stable within their target instrument. Calls and completion callbacks run on the application's model-owner thread. NoteOn and NoteOff must return synchronously; a deferred note start could otherwise arrive after release. An adapter that returns pending for a note operation causes session cancellation. Selection and parameter work may return pending, then publish state and report exactly one terminal completion. COMPLETED includes the accepted revision; authoritative values are queried through `read()`.

`OperationAdapter` is the common operation/cancellation boundary. `InstrumentIntent`, `TransportIntent`, `PartsIntent` and the local semantic `SequenceIntent` implement `OperationIntent`; unrecognized intent implementations and mismatched target kinds are rejected before application mutation. One wire-6 controller uses one dispatcher and operation-ID sequence for its negotiated families. Supplied transport and parts adapters must use the instrument adapter's application namespace. Sequence operations serve LOOM's native UI controller as well as the retained v5 bridge; their existence does not enable the reserved Sequence capability.

Transport supports Start, Stop, Continue and SetTempo. Start restarts at the beginning; Stop terminates playback even with an old revision. Continue resumes a retained location only when the adapter advertises `supportsContinue`; it must never be implemented as Start. All transport operations complete synchronously, so a deferred Start cannot occur after Stop or reconnection. Pending transport results end the session just like pending note starts. Start/tempo edits recheck the target and revision, and tempo uses finite, application-supplied edit bounds and step. Reported tempo may lie outside those edit bounds, such as an imported LOOM tempo above its normal 240 BPM editing limit; invalid source values must not be replaced with invented valid values.

Transport revision covers editable playback/recording state, tempo and limits. Position advances without changing that revision or invalidating knob input. Position is optional: adapters without an authoritative transport position report unavailable, not a selected-track step pretending to be a global clock. Tick positions declare their resolution on the application side; the host formats a bounded unit-labelled subtitle for firmware.

Accepting the transport profile gives the companion session ownership of application playback, including changes made on the phone during that session. Cancellation stops it even if no hardware transport command was sent. An unaccepted transport adapter must not be read, executed or cancelled by the controller. Reconnection never resumes playback automatically.

Parts/mixing exposes stable part references, selection, optional numeric level/pan controls and optional mute/solo flags. The application declares additive or exclusive solo behavior; mute takes precedence. Disabled parts remain selectable and editable but do not sound. A revision covers selection, the complete part list, descriptors and mix values, excluding meters. Each operation targets the selected part and rechecks that revision. SelectPart also changes the part exposed by the instrument adapter; voice IDs continue to identify their original part. All part operations are synchronous. Pending results close the session; cancellation preserves mix values and selection. A supplied but unaccepted adapter is not read, executed or cancelled. Accepted parts without an adapter are an integration error, not an empty mixer.

Results are `COMPLETED`, `ACCEPTED_PENDING`, `UNSUPPORTED`, `INVALID`, `STALE`, `BUSY`, `FAILED` or `CANCELLED`. A completed mutation returns the accepted object revision and authoritative changed state. ACCEPTED_PENDING is not save/load success: exactly one terminal completion follows. A frame ACK only confirms a wire message was accepted; it is not a musical-operation result.

The library's application-operation dispatcher admits at most eight pending operations and retains 64 terminal receipts. It also retains a high-water mark for operation IDs for the session. A duplicate with retained identical arguments returns the existing result without invoking the adapter again. Reusing an ID with different arguments is INVALID. A retry older than the retained receipts is STALE, not executed again. Full admission returns BUSY before application mutation. These limits are distinct from transport queues.

The adapter must report completion of admitted asynchronous work. Session loss stops presenting its results to the old controller and discards uncertain requests; reconnection must query current application state rather than replay them. A save already running may still finish in the application. This protocol does not promise rollback or exactly-once execution across process death.

## 5. Shared hardware workflows

The library maps FM-1 inputs to the active view and capability descriptors. The adapter implements semantic queries/operations and does not reconstruct button edges, held-note bookkeeping or bank navigation.

- Release tracked voices before applying navigation that changes their target. Releases from a stale display context still release the original voice.
- Discard old-context editing deltas. A navigation action and an old-context parameter turn in one scan must not edit the new target.
- The first held-state scan after connection establishes a baseline; already-held controls do not trigger new actions.
- A repeated INPUT serial is not a second musical edit. An ordering gap closes the session and releases held voices; guessing missing deltas is forbidden.
- Map physical notes, four-control banks, item selection and optional transport through descriptors. Unsupported controls produce visible feedback. Host-specific envelope shapes and timing are data, not firmware assumptions.
- Result presentation must show pending work distinctly from success/failure and must survive until firmware acknowledges a display containing the result. A temporary result must not be silently replaced before delivery.

The shared controller owns key/button interpretation, original-voice releases, instrument selection, four-control banks, octave shifts and bounded status delivery. Unsupported sequencer and preset controls produce unavailable feedback; transport and parts do too unless their adapters and capabilities are accepted. The first scan establishes held state; statuses retire the previous edit token and remain queued until a newly acknowledged STATE identifies their context. The initial control mapping follows Skiff/LOOM conventions: EDIT or SEL enters sound controls, HOME shows the instrument overview, SELECT changes sound bank, PRESETS selects an exposed instrument, four knobs edit their visible slots, and OCT−/OCT+ transpose new notes.

With wire-6 transport accepted, PLAY toggles Start/Stop, GLO+PLAY restarts from the beginning, and GLO+SELECT adjusts tempo. No new Continue gesture is assigned. Start/restart/tempo require current drawn context; Stop remains available through stale context and status feedback while playback is running. Stop/restart release held notes and suppress fresh notes or old knob edits in the same scan. Moving position alone does not retire the context; external editable transport changes do. Successful value-only tempo turns may advance their known revision within the same unchanged target/limit layout.

With parts accepted, ALGORITHM selects a part and GLO opens its mixer. The four mixer knobs adjust level, pan, mute and solo. Positive boolean turns enable; negative turns disable. HOME returns to overview; SEL/EDIT return to sound. GLO+SELECT retains its transport-tempo meaning when transport is accepted. Part changes release original held voices, reset the sound bank and suppress new notes/old knob edits in that scan. Touchscreen selection changes retire the same context. Accepted mix value edits may advance their context lineage only while part identities, selection, descriptors, support and solo semantics remain unchanged. Other external changes require a new context.

LOOM's v5 adapter retains its existing button/page meanings while consuming the same transport decisions. Its Play button and tempo controls do not change layout as part of this extraction. LOOM reports Continue unavailable because native Stop resets the sequencer; it reports no global transport position because the current public native API exposes per-track playheads only. Those existing per-track indicators remain available in the legacy pages.

For responsive relative turns, a completed synchronous parameter edit may advance the known authoritative revision within its current context lineage. This is allowed only while the selected instrument, complete descriptor/order/null-slot layout, instrument list and note domain remain unchanged. External changes, pending/rejected edits, navigation and descriptor changes invalidate that lineage before further edits. The adapter still checks every operation against its current revision. This preserves simultaneous/consecutive knob turns without permitting an old slot to edit a newly exposed parameter.

Skiff's editor and physical conventions are the sequence interaction baseline. The shared `SkiffSequenceEditor` drives LOOM's native SEQ pages through a synchronous `SequenceAdapter`: cursor/range navigation, individual note edits, stopped step entry, drum-grid editing and parameter locks. A pattern reference identifies the selected application-owned slot; note indices are valid only at their declared revision. Relative multi-step locks preserve each step's prior value and commit atomically. Sound and step-held assignments are distinct application-owned contexts. Navigation retires stale editing input and releases original auditions.

Native kind 7 carries bounded note geometry, independent note lengths, selection, keyboard masks, drum cells and playheads to the actual Skiff sequencer painters. Capability 4 and view kind 5 remain reserved. LOOM reads captured notes and timing back into the project. Apps advertise the recording and take operations they implement.

## 6. Proposed USB MIDI wire-6 binding

All numbers below are unsigned unless stated. `u14` and `u28` are little-endian groups of seven bits. All bytes between F0 and F7 are seven-bit safe. Text slots are space-padded printable ASCII; host formatting performs any transliteration/truncation. Reserved fields and bits must be zero.

Envelope: `F0 7D 43 41 06 command session:u14 serial:u14 payload F7`. The noncommercial manufacturer ID and existing CA signature are retained for this experimental profile. Session IDs are nonzero. Payload is at most 156 bytes; total frame size is at most 167 bytes. Unknown versions are rejected without takeover.

| Command | ID | Payload |
|---|---|---|
| HELLO | 1 | `coreMajor:u7, coreMinor:u7, audioMode:u7, namespaceLength:u7, namespace[1..64], capabilityCount:u7, capabilities[count]` |
| ACK | 2 | `acknowledgedCommand:u7, result:u7`; a successful HELLO ACK additionally contains `acceptedCount:u7, acceptedCapabilities[count]` |
| STATE | 3 | One complete generic view, as below |
| HEARTBEAT | 4 | Empty |
| BYE | 5 | Empty |
| INPUT | 6 | Fixed 24-byte physical scan, as below |
| PROBE | 7 | Empty; discovers support without claiming ownership |
| OFFER | 8 | `coreMajor:u7, coreMinor:u7, audioModes:u7, maxPayload:u14, capabilityCount:u7, capabilities[count]` |
| VIEW_PART | 9 | `total:u14, offset:u14, data[1..152]`. Complete native view at most 3072 bytes |
| VIEW_DELTA | 10 | `baseRevision:u14` followed by one or more `offset:u14, count:u7, data[count]` records. Requires capability 12 |
| PART_READY | 12 | Core 1.1 native UI only. Next contiguous VIEW_PART offset as u14, 1–3071. Session and serial identify the transfer. |

A capability record is four bytes: `id, major, minor, flags`. Flag bit 0 means required by the HELLO sender; other bits are zero. OFFER flags are zero. Records must have distinct IDs; count is at most 8. OFFER describes supported renderer/workflow capability versions, while HELLO selects the mutually supported application capabilities. The controller advertises IDs 1, 2, 3, 9, 11, 12 and 13 on page 0, plus 14 when panel MIDI is enabled. Page 1 advertises ID 15. All are version 1.0. Capability 11 requires accepted native UI capability 9. Structurally valid unknown OFFER capabilities are ignored unless requested and supported; they do not prevent discovery. HELLO acceptance still permits only supported capability IDs/versions requested by the application and advertised by the peer. HELLO must request instrument capability 1 at version 1.0 with the required flag. Structurally valid unknown optional records may appear in HELLO but are excluded; unsupported required records or versions reject HELLO. IDs reserved for future profiles cannot activate workflows. Namespace bytes are printable nonspace ASCII (33–126). Core major must be 1. OFFER advertises the supported minor. HELLO opts into minor 1 only for native UI when both peers support it. Other sessions use minor 0. Future minor values only enable behavior supported by both peers.

Discovery starts with an empty PROBE at serial 0. OFFER echoes the session ID and serial. From discovery minor 2, a client interested in extra capabilities can request a second OFFER with an empty PROBE at serial 1. Page 0 keeps its original records and page 1 advertises additional IDs including 15. Each page contains at most eight records. The core and route fields agree, and IDs are unique across pages. The stock host retries a missing second page after 250 ms. After 750 ms it can open with the base offer if the extra capability is optional. HELLO and its ACK still select at most eight capabilities across the advertised pages. Discovery minor 2 uses the existing minor-1 fragmented view transport. A v6 client waits at most 1500 ms for discovery. An active owner may still answer PROBE, but another session's HELLO receives BUSY and cannot renew the owner's lease.

HELLO uses serial 0 and is retried no faster than every 250 ms. Repeating the identical HELLO for the current owner is idempotent and returns the same accepted set without renewing its lease; changing its namespace, capabilities or route within a session is INVALID and requires a new session. ACK result values are OK=0, UNSUPPORTED=1, INVALID=2, BUSY=3. A successful HELLO ACK lists the exact accepted capability records (same four-byte layout, flags zero), at most eight, including every required capability. Unsupported optional records are absent. All other ACKs, including a failed HELLO, are exactly two bytes. The host validates the accepted set against OFFER and HELLO before sending state; missing required or unrequested accepted records invalidate negotiation. `capabilitiesAccepted` then reports an immutable set of accepted capability IDs before the first snapshot; this callback does not authorize audio. The first accepted STATE establishes the display; audio must remain gated until its ACK arrives. No capability may be used unless it was offered, requested and explicitly accepted. An accepted optional capability without its corresponding application adapter closes the shared controller instead of inventing application state.

STATE serial is its display revision; INPUT serial is an independent contiguous scan counter. Both wrap modulo 16384. Display revisions advance when `0 < (new-old) mod 16384 < 8192`. Duplicate state can be acknowledged without republishing; it cannot masquerade as new progress at the host. ACK echoes the acknowledged message serial. BYE closes immediately; stale-session frames have no effect.

The lease is 1500 ms. The host services display and lease traffic on a 34 ms timer. Native knob input can trigger an earlier display check, and fragment credits can advance an active native transfer. Generic displays and native sessions without capability 11 retain periodic full publication. For these sessions only an ACK for a newly sent state revision counts as host progress. Display revision and edit context are independent. The device renews its lease on valid state progress or a heartbeat. Disconnect, expired progress or required queue overflow releases ownership.

### INPUT (24 bytes)

| Offset | Field |
|---|---|
| 0 | Button held mask, u14 |
| 2 | Key held mask, u28; bit 27 must be zero |
| 6 | Seven encoder deltas, u14 each, biased by 8192 |
| 20 | Last actually drawn context token, u28 |

The physical mapping is inherited from the FM-1 input scan; application adapters never consume it directly. Context is chosen by the library and changes whenever a visible target, editable descriptor or externally changed object revision changes. Accepted synchronous relative edits follow the constrained lineage rule in section 5. A retired context token is never reused within its session; exhaustion requires a new session.

### Native UI publication

Capability 9 uses `VIEW_PART`, with one display revision shared by all chunks. Firmware stages contiguous chunks in a bounded buffer; it validates the complete view and swaps it atomically before acknowledging the final chunk. Input continues to name the last actually drawn context. No partial view authorizes input or audio activation. A required-native host rejects a legacy snapshot rather than silently degrading to generic cards. Device-owned menu input is identified by INPUT byte 5 bit 6; captured menu controls do not also edit application state. PLAY remains application-owned.

Between transfers the host polls a native panel at roughly 34 ms intervals and other idle-capable native views at roughly 100 ms intervals. With core 1.0 a transfer advances on eligible 34 ms ticks, up to 21 chunks. With core 1.1, the controller can send PART_READY after consuming a new, nonfinal VIEW_PART and releasing the receive slot. The host may immediately send one next part if the session, revision and next offset all match its current transfer. Ignore duplicate, stale and out-of-order credits. When a credit is missing for 100 ms the host retries the same part. The controller reissues its credit for an exact retry of the most recent nonfinal fragment. Ignored or duplicate bytes never renew progress. PART_READY neither publishes the view nor renews the lease or authorizes audio. The final part receives the usual ACK. With capability 11, once the latest native view is acknowledged and its full contents (including edit context) are unchanged, the host sends an occasional empty HEARTBEAT instead of retransmitting the screen. A successful ACK matching a fresh outstanding heartbeat serial renews the host lease once while idle. It never acknowledges a display update, enables audio or extends the deadline for an outstanding transfer. A change invalidates the pending heartbeat and starts publication of a new revision. An unacknowledged or interrupted view follows normal retry and timeout rules. Initial synchronization and reconnect always require a complete view and its ACK. See the [encoding and ownership rules](../../docs/native-ui-wire.md).

### Incremental native views

Optional capability 12 version 1.0 requires capabilities 9 and 11 in the accepted set. An app can request 12 as optional to work with older firmware. The first view of a session always uses complete publication.

For a small change on the same screen ID, variant and byte length, the host can send command 10 in one frame. The envelope serial identifies a new revision. The payload names the confirmed base revision followed by ordered, nonoverlapping replacement spans. Each count is 1–127. Offsets are absolute from the start of the kind-7 view. The entire payload must fit in 156 bytes. For example, changing only the low byte of the playhead uses six payload bytes, or 17 bytes including the SysEx envelope.

FM-1 accepts the update only for its current native revision while the session is active and the staging buffer is free. It checks all spans against the actual view length, applies them in scratch, validates the reconstructed view, then publishes it atomically. A successful ACK identifies command 10 and the new serial. An invalid or stale base, duplicate revision or illegal candidate leaves the published view and lease unchanged. An in-progress full transfer can return BUSY. Input continues to carry the last drawn context.

The host bases a delta on the last acknowledged view only. After a rejection or missing acknowledgement it sends its **current complete view** under a fresh revision, including if the app has since reverted the edit. A delayed ACK never authorizes a newer update. Different screens, changed lengths and patches too large for one frame use full publication. Idle heartbeat behavior and session expiry follow capability 11.

### STATE: first generic views

Common prefix (34 bytes): `kind:u7, flags:u7, context:u28, title:ASCII[16], subtitle:ASCII[12]`. Only flags bit 0 (operation pending) and bit 1 (visible error) are defined. Their strings carry user-facing meaning, not implementation identifiers. Types and exact payload lengths:

- **Parameter bank, kind 1, 138 bytes:** prefix + `bankIndex:u14, bankCount:u14, itemIndex:u14, itemCount:u14` + four 24-byte controls. Each control is `label[8], formattedValue[12], normalizedValue:u14, kind:u7, flags:u7`. Control kinds: absent=0, numeric=1, enum=2, toggle=3, action=4; flags bit 0 enables editing. An absent control has zero value/kind/flags and blank text. Counts are positive and indices less than counts. Missing instruments use visible error text and disabled controls without substituting a sound.
- **Overview, kind 2, 42 bytes:** prefix + `transportFlags:u7, tempoTenths:u14, position:u28, positionUnit:u7`. Transport flags: playing bit 0 and recording bit 1. Position unit 0 means unavailable (position must be zero), 1 milliseconds, 2 ticks. Without transport capability, flags/tempo/position/unit are all zero. The adapter supplies ticks-per-quarter through its capability descriptors; firmware prints the host-formatted subtitle instead of inferring musical semantics.
- **Operation status, kind 3, 35 bytes:** prefix + `result:u7`, using the semantic-result enum in section 4 in listed order, numbered 0–7. The library correlates this view to its phone-side receipt through context. Firmware displays status; it does not execute or retry operations.

### Mixer, kind 4

Kind 4 is exactly 138 bytes; the existing 34-byte generic prefix is unchanged. No existing view layout changes. Fields:

| Offset | Size | Meaning |
|---|---|---|
| 34 | 2 | Selected part's zero-based index, u14 |
| 36 | 2 | Total part count, u14, 1–16383 |
| 38 | 2 | First visible index, u14; multiple of 8 |
| 40 | 1 | Visible count, exactly min(8, total minus first) |
| 41 | 1 | Solo mode: 0 additive, 1 exclusive |
| 42 | 64 | Eight entries of eight bytes each |
| 106 | 12 | Selected level text, printable ASCII |
| 118 | 12 | Selected pan text, printable ASCII |
| 130 | 2 | Selected pan's normalized position, u14 |
| 132 | 1 | Selected controls: level bit 0, pan bit 1, mute bit 2, solo bit 3 |
| 133 | 5 | Reserved, all zero |

Each entry contains a five-byte printable ASCII name, normalized level u14, and flags: muted bit 0, soloed bit 1, enabled bit 2, level supported bit 3, mute supported bit 4, solo supported bit 5. Other bits are zero. An absent level is zero and cannot carry a value. Mute/solo values require their support bits. Unused entries are blank names, zero level and zero flags. The selected index must be inside the visible window. Selected level/mute/solo support bits must agree with that entry; pan support is defined only by the selected control mask. Unsupported level/pan text is blank; unsupported pan normalization is zero. Text for supported controls is host formatted and represents the actual value; normalization is only a display position. Prefix title identifies the selected part and subtitle shows its ordinal/count. The renderer uses native cards, labels and bounded part indicators.

A mixer view without explicitly accepted capability 3 is rejected. Unknown OFFER records do not activate workflows; unknown optional HELLO records are excluded and unknown required requests reject negotiation. ACK may accept only requested, offered and supported capabilities.

Kinds 5–6 remain reserved for sequence/note detail and selection-list views and must be rejected until specified. No arbitrary layout bytecode or application-specific renderer is allowed.

Parsing validates complete envelopes, exact per-message lengths, count/enum/range limits, version/session and context before publishing any state. Firmware keeps one current bounded view. The host keeps authoritative object lists and descriptor pages. The input receive queue has a maximum of 32 complete frames; overflow ends the session rather than dropping non-repeatable input. Replaceable pending display snapshots may be coalesced; releases and shutdown cannot be delayed behind a bulk list transfer.

## 7. Audio and failure policy

HELLO audio mode is 0 for explicitly selected phone output, 1 for FM-1 USB return. OFFER audio-mode bits 0 and 1 advertise these independently of control capabilities. A route not advertised is UNSUPPORTED. An instrument application is not required to implement transport, persistence or USB return.

The library reports synchronization and route choice; the application verifies/enables its actual output. USB return requires verified 44.1 kHz stereo routing and an acknowledged state before unmuting. Phone mode requires an explicit output choice; a failed USB route must never switch automatically to the speaker. Losing either the control lease or the chosen route stops companion playback, releases voices and flushes queued returned audio. Reconnection does not resume automatically.

## 8. Migration and acceptance

1. Preserve legacy v5 codec, session and LOOM workflow tests while moving shared transport into this module (current code increment).
2. Wire-6 codec/negotiation and generic firmware views now have a local implementation and separate host/runtime tests, including Java-to-C USB mailbox integration. Preserve the legacy version tests and qualify physical devices before deployment acceptance.
3. The first instrument semantic dispatcher/controller now handles notes, selection, parameters, retries, pending completion and status delivery. Extend it with the remaining specified capabilities and migrate LOOM mappings without losing its current workflows.
4. The minimal instrument-only `:instrument-demo` application has no sequencer. Its first phone-audio build received user-reported hardware-control and audio smoke acceptance. The subsequent output/lifecycle implementation adds an explicit phone/FM-1 choice and a foreground service; build/render checks do not qualify physical USB audio, route loss or screen-off operation.
5. Specify the remaining view/capability profiles around the complete LOOM phrase-edit/save/reopen loop before enabling them. Recording must include captured-state readback.

The wire-6 and instrument-reference increments extend the compatibility extraction. Full background/device qualification and LOOM's remaining shared workflows remain unfinished. Device-specific latency targets remain an explicit later acceptance decision.
