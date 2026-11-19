#!/usr/bin/env bash
# Compile the vendored portable models for the C integration harnesses.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
plaits="$here/../src/main/cpp/engines/plaits"
out=${1:?output library path}
: "${CXX:=$repo/.toolchains/skiff/host-cxx}"
mapfile -t models < <(rg --files -g '*.cc' "$plaits/thirdparty/eurorack" | LC_ALL=C sort)
"$CXX" -O2 -ffp-contract=off -std=c++11 -DTEST -fPIC -shared -Wl,--no-undefined \
    -I"$plaits/thirdparty/eurorack" -o "$out" "$plaits/plaits_bridge.cc" "${models[@]}" -lm
