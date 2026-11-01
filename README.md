# Auto for Android - port of the Auto desktop engine

Every upload to GitHub is checked automatically (Actions tab): the Kotlin core must give the same results as the Python engine.

`core/` = pure Kotlin (no Android code): runs and tests on a PC, reused unchanged by the phone app.

## Done - each part checked against the Python engine (identical results on the test data)
- Captions.kt    SRT, VTT, Whisper JSON (segments/words), timed text lines; watermark removal; caption cues
- Details.kt     Details file ("Image N - Script line: ..."), headings/sections, scoped_items
- Bundle.kt      sorts any mix of files into narration / click / details / script / captions
- Align.kt       script lines -> caption times (segment_words), spoken section titles, no-audio estimate, timeScenes() = the timing part of analyse()
- Matching.kt    picture matching (v19: Prompt blocks, section headings from the script become scenes - black screen + text when they have no picture -, folder names like 01_Crafty_Question, one big folder = whole script): by number / file-name words / folder name -> section, Hungarian assignment, missing-picture and low-confidence flags (7 scenarios, output identical to Python: testdata/matching)
- Pipeline.kt    ZIP + folder reading (zip-slip safe), analyse() = details + script + captions + audio + pictures -> timed scenes with pictures
- AudioPauses.kt pause detection on 16 kHz mono samples: refineCuts (cuts into real pauses) and anchorCuts (approximate captions)

## Test data
- testdata/v19       8 scenarios for the Quality 19 behaviour (gen.py -> ref.py Python reference -> ref.out; Check6.kt Kotlin -> kt.out; identical)
- testdata/files   caption formats + details (py_ref.py = Python reference, py.out = its output)
- testdata/timing  gen.py makes a test narration (audio.f32) + SRT; ref.py/ref.out = Python reference timings
- testdata/matching  gen.py -> scen.txt, ref.py (Python) -> ref.out; Check4.kt (Kotlin) -> kt.out; the two are identical. Check5.kt = ZIP end-to-end.
- core/src/test/kotlin/Check*.kt print the Kotlin results in the same format (compile with kotlinc, diff against the .out files)

## Phone app (app/) - first version, NOT yet compiled or tested on a phone
- AudioDecoder.kt  mp3/m4a/wav -> 16 kHz mono samples (MediaExtractor + MediaCodec, streaming)
- MainActivity.kt  1 choose ONE ZIP with everything, or files, 2 choose pictures (ZIP or images), 3 Analyse (list of scenes, warnings), 4 Export MP4
- Exporter.kt      Media3 Transformer: one picture per scene + narration -> MP4 in Movies/Auto
- .github/workflows/android.yml builds the APK on GitHub (Actions -> Build APK -> Artifacts -> Auto-debug-apk)

## Not done yet
1. Text over pictures, click sound, music and overlay in the exported video
2. Picture folders (only ZIP files and loose images can be chosen)
3. Storyboard / player / timeline screens (for now: a text list of scenes)
4. Fixing whatever the first real build and phone test show
