#!/usr/bin/env bash
# Compiles core/ with kotlinc and checks that the Kotlin results equal the Python engine's saved results.
# Usage: scripts/run_parity.sh   (kotlinc must be on PATH, or set KOTLINC=/path/to/kotlinc)
set -euo pipefail
cd "$(dirname "$0")/.."
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
# the Check programs read their inputs from these fixed folders
mkdir -p /tmp/tt2 /tmp/tt3 /tmp/tt7
cp testdata/timing/details.txt testdata/timing/script.txt testdata/timing/caps.srt /tmp/tt2/
cp testdata/matching/scen.txt /tmp/tt3/
"$KOTLINC" core/src/main/kotlin/com/auto/core/*.kt core/src/test/kotlin/Check4.kt core/src/test/kotlin/Check5.kt core/src/test/kotlin/Check6.kt core/src/test/kotlin/Check7.kt core/src/test/kotlin/Check8.kt core/src/test/kotlin/Check9.kt core/src/test/kotlin/Check10.kt -d "$OUT" 2>&1 | grep -E "error" && exit 1 || true
CP="$OUT:$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")/../lib/kotlin-stdlib.jar"
java -cp "$CP" Check4Kt > "$OUT/kt.out"
sed 's/|None|/|null|/' testdata/matching/ref.out > "$OUT/ref.norm"
if diff "$OUT/ref.norm" "$OUT/kt.out" > "$OUT/diff.txt"; then echo "PICTURE MATCHING: identical to Python (7 scenarios)"; else echo "PICTURE MATCHING: DIFFERENT"; head -40 "$OUT/diff.txt"; exit 1; fi
java -cp "$CP" Check5Kt | head -1 | grep -q "scenes=12 full=true evil=false" && echo "ZIP end-to-end: OK" || { echo "ZIP end-to-end: FAILED"; exit 1; }
java -cp "$CP" Check6Kt testdata/v19 > "$OUT/kt6.out"
sed 's/|None|/|null|/' testdata/v19/ref.out > "$OUT/ref6.norm"
if diff "$OUT/ref6.norm" "$OUT/kt6.out" > "$OUT/diff6.txt"; then echo "v19 HEADINGS / PROMPT BLOCKS / BLACK SCREENS: identical to Python (8 scenarios)"; else echo "v19 scenarios: DIFFERENT"; head -40 "$OUT/diff6.txt"; exit 1; fi
# Timeline model + edit engine (Steps 29-36 core): self-checking, needs a generated test narration
(cd /tmp/tt7 && cp "$OLDPWD"/testdata/timing/{details.txt,script.txt,gen.py} . && python3 gen.py >/dev/null)
if java -cp "$CP" Check7Kt /tmp/tt7 > "$OUT/kt7.out"; then echo "TIMELINE EDIT ENGINE: $(grep -c '^PASS' "$OUT/kt7.out") checks passed"; else echo "TIMELINE EDIT ENGINE: FAILED"; grep FAIL "$OUT/kt7.out"; exit 1; fi
# DOCX reading, validation report, exact-boundary export plan
python3 scripts/make_check8_data.py /tmp/tt8
if java -cp "$CP" Check8Kt /tmp/tt8 > "$OUT/kt8.out"; then echo "DOCX / VALIDATION / EXPORT PLAN: $(grep -c '^PASS' "$OUT/kt8.out") checks passed"; else echo "DOCX / VALIDATION / EXPORT PLAN: FAILED"; grep FAIL "$OUT/kt8.out"; exit 1; fi
# Confidence scores, pause policy, project store (Steps 7, 26, 27)
if java -cp "$CP" Check9Kt /tmp/tt9 > "$OUT/kt9.out"; then echo "CONFIDENCE / PAUSE POLICY / PROJECT STORE: $(grep -c '^PASS' "$OUT/kt9.out") checks passed"; else echo "CONFIDENCE / PAUSE POLICY / PROJECT STORE: FAILED"; grep FAIL "$OUT/kt9.out"; exit 1; fi
# Backend client + verified timing (Steps 10-16). Needs python3 with fastapi/uvicorn/numpy + ffmpeg; reported as SKIPPED (not passed) otherwise.
if python3 -c "import fastapi, uvicorn, numpy" 2>/dev/null && command -v ffmpeg >/dev/null; then
  PORT=$((20000 + RANDOM % 20000)); rm -rf /tmp/tt10 && mkdir -p /tmp/tt10
  ffmpeg -v error -y -f lavfi -i "sine=frequency=220:duration=6" /tmp/tt10/a.wav
  python3 backend/tests/serve_fake.py "$PORT" /tmp/tt10/data & SRV=$!
  trap 'kill $SRV 2>/dev/null' EXIT
  for _ in $(seq 1 50); do curl -s "http://127.0.0.1:$PORT/v1/health" >/dev/null && break; sleep 0.2; done
  if java -cp "$CP" Check10Kt "$PORT" /tmp/tt10/a.wav /tmp/tt2 > "$OUT/kt10.out"; then echo "BACKEND CLIENT / VERIFIED TIMING: $(grep -c '^PASS' "$OUT/kt10.out") checks passed"; grep '^INFO' "$OUT/kt10.out" || true; else echo "BACKEND CLIENT / VERIFIED TIMING: FAILED"; grep FAIL "$OUT/kt10.out"; exit 1; fi
  kill $SRV 2>/dev/null || true
else echo "BACKEND CLIENT / VERIFIED TIMING: SKIPPED (python3 fastapi/uvicorn/numpy or ffmpeg missing)"; fi
