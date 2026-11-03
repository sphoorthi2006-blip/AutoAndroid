# AutoAndroid / Manva — Scale, Memory, & Performance Results (`RESULTS.md`)

> **Verification Status Legend**:
> - **`VERIFIED (VM ONNX Benchmark)`**: Executed live in the VM container using `python3 scripts/benchmark_export_upscale.py` with `onnxruntime 1.31.0` on the actual `app/src/main/assets/model.onnx` (`realesr-general-x4v3`, SHA-256 `d3b45e236c1cdc43c14b6aca839d59d6ac009f3791f998a16404252f6f65600e`).
> - **`NOT RUN (Physical Device)`**: Requires an attached Android phone (`8 GB RAM / 64 GB storage` baseline or stronger device class) and Android SDK 34 build environment.

---

## 1. VM ONNX Runtime & Streaming PNG Benchmark Results (`VERIFIED` — `docs/logs/benchmark_run.json`)

| Workload / Test Case | Legacy Before (Static `224×224` + Naive `smartFactor` + Unbatched L3 PNG) | Optimized Cold-Cache (Dynamic `384×192` Strips + Aspect-Fit `smartFactor` + Batched L1 PNG) | Optimized Warm-Cache (`ucache_` Reuse + Integrity Check) | Measured VM Speedup | Status |
|---|---|---|---|---|---|
| **Single Image (`160×96 -> 640×384` @ `4×`, 7-Category Mean)** | Decode `0.68 ms` + Infer `1,903.8 ms` + PNG L3 `32.7 ms` = **`1,937.2 ms`** | Decode `0.68 ms` + Infer **`987.7 ms`** + PNG L1 **`22.3 ms`** = **`1,010.7 ms`** | **`3.8 ms`** (`ucache_` hit) | **`1.92× faster`** cold (`-47.8%` time) | `VERIFIED` |
| **10-Scene Mixed Project (`1080p` Export, Balanced Profile)** | `161.41 s` (`82` tiles, `3.7 img/min`, median `15.4 s`, p90 `38.6 s`) | **`28.78 s`** (`12` tiles, `20.8 img/min`, median `1.2 ms` / `7.2 s`, p90 `14.4 s`) | **`0.02 s`** (`0` tiles) | **`5.61× faster`** cold (`-82.2%` time) | `VERIFIED` |
| **25-Scene Mixed Project (`1080p` Export, Balanced Profile)** | `413.65 s` (`210` tiles, `3.6 img/min`, median `15.4 s`, p90 `38.6 s`) | **`57.56 s`** (`24` tiles, `26.1 img/min`, median `1.2 ms` / `7.2 s`, p90 `14.4 s`) | **`0.04 s`** (`0` tiles) | **`7.19× faster`** cold (`-86.1%` time) | `VERIFIED` |
| **200-Scene Mixed Project (`1080p` Export, Balanced Profile)** | `3,228.26 s` (**`53.80 min`**, `1,640` tiles, `3.7 img/min`) | **`575.53 s`** (**`9.59 min`**, `240` tiles, `20.9 img/min`) | **`0.35 s`** (`0` tiles) | **`5.61× faster`** cold (`-82.2%` time) | `VERIFIED` |

---

## 2. Bounded Memory Allocation Results (`4000 × 3000` Source at `2×` and `4×`, Non-EXIF & EXIF `1..8`)

| Scenario | Legacy Full-Bitmap Peak Live Memory | Optimized Strip-Streaming PNG Peak Live Memory | Full Output `Bitmap` Allocated | Full Source `Bitmap` Allocated | Status |
|---|---|---|---|---|---|
| **`4000×3000` at `2×` (`8000×6000`, EXIF `0°`)** | `~286.9 MB` (`192 MB` output `Bitmap`) | **`~36.8 MB` – `~52.9 MB`** | **`0 MB`** | **`0 MB`** | `VERIFIED` (Allocation Map) / `NOT RUN` (Device `meminfo`) |
| **`4000×3000` at `4×` (`16000×12000`, EXIF `0°`)** | `~875.4 MB` (`768 MB` output `Bitmap`) | **`~63.1 MB` – `~76.7 MB`** | **`0 MB`** | **`0 MB`** | `VERIFIED` (Allocation Map) / `NOT RUN` (Device `meminfo`) |
| **`4000×3000` at `4×` (`12000×16000`, EXIF `90°/270°`)** | `~923.4 MB` (`96 MB` rotated src + `768 MB` output `Bitmap`) | **`~63.5 MB`** (`ExifTileMapper` region decode) | **`0 MB`** | **`0 MB`** | `VERIFIED` (Allocation Map) / `NOT RUN` (Device `meminfo`) |

---

## 3. Physical Android Device Acceptance Table (`NOT RUN` in Headless VM)

| Device Class | Workload | Cold-Cache Total Time | Warm-Cache Total Time | Peak Java Heap (`MB`) | Peak Native Heap (`MB`) | Status |
|---|---|---|---|---|---|---|
| **Baseline Device (`8 GB RAM / 64 GB Storage`)** | 10 / 25 / 200 / 300 / 500 Images (`1080p` Balanced) | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN (No physical device in VM)` |
| **Stronger Device (`12+ GB RAM`)** | 10 / 25 / 200 / 300 / 500 Images (`1080p` & `4K` Balanced) | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN (No physical device in VM)` |
| **Long Audio (`30-min` & `60-min` Narration)** | `AudioDecoder.streamPeaks` (`20 peaks/sec`) + Export | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN (No physical device in VM)` |
| **Interrupted Export & Process Kill (`am kill`)** | Cancel mid-upscale, low storage stop, `am kill` & Resume | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN` | `NOT RUN (No physical device in VM)` |
