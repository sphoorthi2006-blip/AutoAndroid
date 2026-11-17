package com.cuevideo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
        color = Color.WHITE
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

    /** White text on black at 2x resolution (3840x2160), laid out like the desktop engine. */
    fun headingLayer(text: String): Bitmap {
        val lay = SlideMath.layout(
            text, W, H, Defaults.LAYER_SCALE,
            Defaults.HEADING_SIZE.toDouble(),
            SlideMath.animMaxWidth(W, Defaults.ZOOM),
            Defaults.HEADING_MIN.toDouble(),
            ::measure,
        )
        val bmp = Bitmap.createBitmap(lay.layerW, lay.layerH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        if (lay.size > 0) {
            textPaint.textSize = lay.size.toFloat()
            val fm = textPaint.fontMetrics
            // desktop anchor "mm": the vertical middle is halfway between ascent and descent
            val shift = (fm.ascent + fm.descent) / 2f
            for (ln in lay.lines) c.drawText(ln.text, ln.cx.toFloat(), ln.cy.toFloat() - shift, textPaint)
        }
        return bmp
    }

    /** Frame f (0-based) of a heading cue that lasts n frames: zoom crop + fade-in from black. */
    fun headingFrame(layer: Bitmap, f: Int, n: Int, fps: Int): Bitmap {
        val z = SlideMath.zoomFactor(f, n)
        val q = SlideMath.fadeBrightness(f, SlideMath.fadeFrames(fps))
        val box = SlideMath.cropBox(layer.width, layer.height, z)
        val m = Matrix()
        m.setRectToRect(
            RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
            RectF(0f, 0f, W.toFloat(), H.toFloat()),
            Matrix.ScaleToFit.FILL,
        )
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val p = Paint(drawPaint)
        if (q < 1.0) {
            val cm = ColorMatrix()
            cm.setScale(q.toFloat(), q.toFloat(), q.toFloat(), 1f)
            p.colorFilter = ColorMatrixColorFilter(cm)
        }
        c.drawBitmap(layer, m, p)
        return out
    }
}
