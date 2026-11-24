package com.cuevideo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri

/**
 * Draws the pictures of sections 9, 10 and 12 as 1920x1080 bitmaps.
 * Stage 5 will feed these to the video encoder; for now they are shown in the preview.
 */
class SlideRenderer(private val ctx: Context) {
    val W = Defaults.W
    val H = Defaults.H

    private val typeface: Typeface = Typeface.createFromAsset(ctx.assets, "PatrickHand-Regular.ttf")
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.BLACK
        typeface = this@SlideRenderer.typeface
        textAlign = Paint.Align.CENTER
    }
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun blackFrame(): Bitmap {
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(Color.BLACK)
        return b
    }

    // ---------------------------------------------------------------- pictures

    private fun exifMatrix(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }

    /** EXIF rotation, RGB, cover-crop to exactly 1920x1080. One image at a time, down-sampled on decode. */
    fun loadPicture(uri: String): Bitmap? {
        val u = Uri.parse(uri)
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var orientation = ExifInterface.ORIENTATION_NORMAL
        try {
            resolver.openInputStream(u)?.use {
                orientation = ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            // no EXIF: keep as is
        }
        val swap = orientation in 5..8
        val ew = if (swap) bounds.outHeight else bounds.outWidth    // size after rotation
        val eh = if (swap) bounds.outWidth else bounds.outHeight

        // largest power-of-two sample that still leaves the picture at least as big as the cover size
        val scale = maxOf(W.toDouble() / ew, H.toDouble() / eh)
        var sample = 1
        while (scale * sample * 2 <= 1.0) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = resolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val m = exifMatrix(orientation)
        if (m != null) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        val cc = SlideMath.coverCrop(ew, eh, W, H)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(
            bmp, null,
            RectF(-cc.left.toFloat(), -cc.top.toFloat(), (cc.newW - cc.left).toFloat(), (cc.newH - cc.top).toFloat()),
            drawPaint,
        )
        bmp.recycle()
        return out
    }

    // ---------------------------------------------------------------- headings

    private fun measure(text: String, size: Int): Double {
        textPaint.textSize = size.toFloat()
        return textPaint.measureText(text).toDouble()
    }

    /** The finished text: black on a transparent 1920x1080 bitmap, laid out like the desktop engine. */
    fun headingInk(text: String): Bitmap {
        val lay = SlideMath.layout(
            text, W, H, Defaults.LAYER_SCALE,
            Defaults.HEADING_SIZE.toDouble(),
            SlideMath.headingMaxWidth(W),
            Defaults.HEADING_MIN.toDouble(),
            ::measure,
        )
        val bmp = Bitmap.createBitmap(lay.layerW, lay.layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (lay.size > 0) {
            textPaint.textSize = lay.size.toFloat()
            val fm = textPaint.fontMetrics
            // desktop anchor "mm": the vertical middle is halfway between ascent and descent
            val shift = (fm.ascent + fm.descent) / 2f
            for (ln in lay.lines) c.drawText(ln.text, ln.cx.toFloat(), ln.cy.toFloat() - shift, textPaint)
        }
        return bmp
    }

    /**
     * The finished placed text on a transparent 1920x1080 bitmap: white ink for the black look
     * ([dark] = true), black ink for the white look. Layout comes from [TextLayout].
     */
    fun textInk(tp: TextPlacement): Bitmap {
        val lay = TextLayout.layout(tp.text, W, H, tp.align, tp.size, tp.x, tp.y, ::measure)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (lay.size > 0) {
            val paint = Paint(textPaint).apply {
                color = if (tp.dark) Color.WHITE else Color.BLACK
                textSize = lay.size.toFloat()
                textAlign = if (lay.align == TextAlign.CENTER) Paint.Align.CENTER else Paint.Align.LEFT
            }
            val fm = paint.fontMetrics
            val shift = (fm.ascent + fm.descent) / 2f
            for (ln in lay.lines) c.drawText(ln.text, ln.x.toFloat(), ln.cy.toFloat() - shift, paint)
        }
        return bmp
    }

    /** 1 = ink, 0 = background, for the pen engine. */
    fun inkBits(ink: Bitmap): ByteArray {
        val w = ink.width
        val h = ink.height
        val px = IntArray(w * h)
        ink.getPixels(px, 0, w, 0, 0, w, h)
        return ByteArray(w * h) { if ((px[it] ushr 24) > 127) 1 else 0 }
    }
}

/**
 * Draws one frame of handwriting: [background] screen (white by default), the text uncovered under the pen lines of [WriteFrame].
 * Reuses two bitmaps, so the returned bitmap is only valid until the next call (upload or copy it at once).
 */
class HeadingPainter(
    private val w: Int = Defaults.W,
    private val h: Int = Defaults.H,
    private val background: Int = Color.WHITE,
) {
    private val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val maskCanvas = Canvas(mask)
    private val outCanvas = Canvas(out)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val keepInk = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(ink: Bitmap, fr: WriteFrame): Bitmap {
        out.eraseColor(background)
        if (fr.finish >= 1.0) { outCanvas.drawBitmap(ink, 0f, 0f, null); return out }
        mask.eraseColor(Color.TRANSPARENT)
        for ((pts, pen) in fr.lines) {
            if (pts.isEmpty()) continue
            if (pts.size == 1) { maskCanvas.drawCircle(pts[0].x.toFloat(), pts[0].y.toFloat(), (pen / 2).toFloat(), dotPaint); continue }
            linePaint.strokeWidth = pen.toFloat()
            val path = Path()
            path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (k in 1 until pts.size) path.lineTo(pts[k].x.toFloat(), pts[k].y.toFloat())
            maskCanvas.drawPath(path, linePaint)
        }
        maskCanvas.drawBitmap(ink, 0f, 0f, keepInk)          // ink only where the pen has been
        if (fr.finish > 0.0) {                                // the last fade shows what the pen missed
            fadePaint.alpha = (fr.finish * 255).toInt().coerceIn(0, 255)
            maskCanvas.drawBitmap(ink, 0f, 0f, fadePaint)
        }
        outCanvas.drawBitmap(mask, 0f, 0f, null)
        return out
    }

    fun recycle() { mask.recycle(); out.recycle() }
}
