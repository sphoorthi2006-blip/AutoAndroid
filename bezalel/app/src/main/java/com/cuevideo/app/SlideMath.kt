package com.cuevideo.app

import kotlin.math.max
import kotlin.math.min

/** Fixed defaults from section 2 of the spec. */
object Defaults {
    const val W = 1920
    const val H = 1080
    const val HEADING_SIZE = 200      // at 1080p
    const val HEADING_MIN = 80        // at 1080p
    const val LAYER_SCALE = 1.0       // text layer is drawn at the video size
}

data class CoverCrop(val newW: Int, val newH: Int, val left: Int, val top: Int)
data class TextLine(val text: String, val cx: Double, val cy: Double)   // cy = vertical middle
data class HeadingLayout(val layerW: Int, val layerH: Int, val size: Int, val lines: List<TextLine>)

/** Pure maths of sections 10 and 12. No Android code here, so it can be tested against the desktop. */
object SlideMath {
    /** Section 12: scale to cover, then crop the centre. Python round() = half-to-even. */
    fun coverCrop(w: Int, h: Int, W: Int, H: Int): CoverCrop {
        val scale = max(W.toDouble() / w, H.toDouble() / h)
        val nw = max(W, Math.rint(w * scale).toInt())
        val nh = max(H, Math.rint(h * scale).toInt())
        return CoverCrop(nw, nh, (nw - W) / 2, (nh - H) / 2)
    }

    /** Width available for a heading: 86% of the picture width. */
    fun headingMaxWidth(W: Int): Double = W * 0.86

    /**
     * Desktop draw_text_layer() + fit_font(). [measure] gives the width in pixels of a string
     * at an integer font size. Sizes below are in screen pixels; the layer is [scale] times bigger.
     */
    fun layout(
        text: String, W: Int, H: Int, scale: Double, size: Double,
        maxW: Double, minSize: Double, measure: (String, Int) -> Double,
    ): HeadingLayout {
        val lw = (W * scale).toInt()
        val lh0 = (H * scale).toInt()
        if (text.isEmpty()) return HeadingLayout(lw, lh0, 0, emptyList())
        val maxWs = maxW * scale
        val minS = minSize * scale

        // fit_font: largest size that fits on ONE line
        var sz = max(1, (size * scale).toInt())
        var one = false
        while (true) {
            val w = measure(text, sz)
            if (w <= maxWs) { one = true; break }
            if (sz <= minS) { one = false; break }
            sz = max(minS.toInt(), min(sz - 1, (sz * maxWs / w).toInt()))
        }
        if (one) return HeadingLayout(lw, lh0, sz, listOf(TextLine(text, lw / 2.0, lh0 / 2.0)))

        // wrap: add words while the line fits (a line always gets at least one word)
        val lines = ArrayList<String>()
        var cur = ""
        for (w in text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val t = (cur + " " + w).trim()
            if (measure(t, sz) <= maxWs || cur.isEmpty()) cur = t
            else { lines.add(cur); cur = w }
        }
        lines.add(cur)
        val lineH = (sz * 1.25).toInt()
        var y = (lh0 - lineH * lines.size) / 2.0 + lineH / 2.0
        val out = ArrayList<TextLine>()
        for (ln in lines) { out.add(TextLine(ln, lw / 2.0, y)); y += lineH }
        return HeadingLayout(lw, lh0, sz, out)
    }

    private fun smooth(x: Double): Double {
        val c = min(1.0, max(0.0, x))
        return c * c * (3 - 2 * c)
    }
}
