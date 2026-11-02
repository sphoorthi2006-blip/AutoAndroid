#!/usr/bin/env bash
# Fast single-check runner: scripts/dev_check.sh Check15   (needs kotlinc on PATH or KOTLINC=...; prints only failures + the last line)
set -euo pipefail
cd "$(dirname "$0")/.."; K="${KOTLINC:-kotlinc}"; O="$(mktemp -d)"
"$K" core/src/main/kotlin/com/auto/core/*.kt "core/src/test/kotlin/$1.kt" -d "$O" 2>&1 | grep -E "error" && exit 1 || true
CP="$O:$(dirname "$(readlink -f "$(command -v "$K")")")/../lib/kotlin-stdlib.jar"
java -cp "$CP" "${1}Kt" "${@:2}" | grep -E "^(FAIL|ALL PASS|FAILED)"
