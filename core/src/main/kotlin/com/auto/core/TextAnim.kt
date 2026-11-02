package com.auto.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Animations a text can have when it appears (in) or leaves (out). Only kinds with allowedIn/allowedOut = true are offered for that slot. */
enum class AnimKind(val label: String, val allowedIn: Boolean, val allowedOut: Boolean) {
    NONE("None", true, true), FADE("Fade", true, true), SLIDE("Slide", true, true), POP("Pop", true, true), ZOOM("Zoom", true, true),
    TYPEWRITER("Typewriter", true, false), BOUNCE("Bounce", true, false)
}

/** How a text looks at one instant. alpha 0..1 multiplies the opacity; dx/dy are in FRACTIONS of the frame width/height; visibleChars is 0..1 of the letters shown. */
data class AnimFrame(val alpha: Float = 1f, val scale: Float = 1f, val dxFrac: Float = 0f, val dyFrac: Float = 0f, val visibleChars: Float = 1f) {
    companion object { val REST = AnimFrame(); val HIDDEN = AnimFrame(alpha = 0f, visibleChars = 0f) }
}

object AnimMath {
    fun easeOutCubic(p: Float): Float { val q = 1f - p.coerceIn(0f, 1f); return 1f - q * q * q }
    fun easeOutBack(p: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; val q = p.coerceIn(0f, 1f) - 1f; return 1f + c3 * q * q * q + c1 * q * q }
    fun easeOutBounce(p0: Float): Float {
        val p = p0.coerceIn(0f, 1f); val n = 7.5625f; val d = 2.75f
        return when { p < 1 / d -> n * p * p; p < 2 / d -> { val t = p - 1.5f / d; n * t * t + 0.75f }; p < 2.5f / d -> { val t = p - 2.25f / d; n * t * t + 0.9375f }; else -> { val t = p - 2.625f / d; n * t * t + 0.984375f } }
    }

    /** State while the text ENTERS: p = 0 (just appeared) .. 1 (finished). */
    private fun entering(k: AnimKind, p: Float): AnimFrame = when (k) {
        AnimKind.NONE -> AnimFrame.REST
        AnimKind.FADE -> AnimFrame(alpha = p)
        AnimKind.SLIDE -> AnimFrame(alpha = p, dyFrac = (1f - easeOutCubic(p)) * 0.08f)
        AnimKind.POP -> AnimFrame(alpha = min(1f, p * 4f), scale = max(0.01f, easeOutBack(p)))
        AnimKind.ZOOM -> AnimFrame(alpha = p, scale = 0.6f + 0.4f * easeOutCubic(p))
        AnimKind.TYPEWRITER -> AnimFrame(visibleChars = p)
        AnimKind.BOUNCE -> AnimFrame(alpha = min(1f, p * 5f), dyFrac = (1f - easeOutBounce(p)) * -0.12f)
    }
    /** State while the text LEAVES: q = 1 (still fully there) .. 0 (gone). Only the kinds with allowedOut. */
    private fun leaving(k: AnimKind, q: Float): AnimFrame = when (k) {
        AnimKind.FADE -> AnimFrame(alpha = q)
        AnimKind.SLIDE -> AnimFrame(alpha = q, dyFrac = (1f - easeOutCubic(q)) * -0.08f)
        AnimKind.POP -> AnimFrame(alpha = min(1f, q * 4f), scale = max(0.01f, 0.2f + 0.8f * easeOutCubic(q)))
        AnimKind.ZOOM -> AnimFrame(alpha = q, scale = 1f + 0.4f * (1f - easeOutCubic(q)))
        else -> AnimFrame.REST
    }

    /** t = ms since the text appeared, dur = how long it is shown. In and out are shortened together when the text is shorter than both. */
    fun frame(s: OverlayStyle, tMs: Long, durMs: Long): AnimFrame {
        if (tMs < 0 || tMs >= durMs) return AnimFrame.HIDDEN
        var inMs = if (s.animIn == AnimKind.NONE) 0L else s.animInMs.toLong(); var outMs = if (s.animOut == AnimKind.NONE || !s.animOut.allowedOut) 0L else s.animOutMs.toLong()
        if (inMs + outMs > durMs) { val k = durMs.toDouble() / (inMs + outMs); inMs = (inMs * k).toLong(); outMs = (outMs * k).toLong() }
        val toEnd = durMs - tMs
        if (outMs > 0 && toEnd < outMs) return leaving(s.animOut, toEnd.toFloat() / outMs)
        if (inMs > 0 && tMs < inMs) return entering(s.animIn, tMs.toFloat() / inMs)
        return AnimFrame.REST
    }
    /** Letters visible for a typewriter frame (never cuts a surrogate pair in half). */
    fun visibleText(text: String, f: AnimFrame): String {
        if (f.visibleChars >= 1f) return text
        var n = (text.length * f.visibleChars).toInt().coerceIn(0, text.length)
        if (n in 1 until text.length && Character.isHighSurrogate(text[n - 1])) n--
        return text.substring(0, n)
    }
}

/** Alignment guides for dragging a text in the preview: snap to the centre lines and thirds, keep inside the safe area. All values are fractions 0..1 of the frame. */
object Guides {
    const val SNAP = 0.015f          // within 1.5 % of a guide the text jumps onto it
    const val SAFE_ACTION = 0.05f    // TV "action safe" margin
    const val SAFE_TITLE = 0.10f     // TV "title safe" margin
    val LINES = listOf(0.5f, 1f / 3f, 2f / 3f)

    class Snap(val value: Float, val guide: Float?)
    fun snap(v: Float, threshold: Float = SNAP): Snap { val g = LINES.minByOrNull { abs(it - v) }!!; return if (abs(g - v) <= threshold) Snap(g, g) else Snap(v, null) }
    /** Centre of a text of half-size (halfW, halfH) kept so that its box stays inside the safe margin. */
    fun keepInside(x: Float, y: Float, halfW: Float, halfH: Float, margin: Float = SAFE_ACTION): Pair<Float, Float> {
        fun c(v: Float, h: Float): Float { val lo = margin + h; val hi = 1f - margin - h; return if (lo >= hi) 0.5f else v.coerceIn(lo, hi) }
        return Pair(c(x, halfW), c(y, halfH))
    }
    fun insideSafe(x: Float, y: Float, halfW: Float, halfH: Float, margin: Float = SAFE_TITLE) = x - halfW >= margin - 1e-4f && x + halfW <= 1f - margin + 1e-4f && y - halfH >= margin - 1e-4f && y + halfH <= 1f - margin + 1e-4f
}
