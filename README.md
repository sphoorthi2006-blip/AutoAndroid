# AutoAndroid repository

This repository contains three separate Android projects plus a browser extension. They have independent build workflows; the Suko video-editor audio add-on does not replace the original image upscaler.

| Project | Folder | Purpose | Build workflow |
|---|---|---|---|
| **Sukko Image Upscaler** | repository root (`app/`, `extension/`) | AI image upscaler for Android and browser extension | `.github/workflows/build.yml` |
| **Suko** | `suko/` | Offline Android app with Auto Editor, SVG Editor and Text Editor | `.github/workflows/build-suko.yml` |
| **Image Layer Studio Android** | `ils-android/` | Standalone handwriting / slide-rendering Android app | `.github/workflows/build-ils.yml` |

## Suko Android video editor

Suko is a separate offline Android app in [`suko/`](suko/README.md). Its Auto Editor turns images, scripts and SRT timing into a 1080p MP4, with optional voice-over and click sounds. The audio add-on supports voice + clicks, voice-only, click-only, or a silent MP4; AAC audio is muxed into the H.264 export when enabled.

The add-on extends the existing Auto Editor and does not replace the root Sukko Image Upscaler. The Suko project has its own CI workflow: [`.github/workflows/build-suko.yml`](.github/workflows/build-suko.yml). That workflow runs web and Kotlin tests, builds the native upscaler and Android debug APK, and uploads the `Suko-debug-apk` artifact.

Current implementation and phone verification notes are maintained in [`suko/README.md`](suko/README.md). The Suko home-screen SVG Editor now opens the dedicated offline Video Mode inside Android WebView. It includes voice-over/music selection, custom or built-in click sounds with an enable/disable switch, AAC audio mixed into MP4 export, and native saving to Download/Suko. Auto Editor remains unchanged.

## Sukko Image Upscaler

Two versions of the same tool:

| | Where | What it is |
|---|---|---|
| **Android app (Sukko)** | `app/` | One button: "Upscale Images (PNG)". Takes images, ZIPs (any number, mixed) or a whole folder. Always removes the corner mark and upscales 2x with AI. Up to 10 pictures are saved as PNGs; more than 10 are delivered as ZIP files (max 50 pictures per ZIP) in `Downloads/Sukko`. |
| **Browser extension** | `extension/` | Chrome / Edge / Brave extension with a full page and the same features. It uses the classic (no-AI) method. |

## How the Android app works
1. **Collect** the pictures: loose images, every image inside ZIPs, and everything inside a chosen folder and its subfolders. The big counter (`3 / 20`) is pictures done out of pictures found.
2. **Remove the fixed corner sparkle mark** (always on, CPU). This runs one picture ahead on a background thread, so it overlaps with the AI step of the current picture.
3. **AI 2x upscale** with Real-ESRGAN (`realesr-animevideov3`) through [ncnn](https://github.com/Tencent/ncnn), on the phone's **GPU (Vulkan)**. The picture is cut into overlapping tiles; the small line under the counter (`AI step 5/8 · GPU`) is tiles done out of tiles for the current picture, and shows whether the GPU or the CPU is working.
4. **Save** PNGs with the original names to `Pictures/Upscaled` (up to 10 pictures), or stream into ZIP files in `Downloads/Sukko` (more than 10). The before/after slider exists for the single-PNG case.
5. The finished screen shows `AI: GPU · x.x s per picture · total x.x min`, so you can see how fast it was.

### GPU first, CPU only when needed
- The GPU is always tried first. The CPU is used only if the phone has no usable Vulkan GPU, or after a real crash inside the native AI library / GPU driver.
- Swiping the app away or Android closing it in the background does **not** turn the GPU off. If a GPU step fails, the GPU is tried again on the next picture.
- Tile size and the maximum output size depend on the phone's RAM:

| Phone RAM | GPU tile | CPU tile | Max output |
|---|---|---|---|
| under 3 GB (or Android "low RAM") | 128 | 128 | 12 MP |
| 3-6 GB | 256 | 192 | 20 MP |
| 6 GB or more | 384 | 192 | 30 MP |

  Example: a 1376x768 picture is 8 tiles on an 8 GB phone's GPU (384), 18 tiles with 256, and 32 tiles on the CPU (192). So the number after the slash in `AI step` depends on picture size and on GPU vs CPU.
- The app keeps running in the background (foreground service with a notification), and adapts to wide / dual-screen layouts.

Mark removal stays on the CPU on purpose: it is a small, step-by-step job (a 4-point star, about 30 px radius) that does not suit a GPU.

## Classic mode (fallback and browser extension)
Light edge-preserving denoise, 2x Lanczos-3 upscale, gentle sharpen. Pictures larger than about 2.2 megapixels are not doubled. The Android app uses this only when the AI step cannot run.

## Notes
- No internet needed, nothing is uploaded.
- The sparkle position is fixed (scaled to the picture size), so it works for pictures from the same source.
- The build workflow downloads the prebuilt ncnn library and the model files (taken from Upscayl's `models` folder), so they are not stored in this repo.
- Licences: ncnn (BSD-3-Clause), Real-ESRGAN and its models (BSD-3-Clause), Real-ESRGAN-ncnn-vulkan (MIT).

## Get the Android APK
Push to `main` (or run **Build Sukko Image Upscaler APK** in Actions). The release workflow publishes
`Sukko.apk` under Releases -> `suko-latest`. This is the root image-upscaler app, not the Suko video editor.

## Install the extension
See [`extension/README.md`](extension/README.md). In short: open `chrome://extensions`, switch on *Developer mode*,
click *Load unpacked* and choose the `extension` folder (or the unzipped `UpscalerExtension.zip`).
