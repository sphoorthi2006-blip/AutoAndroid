#!/usr/bin/env bash
# Priority 3 (Section 4): Fail-closed single-check runner.
# Usage: scripts/dev_check.sh Check31 [args...]
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <CheckName> [args...]" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
JAVA_BIN="${JAVA_BIN:-java}"
CHECK_NAME="$1"
TEST_FILE="core/src/test/kotlin/${CHECK_NAME}.kt"

if ! command -v "$KOTLINC" >/dev/null 2>&1; then
  echo "ERROR: Kotlin compiler ('$KOTLINC') not found on PATH." >&2
  exit 127
fi
if [ ! -f "$TEST_FILE" ]; then
  echo "ERROR: Test file not found: $TEST_FILE" >&2
  exit 2
fi

KOTLINC_PATH="$(readlink -f "$(command -v "$KOTLINC")")"
STDLIB_JAR="${KOTLIN_STDLIB_JAR:-$(dirname "$KOTLINC_PATH")/../lib/kotlin-stdlib.jar}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Compile directly without `|| true` so any syntax/type error exits non-zero immediately
"$KOTLINC" core/src/main/kotlin/com/auto/core/*.kt "$TEST_FILE" -d "$OUT"

CP="$OUT:$STDLIB_JAR"
"$JAVA_BIN" -cp "$CP" "${CHECK_NAME}Kt" "${@:2}"
