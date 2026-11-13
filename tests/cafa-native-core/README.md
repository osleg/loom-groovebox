# Actual native core smoke and exact-length regression

Run on a host with a C++17 compiler:

```sh
./tests/cafa-native-core/run.sh
```

`CXX=clang++` selects Clang; the default is `c++`. The script uses the upstream
smoke test's C++17/O2 configuration. It compiles the production
`AudioEngineCore.cpp` once, serially, then links and runs (with the standalone sequencer test first):

1. Standalone strict forward/reverse/ping-pong/jump/reset and skip tests.
2. The unchanged upstream `composeApp/native/tests/voice_mod_smoke.cpp`.
3. `pattern_length.cpp`, which restores 4, 17, 33, and 64-step lengths through the
   queued `setPatternLength` command, renders real audio, and verifies ordered
   steps, exact wrap boundaries, every expected step, at least two complete loops,
   and in-range indices. Runs cover four melodic tracks plus four FM-drum tracks,
   then four melodic tracks plus four analog-drum tracks, including all 16 drum
   lanes. A separate check triggers distinct MIDI notes on each of eight
   subtractive tracks and requires finite, nonzero audio.

The tests use the actual DSP/core, not mocks. All generated objects and binaries
are temporary and removed after the run. No Android SDK is required, and no
parallel compile jobs are launched.

## Scope and limitations

The regression uses 44.1 kHz to match CAFA. Every selected drum-lane getter is
compared to its actual sequencer index after rendering. A lane-specific skipped
step forces divergence from the track playhead and other lanes, proving selected
lane reporting and current (not previous-step) publication. Direct `getTracks()`
reads occur only after synchronous rendering in this single-threaded test, never
concurrently. Strict transition checks include the first cycle: no startup skip
is waived. These tests do not exercise Android JNI/Oboe,
hardware routing, USB timing, pitch-frequency accuracy, or all synth engines.
They check index bounds at every render block, not memory safety instrumentation.
