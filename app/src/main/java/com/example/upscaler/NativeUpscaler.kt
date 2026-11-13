package com.example.upscaler

import android.content.res.AssetManager

/** Thin Kotlin wrapper around the ncnn / Real-ESRGAN native library (libupscaler.so). */
object NativeUpscaler {
    // All native calls run on ONE permanent thread. The OpenMP / Vulkan runtimes inside ncnn keep
    // per-thread state and can crash when each batch calls them from a brand-new thread.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "upscaler-native", 16L * 1024 * 1024).apply { isDaemon = true }
    }

    private fun <T> onWorker(block: () -> T): T {
        try {
            return worker.submit(java.util.concurrent.Callable { block() }).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private var libOk: Boolean? = null
    private var loadedScale = 0
    private var loadedGpu = false

    @Synchronized
    fun available(): Boolean {
        if (libOk == null) {
            libOk = try {
                System.loadLibrary("upscaler")
                true
            } catch (t: Throwable) {
                false
            }
        }
        return libOk == true
    }

    /** Loads the x2 / x3 / x4 model (once per scale). Returns false if anything about it failed. */
    @Synchronized
    fun ensureLoaded(am: AssetManager, scale: Int, useGpu: Boolean): Boolean {
        if (!available()) return false
        if (loadedScale == scale && loadedGpu == useGpu) return true
        val ok = try {
            onWorker { nativeLoad(am, scale, useGpu) }
        } catch (t: Throwable) {
            false
        }
        loadedScale = if (ok) scale else 0
        loadedGpu = useGpu
        return ok
    }

    fun run(px: IntArray, w: Int, h: Int): IntArray? = onWorker { nativeRun(px, w, h) }

    fun usingGpu(): Boolean = available() && onWorker { nativeUsingGpu() }

    /** "GPU" or "CPU n threads" (empty if the library is not available). */
    fun info(): String = if (available()) onWorker { nativeInfo() } else ""

    private external fun nativeLoad(am: AssetManager, scale: Int, useGpu: Boolean): Boolean
    private external fun nativeRun(px: IntArray, w: Int, h: Int): IntArray?
    private external fun nativeUsingGpu(): Boolean
    private external fun nativeInfo(): String
}
