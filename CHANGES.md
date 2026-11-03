# AutoAndroid / Manva — File-by-File Changes (`CHANGES.md`)

## 1. Protected Auto Create Core Files (100% Unchanged — Verified via SHA-256)

The following 8 protected Auto Create core files were **not modified** (`VERIFIED` identical SHA-256 hashes against the original baseline):
- `core/src/main/kotlin/com/auto/core/Captions.kt` (`ab0f0d9154de3d6c5458e6711774c0dc3d9bed0375c60e4769ce29217982878e`)
- `core/src/main/kotlin/com/auto/core/Details.kt` (`135c86b50051b19a141fb2ddfc448d87f15dcb4818d6c6c8e9898a19d08b3090`)
- `core/src/main/kotlin/com/auto/core/Bundle.kt` (`2ca8a2555a10306bf0d2482daa8fce50a432da58cee614d2c0fecb37a46856f7`)
- `core/src/main/kotlin/com/auto/core/Align.kt` (`aecf5e875d17b290e18ce1e0809aed67038f0b092d9df207916d95c30b993eaa`)
- `core/src/main/kotlin/com/auto/core/AudioPauses.kt` (`b724291a5730337da51472f48076aaa748f913163511a8638da2023605e88768`)
- `core/src/main/kotlin/com/auto/core/Matching.kt` (`61386ef7aba360cf3f5db04b7fcb05b061c4a95a8d1970ed719c8339acee0470`)
- `core/src/main/kotlin/com/auto/core/CaptionPipeline.kt` (`1801a709e9689ff51fcb8cf89a595a45434115359dd92970faa8e1acc6255ab5`)
- `core/src/main/kotlin/com/auto/core/ClickTrack.kt` (`c0659d22c0cfb80e5f11a743c41229f3f8fb56fc152f88446f276ade51ec5f32`)

---

## 2. Final Performance & Real-ESRGAN Directive Modifications (`Phases 1–6` & `C0–C6`)

### `core/` Module
- **`core/src/main/kotlin/com/auto/core/Diagnostics.kt` (Modified — Phase 1 Telemetry)**:
  - Extended `DiagStage` with all 11 export/upscale pipeline stages (`PROJECT_PREP`, `UPSCALE_QUEUE_WAIT`, `CACHE_LOOKUP`, `IMAGE_DECODE`, `UPSCALE`, `PNG_ENCODE_WRITE`, `AUDIO_PREP`, `PREPARE`, `RENDER`, `FINALIZE`, `MEDIASTORE_PUBLISH`, plus `IMPORT` and `ANALYSIS`).
  - Added `UpscaleImageMetric`, `UpscaleAggregateStats` (`medianMs`, `p90Ms`, `slowestMs`, `slowestImageIndex`, `imagesPerMinute`, `totalDecodeMs`, `totalInferenceMs`, `totalEncodeWriteMs`, `totalUpscaleMs`), and `Diagnostics.nanosToMs` / `aggregateUpscaleMetrics`.
- **`core/src/main/kotlin/com/auto/core/StreamingImageWriter.kt` (Added & Optimized — Phase C1 & Phase 3)**:
  - Implemented `StreamingPngWriter` with `128 KB` multi-scanline batching (`batchBuf`) and `Deflater.BEST_SPEED (1)` default so upscaled horizontal strips stream directly into PNG `IDAT` chunks on disk with **zero full-output `Bitmap` allocation** and `~32×–64×` fewer JNI `Deflater` calls.
  - Implemented `PngFormatVerifier.inspect` (full chunk/CRC32/IEND verifier) and `PngFormatVerifier.isValidCachedImageFile` (fast `IHDR` + `IEND` / `SOI` + `EOI` integrity check before reusing cached files).
- **`core/src/main/kotlin/com/auto/core/UpscaleEngine.kt` (Modified — Phases C1, 2, 3, 4)**:
  - Added `OnnxModelSpec` (`SHIPPED_MODEL_SPEC` documenting verified `realesr-general-x4v3` `SRVGGNetCompact` provenance, `1,213,296` params, `4,866,341` bytes, SHA-256 `d3b45e236c1cdc43c14b6aca839d59d6ac009f3791f998a16404252f6f65600e`, BSD-3-Clause license).
  - Added `UpscaleEngine.patchDynamicSpatialDims` (patches the 4 static `224`/`896` spatial dimensions in the trailing 63-byte `ValueInfoProto` in memory so `OrtSession` executes wide strips and exact edge tile sizes without modifying any weight bytes).
  - Upgraded `UpscaleEngine.smartFactor(srcW, srcH, targetW, targetH)` to compute the **aspect-fitted display box** inside `(targetW, targetH)` (`Presentation.LAYOUT_SCALE_TO_FIT`), eliminating false `2×`/`4×` upscaling on square (`1080×1080`) and portrait (`720×1280`) images that already cover their display box.
  - Added `ExifTileMapper` (`rawBoundingRectForOrientedRegion`, `extractOrientedWindowFromRawRegion`, `orientedDimensions`, `toRawCoord`) supporting all 8 EXIF orientations (`1..8`) via strip/region decoding without ever allocating a full-image `Bitmap` or full `IntArray(w * h)`.
  - Upgraded `UpscaleEngine.recommendedThreads` to target performance cores (`min(4, max(2, cpuCores / 2))`) and upgraded `UpscaleCache.get(..., verifyIntegrity = true)` to verify cached file integrity via `PngFormatVerifier.isValidCachedImageFile` and enforce `ucache_` ownership.
- **`core/src/main/kotlin/com/auto/core/ExportTracker.kt` (Modified — Phase C2 & Phase 4)**:
  - Added `pendingMediaStoreUri`, `ExportJobStore.markInterrupted`, and `ExportCancellationGuard` for post-cancellation write prevention and idempotent retry cleanup.
- **`core/src/main/kotlin/com/auto/core/WorkspaceMode.kt` (Modified — Phase C3)**:
  - Added `WorkspaceSessionState` and `WorkspaceSessionSimulator` (`verifyAllPairs` across all `10 × 10 = 100` workspace pairs + contextual text/audio selections).
- **`core/src/test/kotlin/Check31.kt` & `Check32.kt` (Added — Phases C1–C4 & Performance Directive Phases 1–6)**:
  - Added unit test suites covering `StreamingPngWriter`, `PngFormatVerifier`, `UpscaleMemoryGuard`, `UpscaleCache` integrity & ownership, `ExportCancellationGuard`, `10×10 WorkspaceSessionSimulator`, `Diagnostics.aggregateUpscaleMetrics`, `patchDynamicSpatialDims`, aspect-fit `smartFactor`, and `ExifTileMapper` across all 8 EXIF orientations.

### `app/` Module
- **`app/src/main/kotlin/com/auto/app/Upscaler.kt` (Modified — Phases C1, 1, 2, 3, 4)**:
  - Integrated `UpscaleEngine.patchDynamicSpatialDims`, wide horizontal strip tiling (`T_X_DYN = 384, T_Y = 192, P = 16`) with exact edge tile dimensions, bulk `FloatArray` <-> `FloatBuffer` transfer, `ExifTileMapper` region decoding for all 8 EXIF orientations (`1..8`) and alpha strips (`applyAlphaStripFromDecoder`), XNNPACK/NNAPI execution provider support with CPU fallback, background prefetch of next-image SHA-256/EXIF/dimensions (`upscale-meta-prefetcher`), and per-image `UpscaleImageMetric` monotonic `System.nanoTime()` telemetry.
- **`app/src/main/kotlin/com/auto/app/Exporter.kt` (Modified — Phases C2, 1, 4)**:
  - Added monotonic `System.nanoTime()` timers across all pipeline stages, parallel `AUDIO_PREP` overlapped with upscaling, deterministic deduplication of prepared caption/solid/text frames (`preparedFrameCache`), fast `writeBitmapFastPng` (`StreamingPngWriter` at `Deflater.BEST_SPEED = 1`), `ExportForegroundService` integration, post-cancellation write prevention, and idempotent `pendingMediaStoreUri` cleanup.
- **`app/src/main/kotlin/com/auto/app/ExportForegroundService.kt` (Added & Hardened — Phase C2 & Phase 4)**:
  - Implemented Android 14 `mediaProcessing` foreground service with ongoing progress notification, `Cancel` action button, `onTimeout` safe interruption handler, and visible startup warning reporting (`DiagnosticsLog.recordWarning` + `lastStartupWarning`).
- **`app/src/main/kotlin/com/auto/app/DiagnosticsLog.kt` & `SettingsScreen.kt` (Modified — Phase 1)**:
  - Added `recordImageMetric`, `recordWarning`, `System.nanoTime()` run tracking, and Settings UI display of `UpscaleAggregateStats` (`median`, `p90`, `slowest`, `decode/infer/encode` split) and verified Real-ESRGAN v3 (`realesr-general-x4v3`) model metadata.
- **`app/src/main/AndroidManifest.xml`, `MainActivity.kt`, `AutoState.kt`, `EditorScreen.kt` (Modified — Phases C1–C3)**:
  - Removed `android:largeHeap="true"`, added `ExportForegroundService` and permissions, added `AutoAppHolder` (`attachActivity`), and added the **AI Upscale Preflight Memory Guard** choice card in `ExportSheet`.

### Scripts, Benchmarks, & Documentation
- **`scripts/run_parity.sh` & `scripts/dev_check.sh` (Modified — Phase C0 & Phase 6)**:
  - Fail-closed compilation without `|| true`, verifying all 38 core files and 29 test suites (`Check4`–`Check32`).
- **`scripts/benchmark_export_upscale.py` (Added — Phases 1, 2, 5)**:
  - Reproducible ONNX Runtime + `StreamingPngWriter` benchmark harness evaluating all 7 visual categories and 10-, 25-, and 200-image workloads (`docs/logs/benchmark_run.json` & `docs/logs/benchmark_run.log`).
- **Required Deliverables & Reports**:
  - Added/updated `START_HERE.md`, `PERFORMANCE_REPORT.md`, `REAL_ESRGAN_EVALUATION.md`, `UPSCALE_MEMORY_REPORT.md`, `BACKGROUND_JOB_REPORT.md`, `PARITY_REPORT.md`, `WORKSPACE_VERIFICATION.md`, `CORRECTION_REPORT.md`, `CHANGES.md`, `HANDOVER.md`, `RESULTS.md`, and `BASELINE.md`.
