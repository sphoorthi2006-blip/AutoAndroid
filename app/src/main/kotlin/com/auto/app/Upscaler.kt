package com.auto.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.auto.core.DiagStage
import com.auto.core.Diagnostics
import com.auto.core.ExifTileMapper
import com.auto.core.ExportOptions
import com.auto.core.ExportPlan
import com.auto.core.ExportSettings
import com.auto.core.IntermediateFormat
import com.auto.core.StreamingPngWriter
import com.auto.core.UpscaleAggregateStats
import com.auto.core.UpscaleCache
import com.auto.core.UpscaleEngine
import com.auto.core.UpscaleFactor
import com.auto.core.UpscaleImageMetric
import com.auto.core.UpscaleItemState
import com.auto.core.UpscaleMemoryGuard
import com.auto.core.UpscalePreflightResult
import com.auto.core.UpscaleProfile
import com.auto.core.UpscaleQueueItem
import com.auto.core.UpscalerExecutionProvider
import com.auto.core.UpscalerRuntimeAdapter
import com.auto.core.Validation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min

/**
 * Priority 1 & Performance Directive Phases 1–4:
 * High-Throughput, Bounded-Memory ONNX Runtime 1.17.1 AI Image Upscaler (`realesr-general-x4v3` / `SRVGGNetCompact`).
 *
 * Key Performance & Memory Architecture:
 * 1. Verified Real-ESRGAN v3 Model (`app/src/main/assets/model.onnx`):
 *    - `SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4, act_type='prelu')`,
 *      `1,213,296` float32 parameters (`4,866,341` bytes, SHA-256 `d3b45e236c1cdc43c14b6aca839d59d6ac009f3791f998a16404252f6f65600e`).
 * 2. In-Memory Dynamic Spatial Dimension Patching (`UpscaleEngine.patchDynamicSpatialDims`):
 *    - Unlocks resolution-independent `(inH, inW) -> (inH*4, inW*4)` strip/tile execution on the exact same trained weights,
 *      enabling wide horizontal tiles (`T_X = 384`, `T_Y = 192`, `P = 16` overlap halo) and exact edge tile dimensions
 *      so right/bottom edge tiles never compute wasted reflection padding (with automatic fallback to fixed `224x224` tiles).
 * 3. Bulk `FloatArray` <-> `FloatBuffer` Transfer & Preallocated Buffers:
 *    - Eliminates ~2.5 million per-element `FloatBuffer.get(int)/put(int, float)` calls per tile.
 * 4. Zero Full-Image Fallback for All 8 EXIF Orientations & Alpha Channels (`ExifTileMapper` + `BitmapRegionDecoder`):
 *    - Decodes only the required raw sensor bounding rectangle per strip/tile for all 8 EXIF orientations (`1..8`:
 *      `0°`, `90°`, `180°`, `270°`, `FLIP_H`, `FLIP_V`, `TRANSPOSE`, `TRANSVERSE`) and streams output strips directly into
 *      batched `StreamingPngWriter` (`Deflater.BEST_SPEED = 1`) with **ZERO full-source or full-output `Bitmap` allocation**.
 * 5. Monotonic High-Resolution Telemetry (`System.nanoTime()`) & Next-Image Metadata Prefetch:
 *    - Separately measures `decodeMs`, `inferenceMs`, `encodeWriteMs`, `cacheLookupMs`, and `tilesRun` per image while
 *      prefetching the next image's SHA-256 and EXIF/dimension headers in parallel.
 */
class Upscaler(
    ctx: Context,
    intraThreadsOverride: Int? = null,
    preferredProvider: UpscalerExecutionProvider = UpscalerExecutionProvider.CPU_XNNPACK
) : UpscalerRuntimeAdapter {
    interface Progress {
        fun tile(done: Int, total: Int)
        fun cancelled(): Boolean
    }

    data class TileRunTiming(
        val decodeMs: Long,
        val inferenceMs: Long,
        val encodeWriteMs: Long,
        val tilesRun: Int
    )

    class Cancelled : RuntimeException("cancelled")

    override val runtimeId: String = "onnxruntime-android-1.17.1"
    override val modelId: String = UpscaleEngine.SHIPPED_MODEL_SPEC.id
    override val tileSize: Int = T_Y
    override val tilePadding: Int = P

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    val activeProvider: String
    val dynamicShapesSupported: Boolean

    init {
        val rawModelBytes = ctx.assets.open("model.onnx").use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        }
        val threads = intraThreadsOverride ?: SafelightExportProcessor.detectRecommendedThreads(ctx)
        val patchedBytes = UpscaleEngine.patchDynamicSpatialDims(rawModelBytes)

        var createdSession: OrtSession? = null
        var providerName = "CPUExecutionProvider"
        var dynSupported = true

        try {
            val opts = buildSessionOptions(threads, preferredProvider)
            providerName = opts.second
            createdSession = env.createSession(patchedBytes, opts.first)
        } catch (_: Throwable) {
            dynSupported = false
            val fallbackOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            providerName = "CPUExecutionProvider (Static 224x224 Fallback)"
            createdSession = env.createSession(rawModelBytes, fallbackOpts)
        }
        session = createdSession!!
        activeProvider = providerName
        dynamicShapesSupported = dynSupported
    }

    private fun buildSessionOptions(
        threads: Int,
        preferredProvider: UpscalerExecutionProvider
    ): Pair<OrtSession.SessionOptions, String> {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(1)
        opts.setMemoryPatternOptimization(true)
        opts.setCPUArenaAllocator(true)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        try {
            opts.addConfigEntry("session.intra_op.allow_spinning", "1")
        } catch (_: Throwable) {}

        var providerDesc = "CPUExecutionProvider (${threads}t)"
        if (preferredProvider == UpscalerExecutionProvider.CPU_XNNPACK) {
            try {
                val m = opts.javaClass.getMethod("addXnnpack", Map::class.java)
                m.invoke(opts, mapOf("intra_op_num_threads" to threads.toString()))
                providerDesc = "XNNPACKExecutionProvider + CPU (${threads}t)"
            } catch (_: Throwable) {
                // XNNPACK method not exposed on this ORT build; CPUExecutionProvider with NEON remains active
            }
        } else if (preferredProvider == UpscalerExecutionProvider.NNAPI_WITH_CPU_FALLBACK) {
            try {
                opts.addNnapi()
                providerDesc = "NnapiExecutionProvider + CPUFallback (${threads}t)"
            } catch (_: Throwable) {
                DiagnosticsLog.recordWarning("NNAPI provider unavailable on device; using CPU fallback.")
            }
        }
        return Pair(opts, providerDesc)
    }

    /**
     * Truly bounded-memory strip-to-PNG-file upscaler for ALL 8 EXIF orientations and alpha-channel PNGs:
     * - Decodes source strips via `BitmapRegionDecoder` + `ExifTileMapper` (zero full source `Bitmap` or full `IntArray`).
     * - Streams upscaled horizontal strips directly into batched `StreamingPngWriter` (`Deflater.BEST_SPEED = 1`)
     *   with **ZERO full output `Bitmap` allocation**.
     * - Returns monotonic `(decodeMs, inferenceMs, encodeWriteMs, tilesRun)` for Phase 1 telemetry.
     */
    fun upscaleToPngFile(srcFile: File, outFile: File, scale: Int, pr: Progress): TileRunTiming {
        require(scale == 2 || scale == 4) { "Scale must be 2 or 4 (got $scale)." }
        val exifOrientation = SafelightExportProcessor.readExifOrientation(srcFile)
        val hasAlpha = SafelightExportProcessor.probePngHasAlpha(srcFile)

        @Suppress("DEPRECATION")
        val decoder = if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(srcFile.path)
        } else {
            BitmapRegionDecoder.newInstance(srcFile.path, false)
        } ?: throw IllegalArgumentException("Could not open region decoder for '${srcFile.name}'.")

        var decodeNs = 0L
        var inferNs = 0L
        var encodeNs = 0L
        var tilesExecuted = 0

        try {
            val rawW = decoder.width
            val rawH = decoder.height
            val (w, h) = ExifTileMapper.orientedDimensions(rawW, rawH, exifOrientation)
            val sw = if (scale == 2) (w + 1) / 2 else w
            val sh = if (scale == 2) (h + 1) / 2 else h
            val tw = Math.multiplyExact(w, scale)
            val th = Math.multiplyExact(h, scale)

            val stepX = if (dynamicShapesSupported) T_X_DYN else T_Y
            val stepY = T_Y
            val nx = (sw + stepX - 1) / stepX
            val ny = (sh + stepY - 1) / stepY
            val total = nx * ny
            var done = 0

            val stripMaxH = min(th, stepY * S)
            val stripPixels = IntArray(Math.multiplyExact(tw, stripMaxH))

            // Preallocate reusable buffers for the maximum tile size (maxInW x maxInH)
            val maxInW = (if (dynamicShapesSupported) min(sw, stepX) else T_Y) + 2 * P
            val maxInH = (if (dynamicShapesSupported) min(sh, stepY) else T_Y) + 2 * P
            val maxPlane = maxInW * maxInH
            val maxOPlane = (maxInW * S) * (maxInH * S)

            val srcWinPixels = IntArray(Math.multiplyExact(sw, maxInH))
            val inFloatArray = FloatArray(maxPlane * 3)
            val outFloatArray = FloatArray(maxOPlane * 3)
            val directInBuf = ByteBuffer.allocateDirect(maxPlane * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val rawRegionScratch = IntArray(max(1, min(rawW * rawH, max(rawW, rawH) * (maxInH * 2 + 8))))

            StreamingPngWriter.writeFile(
                targetFile = outFile,
                width = tw,
                height = th,
                hasAlpha = hasAlpha,
                compressionLevel = Deflater.BEST_SPEED
            ) { pngWriter ->
                for (ty in 0 until ny) {
                    if (pr.cancelled()) throw Cancelled()
                    val sy = ty * stepY
                    val curInnerH = if (dynamicShapesSupported) min(stepY, sh - sy) else stepY
                    val inH = curInnerH + 2 * P
                    val outY = sy * S
                    val stripH = min(curInnerH * S, th - outY)
                    if (stripH <= 0) break

                    // 1. Decode oriented source strip [sy - P .. sy - P + inH - 1] via BitmapRegionDecoder + ExifTileMapper
                    val tDec0 = System.nanoTime()
                    decodeOrientedSourceStrip(
                        decoder = decoder,
                        rawW = rawW,
                        rawH = rawH,
                        exifOrientation = exifOrientation,
                        w = w,
                        h = h,
                        scale = scale,
                        sw = sw,
                        sh = sh,
                        sy = sy,
                        inH = inH,
                        dstWindow = srcWinPixels,
                        rawScratch = rawRegionScratch
                    )
                    decodeNs += (System.nanoTime() - tDec0)

                    // 2. Run ONNX tiles across the horizontal strip
                    for (tx in 0 until nx) {
                        if (pr.cancelled()) throw Cancelled()
                        val sx = tx * stepX
                        val curInnerW = if (dynamicShapesSupported) min(stepX, sw - sx) else stepX
                        val inW = curInnerW + 2 * P
                        val outX = sx * S
                        val plane = inW * inH
                        val oinW = inW * S
                        val oinH = inH * S
                        val oplane = oinW * oinH

                        val tInf0 = System.nanoTime()
                        // Populate inFloatArray in NCHW RGB [0, 1] order and bulk-put into direct FloatBuffer
                        for (j in 0 until inH) {
                            val rowIn = j * sw
                            val rowOut = j * inW
                            for (i in 0 until inW) {
                                val c = srcWinPixels[rowIn + clamp(sx - P + i, 0, sw - 1)]
                                val idx = rowOut + i
                                inFloatArray[idx] = ((c shr 16) and 255) * INV_255
                                inFloatArray[plane + idx] = ((c shr 8) and 255) * INV_255
                                inFloatArray[plane * 2 + idx] = (c and 255) * INV_255
                            }
                        }
                        directInBuf.clear()
                        directInBuf.put(inFloatArray, 0, plane * 3)
                        directInBuf.rewind()
                        directInBuf.limit(plane * 3)

                        val tensorShape = longArrayOf(1L, 3L, inH.toLong(), inW.toLong())
                        OnnxTensor.createTensor(env, directInBuf, tensorShape).use { tensor ->
                            session.run(Collections.singletonMap("x", tensor)).use { res ->
                                val ot = (res.get(0) as OnnxTensor).floatBuffer
                                ot.rewind()
                                ot.get(outFloatArray, 0, oplane * 3)
                                val copyW = min(curInnerW * S, tw - outX)
                                val copyH = min(curInnerH * S, stripH)
                                val padOut = P * S
                                for (j in 0 until copyH) {
                                    val rowIn = (j + padOut) * oinW + padOut
                                    val rowOut = j * tw + outX
                                    for (i in 0 until copyW) {
                                        val ip = rowIn + i
                                        val r = to8(outFloatArray[ip])
                                        val g = to8(outFloatArray[oplane + ip])
                                        val b = to8(outFloatArray[oplane * 2 + ip])
                                        stripPixels[rowOut + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                    }
                                }
                            }
                        }
                        inferNs += (System.nanoTime() - tInf0)
                        tilesExecuted++
                        done++
                        pr.tile(done, total)
                    }

                    // 3. Optional alpha-channel strip interpolation (decoded per strip without full-image allocation)
                    if (hasAlpha) {
                        val tAlpha0 = System.nanoTime()
                        applyAlphaStripFromDecoder(
                            decoder = decoder,
                            rawW = rawW,
                            rawH = rawH,
                            exifOrientation = exifOrientation,
                            srcW = w,
                            srcH = h,
                            stripPx = stripPixels,
                            outW = tw,
                            outYStart = outY,
                            stripH = stripH,
                            scale = scale
                        )
                        decodeNs += (System.nanoTime() - tAlpha0)
                    }

                    // 4. Stream completed horizontal strip into batched PNG IDAT chunks
                    val tEnc0 = System.nanoTime()
                    pngWriter.writeStrip(stripPixels, rows = stripH, stripOffset = 0, stripStride = tw)
                    encodeNs += (System.nanoTime() - tEnc0)
                }
            }
            return TileRunTiming(
                decodeMs = max(0L, decodeNs / 1_000_000L),
                inferenceMs = max(0L, inferNs / 1_000_000L),
                encodeWriteMs = max(0L, encodeNs / 1_000_000L),
                tilesRun = tilesExecuted
            )
        } finally {
            try { decoder.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * Decodes only the required vertical window `[sy - P .. sy - P + inH - 1]` of the oriented source image
     * for any EXIF orientation (`1..8`) using `BitmapRegionDecoder` + `ExifTileMapper`.
     * Never allocates a full-image `Bitmap` or full `IntArray(w * h)`.
     */
    private fun decodeOrientedSourceStrip(
        decoder: BitmapRegionDecoder,
        rawW: Int,
        rawH: Int,
        exifOrientation: Int,
        w: Int,
        h: Int,
        scale: Int,
        sw: Int,
        sh: Int,
        sy: Int,
        inH: Int,
        dstWindow: IntArray,
        rawScratch: IntArray
    ) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        if (scale == 4) {
            val yStart = clamp(sy - P, 0, h - 1)
            val yEnd = clamp(sy - P + inH - 1, 0, h - 1)
            val oh = yEnd - yStart + 1
            val rawRect = ExifTileMapper.rawBoundingRectForOrientedRegion(0, yStart, w, oh, rawW, rawH, exifOrientation)
            val regionBm = decoder.decodeRegion(Rect(rawRect.left, rawRect.top, rawRect.rightExclusive, rawRect.bottomExclusive), opts)
                ?: throw IllegalStateException("Failed to decode 4x source strip [$yStart..$yEnd]")
            val regW = regionBm.width
            val regH = regionBm.height
            val rawBuf = if (rawScratch.size >= regW * regH) rawScratch else IntArray(regW * regH)
            try {
                regionBm.getPixels(rawBuf, 0, regW, 0, 0, regW, regH)
            } finally {
                regionBm.recycle()
            }
            ExifTileMapper.extractOrientedWindowFromRawRegion(
                rawPixels = rawBuf,
                rawRect = rawRect,
                rawW = rawW,
                rawH = rawH,
                exifOrientation = exifOrientation,
                oxStart = 0,
                oyStart = sy - P,
                ow = sw,
                oh = inH,
                dst = dstWindow
            )
        } else {
            // scale == 2: each sw x sh pixel is a 2x2 box average of oriented w x h
            val sYMin = clamp(sy - P, 0, sh - 1)
            val sYMax = clamp(sy - P + inH - 1, 0, sh - 1)
            val oYStart = clamp(sYMin * 2, 0, h - 1)
            val oYEnd = clamp(sYMax * 2 + 1, 0, h - 1)
            val oStripH = oYEnd - oYStart + 1
            val rawRect = ExifTileMapper.rawBoundingRectForOrientedRegion(0, oYStart, w, oStripH, rawW, rawH, exifOrientation)
            val regionBm = decoder.decodeRegion(Rect(rawRect.left, rawRect.top, rawRect.rightExclusive, rawRect.bottomExclusive), opts)
                ?: throw IllegalStateException("Failed to decode 2x source strip [$oYStart..$oYEnd]")
            val regW = regionBm.width
            val regH = regionBm.height
            val rawBuf = if (rawScratch.size >= regW * regH) rawScratch else IntArray(regW * regH)
            try {
                regionBm.getPixels(rawBuf, 0, regW, 0, 0, regW, regH)
            } finally {
                regionBm.recycle()
            }
            val orientedStrip = IntArray(w * oStripH)
            ExifTileMapper.extractOrientedWindowFromRawRegion(
                rawPixels = rawBuf,
                rawRect = rawRect,
                rawW = rawW,
                rawH = rawH,
                exifOrientation = exifOrientation,
                oxStart = 0,
                oyStart = oYStart,
                ow = w,
                oh = oStripH,
                dst = orientedStrip
            )
            for (j in 0 until inH) {
                val curSy = clamp(sy - P + j, 0, sh - 1)
                val r0 = (min(curSy * 2, h - 1) - oYStart).coerceIn(0, oStripH - 1) * w
                val r1 = (min(curSy * 2 + 1, h - 1) - oYStart).coerceIn(0, oStripH - 1) * w
                val dstRow = j * sw
                for (x in 0 until sw) {
                    val x0 = min(x * 2, w - 1)
                    val x1 = min(x * 2 + 1, w - 1)
                    val a = orientedStrip[r0 + x0]
                    val b = orientedStrip[r0 + x1]
                    val c = orientedStrip[r1 + x0]
                    val d = orientedStrip[r1 + x1]
                    val rr = (((a shr 16) and 255) + ((b shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255) + 2) shr 2
                    val gg = (((a shr 8) and 255) + ((b shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255) + 2) shr 2
                    val bb = ((a and 255) + (b and 255) + (c and 255) + (d and 255) + 2) shr 2
                    dstWindow[dstRow + x] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                }
            }
        }
    }

    /** Decodes only the alpha-channel strip needed for `[outYStart .. outYStart + stripH)` without a full-image allocation. */
    private fun applyAlphaStripFromDecoder(
        decoder: BitmapRegionDecoder,
        rawW: Int,
        rawH: Int,
        exifOrientation: Int,
        srcW: Int,
        srcH: Int,
        stripPx: IntArray,
        outW: Int,
        outYStart: Int,
        stripH: Int,
        scale: Int
    ) {
        val invScale = 1.0f / scale.toFloat()
        val srcY0 = clamp(((outYStart * invScale) - 1f).toInt(), 0, srcH - 1)
        val srcY1 = clamp((((outYStart + stripH) * invScale) + 2f).toInt(), srcY0, srcH - 1)
        val winH = srcY1 - srcY0 + 1
        val rawRect = ExifTileMapper.rawBoundingRectForOrientedRegion(0, srcY0, srcW, winH, rawW, rawH, exifOrientation)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val regionBm = decoder.decodeRegion(Rect(rawRect.left, rawRect.top, rawRect.rightExclusive, rawRect.bottomExclusive), opts)
            ?: return
        val regW = regionBm.width
        val regH = regionBm.height
        val rawBuf = IntArray(regW * regH)
        try {
            regionBm.getPixels(rawBuf, 0, regW, 0, 0, regW, regH)
        } finally {
            regionBm.recycle()
        }
        val alphaWin = IntArray(srcW * winH)
        ExifTileMapper.extractOrientedWindowFromRawRegion(
            rawPixels = rawBuf,
            rawRect = rawRect,
            rawW = rawW,
            rawH = rawH,
            exifOrientation = exifOrientation,
            oxStart = 0,
            oyStart = srcY0,
            ow = srcW,
            oh = winH,
            dst = alphaWin
        )
        for (sy in 0 until stripH) {
            val oy = outYStart + sy
            val fy = (oy + 0.5f) * invScale - 0.5f
            val y0 = (clamp(fy.toInt(), 0, srcH - 1) - srcY0).coerceIn(0, winH - 1)
            val y1 = (clamp(fy.toInt() + 1, 0, srcH - 1) - srcY0).coerceIn(0, winH - 1)
            val wy = (fy - fy.toInt()).coerceIn(0f, 1f)
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = sy * outW
            for (ox in 0 until outW) {
                val fx = (ox + 0.5f) * invScale - 0.5f
                val x0 = clamp(fx.toInt(), 0, srcW - 1)
                val x1 = clamp(x0 + 1, 0, srcW - 1)
                val wx = (fx - x0).coerceIn(0f, 1f)
                val a00 = (alphaWin[row0 + x0] ushr 24) and 0xFF
                val a01 = (alphaWin[row0 + x1] ushr 24) and 0xFF
                val a10 = (alphaWin[row1 + x0] ushr 24) and 0xFF
                val a11 = (alphaWin[row1 + x1] ushr 24) and 0xFF
                val top = a00 + (a01 - a00) * wx
                val bot = a10 + (a11 - a10) * wx
                val a = clamp((top + (bot - top) * wy + 0.5f).toInt(), 0, 255)
                stripPx[dstRow + ox] = (a shl 24) or (stripPx[dstRow + ox] and 0x00FFFFFF)
            }
        }
    }

    /**
     * Used ONLY for small-frame JPEG intermediates (`tw * th <= MAX_JPEG_BITMAP_PIXELS` = `3840x2160`)
     * where Android's `Bitmap.compress(CompressFormat.JPEG, ...)` requires a `Bitmap` instance.
     */
    fun upscale(px: IntArray, w: Int, h: Int, scale: Int, pr: Progress): Bitmap {
        var hasAlpha = false
        for (p in px) {
            if ((p ushr 24) != 0xFF) {
                hasAlpha = true
                break
            }
        }

        var src = px
        var sw = w
        var sh = h
        if (scale == 2) {
            sw = (w + 1) / 2
            sh = (h + 1) / 2
            src = IntArray(sw * sh)
            for (y in 0 until sh) {
                val y0 = min(y * 2, h - 1) * w
                val y1 = min(y * 2 + 1, h - 1) * w
                for (x in 0 until sw) {
                    val x0 = min(x * 2, w - 1)
                    val x1 = min(x * 2 + 1, w - 1)
                    val a = px[y0 + x0]
                    val b = px[y0 + x1]
                    val c = px[y1 + x0]
                    val d = px[y1 + x1]
                    val r = (((a shr 16) and 255) + ((b shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255) + 2) shr 2
                    val g = (((a shr 8) and 255) + ((b shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255) + 2) shr 2
                    val bl = ((a and 255) + (b and 255) + (c and 255) + (d and 255) + 2) shr 2
                    src[y * sw + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
        }

        val tw = Math.multiplyExact(w, scale)
        val th = Math.multiplyExact(h, scale)
        val stepX = if (dynamicShapesSupported) T_X_DYN else T_Y
        val stepY = T_Y
        val nx = (sw + stepX - 1) / stepX
        val ny = (sh + stepY - 1) / stepY
        val total = nx * ny
        var done = 0

        val outBitmap = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        var completed = false
        try {
            val stripMaxH = min(th, stepY * S)
            val stripPixels = IntArray(Math.multiplyExact(tw, stripMaxH))
            val maxInW = (if (dynamicShapesSupported) min(sw, stepX) else T_Y) + 2 * P
            val maxInH = (if (dynamicShapesSupported) min(sh, stepY) else T_Y) + 2 * P
            val maxPlane = maxInW * maxInH
            val maxOPlane = (maxInW * S) * (maxInH * S)
            val inFloatArray = FloatArray(maxPlane * 3)
            val outFloatArray = FloatArray(maxOPlane * 3)
            val directInBuf = ByteBuffer.allocateDirect(maxPlane * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()

            for (ty in 0 until ny) {
                val sy = ty * stepY
                val curInnerH = if (dynamicShapesSupported) min(stepY, sh - sy) else stepY
                val inH = curInnerH + 2 * P
                val outY = sy * S
                val stripH = min(curInnerH * S, th - outY)
                if (stripH <= 0) break

                for (tx in 0 until nx) {
                    if (pr.cancelled()) throw Cancelled()
                    val sx = tx * stepX
                    val curInnerW = if (dynamicShapesSupported) min(stepX, sw - sx) else stepX
                    val inW = curInnerW + 2 * P
                    val outX = sx * S
                    val plane = inW * inH
                    val oinW = inW * S
                    val oinH = inH * S
                    val oplane = oinW * oinH

                    for (j in 0 until inH) {
                        val rowIn = clamp(sy - P + j, 0, sh - 1) * sw
                        val rowOut = j * inW
                        for (i in 0 until inW) {
                            val c = src[rowIn + clamp(sx - P + i, 0, sw - 1)]
                            val idx = rowOut + i
                            inFloatArray[idx] = ((c shr 16) and 255) * INV_255
                            inFloatArray[plane + idx] = ((c shr 8) and 255) * INV_255
                            inFloatArray[plane * 2 + idx] = (c and 255) * INV_255
                        }
                    }
                    directInBuf.clear()
                    directInBuf.put(inFloatArray, 0, plane * 3)
                    directInBuf.rewind()
                    directInBuf.limit(plane * 3)

                    val tensorShape = longArrayOf(1L, 3L, inH.toLong(), inW.toLong())
                    OnnxTensor.createTensor(env, directInBuf, tensorShape).use { tensor ->
                        session.run(Collections.singletonMap("x", tensor)).use { res ->
                            val ot = (res.get(0) as OnnxTensor).floatBuffer
                            ot.rewind()
                            ot.get(outFloatArray, 0, oplane * 3)
                            val copyW = min(curInnerW * S, tw - outX)
                            val copyH = min(curInnerH * S, stripH)
                            val padOut = P * S
                            for (j in 0 until copyH) {
                                val rowIn = (j + padOut) * oinW + padOut
                                val rowOut = j * tw + outX
                                for (i in 0 until copyW) {
                                    val ip = rowIn + i
                                    val r = to8(outFloatArray[ip])
                                    val g = to8(outFloatArray[oplane + ip])
                                    val b = to8(outFloatArray[oplane * 2 + ip])
                                    stripPixels[rowOut + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                        }
                    }
                    done++
                    pr.tile(done, total)
                }

                if (hasAlpha) {
                    applyAlphaStrip(px, w, h, stripPixels, tw, outY, stripH, scale)
                }
                outBitmap.setPixels(stripPixels, 0, tw, 0, outY, tw, stripH)
            }
            completed = true
            return outBitmap
        } finally {
            if (!completed) {
                try { outBitmap.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun applyAlphaStrip(
        srcPx: IntArray,
        srcW: Int,
        srcH: Int,
        stripPx: IntArray,
        outW: Int,
        outYStart: Int,
        stripH: Int,
        scale: Int
    ) {
        val invScale = 1.0f / scale.toFloat()
        for (sy in 0 until stripH) {
            val oy = outYStart + sy
            val fy = (oy + 0.5f) * invScale - 0.5f
            val y0 = clamp(fy.toInt(), 0, srcH - 1)
            val y1 = clamp(y0 + 1, 0, srcH - 1)
            val wy = (fy - y0).coerceIn(0f, 1f)
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = sy * outW
            for (ox in 0 until outW) {
                val fx = (ox + 0.5f) * invScale - 0.5f
                val x0 = clamp(fx.toInt(), 0, srcW - 1)
                val x1 = clamp(x0 + 1, 0, srcW - 1)
                val wx = (fx - x0).coerceIn(0f, 1f)
                val a00 = (srcPx[row0 + x0] ushr 24) and 0xFF
                val a01 = (srcPx[row0 + x1] ushr 24) and 0xFF
                val a10 = (srcPx[row1 + x0] ushr 24) and 0xFF
                val a11 = (srcPx[row1 + x1] ushr 24) and 0xFF
                val top = a00 + (a01 - a00) * wx
                val bot = a10 + (a11 - a10) * wx
                val a = clamp((top + (bot - top) * wy + 0.5f).toInt(), 0, 255)
                stripPx[dstRow + ox] = (a shl 24) or (stripPx[dstRow + ox] and 0x00FFFFFF)
            }
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {}
    }

    companion object {
        private const val S = 4
        private const val T_Y = 192
        private const val T_X_DYN = 384
        private const val P = 16
        private const val INV_255 = 1.0f / 255.0f

        private fun clamp(v: Int, lo: Int, hi: Int): Int =
            if (v < lo) lo else if (v > hi) hi else v

        private fun to8(v: Float): Int {
            val q = (v * 255.0f + 0.5f).toInt()
            return if (q < 0) 0 else if (q > 255) 255 else q
        }
    }
}

object SafelightExportProcessor {
    class Out(val bm: Bitmap, val scale: Int, val w: Int, val h: Int, val note: String = "")

    data class PrefetchedImageMeta(
        val srcPath: String,
        val sha256: String,
        val exifOrientation: Int,
        val exifRotationDeg: Int,
        val srcW: Int,
        val srcH: Int,
        val fileSizeBytes: Long,
        val metaMs: Long
    )

    class PlanUpscaleResult(
        val plan: ExportPlan.Plan,
        val queue: List<UpscaleQueueItem>,
        val warnings: List<String>,
        val cacheHits: Int,
        val upscaledCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val metrics: List<UpscaleImageMetric> = emptyList(),
        val aggregateStats: UpscaleAggregateStats? = null
    )

    fun availableJavaHeapBytes(): Long {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return (rt.maxMemory() - used).coerceAtLeast(32L * 1024L * 1024L)
    }

    fun detectRecommendedThreads(ctx: Context): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        val availMb = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            (mi.availMem / (1024L * 1024L)).coerceAtLeast(256L)
        } catch (_: Throwable) {
            2048L
        }
        val thermal = try {
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                when (pm?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) {
                    PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> 0
                    PowerManager.THERMAL_STATUS_MODERATE -> 1
                    else -> 2
                }
            } else 0
        } catch (_: Throwable) {
            0
        }
        return UpscaleEngine.recommendedThreads(cores, availMb, thermal)
    }

    fun cacheInstance(ctx: Context): UpscaleCache {
        val prefs = ctx.getSharedPreferences("export_prefs", Context.MODE_PRIVATE)
        val limitMb = prefs.getInt("upscale.cacheLimitMb", 512).coerceIn(128, 4096)
        return UpscaleCache(File(ctx.filesDir, "upscale_cache"), limitMb * 1024L * 1024L)
    }

    /** Returns the raw EXIF orientation tag (`1..8`), defaulting to `ExifInterface.ORIENTATION_NORMAL` (`1`). */
    fun readExifOrientation(file: File): Int = try {
        val exif = ExifInterface(file.path)
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL).let {
            if (it in 1..8) it else ExifInterface.ORIENTATION_NORMAL
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Encodes full EXIF orientation (including mirror/transpose states) into an integer key for cache disambiguation. */
    fun readExifRotation(file: File): Int = when (val ori = readExifOrientation(file)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        ExifInterface.ORIENTATION_NORMAL -> 0
        else -> ori * 100 // Distinguishes FLIP_HORIZONTAL (200), FLIP_VERTICAL (400), TRANSPOSE (500), TRANSVERSE (700)
    }

    /** Fast header inspection to check if a source file is a PNG with an alpha channel (`colorType == 4` or `6` or `tRNS`). */
    fun probePngHasAlpha(file: File): Boolean {
        if (!file.isFile || file.length() < 26L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(26)
                raf.readFully(head)
                for (i in 0 until 8) {
                    if (head[i] != StreamingPngWriter.PNG_SIGNATURE[i]) return false
                }
                val colorType = head[25].toInt() and 0xFF
                colorType == 4 || colorType == 6 || colorType == 3
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Reads image width and height (accounting for EXIF orientation 5..8 dimension swap) without allocating pixel memory. */
    fun probeDimensions(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        val ori = readExifOrientation(file)
        return ExifTileMapper.orientedDimensions(opts.outWidth, opts.outHeight, ori)
    }

    private fun prefetchMeta(srcPath: String): PrefetchedImageMeta {
        val t0 = System.nanoTime()
        val f = File(srcPath)
        val sha = try { Validation.sha256(srcPath) } catch (_: Exception) { "${f.length()}_${f.lastModified()}" }
        val ori = readExifOrientation(f)
        val rotKey = readExifRotation(f)
        val (srcW, srcH) = probeDimensions(f)
        return PrefetchedImageMeta(
            srcPath = srcPath,
            sha256 = sha,
            exifOrientation = ori,
            exifRotationDeg = rotKey,
            srcW = srcW,
            srcH = srcH,
            fileSizeBytes = f.length(),
            metaMs = Diagnostics.nanosToMs(t0)
        )
    }

    /**
     * Priority 1 (Section 2.2.6): Runs preflight dimension and memory checks across all unique images in `plan`
     * BEFORE starting export. Never silently lowers scale factor or quality; returns the first image requiring a user choice.
     */
    fun preflightPlan(
        plan: ExportPlan.Plan,
        factor: UpscaleFactor,
        format: IntermediateFormat,
        profile: UpscaleProfile,
        targetSettings: ExportSettings
    ): UpscalePreflightResult {
        if (profile == UpscaleProfile.MANUAL && factor == UpscaleFactor.OFF) {
            return UpscalePreflightResult(safe = true, allocationMap = null, requiresUserChoice = false)
        }
        val uniquePaths = ExportOptions.uniqueSourceImages(plan).filter { File(it).isFile }
        val availHeap = availableJavaHeapBytes()
        for (srcPath in uniquePaths) {
            val f = File(srcPath)
            val (srcW, srcH) = probeDimensions(f)
            if (srcW <= 0 || srcH <= 0) continue
            val (effFactor, effFormat) = UpscaleEngine.resolveForImage(
                profile = profile,
                manualFactor = factor,
                manualFormat = format,
                srcW = srcW,
                srcH = srcH,
                targetW = targetSettings.width,
                targetH = targetSettings.height
            )
            if (effFactor == UpscaleFactor.OFF) continue
            val check = UpscaleMemoryGuard.preflightCheck(
                srcW = srcW,
                srcH = srcH,
                scale = effFactor.scale,
                format = effFormat,
                availableHeapBytes = availHeap
            )
            if (!check.safe) return check
        }
        return UpscalePreflightResult(safe = true, allocationMap = null, requiresUserChoice = false)
    }

    fun decodeFileWithExif(file: File): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bm = BitmapFactory.decodeFile(file.path, opts)
            ?: throw IllegalArgumentException("could not read picture '${file.name}'")
        val ori = readExifOrientation(file)
        if (ori != ExifInterface.ORIENTATION_NORMAL) {
            val m = Matrix().apply {
                when (ori) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                }
            }
            val rotated = try {
                Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            } finally {
                bm.recycle()
            }
            bm = rotated
        }
        return bm
    }

    fun decode(bytes: ByteArray): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("could not read this picture")
        val deg = try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
        if (deg != 0) {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            val rotated = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
            if (rotated !== bm) bm.recycle()
            bm = rotated
        }
        return bm
    }

    fun saveTempImage(bm: Bitmap, outFile: File, format: IntermediateFormat) {
        try {
            val toWrite = if (!format.png && bm.hasAlpha()) {
                val opaque = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
                opaque.eraseColor(Color.BLACK)
                Canvas(opaque).drawBitmap(bm, 0f, 0f, null)
                opaque
            } else {
                bm
            }
            try {
                outFile.outputStream().buffered(65536).use { stream ->
                    val compressFormat = if (format.png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    val quality = if (format.png) 100 else 95
                    if (!toWrite.compress(compressFormat, quality, stream)) {
                        throw IllegalStateException("Failed to encode intermediate ${format.shortLabel} image")
                    }
                }
            } finally {
                if (toWrite !== bm) toWrite.recycle()
            }
        } finally {
            bm.recycle()
        }
    }

    /**
     * Processes unique source images in `plan` with:
     * - Stage overlap (prefetching image `idx + 1` SHA-256 + EXIF/dimensions on a background prefetcher while image `idx` runs ONNX).
     * - Cache integrity verification (`verifyIntegrity = true`) before reusing any cached `ucache_*` file.
     * - Zero full-output `Bitmap` (`StreamingPngWriter` at `Deflater.BEST_SPEED = 1`) and zero full-source `Bitmap` for all 8 EXIF orientations.
     * - Monotonic `System.nanoTime()` telemetry for `CACHE_LOOKUP`, `IMAGE_DECODE`, `UPSCALE` (`inference`), and `PNG_ENCODE_WRITE`.
     */
    fun processPlanDetailed(
        ctx: Context,
        plan: ExportPlan.Plan,
        factor: UpscaleFactor,
        format: IntermediateFormat,
        profile: UpscaleProfile = UpscaleProfile.MANUAL,
        targetSettings: ExportSettings = ExportSettings.P720,
        strictMode: Boolean = false,
        cancelled: AtomicBoolean,
        onProgress: (pct: Int, done: Int, total: Int, status: String) -> Unit
    ): PlanUpscaleResult {
        if (profile == UpscaleProfile.MANUAL && factor == UpscaleFactor.OFF) {
            return PlanUpscaleResult(plan, emptyList(), emptyList(), 0, 0, 0, 0)
        }
        val uniquePaths = ExportOptions.uniqueSourceImages(plan).filter { File(it).isFile }
        if (uniquePaths.isEmpty()) {
            return PlanUpscaleResult(plan, emptyList(), emptyList(), 0, 0, 0, 0)
        }

        val cache = cacheInstance(ctx)
        val processedByKey = HashMap<String, String>()
        val queue = ArrayList<UpscaleQueueItem>(uniquePaths.size)
        val imageMetrics = ArrayList<UpscaleImageMetric>(uniquePaths.size)
        val warnings = ArrayList<String>()
        val totalImages = uniquePaths.size
        var cacheHits = 0
        var upscaledCount = 0
        var skippedCount = 0
        var failedCount = 0
        var totalCacheLookupMs = 0L

        val prefetcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "upscale-meta-prefetcher").apply { isDaemon = true }
        }
        var nextMetaFuture: Future<PrefetchedImageMeta>? = prefetcher.submit(Callable { prefetchMeta(uniquePaths[0]) })

        var lazyUpscaler: Upscaler? = null
        try {
            for ((idx, srcPath) in uniquePaths.withIndex()) {
                if (cancelled.get()) throw Upscaler.Cancelled()
                val freeBytes = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
                if (freeBytes < 64L * 1024L * 1024L) {
                    throw IllegalStateException("Storage ran low (${com.auto.core.MediaLibrary.humanSize(freeBytes)} free). Stopping safely; source and project files are untouched.")
                }

                val itemStartNs = System.nanoTime()
                val curMeta = try {
                    nextMetaFuture?.get() ?: prefetchMeta(srcPath)
                } catch (_: Exception) {
                    prefetchMeta(srcPath)
                }
                // Immediately dispatch metadata + SHA-256 prefetch for the NEXT image (idx + 1) in parallel!
                nextMetaFuture = if (idx + 1 < uniquePaths.size) {
                    val nextPath = uniquePaths[idx + 1]
                    prefetcher.submit(Callable { prefetchMeta(nextPath) })
                } else null

                val key = ExportOptions.canonicalKey(srcPath)
                val srcFile = File(srcPath)
                val sha = curMeta.sha256
                val exifRot = curMeta.exifRotationDeg
                val srcW = curMeta.srcW
                val srcH = curMeta.srcH
                val (effFactor, effFormat) = UpscaleEngine.resolveForImage(
                    profile = profile,
                    manualFactor = factor,
                    manualFormat = format,
                    srcW = srcW,
                    srcH = srcH,
                    targetW = targetSettings.width,
                    targetH = targetSettings.height
                )

                if (effFactor == UpscaleFactor.OFF) {
                    skippedCount++
                    val elapsedMs = Diagnostics.nanosToMs(itemStartNs)
                    queue.add(
                        UpscaleQueueItem(
                            index = idx + 1,
                            sourcePath = srcPath,
                            sourceHash = sha,
                            targetScale = 1,
                            state = UpscaleItemState.SKIPPED,
                            outputPath = srcPath,
                            fromCache = false,
                            elapsedMs = elapsedMs
                        )
                    )
                    val m = UpscaleImageMetric(
                        index = idx + 1,
                        srcWidth = srcW,
                        srcHeight = srcH,
                        fileSizeBytes = curMeta.fileSizeBytes,
                        exifOrientation = curMeta.exifOrientation,
                        chosenScale = 1,
                        modelId = UpscaleEngine.SHIPPED_MODEL_SPEC.id,
                        runtimeProvider = "Skipped (Already covers target frame)",
                        cacheHit = false,
                        skipped = true,
                        totalMs = elapsedMs,
                        outWidth = srcW,
                        outHeight = srcH
                    )
                    imageMetrics.add(m)
                    DiagnosticsLog.recordImageMetric(m)
                    val pct = (((idx + 1).toFloat() / totalImages) * 50f).toInt().coerceIn(0, 50)
                    onProgress(pct, idx + 1, totalImages, "Image ${idx + 1}/$totalImages already adequate (${srcW}×$srcH) — skipped")
                    continue
                }

                val effQuality = if (effFormat.png) 100 else 95
                val tCache0 = System.nanoTime()
                val cachedFile = cache.get(
                    contentSha256 = sha,
                    scale = effFactor.scale,
                    format = effFormat,
                    quality = effQuality,
                    exifRotationDeg = exifRot,
                    verifyIntegrity = true
                )
                val cacheCheckMs = curMeta.metaMs + Diagnostics.nanosToMs(tCache0)
                totalCacheLookupMs += cacheCheckMs

                if (cachedFile != null) {
                    cacheHits++
                    upscaledCount++
                    processedByKey[key] = cachedFile.path
                    processedByKey[srcPath] = cachedFile.path
                    val elapsedMs = Diagnostics.nanosToMs(itemStartNs)
                    queue.add(
                        UpscaleQueueItem(
                            index = idx + 1,
                            sourcePath = srcPath,
                            sourceHash = sha,
                            targetScale = effFactor.scale,
                            state = UpscaleItemState.DONE,
                            outputPath = cachedFile.path,
                            fromCache = true,
                            elapsedMs = elapsedMs
                        )
                    )
                    val m = UpscaleImageMetric(
                        index = idx + 1,
                        srcWidth = srcW,
                        srcHeight = srcH,
                        fileSizeBytes = curMeta.fileSizeBytes,
                        exifOrientation = curMeta.exifOrientation,
                        chosenScale = effFactor.scale,
                        modelId = UpscaleEngine.SHIPPED_MODEL_SPEC.id,
                        runtimeProvider = "UpscaleCache (Verified Integrity)",
                        cacheHit = true,
                        totalMs = elapsedMs,
                        outWidth = srcW * effFactor.scale,
                        outHeight = srcH * effFactor.scale
                    )
                    imageMetrics.add(m)
                    DiagnosticsLog.recordImageMetric(m)
                    val pct = (((idx + 1).toFloat() / totalImages) * 50f).toInt().coerceIn(0, 50)
                    onProgress(pct, idx + 1, totalImages, "Reused cached upscale ${idx + 1}/$totalImages (${effFactor.label})")
                    continue
                }

                try {
                    val pf = UpscaleMemoryGuard.preflightCheck(srcW, srcH, effFactor.scale, effFormat, availableJavaHeapBytes())
                    if (!pf.safe) {
                        throw IllegalStateException(pf.reason)
                    }

                    val upscaler = lazyUpscaler ?: Upscaler(ctx).also { lazyUpscaler = it }
                    val pr = object : Upscaler.Progress {
                        override fun cancelled(): Boolean = cancelled.get()
                        override fun tile(done: Int, total: Int) {
                            val tileFrac = if (total > 0) done.toFloat() / total.toFloat() else 0f
                            val overallFrac = (idx + tileFrac) / totalImages.toFloat()
                            val pct = (overallFrac * 50f).toInt().coerceIn(0, 50)
                            val tilePct = (tileFrac * 100f).toInt().coerceIn(0, 100)
                            onProgress(
                                pct,
                                idx,
                                totalImages,
                                "Upscaling image ${idx + 1} of $totalImages (${effFactor.label} ${effFormat.shortLabel})... $tilePct%"
                            )
                        }
                    }

                    var itemDecodeMs = 0L
                    var itemInferMs = 0L
                    var itemEncodeMs = 0L
                    var itemTiles = 0

                    val savedFile = cache.put(
                        contentSha256 = sha,
                        scale = effFactor.scale,
                        format = effFormat,
                        quality = effQuality,
                        exifRotationDeg = exifRot,
                        tempProducer = { tmpFile ->
                            if (effFormat.png) {
                                // Bounded-memory streaming PNG path (ZERO full source or output Bitmap, supports all 8 EXIF orientations!)
                                val timing = upscaler.upscaleToPngFile(srcFile, tmpFile, effFactor.scale, pr)
                                itemDecodeMs = timing.decodeMs
                                itemInferMs = timing.inferenceMs
                                itemEncodeMs = timing.encodeWriteMs
                                itemTiles = timing.tilesRun
                            } else {
                                // Verified small-frame JPEG path (<= 4K output)
                                val tDec0 = System.nanoTime()
                                val bm = decodeFileWithExif(srcFile)
                                val w = bm.width
                                val h = bm.height
                                val px = IntArray(w * h)
                                try {
                                    bm.getPixels(px, 0, w, 0, 0, w, h)
                                } finally {
                                    bm.recycle()
                                }
                                itemDecodeMs = Diagnostics.nanosToMs(tDec0)
                                val tInf0 = System.nanoTime()
                                val outBm = upscaler.upscale(px, w, h, effFactor.scale, pr)
                                itemInferMs = Diagnostics.nanosToMs(tInf0)
                                val tEnc0 = System.nanoTime()
                                saveTempImage(outBm, tmpFile, effFormat)
                                itemEncodeMs = Diagnostics.nanosToMs(tEnc0)
                            }
                        }
                    )

                    upscaledCount++
                    processedByKey[key] = savedFile.path
                    processedByKey[srcPath] = savedFile.path
                    val elapsedMs = Diagnostics.nanosToMs(itemStartNs)
                    queue.add(
                        UpscaleQueueItem(
                            index = idx + 1,
                            sourcePath = srcPath,
                            sourceHash = sha,
                            targetScale = effFactor.scale,
                            state = UpscaleItemState.DONE,
                            outputPath = savedFile.path,
                            fromCache = false,
                            elapsedMs = elapsedMs
                        )
                    )
                    val m = UpscaleImageMetric(
                        index = idx + 1,
                        srcWidth = srcW,
                        srcHeight = srcH,
                        fileSizeBytes = curMeta.fileSizeBytes,
                        exifOrientation = curMeta.exifOrientation,
                        chosenScale = effFactor.scale,
                        modelId = upscaler.modelId,
                        runtimeProvider = upscaler.activeProvider,
                        cacheHit = false,
                        decodeMs = itemDecodeMs,
                        inferenceMs = itemInferMs,
                        encodeWriteMs = itemEncodeMs,
                        totalMs = elapsedMs,
                        outWidth = srcW * effFactor.scale,
                        outHeight = srcH * effFactor.scale,
                        tilesRun = itemTiles
                    )
                    imageMetrics.add(m)
                    DiagnosticsLog.recordImageMetric(m)
                } catch (c: Upscaler.Cancelled) {
                    throw c
                } catch (err: Throwable) {
                    failedCount++
                    if (strictMode) throw err
                    val msg = "Image #${idx + 1} upscale skipped/fallback (${err.message ?: "error"}); used original picture without silent scale reduction."
                    warnings.add(msg)
                    DiagnosticsLog.recordWarning(msg)
                    val elapsedMs = Diagnostics.nanosToMs(itemStartNs)
                    queue.add(
                        UpscaleQueueItem(
                            index = idx + 1,
                            sourcePath = srcPath,
                            sourceHash = sha,
                            targetScale = effFactor.scale,
                            state = UpscaleItemState.FAILED,
                            outputPath = srcPath,
                            fromCache = false,
                            elapsedMs = elapsedMs,
                            warning = msg
                        )
                    )
                    val m = UpscaleImageMetric(
                        index = idx + 1,
                        srcWidth = srcW,
                        srcHeight = srcH,
                        fileSizeBytes = curMeta.fileSizeBytes,
                        exifOrientation = curMeta.exifOrientation,
                        chosenScale = effFactor.scale,
                        modelId = UpscaleEngine.SHIPPED_MODEL_SPEC.id,
                        runtimeProvider = "FallbackOriginal",
                        cacheHit = false,
                        totalMs = elapsedMs,
                        failed = true,
                        failureReason = err.message ?: "error"
                    )
                    imageMetrics.add(m)
                    DiagnosticsLog.recordImageMetric(m)
                }
            }
        } finally {
            try { prefetcher.shutdownNow() } catch (_: Exception) {}
            try { lazyUpscaler?.close() } catch (_: Exception) {}
        }

        val agg = Diagnostics.aggregateUpscaleMetrics(imageMetrics)
        DiagnosticsLog.recordStage(DiagStage.CACHE_LOOKUP, totalCacheLookupMs, cacheHits, totalImages, skipped = skippedCount)
        DiagnosticsLog.recordStage(DiagStage.IMAGE_DECODE, agg.totalDecodeMs, agg.upscaledCount, totalImages, errors = failedCount, skipped = skippedCount)
        DiagnosticsLog.recordStage(DiagStage.PNG_ENCODE_WRITE, agg.totalEncodeWriteMs, agg.upscaledCount, totalImages, errors = failedCount, skipped = skippedCount)

        return PlanUpscaleResult(
            plan = ExportOptions.withProcessedImages(plan, processedByKey),
            queue = queue,
            warnings = warnings,
            cacheHits = cacheHits,
            upscaledCount = upscaledCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            metrics = imageMetrics,
            aggregateStats = agg
        )
    }

    fun processPlan(
        ctx: Context,
        plan: ExportPlan.Plan,
        factor: UpscaleFactor,
        format: IntermediateFormat,
        tempDir: File,
        cancelled: AtomicBoolean,
        onProgress: (pct: Int, status: String) -> Unit
    ): ExportPlan.Plan = processPlanDetailed(
        ctx = ctx,
        plan = plan,
        factor = factor,
        format = format,
        cancelled = cancelled,
        onProgress = { pct, _, _, status -> onProgress(pct, status) }
    ).plan
}
