#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
peer=${1:?Pass the separately built matching firmware tests/cafa_link_peer.c executable}
tests/run-cafa-protocol.sh
java com.sun.tools.javac.Main -cp build/cafa-host-tests -d build/cafa-host-tests tests/FirmwareLinkTests.java
ASAN_OPTIONS=${ASAN_OPTIONS:-detect_leaks=0} java -cp build/cafa-host-tests FirmwareLinkTests "$peer"
