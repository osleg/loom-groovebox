#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/cafa-host-tests
mkdir -p "$out"
java com.sun.tools.javac.Main -d "$out" app/src/main/java/com/groovebox/cafa/{Protocol,CafaSession}.java tests/CafaProtocolTest.java
java -cp "$out" CafaProtocolTest build/cafa-vectors
