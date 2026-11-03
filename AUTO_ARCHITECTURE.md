# AUTOANDROID — ARCHITECTURE & DECISIONS (`AUTO_ARCHITECTURE.md`)

## 1. Module & Layer Architecture
- **`core/` (pure Kotlin/JVM, zero Android dependencies)**:
  - **Protected Deterministic Auto Create Engine (DO NOT REWRITE)**:
    - `Captions.kt` (SRT, VTT, Whisper-JSON, timed lines, cue parsing)
    - `Details.kt` (`Image N - Script line:` blocks, headings, `scopedItems`)
    - `Bundle.kt` (input role classification: narration, click, details, script, captions)
    - `Align.kt` (word-to-scene alignment, `exactCueScenes`, `timeScenes`)
    - `AudioPauses.kt` (`16 kHz` energy envelope, `silences`, `refineCuts`, `anchorCuts`)
    - `Matching.kt` (deterministic number/filename/folder/Hungarian assignment, missing/uncertain flags)
    - `CaptionPipeline.kt` (timestamps-first `analyse()` without a Details file)
    - `Pipeline.kt` (`picturesFromZip` with `ZipSafety` limits + details-based `analyse()`)
    - `ClickTrack.kt` (picture-start `startsMs`, 16-bit PCM WAV parser/header, exact-sample `mixInto` with 10 ms fade & soft limiter)
  - **Shared Project, Timeline, Typography, Export & Workspace Models (`core/`)**:
    - `Timeline.kt`: Immutable ms-precision timeline (`ImageClip` with optional `replacementImage` and `replacementColor`; `TextClip` with `OverlayStyle`, `name`, `visible`, `locked`, `zIndex`; `CaptionClip`; `AudioMix`; `TimelineEditor` undo/redo; `blank()`, `insertClip()`, `reorderClip()`, `validate()`, Timeline JSON v1 persistence).
    - `OverlayStyle.kt` & `TextAnim.kt`: Resolution-independent text/caption styling, `FontCatalog` (`8` legacy IDs in `faces` + `47` extended families in `allFaces`), `ScriptDetector` (`Latin`, `Devanagari`, `Telugu`, `Malayalam`), and grapheme/akshara-safe typewriter animation.
    - `DesignTokens.kt`: Section 4 Midnight Violet palette tokens and WCAG contrast math.
    - `RenderCaps.kt`: Honesty guard mapping every offered feature to `exporterImplements` (`WRITTEN` until confirmed on a phone; unsupported features `UNAVAILABLE` with explicit reasons).
    - `ExportPlan.kt`, `ExportOptions.kt`, `ExportTracker.kt`, `UpscaleEngine.kt`: Exact-boundary segment planning, `UpscaleProfile` (`Manual`, `Fast`, `Balanced`, `Maximum`), `smartFactor`, persistent SHA-256 `UpscaleCache` with LRU eviction, `ExportStage` progress & ETA (`>= 5` items & `CV <= 0.45`), `StatFs` storage estimator, output verification rules, and atomic `ExportJobStore`.
    - `LayerTools.kt` & `WorkspaceMode.kt`: Design Studio layer stacking/alignment/distribution (`>= 3` items) and all 10 `WorkspaceMode` definitions + Section 7 `ControlRegistry`.
    - `Diagnostics.kt`, `AudioStream.kt`, `ZipSafety.kt`: Privacy-safe stage/heap diagnostics, streaming waveform peak accumulator (`20 peaks/sec`), paged sample buffer, `AutosaveCoordinator`, and `TaskToken`.
- **`app/` (Android + Jetpack Compose + Media3 1.3.1 + ONNX Runtime 1.17.1)**:
  - `AutoShell.kt`, `HomeScreen.kt`, `ProjectsScreen.kt`, `SettingsScreen.kt`, `EditorScreen.kt`, `Workspaces.kt`, `DesignStudio.kt`
  - `AutoState.kt`: Single shared state across all 10 workspaces, Home, Projects, and Settings.
  - `Exporter.kt`, `Upscaler.kt`, `OverlayRenderer.kt`, `TextBitmapOverlay.kt`, `FontResolver.kt`, `ClipEffects.kt`, `AudioGainProcessor.kt`, `ClickSound.kt`, `AudioDecoder.kt`, `DiagnosticsLog.kt`.

## 2. Non-Negotiable Architectural Invariants
1. **Protected Core Engine**: Deterministic parsing, matching, timing, caption alignment, narration synchronization, and click placement in `core/` are never replaced or altered by AI or UI changes.
2. **All Ten Workspaces Share One State**: Switching between `Classic Timeline`, `Compact Editor`, `Advanced Multi-Panel`, `Text Focus`, `Audio Focus`, `Quick Edit`, `Design Studio`, `Dual Preview`, `Vertical Editing`, and `Minimalist` changes the view layout only—never duplicating projects or resetting playhead, selection, or undo history.
3. **Offline-First & Resource Safety**: Zero network calls, telemetry, or cloud dependencies in the Android app; original user media is never deleted or overwritten; `MediaCodec`/`MediaExtractor`/`MediaMetadataRetriever` close in `finally` blocks; `MediaStore` uses `IS_PENDING = 1` and cleans up on failure/cancel.
