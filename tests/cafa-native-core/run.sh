#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../.." && pwd)"
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/loom-cafa-core.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
CXX="${CXX:-c++}"
"$CXX" -std=c++17 -O2 -I"$ROOT/composeApp/native" \
    "$HERE/sequencer_modes.cpp" -o "$BUILD/sequencer_modes"
"$BUILD/sequencer_modes"
# Compile serially and reuse the expensive actual core object. No mocks/stubs.
"$CXX" -std=c++17 -O2 -I"$ROOT/composeApp/native" -c \
    "$ROOT/composeApp/native/AudioEngineCore.cpp" -o "$BUILD/core.o"
"$CXX" -std=c++17 -O2 -I"$ROOT/composeApp/native" \
    "$ROOT/composeApp/native/tests/voice_mod_smoke.cpp" "$BUILD/core.o" -o "$BUILD/voice_mod_smoke"
"$BUILD/voice_mod_smoke"
"$CXX" -std=c++17 -O2 -I"$ROOT/composeApp/native" \
    "$HERE/pattern_length.cpp" "$BUILD/core.o" -o "$BUILD/pattern_length"
"$BUILD/pattern_length"
"$CXX" -std=c++17 -O2 -I"$ROOT/composeApp/native" \
    "$HERE/expanded_controls.cpp" "$BUILD/core.o" -o "$BUILD/expanded_controls"
"$BUILD/expanded_controls"
