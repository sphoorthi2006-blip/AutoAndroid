package com.cuevideo.app

import kotlin.math.min

/** Pure maths for the GL drawing, kept free of Android classes so it can be tested. */
object GlMath {
    /**
     * 4x4 column-major matrix for texture coordinates of a normal (bitmap) texture.
     * Input uv: (0,0) = bottom-left of the screen, (1,1) = top-right.
     * Crop box is in 0..1 texture units with the origin at the TOP-left (row 0 = top of the bitmap).
     * u' = cl + u*(cr-cl),  v' = cb - v*(cb-ct)
     */
    fun crop(cl: Double, ct: Double, cr: Double, cb: Double): FloatArray {
        val a = (cr - cl).toFloat()
        val b = (cb - ct).toFloat()
        return floatArrayOf(
            a, 0f, 0f, 0f,
            0f, -b, 0f, 0f,
            0f, 0f, 1f, 0f,
            cl.toFloat(), cb.toFloat(), 0f, 1f,
        )
    }

    val FULL: FloatArray get() = crop(0.0, 0.0, 1.0, 1.0)

    /** Section 11: fit inside W x H keeping the aspect ratio, black bars, never stretch. Returns (sx, sy) in 0..1 of the screen. */
    fun letterbox(vw: Int, vh: Int, W: Int, H: Int): Pair<Float, Float> {
        val scale = min(W.toDouble() / vw, H.toDouble() / vh)
        return Pair((vw * scale / W).toFloat(), (vh * scale / H).toFloat())
    }
}
