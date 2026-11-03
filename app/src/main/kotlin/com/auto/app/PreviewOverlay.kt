package com.auto.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.auto.core.AnimMath
import com.auto.core.Guides
import com.auto.core.OverlayStyle
import com.auto.core.TextClip
import com.auto.core.captionLineAt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private class Box4(val cx: Float, val cy: Float, val hw: Float, val hh: Float)
private fun boxOf(s: OverlayStyle): Box4 {
    val hw = s.widthFrac / 2f
    val cx = when (s.align) { com.auto.core.TextAlign.LEFT -> s.x + hw; com.auto.core.TextAlign.RIGHT -> s.x - hw; else -> s.x }
    return Box4(cx, s.y, hw, max(0.05f, s.sizeFrac * 1.1f))
}

/**
 * Step 29 & Phase 7: Captions and visible text clips drawn over the picture/solid screen in zIndex order
 * using the export's own OverlayRenderer and AnimMath.
 * Honors `sel.locked` (locked text layers cannot be dragged or rotated) and `st.snappingEnabled`.
 */
@Composable
fun PreviewOverlays(st: AutoState, showCaption: Boolean) {
    val ctx = LocalContext.current
    val tl = st.timeline ?: return
    var live by remember { mutableStateOf<Pair<Int, OverlayStyle>?>(null) }
    var guideX by remember { mutableStateOf<Float?>(null) }
    var guideY by remember { mutableStateOf<Float?>(null) }
    val sel = st.selectedText()
    val selNow by rememberUpdatedState(sel)
    val selVisible = sel != null && sel.visible && !sel.locked && st.playMs >= sel.startMs && st.playMs < sel.endMs

    Box(Modifier.fillMaxSize().pointerInput(sel?.id, selVisible, st.snappingEnabled) {
        if (!selVisible) return@pointerInput
        var mode = 0; var ax = 0f; var ay = 0f
        var base: OverlayStyle = OverlayStyle(); var startPos = Offset.Zero; var d0 = 1f; var a0 = 0f
        detectDragGestures(
            onDragStart = { pos ->
                val s = selNow ?: return@detectDragGestures
                if (s.locked || !s.visible) return@detectDragGestures
                base = s.style; ax = 0f; ay = 0f; startPos = pos
                val w = size.width.toFloat(); val h = size.height.toFloat(); val b = boxOf(base)
                val r = Math.toRadians(base.rotationDeg.toDouble()); val c = cos(r).toFloat(); val sn = sin(r).toFloat()
                val dx = b.hw * w; val dy = b.hh * h
                val hx = b.cx * w + dx * c - dy * sn; val hy = b.cy * h + dx * sn + dy * c
                val cxp = b.cx * w; val cyp = b.cy * h
                mode = when {
                    hypot(pos.x - hx, pos.y - hy) < 56f -> 2
                    run { val lx = (pos.x - cxp) * c + (pos.y - cyp) * sn; val ly = -(pos.x - cxp) * sn + (pos.y - cyp) * c; abs(lx) <= dx + 24f && abs(ly) <= dy + 24f } -> 1
                    else -> 0
                }
                d0 = max(1f, hypot(pos.x - cxp, pos.y - cyp)); a0 = atan2(pos.y - cyp, pos.x - cxp)
            },
            onDrag = { change, amt ->
                change.consume()
                val s = selNow ?: return@detectDragGestures
                val w = size.width.toFloat(); val h = size.height.toFloat()
                if (mode == 1) {
                    ax += amt.x; ay += amt.y
                    val rawX = (base.x + ax / w).coerceIn(0f, 1f)
                    val rawY = (base.y + ay / h).coerceIn(0f, 1f)
                    val sx = if (st.snappingEnabled) Guides.snap(rawX) else Guides.Snap(rawX, null)
                    val sy = if (st.snappingEnabled) Guides.snap(rawY) else Guides.Snap(rawY, null)
                    val (kx, ky) = Guides.keepInside(sx.value, sy.value, 0.06f, base.sizeFrac * 0.6f)
                    live = s.id to base.copy(x = kx, y = ky)
                    guideX = sx.guide?.takeIf { it == kx }
                    guideY = sy.guide?.takeIf { it == ky }
                } else if (mode == 2) {
                    ax += amt.x; ay += amt.y
                    val b = boxOf(base); val cxp = b.cx * w; val cyp = b.cy * h; val p = startPos + Offset(ax, ay)
                    val d = hypot(p.x - cxp, p.y - cyp); val a = atan2(p.y - cyp, p.x - cxp)
                    var rot = base.rotationDeg + Math.toDegrees((a - a0).toDouble()).toFloat()
                    if (st.snappingEnabled && abs(rot) < 3f) rot = 0f
                    live = s.id to base.copy(sizeFrac = (base.sizeFrac * d / d0).coerceIn(0.015f, 0.2f), rotationDeg = rot).clamped()
                }
            },
            onDragEnd = { val l = live; live = null; guideX = null; guideY = null; if (l != null && mode != 0) st.editText(l.first, style = l.second.clamped()); mode = 0 },
            onDragCancel = { live = null; guideX = null; guideY = null; mode = 0 }
        )
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width.toInt(); val h = size.height.toInt()
            val t = st.playMs
            drawIntoCanvas { cv ->
                val n = cv.nativeCanvas
                if (showCaption) tl.captionLineAt(t)?.let { OverlayRenderer.draw(ctx, n, w, h, it, tl.captionStyle) }
                // Timeline.textsAt(t) filters by visible == true and sorts by zIndex then startMs
                for (c in tl.textsAt(t)) drawText(ctx, n, w, h, c, live?.takeIf { it.first == c.id }?.second, t)
            }
            if (selVisible && sel != null) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                val m = Guides.SAFE_ACTION
                drawRect(Color.White.copy(alpha = 0.3f), Offset(w * m, h * m), Size(w * (1 - 2 * m), h * (1 - 2 * m)), style = Stroke(2f, pathEffect = dash))
                guideX?.let { drawLine(C.Cyan, Offset(it * w, 0f), Offset(it * w, h.toFloat()), 2f, pathEffect = dash) }
                guideY?.let { drawLine(C.Cyan, Offset(0f, it * h), Offset(w.toFloat(), it * h), 2f, pathEffect = dash) }
                val s = (live?.second ?: sel.style); val b = boxOf(s)
                rotate(s.rotationDeg, Offset(b.cx * w, b.cy * h)) {
                    drawRect(C.VioletLight, Offset((b.cx - b.hw) * w, (b.cy - b.hh) * h), Size(b.hw * 2 * w, b.hh * 2 * h), style = Stroke(3f, pathEffect = dash))
                    drawCircle(C.Violet, 18f, Offset((b.cx + b.hw) * w, (b.cy + b.hh) * h))
                    drawCircle(Color.White, 8f, Offset((b.cx + b.hw) * w, (b.cy + b.hh) * h))
                }
            }
        }
    }
}

private fun drawText(ctx: android.content.Context, n: android.graphics.Canvas, w: Int, h: Int, c: TextClip, override: OverlayStyle?, t: Long) {
    val s = (override ?: c.style).clamped()
    val f = AnimMath.frame(s, t - c.startMs, c.durMs)
    if (f.alpha > 0f) OverlayRenderer.draw(ctx, n, w, h, c.text, s, f)
}
