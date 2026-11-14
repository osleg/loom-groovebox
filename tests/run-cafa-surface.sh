#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KOTLINC:=kotlinc}"
: "${JUNIT_JAR:?Set JUNIT_JAR to JUnit 4.13.2}"
: "${HAMCREST_JAR:?Set HAMCREST_JAR to Hamcrest core 1.3}"
bash tests/run-cafa-protocol.sh
out=build/cafa-kotlin
mkdir -p "$out"
"$KOTLINC" composeApp/src/commonMain/kotlin/com/groovebox/{GrooveboxState,ScaleLogic}.kt \
 composeApp/src/commonMain/kotlin/com/groovebox/utils/Version.kt \
 app/src/main/java/com/groovebox/cafa/{CafaCatalog,CafaSurface}.kt \
 app/src/test/java/com/groovebox/cafa/CafaSurfaceTest.kt \
 -classpath "build/cafa-host-tests:$JUNIT_JAR" -include-runtime -d "$out/tests.jar"
java -cp "$out/tests.jar:build/cafa-host-tests:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore com.groovebox.cafa.CafaSurfaceTest
