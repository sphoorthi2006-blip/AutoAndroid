# AUTOANDROID — ONNX UPSCALER BENCHMARK & ADAPTER SPECIFICATION (`docs/ONNX_BENCHMARK.md`)

## 1. Inspected ONNX Runtime & Model Facts (Section 15)

| Parameter | Verified Repository Value |
|---|---|
| **Runtime Library** | `com.microsoft.onnxruntime:onnxruntime-android:1.17.1` |
| **Model Asset** | `app/src/main/assets/model.onnx` (`4,873,934` bytes / `~4.65 MiB`) |
| **Model Fingerprint** | `onnx_esrgan_v1_4873934` |
| **Tile Geometry** | Scale `S = 4`, inner tile `T = 192`, reflective padding `P = 16`, input tensor `[1, 3, 224, 224]` (`float32`), output tensor `[1, 3, 896, 896]` |
| **2× Strategy** | Box-downsample source by `2×` (`(w+1)/2 × (h+1)/2`), run `4×` restoration pass, crop to exact `2w × 2h` |
| **Bounded Memory Architecture (Phase 5)** | Allocates a single horizontal strip buffer of `tw × 768` pixels (`T × S = 768` rows) and writes each completed strip directly into the target `Bitmap` via `setPixels`, eliminating the old redundant full-image `IntArray(ow * oh)` allocation. Safe output pixel cap is enforced at `20,000,000` pixels (`~80 MB` `ARGB_8888`), automatically stepping `4×` down to `2×` on very large inputs. |
| **Persistent Cache** | Content-addressed in `filesDir/upscale_cache/` by `SHA-256(source) + modelFp + scale + format`, with configurable LRU size cap (`256 MB`–`2048 MB`, default `512 MB`). |

---

## 2. Ten-Image On-Device Benchmark Recording Table

Run the same 10 representative project images (faces, text overlays, fine detail, high-contrast edges, gradients, varied aspect ratios) on the target device and record measurements from `DiagnosticsLog`:

| Image # | Source Dimensions (`w × h`) | Content Type | Profile / Factor | Time per Image (`ms`) | Peak Java Heap (`MB`) | Peak Native Heap (`MB`) | Cache Hit on 2nd Run (`YES/NO`) | Visual Quality Notes (Faces / Text / Edges / Colour / Aspect) |
|---|---|---|---|---|---|---|---|---|
| **1** | `640 × 360` | Face portrait | `Balanced (4×)` | | | | | |
| **2** | `960 × 540` | Small UI/sign text | `Balanced (2×)` | | | | | |
| **3** | `1280 × 720` | Fine architecture detail | `Balanced (2×)` | | | | | |
| **4** | `1920 × 1080` | Already 1080p landscape | `Balanced (Skip)` | | | | | |
| **5** | `1080 × 1920` | Vertical 9:16 portrait | `Balanced (Skip/2×)` | | | | | |
| **6** | `800 × 800` | 1:1 product card | `Fast (2×)` | | | | | |
| **7** | `1024 × 768` | High-contrast line art | `Maximum (4× PNG)` | | | | | |
| **8** | `2560 × 1440` | Large source (2.5K) | `Maximum (2× cap)` | | | | | |
| **9** | `4000 × 3000` | 12 MP camera photo | `Balanced (Skip)` | | | | | |
| **10** | `720 × 480` | Low-light colour gradient | `Balanced (2×)` | | | | | |

---

## 3. Adapter Boundary for Alternate Engines / Models

`Upscaler` implements `com.auto.core.UpscalerRuntimeAdapter`. Per Section 15:
* Any evaluation of NCNN, MNN, LiteRT, ExecuTorch/Vulkan, or alternate models (Real-ESRGAN, Real-ESRNet, SwinIR, HAT, compact SR) requires real benchmark evidence from Table 2 above, licence review, and explicit owner approval.
* Alternate runtimes plug into `UpscalerRuntimeAdapter` without touching `Matching.kt`, `Align.kt`, `CaptionPipeline.kt`, or `Pipeline.kt`.
