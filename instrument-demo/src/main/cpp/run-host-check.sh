#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$here/../../../../../" && pwd)
: "${SKIFF_SOURCE_DIR:=$repo/.cache/skiff-2.5.1/expanded}"
: "${SKIFF_GENERATED_DIR:=$SKIFF_SOURCE_DIR/build/gen}"
: "${SKIFF_PROTOCOL_DIR:=$repo/skiff/firmware/src}"
: "${CC:=$repo/.toolchains/skiff/host-cc}"
: "${TMPDIR:=/tmp}"
work=$(mktemp -d "$TMPDIR/skiff-instrument.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
bash "$here/../../../tools/build-host-plaits.sh" "$work/libskiff_plaits.so"
CC="$CC" bash "$here/engines/dx386/build-host.sh" "$work/libdx386.a"
set -- "$here/engines/atek303/thirdparty/atek_voice.c" \
    "$here/engines/cz/thirdparty/cz_voice.c" \
    "$here/engines/dock_analog/thirdparty/arp_engine.c" "$here/engines/dock_analog/thirdparty/em_engine.c" \
    "$here/engines/dock_modeled/thirdparty/kalimba.c" "$here/engines/dock_modeled/thirdparty/monk.c" \
    "$here/engines/esq/thirdparty/esq_voice.c" \
    "$here/engines/obervave/thirdparty/obv_voice.c" \
    "$here/engines/opn/thirdparty/opn2.c" "$here/engines/opn/thirdparty/psg.c" \
    "$here/engines/sid/thirdparty/sid.c" \
    "$here/engines/synthex/thirdparty/sc_voice.c" \
    "$here/engines/x0x/thirdparty/drum808.c" "$here/engines/x0x/thirdparty/drum909.c" \
    "$work/libskiff_plaits.so"
while IFS= read -r source; do
    set -- "$@" "$here/$source"
done < "$here/vocal-sources.txt"
set -- "$@" "$work/libdx386.a"
for check in runtime_check skiff_view_check; do
    "$CC" -O2 -ffp-contract=off -std=gnu99 -w \
        -I"$SKIFF_SOURCE_DIR/web/emu" -I"$SKIFF_SOURCE_DIR/firmware/src" \
        -I"$SKIFF_GENERATED_DIR" -I"$SKIFF_PROTOCOL_DIR" \
        -o "$work/$check" "$here/$check.c" "$@" -lm -pthread
    "$work/$check"
done

stock="$repo/.cache/skiff-2.5.1/source"
"$CC" -O2 -ffp-contract=off -std=gnu99 -w \
    -I"$stock/web/emu" -I"$stock/firmware/src" -I"$stock/build/gen" -I"$SKIFF_PROTOCOL_DIR" \
    -o "$work/legacy-save" "$here/expanded_check.c" -lm
"$work/legacy-save" "$work/legacy-nor.bin"
"$CC" -O2 -ffp-contract=off -std=gnu99 -w \
    -I"$SKIFF_SOURCE_DIR/web/emu" -I"$SKIFF_SOURCE_DIR/firmware/src" \
    -I"$SKIFF_GENERATED_DIR" -I"$SKIFF_PROTOCOL_DIR" \
    -o "$work/expanded-check" "$here/expanded_check.c" "$@" -lm -pthread
"$work/expanded-check" "$work/legacy-nor.bin"
