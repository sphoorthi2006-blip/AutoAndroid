# Bug fixes applied on top of AutoAndroid-complete

Found by compiling `core/` with real `kotlinc 2.0.21` and running the project's own parity suites
(Check4–Check32), then reviewing the Android-only code by hand (the Android module could not be built offline).

| # | Severity | File | Problem | Fix |
|---|----------|------|---------|-----|
| 1 | Build-breaking | `core/.../ZipSafety.kt` | `TaskToken.runIfActive` is `inline` but read a `private` field -> "public-API inline function cannot access non-public-API property" (core would not compile). | Read the public `isCancelled` instead. |
| 2 | Critical (runtime) | `core/.../StreamingImageWriter.kt` | `StreamingPngWriter.writeFile` called `fos.fd.sync()` after the writer had already closed the stream -> always `SyncFailedException`, partial file deleted. Broke every streaming PNG: 4x/Maximum upscales, and (via `Exporter.writeBitmapFastPng`) caption / solid-colour / text screens during normal export. | `fd.sync()` now runs before the stream is closed. |
| 3 | Functional | `core/.../Timeline.kt` | `insertClip` at a playhead exactly on a clip boundary snapped to the END of the following clip, so the new clip landed one slot too late. | Only snap when the playhead is strictly inside a clip. |
| 4 | Minor (race) | `app/.../Exporter.kt` | `currentTempFiles` (plain `ArrayList`) written from the audio-prep thread and the export thread. | `Collections.synchronizedList`. |
| 5 | Stale tests | `Check5.kt`, `Check26.kt`, `Check28.kt` | Tests still expected old behaviour (zip-slip entries skipped, MAXIMUM stepping down to 2x, 7 upscale threads on 8 cores) or a misspelt API (`toggleTextLocked`). | Tests updated to the current, intended behaviour. |

Result after fixes: `core` compiles; 27 parity suites PASS, 2 SKIPPED (Check10 needs a Python backend, Check12 needs a private fixture).
Also verified: shipped `model.onnx` SHA-256 matches the documented value, and `patchDynamicSpatialDims` yields a model that runs at
arbitrary sizes (e.g. 224x416 -> 896x1664) with output identical to the original at 224x224 (checked with onnxruntime).
NOT verified: the Android module (`app/`) has never been compiled or run - do that in Android Studio.
