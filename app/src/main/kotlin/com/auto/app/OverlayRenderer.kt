package com.auto.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.auto.core.AnimFrame
import com.auto.core.AnimMath
import com.auto.core.OverlayStyle
import com.auto.core.TextAlign
import com.auto.core.TextWrap
import kotlin.math.max

/**
 * Draws ONE OverlayStyle text onto a Canvas of w x h pixels. Used for the burned-in captions and (next steps) text overlays and the preview, so all look the same.
 * Every size in the style is a fraction of the frame height (or of the font size), so the result is identical at any resolution.
 * Position rule: (x, y) is the CENTRE of the box for centred text; for LEFT-aligned text x is the box's left edge, for RIGHT-aligned text its right edge (so "Lower Third" at x = 0.08 starts 8 % from the left).
 * `f` is the animation state at this instant (alpha, scale, offset, typewriter letters). NOT yet run on a device.
 */
object OverlayRenderer {
    private const val MAX_LINES = 6

    private fun mulAlpha(argb: Int, a: Float): Int = ((((argb ushr 24) * a.coerceIn(0f, 1f)) + 0.5f).toInt() shl 24) or (argb and 0xFFFFFF)

    fun draw(ctx: Context, c: Canvas, w: Int, h: Int, text: String, textStyle: OverlayStyle, f: AnimFrame = AnimFrame.REST) {
        val st = textStyle.clamped()
        if (f.alpha <= 0f || text.isBlank()) return
        val size = st.sizeFrac * h
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = FontResolver.typeface(ctx, st); textSize = size; letterSpacing = st.letterSpacingEm
            isUnderlineText = st.underline; isStrikeThruText = st.strike
        }
        val full = st.display(text)
        val pad = st.paddingEm * size
        val maxW = max(size, st.widthFrac * w - 2 * pad)
        val lines = TextWrap.wrap(full, maxW, { fill.measureText(it) }, MAX_LINES)
        if (lines.isEmpty()) return
        val lineH = size * st.lineSpacing
        val blockW = lines.maxOf { fill.measureText(it) }
        val bw = blockW + 2 * pad; val bh = lineH * lines.size + 2 * pad
        val fm = fill.fontMetrics
        val firstBase = -bh / 2 + pad + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent

        // typewriter: show the first n letters of the wrapped text (the layout of the whole text stays fixed so nothing jumps)
        var left = AnimMath.visibleText(full, f).length
        val shown = lines.map { l -> val n = left.coerceIn(0, l.length); left -= l.length + 1; l.substring(0, n) }

        val a = f.alpha
        c.save()
        val anchorShift = when (st.align) { TextAlign.LEFT -> bw / 2; TextAlign.RIGHT -> -bw / 2; TextAlign.CENTER -> 0f }
        c.translate(st.x * w + f.dxFrac * w + anchorShift, st.y * h + f.dyFrac * h)
        if (st.rotationDeg != 0f) c.rotate(st.rotationDeg)
        if (f.scale != 1f) c.scale(f.scale, f.scale)

        if (st.hasBackground) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mulAlpha(st.bgArgb(), a) }
            val r = st.bgRadiusEm * size
            c.drawRoundRect(RectF(-bw / 2, -bh / 2, bw / 2, bh / 2), r, r, bg)
        }
        val textX = when (st.align) { TextAlign.LEFT -> -bw / 2 + pad; TextAlign.RIGHT -> bw / 2 - pad; TextAlign.CENTER -> 0f }
        fill.textAlign = when (st.align) { TextAlign.LEFT -> Paint.Align.LEFT; TextAlign.RIGHT -> Paint.Align.RIGHT; TextAlign.CENTER -> Paint.Align.CENTER }
        fill.color = mulAlpha(st.fillArgb(), a)
        st.gradientEndArgb()?.let { e -> fill.shader = LinearGradient(0f, -bh / 2 + pad, 0f, bh / 2 - pad, mulAlpha(st.fillArgb(), a), mulAlpha(e, a), Shader.TileMode.CLAMP) }
        val edge = if (st.hasStroke) Paint(fill).apply {
            shader = null; style = Paint.Style.STROKE; strokeWidth = st.strokeEm * size; strokeJoin = Paint.Join.ROUND
            color = mulAlpha(st.strokeColor, st.opacity * a)
        } else null
        if (st.hasShadow) {
            val shadowOn = edge ?: fill
            shadowOn.setShadowLayer(max(0.5f, st.shadowBlurEm * size), st.shadowDxEm * size, st.shadowDyEm * size, mulAlpha(st.shadowArgb(), a))
        }
        var y = firstBase
        for (l in shown) {
            if (l.isNotEmpty()) {
                if (edge != null) c.drawText(l, textX, y, edge)
                if (edge != null && st.hasShadow) edge.clearShadowLayer()   // the shadow is drawn once, under the outline
                c.drawText(l, textX, y, fill)
            }
            y += lineH
        }
        c.restore()
    }
}
