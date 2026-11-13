package com.example.upscaler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Before / After comparison with a draggable slider.
 * - Left of the handle: the original (BEFORE). Right of the handle: the result (AFTER).
 * - Drag the handle (or drag anywhere while not zoomed) to move the slider.
 * - Pinch or double-tap to zoom in and check the detail; drag to pan when zoomed.
 * Both pictures are drawn into the same rectangle, so the original is stretched to the result's size,
 * which is exactly the "same size on screen" comparison you want for judging quality.
 */
class CompareView(context: Context) : View(context) {

    private var before: Bitmap? = null
    private var after: Bitmap? = null

    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * density }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; strokeWidth = 5f * density }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF333333.toInt() }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 13f * density; isFakeBoldText = true
    }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); textSize = 16f * density; textAlign = Paint.Align.CENTER
    }

    private var divider = 0.5f          // slider position, fraction of the view width
    private var fit = 1f                // scale that fits the whole picture in the view
    private var zoom = 1f               // extra zoom on top of fit (1 = whole picture visible)
    private var tx = 0f
    private var ty = 0f
    private var message = "Loading ..."

    private var draggingSlider = false
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) resetView() else zoomAt(3f, e.x, e.y)
            return true
        }
    })

    init {
        setBackgroundColor(0xFF111111.toInt())
    }

    fun setImages(b: Bitmap?, a: Bitmap?, msg: String = "Loading ...") {
        val oldB = before
        val oldA = after
        before = b
        after = a
        message = msg
        divider = 0.5f
        resetView()
        if (oldB != null && oldB !== b) oldB.recycle()
        if (oldA != null && oldA !== a) oldA.recycle()
    }

    private fun resetView() {
        zoom = 1f
        val a = after
        if (a != null && width > 0 && height > 0) {
            fit = min(width.toFloat() / a.width, height.toFloat() / a.height)
            tx = (width - a.width * fit) / 2f
            ty = (height - a.height * fit) / 2f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetView()
    }

    private fun zoomAt(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(1f, 12f)
        val k = z / zoom
        tx = fx - (fx - tx) * k
        ty = fy - (fy - ty) * k
        zoom = z
        clamp()
        invalidate()
    }

    private fun clamp() {
        val a = after ?: return
        val sw = a.width * fit * zoom
        val sh = a.height * fit * zoom
        tx = if (sw <= width) (width - sw) / 2f else tx.coerceIn(width - sw, 0f)
        ty = if (sh <= height) (height - sh) / 2f else ty.coerceIn(height - sh, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = after
        val b = before
        if (a == null || b == null) {
            canvas.drawText(message, width / 2f, height / 2f, msgPaint)
            return
        }
        val s = fit * zoom
        val dst = RectF(0f, 0f, a.width.toFloat(), a.height.toFloat())
        val dx = width * divider

        // AFTER everywhere ...
        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        canvas.drawBitmap(a, null, dst, bitmapPaint)
        canvas.restore()

        // ... BEFORE on top, only left of the slider
        canvas.save()
        canvas.clipRect(0f, 0f, dx, height.toFloat())
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        canvas.drawBitmap(b, null, dst, bitmapPaint)
        canvas.restore()

        // slider line + handle
        canvas.drawLine(dx, 0f, dx, height.toFloat(), shadowPaint)
        canvas.drawLine(dx, 0f, dx, height.toFloat(), linePaint)
        val cy = height / 2f
        val r = 22f * density
        canvas.drawCircle(dx, cy, r + 1.5f * density, shadowPaint)
        canvas.drawCircle(dx, cy, r, knobPaint)
        val t = 7f * density
        val left = Path().apply {
            moveTo(dx - 4f * density, cy - t); lineTo(dx - 4f * density, cy + t); lineTo(dx - 4f * density - t, cy); close()
        }
        val right = Path().apply {
            moveTo(dx + 4f * density, cy - t); lineTo(dx + 4f * density, cy + t); lineTo(dx + 4f * density + t, cy); close()
        }
        canvas.drawPath(left, arrowPaint)
        canvas.drawPath(right, arrowPaint)

        // labels
        drawChip(canvas, "BEFORE", 10f * density, 10f * density, false, dx)
        drawChip(canvas, "AFTER", width - 10f * density, 10f * density, true, dx)
    }

    private fun drawChip(canvas: Canvas, text: String, x: Float, y: Float, alignRight: Boolean, dx: Float) {
        val padH = 8f * density
        val w = chipText.measureText(text) + padH * 2
        val h = 24f * density
        val left = if (alignRight) x - w else x
        // hide a label when the slider is on top of it
        if (!alignRight && dx < left + w) return
        if (alignRight && dx > left) return
        canvas.drawRoundRect(RectF(left, y, left + w, y + h), h / 2, h / 2, chipPaint)
        canvas.drawText(text, left + padH, y + h / 2 + chipText.textSize * 0.35f, chipText)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (after == null || before == null) return true
        scaleDetector.onTouchEvent(e)
        tapDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                draggingSlider = zoom <= 1.05f || abs(e.x - width * divider) < 44f * density
                lastX = e.x; lastY = e.y
                if (draggingSlider) moveSlider(e.x)
            }
            MotionEvent.ACTION_POINTER_DOWN -> draggingSlider = false
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    if (draggingSlider) {
                        moveSlider(e.x)
                    } else {
                        tx += e.x - lastX
                        ty += e.y - lastY
                        clamp()
                        invalidate()
                    }
                    lastX = e.x; lastY = e.y
                }
            }
        }
        return true
    }

    private fun moveSlider(x: Float) {
        divider = (x / width).coerceIn(0.01f, 0.99f)
        invalidate()
    }
}
