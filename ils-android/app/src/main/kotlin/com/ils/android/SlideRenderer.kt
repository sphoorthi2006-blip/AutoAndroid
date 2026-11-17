package com.ils.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import ils.core.Detected
import ils.core.ElementSettings
import ils.core.SlidePlan

/**
 * Draws one slide at any moment.
 * Base layer = the image with every planned element erased (filled with the background colour).
 * Pictures pop in (fade + small grow). Labels are written by the pen: the pen strokes form a mask and only the label's
 * own ink under the mask is shown, so the lettering keeps its look. At the end a short fade covers what the pen missed.
 */
class SlideRenderer(
    private val w: Int, private val h: Int,
    pixels: IntArray, private val plan: SlidePlan, bg: Int,
    private val st: ElementSettings = ElementSettings(),
) {
    private class Sprite(val d: Detected, val body: Bitmap?, val label: Bitmap?)

    private val base: Bitmap
    private val sprites: List<Sprite>
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val keepInk = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }

    init {
        val work = pixels.copyOf()
        val fill = bg or (0xFF shl 24)
        for (p in plan.placed) {
            erase(work, p.d, p.split.body, fill)
            erase(work, p.d, p.split.label, fill)
        }
        base = Bitmap.createBitmap(work, w, h, Bitmap.Config.ARGB_8888)
        sprites = plan.placed.map { Sprite(it.d, sprite(pixels, it.d, it.split.body), sprite(pixels, it.d, it.split.label)) }
    }

    private fun erase(dst: IntArray, d: Detected, mask: ByteArray?, fill: Int) {
        if (mask == null) return
        for (y in 0 until d.h) for (x in 0 until d.w) if (mask[y * d.w + x].toInt() != 0) dst[(d.y0 + y) * w + d.x0 + x] = fill
    }

    private fun sprite(src: IntArray, d: Detected, mask: ByteArray?): Bitmap? {
        if (mask == null) return null
        val a = IntArray(d.w * d.h)
        for (y in 0 until d.h) for (x in 0 until d.w) {
            if (mask[y * d.w + x].toInt() != 0) a[y * d.w + x] = src[(d.y0 + y) * w + d.x0 + x] or (0xFF shl 24)
        }
        return Bitmap.createBitmap(a, d.w, d.h, Bitmap.Config.ARGB_8888)
    }

    private fun ease(x: Double) = x * x * (3 - 2 * x)

    /** Draws the slide at absolute time t (seconds) onto the canvas, which must be w x h. */
    fun draw(canvas: Canvas, t: Double) {
        canvas.drawBitmap(base, 0f, 0f, null)
        for ((i, pl) in plan.placed.withIndex()) {
            val sp = sprites[i]
            val x0 = sp.d.x0.toFloat()
            val y0 = sp.d.y0.toFloat()

            val bp = pl.bodyProgress(t)
            if (sp.body != null && bp > 0.0) {
                val e = ease(bp)
                val scale = (st.popScaleFrom + (1.0 - st.popScaleFrom) * e).toFloat()
                paint.alpha = (255 * e).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.scale(scale, scale, x0 + sp.d.w / 2f, y0 + sp.d.h / 2f)
                canvas.drawBitmap(sp.body, x0, y0, paint)
                canvas.restore()
                paint.alpha = 255
            }

            val lp = pl.labelProgress(t)
            val label = sp.label
            if (label != null && lp > 0.0) {
                val wp = pl.writePlan
                if (lp >= 1.0) {
                    canvas.drawBitmap(label, x0, y0, null)
                } else if (wp == null) {
                    paint.alpha = (255 * ease(lp)).toInt().coerceIn(0, 255)
                    canvas.drawBitmap(label, x0, y0, paint)
                    paint.alpha = 255
                } else {
                    val fr = wp.frame(lp)
                    val layer = canvas.saveLayer(x0, y0, x0 + sp.d.w, y0 + sp.d.h, null)
                    for ((pts, pen) in fr.lines) {
                        if (pts.isEmpty()) continue
                        penPaint.strokeWidth = (pen * 1.2 + 1.0).toFloat()
                        val path = Path()
                        path.moveTo((x0 + pts[0].x).toFloat(), (y0 + pts[0].y).toFloat())
                        if (pts.size == 1) path.lineTo((x0 + pts[0].x).toFloat() + 0.01f, (y0 + pts[0].y).toFloat())
                        for (k in 1 until pts.size) path.lineTo((x0 + pts[k].x).toFloat(), (y0 + pts[k].y).toFloat())
                        canvas.drawPath(path, penPaint)
                    }
                    canvas.drawBitmap(label, x0, y0, keepInk)
                    canvas.restoreToCount(layer)
                    if (fr.finish > 0.0) {
                        paint.alpha = (255 * fr.finish).toInt().coerceIn(0, 255)
                        canvas.drawBitmap(label, x0, y0, paint)
                        paint.alpha = 255
                    }
                }
            }
        }
    }

    fun recycle() {
        base.recycle()
        for (s in sprites) { s.body?.recycle(); s.label?.recycle() }
    }
}
