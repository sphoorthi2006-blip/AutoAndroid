package com.suko.app.web

import android.content.Context

/**
 * The native methods the web page can call through the bridge (see web/src/bridge.ts).
 * Only two are left: "health" (is the app there) and "keepalive" (a long export keeps running with the screen off).
 * The picture analyser (Python/OpenCV) and the AI upscaler were removed in steps 8-9 of the 15-step plan.
 */
object PhoneEngine {
    fun methods(ctx: Context): Map<String, NativeMethod> = mapOf(
        "health" to { _ -> """{"engine":"${WebConfig.ENGINE}","ready":true}""" },
        "keepalive" to { p -> try { ExportKeepAliveService.set(ctx, p == "1"); """{"ok":true}""" } catch (e: Exception) { """{"ok":false}""" } },
    )
}
