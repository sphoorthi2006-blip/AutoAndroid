# AUTOANDROID — 200-IMAGE PERFORMANCE & MEMORY BASELINE (`BASELINE.md`)

This document provides the blank measurement tables for establishing the on-device performance and memory baseline before and after optimization phases.

* **Rule**: Never fabricate or estimate device timings. Populate these tables only from real runs on physical Android devices using **Settings → Diagnostics → Share Log** and the synthetic project generator (`python3 scripts/generate_synthetic_project.py --images 200 --audio-minutes 30 --out /tmp/scale200`).
* **User-reported motivation (unverified target to investigate)**: A 200-image export previously took at least 72 minutes when AI upscaling and video rendering were both active.

---

## 1. Device Under Test (DUT) Metadata

| Field | Baseline Device (8 GB RAM / 64 GB Storage Floor) | Stronger Reference Device |
|---|---|---|
| **Device Make & Model** | | |
| **Android Version / API Level** | | |
| **SoC / Chipset (CPU / GPU / NPU)** | | |
| **Total RAM (GB) / Available RAM Before Run (MB)** | | |
| **Total Storage (GB) / Free Storage Before Run (GB)** | | |
| **Thermal State at Start (`NONE` / `LIGHT` / `MODERATE`)** | | |
| **App Version / Commit SHA** | | |
| **Test Date (YYYY-MM-DD)** | | |

---

## 2. 200-Image Export Baseline — Stage Breakdown (Upscale OFF vs. Upscale ON)

### 2A. Run Configuration: 200 Images, 1080p (1920×1080), 30 FPS, 16:9, Captions ON, Click ON, Upscale OFF

| Stage | Item Count (`done/total`) | Stage Time (`mm:ss.S` / `ms`) | Throughput (`images/min`) | Java Heap Peak (`MB`) | Native Heap Peak (`MB`) | Errors / Warnings | Notes |
|---|---|---|---|---|---|---|---|
| **1. Import** | / 200 | | | | | | |
| **2. Analysing** | / 200 | | | | | | |
| **3. Preparing Images** | / 200 | | | | | | |
| **4. Upscaling** | 0 / 0 (OFF) | 0 ms | — | | | | Skipped (Upscale OFF) |
| **5. Rendering (Media3 1.3.1)** | / 200 | | | | | | |
| **6. Finalizing (MediaStore + Verify)** | 1 / 1 | | — | | | | |
| **TOTAL EXPORT** | **200 scenes** | | | | | | |

* **Output Verification**: Playable (`YES/NO`): `___` | Expected Duration (`ms`): `_______` | Actual Duration (`ms`): `_______` | Resolution: `_________` | FPS: `___` | Audio Track Present (`YES/NO`): `___`

---

### 2B. Run Configuration: 200 Images, 1080p (1920×1080), 30 FPS, 16:9, Captions ON, Click ON, Upscale 2× (Balanced / JPEG)

| Stage | Item Count (`done/total`) | Stage Time (`mm:ss.S` / `ms`) | Throughput (`images/min`) | Java Heap Peak (`MB`) | Native Heap Peak (`MB`) | Errors / Warnings | Notes |
|---|---|---|---|---|---|---|---|
| **1. Import** | / 200 | | | | | | |
| **2. Analysing** | / 200 | | | | | | |
| **3. Preparing Images** | / 200 | | | | | | |
| **4. Upscaling (2× / Balanced)** | / 200 | | | | | | |
| **5. Rendering (Media3 1.3.1)** | / 200 | | | | | | |
| **6. Finalizing (MediaStore + Verify)** | 1 / 1 | | — | | | | |
| **TOTAL EXPORT** | **200 scenes** | | | | | | |

* **Output Verification**: Playable (`YES/NO`): `___` | Expected Duration (`ms`): `_______` | Actual Duration (`ms`): `_______` | Resolution: `_________` | FPS: `___` | Audio Track Present (`YES/NO`): `___`

---

### 2C. Run Configuration: 200 Images, 1080p (1920×1080), 30 FPS, 16:9, Captions ON, Click ON, Upscale 4× (Maximum / PNG)

| Stage | Item Count (`done/total`) | Stage Time (`mm:ss.S` / `ms`) | Throughput (`images/min`) | Java Heap Peak (`MB`) | Native Heap Peak (`MB`) | Errors / Warnings | Notes |
|---|---|---|---|---|---|---|---|
| **1. Import** | / 200 | | | | | | |
| **2. Analysing** | / 200 | | | | | | |
| **3. Preparing Images** | / 200 | | | | | | |
| **4. Upscaling (4× / Maximum)** | / 200 | | | | | | |
| **5. Rendering (Media3 1.3.1)** | / 200 | | | | | | |
| **6. Finalizing (MediaStore + Verify)** | 1 / 1 | | — | | | | |
| **TOTAL EXPORT** | **200 scenes** | | | | | | |

* **Output Verification**: Playable (`YES/NO`): `___` | Expected Duration (`ms`): `_______` | Actual Duration (`ms`): `_______` | Resolution: `_________` | FPS: `___` | Audio Track Present (`YES/NO`): `___`

---

## 3. How to Capture & Record Diagnostics

1. Open the app on the target Android device, import the 200-image baseline bundle, press **Analyse**, and run **Export**.
2. Navigate to **Settings → Diagnostics** to inspect the per-stage table recorded during the run.
3. Tap **Share Log** to export the privacy-safe JSON report (contains stage durations, item counts, throughput, and Java/native heap peaks; no filenames, paths, or personal data).
4. Copy the measured values into Sections 1 and 2 above.
