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
 companion/src/main/java/org/fm1/companion/control/{ParameterDescriptor,ParameterBanks}.kt \
 companion/src/main/java/org/fm1/companion/instrument/{InstrumentAdapter,OperationDispatcher}.kt \
 companion/src/main/java/org/fm1/companion/parts/{PartsAdapter,PartsControls}.kt \
 companion/src/main/java/org/fm1/companion/sequence/{SequenceAdapter,SkiffSequenceEditor}.kt \
 companion/src/main/java/org/fm1/companion/transport/{TransportAdapter,TransportControls}.kt \
 app/src/main/java/com/groovebox/cafa/{CafaCatalog,CafaSurface,LoomParameterAdapter,LoomTransportAdapter,LoomPartsAdapter,LoomSequenceAdapter}.kt \
 app/src/test/java/com/groovebox/cafa/{CafaSurfaceTest,LoomTransportAdapterTest,LoomPartsAdapterTest,LoomSequenceAdapterTest}.kt \
 -classpath "build/cafa-host-tests:$JUNIT_JAR" -include-runtime -d "$out/tests.jar"
java -cp "$out/tests.jar:build/cafa-host-tests:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore com.groovebox.cafa.CafaSurfaceTest com.groovebox.cafa.LoomTransportAdapterTest com.groovebox.cafa.LoomPartsAdapterTest com.groovebox.cafa.LoomSequenceAdapterTest
