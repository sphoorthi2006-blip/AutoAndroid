package com.auto.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import com.auto.core.ExportOptions
import com.auto.core.ExportPlan
import com.auto.core.IntermediateFormat
import com.auto.core.UpscaleFactor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Reused from Safelight-Upscaler-v2 (com.safelight.upscaler.Upscaler & JobRunner):
 * loads assets/model.onnx into ONNX Runtime with ALL_OPT and runs tiled 4x super-resolution
 * (tile inner size T = 192, padding P = 16, input tile IN = 224 -> output 896).
 * For 2x upscaling, box-downsamples by 2x first and runs the 4x restoration/upscaling pass,
 * then crops to exact target dimensions. Preserves alpha channel when present.
 */
class Upscaler(ctx: Context) : AutoCloseable {
    interface Progress {
        fun tile(done: Int, total: Int)
        fun cancelled(): Boolean
    }

    class Cancelled : RuntimeException("cancelled")

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = ctx.assets.open("model.onnx").use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
            }
            bos.toByteArray()
        }
        val opts = OrtSession.SessionOptions()
        val cores = Runtime.getRuntime().availableProcessors()
        opts.setIntraOpNumThreads(max(2, min(8, cores - 1)))
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(modelBytes, opts)
    }

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

        val ow = sw * S
        val oh = sh * S
        val oin = IN * S
        val oplane = oin * oin
        val plane = IN * IN
        val out = IntArray(ow * oh)
        val nx = (sw + T - 1) / T
        val ny = (sh + T - 1) / T
        val total = nx * ny
        var done = 0
        val fb = ByteBuffer.allocateDirect(plane * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val tensorShape = longArrayOf(1L, 3L, IN.toLong(), IN.toLong())

        for (ty in 0 until ny) {
            for (tx in 0 until nx) {
                if (pr.cancelled()) throw Cancelled()
                val sx = tx * T
                val sy = ty * T
                for (j in 0 until IN) {
                    val rowIn = clamp(sy - P + j, 0, sh - 1) * sw
                    for (i in 0 until IN) {
                        val c = src[rowIn + clamp(sx - P + i, 0, sw - 1)]
                        val idx = j * IN + i
                        fb.put(idx, ((c shr 16) and 255) / 255.0f)
                        fb.put(plane + idx, ((c shr 8) and 255) / 255.0f)
                        fb.put(plane * 2 + idx, (c and 255) / 255.0f)
                    }
                }
                OnnxTensor.createTensor(env, fb, tensorShape).use { tensor ->
                    session.run(Collections.singletonMap("x", tensor)).use { res ->
                        val ot = (res.get(0) as OnnxTensor).floatBuffer
                        val copyW = min(T, sw - sx) * S
                        val copyH = min(T, sh - sy) * S
                        for (j in 0 until copyH) {
                            val rowIn = (j + P * S) * oin + P * S
                            val rowOut = (sy * S + j) * ow + sx * S
                            for (i in 0 until copyW) {
                                val ip = rowIn + i
                                val r = to8(ot.get(ip))
                                val g = to8(ot.get(oplane + ip))
                                val b = to8(ot.get(oplane * 2 + ip))
                                out[rowOut + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                    }
                }
                done++
                pr.tile(done, total)
            }
        }

        val tw = w * scale
        val th = h * scale
        if (hasAlpha) {
            applyAlphaChannel(px, w, h, out, ow, min(tw, ow), min(th, oh), scale)
        }

        val bm = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        bm.setPixels(out, 0, ow, 0, 0, ow, oh)
        if (tw != ow || th != oh) {
            val cropped = Bitmap.createBitmap(bm, 0, 0, tw, th)
            bm.recycle()
            return cropped
        }
        return bm
    }

    private fun applyAlphaChannel(
        srcPx: IntArray,
        srcW: Int,
        srcH: Int,
        dstPx: IntArray,
        dstStride: Int,
        outW: Int,
        outH: Int,
        scale: Int
    ) {
        val invScale = 1.0f / scale.toFloat()
        for (oy in 0 until outH) {
            val fy = (oy + 0.5f) * invScale - 0.5f
            val y0 = clamp(fy.toInt(), 0, srcH - 1)
            val y1 = clamp(y0 + 1, 0, srcH - 1)
            val wy = (fy - y0).coerceIn(0f, 1f)
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = oy * dstStride
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
                dstPx[dstRow + ox] = (a shl 24) or (dstPx[dstRow + ox] and 0x00FFFFFF)
            }
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val S = 4
        private const val T = 192
        private const val P = 16
        private const val IN = 224

        private fun clamp(v: Int, lo: Int, hi: Int): Int =
            if (v < lo) lo else if (v > hi) hi else v

        private fun to8(v: Float): Int {
            val q = (v * 255.0f + 0.5f).toInt()
            return if (q < 0) 0 else if (q > 255) 255 else q
        }
    }
}

/**
 * Reuses Safelight's JobRunner image decoding (with EXIF orientation), low-memory fallback,
 * and JPEG/PNG compression to process unique timeline images into an app-private temporary export directory.
 */
object SafelightExportProcessor {
    class Out(val bm: Bitmap, val scale: Int, val w: Int, val h: Int, val note: String = "")

    /** Ported from Safelight JobRunner.decode([B): decodes bitmap and applies EXIF orientation rotation. */
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

    /** Ported from Safelight JobRunner.upscaleBytes: enforces safe pixel cap and runs tiled upscaling. */
    fun upscaleBytes(upscaler: Upscaler, bytes: ByteArray, requestedScale: Int, pr: Upscaler.Progress): Out {
        val bm = decode(bytes)
        val w = bm.width
        val h = bm.height
        var scale = requestedScale
        var note = ""
        if (scale == 4 && w.toLong() * h.toLong() * 16L > 20_000_000L) {
            scale = 2
            note = " (4× is too big for this picture, used 2×)"
        }
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        bm.recycle()
        val outBm = upscaler.upscale(px, w, h, scale, pr)
        return Out(outBm, scale, w, h, note)
    }

    /** Ported from Safelight JobRunner.runWithFallback: falls back from 4x to 2x on OOM / allocation failure. */
    fun runWithFallback(upscaler: Upscaler, bytes: ByteArray, scale: Int, pr: Upscaler.Progress): Out {
        try {
            return upscaleBytes(upscaler, bytes, scale, pr)
        } catch (c: Upscaler.Cancelled) {
            throw c
        } catch (t: Throwable) {
            System.gc()
            if (scale == 4) {
                try {
                    val o = upscaleBytes(upscaler, bytes, 2, pr)
                    return Out(o.bm, o.scale, o.w, o.h, o.note + " (used 2× because 4× did not fit)")
                } catch (c: Upscaler.Cancelled) {
                    throw c
                }
            }
            throw t
        }
    }

    /** Writes the upscaled Bitmap to a temporary file in JPEG (95) or PNG (100, lossless) format and recycles the Bitmap. */
    fun saveTempImage(bm: Bitmap, outFile: File, format: IntermediateFormat) {
        try {
            val toWrite = if (!format.png && bm.hasAlpha()) {
                // Composite over black if saving an image with alpha to JPEG (which does not support transparency)
                val opaque = Bitmap.createBitmap(bm.width, bm.height, Bitmap.Config.ARGB_8888)
                opaque.eraseColor(Color.BLACK)
                android.graphics.Canvas(opaque).drawBitmap(bm, 0f, 0f, null)
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
     * Reads the finalized export plan, identifies unique source images, upscales each unique source image once,
     * saves intermediate files in `tempDir`, and returns a new ExportPlan.Plan referencing the processed files.
     */
    fun processPlan(
        ctx: Context,
        plan: ExportPlan.Plan,
        factor: UpscaleFactor,
        format: IntermediateFormat,
        tempDir: File,
        cancelled: AtomicBoolean,
        onProgress: (pct: Int, status: String) -> Unit
    ): ExportPlan.Plan {
        if (factor == UpscaleFactor.OFF) return plan
        val uniquePaths = ExportOptions.uniqueSourceImages(plan).filter { File(it).isFile }
        if (uniquePaths.isEmpty()) return plan

        tempDir.mkdirs()
        try {
            File(tempDir, ".nomedia").createNewFile()
        } catch (_: Exception) {
        }

        val processedByKey = HashMap<String, String>()
        val totalImages = uniquePaths.size

        Upscaler(ctx).use { upscaler ->
            for ((idx, srcPath) in uniquePaths.withIndex()) {
                if (cancelled.get()) throw Upscaler.Cancelled()
                val key = ExportOptions.canonicalKey(srcPath)
                if (processedByKey.containsKey(key)) continue

                val srcFile = File(srcPath)
                val bytes = srcFile.readBytes()
                val pr = object : Upscaler.Progress {
                    override fun cancelled(): Boolean = cancelled.get()
                    override fun tile(done: Int, total: Int) {
                        val tileFrac = if (total > 0) done.toFloat() / total.toFloat() else 0f
                        val overallFrac = (idx + tileFrac) / totalImages.toFloat()
                        val pct = (overallFrac * 50f).toInt().coerceIn(0, 50)
                        val tilePct = (tileFrac * 100f).toInt().coerceIn(0, 100)
                        onProgress(
                            pct,
                            "Upscaling image ${idx + 1} of $totalImages (${factor.label} ${format.shortLabel})... $tilePct%"
                        )
                    }
                }

                val out = runWithFallback(upscaler, bytes, factor.scale, pr)
                val outFile = File(tempDir, "upscaled_${idx}_${out.scale}x.${format.ext}")
                saveTempImage(out.bm, outFile, format)
                processedByKey[key] = outFile.path
                processedByKey[srcPath] = outFile.path
            }
        }

        return ExportOptions.withProcessedImages(plan, processedByKey)
    }
}
