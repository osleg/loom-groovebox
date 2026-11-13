# Loom CAFA Android build

Source fork baseline: `9937566f052fcceeceba1e8b0004a70e1b557adc`.
License: GPL-3.0, retained in `LICENSE.md`. Distribute matching complete source with the APK.

## Toolchain

- Eclipse Temurin JDK 21.0.13+9 (full JDK including javac, not a JRE).
- Gradle 8.13, via checked-in wrapper with official distribution SHA-256.
- Android Gradle Plugin 8.13.2; Kotlin 1.9.0; Compose Multiplatform 1.5.11.
- Android SDK platform 35 revision 2, build-tools 35.0.0, platform-tools 37.0.1.
- NDK 27.2.12479018; CMake 3.22.1.

Install Android SDK command-line tools from Google's official Android developer site.
Run sdkmanager for `platforms;android-35`, `build-tools;35.0.0`,
`ndk;27.2.12479018`, and `cmake;3.22.1`, reviewing the SDK licenses.
Set `ANDROID_HOME` to the SDK directory. Alternatively use local.properties
with `sdk.dir=/absolute/path/to/sdk` (do not distribute local.properties).

Run `scripts/build-android.sh`, then `scripts/verify-apk.sh`.
The build runs debug unit tests, Android lint, and assembles the debug APK.
The output is `app/build/outputs/apk/debug/app-debug.apk`.
The APK has the separate application ID `com.groovebox.cafa`, label `Loom CAFA`,
and retains upstream source/JNI namespaces to preserve native bindings.
Debug signing material belongs outside the repository/distribution. Never
include `.android/debug.keystore`, any private keystore, or SDK/Gradle caches.

Version fields are validated without automatically mutating release versions.
Change tracked version.properties explicitly before a new release.
Upstream density icon JPEGs were moved into valid resource directories without
changing their bytes. The missing upstream check_version.py dependency was
replaced with a self-contained build-local validation task, not a test skip.

A successful build is not a device audio or MIDI test. Validate device launch,
FM sound, saved projects, and USB MIDI on physical hardware separately.

The Android fork reports version `3.2.19-cafa.1` (code 389); upstream
version.properties remains `3.2.19` so its source-version UI stays traceable.
APK verification also checks all four CAFA JNI entry points in every ABI.
