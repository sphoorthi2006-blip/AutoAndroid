# AUTO ANDROID: HANDOVER (Checkpoint 02 = steps 12-21; since then steps 11 and 22-26 done; next = step 27; app compiles on GitHub, NOT yet run on a phone)

Read this file first. You (a new Claude session or account) have no memory of earlier chats. Everything you need is in this zip.

## 1. The job
Redesign the Android app in this repo as a premium dark video editor (charcoal + violet), keeping the existing pipeline (import, ZIP, script/SRT/VTT parsing, scene analysis, picture matching, audio decoding, timeline, MP4 export). Only the Android app is redesigned; the desktop app (Auto_Desktop_srt_driven.zip) is a READ-ONLY reference (its fonts/bundled folder holds OFL fonts Inter, Noto Serif, Nimbus Mono, Z003 that we reuse).
Owner rules (non-negotiable):
- Work in numbered steps. Never mark a step complete unless it is saved AND verified. Say plainly what is only "written".
- Never show a control that does nothing. Unavailable features are labelled "Soon / not available" and explain why.
- Every 10 COMPLETED steps = one Auto_Android_Checkpoint_NN.json (valid JSON, same fields as docs/Auto_Android_Checkpoint_01.json) + one matching Auto_Android_Source_NN.zip. Checkpoint 02 = steps 12-21 (done). Step 11 is still open; Checkpoint 03 = next ten completed steps.
- Do not put Markdown inside the JSON files.

## 2. How the repo is laid out
- core/ : pure Kotlin/JVM logic, NO Android classes. All new logic goes here with a CheckNN.kt test. This is where verification is possible.
- app/ : Android + Jetpack Compose + Media3 Transformer 1.3.1. NEVER COMPILED by anyone yet (no Android SDK / Google Maven in the sandboxes used so far). Expect small compile errors on the first build.
- scripts/run_parity.sh : runs every test. scripts/dev_check.sh CheckNN : runs one test, prints only failures + "ALL PASS".
- docs/ : checkpoint 01 JSON and redesign-shell.patch (history only; its contents are already in this tree).
- AUTO_ARCHITECTURE.md, AUTO_PROGRESS.md, AUTO_TODO.md : older project docs.

## 3. How to run the tests (about 40 s)
Needs JDK 17+ and a Kotlin compiler. No kotlinc? Download it from GitHub (works in restricted sandboxes):
  curl -L -o k.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip && unzip -q k.zip -d kt && export PATH=$PWD/kt/kotlinc/bin:$PATH
Then:  KOTLINC=kotlinc bash scripts/run_parity.sh      (one test: bash scripts/dev_check.sh Check17)
Last full result (all pass): picture matching identical to Python; ZIP OK; v19 identical; timeline 27; docx/validation/plan 21; confidence/pause/store 30; caption timing 14; free scene length 39; export shapes 19; style/fonts/animation/guides 52; effects/transitions/text track/plan 44; audio mix/waveform/caption tools 36. Two suites SKIP (owner's private data; python fastapi+ffmpeg missing). Nothing has been run on a phone.

## 4. Step status
Total planned: 21 steps to Checkpoint 02, then roughly 10 more app-wiring steps (22-31) to Checkpoint 03; later steps are estimates.

| Step | Title | Status |
|---|---|---|
| 1-10 | Home, Settings, shell, export aspect ratios (see docs/checkpoint 01) | DONE (core verified; app uncompiled) |
| 11 | Push branch, run GitHub Actions "Build APK", fix compile errors | DONE + VERIFIED 2026-11-02: branch `redesign` (commit 37881b7) pushed; Build APK run 41421134804 succeeded with NO source changes (app/ compiles; debug APK 10.9 MB at Releases "latest"); Check core run 41421111198 succeeded. NOT run on a phone yet. |
| 12 | OverlayStyle: one style for captions and text (font, weight, colours, HEX, outline, shadow, background, position), 8 text + 6 caption presets, saved-style library, TextWrap | DONE + VERIFIED (Check15) |
| 13 | TextAnim: fade/slide/pop/zoom/typewriter/bounce maths, alignment guides, safe area | DONE + VERIFIED (Check15) |
| 14 | Effects (brightness/contrast/saturation/opacity as one colour matrix), transitions (Fade, Slide in, Zoom in), text track, duplicate clip, plan carries all | DONE + VERIFIED (Check16) |
| 15 | AudioMix: volume, fades, mute, music bed, PCM gain, waveform peaks, saved in timeline JSON | DONE + VERIFIED (Check17) |
| 16 | CaptionTools: import SRT/VTT/JSON, export SRT/VTT, split/merge/shift/add/delete, reading-speed checks | DONE + VERIFIED (Check17) |
| 17 | MediaLibrary model: categories, search, sort, multi-select, file info | DONE + VERIFIED (Check18) |
| 18 | ProjectPrefs: export prefs in manifest, recent-projects summaries for Home | DONE + VERIFIED (Check18) |
| 19 | RenderCaps: which features the exporter renders (VERIFIED / WRITTEN / UNAVAILABLE). Nothing may become VERIFIED before a device test | DONE + VERIFIED (Check18). NOTE: statuses say WRITTEN for features whose exporter code is NOT written yet (step 22+): set them to UNAVAILABLE or write the code before showing any control |
| 20 | Workflow status machine (Waiting, Importing, Analysing, Ready, Exporting, Completed, Failed) + analysis report | DONE + VERIFIED (Check18) |
| 21 | DesignTokens: charcoal + violet palette, type scale, WCAG contrast check | DONE + VERIFIED (Check18); Auto_Android_Checkpoint_02.json + Auto_Android_Source_02.zip written |
| 19b | RenderCaps honesty fix: only pictures, aspect and captions (what Exporter.kt really contains) are offered; captionStyle, text, textAnim, fx, transitions, volume, music are UNAVAILABLE ("Planned: export code not written"). New guard test: every offered feature id must be in RenderCaps.exporterImplements. When step 22+ writes an exporter feature, add its id there and flip its status to WRITTEN in the same commit | DONE + VERIFIED (Check18, 50+ checks) |
| 22a | Palette swap: Theme.kt object C now reads DesignTokens (charcoal + violet + lavender, Pink name = danger red, flat top bar); Export button in the editor is the violet primary | WRITTEN ONLY: app/ still never compiled, never seen on a device |
| 22+ | App wiring (all uncompiled until step 11 works): palette swap in Theme.kt; Exporter using OverlayStyle for captions; text overlays + animation via Media3 OverlayEffect/BitmapOverlay on the whole Composition (per-frame getOverlaySettings); per-clip RgbMatrix; Fade/Slide/Zoom via time-varying effects; AudioProcessor using AudioMath.applyGain + music sequence; Text Studio, Captions, Media, Audio, Effects sheets; Projects page wired to ProjectStore; bundle fonts from desktop fonts/bundled into app/src/main/res/font (names inter_regular, notoserif_regular, nimbusmono_regular, z003_mediumitalic) | pending |

Lost work note: an earlier session did steps 12-14 but its files were lost; they were REBUILT and re-verified here (different code, same goals).

## 5. Technical decisions to keep
- All sizes in OverlayStyle are fractions of frame height (or of font size): preview and any export resolution/aspect look identical.
- Effects limited to what Media3 1.3.1 can render: brightness, contrast, saturation, opacity (via RgbMatrix, needs opaque pictures). Blur, sharpen, vignette, dissolve, wipe are listed in EffectCatalog/TransitionKind as NOT rendered: never show sliders for them.
- Transitions are entrances at the start of a picture (FADE also fades the previous one out = dip through black). There is no overlap/crossfade in 1.3.1.
- Fonts: static files have only Regular/Bold; other weights need API 28+ (FontCatalog.offeredWeights(sdk) decides what the UI offers).
- Narration is never trimmed or offset (video length == narration length). Music starts at 0, loops or stops; no start offset, no narration split, no video clips on the timeline (image-only pipeline).
- Timeline JSON stays version 1; new fields (fx, transition, texts, captionStyle, mix) are optional so older project files load. Edits are immutable snapshots, so undo/redo works for every new operation.
- Text/overlay time is the composition timeline: apply overlays at Composition level, not per picture item (unverified: confirm on a device).
- Palette: current app uses navy/cyan/pink (object C in Theme.kt). Brief asks charcoal + violet: change VALUES of C, keep the names.

## 6. Next exact action
STEP 11 (owner): git clone https://github.com/sphoorthi2006-blip/AutoAndroid ; cd AutoAndroid ; git checkout -b redesign origin/mobile-editor-ui ; git am redesign-02.patch (delivered next to this zip) ; git push -u origin redesign ; GitHub > Actions > "Build APK" > Run workflow > branch "redesign". On failure the compiler log is pushed to branch `buildlog` (public; a Claude session can `git clone` it and read build.log). Fix errors in small commits, change no behaviour, repeat until the APK appears under Releases ("latest") / Actions artifacts. Earlier log (old commit 9d07393) showed Gradle resolves all dependencies on GitHub, so only Kotlin errors are expected.
(Old text follows, partly done:)
1. (Owner's PC) Step 11: push, run "Build APK", fix compile errors without changing behaviour.
2. (done) Steps 17-21 are written and verified; Check18 is in run_parity.sh. After step 11 compiles, start step 22 (app wiring, list in section 4).
3. Before each batch: run the suite, read this file, confirm the last completed step, do not redo finished work.

## 6b. Session log (after Checkpoint 02)
- GitHub repo https://github.com/sphoorthi2006-blip/AutoAndroid had NO `redesign` branch (branches: main, mobile-editor-ui, srt-first-port, warnings-list, buildlog). So step 11 was not done; buildlog still only holds the old 9d07393 log.
- The Claude sandbox cannot reach dl.google.com, maven.google.com, repo.maven.apache.org or services.gradle.org (only github.com and a few package hosts), and has no push credentials. So an APK cannot be built or pushed from there. Only core/ can be verified. Step 11 must be done by the owner (section 6).
- Baseline reproduced with kotlinc 2.0.21: every suite passed exactly as listed in section 3.
- Steps 19b and 22a above were done. Checkpoint 03 is NOT due: it needs ten completed and verified steps.

## 7. Known problems
- app/ never compiled (see step 11). Aspect ratios other than 16:9 never seen on a device. 4K/60 fps heavy for phones. Home "Recent project" shows only the open project. Overlay-on-Composition timing and RgbMatrix with images are assumptions to test on a device.


## 6c. Session log: step 11 done
- Pushed `redesign` and dispatched Build APK from the chat with a short-lived token. First-ever compile of app/ passed without edits. Next: step 22 (Exporter work) then device test. Owner must install app-debug.apk from Releases and report crashes/visual problems.

## 6d. Batch 2a: steps 22-26 (small steps, ZIP every 5 steps; JSON checkpoint every 10 = Checkpoint 03 at step 31)
Each step was pushed to branch `redesign` and the "Build APK" workflow compiled it (status = COMPILES, not seen on a device).
| Step | What | Status |
|---|---|---|
| 22 | Palette swap (Theme.kt object C reads DesignTokens: charcoal + violet) | COMPILES (run 41421134804 and later); look not seen on a phone |
| 23 | Fonts: Inter, Noto Serif, Nimbus Mono, Z003 (OFL, from desktop fonts/bundled) in app/src/main/res/font; FontResolver.kt (OverlayStyle -> Typeface, weights by API level) | COMPILES |
| 24 | OverlayRenderer.kt: draws any OverlayStyle text on a Canvas (font, weight, italic, underline, strike, spacing, line spacing, align, case, colour, gradient, opacity, outline, shadow, box, padding, rotation, anim frame). Exporter burns captions with it (plan.captionStyle). RenderCaps: captionStyle WRITTEN. Position rule: (x,y) = box centre; LEFT align -> x is the left edge, RIGHT -> right edge | COMPILES (first attempt failed: parameter `style` shadowed Paint.style; fixed) |
| 25 | TextBitmapOverlay.kt + Exporter: text overlays with animation drawn as ONE full-frame transparent bitmap per moment, applied with OverlayEffect on the whole Composition. RenderCaps: text, textAnim WRITTEN | COMPILES (OverlayEffect needs Guava ImmutableList). ASSUMPTION: presentationTimeUs = composition time |
| 26 | ClipEffects.kt: ClipColor (RgbMatrix from ClipFx + fade) and ClipMove (MatrixTransformation: slide/zoom) per picture, after Presentation. RenderCaps: fx, transitions WRITTEN | COMPILES. ASSUMPTION: per-item presentationTimeUs starts at 0 for each picture; RgbMatrix works on letterboxed picture frames |
Still UNAVAILABLE in RenderCaps: volume, music (step 27 = audio gain + music sequence), blur/sharpen/vignette/dissolve/wipe (library limit).
No UI can create text clips, fx or transitions yet (Text Studio = step 28+); the exporter code is ready for them and is only exercised when a Timeline contains them.
Lessons: build with the GitHub token flow in section 6 (push redesign, dispatch workflow 403286994 on branch redesign, read branch buildlog on failure). In the Claude sandbox run bash scripts with `bash`, not `sh` (process substitution).
Next steps (small): 27 audio gain + music in export; 28 Text Studio sheet (preview with OverlayRenderer); 29 Captions sheet; 30 Effects/Transitions sheet; 31 Media/Audio sheet + Projects page, then Checkpoint 03.

## 6e. Batch 2b (steps 27+)
| Step | What | Status |
|---|---|---|
| 27 | AudioGainProcessor.kt (volume/fades/mute via AudioMath.applyGain) on narration; music = extra looping audio sequence with own gain; RenderCaps volume+music WRITTEN, Check18 updated | see build result |
ASSUMPTION (device): looping music may restart its fade per loop (processor frame counter resets at flush); looping sequence ends with the video.
