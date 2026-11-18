package com.suko.app.upscale

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

private fun writePng(img: ImageProcessor.Img, dest: File) {
    val bmp = Upscaler.toBitmap(img)
    try {
        FileOutputStream(dest).use { o ->
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, o)) throw IOException("could not write the upscaled picture")
        }
    } finally {
        bmp.recycle()
    }
}

/**
 * Step 14: the real engine (step 12 interface) on top of [Upscaler.run]: mark removal, then AI 2x (GPU first).
 * ImageProcessor catches every Throwable from the AI step and silently falls back to the classic method, which would
 * swallow a cancel thrown from the tile callback. So the callback's exception is remembered and thrown again here,
 * and a result that is not "AI" is reported as a failure so the runner can mark it DEGRADED with a reason.
 */
class AiEngine(private val ctx: Context, private val removeMark: Boolean) : UpscaleEngine {
    override fun upscale(job: UpscaleJob, dest: File, onTile: (Int, Int) -> Unit) {
        val src = Upscaler.decode(ctx, Uri.parse(ExportUpscale.sourceUri(job)))
            ?: throw IOException("could not read the picture ${job.entry.name}")
        val pending = AtomicReference<Throwable?>(null)
        val res = Upscaler.run(ctx, src, removeMark) { d, t ->
            try { onTile(d, t) } catch (e: Throwable) { pending.set(e); throw e }
        }
        pending.get()?.let { throw it }
        if (res.method != "AI") throw IllegalStateException(res.note ?: "the AI step did not run")
        writePng(res.img, dest)
    }
}

/** Fallback (step 15): the classic pipeline (mark removal, denoise, 2x, sharpen). No AI, no GPU. */
class ClassicEngine(private val ctx: Context, private val removeMark: Boolean) : UpscaleEngine {
    override fun upscale(job: UpscaleJob, dest: File, onTile: (Int, Int) -> Unit) {
        val src = Upscaler.decode(ctx, Uri.parse(ExportUpscale.sourceUri(job)))
            ?: throw IOException("could not read the picture ${job.entry.name}")
        val out = ImageProcessor.process(src, removeMark = removeMark, cropStrip = false, aiScale = 0)
        writePng(out, dest)
    }
}
