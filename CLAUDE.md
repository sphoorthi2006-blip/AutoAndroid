# Upscaler (AutoAndroid) - project notes for Claude

Read this first. It records what exists, what was done, what is broken or untested, and what to do next.
Repo: `sphoorthi2006-blip/AutoAndroid` (public), branch `main`. Last updated 2026-11-10 (speed work v2.1 pushed, untested on device).

## What the project is
Batch image upscaler in two versions with the same idea:
- **Android app** (`app/`, Kotlin, no external UI libs): pick images / ZIPs / a folder, remove a fixed corner "sparkle" watermark, upscale, save PNGs to `Pictures/Upscaled`, then compare before/after with a slider (`CompareView.kt`).
- **Browser extension** (`extension/`, plain JS: `app.js`, `worker.js`, `compare.js`, `zip.js`, `background.js`): same classic pipeline, no AI. Unchanged by the AI work.

The user (non-developer, tests on an Android phone, phone model unknown) compares results against the **Upscayl** desktop app, which is sharper because it uses Real-ESRGAN. Their images are anime/line-art style, 1376x768 originals.

## Sukko redesign (2026-11-13)
The app is now named **Sukko** with a dark premium UI and ONE action button, "Upscale Images (PNG)". All other options were removed on purpose (crop strip, mark checkbox, scale picker, GPU box, save-format picker). Fixed behaviour: remove mark + AI 2x + PNG; GPU used automatically unless the `gpu_crashed` flag says the last run died (then that one run uses CPU). Input: images / ZIPs / folder (small chooser sheet). Output: <= 10 pictures -> single PNGs in Pictures/Upscaled; > 10 -> streamed straight into ZIP file(s) in Downloads/Sukko, new ZIP after every 50 pictures (`ZipSink` in MainActivity.kt). The before/after slider only exists for the single-PNG case. applicationId stays `com.example.upscaler` so it installs over old builds. The release asset is still named Upscaler.apk (workflow untouched). Not compiled locally; CI is the compiler.

## Layout
```
app/src/main/java/com/example/upscaler/
  MainActivity.kt     UI, batch loop, saving, crash/exit reporting
  ImageProcessor.kt   classic pipeline + tiled AI pipeline
  NativeUpscaler.kt   Kotlin wrapper for the native lib, ONE permanent worker thread
  CompareView.kt      before/after slider
app/src/main/cpp/
  CMakeLists.txt      finds ncnn under ./ncnn/ncnn-*-android-vulkan (fetched by CI)
  upscaler.cpp        JNI: nativeLoad / nativeRun / nativeUsingGpu (Real-ESRGAN via ncnn)
.github/workflows/build.yml   builds APK + zips extension + publishes release
```
`app/src/main/cpp/ncnn/` and `app/src/main/assets/models/` are git-ignored; CI downloads them.

## Processing pipelines (ImageProcessor.process)
Common first steps: `removeMark` (optional) then `cropTopBlack` (optional).
- **Mark removal:** sparkle is a 4-point star (astroid) centred at (1279, 669.5) on a 1376x768 image, radius 30, scaled with image size. Hole is filled by greedy patch-based inpainting (5x5 patches, candidate grid stride 2, copies texture layer by layer from the edge inward). v2.1: candidates are sorted nearest-the-hole first (early exit rejects most quickly), the known-pixel offsets of a patch are precomputed once per hole pixel, and the pixels of one layer are matched on up to 4 threads. Output should be near-identical to before (only ties differ). Known weakness: no structure awareness, so outlines/folds crossing the hole come out broken and smudgy; a tiny orange speck was left near the belt. Position is hard-coded (only works for images from the same source).
- **Classic path** (picker "Classic 2x", and the fallback): 3x3 edge-preserving denoise (sigma 14), Lanczos-3 2x (skipped if image > 2.2 MP), unsharp mask amount 0.4.
- **AI path** (picker "AI 2x/3x/4x", default 2x): tiled Real-ESRGAN, `TILE_CPU=192` / `TILE_GPU=256` source px (chosen from `NativeUpscaler.usingGpu()`), `TILE_PAD=10` overlap that is cropped away, output capped at 30 M pixels (scale is lowered step by step, else classic is used). Any failure falls back to the classic path and `ImageProcessor.aiNote` explains why (shown in the status text).

## AI implementation details
- Model: `realesr-animevideov3` x2/x3/x4 ncnn files (`.param` + `.bin`, ~1.2 MB each), fetched in CI from `https://raw.githubusercontent.com/upscayl/upscayl/main/models/`. (Not available in `xinntao/Real-ESRGAN-ncnn-vulkan` master or its release zips.) Blob names: input `data`, output `output`; input scaled by 1/255, output by 255.
- Library: latest prebuilt `Tencent/ncnn` `*-android-vulkan.zip` release (was `ncnn-20261020`), static, with static OpenMP (`-fopenmp -static-openmp`; CI fails the build if `libomp.so` is a runtime dependency).
- ABIs: `arm64-v8a`, `armeabi-v7a`. APK is ~22.7 MB (was 0.6 MB before AI).
- GPU: Vulkan tried first (fp16 packed/storage), falls back to CPU if no GPU or if GPU inference fails. UI checkbox "Use the phone's GPU". A `gpu_crashed` pref is set before a GPU run and cleared on normal finish, so a native driver crash turns GPU off on next launch.
- `upscaler.cpp` keeps one global `ncnn::Net`, loaded per (scale, gpu) combination; asset manager held as a JNI global ref.

## CI / build facts learned the hard way
- Push to `main` triggers `build.yml` (markdown-only pushes are ignored via `paths-ignore`). Manual run: Actions -> Build APK.
- **NDK must be r28 or newer.** The runner default (r26) fails at link with `undefined symbol std::__ndk1::__hash_memory`, because the prebuilt ncnn needs a newer libc++. The workflow picks the newest installed NDK (runner had 30.0.16248370) and passes `-PndkVer=...`; `app/build.gradle.kts` applies it. It installs `ndk;28.2.13676358` if nothing >= 28 exists.
- CMake 3.22.1 is pinned in `app/build.gradle.kts`; CI installs it via sdkmanager (best effort).
- **Publishing:** the step updates the existing `latest` release **in place** (`gh release upload --clobber`) and keeps the previous app as an extra asset `Upscaler-v1.1-no-AI.apk`. Do NOT delete/recreate the release or create new tags in CI: creating a tag at a commit that changes workflow files returned `403 Resource not accessible by integration`.
- Other tags in the repo (`upscaler-latest`, `imageupscaler-latest`, `voicestudio-latest`) belong to older, unrelated apps. Leave them alone.
- Release downloads: `.../releases/download/latest/Upscaler.apk` and `UpscalerExtension.zip`.

## Working constraints in the Claude sandbox
- The shell has no package-install network, and `github.com`/`raw.githubusercontent.com` downloads from the shell fail. Android cannot be compiled locally, so **CI is the compiler**: push, then read results.
- `gh` only works with repository REST endpoints (`gh api repos/OWNER/REPO/...`); GraphQL and account-wide lists are blocked.
- Poll a run with the FULL commit SHA: `gh api "repos/sphoorthi2006-blip/AutoAndroid/actions/runs?head_sha=<full sha>"` (a short SHA returns nothing). Read logs with `gh api repos/.../actions/jobs/<job id>/logs` (job id from `.../runs/<run id>/jobs`).
- The user is on the free Claude plan with usage limits, which is why building on GitHub `main` was chosen. Never write the user's GitHub token anywhere; a token was pasted into chat earlier and should be revoked.

## Measurements so far (same 1376x768 source, outputs 2752x1536)
- Fine-detail (Laplacian variance): classic APK 165, Upscayl 470, i.e. Upscayl ~2.8x sharper. Classic gradient energy was similar.
- Mark patch of the classic APK looked smudgy at 4x zoom; Upscayl output still contains the mark.

## Decision log (2026-11-10)
v2.1 is the user's FINAL accepted app (22.7 MB). Experiments tried on top of it (GPU fp16 maths option, GPU tile-size picker, AI step timing breakdown, v2.2 ZIP output that cached every result first) were tested by the user, were still slow (AI step ~9 s per 1376x768 picture on GPU, almost all of the time) and were REVERTED. Do not re-add them unless asked. Internal versionCode is kept increasing (7+) with versionName "2.1" so the APK installs over the newer test builds (Android refuses downgrades).
Device numbers for v2.1: read 0.0 s, mark removal 0.1-0.3 s, AI upscale 9.1 s, save ~3 s (hidden by overlap), real time 9.2 s per picture.
Requested next: ZIP input -> ZIP output, streamed straight into the output ZIP with NO extra temporary copies (users run 50-100+ pictures).

## Status / open issues
1. **App closed on the second batch (CPU mode, GPU box unticked).** Fix pushed earlier (commit c56cde8, still untested on device): all native calls run on one permanent thread (`NativeUpscaler.onWorker`). Diagnostics exist: after an interrupted run the app shows why it last closed (Android 11+ `ApplicationExitInfo` reason, or the last Java exception). **Ask the user what message appears at the top after a crash, and whether a second batch works, on CPU and then on GPU.**
2. Not yet tested on a real device: GPU path, 3x/4x, large images, and all the v2.1 speed work below.

## Speed work done in v2.1 (versionCode 4) - pushed, needs a device test
1. **Timing readout:** the status line shows the last picture's read / mark removal / upscale / save times while running, and the average per picture plus real wall time per picture at the end. Also says whether the AI ran on "GPU" or "CPU n threads" (`nativeInfo`). **Ask the user for this line**: it shows which stage is the bottleneck.
2. **Faster mark removal** (see pipelines above).
3. **CPU AI tuning** (`upscaler.cpp`, `tune_cpu`): `ncnn::set_cpu_powersave(2)` and `num_threads = big core count`, only when the phone has >= 2 big cores and fewer than all cores; otherwise ncnn defaults. fp16 options left at ncnn defaults (not forced).
4. **Saving:** new "Save as" picker: PNG (default), WebP lossless (Android 11+, falls back to PNG below), JPEG 95. Bitmap is saved without an alpha channel (`setHasAlpha(false)`).
5. **Tile tuning:** see pipelines (192 CPU / 256 GPU, pad 10). If seams show on the user's images, raise `TILE_PAD` to 12.
6. **Overlap:** next picture is decoded on a background thread and the previous result is saved on another while the current one is processed (max one save in flight; results over 12 MP are saved immediately to limit memory). Recommended scale stays 2x.

Next: read the timing line from the user, then optimise the stage that dominates (ideas: GPU tile size, ncnn fp16 arithmetic on CPU, a mark-removal search ring, WebP vs PNG timings).

## Plan: quality (later)
- Structure-aware mark removal that continues dark outlines through the hole (current patch is the weakest visible part); auto-detect the mark instead of the fixed position.
- Optional second model (`realesrgan-x4plus-anime`, larger and slower) and a model picker.
- Extension could get AI via onnxruntime-web (separate job; extension stays classic for now).

## Licences
ncnn (BSD-3-Clause), Real-ESRGAN and its models (BSD-3-Clause), Real-ESRGAN-ncnn-vulkan (MIT). Models are taken from the Upscayl repository's `models` folder. Keep the README licence note when changing this.
