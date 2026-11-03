#!/usr/bin/env bash
# Priority 3 & Performance Directive Phase 6: Fail-Closed Parity & Verification Runner
# Compiles core/ and Check4..Check32 with kotlinc without any pipeline exit-code masking (`|| true` removed).
# Verifies kotlinc, java, kotlin-stdlib.jar, and every explicit source/test file before compiling.
# Preserves per-suite logs and prints a final PASSED / FAILED / SKIPPED summary.
set -euo pipefail

cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-kotlinc}"
JAVA_BIN="${JAVA_BIN:-java}"
LOG_DIR="${PARITY_LOG_DIR:-docs/logs/parity_suites}"
mkdir -p "$LOG_DIR"

echo "=== AutoAndroid Fail-Closed Parity Runner ==="
echo "Date (UTC): $(date -u +"%Y-%m-%dT%H:%M:%SZ")"

# 1. Verify kotlinc and java binaries before running
if ! command -v "$KOTLINC" >/dev/null 2>&1; then
  echo "ERROR: Kotlin compiler ('$KOTLINC') not found on PATH. Install Kotlin 2.0.21 or set KOTLINC=/path/to/kotlinc." >&2
  exit 127
fi
if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "ERROR: Java runtime ('$JAVA_BIN') not found on PATH." >&2
  exit 127
fi

KOTLINC_PATH="$(readlink -f "$(command -v "$KOTLINC")")"
STDLIB_JAR="${KOTLIN_STDLIB_JAR:-$(dirname "$KOTLINC_PATH")/../lib/kotlin-stdlib.jar}"

echo "Kotlin compiler: $KOTLINC_PATH"
"$KOTLINC" -version 2>&1 || true
echo "Java runtime: $(command -v "$JAVA_BIN")"
"$JAVA_BIN" -version 2>&1 | head -n 2 || true

# 2. Explicit CORE_SOURCES and TEST_SOURCES lists (no wildcard expansion that could silently miss a deleted file)
CORE_SOURCES=(
  "core/src/main/kotlin/com/auto/core/Align.kt"
  "core/src/main/kotlin/com/auto/core/AudioMix.kt"
  "core/src/main/kotlin/com/auto/core/AudioPauses.kt"
  "core/src/main/kotlin/com/auto/core/AudioStream.kt"
  "core/src/main/kotlin/com/auto/core/BackendClient.kt"
  "core/src/main/kotlin/com/auto/core/Bundle.kt"
  "core/src/main/kotlin/com/auto/core/CaptionPipeline.kt"
  "core/src/main/kotlin/com/auto/core/CaptionTools.kt"
  "core/src/main/kotlin/com/auto/core/Captions.kt"
  "core/src/main/kotlin/com/auto/core/ClickTrack.kt"
  "core/src/main/kotlin/com/auto/core/Confidence.kt"
  "core/src/main/kotlin/com/auto/core/DesignTokens.kt"
  "core/src/main/kotlin/com/auto/core/Details.kt"
  "core/src/main/kotlin/com/auto/core/Diagnostics.kt"
  "core/src/main/kotlin/com/auto/core/Effects.kt"
  "core/src/main/kotlin/com/auto/core/ExportOptions.kt"
  "core/src/main/kotlin/com/auto/core/ExportPlan.kt"
  "core/src/main/kotlin/com/auto/core/ExportTracker.kt"
  "core/src/main/kotlin/com/auto/core/Json.kt"
  "core/src/main/kotlin/com/auto/core/LayerTools.kt"
  "core/src/main/kotlin/com/auto/core/Matching.kt"
  "core/src/main/kotlin/com/auto/core/MediaLibrary.kt"
  "core/src/main/kotlin/com/auto/core/OverlayStyle.kt"
  "core/src/main/kotlin/com/auto/core/Pipeline.kt"
  "core/src/main/kotlin/com/auto/core/ProjectPrefs.kt"
  "core/src/main/kotlin/com/auto/core/ProjectStore.kt"
  "core/src/main/kotlin/com/auto/core/RenderCaps.kt"
  "core/src/main/kotlin/com/auto/core/StreamingImageWriter.kt"
  "core/src/main/kotlin/com/auto/core/StudioTools.kt"
  "core/src/main/kotlin/com/auto/core/TextAnim.kt"
  "core/src/main/kotlin/com/auto/core/TextFiles.kt"
  "core/src/main/kotlin/com/auto/core/TimeText.kt"
  "core/src/main/kotlin/com/auto/core/Timeline.kt"
  "core/src/main/kotlin/com/auto/core/UpscaleEngine.kt"
  "core/src/main/kotlin/com/auto/core/Validation.kt"
  "core/src/main/kotlin/com/auto/core/Workflow.kt"
  "core/src/main/kotlin/com/auto/core/WorkspaceMode.kt"
  "core/src/main/kotlin/com/auto/core/ZipSafety.kt"
)

TEST_SOURCES=(
  "core/src/test/kotlin/Check4.kt"
  "core/src/test/kotlin/Check5.kt"
  "core/src/test/kotlin/Check6.kt"
  "core/src/test/kotlin/Check7.kt"
  "core/src/test/kotlin/Check8.kt"
  "core/src/test/kotlin/Check9.kt"
  "core/src/test/kotlin/Check10.kt"
  "core/src/test/kotlin/Check11.kt"
  "core/src/test/kotlin/Check12.kt"
  "core/src/test/kotlin/Check13.kt"
  "core/src/test/kotlin/Check14.kt"
  "core/src/test/kotlin/Check15.kt"
  "core/src/test/kotlin/Check16.kt"
  "core/src/test/kotlin/Check17.kt"
  "core/src/test/kotlin/Check18.kt"
  "core/src/test/kotlin/Check19.kt"
  "core/src/test/kotlin/Check20.kt"
  "core/src/test/kotlin/Check21.kt"
  "core/src/test/kotlin/Check22.kt"
  "core/src/test/kotlin/Check23.kt"
  "core/src/test/kotlin/Check24.kt"
  "core/src/test/kotlin/Check25.kt"
  "core/src/test/kotlin/Check26.kt"
  "core/src/test/kotlin/Check27.kt"
  "core/src/test/kotlin/Check28.kt"
  "core/src/test/kotlin/Check29.kt"
  "core/src/test/kotlin/Check30.kt"
  "core/src/test/kotlin/Check31.kt"
  "core/src/test/kotlin/Check32.kt"
)

for f in "${CORE_SOURCES[@]}" "${TEST_SOURCES[@]}"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: Expected source file missing: $f" >&2
    exit 2
  fi
done
echo "Verified ${#CORE_SOURCES[@]} core files and ${#TEST_SOURCES[@]} test suites exist."

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# 3. Direct kotlinc compilation — NO `|| true` and NO grep exit-code masking
COMPILE_LOG="$LOG_DIR/compile.log"
echo "Compiling ${#CORE_SOURCES[@]} core files and ${#TEST_SOURCES[@]} test files..."
set +e
"$KOTLINC" "${CORE_SOURCES[@]}" "${TEST_SOURCES[@]}" -d "$OUT" >"$COMPILE_LOG" 2>&1
COMPILE_EXIT=$?
set -e

if [ "$COMPILE_EXIT" -ne 0 ]; then
  echo "COMPILE FAILED with exit code $COMPILE_EXIT. Compiler output:" >&2
  cat "$COMPILE_LOG" >&2
  exit "$COMPILE_EXIT"
fi

if [ ! -f "$STDLIB_JAR" ]; then
  echo "ERROR: kotlin-stdlib.jar not found at '$STDLIB_JAR'. Set KOTLIN_STDLIB_JAR=/path/to/kotlin-stdlib.jar." >&2
  exit 3
fi

CP="$OUT:$STDLIB_JAR"

# Prepare fixed test fixture directories
mkdir -p /tmp/tt2 /tmp/tt3 /tmp/tt7
cp testdata/timing/details.txt testdata/timing/script.txt testdata/timing/caps.srt /tmp/tt2/
cp testdata/matching/scen.txt /tmp/tt3/

PASSED=0
FAILED=0
SKIPPED=0

run_suite() {
  local name="$1"
  local log_file="$LOG_DIR/${name}.log"
  shift
  set +e
  "$@" >"$log_file" 2>&1
  local rc=$?
  set -e
  if [ "$rc" -ne 0 ] || grep -q '^FAIL' "$log_file"; then
    echo "[FAIL] $name (exit=$rc) — see $log_file" >&2
    cat "$log_file" >&2
    FAILED=$((FAILED + 1))
    echo "=== SUMMARY: PASSED=$PASSED FAILED=$FAILED SKIPPED=$SKIPPED ===" >&2
    exit 1
  fi
  local pass_count
  pass_count="$(grep -c '^PASS' "$log_file" || true)"
  echo "[PASS] $name (${pass_count} checks) -> $log_file"
  PASSED=$((PASSED + 1))
}

# Check4: Picture matching parity vs Python reference (7 scenarios)
"$JAVA_BIN" -cp "$CP" Check4Kt > "$LOG_DIR/Check4.out"
sed 's/|None|/|null|/' testdata/matching/ref.out > "$OUT/ref.norm"
if diff -u "$OUT/ref.norm" "$LOG_DIR/Check4.out" > "$LOG_DIR/Check4.diff"; then
  echo "[PASS] Check4 PICTURE MATCHING: identical to Python (7 scenarios)"
  PASSED=$((PASSED + 1))
else
  echo "[FAIL] Check4 PICTURE MATCHING: DIFFERENT" >&2
  head -40 "$LOG_DIR/Check4.diff" >&2
  FAILED=$((FAILED + 1))
  exit 1
fi

# Check5: ZIP end-to-end
"$JAVA_BIN" -cp "$CP" Check5Kt > "$LOG_DIR/Check5.out"
if head -1 "$LOG_DIR/Check5.out" | grep -q "scenes=12 full=true evil=false"; then
  echo "[PASS] Check5 ZIP end-to-end: OK"
  PASSED=$((PASSED + 1))
else
  echo "[FAIL] Check5 ZIP end-to-end: FAILED" >&2
  cat "$LOG_DIR/Check5.out" >&2
  FAILED=$((FAILED + 1))
  exit 1
fi

# Check6: v19 headings / prompt blocks / black screens vs Python reference (8 scenarios)
"$JAVA_BIN" -cp "$CP" Check6Kt testdata/v19 > "$LOG_DIR/Check6.out"
sed 's/|None|/|null|/' testdata/v19/ref.out > "$OUT/ref6.norm"
if diff -u "$OUT/ref6.norm" "$LOG_DIR/Check6.out" > "$LOG_DIR/Check6.diff"; then
  echo "[PASS] Check6 v19 HEADINGS / PROMPT BLOCKS / BLACK SCREENS: identical to Python (8 scenarios)"
  PASSED=$((PASSED + 1))
else
  echo "[FAIL] Check6 v19 scenarios: DIFFERENT" >&2
  head -40 "$LOG_DIR/Check6.diff" >&2
  FAILED=$((FAILED + 1))
  exit 1
fi

# Check7: Timeline model + edit engine
(cd /tmp/tt7 && cp "$OLDPWD"/testdata/timing/{details.txt,script.txt,gen.py} . && python3 gen.py >/dev/null)
run_suite "Check7_TimelineEditEngine" "$JAVA_BIN" -cp "$CP" Check7Kt /tmp/tt7

# Check8: DOCX reading, validation report, exact-boundary export plan
python3 scripts/make_check8_data.py /tmp/tt8
run_suite "Check8_DocxValidationExportPlan" "$JAVA_BIN" -cp "$CP" Check8Kt /tmp/tt8

# Check9: Confidence scores, pause policy, project store
run_suite "Check9_ConfidencePauseProjectStore" "$JAVA_BIN" -cp "$CP" Check9Kt /tmp/tt9

# Check11: Exact caption timing
run_suite "Check11_ExactCaptionTiming" "$JAVA_BIN" -cp "$CP" Check11Kt testdata/timing

# Check12: Timestamps-first analyse (optional private fixture)
if [ -f testdata/real/ps_clean.srt ]; then
  run_suite "Check12_TimestampsFirstAnalyse" "$JAVA_BIN" -cp "$CP" Check12Kt testdata/real
else
  echo "[SKIP] Check12 TIMESTAMPS-FIRST ANALYSE (testdata/real/ps_clean.srt not in public repo)"
  SKIPPED=$((SKIPPED + 1))
fi

# Check13..Check32
run_suite "Check13_FreeSceneLengthAndPresets" "$JAVA_BIN" -cp "$CP" Check13Kt
run_suite "Check14_ExportShapes" "$JAVA_BIN" -cp "$CP" Check14Kt
run_suite "Check15_StyleFontsAnimationGuides" "$JAVA_BIN" -cp "$CP" Check15Kt
run_suite "Check16_EffectsTransitionsTextPlan" "$JAVA_BIN" -cp "$CP" Check16Kt
run_suite "Check17_AudioMixWaveformCaptions" "$JAVA_BIN" -cp "$CP" Check17Kt
run_suite "Check18_MediaPrefsRenderCapsTokens" "$JAVA_BIN" -cp "$CP" Check18Kt
run_suite "Check19_StudioToolsStyleLibrary" "$JAVA_BIN" -cp "$CP" Check19Kt
run_suite "Check20_ClickSoundSync" "$JAVA_BIN" -cp "$CP" Check20Kt app/src/main/res/raw/click.wav testdata/click/master.srt
run_suite "Check21_Phase0Diagnostics" "$JAVA_BIN" -cp "$CP" Check21Kt
run_suite "Check22_Phase1StabilityAndViolet" "$JAVA_BIN" -cp "$CP" Check22Kt
run_suite "Check23_Phase2FontsAndIndicScripts" "$JAVA_BIN" -cp "$CP" Check23Kt
run_suite "Check24_Phase3SolidColorReplace" "$JAVA_BIN" -cp "$CP" Check24Kt
run_suite "Check25_Phase4ExportFoundation" "$JAVA_BIN" -cp "$CP" Check25Kt
run_suite "Check26_Phase5AiUpscalingAndCache" "$JAVA_BIN" -cp "$CP" Check26Kt
run_suite "Check27_Phase6ManualEditorAndCopy" "$JAVA_BIN" -cp "$CP" Check27Kt
run_suite "Check28_Phase7DesignStudioLayers" "$JAVA_BIN" -cp "$CP" Check28Kt
run_suite "Check29_Phase8TenWorkspacesAndControls" "$JAVA_BIN" -cp "$CP" Check29Kt
run_suite "Check30_Phase9PolishAndAccessibility" "$JAVA_BIN" -cp "$CP" Check30Kt
run_suite "Check31_CorrectionSpecVerification" "$JAVA_BIN" -cp "$CP" Check31Kt
run_suite "Check32_PerfAndRealEsrganDirective" "$JAVA_BIN" -cp "$CP" Check32Kt

# Check10: Backend client + verified timing
if python3 -c "import fastapi, uvicorn, numpy" 2>/dev/null && command -v ffmpeg >/dev/null; then
  PORT=$((20000 + RANDOM % 20000)); rm -rf /tmp/tt10 && mkdir -p /tmp/tt10
  ffmpeg -v error -y -f lavfi -i "sine=frequency=220:duration=6" /tmp/tt10/a.wav
  python3 backend/tests/serve_fake.py "$PORT" /tmp/tt10/data & SRV=$!
  trap 'kill $SRV 2>/dev/null; rm -rf "$OUT"' EXIT
  for _ in $(seq 1 50); do curl -s "http://127.0.0.1:$PORT/v1/health" >/dev/null && break; sleep 0.2; done
  run_suite "Check10_BackendClient" "$JAVA_BIN" -cp "$CP" Check10Kt "$PORT" /tmp/tt10/a.wav /tmp/tt2
  kill $SRV 2>/dev/null || true
else
  echo "[SKIP] Check10 BACKEND CLIENT (python3 fastapi/uvicorn/numpy or ffmpeg missing)"
  SKIPPED=$((SKIPPED + 1))
fi

echo "=== SUMMARY: PASSED=$PASSED FAILED=$FAILED SKIPPED=$SKIPPED ==="
