#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/../.." && pwd)"
BUILD="$(mktemp -d "${TMPDIR:-/tmp}/loom-cafa-route.XXXXXX")"
trap 'rm -rf "$BUILD"' EXIT
# Copy the production implementation so quoted Core/Oboe includes resolve to
# the deliberately small substitutes, without altering production sources.
cp "$ROOT/composeApp/native/AudioEngine.cpp" "$ROOT/composeApp/native/AudioEngine.h" "$BUILD/"
cp -R "$HERE/stubs/." "$BUILD/"
"${CXX:-c++}" -std=c++17 -pthread -I"$BUILD" \
    "$BUILD/AudioEngine.cpp" "$HERE/test.cpp" -ldl -o "$BUILD/route-test"
"$BUILD/route-test"
