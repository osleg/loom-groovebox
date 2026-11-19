#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$here/../../../../../" && pwd)
: "${SKIFF_SOURCE_DIR:=$repo/.cache/skiff-2.5.1/source}"
: "${SKIFF_GENERATED_DIR:=$SKIFF_SOURCE_DIR/build/gen}"
: "${SKIFF_PROTOCOL_DIR:=$repo/skiff/firmware/src}"
: "${CC:=$repo/.toolchains/skiff/host-cc}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
for mode in stock companion; do
    flags=
    if [ "$mode" = stock ]; then flags=-DSKIFF_ORACLE=1; fi
    "$CC" -O2 -ffp-contract=off -std=gnu99 -w $flags \
        -I"$here" -I"$SKIFF_SOURCE_DIR/web/emu" -I"$SKIFF_SOURCE_DIR/firmware/src" \
        -I"$SKIFF_GENERATED_DIR" -I"$SKIFF_PROTOCOL_DIR" \
        -o "$tmp/$mode" "$here/release_oracle.c" -lm
    "$tmp/$mode" > "$tmp/$mode.txt"
done
if ! diff -u "$tmp/stock.txt" "$tmp/companion.txt"; then
    echo "Stock and companion performance diverged" >&2
    exit 1
fi
tail -2 "$tmp/stock.txt"
