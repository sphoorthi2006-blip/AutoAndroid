# Bezalel Android app (formerly "Suko"; folder and package are still `suko` / `com.suko.app` on purpose)
**STATUS 2026-11-19 (final of this session): all roadmap steps are written and the app BUILDS GREEN on GitHub Actions. The latest code (this README's commit) is on `main`; the APK is at Releases -> `bezalel-latest` (`Bezalel.apk`, unsigned debug build, about 58 MB). Nothing has been run on a phone yet: the phone test lists below are the only thing left, plus the optional signed-release secrets. See `DEVELOPER.md` for the developer guide and `docs/skills/README.md` for the two prompt skills.**

**UPDATE 2026-11-19 (step 37, see the end of this file): the SVG Editor is now a full-window, dark, native-looking app screen (own top bar, pinned preview, tabs) instead of the old white web page, the home screen got premium cards, and the web assets were rebuilt. Web side verified in the sandbox; the Kotlin edits (`WebHostActivity.kt`, `HubActivity.kt`) are NOT compiled here, so the next CI run is their compiler.**

One Android app with three tools. Everything runs inside the app: no website, no server, no internet.

| Tool | What it does | Engine |
|---|---|---|
| **Auto Editor** | Images + script + SRT files + optional voice-over + optional click sounds (+ scene clips) become a 1080p MP4. Export can be voice-only, click-only, mixed, or silent. On Export the images are AI-upscaled first, then the video is rendered (one progress bar). | Cue Video (Kotlin, OpenGL, MediaCodec), native audio mixer and MP4 muxer, upscaler from Sukko (ncnn Vulkan) |
| **SVG Editor** | The home card opens the web Video screen INSIDE the app as a full-window dark app screen (top bar with back arrow + Export, pinned preview with play / timeline, tabs Import / Style / Audio / Images / Export). Pictures + timing JSON + scene clips + voice-over, hand-drawn panel animation, AI-sharpened MP4 export. | `web/src/VideoMode.tsx`, `VideoMode.css`; Python analyser via Chaquopy |
| **Text Editor** | Type text, use the built-in Patrick Hand font, and export 1080p, 2K or 4K at 30 or 60 fps. No upscaling. Custom font import (step 21) remains intentionally skipped. | Image Layer Studio text engine |

**Input rule for all tools:** accept any entry type: loose files, ZIPs (also ZIPs inside ZIPs), folders with subfolders, or a mix. Route every file by its type; never reject a type without telling the user why.

## WHAT THE APP HAS (all features, 2026-11-19)
Home screen (premium dark cards with a gradient badge, tag line and arrow per tool) with four tools: Auto Editor, Upscale Images, SVG Editor, Text Editor. Dark theme `Theme.Bezalel`, Bezalel icon, everything offline (no INTERNET permission), arm64-v8a only.

**Shared for all tools**
- Intake: loose files, ZIPs (also ZIPs inside ZIPs), folders with sub-folders, or a mix; every file is routed by kind (image, video, audio, SRT, text, JSON, font, ZIP) and anything skipped is listed with a reason. Zip-bomb guards.
- AI upscaler (Sukko engine: corner-mark removal, Real-ESRGAN `realesr-animevideov3` 2x through ncnn on the GPU via Vulkan, CPU fallback, classic fallback). Results are cached on content (SHA-256) so nothing is upscaled twice; interrupted work resumes; low-memory retry.
- Background export: foreground service with a notification and wake lock while a long export runs.

**Auto Editor** (images + script + SRT files -> 1080p H.264 MP4)
- Optional voice-over, click sounds (built-in or your own file, on/off), scene clips (video files named by cue number, e.g. `12-14.mp4`).
- Audio modes: voice + clicks, voice only, clicks only, or a silent MP4. Loudness matching, limiter, AAC-LC 192 kbps muxed into the MP4.
- Export step 1 upscales the pictures (switch, default ON), step 2 renders the video; ONE progress bar, cancel works; `<n>_report.csv` is saved next to the MP4. Audio mix test saves a `.m4a`.

**SVG Editor** (opens the web Video screen inside the app; pictures + timing JSON + optional scene clips + voice)
- Picture analyser on the phone (the desktop Python vision code through Chaquopy, Python 3.10 + numpy + OpenCV): finds comic panels, labels, captions, arrows; hand-drawn panel animation with a pen plan; Panel check against the JSON.
- Heading and subscribe slides are plain white screens with black Patrick Hand text written by hand (no zoom, fade, box or colour); manual control per row (Auto / Picture / Heading / Subscribe, editable words).
- Scene videos stay videos: letterboxed, muted, in step with the playhead in the preview and drawn frame by frame in the MP4.
- Timing report and CSV (with `image_kind` column), per-element click sounds, voice-over.
- Export: "Make every picture sharper" (AI upscale, cached between exports, cancel works mid-picture), then the MP4, one progress bar, screen-off protection.
- Looks like an app, not a web page (step 37): dark theme shared with the Text Editor, own top bar (back arrow, title with image count and length, gradient Export button), picture + play / previous / next + timeline pinned on tall phones, five tabs (Import, Style, Audio, Images, Export) with one card each, switches instead of tick boxes, import tiles that turn green when loaded, two-column layout on wide windows.
- Native intake screen with audio controls (voice-over, click sound, audio mix test; `svg/SvgClickPlan.kt`, `SvgAudioViewModel.kt`). The home card now opens the web screen; whether the native audio controls are still reachable is not checked.

**Text Editor** (Layer Studio text engine, Patrick Hand built in)
- Type text (formulas like `x^2`, symbols like the square root), drag the text on the picture, resize, position buttons, colours, background colour or image, shape (16:9 / 9:16 / 1:1), align, handwritten or outline-then-fill motion.
- Upload your own .ttf/.otf/.woff font (kept on the phone). Export 1080p, 2K or 4K at 30 or 60 fps (4K is 30 only) to `Download/Bezalel`.

**Upscale Images tool**: pictures, ZIPs and folders in, PNGs or one ZIP per input out, into a "Bezalel upscaled" folder; names keep their numbers.

## Rules for every session (credit-saving, from the owner)
1. **One step per session:** do the next `todo` row below, then stop. Do not start the next step unless asked.
2. Plan in a few lines, read only the files the step touches, write each file once. No polish passes, no extra features.
3. Run each test suite once at the end. Say plainly what was verified and what was NOT (the sandbox has no Android toolchain, so the APK is built by GitHub Actions. Pure-Kotlin logic CAN be compiled and run in the sandbox: download `kotlin-compiler-2.0.21.zip` from the JetBrains GitHub release, and use a tiny stand-in for JUnit, because Maven is not reachable).
4. **At the end of every step:** update the PROGRESS table and "Last finished step", then give the owner the full source ZIP (no `build/`, no `.gradle/`) and a short test list for that step.
5. Short replies: what changed, what passed, what is next.
6. **README IS THE HANDOVER (owner rule, 2026-11-19):** after EVERY finished step, before replying, update this file so the next Claude session (a different model with no memory of this chat) can continue from it alone: the step's row (DONE / part done / todo, with test counts), the "Done / Next" lines, "WHAT IS TO BE DONE", what was verified and what was NOT, and any decision or blocker the owner made or still owes. Never leave the README behind the code.

## SESSION LOG 2026-11-19 (speed, labels, save progress) - owner request, three changes
1. **Faster preparing of pictures (SVG Editor, "Finding elements...").** Cause found by profiling the analyser on the 4 reference pictures: ~90% of `panels()` time was `_order_outline` in `app/src/main/python/ils/reveal.py` (pure-Python O(n^2) loop, ~20 million `hypot` calls per picture). It is now numpy (same rules, same tie order). **Output is byte-identical** (SHA-256 of the full `panels()` and `process()` JSON on all 4 references, before vs after) and `check_engine.py` passes 14/14. Panel analysis per picture in the sandbox: 3.8 / 9.7 / 4.7 / 6.7 s -> 1.3 / 2.8 / 1.8 / 2.3 s. Second change: `web/src/api.ts` `analyseImage()` looks for panels FIRST and only runs the heavy full separation (`processImage`: segment + trace + pen plan of every element, 6-8 s) when the picture has no panels, because with panels found the app replaces those layers anyway (`panelLayers`). Used by import (`processAll`) and by the export's re-analysis after upscaling. Pictures with no panels behave exactly as before. NOT measured on a phone (phone times will be slower; ratio should hold).
2. **Labels stay as in the picture.** "Rewrite the picture's labels and captions in Patrick Hand" (`replaceText`, Style tab) is now OFF by default, so the picture's own labels and captions are shown exactly as drawn. The option still exists (same place and box, text colour picker, default near-black `#111111`). Heading and subscribe slides are unchanged (always Patrick Hand, black on white). I could not find any code that makes the label text blue (default `#111111`; the blue in the app is only the Panel-check arrow outline and the UI accent), so the blue the owner saw is NOT explained; ask for a screenshot if it comes back with the option ON.
3. **Save progress.** New `web/src/projectStore.ts` (IndexedDB, tested in real Chromium: 12 checks incl. a 3 MB file round trip) + `VideoMode.tsx`: the project (pictures incl. sharpened ones, timing JSON text, scene videos, audio, every Style / Audio / Export setting, the Heading/Subscribe choices) is auto-saved 1.5 s after any change and when the app goes to the background; "Save" button in the top bar, "Save progress" and "Start a new project" in the Import tab; the saved project is restored when the screen opens (pictures are analysed again, about 1-3 s each). Not saved: the playhead, finished MP4s (they are in Download/Bezalel), analysis results.
4. **Audio now plays in the preview (owner report: "audio was not played").** Cause: the preview `Player` in `VideoMode.tsx` had NO audio at all; the voice-over (`audioBuffer`) was only used by the MP4 export, so it was silent while previewing (not a loading problem). Now, while the playhead runs, Web Audio plays the voice-over from the playhead and, when "Use the bundled click.wav" is on, the click sounds at the same moments the export uses (`clickEvents()` is shared by preview and export). Playing restarts from the new place after every jump (previous / next / scrub / tap on the timeline); pause stops it. Needs a tap on Play to start sound (Android WebView rule). NOT tested on a phone; the export's audio path was not changed.
- **Verified:** Python output identical + 14/14 analyser checks; storage module in Chromium; `tsc` shows the same errors as the original zip only (they come from missing npm packages in the sandbox), none new.
- **NOT verified:** the web bundle was NOT rebuilt here (no npm packages in the sandbox): CI runs `npm test`, `tsc` and `npm run build:suko` before `assembleDebug`, so the next CI build ships it, but the old committed bundle in `app/src/main/assets/web/studio/` is stale until `npm run build:suko` is run and committed. The restore flow and the new buttons have never run on a phone. Phone test: add pictures + JSON, close the app completely, reopen the SVG Editor -> project is back and pictures re-analyse; tap Save progress; Start a new project; check labels are the picture's own with the option off.

## SESSION LOG 2026-11-19 (final): what was done after the big session
1. **Update synced from the owner's latest zip (`Bezalel-app-LATEST`) into this repo** (commit `a923f61`): rebuilt Studio web assets, `VideoMode.tsx` / `VideoMode.css`, `HubActivity.kt`, `WebHostActivity.kt`, README updates; stray committed `__pycache__` files removed. GitHub built it green and published `bezalel-latest`.
2. **Images tab tidy-up (commit `d694519`):** the two loose report buttons ("Download report (.txt)", "Download (.csv)") are now ONE full-width "Download report" button that opens a small menu (Text .txt / Spreadsheet .csv). The report preview wraps instead of scrolling sideways; the "Check last frame" row wraps. Web tests (7 suites) and `tsc` pass; the web assets were rebuilt (`index-DUKSzEAZ.js`, `index-DzZ2yDHm.css`); CI build green. NOT seen on a phone.
3. **Prompt skills for this app finalized and stored in `docs/skills/`:** `svg-bh` (Bible and history: NO outer frame, Template A comic canvases, Template B plain text slides) and `svg-hf` (health and finance: strict mandatory outer frame, ribbon headings, Layout 1 / Layout 2, disclaimer canvas with ribbon `DISCLAIMER`). New strict rule in both: a title label never repeats text already written inside its own panel (disclaimer label `A QUICK NOTE`, HF like-and-subscribe label `THANKS FOR WATCHING`, Bible verse label `SCRIPTURE`). Documented in `docs/skills/README.md`.
4. **Findings from reading the code (not run on a phone):** heading and subscribe canvases are replaced by an app-drawn handwritten white slide and need no picture (`textslide.ts`); a HF disclaimer (label + scene) is a normal picture; a ribbon `heading` is part of the picture animation and its handwriting replacement is unconfirmed; export runs under a foreground service; the app has no INTERNET permission (fully offline).
5. **Added `DEVELOPER.md`** (layout, build, release, web-build rule, how the SVG Editor works, open items).

### Phone test list added by this session
- Images tab: tap "Download report", save the .txt and the .csv, open both.
- SVG Editor: one HF canvas with a ribbon heading: is the ribbon written as handwriting or does it stay as the AI drew it?
- SVG Editor: leave out the picture of one subscribe canvas: does the handwritten white slide still appear?
- HF disclaimer canvas (ribbon `DISCLAIMER`, one picture, label `A QUICK NOTE`): plays as one slide?
- Start an export, switch to another app for a minute, come back: did it finish and was the notification shown?
- Install the new APK over an older Bezalel: does it install as an update?

## WHERE WE ARE (read this first; updated 2026-11-19, end of the big session)
**The picture-to-animation converter (old steps 26-28) is now IN THE APP SOURCE, but has never been built or run on Android.** Method (decided to save credits and avoid a risky rewrite): the desktop Image Layer Studio v0.15.7 Python code (`panels.py`, `raster.py`, `reveal.py`, `ink.py`, `segment.py`, `vectorize.py`) is copied unchanged into `app/src/main/python/ils/` and runs inside the app through **Chaquopy** (Python 3.11 + numpy + opencv-python-headless + pillow). `ils/engine.py` = the desktop's `/api/process` and `/api/panels` as plain functions (FastAPI removed) with one entry point `run(method, base64, raster)`; `web/PhoneEngine.kt` calls it for the bridge methods `process` and `panels` (they no longer answer NOT_READY in the app; the JVM test still checks the NOT_READY default). `vtracer` (Rust) is NOT available on Android: `vectorize.py` returns EMPTY traced groups without it. That is fine for the app because it always asks with raster=1 (the picture is drawn from the ORIGINAL-pixel crops, the traced groups are not used). Never call `process` with raster=0 in the app.
- **Verified in the sandbox (desktop Python, same files):** `app/src/test/python/check_engine.py` = 14 checks on the four REAL reference pictures (`docs/reference/`, ref4 = garden/bread layout with no frame and no arrows, added 2026-11-19): ref1 6/6 panels, ref2 5/5, ref3 5/5 with 3/3 arrows each, ref4 5/5 with 0 arrows, every panel has its raster crop and pen plan, `process` gives layers with crops, a broken file gives a clear "unreadable" answer. Speed on the sandbox CPU: 3-8 s per picture (a phone will be slower).
- **NOT verified (needs the GitHub build and a phone):** that Chaquopy 16.0.0 works with AGP 8.5.2 here, that the numpy/opencv wheels exist for Python 3.11 arm64 (Chaquopy's pip repo; if the build fails on a missing wheel, pin another version or Python in `chaquopy { defaultConfig { ... } }` in `app/build.gradle.kts`), the `PhoneEngine.kt` Kotlin (never compiled), real speed and memory on a phone, APK size (Python + OpenCV add roughly 60-100 MB).
- **Changes you must know:** (1) the app is now **arm64-v8a only** (`abiFilters`), dropped `armeabi-v7a` (Chaquopy wheels; modern phones are arm64). (2) New CI step "Check the picture analyser" runs `check_engine.py` with pip numpy/opencv. (3) `docs/reference/` (the 4 real pictures) is now in this repo. (4) `settings.gradle.kts` adds the `https://chaquo.com/maven` repository.
- **Still NOT done (GitHub build is green now, see BUILD RESULT):** **A4** (phone test of speed/memory), the signed release APK needs the owner's keystore (see step 30 row), and everything here must first be built by GitHub. Steps 29 and 30 code exists but was never run.

## BUILD RESULT (2026-11-19, later the same day) - THE BIG SESSION NOW BUILDS GREEN ON GITHUB
Pushed to branch **`bezalel-big-session`** (NOT yet merged to `main`, so the released `bezalel-latest` APK is still the old one; the release step only runs on `main`). Run "Build Bezalel APK" on that branch: web tests, analyser check, Kotlin logic tests, debug APK, native-library check and artifact upload are ALL green. APK artifact `Bezalel-debug-apk` is about 46 MB zipped.
Three fixes were needed, all in CI/Gradle config (no app code changed):
1. **Python 3.10, not 3.11, with pinned wheels.** Chaquopy's repo (`https://chaquo.com/pypi-13.1`) has OpenCV ONLY for Python 3.8 and 3.10 (`opencv-python-headless 4.5.1.48`, arm64, cp310). Unpinned, pip took `opencv-python-headless 5.x` from PyPI as a source package and Chaquopy cannot compile native code. Now `app/build.gradle.kts` has `version = "3.10"` and `numpy==1.26.2`, `opencv-python-headless==4.5.1.48`, `pillow==9.2.0`. Do not unpin them. The analyser was re-tested in the sandbox with numpy 1.26.2 + OpenCV 4.5.5 (closest PyPI wheel to 4.5.1.48): all 14 checks pass; the Python code uses nothing newer than 3.10.
2. CI now uses `actions/setup-python@v5` (3.11) for the "Check the picture analyser" step (the runner's system pip refused installs).
3. The failure step now saves the pip/Gradle error context and the Chaquopy wheel list to the `buildlog-suko` branch (`log.txt`, `wheels.txt`), because GitHub's raw job logs are not readable from the Claude sandbox.
Still true: numpy/OpenCV/Chaquopy only compile and package; whether they RUN on a phone (startup 20-60 s, memory, speed) is untested. Stale text elsewhere in this file that mentions "Python 3.11" means 3.10 now.

## PROGRESS  <-- update at the end of every step
OLD ROADMAP: steps 1-24 DONE (21 skipped on purpose). **Steps 25-30 are OPEN AGAIN (owner decision 2026-11-19).** The owner had closed them believing the picture-to-animation converter was finished; it is NOT (see KNOWN GAP above). Steps 26-28 ARE the converter (= plan A1-A3 in the NEW WORKFLOW section); do them, then 25, 29 and 30. The Layer Studio source (v0.15.7) is in `web/`; the Python vision code (`panels.py`, `raster.py`, `reveal.py`, `ink.py`, `segment.py`) must be read from the Image Layer Studio zip, which the owner still has to send.

**Reading the table below (2026-11-19):** every row is written. Remarks like "never compiled", "not yet built on GitHub" or "not yet built" are OUTDATED for compiling and building: CI built all of it green. What stays true is every "NOT seen on a phone" / "NOT verified on a phone" remark. Total: 30 old steps (21 skipped on purpose, 24 appears twice = export sizes + SVG intake), SVG workflow 1-10, audio 33-36, Upscale Images tool.

| # | Step | Status | Where |
|---|---|---|---|
| 1 | Shell: Bezalel project, home screen with 3 tool cards, Auto Editor = Cue Video unchanged, one CI workflow | DONE (not yet built on GitHub) | `HubActivity.kt`, `build.yml` |
| 2 | Bring the Sukko native upscaler in (JNI `com.suko.app`, CMake, CI fetches NDK, ncnn, models) + one-image upscale self-test | DONE (JVM logic tests pass; native build not yet run on GitHub) | `cpp/`, `upscale/`, `UpscaleTestActivity.kt` |
| 3 | Intake 1: file-kind classifier (image, video, audio, srt, txt, json, font, zip, junk, unknown) + reasons for skipped files | DONE (2 tests run and pass in sandbox) | `intake/FileKind.kt` |
| 4 | Intake 2: read one ZIP into a list of entries (no extraction to disk yet) | DONE (tests pass in sandbox) | `intake/` |
| 5 | Intake 3: ZIPs inside ZIPs (depth limit, size guard against zip bombs) | DONE (tests pass in sandbox) | `intake/` |
| 6 | Intake 4: folder walker (chosen folder with subfolders) | DONE (tests pass in sandbox) | `intake/` |
| 7 | Intake 5: merge loose files + ZIPs + folders into ONE classified list with a skipped-with-reason report | DONE (7 tests pass in sandbox) | `intake/` |
| 8 | Intake 6: shared intake screen (add files / folder / ZIP, list grouped by kind) | DONE (Compose/Android code never compiled) | `intake/`, UI |
| 9 | Auto Editor 1: replace Cue Video's own picker with the shared intake | DONE (never compiled; needs the phone test) | `com.cuevideo.app` |
| 10 | Auto Editor 2: show intake summary and skipped files on the Auto Editor screen | DONE (never compiled; needs the phone test) | `com.cuevideo.app` |
| 11 | Upscale 1: cache and plan. EVERY picture is upscaled (owner: no skipping big ones); cache key from picture content + settings; finished results reused, with tests | DONE (5 tests pass in sandbox) | `upscale/UpscaleCache.kt` |
| 12 | Upscale 2: phase-1 batch runner (one image at a time, cancel, progress) | DONE (5 tests pass in sandbox; real engine adapter comes in step 14) | `upscale/` |
| 13 | Upscale 3: export plan with two phases mapped onto ONE progress bar, with tests | DONE (5 tests pass in sandbox) | `ExportPlan` |
| 14 | Upscale 4: wire phase 1 + phase 2 into `VideoExport` (render uses upscaled images) | DONE (5 tests pass in sandbox; Android glue never compiled) | `VideoExport`, `upscale/ExportUpscale.kt`, `upscale/EngineAdapters.kt` |
| 15 | Upscale 5: failure and low-memory fallback, resume after interruption | DONE (5 tests pass in sandbox) | `upscale/` |
| 16 | Web host 1: offline WebView loading local assets only | DONE (4 rule tests pass; WebView code never compiled) | `web/WebRules.kt`, `WebHostActivity.kt` |
| 17 | Web host 2: Kotlin-JS bridge replacing `api.ts` (read files, save files) | DONE (12 JS + 7 Kotlin tests pass; Android code never compiled) | `web/src/bridge.ts`, `web/BridgeCore.kt`, `SukoBridge.kt` |
| 18 | Web host 3: load the Image Layer Studio bundle, smoke-test screen | DONE (4 bundle tests pass; the smoke screen needs the phone) | `app/src/main/assets/web/studio/`, `web/src/smoke.ts` |
| 19 | Text Editor 1: screen with text entry and position | DONE (12 position checks + 3 Kotlin tests pass; the screen needs the phone) | `web/src/textanim.ts`, `App.tsx`, `HubActivity.kt` |
| 20 | Text Editor 2: built-in fonts | DONE as "Patrick Hand ONLY" (owner's file `web/src/fonts/PatrickHand-Regular.ttf`; the other 5 fonts removed) | `web/src/textanim.ts` |
| 21 | Text Editor 3: import own .ttf/.otf font | SKIPPED (owner asked for Patrick Hand only; say so if you want it back) | |
| 22 | Text Editor 4: preview | DONE (the Studio's live preview with Play again; nothing new was needed) | `web/src/App.tsx` |
| 23 | Text Editor 5: export 1080p at 30/60 fps | DONE for the WebCodecs path (24 export checks pass with a fake encoder); real phone speed NOT measured; native GL fallback NOT built | `web/src/video.ts`, `App.tsx` |
| 24 | Export sizes 1080p / 2K / 4K and font upload | DONE in the web source; CI rebuilds the web bundle (`npm run build:suko`). 4K speed and memory NOT measured on a phone | `web/src/video.ts`, `FontPicker.tsx`, `fontStore.ts`, `ResolutionPicker.tsx` |
| 24b | SVG Editor 1: intake (images + JSON) using the shared intake | DONE (rules: 5 tests pass; screen never compiled) | `VideoMode.tsx` |
| 25 | SVG Editor 2: rough preview with Panel check (panels found vs JSON) | DONE in source (the Video screen's Panel check uses the analyser now; NOT seen on a phone) | `panelmatch.ts`, `VideoMode.tsx` |
| 26 | Vision 1: port `panels.py` to Kotlin + OpenCV Android, check against `docs/reference` | DONE in source via the desktop Python through Chaquopy (A1; 11 sandbox checks on the real reference pictures pass; Android build NOT run) | `app/src/main/python/ils/panels.py`, `engine.py`, `PhoneEngine.kt` |
| 27 | Vision 2: port `raster.py` and `reveal.py` | DONE in source (A2): `raster.py` + `reveal.py` run the same way | `app/src/main/python/ils/` |
| 28 | Vision 3: port `ink.py` and `segment.py` (decide about `vtracer` fallback) | DONE in source (A3): `ink.py` + `segment.py` run the same way; `vtracer` is not on Android, traced groups are empty, the app uses raster crops (decided) | `app/src/main/python/ils/` |
| 29 | SVG Editor 3: export: upscale, then render, one progress bar | DONE in source, NEVER RUN: at Export inside the app (checkbox, default ON) every picture goes through the AI upscaler (`upscale` bridge method, `PhoneEngine.kt`), is analysed again, then the MP4 is drawn; ONE bar (upscale = first third); a picture that fails keeps its normal version. **Later the same day (DONE IN SOURCE, built green on CI):** cache between exports (`PhoneEngine.upscale` uses the Auto Editor's cache folder `cacheDir/upscale` and keys; only real AI results are cached; a cached picture answers `method:"cached"` at once) and cancel mid-picture (page calls `cancelNative()` -> `SukoBridge.cancelWork()` -> `WorkControl.cancelled`, checked in the tile callback; answer `{error:"cancelled"}`; NOT queued behind the running job). Note: if the AI step is stopped, ImageProcessor may still run its classic fallback for a moment before the stop is answered. Not seen on a phone | `VideoMode.tsx` `exportMp4`, `PhoneEngine.kt` |
| 30 | Polish: one theme, background service while exporting, icon, release APK | DONE in source (never built): background service while exporting (`web/ExportKeepAliveService.kt`: foreground service + wake lock, started/stopped by the page via bridge method `keepalive`, used by the Video and Text editors' exports; manifest permissions added), release signing from environment variables (`BEZALEL_KEYSTORE`, `BEZALEL_STORE_PASSWORD`, `BEZALEL_KEY_ALIAS`, `BEZALEL_KEY_PASSWORD`; without them release falls back to the debug key). The icon and the one dark theme (`Theme.Bezalel`) already existed; no new icon was designed. The CI still builds the DEBUG apk; a signed release needs the owner's keystore as GitHub secrets and a release step (not added) | `ExportKeepAliveService.kt`, `PhoneEngine.kt`, `bridge.ts`, `build.gradle.kts` |
| 37 | SVG Editor as a native-looking premium app screen (owner request 2026-11-19: not a white web page; neat, organised, premium overall) | DONE in source. Web side built into the app assets and checked in a headless browser at phone and desktop sizes; `npm test` 292 checks pass, `tsc` clean. Kotlin edits (`WebHostActivity.kt` dark WebView, `HubActivity.kt` home cards) are NOT compiled in the sandbox; the next CI run compiles them. NOT seen on a phone. | `VideoMode.tsx`, `VideoMode.css`, `App.tsx`, `main.tsx`, `styles.css`, `index.html`, `WebHostActivity.kt`, `HubActivity.kt` |

(The owner may ask for several steps in one session; then do them in order and test once at the end.)
(This README lives INSIDE the source ZIP at `Bezalel/suko/README.md`; always update it there and ship the whole ZIP. Never hand over a separate README.)
(The old 13-step plan was split into 30 smaller steps in session 3. Steps 1-2 are unchanged.)

## NEW WORKFLOW: flexible SVG Editor (scenes + headings, like the Auto Editor)  <-- update at the end of every step
Started 2026-11-19. Goal: the SVG Editor (`web/src/VideoMode.tsx`) works like the Auto Editor. Scenes are videos and stay videos (never turned into SVG). Heading and subscribe slides are NOT AI pictures: they are a plain white screen with black Patrick Hand words **written by hand** (the Text Editor's pen engine `textParts`). **No zoom, no fade, no box, no colour.** All lettering is Patrick Hand.
Done in source: **1-9** and **10 part 1** (7 added in the big session). Left: 10 part 2 check, then the phone tests.

**WHAT IS TO BE DONE (updated 2026-11-19, end of the big session):**
1. **DONE 2026-11-19: pushed to branch `bezalel-big-session` and the build is green (see BUILD RESULT). NEXT: install that APK on the phone and run the phone test list; then merge the branch to `main` to publish `bezalel-latest`.** Old text: the Chaquopy/Python/Kotlin parts were never built. If red, paste the red text to Claude; most likely causes: Chaquopy wheels (numpy/opencv for Python 3.11 arm64), AGP/Chaquopy version match, `PhoneEngine.kt` compile errors.
2. **Phone test list** (below): picture slides analysed in the app, scene video preview and export, upscale at export.
3. **Release APK:** the workflow now has the signed-release step (DONE). It runs on `main` only when the repo secret `BEZALEL_KEYSTORE_B64` (the .jks file, base64) exists, together with `BEZALEL_STORE_PASSWORD`, `BEZALEL_KEY_ALIAS`, `BEZALEL_KEY_PASSWORD`; then `bezalel-latest` carries `Bezalel.apk` signed with the owner's key, otherwise the debug APK as before. OWNER STILL HAS TO create the keystore and add the 4 secrets (Settings > Secrets and variables > Actions). Make the keystore ONCE and keep it: a phone will not update an app signed with a different key.
4. **A4:** measure analysis speed and memory on a real phone; decide whether to lower MAX_SIDE (1600 in `engine.py`) or analyse one picture at a time with a warning.
5. Optional later: the Android intake handoff for the SVG Editor (see step 10).

| # | Step | Status | Where |
|---|---|---|---|
| 1 | Detect heading and subscribe canvases from the timing JSON (svg-bh heading canvas = one parchment panel, empty label, verse; subscribe = mentions "subscribe"; numbered line; `system: "heading"`). Verse canvases with a label, scenes and normal canvases stay pictures | DONE (10 detection tests pass) | `web/src/textslide.ts` `classifyTextSlide` |
| 2 | Handwritten white slide: 1920x1080 white, black Patrick Hand, pen follows the letter strokes (borrowed from the Text Editor), no zoom, no fade; used in the preview, the image list and the MP4 export; one click at its start; its AI picture is not needed for export | DONE (12 slide tests pass; NOT yet seen on a phone) | `web/src/textslide.ts` `buildTextSlide`, `VideoMode.tsx` |
| 3 | Manual control per row: choose Auto / Picture / Heading / Subscribe and edit the words (when the JSON is missing or wrong). Works with no timing entry and no picture | DONE (8 override tests pass; not seen on a phone) | `textslide.ts` `resolveTextSlide`, `VideoMode.tsx` |
| 4 | Intake accepts scene videos (mp4, mov, mkv, webm, m4v, avi; loose, in a ZIP or a folder); the clip name gives the cue range (`53.mp4`, `12-14.mp4`, last number wins, like `CueNames.clipRange`). Listed in the sidebar with notes for overlaps and missing cues. NOT yet on the timeline (step 5) | DONE (12 name tests pass; Android intake screen is step 10) | `slides.ts` `clipRange`/`readClips`/`clipNotes`, `VideoMode.tsx` |
| 5 | Scene rows on the timeline: a clip replaces the images of its range, those images are NOT processed into SVG (saves the backend), start/end come from the JSON (first covered cue start to last covered cue end; no entry: follows the row before, default seconds per cue). A cue belongs to the first clip that names it. Export is OFF while a scene is in the project (until step 7) | DONE (9 scene tests pass; not seen on a phone) | `slides.ts` `sceneSpans`/`sceneTimes`, `VideoMode.tsx` |
| 6 | Preview plays the scene video in step with the playhead (muted, letterboxed with black bars; last frame held if the slot is longer than the clip; clip cut if longer than the slot) | DONE (browser video code, NOT tested: no real video or phone here) | `VideoMode.tsx` `SceneVideo`, Player |
| 7 | MP4 export draws scene frames (seek per frame, letterbox, muted); clicks follow the clip-aware plan | DONE (6 export checks with a fake encoder pass: seek + draw per frame, last frame held, cancel closes the clip; the real <video> seek path NOT tested)  | `video.ts` |
| 8 | Labels and captions: Patrick Hand locked in the Video tab (font picker removed there; the Text tab keeps it); verse/parchment text unchanged | DONE | `VideoMode.tsx` |
| 9 | Timing report and CSV show the kind of every row (picture, heading, subscribe, scene); Panel check and the last-frame check skip non-picture rows. CSV has a new column `image_kind` after `image_end`; a scene row's image is e.g. `3-5` | DONE (6 report tests pass) | `slides.ts` `RowKind`, `VideoMode.tsx` |
| 10 | Wire the same flow into the Android SVG Editor screen (part 1 DONE: the SVG Editor card opens the web editor's Video screen INSIDE the app, `?app=video`, like the Text Editor card; never compiled), full test run, README, source ZIP | part 1 done; the web screen has the whole flow now. NOT checked: whether the native shared-intake files (`inputFiles()`) reach it (it has its own file buttons) | `SvgEditorActivity.kt`, tests |

[STALE since the big session: `process` and `panels` now run through Chaquopy via `PhoneEngine.kt`; `BridgeCore.kt` only keeps NOT_READY as the JVM-test default.] Old note: the picture engine answered NOT_READY, so normal PICTURE slides could not be analysed on the phone. Headings, subscribe slides and scene videos do not need it. Owner decided: build the analyser (see DECIDED below).

### DECIDED by the owner on 2026-11-19: build the picture analyser for the phone (option 2). DONE IN SOURCE (see WHERE WE ARE)
Normal picture slides get the same hand-drawn panel animation as the desktop. Plan A1-A3 (= old steps 26-28) were done in one go by running the desktop Python through Chaquopy instead of porting it to Kotlin + OpenCV Android. A4 (phone check: speed, memory, cancel) is still todo and needs the phone.

Verified (sandbox, end of the big session): `npm test` = 290 checks pass (re-run after step 30) (units, bridge, text, export incl. 6 scene-clip checks, text slides, clips, scenes/report), `tsc --noEmit` clean, web bundle rebuilt (`npm run build:suko`); `python3 -I app/src/test/python/check_engine.py app/src/main/python docs/reference` = 14 checks pass (desktop Python on the 4 real reference pictures). NOT verified: the Android build (CI is the compiler: Chaquopy, numpy/opencv wheels, `PhoneEngine.kt`, `upscale` Kotlin), anything on a phone (analysis speed and memory, scene video preview and export, the AI upscale at export, the look), a real `<video>` seek during export.

### Phone test list for the big session (steps 25-29, 7)
1. GitHub Actions is green. Watch: "Check the picture analyser" step, the Gradle step that downloads Python wheels, APK size. If red, paste the red text.
2. Home -> SVG Editor. Add a ZIP with comic-style pictures + the timing JSON. Each picture should say "finding elements..." then show elements (first one may take 20-60 s while Python starts). Note the time per picture and whether any fails with "memory".
3. A picture slide plays with the hand-drawn panel animation in the preview; the Panel check shows numbered outlines; the Report shows picture rows.
4. Add a scene video named like `12-14.mp4`: the row says Scene, the preview plays it muted with black bars, scrubbing works.
5. Export with "Make every picture sharper" ON: one bar "Making picture n of m sharper" then "Drawing the video"; the MP4 shows pictures animated, headings handwritten, scene videos playing. Then OFF: faster, no sharpening. Cancel works.
6. Start a long export, lock the screen for a minute: the notification "Making your video" shows and the export keeps going (step 30).
7. Note memory problems or phone heating and tell Claude (A4).

## How to build the APK (GitHub Actions)
1. The repository is already configured; do not upload a second copy or create another workflow.
2. Push a change under `suko/**`, or open Actions and run **Build Bezalel APK** manually.
3. The workflow runs web tests, TypeScript checks, Kotlin unit tests, rebuilds the offline web assets, and assembles the debug APK with the native upscaler.
4. When green, download **Bezalel-debug-apk** from the run artifacts. The APK is also published as `Bezalel.apk` under Releases -> `bezalel-latest`.
5. If the run is red, inspect the failed step and paste its error output. The separate root `build.yml` is for the image upscaler, not Bezalel.

## Audio add-on (separate feature track: steps 33-36)

This add-on extends the existing Auto Editor. It does not replace the original Bezalel roadmap or the image-upscaling stage.

| Add-on step | Feature | Repository status |
|---|---|---|
| 33 | Native audio mixing | **DONE in source:** optional voice-over; built-in click by default or a custom click file; click enable/disable; loudness matching, limiter and AAC-LC encoding. |
| 34 | MP4 audio/video muxing | **DONE in source:** the AAC track is copied into the H.264 MP4 by the existing `VideoSink` muxer. |
| 35 | Auto Editor export integration | **DONE in source:** voice + clicks, voice only, clicks only, or no audio track (silent MP4). The existing one-bar export progress and cancellation remain in use. |
| 36 | SVG Editor audio controls | **DONE in source (native, never compiled):** the Android SVG Editor screen now has Voice-over / audio input, Click sound (built-in or custom, on/off, one click per panel start time from the JSON, or at slide start when a slide has no panels) and an Audio mix test saving a .m4a, using the Auto Editor's `AudioJob`. The SVG export (step 29) now exists in the web screen; whether it carries the native screen's voice/click choices is not verified. Code: `svg/SvgClickPlan.kt`, `svg/SvgAudioViewModel.kt`, `SvgEditorActivity.kt`. |

### Auto Editor audio rules

- Voice-over is optional.
- Click sounds are enabled by default. They play at each cue/image start except the first cue; scene clips use the existing clip-aware click timing.
- A custom click file replaces the bundled `click.wav`; removing it restores the built-in click.
- With voice-over and clicks enabled, both are mixed. With clicks disabled, the export contains only the voice-over. With no voice-over, enabled clicks produce a click-only audio track. With neither selected, the MP4 has no audio track and is silent.
- The mix is normalized to 44.1 kHz stereo, click gain is matched against measured voice loudness (or the documented fallback), a look-ahead limiter is applied, and AAC-LC at 192 kbps is muxed with the H.264 video.
- The Audio mix test creates a separate `.m4a` for checking. Audio preview playback inside the editor is not part of this add-on.

### Build and verification for the audio add-on

The canonical Bezalel workflow is `.github/workflows/build-suko.yml`. It runs the web tests and TypeScript check, Kotlin unit tests, builds the web assets, builds the Android debug APK with the native upscaler, checks the native library, and uploads the `Bezalel-debug-apk` artifact. A push that changes `suko/**` starts this workflow. The duplicate `android-apk.yml` workflow is retired so there is one automatic Bezalel build.

The new unit tests cover click-only and voice-only mixing at the pure audio-mix layer. The GitHub Actions result is the source of truth for Android compilation and APK build; a real-device export test is still required for audio playback, muxed MP4 compatibility, and actual phone performance.

### Phone test list

1. Export with voice-over + clicks; confirm both are audible and clicks are not excessively loud.
2. Turn clicks off and export with voice-over; confirm only the voice-over is present.
3. Remove voice-over but leave clicks on; confirm only clicks are present.
4. Remove voice-over and turn clicks off; confirm the MP4 plays silently.
5. Select a custom click file; confirm it replaces the bundled click. Remove it and confirm the bundled click returns.
6. Test a project containing scene clips; confirm click timing follows the clip-aware cue plan.
7. Run the Audio mix test and confirm the resulting `.m4a` can be played.

---

## Steps 20-23: what is in them
- **Font (20):** Patrick Hand is the ONLY text font, from the owner's `PatrickHand-Regular.ttf` (bundled in the web view, so it works offline; same file as the native app's font). The Font picker is gone (it shows the font name). The other five fonts and their npm packages were removed. Noto Sans Math stays as a hidden fallback so symbols like √ ∑ π still draw.
- **Step 21 skipped:** importing your own .ttf/.otf was not built because you asked for Patrick Hand only.
- **Preview (22):** already in the Studio: the picture updates a moment after you stop typing, with the writing animation and "Play again".
- **Export (23):** Export section has **30 fps / 60 fps** buttons (default 30) and the button reads "Export 1080p MP4, 60 fps (20 s)". Always 1920x1080 H.264 (wide shape fills it; tall/square are fitted with bars of the background colour). 60 fps has twice the frames, so it takes about twice as long. H.264 levels up to 4.2 are tried so 1080p60 works on phones that have it; if the phone has no encoder for 60 fps the message says to try 30. File name `text-1080p-60fps-20s.mp4`, saved to `Download/Bezalel`. The same fps buttons also appear for the Studio's Image tab; the Video tab is unchanged (30 fps).
- **Not built (needs your phone result first):** the native GL fallback for phones whose web view has no WebCodecs. If your phone shows "no WebCodecs" (Home -> Web test prints this), tell me and the fallback becomes the next job.

### Test list for steps 20-23 (on the phone)
1. GitHub Actions is green. If red, paste the red text.
2. Home -> Web test: write down the WebCodecs and H.264 1080p 30/60 fps lines and send them to me.
3. Home -> Text Editor: the font line says Patrick Hand; type text with a formula like `x^2` and a symbol like √: it draws.
4. Export 1080p at **30 fps**, 10 s: note how long it takes. Open the MP4 in the gallery: 1920x1080, text writes by hand, ends with the whole text shown.
5. Export at **60 fps**, 10 s: note the time (about double). Check the video is smooth.
6. Cancel during an export: it stops and you can start again.

### Verified / NOT verified in steps 20-23
- Verified: `tsc --noEmit` and the web build pass; 188 JS checks pass (including 24 export checks that run the real export loop with a fake encoder: 30 and 60 fps frame counts, timestamps, key frames, progress, cancel); 21 Kotlin tests for steps 16-19 and 23 pass against the rebuilt bundle.
- NOT verified: Gradle/JUnit, Android code, a real WebCodecs encoder, real export speed or memory on the phone, whether a long 60 fps text video finishes, the look of the output.

## Step 19: what is in it
- The home screen's **Text Editor** card is now open. It opens the Layer Studio **inside the app** (the offline web view of steps 16-18: the app answers every request itself; no Chrome, no internet address, no INTERNET permission) in text-only mode: page `web/studio/index.html?app=text`, the Image and Video tabs are hidden and the title says "Text Editor".
- Text entry (formulas `x^2`, `H_2O`, symbols), font, colour, align, size, writing style and shape already existed in the Studio's Text tab. New: **Position across** and **Position up/down** sliders (0-100 % of the free room inside the safe area) and a **Centre the text** button. Not touched = automatic placement exactly as before (the Video tab's lettering is unchanged). At size 100 % the text fills the area, so make the size smaller to have room to move it.
- `tests/text.test.mjs` (12 checks with the real bundled fonts) is part of `npm test`. The old units test needed two example JSON files from the Layer Studio repo that were not in this ZIP, so `npm test` stopped early; they are now in `web/tests/fixtures/`.
- Export of the text video is still the Studio's own export (step 23 tests speed and the native fallback).

### Test list for step 19 (on the phone)
1. GitHub Actions is green ("Run web tests" and "Run logic tests"). If red, paste the red text.
2. Home -> **Text Editor**: it opens inside the app (no browser bar) with the title "Text Editor" and no Image/Video tabs; the handwriting fonts show.
3. Type your own text. Set Size to about 50, then move **Position across** and **Position up/down**: the preview moves; **Centre the text** puts it back in the middle.
4. Switch on airplane mode and repeat 3: same result.
5. Back button returns to the home screen.

### Verified / NOT verified in step 19
- Verified: `tsc --noEmit` and the web build pass; 164 JS checks pass (units, bridge, 12 new position checks); Step16, Step18 and the new Step19 Kotlin tests (11) pass at Kotlin language level 1.9 against the rebuilt bundle.
- NOT verified: Gradle/JUnit, the `HubActivity` edit (Android, never compiled), the real WebView showing text-only mode and the sliders, phone speed.

## Steps 16-18: what is in them
- **Layer Studio source** (v0.15.7, web part only) is copied into `web/`. Changed files: new `src/bridge.ts` and `src/smoke.ts`, `src/api.ts` (inside the app it calls the Kotlin bridge instead of `http://localhost:8000`; in a browser it behaves exactly as before), `src/compose.ts` (`download()` saves through Android inside the app), new `vite.suko.config.ts`. Rebuild after any change: `cd web && npm ci && npm run build:suko` (writes `app/src/main/assets/web/studio/`, which IS committed; CI also runs the web tests). The FastAPI backend is not used: its Python vision code runs inside the app through Chaquopy (not ported to Kotlin).
- **Step 16 `web/WebHostActivity.kt` + `WebRules.kt`:** shows one page from `assets/web/` at `https://appassets.androidplatform.net/`. The app answers every request itself; any other address gets 403 and is counted; no `file://`; no INTERNET permission; Safe Browsing off. Paths with `..`, `%`, `\` or empty parts are refused.
- **Step 17 `bridge.ts` <-> `SukoBridge.kt` + `BridgeCore.kt`:** `nativeCall(method, payload)` (answer is a JSON file read once from `/bridge/<id>`), `saveFile(name, blob)` (base64 chunks, saved to `Download/Bezalel`; older Android: the app's own Download folder), `inputFiles()` (files the app hands over, read from `/input/<n>`; the SVG/Text editors will use this with the shared intake), plus the Android file picker for the Studio's own "choose file" buttons and `alert`/`confirm` dialogs. `process` and `panels` answer "not ready" (steps 26-28) so the Studio shows a clear reason.
- **Step 18:** the Studio bundle and a smoke-test page are in `assets/web/studio/`. Home screen -> Developer tools -> **Web test** runs 8 checks and prints WebCodecs info for step 23; its button opens the Studio. **Layer Studio (raw)** opens the Studio directly.
- Not supported by Android's WebView: folder picking inside the page (`webkitdirectory`). Folders and ZIPs come through the native intake instead.

### Test list for steps 16-18 (on the phone)
1. GitHub Actions is green ("Run web tests" and "Run logic tests" both pass). If red, paste the red text (the WebView code was never compiled).
2. Home -> Web test. Expect "All 8 checks passed", the file `suko-web-test.txt` in Downloads/Bezalel and a "Saved:" message. Write down the three INFO lines (WebCodecs, H.264 1080p at 30 and 60 fps): they decide step 23.
3. Tap "Open the Studio": the Layer Studio opens with its handwriting fonts (Patrick Hand etc.). Switch to Text mode, type something, export PNG: "Saved: Download/Bezalel/composition.png".
4. In the Studio add a picture with its own button: the Android file picker opens. Processing it says picture separation is not built in yet (expected).
5. Switch on airplane mode and repeat 2: same result (nothing needs the internet).

### Verified / NOT verified in steps 16-18
- Verified: web side compiles (`tsc --noEmit`) and builds; 134 JS checks pass (Studio units + 12 bridge checks against a fake host); 15 new Kotlin tests (routes, MIME, bridge, chunked save, bundle completeness) plus the 24 earlier = 39 pass at Kotlin language level 1.9.
- NOT verified: Gradle/JUnit, ALL Android code (`WebHostActivity`, `SukoBridge`, `MediaStoreSaver`, hub and manifest edits), the real WebView (module scripts through the interceptor, file chooser, saving to Downloads, fonts), real phone speed.

## Step 14: what is in it
- `upscale/ExportUpscale.kt` (pure, tested): `prepare(pics, cache, settings, opener)` builds the step-11 plan from the Auto Editor's pictures (name + uri); `swapMap(plan, cache)` gives original uri -> upscaled file for every finished picture (a failed picture is simply absent, so the render uses the original); `note(batch)` gives one plain sentence about failed / classic-method pictures or a stop.
- `upscale/EngineAdapters.kt` (Android): `AiEngine` wraps `Upscaler.run` (mark removal, AI 2x, GPU first) and `ClassicEngine` is the fallback (classic 2x). IMPORTANT: `ImageProcessor.process` catches every Throwable from the AI step and falls back to classic, so a cancel thrown from the tile callback would be swallowed. `AiEngine` remembers the callback's exception and throws it again, and treats a non-AI result as a failure so the runner marks the picture DEGRADED with a reason.
- `VideoExport.run`: phase 1 first (hash pictures, upscale what is not cached, one at a time, cancel works), then phase 2 (audio, frames) with `renderer.loadPicture(swap[uri] ?: uri)`. One bar through `ExportProgress`; labels "Checking pictures...", "Upscaling picture 3 of 12", then the old render labels. Cache folder `cacheDir/upscale`, trimmed to about 2 GB (never the current project). The upscale problem note shows under "Pictures used" in amber.
- Assumptions to confirm: (a) only pictures the video really shows are upscaled (pictures under scene clips, and heading/subscribe cues, are never shown, so they are skipped; size never skips a picture); (b) mark removal is ON; (c) the render-time estimate for the bar split is 90 frames/s (a guess; the bar never goes backwards, only its split between the phases is affected).

### Test list for step 14 (on the phone)
1. GitHub Actions is green (the Android glue was never compiled; paste the red text if not).
2. Auto Editor: small project (5-10 pictures), Export. The bar first says "Upscaling picture n of m", then "Drawing video...", never going backwards. The video looks sharper than before.
3. Export the same project again: upscaling is skipped (all cached), only rendering runs, much faster.
4. Press Cancel during upscaling, then Export again: it continues with the pictures not yet done.
5. Add a damaged picture: the export still finishes and the amber line says 1 picture was used as it is.

### Verified / NOT verified in step 14
- Verified: 24 pure tests (steps 11-15) compile at Kotlin language level 1.9 and pass in the sandbox.
- NOT verified: Gradle/JUnit, the `VideoExport`/`AppViewModel`/`MainActivity` edits and the engine adapters (Android, never compiled), cancel through the real native upscaler, memory behaviour and real speed.

## Steps 12, 13, 15, 24: what is in them
- Step 12 `upscale/UpscaleRunner.kt`: `UpscaleRunner(cache, primary, fallback, onLowMemory).run(plan, isCancelled, onProgress, onPicture)` upscales the plan's pictures one at a time into the cache through an `UpscaleEngine` (writes the finished PNG to the given file, or throws). Progress gives picture index plus tile progress. Cancel works between pictures and inside a picture (no half file stays). Identical or cached pictures are not repeated. One failed picture is recorded and the batch goes on. The real engine adapter (wrapping `Upscaler.run`) is part of step 14.
- Step 15 (same file): out of memory -> `onLowMemory()` then ONE retry; still failing -> the fallback engine (classic method) and the picture is marked DEGRADED with the reason; no result at all -> FAILED (the export must then use the original picture); a full disk stops the batch with the reason "storage is full". Resume: leftover half-written files are removed at the start and finished results are reused, so pressing Export again after a cancel or crash only does the rest.
- Step 13 `upscale/ExportProgress.kt`: ONE bar for two phases. Each phase gets a share equal to its estimated time (default 9 s per picture, corrected from the pictures already done); the bar never goes backwards; with nothing to upscale it starts in the render phase; labels "Upscaling picture 3 of 12" and "Rendering video".
- Step 24 `intake/SvgInput.kt` (+ `SvgEditorActivity.kt`): the SVG Editor takes pictures plus ONE JSON, from files, ZIPs, folders. Pictures sorted by number; with several JSON files the largest is used and the others are named in a warning; same-named pictures get a warning; other types are reported "not used by this tool". The home screen's SVG Editor card is now open and shows the readiness line. Preview and panel check are step 25.

### Test list for these steps (on the phone, after the APK is built)
1. GitHub Actions is green; "Run logic tests" shows Step3-Step7, Step11-18 and Step24 passing. If red, paste the red text (the Android parts were never compiled).
2. Home -> SVG Editor card opens. Add a ZIP (or folder) with pictures and a .json: the line turns green "Ready: N picture(s) and JSON ...". Add a second .json: a warning names the unused one. Remove the JSON (Clear all, add pictures only): "no JSON file yet".
3. Nothing else new: steps 12, 13 and 15 have no screen yet; they are used by step 14.

### Verified / NOT verified in these steps
- Verified: 45 pure tests in total (intake, upscale cache, runner, progress, SVG rules) compile at Kotlin language level 1.9 and pass in the sandbox.
- NOT verified: Gradle/JUnit, the new SVG Editor screen, the hub/manifest edits (never compiled), cancelling through the real native upscaler (an exception thrown from the tile callback must be checked in step 14), real phone memory behaviour.

## Step 11: what is in it
- Owner decision: ALL pictures are upscaled, no "skip big images" rule (all his pictures are the same size). Same process as the Sukko app.
- `upscale/UpscaleCache.kt`:
  - `UpscaleKeys`: key = SHA-256 of the picture's bytes + settings (corner-mark on/off, scale, pipeline name). Same picture from a ZIP, a folder or a second session gets the same key. Change `UpscaleSettings.PIPELINE` whenever the engine or models change, so old results are not reused.
  - `UpscaleCache(dir)`: one PNG per key. A result only counts once fully written (`<key>.part` is renamed to `<key>.png`); empty or half-written files are never used; `cleanLeftovers()`, `trimTo(maxBytes, keep)` (oldest first, never the current project) and `clear()`.
  - `UpscalePlan.build(entries, cache, settings)`: one job for every IMAGE entry (other kinds ignored). `todo` = jobs without a finished result, one per distinct picture content. A picture is never left out; only identical finished work is not repeated.
- No Android code in this step; nothing visible changes in the app. Steps 12-14 use it (batch runner, one progress bar, export wiring).

### Test list for step 11
1. GitHub Actions is green and "Run logic tests" shows `Step11Test` passing. Nothing new on the phone.

### Verified / NOT verified in step 11
- Verified: 5 new tests (26 pure tests in total) compile at Kotlin language level 1.9 and pass in the sandbox.
- NOT verified: the Gradle/JUnit run, and hashing real phone files (speed).

## Steps 7-10: what is in them
- Step 7 `Intake.kt`: `Intake.scan(sources, workDir, wanted, limits)` turns loose files, folders (with sub-folders) and ZIPs (also nested, also ZIPs found inside folders) into ONE list of `IntakeEntry` plus a list of problems. Files from ZIPs are unpacked into `workDir/<n>/<real name>`; loose and folder files stay where the user has them (`node.location` = the Android Uri). `wanted` = the kinds the tool uses; other valid kinds are reported as "not used by this tool". One safety budget covers every source; write errors and unreadable ZIPs become problems, never crashes. `IntakeSummary.kt` groups the result by kind (pictures sorted 2 before 10) and the problems by reason, with a one-line headline.
- Step 8 `AndroidNodes.kt` (folder and file adapters over `DocumentFile`/`ContentResolver`, name and size read once), `IntakeViewModel.kt`, `IntakePanel.kt` (reusable add-and-review panel for later tools) and `IntakeTestActivity.kt` (home screen -> Developer tools -> Intake test). Each add is appended to what was already added; "Clear all" empties it.
- Step 9 `AppViewModel.addAny` and `addFolderAny` now call the shared intake (`runIntake`). The old Cue Video code that unpacked one ZIP level and silently counted other files was removed. New: ZIPs inside ZIPs, ZIPs inside folders, deeper folders (up to 20 levels, was 4). The Auto Editor's own `ingest` rules (master/original SRT, script, voice-over, click, clips, pictures) are unchanged.
- Step 10 The "Your project" section lists what was skipped, grouped by reason with up to 3 example paths (hidden/system files, unsupported types, JSON/fonts "not used by this tool", damaged or too deep ZIPs, files that could not be unpacked). The old line "Ignored N file(s) of other types" is replaced.

### Test list for steps 7-10 (on the phone)
1. GitHub Actions is green; in "Run logic tests" Step3 to Step7 pass. If the build is RED, paste the red text: the Android parts (steps 8-10) were never compiled.
2. Home -> Intake test. Add ONE ZIP that holds pictures and another ZIP with more pictures inside. Expect all pictures counted under "Pictures", sorted names, no error.
3. Add a folder with sub-folders, one ZIP, a .docx and a .json inside. Expect the ZIP content counted, and "Skipped or failed" showing `file type not supported` (docx) and `not used by this tool` is NOT shown here (this screen wants every usable kind, so the .json is counted under JSON files).
4. Tap Clear all: the list empties.
5. Auto Editor -> + Add project -> Files or ZIP bundle: pick a project ZIP (pictures, master SRT, original SRT, script, voice-over). "What I found" fills as before.
6. Repeat with a folder that has a ZIP inside, and with a ZIP inside a ZIP.
7. Add a .docx and a .json with the project: under "Your project" expect `Skipped or failed` with `1 x file type not supported` and `1 x not used by this tool`.
8. Do a small export (5-10 pictures) to confirm nothing else changed.

### Verified / NOT verified in steps 7-10
- Verified: Step 3-7 tests (21) compile at Kotlin language level 1.9 (the Android build's level) and pass in the sandbox.
- NOT verified: everything that touches Android: `AndroidNodes`, `IntakeViewModel`, `IntakePanel`, `IntakeTestActivity`, the changed `AppViewModel`/`MainActivity`, the manifest and hub edits (never compiled), reading real `content://` Uris, large ZIPs and speed on the phone, and the Gradle/JUnit run.

## Steps 4-6: what is in them
- `intake/IntakeModels.kt`: `IntakeItem(path, kind, size)`, `IntakeProblem(path, reason)`, `ScanResult`, `Limits` (max ZIP depth 4, 50,000 files, 16 GB unpacked) and one shared `Budget`.
- Step 4 `ZipReader.kt`: streams ONE ZIP (nothing written to disk, nothing held in memory), gives each file's kind and real size. Unsafe names (`..`, drive letters) are skipped with a reason; damaged or fake ZIPs keep what could be read and add a problem; file-count and size limits stop zip bombs. A ZIP inside is only listed as kind ZIP.
- Step 5 `NestedZip.kt`: opens ZIPs inside ZIPs, still streamed. Paths keep the chain, e.g. `a.zip/b.zip/2.png`. The inner ZIP itself is not listed, its files are. Too deep, fake or broken inner ZIPs add a problem and the outer ZIP carries on.
- Step 6 `FolderWalker.kt`: walks a folder and all subfolders through a small `FolderNode` interface (the real Android folder adapter comes in step 8). Sorted, relative paths, hidden and `__MACOSX` folders skipped WITH a reason, unreadable folders add a problem. ZIP files are kept as kind ZIP with their node, so step 7 can open them.
- Tests: `Step4Test`, `Step5Test`, `Step6Test` (13 tests, ZIPs built in memory).
- Nothing visible changes in the app yet.

### Test list for steps 4-6
1. GitHub Actions is green and "Run logic tests" shows Step3 to Step6 passing.
2. Nothing new on the phone.

### Verified / NOT verified in steps 4-6
- Verified: all 15 intake tests (steps 3-6) compile with Kotlin 2.0.21 and pass in the sandbox (stand-in for JUnit).
- NOT verified: the real Gradle/JUnit run, and the real Android folder and stream behaviour (comes with steps 7-8).

## Step 3: what is in it
- `intake/FileKind.kt`: `FileKinds.of(path)` gives every file exactly one kind: IMAGE, VIDEO, AUDIO, SRT, TEXT (.txt), JSON, FONT (.ttf/.otf), ZIP, JUNK (hidden/`__MACOSX`), UNKNOWN. `skipReason(kind)` returns the plain-words reason for JUNK and UNKNOWN, so nothing is dropped without telling the user. It reuses the extension lists of `CueNames.kt`.
- `Step3Test.kt`: routing by extension (any case, inside paths), junk and unknown named with a reason.
- Nothing visible changes in the app yet.

### Test list for step 3
1. GitHub Actions is green and "Run logic tests" shows `Step3Test` passing.
2. Nothing new on the phone: the app behaves exactly as after step 2.

### Verified / NOT verified in step 3
- Verified: the file compiles with Kotlin 2.0.21 and both tests pass in the sandbox (with a stand-in for JUnit).
- NOT verified: the Gradle build and the real JUnit run (done by GitHub Actions).

## Step 2: what is in it
- **Upscale engine** copied from Sukko into `com.suko.app.upscale`: `NativeUpscaler.kt` and `ImageProcessor.kt` (only the package line changed; corner-mark removal, tiled Real-ESRGAN `realesr-animevideov3` x2, classic fallback) and `cpp/upscaler.cpp` + `CMakeLists.txt` (only the JNI function names changed to `Java_com_suko_app_upscale_...`).
- `Upscaler.kt`: the small front door the exports will use later: `decode`, `run` (mark removal, then AI 2x, GPU first), `toBitmap`.
- **Upscale test** screen (home screen -> "Developer tools" -> Upscale test): pick ONE picture, switch the corner-mark removal on/off, it upscales, saves `<name>_x2.png` to `Pictures/Bezalel` (Android 10+; older Android: the app's own Pictures folder), and shows the result with `AI step n/m`, GPU or CPU, and times.
- **CI** now installs CMake, picks NDK r28+, downloads ncnn (Vulkan) and the three model sizes from GitHub (same as Sukko), runs the tests, builds the APK for arm64-v8a and armeabi-v7a, and fails the build if `libupscaler.so` needs `libomp.so`. The ncnn folder and the models are NOT in the ZIP (git-ignored, downloaded by CI).
- The app name now lives only in `res/values/strings.xml` (`app_name`). The launcher label and the home title both read it.
- New tests: `Step2Test.kt` (mark removal fills the star at the fixed spot and leaves far pixels alone; the classic path doubles small pictures and keeps flat colour; mark off leaves the picture alone).

### Test list for step 2
1. GitHub Actions is green. In the log: "Using NDK 28..", the ncnn and models downloads, "Run logic tests" passes, and "Check the native library is self-contained" lists `libupscaler.so` for both ABIs.
2. Open Bezalel -> Upscale test. It says "AI library: loaded" and shows the phone memory.
3. Pick a normal 1376x768 picture that has the sparkle. Expect: `Method: AI · GPU`, size 2752x1536, the star gone, file in `Pictures/Bezalel`. Open it in the gallery: it should look sharper than the original.
4. Note the times. The old Sukko app measured about 9 s per picture (256 tiles) on a big-RAM phone; this should be similar.
5. Switch "Remove the corner mark" off and pick the same picture: the star must stay.
6. Pick a picture from another source (no sparkle) with the switch on, and look at the bottom-right corner area: it should look unchanged. This decides whether mark removal stays always-on (see decisions).
7. If "AI library: NOT loaded" or a failure text appears, send me the text and a screenshot.

### Not verified in step 2
- Verified: the engine's mark-removal and classic-pipeline logic (3 tests, run on the JVM with stand-in Android classes).
- NOT verified: the Gradle/NDK/CMake build and the JNI link, the GPU and CPU AI paths on a real phone, the new screens compiling (Compose code, never compiled), speed, and mark removal on real pictures.

## Step 1: what is in it
- New app id `com.suko.app`, name **Bezalel**, minSdk 26. It installs next to the old Cue Video and Sukko apps; it does not replace them.
- `HubActivity` is the launcher: three cards. **Auto Editor** opens the Cue Video screen. **SVG Editor** and **Text Editor** are greyed out ("Coming in a later step").
- The Cue Video engine is copied unchanged (still package `com.cuevideo.app`, with its Stage 1-5 unit tests). Only its manifest entry, theme name (`Theme.Bezalel`) and app id changed. The Android Back button returns to the home screen.
- The Auto Editor still has Cue Video's own file picker, no upscaling yet (steps 3-5).

### Test list for step 1 (on the phone, after the APK is built)
1. The app opens on a dark home screen titled Bezalel with three cards; the last two are dimmed.
2. Tap Auto Editor: the Cue Video screen opens ("+ Add project", Export video). Back returns to the home screen.
3. Do a small Cue Video export (5-10 images + SRT + voice-over) to confirm the engine still works after the move. Compare with the old Cue Video app if in doubt.
4. In GitHub Actions, the "Run logic tests" step passes (the old Stage 1-5 tests).

### Not verified in step 1
Nothing was compiled or run: the sandbox has no Gradle/Android SDK. The first real check is the GitHub Actions run. The hub code is new; the rest is the unchanged Cue Video source.

## Decisions and assumptions
- App name is **Bezalel** (owner, spelling assumed from dictation; change it in one place: `res/values/strings.xml`). Three tools, in this order: Auto Editor, SVG Editor, Text Editor.
- Upscale happens at Export, not on import. Upscaled images are cached so a second export does not repeat it. EVERY picture is upscaled, whatever its size; the engine keeps only its own memory protection for huge pictures (owner, step 11).
- SVG Editor shows a rough preview on the original images first; the sharper pixels are used for the final video. (owner)
- Layer Studio screens run in an embedded view inside the app (offline); the full native rewrite of its screens was NOT chosen. (assumption, confirm if wanted)
- The corner-mark removal is tuned to the usual picture source (fixed star position, scaled). It must leave other pictures untouched; to verify on a real image in step 2/5.

## Reference material
- `docs/autoeditor_porting_spec.txt`: the rules the Auto Editor copies from the desktop `make_video.py` (sorting rules, timing, clicks, audio).
- Old Cue Video notes (kept short): audio with "click" in its name is the click sound, the biggest other audio is the voice-over; the SRT named "master" is the master; a .txt is the script; videos are scene clips named by cue number; pictures by number. Export saves `<n>.mp4` and `<n>_report.csv`.

## Step 24: export sizes and font upload
- **Sizes:** 1080p (1920x1080), 2K (2560x1440), 4K (3840x2160), all 16:9, pictures fitted inside. Picker in the Text/Image export section and in the Video tab. Default 1080p. Bitrate = width x height x fps x 0.15. H.264 level is chosen from the size and frame rate (2K30 needs 5.0, 2K60 and 4K30 need 5.1). **4K is 30 fps only**: the 60 fps button is disabled while 4K is selected; the picker marks sizes the phone's encoder rejects (✕) and warns when the estimated file is over 400 MB (the MP4 is built in memory). File names: `text-2k-30fps-10s.mp4`, `video-4k-60s.mp4`.
- **Fonts:** "Upload font..." next to the font list (Text tab and Video tab). .ttf, .otf or .woff (not .woff2). The font is parsed in memory, added after Patrick Hand, selected at once, and kept in the app's IndexedDB for next time. "Remove this font" deletes an uploaded one. Same file name again replaces it.
- The web bundle in `app/src/main/assets/web/studio` is generated: CI now runs `npm run build:suko` before the Android tests, so a push rebuilds it. To rebuild by hand: `cd web && npm ci && npm run build:suko`.

## Upscale Images tool + "Upscale images" switch
- New tool **Upscale Images** (`UpscaleImagesActivity`, `upscale/UpscaleImagesViewModel.kt`, pure rules in `upscale/UpscaleBatch.kt`, tests `Step25Test.kt`). Pictures, ZIPs (also nested) and folders in. Output goes into a "Bezalel upscaled" folder inside the folder the user picks: PNG files (same sub-folders) or one ZIP per input. Names keep their numbers (1.jpg -> 1.png). Shares the upscale cache with the exports.
- Auto Editor export: ONE **Upscale images** switch (default ON) with a visible sentence, a "Step 1 of 2 / Step 2 of 2" line while exporting, and an "Upscaling: ..." line in the result. Reusable composable `UpscaleOption` (for the SVG Editor export later). Off = pictures are used as they are.
- `EngineAdapters` read pictures through `UpscaleBatch.open(job)` (file from a ZIP, or node), so ZIP content works in the engine.
- The video tool is called **Auto Editor** everywhere users see it (the old "Cue Video" header and file names are gone; the Kotlin package stays `com.cuevideo.app`).
- The old one-picture `UpscaleTestActivity` stays in the manifest but the home card now opens the new tool.
- NOT verified: Android code never compiled here (CI is the compiler). `UpscaleBatch` was compiled and run on the JVM.

## Text Editor: new screen from the owner's layout (Text Editor - write / export)
- `web/src/TextEditor.tsx` + `TextEditor.css` replace the old sidebar page for `index.html?app=text` (Image and Video modes are untouched and still use the old Studio page).
- Dark layout: back arrow (new `SukoBridge.back()` closes the screen), Export button, picture (16:9 / 9:16 / 1:1) with a resolution badge, **drag the text on the picture** to move it (bottom-right dot resizes), play button + scrubber + time, tabs **Text / Style / Motion / Export**.
- Text: text box in Patrick Hand, font line + Add font file (uses the step 24 `fontStore`, kept on the phone), Size in px, Position (Center + 4 arrows). Style: colours, background colour or image, picture shape, align. Motion: handwritten or outline-then-fill. Export: 1080p / 2K / 4K, 30/60 fps (4K = 30), length, progress with Cancel.
- The MP4 is saved to Download/Bezalel (the layout says Movies/Bezalel; the saver was not changed).
- 3 new checks in `web/tests/text.test.mjs` (exact px size, size limit, ink box for dragging). NOT verified on a phone: Kotlin compile of `back()`, touch dragging, real export.

## Step 37: SVG Editor as a native-looking premium app screen (2026-11-19)
Owner request: the SVG Editor window must feel like it is INSIDE the app, not like a white web page; the interior must be neat and well organised; the whole app should look premium. Nothing about what the editor DOES was changed (same state, same export, same timing logic); only the screen around it.

**What changed**
- `web/src/VideoMode.tsx`: the render part was rebuilt, the logic was kept. New prop `standalone` (true for the app screen, set in `App.tsx` for `index.html?app=video`): it draws its own top bar (back arrow -> `goBack()`, title + "N images - length", gradient **Export** button that jumps to the Export tab) and its own error line (`report()` also still calls the old `onError`, so the browser Studio keeps working; there it is drawn with `standalone` off as an embedded block under the old header). New `Icon` component (inline line icons, no emoji). New tab state `import | style | audio | images | export`, one card per tab. After a successful export the Export tab says `Saved <name>`.
- `web/src/VideoMode.css` (new, ALL rules scoped under `.se`): dark tokens identical to the Text Editor (`--bg #0b0d12`, `--card #141824`, accent `#7c3aed` / `#8b5cf6`, blue `#38bdf8`, warn `#f5b82e`, ok `#34d399`), gradient `--grad` for primary actions, pill buttons, switch-style checkboxes, custom range and select, import tiles, list cards, progress bar. Layout: phone = one column, the preview + transport + timeline stay pinned on tall screens (`min-height: 760px`), tabs scroll in one row; windows >= 900 px wide = preview on the left, tabs + card on the right, both scroll on their own.
- `web/src/App.tsx`: `app=video` now renders `<VideoMode standalone />` directly (the old Studio header and `.app` shell are no longer used for the SVG Editor). `web/src/main.tsx` marks the page `html[data-app]` and paints html/body dark before React starts, `styles.css` keeps them dark, `index.html` / `app.html` got `viewport-fit=cover`, `color-scheme` and `theme-color`. No white flash, no light page behind the app screens.
- `WebHostActivity.kt`: `web.setBackgroundColor(0xFF0B0D12)` and `OVER_SCROLL_NEVER`, so the native window is dark from the first frame. (Theme and status / navigation bar colours were already `#0B0D12`; targetSdk 34 is not edge-to-edge, so no inset code was needed.)
- `HubActivity.kt`: home screen restyled: brand badge + "Offline video studio", "PICK A TOOL" label, each tool card has a gradient badge, a small tag (Video / Images / Animation / Text), title, blurb and an arrow, plus an "Everything runs on your phone" line. Behaviour and the four click handlers are unchanged. (`ToolCard` now takes `tag` and `colors`.)
- `app/src/main/assets/web/studio/` rebuilt with `npm run build:suko` (CSS 31.8 kB, JS 587 kB). CI rebuilds it again anyway.

**Rules for the next session (so the look stays consistent)**
1. New SVG Editor controls go inside the `.se` scope and reuse the existing classes (`se-card`, `se-tile`, `se-list`, `se-stats`, `se-sum`, `row`, `hint`, `ghost` / `on` / `go` buttons, `label.chk` for a switch). Do not add light colours or inline hex colours; use the CSS variables.
2. `styles.css` still holds the OLD light styles for the Image tab and the browser Studio. They leak into `.se` only where a rule is not overridden, so when a control looks odd in the app, check for an old rule with the same class name (example fixed in this step: `.sliders label` had a two-column grid; `.se .sliders > label` now resets `grid-template-columns`).
3. Keep every tab reachable without scrolling sideways at 360 px width (five tabs share the row now).
4. The browser has no picture analyser, so in a plain browser every picture shows "could not be read"; that is expected. Use the phone (or Chaquopy backend) to judge the picture states.

**Verified in the sandbox:** `tsc --noEmit` clean, `npm test` 292 checks pass, `npm run build:suko` OK, the built bundle loads for `?app=video` and `?app=text` with no page errors and a dark body, headless Chromium screenshots at 390x844 (phone) and 1280x760 (wide) for the empty state and all five tabs with real sample pictures and the example timing JSON.
**NOT verified:** the Kotlin edits compile (CI will tell), the look on a real phone and inside the real Android WebView (fonts, safe areas, keyboard), the pinned-preview behaviour with the on-screen keyboard open, a full export from the new screen on a phone.

**Phone check for this step**
1. Home screen: four cards with coloured badges; every card still opens its tool.
2. Home -> SVG Editor: the screen is dark from the first frame (no white flash), has a top bar with back arrow and a gradient Export button, and nothing looks like a web page.
3. Add a ZIP + JSON: the two import tiles turn green, the preview shows the first picture, play / previous / next and dragging the timeline work.
4. Scroll the tabs content on a tall phone: the preview stays pinned. Rotate the phone: the layout switches to two columns when wide enough.
5. Export tab: shows images / length / audio, resolution buttons, the big Export button; during export one progress block with Cancel; at the end `Saved video-....mp4`.
6. Back arrow closes the screen and returns to the home screen.

**UPDATE 2026-11-19 (Images tab tidy-up): the two loose report buttons ("Download report (.txt)" and "Download (.csv)") are now ONE "Download report" button that opens a small menu (Text .txt / Spreadsheet .csv). The report preview wraps instead of scrolling sideways. Web tests pass and the web build was regenerated; not seen on a phone yet.**
