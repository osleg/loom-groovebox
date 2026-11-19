#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
root=$(CDPATH= cd -- "$here/../../../../../../.." && pwd)
: "${CC:=$root/.toolchains/skiff/host-cc}"
: "${AR:=ar}"
dest=$1
work=$(mktemp -d "${TMPDIR:-/tmp}/skiff-dx386.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM

build_unit() {
    obj="$work/$(printf '%s' "$1" | tr / _).o"
    src="$here/$1"
    shift
    "$CC" -w -O2 -ffp-contract=off -fwrapv -fPIC -pthread -DDX_HOST=1 \
        -I"$here/thirdparty/espeak/include" -I"$here/thirdparty/espeak/src" -I"$here/thirdparty/dx" \
        "$@" -c "$src" -o "$obj"
}

while IFS= read -r unit; do
    build_unit "$unit" -include "$here/thirdparty/dx/espeak_port.h"
done < "$here/espeak-source-list.txt"
for unit in thirdparty/dx/speech.c thirdparty/dx/singing.c thirdparty/dx/speech_runtime.c dx386_runtime.c; do
    build_unit "$unit"
done
"$AR" rcs "$work/dx386.a" "$work"/*.o
mv "$work/dx386.a" "$dest"
