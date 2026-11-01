# Auto for Android - port of the Auto desktop engine

Every upload to GitHub is checked automatically (Actions tab): the Kotlin core must give the same results as the Python engine.

`core/` = pure Kotlin (no Android code): runs and tests on a PC, reused unchanged by the phone app.

## Done - each part checked against the Python engine (identical results on the test data)
- Captions.kt    SRT, VTT, Whisper JSON (segments/words), timed text lines; watermark removal; caption cues
- Details.kt     Details file ("Image N - Script line: ..."), headings/sections, scoped_items
- Bundle.kt      sorts any mix of files into narration / click / details / script / captions
- Align.kt       script lines -> caption times (segment_words), spoken section titles, no-audio estimate, timeScenes() = the timing part of analyse()
- Matching.kt    picture matching: by number / file-name words / folder name -> section, Hungarian assignment, missing-picture and low-confidence flags (7 scenarios, output identical to Python: testdata/matching)
- Pipeline.kt    ZIP + folder reading (zip-slip safe), analyse() = details + script + captions + audio + pictures -> timed scenes with pictures
- AudioPauses.kt pause detection on 16 kHz mono samples: refineCuts (cuts into real pauses) and anchorCuts (approximate captions)

## Test data
- testdata/files   caption formats + details (py_ref.py = Python reference, py.out = its output)
- testdata/timing  gen.py makes a test narration (audio.f32) + SRT; ref.py/ref.out = Python reference timings
- testdata/matching  gen.py -> scen.txt, ref.py (Python) -> ref.out; Check4.kt (Kotlin) -> kt.out; the two are identical. Check5.kt = ZIP end-to-end.
- core/src/test/kotlin/Check*.kt print the Kotlin results in the same format (compile with kotlinc, diff against the .out files)

## Not done yet
1. (done) Picture matching and ZIP handling
2. Android layer: decode mp3/m4a to 16 kHz samples (MediaExtractor/MediaCodec), file picker, Open bundle screen
3. Storyboard + player + simple timeline screens
4. MP4 export (Media3 Transformer): narration, click sound, music, text, overlay

Note: with audio but NO captions file the phone version estimates timing (Whisper is not ported) and says so in a warning.
