# AutoAndroid — Parity & Verification Script Report (`PARITY_REPORT.md`)

## 1. Fail-Closed Parity Runner Architecture (`Phase C0` & `Performance Directive Phase 6`)

### `scripts/run_parity.sh` & `scripts/dev_check.sh`
- **Strict shell mode**: Runs under `set -euo pipefail` with zero `|| true` or `grep` pipeline exit-code masking.
- **Upfront binary & stdlib verification**: Checks `command -v "$KOTLINC"` and `command -v "$JAVA_BIN"`, prints `kotlinc -version` and `java -version`, and verifies `kotlin-stdlib.jar` exists before executing any suite.
- **Explicit file inventory check**: Explicitly lists all **38 core Kotlin source files** (`CORE_SOURCES`, including `StreamingImageWriter.kt`) and all **29 test suites** (`TEST_SOURCES`, `Check4.kt` through `Check32.kt`), failing with exit code `2` if any file is missing.
- **Fail-closed compilation**: Runs `"$KOTLINC" "${CORE_SOURCES[@]}" "${TEST_SOURCES[@]}" -d "$OUT" >"$COMPILE_LOG" 2>&1` directly, captures `COMPILE_EXIT=$?`, and if `COMPILE_EXIT -ne 0`, prints the full compiler log to stderr and exits immediately with `COMPILE_EXIT`.
- **Per-suite log preservation & summary**: Saves each suite's output under `docs/logs/parity_suites/<SuiteName>.log` and prints `=== SUMMARY: PASSED=<N> FAILED=<N> SKIPPED=<N> ===`.

---

## 2. Verification Evidence & Raw Logs

| Verification Case | Command | Exit Code | Status | Raw Log Path |
|---|---|---|---|---|
| **1. Intentional Compile Error Test** | `KOTLINC=/tmp/mock_broken_kotlinc.sh bash scripts/run_parity.sh` | `1` (Non-zero) | `VERIFIED` | `docs/logs/parity_broken_compile.log` |
| **2. Normal Run in VM Sandbox** | `bash scripts/run_parity.sh` | `127` (Missing `kotlinc`) | `BLOCKED` (VM lacks `kotlinc`) | `docs/logs/parity_normal_run.log` |
| **3. Source & Suite Existence Check** | Verified by `scripts/run_parity.sh` step 2 (`38` core files, `29` test suites) | `0` | `VERIFIED` | `docs/logs/parity_broken_compile.log` |
| **4. Reproducible ONNX & PNG Benchmark Harness** | `python3 scripts/benchmark_export_upscale.py` | `0` | `VERIFIED` | `docs/logs/benchmark_run.log` & `docs/logs/benchmark_run.json` |

### Verbatim Output: Intentional Compile Failure (`docs/logs/parity_broken_compile.log`)
```text
=== AutoAndroid Fail-Closed Parity Runner ===
Date (UTC): 2026-11-03T08:28:59Z
Kotlin compiler: /tmp/mock_broken_kotlinc.sh
info: kotlinc-jvm 2.0.21 (JRE 17.0.18+8-LTS) [mock compile-error verifier]
Java runtime: /usr/bin/java
openjdk version "17.0.20.1" 2026-08-18
OpenJDK Runtime Environment (build 17.0.20.1+1-1-deb12u1-Debian)
Verified 38 core files and 29 test suites exist.
Compiling 38 core files and 29 test files...
COMPILE FAILED with exit code 1. Compiler output:
core/src/test/kotlin/Check32.kt:15:5: error: unresolved reference: intentionalCompileErrorForPhase6Test
EXIT_CODE=1
```

### Verbatim Output: Normal Run in Offline VM (`docs/logs/parity_normal_run.log`)
```text
=== AutoAndroid Fail-Closed Parity Runner ===
Date (UTC): 2026-11-03T08:28:59Z
ERROR: Kotlin compiler ('kotlinc') not found on PATH. Install Kotlin 2.0.21 or set KOTLINC=/path/to/kotlinc.
EXIT_CODE=127
```

---

## 3. Suite Status Matrix (`Check4` – `Check32`)

Because the offline VM container has OpenJDK `17.0.20.1` (`java`), `python3`, and `onnxruntime 1.31.0`, but does **not** have `kotlinc` or `gradle` installed and has no outbound internet access, the execution status of `Check4`–`Check32` is reported honestly below:

| Suite | Scope | Source File Status | Execution Status in VM | Command to Run on Host with Kotlin 2.0.21 |
|---|---|---|---|---|
| `Check4` | Picture matching parity vs Python (7 scenarios) | `VERIFIED` (Unchanged) | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/run_parity.sh` |
| `Check5` | ZIP end-to-end extraction & zip-slip | `VERIFIED` (Unchanged) | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/run_parity.sh` |
| `Check6` | v19 headings / prompt blocks / black screens (8 scenarios) | `VERIFIED` (Unchanged) | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/run_parity.sh` |
| `Check7`–`Check11` | Timeline edit engine, DOCX/ExportPlan, Confidence/ProjectStore, Backend, Exact caption timing | `VERIFIED` (Unchanged) | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/run_parity.sh` |
| `Check12` | Timestamps-first analyse (private fixture) | `VERIFIED` (Unchanged) | `SKIPPED / NOT RUN` | `testdata/real/ps_clean.srt` is private |
| `Check13`–`Check20` | Export presets, shapes, fonts, effects, audio, studio, click | `VERIFIED` | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/run_parity.sh` |
| `Check21`–`Check30` | Phase 0–9 unit suites | `WRITTEN ONLY` | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/run_parity.sh` |
| `Check31` | Corrective Specification suite (`StreamingPngWriter`, `UpscaleMemoryGuard`, `UpscaleCache`, `ExportCancellationGuard`, `10×10 WorkspaceSessionSimulator`) | `WRITTEN ONLY` | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/dev_check.sh Check31` |
| `Check32` | Final Performance & Real-ESRGAN Directive suite (`UpscaleAggregateStats`, `patchDynamicSpatialDims`, aspect-fit `smartFactor`, `ExifTileMapper` 1..8, batched `StreamingPngWriter` & cache integrity check) | `WRITTEN ONLY` | `NOT RUN (BLOCKED: no kotlinc)` | `bash scripts/dev_check.sh Check32` |
