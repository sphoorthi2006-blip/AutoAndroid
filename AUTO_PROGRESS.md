# AUTO - PROGRESS (update after every step; trust source + tests over this file)

Last updated: Session 2 (new account). Core now also has Confidence, PausePolicy, ProjectStore (tested, 30 checks). App still NOT wired to them and NOT compiled in this sandbox (Maven blocked); GitHub CI compiled the app at ecf2f6b per owner report. Previous: Session 1, part 2. Audit done; core now has Timeline engine, DOCX reader, ValidationReport, ExportPlan (all tested). App wiring written but NOT compiled.

## Master roadmap status (45 steps)
Legend: DONE = implemented AND verified | PART = partly implemented/verified | EXISTS = already in repo, verified by tests only | TODO

| # | Step | Status | Notes |
|---|------|--------|-------|
| 1-5 | Phase 1 audit + architecture + roadmap | DONE | see AUTO_ARCHITECTURE.md |
| 6 | Project structure | TODO | no Gradle wrapper; app is 3 files |
| 7 | Persistent project storage | PART | `ProjectStore.kt` (tested, core): folder per project, atomic writes, .bak recovery, importFile, list, cleanTemp. **Not yet used by MainActivity; no projects screen** |
| 8 | Import + ZIP extraction | DONE (core) | Pipeline.kt zip-slip safe; `TextFiles.kt` reads .docx (tested on a real docx); app wiring uncompiled |
| 9 | Validation + error reporting | PART | `Validation.kt` (tested): duplicates by SHA-256, missing numbers, unreadable images, unused images, scenes without picture, uncertain matches. Shown as text in MainActivity (uncompiled). **No review screen, no Accept/Other image/Adjust timing flow** |
| 10 | Backend communication | PART | `backend/` FastAPI (bearer token, hash dedupe, job cache) + `BackendClient.kt` (https rule, retries, readable errors); tested live with FAKE models. App wiring in MainActivity written, **not compiled here**; token typed in app (stored in app-private prefs). No chunk-resumable upload |
| 11-16 | Faster-Whisper, WhisperX, VAD, confidence | PART | `backend/auto_backend/engine.py`: Silero VAD (real, tested on silence), Faster-Whisper + WhisperX wrappers, caption/script diff, clamp-to-speech, warnings. **Whisper/WhisperX never run for real (no HuggingFace in sandbox) - first real run is on owner's server.** Recognition/alignment accuracy NOT measured |
| 17-22 | LLM / Vision understanding | TODO | deterministic number/name/Hungarian matching exists (Matching.kt) |
| 23-24 | Integrated matching / scene-to-image | EXISTS | parity-tested vs saved Python output (15 scenarios) |
| 25-26 | Start/end from audio, pauses | PART | Align + AudioPauses + `AudioPauses.silences` + `PausePolicy` (KEEP / FLAG_LONG, tested in core). **No settings UI; policy never auto-switches pictures at pauses by design** |
| 27 | Confidence + warnings | PART | `Confidence.kt`: 0-100 score per clip stored in Timeline JSON (old files still load), `needsReview`. **Rule-based from matcher evidence only; no audio/ASR/vision evidence yet; no review screen** |
| 28 | Timeline JSON | PART | Timeline.toJson (clips+captions); no scene/word/silence data yet |
| 29-36 | Timeline editor | PART | **core logic DONE+tested** (move/trim/rolling/split/delete/replace/undo/redo/validate). **No UI, no waveform, no tracks drawn** |
| 37 | Preview | TODO | |
| 38-40 | Export | PART | `ExportPlan.kt` (tested: video length == narration length exactly). `Exporter.exportTimeline` (720p/1080p, 24/30/60 settings object) written, **never compiled or run**. Still missing: captions burn-in, settings UI, progress screen, foreground service/recovery |
| 41-45 | Testing/delivery | TODO | |

Fully DONE: 1,2,3,4,5 and 8 (core part; app part uncompiled).  Partial: 7,9,25,26,27,28,29-36(core only),38-40.  Count: 6 / 45, with the caveat on 8.

## Verified in this session (kotlinc 2.0.21, JDK 21)
- scripts/run_parity.sh: matching 7/7 identical, ZIP e2e OK, v19 8/8 identical, Timeline engine 27 checks, DOCX/validation/export-plan 21 checks (run_parity.sh, all pass).
- Check.kt (captions/details/bundle roles) output == testdata/files/py.out.
- NOT verified: anything under app/ (Gradle/Maven unreachable in sandbox; app never compiled by anyone yet per README).

## Known issues (see AUTO_TODO.md for fixes)
1. testdata/timing/ref.out does NOT reproduce from committed gen.py/caps.srt (captions-only + with-audio sections differ; only anchorCuts output matches). Python engine isn't in the repo. Timing parity is therefore UNVERIFIED and is not in CI.
2. Exporter drift: CONFIRMED by arithmetic (600 scenes of 1.0003 s: old method 180 ms short). Fixed in core (ExportPlan) and used by Exporter.exportTimeline when pictures cover the whole script; the legacy per-group path remains for partial coverage. Whether Media3 adds frame-rounding of its own is UNMEASURED (needs a device).
3. AudioDecoder: whole narration as FloatArray with doubling+copy (1 h ~ 230 MB x up to 3 transient); assumes 16-bit PCM.
4. MainActivity: no persistence, raw threads (lost on rotation/process death), export not in a foreground service/WorkManager, progress poller can leak.
5. Exporter: fixed 1280x720@30, no captions burn-in, no transitions; `publish()` swallows MediaStore errors and returns a cache path that Android may delete.
6. (fixed) DOCX now supported in core + MainActivity; legacy .doc not supported.
8. Bug found+fixed by tests: ExportSettings companion init order (NPE).
9. Partial-coverage case (several folders, not full) still uses the legacy exporter and has no timeline.
7. No APK was in the upload (source zip only).

## Session 2 log
- Verified zip == report; all 5 earlier suites reproduce (kotlinc 2.0.21 fetched from GitHub; Maven/Google repos unreachable so :app cannot be built here).
- Added core/Confidence.kt, core/ProjectStore.kt, AudioPauses.silences, jsonQuote, ImageClip.score/cutCheck, IssueKind.LOW_CONFIDENCE/LONG_PAUSE, Check9.kt (30 PASS), run_parity.sh runs Check9.
- STILL WAITING: owner's phone test of the APK (sync at end of video, warnings list, crashes). Nothing in app/ changed this session.

## Session 2 part 2 (owner's phone result: ~90% right, ~10% pictures appear BEFORE the sentence)
- Likely cause: caption times are approximate and AudioPauses.anchorCuts/refineCuts snap cuts to energy pauses, which can land early. Fix: backend gives verified word times; `timeScenes(verified=true)` skips snapping and cuts at first spoken word - 0.06 s (test: 0/9 cuts off-word vs 8/9 on adversarial audio with the old path). NOT confirmed on the owner's real project yet.
- Added: backend/ (17 pytest), BackendClient.kt, Check10.kt (15 PASS, live server), MainActivity server URL/token fields, INTERNET permission.
- If pictures still come early after using the server: send the Audio warnings + scene list; next suspect is Align.segmentWords (word->scene assignment), then Gemini fallback (Steps 17-22, not built).

## Session 3 - exact caption timing ported from desktop (TIMING_FIX_README)
- Captions.parseRows / Captions.pauseAware, Align.exactCueScenes, timeScenes(rows=...), Pipeline.analyse(rows=...), MainActivity passes the caption rows.
- Rule: one caption cue per scene -> picture changes are the cue starts (0.06 s early, no snapping); captions that keep real pauses -> caption times kept, no pause snapping; Clipchamp-style even captions -> unchanged.
- Check11.kt: 14 PASS (run_parity.sh). All earlier suites still pass. app/ change is two lines in MainActivity and is NOT compiled here.
- NOT verified: the old early-cut bug did not reproduce on synthetic audio, so this is a regression guard, not proof the real project is fixed. Test on the real TurboScribe project.
- Still missing vs desktop: timestamps-first analyse without a Details file, text/click/music tracks, export quality/resolution options, new picture-number patterns (prompt/cue, copy suffix, 0-based).

## Session 3 part 2 - timestamps-first analyse, NO Details file (matches the desktop's new Analyse)
- core/CaptionPipeline.kt: inputs are captions (timestamps), audio, pictures, script - none mandatory; only "nothing at all" throws. Scenes: cues > script lines > pictures > audio alone. Timing: exact cue starts > verified word times (script only) > estimate > 3 s each. Pictures by number in the file name (image/img/scene/shot/frame/pic/picture/slide/prompt/cue, copy suffix "7 (1)", numbering from 0), else file-name order with a warning; scenes without a picture = black screen + text. Warnings: missing/duplicate/extra/unnumbered/unreadable pictures, scenes >7 s or <0.6 s, overlapping or unsorted captions, audio shorter/longer than captions, script mismatch.
- MainActivity.analyse() rewritten to use it: no details file needed, no whole-narration decode (only its duration), server only used when there are no captions but a script + audio.
- Check12.kt: 20 PASS, uses the owner's real ps_clean.srt (testdata/real/). run_parity.sh runs it.
- NOT done / NOT verified: app/ still not compiled here (CI needed). Blank-picture detection (needs bitmap decode) not ported. Details-based path (Pipeline.analyse) kept for old tests but no longer used by the app.

## Session 3 part 3 - manual AI server inputs removed from the app
- Owner: a server URL + token typed by hand is not an "AI feature". Removed from MainActivity (fields, saved prefs, BackendClient call) and the INTERNET permission. The app is fully offline now: captions, audio, pictures, script.
- Script without captions now always uses the ESTIMATED timing (with a warning) - there is no speech recognition in the app.
- Left in the repo, unused by the app: backend/ (FastAPI) and core/BackendClient.kt + Check10 (their tests still run). A real built-in AI feature (on-device speech timing) would be a separate step: it needs a decision on which on-device model to ship.

## Session 4 - phone editor screen (Canva-style, navy + glow) - NOT compiled here
- Replaced the plain text screen with a Jetpack Compose editor: gradient top bar (undo/redo, scenes, warnings, Export), 16:9 preview with the scene's picture + text, play row, scrolling timeline under a fixed playhead (picture / text / narration tracks), bottom tab bar (Inputs, Scenes, Pictures, Warnings, Export) opening bottom sheets, and a tool bar for a selected scene (Done, Split, Shorter, Longer, Replace, Delete).
- Files: app/Theme.kt, app/AutoState.kt (old MainActivity logic moved here + TimelineEditor edits + MediaPlayer playback), app/EditorScreen.kt, app/MainActivity.kt (thin host). Gradle: Compose BOM 2024.06.00, activity-compose 1.9.0, kotlin compose plugin 2.0.21. Manifest theme -> dark.
- Edits use core Timeline/TimelineEditor (split, trim, delete, replaceImage, undo/redo). Export uses the edited timeline when something was edited or the script is fully covered.
- NOT built yet (no buttons for them on purpose): Text sheet / text editing, Music, Fit/Fill, Colour, Animate, Duplicate, pinch zoom, clip edge drag handles, real waveform (the narration wave is decorative).
- NOT verified: first compile (run the "Build APK" workflow; if it fails, read the buildlog branch) and a phone test.
- Review fixes (after the first draft): Split now checks the playhead is inside the scene and shows a message otherwise (core silently ignored a too-short cut); edit errors also show as a toast (the status line is only visible in Inputs/Export); timeline scroll no longer fights the playhead - only real finger drags/flings seek, our own jump-to-scene scroll does not.
