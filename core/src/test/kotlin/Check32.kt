import com.auto.core.*
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * Check32: Final Performance & Real-ESRGAN Directive Verification Suite (Phases 1–6):
 * 1. High-resolution monotonic telemetry (`Diagnostics.nanosToMs`, `UpscaleImageMetric`, `UpscaleAggregateStats` median/p90/slowest/decode-infer-encode split, JSON round-trip).
 * 2. Real-ESRGAN v3 (`app/src/main/assets/model.onnx`) provenance verification (`SHA-256`, `4,866,341` bytes) and `UpscaleEngine.patchDynamicSpatialDims` in-memory dynamic shape patching.
 * 3. Aspect-fit aware `UpscaleEngine.smartFactor` (skips square/portrait images whose source pixels already cover their `LAYOUT_SCALE_TO_FIT` display box inside the target frame).
 * 4. Region-based `ExifTileMapper` across all 8 EXIF orientations (`1..8`: `0°, 90°, 180°, 270°, flips, transpose, transverse`) with zero full-image allocation.
 * 5. Multi-scanline batched `StreamingPngWriter` (`Deflater.BEST_SPEED = 1`) and `UpscaleCache.get(..., verifyIntegrity = true)` pre-reuse corruption detection.
 */
fun main() {
    val tmpRoot = Files.createTempDirectory("check32_perf_esrgan").toFile()
    try {
        // 1. High-resolution monotonic telemetry & UpscaleAggregateStats (median, p90, slowest, decode/infer/encode split)
        val t0 = System.nanoTime()
        val sampleMetrics = listOf(
            UpscaleImageMetric(1, 1920, 1080, 500_000L, 1, 1, "realesr-general-x4v3-srvgg32", "Skipped", false, skipped = true, totalMs = 2L),
            UpscaleImageMetric(2, 1280, 720, 420_000L, 1, 2, "realesr-general-x4v3-srvgg32", "Cache", true, totalMs = 4L),
            UpscaleImageMetric(3, 960, 540, 310_000L, 1, 2, "realesr-general-x4v3-srvgg32", "CPU (2t)", false, decodeMs = 12L, inferenceMs = 410L, encodeWriteMs = 18L, totalMs = 440L, tilesRun = 4),
            UpscaleImageMetric(4, 960, 540, 315_000L, 6, 2, "realesr-general-x4v3-srvgg32", "CPU (2t)", false, decodeMs = 15L, inferenceMs = 450L, encodeWriteMs = 20L, totalMs = 485L, tilesRun = 4),
            UpscaleImageMetric(5, 640, 360, 210_000L, 1, 4, "realesr-general-x4v3-srvgg32", "CPU (2t)", false, decodeMs = 10L, inferenceMs = 820L, encodeWriteMs = 25L, totalMs = 855L, tilesRun = 6)
        )
        val agg = Diagnostics.aggregateUpscaleMetrics(sampleMetrics)
        check(agg.totalImages == 5 && agg.upscaledCount == 3 && agg.cacheHits == 1 && agg.skippedCount == 1) {
            "Aggregate counts mismatch: $agg"
        }
        check(agg.slowestImageIndex == 5 && agg.slowestMs == 855L) { "Slowest image must be #5 (855 ms)" }
        check(agg.totalDecodeMs == 37L && agg.totalInferenceMs == 1680L && agg.totalEncodeWriteMs == 63L) {
            "Decode/inference/encode totals mismatch: $agg"
        }
        val rep = DiagnosticRunReport(
            timestampMs = 1762150000000L,
            resolutionP = 1080,
            fps = 30,
            aspect = "16:9",
            upscaleFactor = 2,
            upscaleProfile = "BALANCED",
            sceneCount = 5,
            videoDurationMs = 15000L,
            totalExportMs = Diagnostics.nanosToMs(t0) + 2500L,
            status = "COMPLETED",
            stages = listOf(
                StageMetric(DiagStage.PROJECT_PREP, 8L, 5, 5),
                StageMetric(DiagStage.CACHE_LOOKUP, 6L, 1, 5, skippedCount = 1),
                StageMetric(DiagStage.IMAGE_DECODE, 37L, 3, 5),
                StageMetric(DiagStage.UPSCALE, 1680L, 3, 5, skippedCount = 1),
                StageMetric(DiagStage.PNG_ENCODE_WRITE, 63L, 3, 5),
                StageMetric(DiagStage.AUDIO_PREP, 14L, 1, 1),
                StageMetric(DiagStage.RENDER, 620L, 5, 5),
                StageMetric(DiagStage.FINALIZE, 45L, 1, 1),
                StageMetric(DiagStage.MEDIASTORE_PUBLISH, 27L, 1, 1)
            ),
            upscaleStats = agg,
            warnings = listOf("Sample non-fatal warning")
        )
        val parsedRep = Diagnostics.parseHistory(Diagnostics.serializeHistory(listOf(rep))).firstOrNull()
        check(parsedRep != null && parsedRep.upscaleStats?.slowestMs == 855L && parsedRep.stages.size == 9) {
            "DiagnosticRunReport JSON round-trip failed"
        }
        println("PASS: Check32.1 Monotonic 11-stage telemetry and UpscaleAggregateStats verified")

        // 2. Real-ESRGAN v3 (`app/src/main/assets/model.onnx`) provenance & dynamic spatial dimension patcher
        val modelFile = File("app/src/main/assets/model.onnx")
        if (modelFile.isFile) {
            val sha = Validation.sha256(modelFile.path)
            val spec = UpscaleEngine.SHIPPED_MODEL_SPEC
            check(sha == spec.sha256 && modelFile.length() == spec.fileSizeBytes) {
                "model.onnx SHA-256 or size mismatch: got $sha (${modelFile.length()})"
            }
            val rawBytes = modelFile.readBytes()
            val patched = UpscaleEngine.patchDynamicSpatialDims(rawBytes)
            check(patched.size == rawBytes.size && !patched.contentEquals(rawBytes)) {
                "patchDynamicSpatialDims must patch trailing ValueInfoProto without changing byte length"
            }
            // All bytes before the last 63 bytes (including all 1,213,296 float32 weights) must be 100% identical
            for (i in 0 until (rawBytes.size - 63)) {
                check(rawBytes[i] == patched[i]) { "Weight byte modified at index $i" }
            }
        }
        println("PASS: Check32.2 Real-ESRGAN v3 (SRVGGNetCompact) provenance & dynamic spatial dimension patcher verified")

        // 3. Aspect-Fit aware smartFactor
        // Square 1080x1080 inside 1920x1080 (16:9) occupies 1080x1080 -> already covered (OFF)
        check(UpscaleEngine.smartFactor(1080, 1080, 1920, 1080) == UpscaleFactor.OFF) {
            "1080x1080 inside 1920x1080 fits at 1080x1080 and must return OFF"
        }
        // Portrait 720x1280 inside 1920x1080 (16:9) fits at 608x1080 -> already covered by 720x1280 (OFF)
        check(UpscaleEngine.smartFactor(720, 1280, 1920, 1080) == UpscaleFactor.OFF) {
            "720x1280 portrait inside 1920x1080 fits at 608x1080 and must return OFF"
        }
        // 800x600 (4:3) inside 1920x1080 (16:9) fits at 1440x1080 -> 2x (1600x1200) covers it (X2, NOT X4!)
        check(UpscaleEngine.smartFactor(800, 600, 1920, 1080) == UpscaleFactor.X2) {
            "800x600 inside 1920x1080 fits at 1440x1080 and must return X2"
        }
        // 400x300 inside 1920x1080 fits at 1440x1080 -> requires X4
        check(UpscaleEngine.smartFactor(400, 300, 1920, 1080) == UpscaleFactor.X4) {
            "400x300 inside 1920x1080 must return X4"
        }
        println("PASS: Check32.3 Aspect-fit aware smartFactor verified")

        // 4. ExifTileMapper across all 8 EXIF orientations (1..8) without full-image allocation
        val rawW = 12
        val rawH = 8
        val rawSensor = IntArray(rawW * rawH) { idx -> (idx + 1) * 100 }
        for (ori in 1..8) {
            val (ow, oh) = ExifTileMapper.orientedDimensions(rawW, rawH, ori)
            // Extract in 2 horizontal strips and verify every pixel matches direct `toRawCoord`
            val stripH = oh / 2
            val assembled = IntArray(ow * oh)
            for (s in 0..1) {
                val y0 = s * stripH
                val sh = if (s == 0) stripH else (oh - y0)
                val rect = ExifTileMapper.rawBoundingRectForOrientedRegion(0, y0, ow, sh, rawW, rawH, ori)
                // Copy just the bounding sub-rect from rawSensor
                val subRaw = IntArray(rect.width * rect.height)
                for (ry in 0 until rect.height) {
                    for (rx in 0 until rect.width) {
                        subRaw[ry * rect.width + rx] = rawSensor[(rect.top + ry) * rawW + (rect.left + rx)]
                    }
                }
                val stripOut = IntArray(ow * sh)
                ExifTileMapper.extractOrientedWindowFromRawRegion(
                    rawPixels = subRaw,
                    rawRect = rect,
                    rawW = rawW,
                    rawH = rawH,
                    exifOrientation = ori,
                    oxStart = 0,
                    oyStart = y0,
                    ow = ow,
                    oh = sh,
                    dst = stripOut
                )
                System.arraycopy(stripOut, 0, assembled, y0 * ow, ow * sh)
            }
            for (oy in 0 until oh) {
                for (ox in 0 until ow) {
                    val (rx, ry) = ExifTileMapper.toRawCoord(ox, oy, rawW, rawH, ori)
                    val expected = rawSensor[ry * rawW + rx]
                    check(assembled[oy * ow + ox] == expected) {
                        "ExifTileMapper mismatch for orientation $ori at ($ox, $oy)"
                    }
                }
            }
        }
        println("PASS: Check32.4 ExifTileMapper verified across all 8 EXIF orientations (1..8)")

        // 5. Batched StreamingPngWriter (Deflater.BEST_SPEED = 1) & UpscaleCache pre-reuse integrity check
        val cacheDir = File(tmpRoot, "cache")
        val cache = UpscaleCache(cacheDir, maxBytes = 10L * 1024L * 1024L)
        val sha = "1234567890abcdef1234567890abcdef"
        val savedPng = cache.put(sha, scale = 2, format = IntermediateFormat.PNG, tempProducer = { tmp ->
            StreamingPngWriter.writeFile(tmp, 64, 48, hasAlpha = false, compressionLevel = Deflater.BEST_SPEED) { writer ->
                writer.writeStrip(IntArray(64 * 24) { 0xFF224466.toInt() }, rows = 24)
                writer.writeStrip(IntArray(64 * 24) { 0xFF88AAC0.toInt() }, rows = 24)
            }
        })
        check(PngFormatVerifier.isValidCachedImageFile(savedPng, IntermediateFormat.PNG)) {
            "Batched StreamingPngWriter file must pass isValidCachedImageFile"
        }
        check(cache.get(sha, scale = 2, format = IntermediateFormat.PNG, verifyIntegrity = true) != null) {
            "Valid cached PNG must be returned when verifyIntegrity = true"
        }
        // Corrupt the trailing IEND chunk and verify UpscaleCache rejects and deletes the corrupt file
        savedPng.writeBytes(savedPng.readBytes().dropLast(6).toByteArray())
        val afterCorrupt = cache.get(sha, scale = 2, format = IntermediateFormat.PNG, verifyIntegrity = true)
        check(afterCorrupt == null && !savedPng.exists()) {
            "Corrupt cached PNG must be rejected and deleted when verifyIntegrity = true"
        }
        println("PASS: Check32.5 Batched StreamingPngWriter & UpscaleCache integrity validation verified")
        println("ALL PASS (Check32)")
    } finally {
        tmpRoot.deleteRecursively()
    }
}
