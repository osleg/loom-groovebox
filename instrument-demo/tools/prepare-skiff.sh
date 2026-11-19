#!/usr/bin/env bash
# Build the phone's stock sample and UI tables independently of the controller build.
set -euo pipefail
prepare_tools=$(cd "$(dirname "$0")" && pwd)
repo=${1:?path to Skiff checkout}
cache=${2:?path to build cache on this machine}
python=${3:-python3}
rev=fe387231f7b7cb98823f19d8d4f99fee4fcd3792
mkdir -p "$cache"
exec 9>"$cache/generate.lock"
flock 9
if [[ ! -f "$cache/source/.stock-revision" ]]; then
    staging=$(mktemp -d "$cache/export.XXXXXX")
    trap 'rm -rf "$staging"' EXIT
    git -C "$repo" archive "$rev" firmware tests/hostsim.c tests/c1_host.h web/emu web/fukiai.ttf web/FUKIAI-LICENSE.txt tools assets LICENSE LICENSES LICENSING.md |
        tar -xf - -C "$staging"
    echo "$rev" > "$staging/.stock-revision"
    mv -T "$staging" "$cache/source"
    trap - EXIT
fi
cd "$cache/source"
test "$(cat .stock-revision)" = "$rev"
if [[ ! -f build/gen/.stock-generated ]]; then
    FELUCCA_FACTORY_SAMPLES=1 "$python" -c 'import sys; sys.path.insert(0, "tools"); import build; build.generate()'
    if ! grep -Eq 'SMP_DATA\[[1-9][0-9]{3,}\]' build/gen/felucca_samples.h; then
        echo "Skiff 2.5.1 factory samples are missing" >&2
        exit 1
    fi
    touch build/gen/.stock-generated
fi
"$python" "$prepare_tools/capture-columns.py" firmware/src/ui_draw.c build/gen/skiff_stock_columns.inc

patches=(
    "$prepare_tools/skiff-instrument-expansion.patch"
    "$prepare_tools/skiff-engine-registry.patch"
    "$prepare_tools/skiff-engine-picker.patch"
    "$prepare_tools/skiff-engine-favorites.patch"
    "$prepare_tools/skiff-donor-engines.patch"
    "$prepare_tools/skiff-sampling.patch"
    "$prepare_tools/skiff-vocal-engines.patch"
)
engines="$prepare_tools/../src/main/cpp/engines"
expanded="$cache/expanded"
digest=$({
    sha256sum "${patches[@]}"
    find "$engines" -type f -print0 | sort -z | xargs -0 sha256sum
} | sha256sum)
digest=${digest%% *}
if [[ ! -f "$expanded/.expansion" || "$(cat "$expanded/.expansion")" != "$digest" ]]; then
    staging=$(mktemp -d "$cache/expand.XXXXXX")
    trap 'rm -rf "$staging"' EXIT
    cp -a firmware web tests build "$staging/"
    for expansion in "${patches[@]}"; do
        patch --batch --forward --directory "$staging" -p1 < "$expansion"
    done
    cp -a "$engines"/* "$staging/firmware/src/"
    "$python" "$prepare_tools/capture-columns.py" "$staging/firmware/src/ui_draw.c" "$staging/build/gen/skiff_stock_columns.inc"
    echo "$digest" > "$staging/.expansion"
    rm -rf "$expanded"
    mv -T "$staging" "$expanded"
    trap - EXIT
fi
