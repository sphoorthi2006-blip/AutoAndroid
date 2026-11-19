package com.suko.app.web

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.suko.app.upscale.Upscaler
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

    /** Step 29: the Bezalel AI upscaler (corner mark removed, AI 2x, classic 2x if the AI step cannot run) for ONE picture: base64 in, `{"png":base64,"method":...}` out. */
    private fun upscale(ctx: Context, base64: String): String {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val src = Upscaler.decode { ByteArrayInputStream(bytes) }
            ?: return """{"error":"unreadable","detail":"The picture could not be read for upscaling."}"""
        val res = Upscaler.run(ctx, src, removeMark = true)
        val bmp = Upscaler.toBitmap(res.img)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return """{"png":"${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}","method":"${res.method}"}"""
    }
}
