# Android build verification

The fork builds using the pinned toolchain documented in ANDROID_BUILD_CAFA.md.
Checks executed using scripts/build-android.sh and scripts/verify-apk.sh:

- Shared Android unit tests: 13 passed (6 MIDI clock input, 4 MIDI clock output,
  3 grid cycle); no failures, errors or skipped tests.
- App CAFA surface unit tests: 11 passed; no failures, errors or skipped tests.
- Actual-model wire fixtures: 51 snapshots plus hello/bye exported by the app test.
- Android lint: 0 errors, 32 warnings, 1 hint. No lint suppression or baseline
  was introduced. Remaining warnings concern dependency updates, existing
  resources, compatibility/style recommendations, and synchronous persistence
  of the CAFA safety hold (intentional so a crash cannot lose that hold).
- Native code compiled for arm64-v8a, armeabi-v7a, x86, and x86_64.
- Package and label verified: com.groovebox.cafa / Loom CAFA.
- Version verified: 3.2.19-cafa.1, version code 389.
- Debug APK signature verified (APK Signature Scheme v2).
- ZIP alignment verified; all four ABIs contain the native engine and C++ runtime.
- All four CAFA JNI entry points are exported in every ABI.
- No signing keystore is present in the APK. Signing keys remain outside source.

The initial environment had only a JRE; a full Eclipse Temurin JDK was installed
outside the source distribution. SDK/cache data was moved off memory-backed /tmp
and Gradle was limited to two workers / 2 GB to avoid resource exhaustion.
Upstream build blockers were fixed directly: missing version-check script,
malformed density icon paths, unchecked recording permission, unimplemented TV
feature declaration, and an API31 splash attribute in unqualified resources.

This is build/static/unit verification, not a physical-device acceptance test.
No APK was installed on a device. Real USB audio routing, audible synthesis,
USB reconnect, controller LED timing, Android lifecycle and live MIDI latency
remain to be validated on the actual device and controller.

Detailed logs are under docs/verification. Rebuilding after any source change
requires running both verification scripts again; artifact hashes identify a
particular build, not every rebuild with a different debug key or toolchain.


Verified APK SHA-256: `cd48c384d3ce070fdade52f68bd4993cc3fa9add9d4778bea3f2f7e0d0e8ad74`.
