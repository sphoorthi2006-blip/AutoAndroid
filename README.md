# Sukko (Upscaler)

Two versions of the same tool, with the same processing:

| | Where | What it is |
|---|---|---|
| **Android app (Sukko)** | `app/` | One button: "Upscale Images (PNG)". Takes images, ZIPs (any number, mixed) or a whole folder. Always removes the corner mark and upscales 2x with AI. Up to 10 pictures are saved as PNGs; more than 10 are delivered as ZIP files (max 50 pictures per ZIP) in Downloads/Sukko. |
| **Browser extension** | `extension/` | Chrome / Edge / Brave extension that opens a full page with the same features. |

## What it does
1. **Automatically removes the fixed corner sparkle mark** (no settings needed; it can be switched off with the checkbox).
2. Light edge-preserving denoise, **2x Lanczos-3 upscale**, gentle sharpen. Pictures larger than about 2.2 megapixels are not doubled (to avoid running out of memory).
3. Saves PNGs with the original file names (Android: `Pictures/Upscaled`; extension: single PNGs, one ZIP, or straight into a folder).
4. **Before / After slider** to check the quality: drag the handle to wipe between the original and the result, pinch / scroll / double-tap to zoom into the detail.

No AI model, no internet, nothing is uploaded.

## AI upscaling (Android app)
The app can upscale with **Real-ESRGAN** (`realesr-animevideov3`, 2x / 3x / 4x) running through
[ncnn](https://github.com/Tencent/ncnn) on the phone's GPU (Vulkan), with a CPU fallback and a classic (no-AI) mode.
Pictures are processed in small overlapping tiles, so large images work too.
The build workflow downloads the prebuilt ncnn library and the model files (taken from Upscayl's `models` folder), so they are not stored in this repo.
Licences: ncnn (BSD-3-Clause), Real-ESRGAN and its models (BSD-3-Clause), Real-ESRGAN-ncnn-vulkan (MIT).
The browser extension still uses the classic method.

## Get the Android APK
Push to `main` (or run "Build APK" in the Actions tab). The APK is published at
Releases -> latest -> `Upscaler.apk`.

## Install the extension
See [`extension/README.md`](extension/README.md). In short: open `chrome://extensions`, switch on *Developer mode*,
click *Load unpacked* and choose the `extension` folder (or the unzipped `UpscalerExtension.zip`).

## Notes
- The sparkle position is fixed (scaled to the picture size), so it works for pictures from the same source.
- The extension and the app use the same algorithm, so results match.
