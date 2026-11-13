# Retained upstream components

The GPL license for Loom and this fork is at `../LICENSE.md`. Separate upstream
component notices and embedded asset metadata are retained without modification.

- TinySoundFont: `composeApp/native/libs/tsf.h`, MIT license in its header.
- GeneralUser GS 1.471 SoundFont: original bundled binary and embedded license;
  a readable verbatim extraction is `GeneralUser_GS_LICENSE.txt`.
- Bundled wavetables/images/fonts: unchanged assets from the pinned official
  Loom repository. This fork does not claim new ownership or relicense them.
- Android/Kotlin/Compose/Oboe dependencies: fetched through the build's declared
  official repositories; preserve their own applicable licenses when redistributing.

CAFA protocol/session adaptation and new integration/test files use
`SPDX-License-Identifier: GPL-3.0-only`. Do not distribute only the APK without
the matching source and build instructions.
