#!/usr/bin/env bash
set -euo pipefail
AUDIT_ROOT=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$AUDIT_ROOT/../../.." && pwd)
: "${KOTLIN_AUDIT_CLASSPATH:?Set Kotlin JVM compiler classpath}"
: "${KOTLIN_AUDIT_STDLIB:?Set Kotlin standard-library JAR path}"
RESULT_ROOT=$(mktemp -d)
trap 'rm -rf "$RESULT_ROOT"' EXIT
java -cp "$KOTLIN_AUDIT_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$KOTLIN_AUDIT_STDLIB" "$REPO/app/src/main/java/app/gamenative/powercontrol/autotuning/AdaptiveFpsCap.kt" "$REPO/app/src/main/java/app/gamenative/powercontrol/AdaptiveFpsCapController.kt" "$AUDIT_ROOT/Stubs.kt" "$AUDIT_ROOT/Log.kt" "$AUDIT_ROOT/Jsonl.kt" "$AUDIT_ROOT/Main.kt" -d "$RESULT_ROOT/cap"
java -cp "$RESULT_ROOT/cap:$KOTLIN_AUDIT_STDLIB" app.gamenative.powercontrol.MainKt
java -cp "$KOTLIN_AUDIT_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$KOTLIN_AUDIT_STDLIB" "$AUDIT_ROOT/LegacyAck.kt" -d "$RESULT_ROOT/legacy"
java -Djava.io.tmpdir="$RESULT_ROOT" -cp "$RESULT_ROOT/legacy:$KOTLIN_AUDIT_STDLIB" LegacyAckKt
