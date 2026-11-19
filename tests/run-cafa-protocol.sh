#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/cafa-host-tests
mkdir -p "$out"
java com.sun.tools.javac.Main -d "$out" companion/src/main/java/org/fm1/companion/legacy/{Protocol,CafaSession,LegacySurface}.java tests/CafaProtocolTest.java
java -cp "$out" CafaProtocolTest build/cafa-vectors
