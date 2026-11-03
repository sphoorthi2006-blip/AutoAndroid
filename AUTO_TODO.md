# AutoAndroid — Remaining Host & Device Verification Tasks (`AUTO_TODO.md`)

## 1. Completed in Repository (`Phases 0–10` & `Phases C0–C6`)
- [x] **Phase C0**: Rewrote `scripts/run_parity.sh` and `scripts/dev_check.sh` to fail closed without `|| true`, verify all 38 core files and 28 test suites (`Check4`–`Check31`), and capture raw logs (`docs/logs/parity_broken_compile.log` and `docs/logs/parity_normal_run.log`) in `PARITY_REPORT.md`.
- [x] **Phase C1**: Implemented `StreamingPngWriter` + `PngFormatVerifier` (`StreamingImageWriter.kt`), `BitmapRegionDecoder` source-strip decoding (`Upscaler.upscaleToPngFile`), `UpscaleMemoryGuard` (`Math.multiplyExact` overflow protection + preflight check with explicit user choices and no silent `4× -> 2×` reduction), pixel-complete `cacheKey` (quality + EXIF rotation), and `ucache_` ownership verification in `UpscaleCache`. Documented in `UPSCALE_MEMORY_REPORT.md`.
- [x] **Phase C2**: Removed `android:largeHeap="true"` from `AndroidManifest.xml`, implemented `ExportForegroundService` (`foregroundServiceType="mediaProcessing"`, ongoing progress notification with `Cancel` button, Android 14 `onTimeout` handler), added `AutoAppHolder` (`MainActivity.kt`), and hardened post-cancellation write prevention and idempotent `pendingMediaStoreUri` cleanup in `Exporter.kt` and `ExportTracker.kt`. Documented in `BACKGROUND_JOB_REPORT.md`.
- [x] **Phase C3**: Implemented `WorkspaceSessionSimulator` (`10 × 10 = 100` ordered workspace transitions + contextual text/audio selection transitions) in `WorkspaceMode.kt` and documented all 10 workspaces and all 22 Section 7 control groups in `WORKSPACE_VERIFICATION.md`.
- [x] **Phase C4–C6**: Verified SHA-256 invariance of all 8 protected Auto Create core files (`Captions.kt`, `Details.kt`, `Bundle.kt`, `Align.kt`, `AudioPauses.kt`, `Matching.kt`, `CaptionPipeline.kt`, `ClickTrack.kt`) and updated `CORRECTION_REPORT.md`, `CHANGES.md`, `HANDOVER.md`, and `RESULTS.md`.

---

## 2. Host & Physical Device Tasks (`NOT RUN` / `BLOCKED` in Offline VM)
Run these steps on a developer workstation with Kotlin `2.0.21`, Android SDK `34`, and an attached physical Android device:
- [ ] **Host Kotlin Parity & Unit Suite Execution (`NOT RUN` in VM)**:
  - Run `bash scripts/run_parity.sh` with `kotlinc` `2.0.21` on `PATH` and verify all suites (`Check4` through `Check31`) pass.
- [ ] **Host Gradle Build & Lint (`NOT RUN` in VM)**:
  - Run `./gradlew test :app:assembleDebug :app:lintDebug` and archive build logs.
- [ ] **Physical Device Memory & Lifecycle Verification (`NOT RUN` in VM)**:
  - Install `app-debug.apk` on a physical Android device, run a `4000 × 3000` image upscale at `2×` and `4×` (`Streaming PNG`), capture `adb shell dumpsys meminfo com.auto.app`, and populate the `NOT RUN` cells in `RESULTS.md`.
  - Verify background navigation (`Editor -> Home -> Projects -> Settings -> Editor`), notification `Cancel`, and `adb shell am kill com.auto.app` process-death recovery on device.
  - Capture portrait, landscape, and tablet screenshots of all 10 workspaces into `docs/screenshots/`.
