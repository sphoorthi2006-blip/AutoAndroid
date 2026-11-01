#!/usr/bin/env bash
# Compiles core/ with kotlinc and checks that the Kotlin results equal the Python engine's saved results.
# Usage: scripts/run_parity.sh   (kotlinc must be on PATH, or set KOTLINC=/path/to/kotlinc)
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
# the Check programs read their inputs from these fixed folders
mkdir -p /tmp/tt2 /tmp/tt3
cp testdata/timing/details.txt testdata/timing/script.txt testdata/timing/caps.srt /tmp/tt2/
cp testdata/matching/scen.txt /tmp/tt3/
"$KOTLINC" core/src/main/kotlin/com/auto/core/*.kt core/src/test/kotlin/Check4.kt core/src/test/kotlin/Check5.kt -d "$OUT" 2>&1 | grep -E "error" && exit 1 || true
CP="$OUT:$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
java -cp "$CP" Check4Kt > "$OUT/kt.out"
sed 's/|None|/|null|/' testdata/matching/ref.out > "$OUT/ref.norm"
if diff "$OUT/ref.norm" "$OUT/kt.out" > "$OUT/diff.txt"; then echo "PICTURE MATCHING: identical to Python (7 scenarios)"; else echo "PICTURE MATCHING: DIFFERENT"; head -40 "$OUT/diff.txt"; exit 1; fi
java -cp "$CP" Check5Kt | head -1 | grep -q "scenes=10 full=true evil=false" && echo "ZIP end-to-end: OK" || { echo "ZIP end-to-end: FAILED"; exit 1; }
