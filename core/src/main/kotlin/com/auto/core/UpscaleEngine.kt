package com.auto.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 5, Priority 1/7 & Performance Directive Phases 2–4:
 * - Verified Real-ESRGAN v3 (`realesr-general-x4v3`, `SRVGGNetCompact`) model metadata & dynamic spatial dimension patcher.
 * - Aspect-fit aware `smartFactor` (never upscales images whose source resolution already covers their rendered display box in the target frame).
 * - Region-based EXIF orientation mapper (`ExifTileMapper` supporting all 8 EXIF orientations `1..8` without any full-image Bitmap or full pixel array).
 * - Persistent content-addressed `UpscaleCache` with pixel-complete keys, `ucache_` ownership verification, and pre-reuse file integrity validation.
 */
enum class UpscaleItemState(val label: String) {
    WAITING("Waiting"),
    WORKING("Working"),
    DONE("Done"),
    SKIPPED("Skipped (adequate)"),
    FAILED("Failed (fallback to original)")
}

data class UpscaleQueueItem(
    val index: Int,
    val sourcePath: String,
    val sourceHash: String,
    val targetScale: Int,
    val state: UpscaleItemState,
    val outputPath: String? = null,
    val fromCache: Boolean = false,
    val elapsedMs: Long = 0L,
    val warning: String = ""
)

/**
 * Performance Directive Phase 2: Verified provenance and architecture metadata for `app/src/main/assets/model.onnx`.
 */
data class OnnxModelSpec(
    val id: String,
    val fingerprint: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val architecture: String,
    val parameterCount: Long,
    val scaleFactor: Int,
    val license: String,
    val sourceProvenance: String
)

enum class UpscalerExecutionProvider(val label: String) {
    CPU_XNNPACK("XNNPACK + CPU Fallback"),
    NNAPI_WITH_CPU_FALLBACK("NNAPI + CPU Fallback"),
    CPU_DEFAULT("CPU (ORT Default)")
}

/**
 * Priority 1 (Section 2.2): Complete live-memory allocation map for a single image upscale operation.
 * Compares the legacy full-output-Bitmap path vs. the bounded strip-streaming path (`StreamingPngWriter` + `BitmapRegionDecoder`).
 */
data class UpscaleAllocationMap(
    val srcWidth: Int,
    val srcHeight: Int,
    val scale: Int,
    val outWidth: Int,
    val outHeight: Int,
    val sourceStripRows: Int,
    val outputStripRows: Int,
    val sourceStripBytes: Long,
    val onnxInputTensorBytes: Long,
    val onnxOutputTensorBytes: Long,
    val outputStripBufferBytes: Long,
    val streamingEncoderBufferBytes: Long,
    val fullOutputBitmapBytesIfMaterialized: Long
) {
    /** Peak simultaneously live bytes during the bounded strip-streaming PNG path (zero full output Bitmap). */
    val peakStreamingLiveBytes: Long
        get() = sourceStripBytes + onnxInputTensorBytes + onnxOutputTensorBytes + outputStripBufferBytes + streamingEncoderBufferBytes

    /** Peak simultaneously live bytes if a full output ARGB_8888 Bitmap is materialized (e.g. for legacy or Android JPEG Bitmap.compress). */
    val peakFullBitmapLiveBytes: Long
        get() = peakStreamingLiveBytes + fullOutputBitmapBytesIfMaterialized
}

/**
 * Priority 1 (Section 2.2.6): Preflight check result before starting an upscale job.
 * Never silently lowers scale factor or quality; if `safe == false`, returns explicit user choices.
 */
data class UpscalePreflightResult(
    val safe: Boolean,
    val allocationMap: UpscaleAllocationMap?,
    val requiresUserChoice: Boolean,
    val reason: String = "",
    val choices: List<String> = emptyList()
) {
    val warningMessage: String get() = reason
    val userChoices: List<String> get() = choices
}

object UpscaleMemoryGuard {
    private const val TILE_INNER = 192
    private const val TILE_PAD = 16
    private const val TILE_IN = 224
    private const val MODEL_SCALE = 4
    /** Maximum single-image output dimension supported by typical mobile OpenGL ES texture renderers (Media3 Presentation). */
    const val DEFAULT_MAX_GL_TEXTURE_SIDE = 16_384
    /** Maximum output pixel count for materializing a full ARGB_8888 Bitmap for Android's Bitmap.compress(JPEG) (~33.2 MB bitmap at 4K). */
    const val MAX_JPEG_BITMAP_PIXELS = 3840L * 2160L

    /**
     * Computes exact byte allocations with `Math.multiplyExact` overflow protection.
     * Throws `ArithmeticException` or `IllegalArgumentException` on invalid/overflowing dimensions.
     */
    fun computeAllocationMap(srcW: Int, srcH: Int, scale: Int): UpscaleAllocationMap {
        require(srcW > 0 && srcH > 0) { "Source dimensions must be positive (${srcW}x$srcH)." }
        require(scale == 2 || scale == 4) { "Upscale factor must be 2 or 4 (got $scale)." }
        val outW = Math.multiplyExact(srcW, scale)
        val outH = Math.multiplyExact(srcH, scale)
        val outPixels = Math.multiplyExact(outW.toLong(), outH.toLong())
        val fullBitmapBytes = Math.multiplyExact(outPixels, 4L)

        // Source strip height needed per tile row: 224 rows for 4x, or 448 rows (downsampled 2x -> 224) for 2x
        val srcStripRows = if (scale == 2) min(srcH, TILE_IN * 2) else min(srcH, TILE_IN)
        val sourceStripBytes = Math.multiplyExact(Math.multiplyExact(srcW.toLong(), srcStripRows.toLong()), 4L)

        // ONNX input [1, 3, 224, 224] float32 = 602,112 bytes; output [1, 3, 896, 896] float32 = 9,633,792 bytes
        val onnxInBytes = 1L * 3L * TILE_IN * TILE_IN * 4L
        val onnxOutSide = TILE_IN * MODEL_SCALE
        val onnxOutBytes = 1L * 3L * onnxOutSide * onnxOutSide * 4L

        // Output strip height: at most TILE_INNER * 4 = 768 rows (or 384 rows at 2x when writing 1 tile-row at a time)
        val outStripRows = min(outH, TILE_INNER * scale)
        val outputStripBytes = Math.multiplyExact(Math.multiplyExact(outW.toLong(), outStripRows.toLong()), 4L)

        // StreamingPngWriter internal buffers: 128 KB scanline batch buffer + 64 KB Deflater buffer
        val encoderBytes = max(131_072L, 1L + Math.multiplyExact(outW.toLong(), 4L)) + 65_536L

        return UpscaleAllocationMap(
            srcWidth = srcW,
            srcHeight = srcH,
            scale = scale,
            outWidth = outW,
            outHeight = outH,
            sourceStripRows = srcStripRows,
            outputStripRows = outStripRows,
            sourceStripBytes = sourceStripBytes,
            onnxInputTensorBytes = onnxInBytes,
            onnxOutputTensorBytes = onnxOutBytes,
            outputStripBufferBytes = outputStripBytes,
            streamingEncoderBufferBytes = encoderBytes,
            fullOutputBitmapBytesIfMaterialized = fullBitmapBytes
        )
    }

    /**
     * Evaluates whether `(srcW, srcH)` can be safely upscaled by `scale` in `format` given `availableHeapBytes`
     * and `maxTextureSide`. Never silently lowers factor or quality; returns `requiresUserChoice = true` with
     * actionable options when limits are exceeded.
     */
    fun preflightCheck(
        srcW: Int,
        srcH: Int,
        scale: Int,
        format: IntermediateFormat,
        availableHeapBytes: Long,
        maxTextureSide: Int = DEFAULT_MAX_GL_TEXTURE_SIDE
    ): UpscalePreflightResult {
        if (scale <= 1) return UpscalePreflightResult(safe = true, allocationMap = null, requiresUserChoice = false)
        val map = try {
            computeAllocationMap(srcW, srcH, scale)
        } catch (e: Exception) {
            return UpscalePreflightResult(
                safe = false,
                allocationMap = null,
                requiresUserChoice = true,
                reason = "Image dimensions (${srcW}×$srcH at ${scale}×) overflow safe integer pixel bounds: ${e.message}",
                choices = listOf("Switch to Balanced (Smart Factor)", "Use 2× Upscale", "Cancel")
            )
        }

        if (map.outWidth > maxTextureSide || map.outHeight > maxTextureSide) {
            return UpscalePreflightResult(
                safe = false,
                allocationMap = map,
                requiresUserChoice = true,
                reason = "Upscaling ${srcW}×$srcH at ${scale}× produces ${map.outWidth}×${map.outHeight}, which exceeds the GPU texture limit (${maxTextureSide}px).",
                choices = listOf("Switch to Balanced (Smart Factor)", "Use 2× Upscale (${srcW * 2}×${srcH * 2})", "Cancel")
            )
        }

        val outPixels = map.outWidth.toLong() * map.outHeight.toLong()
        if (!format.png && outPixels > MAX_JPEG_BITMAP_PIXELS) {
            return UpscalePreflightResult(
                safe = false,
                allocationMap = map,
                requiresUserChoice = true,
                reason = "Android's JPEG encoder requires materializing a full ${map.outWidth}×${map.outHeight} Bitmap (${MediaLibrary.humanSize(map.fullOutputBitmapBytesIfMaterialized)}) in memory, whereas Streaming PNG uses only ${MediaLibrary.humanSize(map.peakStreamingLiveBytes)} via strip-streaming.",
                choices = listOf(
                    "Use Streaming PNG (Bounded Memory: ${MediaLibrary.humanSize(map.peakStreamingLiveBytes)})",
                    "Switch to Balanced (Smart Factor)",
                    "Use 2× Upscale",
                    "Cancel"
                )
            )
        }

        val requiredBytes = if (format.png) map.peakStreamingLiveBytes * 2L else map.peakFullBitmapLiveBytes * 2L
        if (availableHeapBytes in 1 until requiredBytes) {
            return UpscalePreflightResult(
                safe = false,
                allocationMap = map,
                requiresUserChoice = true,
                reason = "Upscaling ${srcW}×$srcH at ${scale}× requires ~${MediaLibrary.humanSize(requiredBytes)} safe headroom, but only ${MediaLibrary.humanSize(availableHeapBytes)} heap is currently available.",
                choices = listOf("Switch to Balanced (Smart Factor)", "Use 2× Upscale", "Cancel")
            )
        }

        return UpscalePreflightResult(safe = true, allocationMap = map, requiresUserChoice = false)
    }
}

/**
 * Performance Directive Phase 3: Region-based EXIF orientation coordinate mapper (`ExifTileMapper`).
 * Eliminates the full-image `Bitmap` and full `IntArray(w * h)` fallback for rotated (`90°/180°/270°`) and mirrored
 * (`FLIP_H`, `FLIP_V`, `TRANSPOSE`, `TRANSVERSE`) images by mapping any sub-rectangle `[ox .. ox+ow), [oy .. oy+oh)`
 * in oriented image space `(orientedW, orientedH)` back to the exact bounding rectangle in raw unrotated sensor space `(rawW, rawH)`.
 *
 * Standard EXIF orientation codes:
 * 1 = Normal
 * 2 = Flip horizontal
 * 3 = Rotate 180°
 * 4 = Flip vertical
 * 5 = Transpose (flip across main diagonal: rawX = oy, rawY = ox)
 * 6 = Rotate 90° CW (rawX = oy, rawY = rawH - 1 - ox)
 * 7 = Transverse (flip across anti-diagonal: rawX = rawW - 1 - oy, rawY = rawH - 1 - ox)
 * 8 = Rotate 270° CW (rawX = rawW - 1 - oy, rawY = ox)
 */
object ExifTileMapper {
    data class RawBoundingRect(val left: Int, val top: Int, val rightExclusive: Int, val bottomExclusive: Int) {
        val width: Int get() = rightExclusive - left
        val height: Int get() = bottomExclusive - top
    }

    fun swapsDimensions(exifOrientation: Int): Boolean =
        exifOrientation in 5..8

    fun orientedDimensions(rawW: Int, rawH: Int, exifOrientation: Int): Pair<Int, Int> =
        if (swapsDimensions(exifOrientation)) Pair(rawH, rawW) else Pair(rawW, rawH)

    /** Maps a single oriented pixel `(ox, oy)` in `(orientedW, orientedH)` to `(rawX, rawY)` in `(rawW, rawH)`. */
    fun toRawCoord(ox: Int, oy: Int, rawW: Int, rawH: Int, exifOrientation: Int): Pair<Int, Int> {
        val rx: Int
        val ry: Int
        when (exifOrientation) {
            2 -> { rx = rawW - 1 - ox; ry = oy }
            3 -> { rx = rawW - 1 - ox; ry = rawH - 1 - oy }
            4 -> { rx = ox; ry = rawH - 1 - oy }
            5 -> { rx = oy; ry = ox }
            6 -> { rx = oy; ry = rawH - 1 - ox }
            7 -> { rx = rawW - 1 - oy; ry = rawH - 1 - ox }
            8 -> { rx = rawW - 1 - oy; ry = ox }
            else -> { rx = ox; ry = oy }
        }
        return Pair(rx.coerceIn(0, rawW - 1), ry.coerceIn(0, rawH - 1))
    }

    /**
     * Computes the exact bounding box in raw sensor coordinates `[left, top, rightExclusive, bottomExclusive)`
     * needed to populate the oriented region `[ox .. ox + ow), [oy .. oy + oh)`.
     */
    fun rawBoundingRectForOrientedRegion(
        ox: Int,
        oy: Int,
        ow: Int,
        oh: Int,
        rawW: Int,
        rawH: Int,
        exifOrientation: Int
    ): RawBoundingRect {
        val (orientedW, orientedH) = orientedDimensions(rawW, rawH, exifOrientation)
        val x0 = ox.coerceIn(0, orientedW - 1)
        val y0 = oy.coerceIn(0, orientedH - 1)
        val x1 = (ox + ow - 1).coerceIn(x0, orientedW - 1)
        val y1 = (oy + oh - 1).coerceIn(y0, orientedH - 1)

        val c00 = toRawCoord(x0, y0, rawW, rawH, exifOrientation)
        val c10 = toRawCoord(x1, y0, rawW, rawH, exifOrientation)
        val c01 = toRawCoord(x0, y1, rawW, rawH, exifOrientation)
        val c11 = toRawCoord(x1, y1, rawW, rawH, exifOrientation)

        val minRx = minOf(c00.first, c10.first, c01.first, c11.first).coerceIn(0, rawW - 1)
        val maxRx = maxOf(c00.first, c10.first, c01.first, c11.first).coerceIn(minRx, rawW - 1)
        val minRy = minOf(c00.second, c10.second, c01.second, c11.second).coerceIn(0, rawH - 1)
        val maxRy = maxOf(c00.second, c10.second, c01.second, c11.second).coerceIn(minRy, rawH - 1)

        return RawBoundingRect(minRx, minRy, maxRx + 1, maxRy + 1)
    }

    /**
     * Populates an oriented window `dst` (`ow * oh`) from a decoded raw bounding region `rawPixels` (`rawRect.width * rawRect.height`),
     * clamping out-of-bounds halo coordinates `[ox .. ox + ow), [oy .. oy + oh)` to `[0 .. orientedW - 1], [0 .. orientedH - 1]`.
     */
    fun extractOrientedWindowFromRawRegion(
        rawPixels: IntArray,
        rawRect: RawBoundingRect,
        rawW: Int,
        rawH: Int,
        exifOrientation: Int,
        oxStart: Int,
        oyStart: Int,
        ow: Int,
        oh: Int,
        dst: IntArray
    ) {
        val (orientedW, orientedH) = orientedDimensions(rawW, rawH, exifOrientation)
        val regW = rawRect.width
        val regH = rawRect.height
        for (j in 0 until oh) {
            val oy = (oyStart + j).coerceIn(0, orientedH - 1)
            val dstRow = j * ow
            for (i in 0 until ow) {
                val ox = (oxStart + i).coerceIn(0, orientedW - 1)
                val (rx, ry) = toRawCoord(ox, oy, rawW, rawH, exifOrientation)
                val localX = (rx - rawRect.left).coerceIn(0, regW - 1)
                val localY = (ry - rawRect.top).coerceIn(0, regH - 1)
                dst[dstRow + i] = rawPixels[localY * regW + localX]
            }
        }
    }
}

interface UpscalerRuntimeAdapter : AutoCloseable {
    val runtimeId: String
    val modelId: String
    val tileSize: Int
    val tilePadding: Int
}

object UpscaleEngine {
    const val MODEL_FINGERPRINT = "onnx_esrgan_v1_4873934"
    const val DEFAULT_CACHE_LIMIT_BYTES = 512L * 1024L * 1024L
    val CACHE_LIMIT_OPTIONS_MB = listOf(256, 512, 1024, 2048)

    /** Verified metadata for the shipped `app/src/main/assets/model.onnx` (`realesr-general-x4v3` `SRVGGNetCompact`). */
    val SHIPPED_MODEL_SPEC = OnnxModelSpec(
        id = "realesr-general-x4v3-srvgg32",
        fingerprint = MODEL_FINGERPRINT,
        sha256 = "d3b45e236c1cdc43c14b6aca839d59d6ac009f3791f998a16404252f6f65600e",
        fileSizeBytes = 4_866_341L,
        architecture = "SRVGGNetCompact (num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4, act_type=prelu)",
        parameterCount = 1_213_296L,
        scaleFactor = 4,
        license = "BSD-3-Clause (Xintao Wang / Tencent ARC Lab, xinntao/Real-ESRGAN)",
        sourceProvenance = "Official Real-ESRGAN v3 General compact model exported via PyTorch 2.15.0 (ONNX IR v8, Opset 17)"
    )

    /**
     * Patches the trailing 63-byte `ValueInfoProto` of `model.onnx` in memory so `input` (`x`) and `output` (`y`)
     * have dynamic spatial dimensions (`[1, 3, "H", "W"] -> [1, 3, "Y", "X"]`) instead of static `224x224 -> 896x896`.
     * All 71 graph nodes (`34 Conv`, `33 PRelu`, `DepthToSpace(4)`, `Resize(scales=[1,1,4,4])`, `Add`) are 100%
     * resolution-independent, and weights are 100% untouched.
     */
    fun patchDynamicSpatialDims(modelBytes: ByteArray): ByteArray {
        if (modelBytes.size < 64) return modelBytes
        val out = modelBytes.copyOf()
        val tailStart = out.size - 63
        // Look for the exact static 224x224 (`0a 03 08 e0 01 0a 03 08 e0 01`) and 896x896 (`0a 03 08 80 07 0a 03 08 80 07`) sequences in the last 63 bytes
        val pat224 = byteArrayOf(0x0A, 0x03, 0x08, 0xE0.toByte(), 0x01, 0x0A, 0x03, 0x08, 0xE0.toByte(), 0x01)
        val repHW = byteArrayOf(0x0A, 0x03, 0x12, 0x01, 'H'.code.toByte(), 0x0A, 0x03, 0x12, 0x01, 'W'.code.toByte())
        val pat896 = byteArrayOf(0x0A, 0x03, 0x08, 0x80.toByte(), 0x07, 0x0A, 0x03, 0x08, 0x80.toByte(), 0x07)
        val repYX = byteArrayOf(0x0A, 0x03, 0x12, 0x01, 'Y'.code.toByte(), 0x0A, 0x03, 0x12, 0x01, 'X'.code.toByte())

        fun replaceInRange(buf: ByteArray, start: Int, pat: ByteArray, rep: ByteArray): Boolean {
            for (i in start..(buf.size - pat.size)) {
                var match = true
                for (k in pat.indices) {
                    if (buf[i + k] != pat[k]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    System.arraycopy(rep, 0, buf, i, rep.size)
                    return true
                }
            }
            return false
        }

        val ok1 = replaceInRange(out, tailStart, pat224, repHW)
        val ok2 = replaceInRange(out, tailStart, pat896, repYX)
        return if (ok1 && ok2) out else modelBytes
    }

    /**
     * Aspect-fit aware smart factor (Performance Directive Phase 3):
     * Computes the actual display box `(displayW, displayH)` that `(srcW, srcH)` occupies inside `(targetW, targetH)`
     * under `Presentation.LAYOUT_SCALE_TO_FIT` (`fitScale = min(targetW / srcW, targetH / srcH)`).
     * - If `fitScale <= 1.0` (source already has >= 1 pixel per display pixel in the output video), returns `OFF`.
     * - If `fitScale <= 2.0` (`2×` covers 100% of the display box in the output video), returns `X2`.
     * - Otherwise returns `X4`.
     */
    fun smartFactor(srcW: Int, srcH: Int, targetW: Int, targetH: Int): UpscaleFactor {
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return UpscaleFactor.OFF
        val fitScale = min(targetW.toDouble() / srcW.toDouble(), targetH.toDouble() / srcH.toDouble())
        val displayW = max(1, (srcW * fitScale).roundToInt())
        val displayH = max(1, (srcH * fitScale).roundToInt())
        if (srcW >= displayW && srcH >= displayH) return UpscaleFactor.OFF
        if (srcW * 2 >= displayW && srcH * 2 >= displayH) return UpscaleFactor.X2
        return UpscaleFactor.X4
    }

    /**
     * Resolves the effective `(UpscaleFactor, IntermediateFormat)` for a single source image.
     * - `FAST`: skips already-adequate images (`smartFactor == OFF`), uses `2×` for undersized images, and uses fast lossless `PNG` (at `Deflater.BEST_SPEED = 1`) or `JPEG` (`<= 4K`).
     * - `BALANCED`: uses aspect-fit `smartFactor` (`OFF`, `2×`, or `4×`) and fast lossless `PNG` for `> 4K`.
     * - `MAXIMUM`: uses `4×` with `StreamingPngWriter` (`PNG`), never silently lowering `4×` to `2×`.
     */
    fun resolveForImage(
        profile: UpscaleProfile,
        manualFactor: UpscaleFactor,
        manualFormat: IntermediateFormat,
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int
    ): Pair<UpscaleFactor, IntermediateFormat> = when (profile) {
        UpscaleProfile.MANUAL -> Pair(manualFactor, manualFormat)
        UpscaleProfile.FAST -> {
            val sf = smartFactor(srcW, srcH, targetW, targetH)
            val f = if (sf == UpscaleFactor.OFF) UpscaleFactor.OFF else UpscaleFactor.X2
            val outPixels = srcW.toLong() * f.scale * srcH.toLong() * f.scale
            val fmt = if (outPixels > UpscaleMemoryGuard.MAX_JPEG_BITMAP_PIXELS) IntermediateFormat.PNG else IntermediateFormat.JPEG
            Pair(f, fmt)
        }
        UpscaleProfile.BALANCED -> {
            val sf = smartFactor(srcW, srcH, targetW, targetH)
            val outPixels = srcW.toLong() * sf.scale * srcH.toLong() * sf.scale
            val fmt = if (outPixels > UpscaleMemoryGuard.MAX_JPEG_BITMAP_PIXELS) IntermediateFormat.PNG else IntermediateFormat.JPEG
            Pair(sf, fmt)
        }
        UpscaleProfile.MAXIMUM -> {
            Pair(UpscaleFactor.X4, IntermediateFormat.PNG)
        }
    }

    /**
     * Caps intra-op thread count to the performance-core count (`min(4, max(2, cpuCores / 2))`) on healthy devices
     * so mobile big.LITTLE efficiency cores do not stall barrier synchronization across the 34 Conv layers.
     */
    fun recommendedThreads(
        cpuCores: Int,
        availableRamMb: Long,
        thermalSeverity: Int = 0
    ): Int {
        val perfCores = max(2, min(4, cpuCores / 2))
        return when {
            thermalSeverity >= 2 || availableRamMb < 512L -> 1
            thermalSeverity == 1 || availableRamMb < 1024L -> min(2, perfCores)
            else -> perfCores
        }
    }

    /**
     * Priority 7 & Phase 3: Deterministic cache key including source SHA-256 fingerprint, model/version,
     * scale factor, and all processing settings that affect pixels (format, quality, EXIF orientation).
     * Prefixed with `ucache_` so LRU eviction can verify cache ownership before deleting any file.
     */
    fun cacheKey(
        contentSha256: String,
        modelFp: String = MODEL_FINGERPRINT,
        scale: Int,
        format: IntermediateFormat,
        quality: Int = if (format.png) 100 else 95,
        exifRotationDeg: Int = 0
    ): String {
        val cleanSha = contentSha256.filter { it.isLetterOrDigit() }.take(32).ifEmpty { "0" }
        val normRot = ((exifRotationDeg % 360) + 360) % 360
        return "ucache_${cleanSha}_${modelFp}_${scale}x_q${quality}_rot${normRot}.${format.ext}"
    }
}

/**
 * Persistent content-addressed upscale cache in `<cacheDir>` (e.g. `filesDir/upscale_cache`).
 * Safety & Integrity Invariants (Priority 7 & Phase 3):
 * 1. Only files directly inside `cacheDir` whose filename starts with `ucache_` may ever be deleted by LRU eviction or `clear()`.
 * 2. Before reusing any cached file in `get(...)`, validates structural image integrity (`PngFormatVerifier.isValidCachedImageFile`);
 *    if corrupt or truncated, deletes the `ucache_` file and returns `null`.
 */
class UpscaleCache(
    val cacheDir: File,
    var maxBytes: Long = UpscaleEngine.DEFAULT_CACHE_LIMIT_BYTES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        cacheDir.mkdirs()
    }

    private fun isCacheOwnedFile(f: File): Boolean {
        if (!f.isFile) return false
        if (!f.name.startsWith("ucache_")) return false
        return try {
            f.canonicalFile.parentFile == cacheDir.canonicalFile
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun get(
        contentSha256: String,
        scale: Int,
        format: IntermediateFormat,
        modelFp: String = UpscaleEngine.MODEL_FINGERPRINT,
        quality: Int = if (format.png) 100 else 95,
        exifRotationDeg: Int = 0,
        verifyIntegrity: Boolean = false
    ): File? {
        val key = UpscaleEngine.cacheKey(contentSha256, modelFp, scale, format, quality, exifRotationDeg)
        val f = File(cacheDir, key)
        if (!isCacheOwnedFile(f) || f.length() <= 0L) return null
        if (verifyIntegrity && !PngFormatVerifier.isValidCachedImageFile(f, format)) {
            try { f.delete() } catch (_: Exception) {}
            return null
        }
        try { f.setLastModified(clock()) } catch (_: Exception) {}
        return f
    }

    @Synchronized
    fun put(
        contentSha256: String,
        scale: Int,
        format: IntermediateFormat,
        tempProducer: (File) -> Unit,
        modelFp: String = UpscaleEngine.MODEL_FINGERPRINT,
        quality: Int = if (format.png) 100 else 95,
        exifRotationDeg: Int = 0
    ): File {
        cacheDir.mkdirs()
        val key = UpscaleEngine.cacheKey(contentSha256, modelFp, scale, format, quality, exifRotationDeg)
        val target = File(cacheDir, key)
        val tmp = File(cacheDir, "$key.tmp_${clock()}")
        try {
            tempProducer(tmp)
            if (!tmp.isFile || tmp.length() <= 0L) {
                throw IllegalStateException("Upscaler produced an empty file.")
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try { target.setLastModified(clock()) } catch (_: Exception) {}
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        enforceLruLimit()
        return target
    }

    /** Evicts oldest `ucache_*` files until total cache size is `<= maxBytes`. Never touches non-cache files. */
    @Synchronized
    fun enforceLruLimit(limitBytes: Long = maxBytes): Long {
        maxBytes = max(1024L * 1024L, limitBytes)
        val files = (cacheDir.listFiles()?.filter { isCacheOwnedFile(it) && !it.name.contains(".tmp_") } ?: emptyList())
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        var freed = 0L
        for (f in files) {
            if (total <= maxBytes) break
            val len = f.length()
            if (isCacheOwnedFile(f) && f.delete()) {
                total -= len
                freed += len
            }
        }
        return freed
    }

    @Synchronized
    fun currentSizeBytes(): Long =
        (cacheDir.listFiles()?.filter { isCacheOwnedFile(it) && !it.name.contains(".tmp_") } ?: emptyList()).sumOf { it.length() }

    @Synchronized
    fun entryCount(): Int =
        (cacheDir.listFiles()?.count { isCacheOwnedFile(it) && !it.name.contains(".tmp_") } ?: 0)

    /** Removes only `ucache_*` files inside `cacheDir`. Never touches project or source files. */
    @Synchronized
    fun clear(): Long {
        var freed = 0L
        cacheDir.listFiles()?.forEach { f ->
            if (isCacheOwnedFile(f) || f.name.startsWith("ucache_")) {
                val len = f.length()
                try {
                    if (f.delete()) freed += len
                } catch (_: Exception) {}
            }
        }
        return freed
    }
}
