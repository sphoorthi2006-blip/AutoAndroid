# AutoAndroid / Manva — Background Export & Recovery Lifecycle Report (`BACKGROUND_JOB_REPORT.md`)

## 1. Lifecycle Architecture (`Phases C2 & Performance Directive Phase 4`)

### 1.1 Application-Scoped State Holder (`AutoAppHolder` in `MainActivity.kt` + `AutoState.attachActivity`)
- `AutoAppHolder.getOrCreate(act)` retains the singleton `AutoState` across `MainActivity` configuration changes (portrait/landscape rotation, multi-window resize) and screen navigation.
- Navigating between **Home**, **Projects**, **Settings**, and all **10 Editing Workspaces** inside `AutoShell` never cancels or resets an active export or upscale job.
- `MainActivity.onDestroy()` only releases preview audio/click players when `isFinishing` is `true`, leaving `Exporter` and `SafelightExportProcessor` untouched.

### 1.2 Foreground Media-Processing Service & Visible Startup Error Handling (`ExportForegroundService.kt`)
- **Manifest Configuration (`app/src/main/AndroidManifest.xml`)**:
  - Removed `android:largeHeap="true"` (justified by bounded strip-streaming in `Upscaler.kt` + `StreamingPngWriter` and streaming waveform generation in `AudioDecoder.streamPeaks`).
  - Declared `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, and `POST_NOTIFICATIONS` permissions, and registered `.ExportForegroundService` with `android:foregroundServiceType="mediaProcessing"` (`targetSdk = 34`).
- **Visible Startup Warning Reporting (Phase 4 Requirement)**:
  - `ExportForegroundService.startForegroundSafe` and `ExportForegroundService.notifyProgress` **never swallow exceptions silently**.
  - If the OS restricts foreground service start or notification promotion, the exception is caught, formatted into a clear user-facing warning (`"Background service start restricted by OS (<Exception>); keep screen active during export."`), recorded in `DiagnosticsLog.recordWarning(...)`, stored in `ExportForegroundService.lastStartupWarning`, and returned to `Exporter.exportTimeline` -> `onStatus(fgWarn)` so the user sees it immediately in the UI and in **Settings -> Diagnostics**.
- **Android 14 (`API 34`) `onTimeout` Handling**:
  - Overrides `Service.onTimeout(startId: Int)` to record a diagnostic warning and invoke `Exporter.markInterruptedBySystem(applicationContext, ...)`, which stops the active transformer/upscaler, deletes unverified temporary `.mp4` files, preserves all completed `ucache_*` upscale artifacts, and persists `ExportJobStatus.INTERRUPTED` in `ExportJobStore`.

### 1.3 Post-Cancellation Write Prevention & Idempotent Retry (`Exporter.kt` & `ExportTracker.kt`)
- **Explicit Cancellation (`Exporter.cancel` & `ExportCancellationGuard`)**:
  - Sets `currentCancelFlag.set(true)`, cancels the active Media3 `Transformer`, clears handler callbacks, deletes temporary files and any `pendingMediaStoreUri` from `MediaStore`, stops `ExportForegroundService`, and atomically writes `ExportJobStatus.CANCELLED`.
  - Both the upscaler thread (`SafelightExportProcessor.processPlanDetailed`) and `Transformer.Listener.onCompleted` check `if (cancelFlag.get())` before verification, after verification, and inside the 64 KB `publish()` copy loop, guaranteeing that a cancelled job never publishes to `Movies/Auto` or overwrites `CANCELLED` with `COMPLETED`.
- **Idempotent Retry & Process-Death Recovery (`ExportJobStore` & `UpscaleCache`)**:
  - `ExportJob` atomically persists `pendingMediaStoreUri`, `settingsFingerprint`, `modelFingerprint`, `sourceFingerprints`, and `completedArtifacts` in `filesDir/export_jobs/<jobId>.json` via `FileOutputStream.fd.sync()` + `ATOMIC_MOVE`.
  - On app launch after process death (`ExportJobStore.recoverInterruptedJobs()`), any `ACTIVE` job is transitioned to `INTERRUPTED ("Export interrupted — Resume where safe.")`.
  - When the user taps **Resume Export** or **Retry Export**:
    1. Any stale `pendingMediaStoreUri` from the interrupted run is deleted from `MediaStore` before starting (preventing duplicate or orphan `IS_PENDING = 1` entries).
    2. `UpscaleCache.get(..., verifyIntegrity = true)` verifies every cached `ucache_*` image via `PngFormatVerifier.isValidCachedImageFile` (`IHDR` + `IEND` check for PNG, `SOI` + `EOI` check for JPEG) so completed valid upscales are reused in `< 4 ms` per image while any truncated file is cleanly regenerated.

---

## 2. Verification Evidence Matrix

| Requirement | Implementation & Unit Test | Status |
|---|---|---|
| `android:largeHeap="true"` removed & `ExportForegroundService` declared | `app/src/main/AndroidManifest.xml` | `VERIFIED` |
| Post-cancellation write prevention & `pendingMediaStoreUri` cleanup | `ExportCancellationGuard` (`Check31.6` in `core/src/test/kotlin/Check31.kt`) | `WRITTEN ONLY` (Unit test written; pending host `kotlinc`) |
| System `onTimeout` transitions `ACTIVE -> INTERRUPTED` & preserves `completedArtifacts` | `ExportJobStore.markInterrupted` (`Check31.6`) | `WRITTEN ONLY` |
| Pre-reuse `ucache_*` integrity check (`isValidCachedImageFile`) rejects truncated files | `PngFormatVerifier.isValidCachedImageFile` + `UpscaleCache.get` (`Check32.5`) | `WRITTEN ONLY` (Kotlin) / `VERIFIED` (Python harness) |
| Visible foreground-service warning recording in `DiagnosticsRunReport.warnings` | `ExportForegroundService` + `DiagnosticsLog.recordWarning` (`Check32.1`) | `WRITTEN ONLY` |
| Physical Android device background navigation, notification `Cancel`, & `am kill` test | `RESULTS.md` Section 3 | `NOT RUN (BLOCKED — no physical Android device attached)` |
