# Portable CAFA output routing regression test

Run from the checkout on Linux with a C++17 compiler:

```sh
./tests/cafa-audio-route/run.sh
```

Set `CXX` to choose a compiler. The script copies the **current production**
`AudioEngine.cpp` and header into a temporary directory, builds against the small
Oboe, Android, and synthesis-core test doubles here, runs assertions, and deletes
all temporary build output. No Android SDK, audio device, or third-party download
is required. No binaries are checked in.

Coverage:

- Ordinary audio is audible before CAFA selection and after explicit exit.
- Positive device selection starts muted and reports the actual running route.
- Unmute/mute, actual-device mismatch, and route mismatch latching.
- Enabled-state getter rejects muted, stopped, errored, or mismatched routes.
- Error callbacks invalidate the route and prevent accidental re-enable.
- Lifecycle stop followed by enable/start remains muted.
- Device 0 does not open a default speaker stream.
- Wrong output device, wrong sample rate, or open failure fails closed.
- A mute arriving during render discards the output block.

## Limits

This is a deterministic **control-flow unit test**, not an Android integration
test. The doubles do not model Oboe threading, asynchronous start/close, OS audio
routing, format conversion, or hardware-buffered audio. A pass does not prove
absence of all concurrency races or speaker leakage on a physical device. Build
the Android target against real Oboe and test repeated attach/detach, lifecycle
pause/resume, routing changes, and playback under load on the actual CAFA setup.
Previously submitted hardware audio buffers cannot be retracted by the software
mute gate. The AAudio device pin and Android disconnect handling must be verified
on the target OS/device.
