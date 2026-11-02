package com.auto.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Simple vector icons drawn on a Compose Canvas (24 x 24 design grid). No dependency. Unknown names draw nothing. */
@Composable
fun AutoIcon(name: String, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) { drawIcon(name, color) }
}

private fun DrawScope.drawIcon(name: String, c: Color) {
    val k = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * k, y * k)
    val sw = Stroke(width = 2f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, o(x1, y1), o(x2, y2), strokeWidth = 2f * k, cap = StrokeCap.Round)
    fun poly(vararg p: Float, close: Boolean = false, fill: Boolean = false) {
        val path = Path(); path.moveTo(p[0] * k, p[1] * k)
        var i = 2; while (i < p.size) { path.lineTo(p[i] * k, p[i + 1] * k); i += 2 }
        if (close) path.close()
        drawPath(path, c, style = if (fill) Fill else sw)
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f, fill: Boolean = false) =
        drawRoundRect(c, o(x, y), Size(w * k, h * k), CornerRadius(r * k), style = if (fill) Fill else sw)
    fun ring(x: Float, y: Float, r: Float, fill: Boolean = false) = drawCircle(c, r * k, o(x, y), style = if (fill) Fill else sw)
    when (name) {
        "home" -> poly(3f, 11f, 12f, 3f, 21f, 11f, close = false).also { poly(5f, 10f, 5f, 21f, 19f, 21f, 19f, 10f); poly(10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f) }
        "projects" -> poly(3f, 7f, 3f, 19f, 21f, 19f, 21f, 9f, 11f, 9f, 9f, 6f, 3f, 6f, close = true)
        "editor", "split" -> { if (name == "split") { line(12f, 3f, 12f, 21f); poly(8f, 8f, 4f, 12f, 8f, 16f); poly(16f, 8f, 20f, 12f, 16f, 16f) } else { ring(6f, 18f, 3f); ring(18f, 18f, 3f); line(8f, 16f, 19f, 4f); line(16f, 16f, 5f, 4f) } }
        "media" -> { rect(3f, 4f, 18f, 16f, 3f); ring(9f, 10f, 1.6f, fill = true); poly(4f, 18f, 10f, 13f, 14f, 16f, 17f, 13f, 20f, 16f) }
        "settings" -> { ring(12f, 12f, 3.2f); ring(12f, 12f, 7f); for (i in 0 until 8) { val a = i * Math.PI.toFloat() / 4f; line(12f + 7f * cos(a), 12f + 7f * sin(a), 12f + 9.5f * cos(a), 12f + 9.5f * sin(a)) } }
        "undo" -> { poly(9f, 5f, 4f, 10f, 9f, 15f); poly(4f, 10f, 14f, 10f, 19f, 12f, 19f, 18f) }
        "redo" -> { poly(15f, 5f, 20f, 10f, 15f, 15f); poly(20f, 10f, 10f, 10f, 5f, 12f, 5f, 18f) }
        "warning" -> { poly(12f, 3f, 22f, 20f, 2f, 20f, close = true); line(12f, 9f, 12f, 14f); ring(12f, 17f, 0.8f, fill = true) }
        "export" -> { line(12f, 15f, 12f, 3f); poly(7f, 8f, 12f, 3f, 17f, 8f); poly(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f) }
        "import" -> { line(12f, 3f, 12f, 15f); poly(7f, 10f, 12f, 15f, 17f, 10f); poly(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f) }
        "play" -> poly(7f, 4f, 20f, 12f, 7f, 20f, close = true, fill = true)
        "pause" -> { rect(6f, 4f, 4f, 16f, 1f, fill = true); rect(14f, 4f, 4f, 16f, 1f, fill = true) }
        "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        "minus" -> line(5f, 12f, 19f, 12f)
        "text" -> { line(5f, 6f, 19f, 6f); line(12f, 6f, 12f, 20f); line(9f, 20f, 15f, 20f) }
        "captions", "cc" -> { rect(3f, 5f, 18f, 14f, 3f); line(7f, 11f, 11f, 11f); line(13f, 11f, 17f, 11f); line(7f, 15f, 14f, 15f) }
        "music", "audio" -> { ring(7f, 18f, 3f); line(10f, 18f, 10f, 5f); poly(10f, 5f, 19f, 7f, 19f, 11f, 10f, 9f) }
        "effects" -> poly(12f, 2f, 14.5f, 9.5f, 22f, 12f, 14.5f, 14.5f, 12f, 22f, 9.5f, 14.5f, 2f, 12f, 9.5f, 9.5f, close = true)
        "delete" -> { line(4f, 7f, 20f, 7f); poly(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f); poly(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f); line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f) }
        "duplicate" -> { rect(8f, 8f, 13f, 13f, 2f); poly(16f, 8f, 16f, 4f, 4f, 4f, 4f, 16f, 8f, 16f) }
        "check" -> poly(4f, 12f, 10f, 18f, 20f, 6f)
        "close" -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
        "scenes" -> { rect(3f, 3f, 8f, 8f, 2f); rect(13f, 3f, 8f, 8f, 2f); rect(3f, 13f, 8f, 8f, 2f); rect(13f, 13f, 8f, 8f, 2f) }
        "back" -> poly(15f, 5f, 8f, 12f, 15f, 19f)
        "length" -> { ring(12f, 13f, 8f); line(12f, 13f, 12f, 8f); line(12f, 13f, 15f, 15f); line(9f, 2f, 15f, 2f) }
        "replace" -> { poly(4f, 8f, 20f, 8f); poly(16f, 4f, 20f, 8f, 16f, 12f); poly(20f, 16f, 4f, 16f); poly(8f, 12f, 4f, 16f, 8f, 20f) }
        "record", "new" -> { ring(12f, 12f, 9f); line(12f, 8f, 12f, 16f); line(8f, 12f, 16f, 12f) }
        "analyse" -> { ring(12f, 12f, 9f); ring(12f, 12f, 4f); ring(12f, 12f, 1f, fill = true) }
    }
}
