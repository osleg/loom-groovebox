#!/bin/sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo=$(CDPATH= cd -- "$here/../../../" && pwd)
cpp="$here/../src/main/cpp"
protocol="$repo/loom-groovebox/companion/src/main/java/org/fm1/companion/protocol"
: "${SKIFF_SOURCE_DIR:=$repo/.cache/skiff-2.5.1/expanded}"
: "${SKIFF_GENERATED_DIR:=$SKIFF_SOURCE_DIR/build/gen}"
: "${SKIFF_PROTOCOL_DIR:=$repo/skiff/firmware/src}"
: "${CC:=$repo/.toolchains/skiff/host-cc}"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
bash "$here/build-host-plaits.sh" "$tmp/libskiff_plaits.so"
CC="$CC" bash "$cpp/engines/dx386/build-host.sh" "$tmp/libdx386.a"
set --
while IFS= read -r source; do
    set -- "$@" "$cpp/$source"
done < "$cpp/vocal-sources.txt"
"$CC" -O2 -ffp-contract=off -std=gnu99 -w \
    -I"$SKIFF_SOURCE_DIR/web/emu" -I"$SKIFF_SOURCE_DIR/firmware/src" \
    -I"$SKIFF_GENERATED_DIR" -I"$SKIFF_PROTOCOL_DIR" \
    -o "$tmp/paired-host" "$cpp/paired_host.c" \
    "$cpp/engines/atek303/thirdparty/atek_voice.c" \
    "$cpp/engines/cz/thirdparty/cz_voice.c" \
    "$cpp/engines/dock_analog/thirdparty/arp_engine.c" "$cpp/engines/dock_analog/thirdparty/em_engine.c" \
    "$cpp/engines/dock_modeled/thirdparty/kalimba.c" "$cpp/engines/dock_modeled/thirdparty/monk.c" \
    "$cpp/engines/esq/thirdparty/esq_voice.c" \
    "$cpp/engines/obervave/thirdparty/obv_voice.c" \
    "$cpp/engines/opn/thirdparty/opn2.c" "$cpp/engines/opn/thirdparty/psg.c" \
    "$cpp/engines/sid/thirdparty/sid.c" \
    "$cpp/engines/synthex/thirdparty/sc_voice.c" \
    "$cpp/engines/x0x/thirdparty/drum808.c" "$cpp/engines/x0x/thirdparty/drum909.c" \
    "$@" "$tmp/libskiff_plaits.so" "$tmp/libdx386.a" -lm -pthread
"$CC" -O1 -std=gnu99 -w -DFELUCCA_CONTROLLER=1 -DFELUCCA_UART=1 -DFELUCCA_SLICE=0 -DHOST_DIAG_PEER=1 \
    -I"$repo/skiff/build/gen" -I"$repo/skiff/firmware/src" \
    -o "$tmp/controller-peer" "$repo/skiff/tests/open_link_peer.c" -lm
java com.sun.tools.javac.Main -d "$tmp" "$protocol/Wire6.java" "$protocol/NativeUi.java" \
    "$protocol/OpenSession.java" "$here/PairedSessionCheck.java"
java -cp "$tmp" PairedSessionCheck "$tmp/paired-host" "$tmp/controller-peer"
