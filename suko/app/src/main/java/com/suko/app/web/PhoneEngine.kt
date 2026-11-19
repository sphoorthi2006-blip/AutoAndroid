package com.suko.app.web

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.suko.app.upscale.UpscaleCache
import com.suko.app.upscale.UpscaleKeys
import com.suko.app.upscale.UpscaleSettings
import com.suko.app.upscale.Upscaler
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The picture analyser (old steps 26-28). It is the desktop Image Layer Studio's own Python code (`app/src/main/python/ils/`, copied from v0.15.7, same maths),
 * run inside the app by Chaquopy with numpy + OpenCV. It replaces the "not ready" answers of `process` (separate a picture into pieces + pen plan)
 * and `panels` (find the comic panels, labels, captions, arrows). The page calls it through the normal bridge, on the bridge's background thread.
 */
object PhoneEngine {
    private fun module(ctx: Context) = run {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        Python.getInstance().getModule("ils.engine")
    }

    fun methods(ctx: Context): Map<String, NativeMethod> = mapOf(
        "health" to { _ -> """{"engine":"${WebConfig.ENGINE}","ready":true}""" },
        "process" to { p -> module(ctx).callAttr("run", "process", p, 1).toString() },
        "panels" to { p -> module(ctx).callAttr("run", "panels", p, 1).toString() },
        "upscale" to { p -> upscale(ctx, p) },
        "keepalive" to { p -> try { ExportKeepAliveService.set(ctx, p == "1"); """{"ok":true}""" } catch (e: Exception) { """{"ok":false}""" } },
    )

    private fun pngJson(png: ByteArray, method: String) =
        """{"png":"${Base64.encodeToString(png, Base64.NO_WRAP)}","method":"$method"}"""

    /**
     * Step 29: the Bezalel AI upscaler (corner mark removed, AI 2x, classic 2x if the AI step cannot run) for ONE picture: base64 in, `{"png":base64,"method":...}` out.
     * Cache (the same folder and keys as the Auto Editor, `cacheDir/upscale`): a picture whose AI result is already there is returned at once ("cached"), so a second export does not upscale again.
     * Only real AI results are cached. Cancel: the page calls `SukoBridge.cancelWork()`, which raises [WorkControl.cancelled]; the tile callback then stops the AI step
     * (ImageProcessor swallows exceptions from the AI step, so the stop is remembered here and answered as `{"error":"cancelled"}`).
     */
    private fun upscale(ctx: Context, base64: String): String {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val cache = UpscaleCache(File(ctx.cacheDir, "upscale"))
        val key = UpscaleKeys.key(UpscaleKeys.contentHash(ByteArrayInputStream(bytes)), UpscaleSettings(removeMark = true))
        if (cache.has(key)) {
            try {
                val f = cache.fileFor(key)
                val png = f.readBytes()
                f.setLastModified(System.currentTimeMillis())
                return pngJson(png, "cached")
            } catch (e: Exception) { /* unreadable cache file: do the work again */ }
        }
        WorkControl.cancelled = false
        val src = Upscaler.decode { ByteArrayInputStream(bytes) }
            ?: return """{"error":"unreadable","detail":"The picture could not be read for upscaling."}"""
        val stop = AtomicReference<Throwable?>(null)
        val res = Upscaler.run(ctx, src, removeMark = true) { _, _ ->
            if (WorkControl.cancelled) {
                val e = CancellationException("cancelled")
                stop.set(e)
                throw e
            }
        }
        if (WorkControl.cancelled || stop.get() != null) return """{"error":"cancelled","detail":"Stopped."}"""
        val bmp = Upscaler.toBitmap(res.img)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        val png = out.toByteArray()
        if (res.method == "AI") {
            try {
                cache.tempFor(key).writeBytes(png)
                cache.commit(key)
                cache.trimTo(2L * 1024 * 1024 * 1024, setOf(key))
            } catch (e: Exception) { cache.discard(key) }
        }
        return pngJson(png, res.method)
    }
}
