# Bezalel: developer README

For the next developer or Claude session. Read this first, then `README.md` (status, step table, phone test lists).
Last updated 2026-11-19.

## What this is
Bezalel is an offline Android app (package `com.suko.app`, folder `suko/`; "Suko" is the old name) with four tools on the home screen:
Auto Editor (images + script + SRT to MP4), Upscale Images (Sukko AI upscaler), SVG Editor (comic pictures + timing JSON to a hand-drawn animated MP4) and Text Editor (typed text to a handwriting MP4).
No internet permission. arm64-v8a only. Everything runs on the phone.

## Repository layout (this repo, `sphoorthi2006-blip/AutoAndroid`)
| Folder | What |
|---|---|
| `app/`, `extension/` (repo root) | Sukko Image Upscaler Android app and browser extension (separate project, workflow `build.yml`) |
| `suko/` | **Bezalel** (this project, workflow `build-suko.yml`) |
| `ils-android/` | Image Layer Studio Android app (separate, workflow `build-ils.yml`) |

## Inside `suko/`
| Path | What |
|---|---|
| `app/src/main/java/com/suko/app/` | Kotlin: `HubActivity` (home), `WebHostActivity` (shows the web screens inside the app), `web/` (bridge to the web UI, `PhoneEngine`, `ExportKeepAliveService`), `intake/` (file kinds, ZIP and folder reading), `upscale/` (JNI upscaler), `svg/` (native SVG audio controls) |
| `app/src/main/java/com/cuevideo/` | Auto Editor engine (Cue Video: OpenGL, MediaCodec, audio mixer, MP4 muxer) |
| `app/src/main/cpp/` | Native upscaler (ncnn + Vulkan). The `ncnn/` folder and the models are NOT committed; CI fetches them |
| `app/src/main/python/ils/` | Picture analyser (desktop Image Layer Studio code, runs on the phone through Chaquopy: panels, arrows, pen plan) |
| `web/` | React + TypeScript web UI: `VideoMode.tsx` (SVG Editor screen), `TextEditor.tsx`, `textslide.ts` (heading / subscribe slides), `textanim.ts` (handwriting), `slides.ts` (timing JSON), `panelmatch.ts`, `compose.ts`, `video.ts` (MP4 export) |
| `app/src/main/assets/web/studio/` | The BUILT web UI that ships in the APK. Generated; see "Web build" below |
| `docs/reference/` | Four real reference pictures used by the analyser check |
| `docs/skills/` | The two prompt skills (`svg-bh`, `svg-hf`) and their README. Not part of the app build |

## Build and release (GitHub Actions only)
There is no Android toolchain in the sandbox. The APK is built by `.github/workflows/build-suko.yml`:
1. Fetch prebuilt ncnn (Vulkan) and the Real-ESRGAN models.
2. Web tests, `tsc`, picture-analyser check (`check_engine.py`, all pass), Kotlin unit tests.
3. Build the web UI into the app, then `assembleDebug` (NDK r28+, CMake 3.22.1).
4. Verify the native library has no `libomp.so` dependency.
5. Optional signed release APK (only on `main` and only when the repo secrets `BEZALEL_KEYSTORE_B64`, `BEZALEL_STORE_PASSWORD`, `BEZALEL_KEY_ALIAS`, `BEZALEL_KEY_PASSWORD` exist).
6. On `main`: publish `Bezalel.apk` to Releases -> `bezalel-latest` (the previous release is deleted and recreated).
The workflow runs on pushes that touch `suko/**`. A commit message containing `[skip ci]` skips it.

**Download:** `https://github.com/sphoorthi2006-blip/AutoAndroid/releases/download/bezalel-latest/Bezalel.apk` (the repo is private: open it while logged in to GitHub). Without the keystore secrets the APK is an unsigned DEBUG build.

## Web build (important)
The web UI is built into `app/src/main/assets/web/studio/` and that output is COMMITTED. After any change under `web/src/`:
```
cd suko/web
npm ci --no-audit --no-fund
npm test               # 7 unit/bridge/text/export/textslide/clips/scenes suites
npx tsc --noEmit
npm run build:suko     # regenerates the assets; commit the new files and delete the old hashed ones
```
Forgetting `build:suko` means the APK ships the old screen.

## How the SVG Editor works (short)
1. Inputs: pictures (ZIP / folder), timing JSON (`format: ils-slides`, `version: 2`), optional scene videos (named by cue range, e.g. `12-14.mp4`), optional voice-over and click sounds.
2. Each canvas in the JSON becomes one row with a start and end time.
3. `textslide.ts` classifies each row. Subscribe (one panel or fewer mentioning "subscribe") and heading (one panel, empty label, short `verse`; or a short numbered line; or `system` heading / text) rows are drawn by the app: white screen, handwritten black Patrick Hand, no zoom or fade. The AI picture is NOT used for these, so no picture is needed.
4. Picture rows go through the analyser (panels, labels, arrows), then a pen plan animates panel by panel; labels and captions are rewritten in Patrick Hand ("Write headings and captions with handwriting" switch, default on). A ribbon `heading` in the JSON is part of the picture animation; whether its words are re-written as handwriting is NOT confirmed (the code skips heading replacement when panels were found).
5. Each row can be forced to Auto / Picture / Heading / Subscribe with editable words (Images tab).
6. Export: optional AI sharpen (cached by SHA-256), then the MP4 (H.264 + AAC). It runs under `ExportKeepAliveService` (foreground service, wake lock) so it can continue in the background.
7. The Images tab ends with ONE "Download report" button that opens a small menu: Text (.txt) or Spreadsheet (.csv).

## The prompt skills (docs/skills)
`svg-bh` (Bible and history) and `svg-hf` (health and finance) produce the image prompts and the timing JSON this app consumes. Their rules and the JSON format are in `docs/skills/README.md`. Key points that affect the app:
- BH has NO outer frame; HF ALWAYS has a strict outer frame.
- A title label never repeats text written inside its own panel.
- HF disclaimer = its own one-panel canvas with ribbon `DISCLAIMER` and label `A QUICK NOTE`.
- The JSON `label` must equal the label in the prompt; the app pairs labels to panels by position.

## Rules for working on this repo
- One step per session; update `README.md` (status, step table, verified / not verified) at the end of every step.
- Say plainly what was verified and what was NOT. Never claim something works on a phone unless it was run on a phone.
- Do not add an INTERNET permission. Do not commit `build/`, `.gradle/`, `local.properties`, keystores or tokens.
- Do not create another workflow for Bezalel; extend `build-suko.yml`.

## Known open items (2026-11-19)
- Nothing has been run on a phone. Use the phone test lists in `README.md`.
- Unconfirmed: handwritten ribbon headings, a text slide with no picture loaded, background export with the screen off, an update install over an older Bezalel.
- Signed release APK needs the owner's keystore secrets.
- This GitHub session can create commits and branches but cannot create Releases or tags by hand (the workflow itself can).
