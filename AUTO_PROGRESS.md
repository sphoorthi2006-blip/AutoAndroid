# AUTO - PROGRESS (update after every step; trust source + tests over this file)

Last updated: Session 1, part 2. Audit done; core now has Timeline engine, DOCX reader, ValidationReport, ExportPlan (all tested). App wiring written but NOT compiled.

## Master roadmap status (45 steps)
Legend: DONE = implemented AND verified | PART = partly implemented/verified | EXISTS = already in repo, verified by tests only | TODO

| # | Step | Status | Notes |
|---|------|--------|-------|
| 1-5 | Phase 1 audit + architecture + roadmap | DONE | see AUTO_ARCHITECTURE.md |
| 6 | Project structure | TODO | no Gradle wrapper; app is 3 files |
| 7 | Persistent project storage | PART | `Timeline.toJson/Json.parse` (tested). No Room/files store, no UI |
| 8 | Import + ZIP extraction | DONE (core) | Pipeline.kt zip-slip safe; `TextFiles.kt` reads .docx (tested on a real docx); app wiring uncompiled |
| 9 | Validation + error reporting | PART | `Validation.kt` (tested): duplicates by SHA-256, missing numbers, unreadable images, unused images, scenes without picture, uncertain matches. Shown as text in MainActivity (uncompiled). **No review screen, no Accept/Other image/Adjust timing flow** |
| 10 | Backend communication | TODO | |
| 11-16 | Faster-Whisper, WhisperX, VAD, confidence | TODO | core has energy-based pauses only (AudioPauses.kt) |
| 17-22 | LLM / Vision understanding | TODO | deterministic number/name/Hungarian matching exists (Matching.kt) |
| 23-24 | Integrated matching / scene-to-image | EXISTS | parity-tested vs saved Python output (15 scenarios) |
| 25-26 | Start/end from audio, pauses | PART | Align + AudioPauses; no user "pause policy" setting |
| 27 | Confidence + warnings | PART | Scene.conf "?" and cutCheck "!"; no numeric scores |
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
