# Manva (AutoAndroid) — Quick Start Guide (`START_HERE.md`)

Follow these steps to open the project, run the parity/unit test suites, build and install the APK on your phone, and export a video quickly.

---

## 1. Prerequisites on Your Computer
- **Android Studio Ladybug / Koala** (or command-line **Android SDK Platform 34**, Build-Tools 34.0.0)
- **JDK 17** (`JAVA_HOME` pointing to OpenJDK 17)
- **Kotlin 2.0.21** (`kotlinc` on `PATH` if running `scripts/run_parity.sh` outside Android Studio)

---

## 2. Open & Build the Project

### Option A: Using Android Studio (Recommended)
1. Unzip `AutoAndroid-complete.zip` to a local folder.
2. Open **Android Studio** -> **Open** -> select the unzipped `AutoAndroid` directory.
3. Let Android Studio sync Gradle (`AGP 8.5.2`, `Kotlin 2.0.21`, `compileSdk = 34`).
4. Connect your Android phone (with **USB Debugging** enabled) and click **Run (`app`)**.

### Option B: Using the Command Line
```bash
cd AutoAndroid
# 1. Run the fail-closed Kotlin parity & unit test suite (Check4 through Check32):
bash scripts/run_parity.sh

# 2. Build the debug APK and run unit tests + lint:
gradle test :app:assembleDebug :app:lintDebug

# 3. Install the APK on your connected Android phone:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 3. How to Export Videos Fast (Recommended Settings)

1. **Create or Open Your Project**:
   - Tap **Auto Create** on the Home dashboard (or **Inputs** in the editor), pick your **Script / Captions (SRT/VTT) / Narration Audio / Pictures (or ZIP)**, and tap **Auto Analyse & Build Video**.
   - Or tap **Blank Manual Project** to build a timeline manually from images and solid-colour scenes.
2. **Open the Export Sheet**:
   - Tap the violet **Export** button in the top-right corner of the editor.
3. **Choose the Best AI Upscaling Profile for Your Speed vs. Quality Needs**:
   - **Balanced (Recommended)**: Automatically checks each image's rendered display box inside your target video frame (`1080p` / `4K`). Skips images that already have enough pixels (`0s` spent upscaling already-sharp images!), uses fast `2×` Real-ESRGAN v3 when `2×` covers the frame, and uses `4×` Streaming PNG only for genuinely small images.
   - **Fast**: Uses `2×` Real-ESRGAN v3 only on undersized images and skips all adequate images.
   - **Manual -> Off**: Bypasses AI image upscaling completely and goes straight to Media3 hardware H.264 MP4 encoding (fastest when your source images are already HD/4K).
   - **Maximum (4× Streaming PNG)**: Forces `4×` Real-ESRGAN v3 (`realesr-general-x4v3`) on every image using bounded strip-streaming (`StreamingPngWriter`). Use only when you specifically want maximum 4× super-resolution on all images.
4. **Tap `Start export`**:
   - You can freely switch workspaces or navigate to **Home / Projects / Settings** while exporting — `ExportForegroundService` keeps the job running and shows live progress in your system notification shade.
   - If you re-export the same project after tweaking captions, audio volume, or transitions, **completed upscaled images are reused automatically from `UpscaleCache` in milliseconds** instead of re-running AI inference!
   - Finished videos are saved to **`Movies/Auto`** in your phone's gallery.

---

## 4. Inspecting Export Timing & Bottleneck Diagnostics on Your Phone
- After any export, open **Settings -> Diagnostics & Performance Baseline** to view:
  - Total export time and per-stage breakdown (`Project Prep`, `Cache Lookup`, `Image Decode`, `Upscaling (ONNX Inference)`, `PNG Write`, `Audio Prep`, `Video Rendering`, `Finalizing`, `MediaStore Publish`).
  - Per-image upscale summary (`Median ms`, `p90 ms`, `Slowest image #`, `Images/min`, and `Decode vs. Inference vs. PNG Write` split).
  - Tap **Share Log (JSON)** to export the privacy-safe JSON benchmark report.
