# Auto for Android - port of the Auto desktop engine

Every upload to GitHub is checked automatically (Actions tab): the Kotlin core must give the same results as the Python engine.

`core/` = pure Kotlin (no Android code): runs and tests on a PC, reused unchanged by the phone app.

## What the core does (SRT-first, same rules as the desktop app - session 3)
Four inputs, NONE mandatory: timestamps (SRT), audio, pictures, script. Analyse only refuses when there is nothing at all.
- scenes: timestamps cues > script lines > pictures > audio alone.  timing: exact cue starts (0.06 s before the first word) > AI-server word times / audio pauses > estimate from words > 3 s each.
- pictures: by the number in the file name (7.jpg = scene 7), else file-name order (with a warning). More cues than pictures -> the extra scenes are a BLACK screen with their text. More pictures than cues -> the extra pictures are left out.
- Warnings (clickable in the app, never blocking): missing / extra / duplicate / unnumbered / damaged pictures, scenes over 7 s or under 0.6 s, overlapping or unsorted timestamps, audio shorter / longer than the timestamps, script that does not match, files that could not be read.
- The Details file, Matching.kt (Hungarian / section matching) and the old Details-based Bundle are REMOVED.

Files in core/:
- Analyse.kt     Analyser.analyse() = the whole logic above (port of the desktop engine.analyse); Analysis, AnalyseIssue
- Pictures.kt    Pics: image number from the file name, natural sort, assign() pictures -> scenes
- Captions.kt    SRT, VTT, Whisper JSON, timed lines; parseRows() = the cues exactly as written; watermark removal
- Text.kt        tokens, lines, caption-tag cleaning, script lines -> scenes
- Bundle.kt      sorts any mix of files into narration / click / timestamps / script
- Pipeline.kt    ZIP + folder reading (zip-slip safe) + the one-call analyse() the app uses
- Align.kt       script lines + exact word times (AI server) -> scenes; no-audio estimate
- AudioPauses.kt pause detection on 16 kHz mono samples (used only when there are no timestamps)
- Validation.kt  identical-file duplicates, magic-byte image check
- Timeline.kt, ExportPlan.kt, Confidence.kt, ProjectStore.kt  editing model, exact-boundary export plan, scores, saved projects

## Tests
scripts/run_parity.sh compiles core/ with kotlinc and runs CheckSrt (SRT-first Analyse, incl. 218 cues + a ZIP of 218 pictures), Check7 (timeline editing), Check8 (DOCX, validation, export plan), Check9 (confidence, project store), Check10 (backend client + verified timing, needs fastapi + ffmpeg).
- testdata/timing  gen.py makes a test narration (audio.f32) + SRT + script

## Phone app (app/) - first version, NOT yet compiled or tested on a phone
- AudioDecoder.kt  mp3/m4a/wav -> 16 kHz mono samples (MediaExtractor + MediaCodec, streaming)
- MainActivity.kt  1 choose ONE ZIP with everything, or files (any of timestamps / audio / script / pictures), 2 choose pictures (ZIP or images), 3 Analyse (scene list + tappable warnings), 4 Export MP4 (black screen + text for scenes without a picture)
- Exporter.kt      Media3 Transformer: one picture per scene + narration -> MP4 in Movies/Auto
- .github/workflows/android.yml builds the APK on GitHub (Actions -> Build APK -> Artifacts -> Auto-debug-apk)

## Not done yet
1. Text over pictures, click sound, music and overlay in the exported video
2. Picture folders (only ZIP files and loose images can be chosen)
3. Storyboard / player / timeline screens (for now: a text list of scenes)
4. Fixing whatever the first real build and phone test show
