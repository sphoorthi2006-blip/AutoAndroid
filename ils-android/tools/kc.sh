#!/usr/bin/env bash
# Compile and run pure-Kotlin core code with the Kotlin compiler that ships inside Gradle (no network needed).
L=/opt/gradle-8.14.3/lib
CP="$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar"
OUT=${KC_OUT:-/tmp/ils-build}
mkdir -p "$OUT"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -cp "$L/kotlin-stdlib-2.0.21.jar" -d "$OUT" -jvm-target 17 "$@"
