# ATEK303 Android voice bridge

`thirdparty/atek_voice.c` and `.h` are unmodified copies from
[animatek/FM1-Animatek](https://github.com/animatek/FM1-Animatek), commit
`8934f53281b469b821949217a682e2adb934c0db`, path `atek303/app/dsp/`.
They carry GPL-3.0-or-later notices and contain portions derived from Robin
Schmidt's Open303 under MIT. The donor's full GPL license and Open303 MIT notice
are preserved in `thirdparty/LICENSE-GPL-3.0.txt` and
`thirdparty/LICENSE-open303.txt`. The donor commit did not track the
`fastmath.h` named by `atek_voice.c`; our small `thirdparty/fastmath.h` binds
those calls to the platform C math library. It is marked as local code, rather
than a donor copy. `eng_atek303.c` is the Skiff adapter. No factory bank,
sequencer, firmware code, or extra Android library is imported.

The engine has sixteen independent voice slots per part, in the shared part
memory on all eight tracks. Each slot initializes on its first note, then
retains circuit state until the engine switch clears the shared part memory.
Skiff's gate controls the donor gate; a new gated note slides in legato or
when GLIDE is active, while a fresh non-slide attack retriggers. The
sequencer's accent arrives as velocity 127, which the bridge maps to the donor
accent input using the existing velocity threshold above 110. The donor's
internal decay and release are heard inside Skiff's normal track ADSR; Skiff
may end a voice before the donor's long tail. Skiff pitch bend and tuning
feed the donor pitch wheel per block; the donor handles note glide. E1 cutoff receives Skiff's
filter modulation. The fixed donor sample rate is 44.1 kHz, matching Skiff's
engine rate; Skiff's existing output route handles Android device conversion.

The four presets in `eng_atek303.c` are original settings written for this
integration, not copied from the donor's factory bank. E0-E7 expose donor
wave, cutoff, resonance, envelope modulation, decay, accent depth, slide time,
and filter drive respectively. E1 and E2 are the standard cutoff and resonance
MIDI CC destinations.

Build integration: include `engines/atek303/eng_atek303.c` from `engines.c`
before the part unions, add `atek_part_t atek;` to both union types, append
`ENG_ATEK303` at index 15, compile `engines/atek303/thirdparty/atek_voice.c`
exactly once, and link the platform math library (`m`).
