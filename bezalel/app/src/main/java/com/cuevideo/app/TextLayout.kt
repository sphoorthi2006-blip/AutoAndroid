package com.cuevideo.app

import kotlin.math.max
import kotlin.math.min

/** One line of a placed text. [x] is the anchor: the middle of the line for CENTER, its left end for LEFT. [cy] is the vertical middle. */
data class BlockLine(val text: String, val x: Double, val cy: Double)

data class TextBlockLayout(val size: Int, val align: TextAlign, val lines: List<BlockLine>)

/**
 * Pure layout of a text placement (no Android code). Size 1.0 = the largest size at which the
 * whole text fits the screen (86 % wide, 80 % high); other values scale that size. Line breaks
 * typed by the user are kept; long lines are wrapped by words. x / y move the block by a share
 * of the screen width / height.
 */
object TextLayout {
    const val MAX_SIZE = 200          // at 1080p, same top size as headings
    const val MIN_SIZE = 24
    const val LINE_GAP = 1.25
    const val FIT_HEIGHT = 0.80
    const val SIZE_MIN = 0.3
    const val SIZE_MAX = 3.0
    const val SHIFT_LIMIT = 0.5

    fun wrap(text: String, size: Int, maxW: Double, measure: (String, Int) -> Double): List<String> {
        val out = ArrayList<String>()
        for (para in text.trim().split(Regex("\\r?\\n"))) {
            val words = para.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) { out.add(""); continue }          // a blank line stays a blank line
            var cur = ""
            for (w in words) {
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (cur.isEmpty() || measure(t, size) <= maxW) cur = t
                else { out.add(cur); cur = w }
            }
            out.add(cur)
        }
        return out
    }

    /** Largest size (in steps of 2) at which the text fits; MIN_SIZE if none does. */
    fun autoSize(text: String, W: Int, H: Int, measure: (String, Int) -> Double): Int {
        val maxW = SlideMath.headingMaxWidth(W)
        var sz = MAX_SIZE
        while (sz > MIN_SIZE) {
            val n = wrap(text, sz, maxW, measure).size
            if (n * sz * LINE_GAP <= H * FIT_HEIGHT) return sz
            sz -= 2
        }
        return MIN_SIZE
    }

    fun layout(
        text: String, W: Int, H: Int, align: TextAlign, scale: Double, x: Double, y: Double,
        measure: (String, Int) -> Double,
    ): TextBlockLayout {
        if (text.isBlank()) return TextBlockLayout(0, align, emptyList())
        val auto = autoSize(text, W, H, measure)
        val sz = max(8, Math.rint(auto * scale.coerceIn(SIZE_MIN, SIZE_MAX)).toInt())
        val maxW = SlideMath.headingMaxWidth(W)
        val rows = wrap(text, sz, maxW, measure)
        val lineH = sz * LINE_GAP
        val dx = x.coerceIn(-SHIFT_LIMIT, SHIFT_LIMIT) * W
        val dy = y.coerceIn(-SHIFT_LIMIT, SHIFT_LIMIT) * H
        val anchorX = if (align == TextAlign.CENTER) W / 2.0 + dx else (W - maxW) / 2.0 + dx
        var cy = H / 2.0 + dy - lineH * rows.size / 2.0 + lineH / 2.0
        val lines = ArrayList<BlockLine>()
        for (r in rows) { lines.add(BlockLine(r, anchorX, cy)); cy += lineH }
        return TextBlockLayout(sz, align, lines)
    }
}
