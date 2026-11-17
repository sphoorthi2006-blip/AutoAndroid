# ils-android

## core/ (pure Kotlin, no dependencies, runs unchanged on Android)
| File | What it does |
|---|---|
| `Srt.kt` | Reads the raw SRT, removes the TurboScribe (and similar) watermark and sound tags |
| `Words.kt` | Word splitting and spelling-insensitive matching (odour/odor, five/5, check ups/check-ups) |
| `Align.kt` | Matches script words to spoken words and gives every script word a time |
| `SlideMap.kt` | Script lines -> slides (own images for headings, disclaimer, subscribe), long-paragraph split, `slides.json`, `slides.srt` |
| `Pen.kt` | Handwriting pen engine: thins letters to a centre line and turns it into pen strokes (port of the desktop skeleton.ts) |
| `Handwriting.kt` | Writes a text bitmap as handwriting: reading order, stroke timing, and what to draw at any moment (`WritePlan.frame(u)`) |
| `Json.kt` | Small JSON reader/writer |
| `Main.kt` | Command-line test runner |

All tunable values are in `SlideMapSettings` (`SlideMap.kt`).

## Run the test here
```
tools/kc.sh core/src/main/kotlin/*.kt                 # compiles with the Kotlin compiler bundled in Gradle
java -cp /tmp/ils-build:/opt/gradle-8.14.3/lib/kotlin-stdlib-2.0.21.jar ils.core.MainKt script.txt raw.srt outDir [splitAfterSec]
```
`sample-output/` has the result for the baking soda video.

## Handwriting test
`core/src/test/kotlin/WriteTest.kt` (PC only) renders the reveal of a text image at several moments. `sample-output/handwriting/` has the results.
`app/src/main/assets/fonts/` has three open-licence handwriting fonts (converted to TTF) for the optional typed-text mode.

## App (`app/`)
One minimal screen (`MainActivity.kt`, no extra libraries). Needed: script, raw SRT, output folder, which give `slides.json`, `slides.srt`, `report.txt`.
Optional, for the MP4: audio file, image folder (files named by image number: `1.png`, `2.jpg`, ...), and `elements.json`. Then `video.mp4` is saved too.

| File | What it does |
|---|---|
| `Pipeline.kt` | The whole job: slide map, then per slide: load image, plan elements, draw frames, encode; audio; join; save |
| `SlideRenderer.kt` | Canvas drawing of one slide at time t: erased base image, pictures popping in, labels written by the pen |
| `VideoWriter.kt` | MediaCodec H.264 encoder + MediaMuxer, frames with exact timestamps |
| `AudioTranscoder.kt` | Any audio the phone can decode -> AAC (.m4a); AAC files are copied as they are |
| `Mp4Mux.kt` | Joins the video and audio tracks into one MP4 without re-encoding |

Core files for the element stage: `ElementList.kt` (elements.json), `Elements.kt` (detection, slots, label split), `Choreography.kt` (matching, trigger times, draw times).

### elements.json
Made outside the app (by the AI model that writes the image prompts), one entry per element, in narration order. See `sample-output/elements.example.json`.
```
{ "slides": [ { "image": 6, "elements": [
    { "label": "skin", "type": "icon", "sentence": 0, "trigger": "skin", "row": 1, "col": 1 },
    { "label": "3 : 1", "type": "text", "sentence": 2, "trigger": "three parts", "row": 3, "col": 1 } ] } ] }
```
`image` = image number in slides.json. `type` = `icon` (picture, with its label if it has one) or `text` (words only). `sentence` = sentence number in slides.json, starting at 0.
`trigger` = the word(s) at which the element appears. `row`, `col` = where it sits in the image, starting at 1 (rows top to bottom, columns left to right).
Images with no entry are shown as they are (a heading image is written as handwriting).

## Build
Open the folder in Android Studio (Koala or newer, JDK 17+) and run the `app` configuration, or from a terminal:
```
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew :core:compileKotlin       # core only, no Android SDK needed
```
GitHub Actions (`.github/workflows/build.yml`) builds the debug APK on every push and attaches it to the run.

## Test
`./gradlew :core:elementsTest` runs `core/src/test/kotlin/ElementsTest.kt` on a synthetic image (detection, slots, matching, label split, timing, pen plan). It runs in CI too.

## Known limits
- Not yet run on a real phone with real images: the Android part was compiled against the Android 34 API but its first real run is yours. Expect to tune `ElementSettings`.
- The export runs while the app is open (screen is kept on). Leaving the app can stop it.
- Hidden elements are erased with the image's single background colour, so a textured or shaded background shows patches until the element appears.
- The video is always 16:9.
