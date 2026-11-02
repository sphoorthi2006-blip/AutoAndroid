package com.auto.core

import kotlin.math.max
import kotlin.math.min

/**
 * Colour adjustments for one picture. brightness / contrast / saturation are -1..1 (0 = unchanged), opacity is 0..1 (fades towards the black background).
 * Everything is folded into ONE 4x4 colour matrix so the exporter applies a single Media3 RgbMatrix (the same matrix the preview can use).
 * NOT offered, because Media3 1.3.1 cannot render them and a fake slider is worse than none: blur, sharpen, vignette (see EffectCatalog).
 */
data class ClipFx(val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val opacity: Float = 1f) {
    val isIdentity get() = brightness == 0f && contrast == 0f && saturation == 0f && opacity == 1f
    fun clamped() = ClipFx(brightness.coerceIn(-1f, 1f), contrast.coerceIn(-1f, 1f), saturation.coerceIn(-1f, 1f), opacity.coerceIn(0f, 1f))

    /** OpenGL column-major 4x4 for a pixel (r, g, b, 1): out = M * in. The 4th column holds the offset, which is why the picture's alpha must be 1 (pictures are opaque). */
    fun rgbMatrix(extraAlpha: Float = 1f): FloatArray {
        val f = clamped(); val a = 1f + f.saturation; val k = 1f + f.contrast; val op = f.opacity * extraAlpha.coerceIn(0f, 1f)
        val lw = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        val t = 0.5f * (1f - k) + 0.5f * f.brightness
        val m = FloatArray(16)
        for (i in 0..2) for (j in 0..2) m[j * 4 + i] = op * k * ((if (i == j) a else 0f) + (1f - a) * lw[j])
        for (i in 0..2) m[12 + i] = op * t
        m[15] = 1f
        return m
    }
    fun toJson() = "{\"b\":$brightness,\"c\":$contrast,\"s\":$saturation,\"o\":$opacity}"
    companion object {
        fun fromMap(m: Map<*, *>?): ClipFx = if (m == null) ClipFx() else ClipFx((m["b"] as? Double)?.toFloat() ?: 0f, (m["c"] as? Double)?.toFloat() ?: 0f, (m["s"] as? Double)?.toFloat() ?: 0f, (m["o"] as? Double)?.toFloat() ?: 1f).clamped()
        /** What the matrix does to one pixel (0..1 per channel), clipped to 0..1 like the GPU does. Used by the tests and by the live preview filter. */
        fun apply(m: FloatArray, r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
            fun ch(i: Int) = (m[i] * r + m[4 + i] * g + m[8 + i] * b + m[12 + i]).coerceIn(0f, 1f)
            return Triple(ch(0), ch(1), ch(2))
        }
    }
}

/** The sliders the Effects tab may show. `rendered` = the export really draws it. Anything else is listed so the UI can say "not available" instead of showing a dead control. */
class EffectInfo(val id: String, val label: String, val rendered: Boolean, val note: String)
object EffectCatalog {
    val all = listOf(
        EffectInfo("brightness", "Brightness", true, ""), EffectInfo("contrast", "Contrast", true, ""), EffectInfo("saturation", "Saturation", true, ""),
        EffectInfo("opacity", "Opacity", true, "Fades the picture towards black"),
        EffectInfo("blur", "Blur", false, "Needs a newer video library than the one in this app"), EffectInfo("sharpen", "Sharpen", false, "Needs a newer video library than the one in this app"), EffectInfo("vignette", "Vignette", false, "Needs a newer video library than the one in this app"))
    val available get() = all.filter { it.rendered }
    val unavailable get() = all.filterNot { it.rendered }
}

/** Entrance styles at the START of a picture. FADE is a dip through black (the previous picture fades out, this one fades in). */
enum class TransitionKind(val label: String, val rendered: Boolean) { NONE("None", true), FADE("Fade", true), SLIDE("Slide in", true), ZOOM("Zoom in", true), DISSOLVE("Dissolve", false), WIPE("Wipe", false) }
data class TransitionSpec(val kind: TransitionKind = TransitionKind.NONE, val ms: Int = 500) {
    val active get() = kind != TransitionKind.NONE
    fun clamped() = copy(ms = ms.coerceIn(100, 2000), kind = if (kind.rendered) kind else TransitionKind.NONE)
    fun toJson() = "{\"k\":\"${kind.name}\",\"ms\":$ms}"
    companion object { fun fromMap(m: Map<*, *>?): TransitionSpec = if (m == null) TransitionSpec() else TransitionSpec(TransitionKind.values().firstOrNull { it.name == m["k"] } ?: TransitionKind.NONE, (m["ms"] as? Double)?.toInt() ?: 500).clamped() }
}

object Transitions {
    val offered get() = TransitionKind.values().filter { it.rendered }
    val unavailable get() = TransitionKind.values().filterNot { it.rendered }
    /** The transition can never be longer than half of the picture, so two neighbours never fight over the same frames. */
    fun effectiveMs(spec: TransitionSpec, durMs: Long): Long = min(spec.ms.toLong(), max(0L, durMs / 2))

    /** Look of a picture at tMs (ms after ITS start) of durMs. `next` = the following picture's transition (a FADE there also fades THIS picture out). dxFrac/dyFrac in frame fractions, scale around the centre. */
    fun frame(own: TransitionSpec, next: TransitionSpec?, tMs: Long, durMs: Long): AnimFrame {
        var alpha = 1f; var scale = 1f; var dx = 0f
        val inMs = if (own.active) effectiveMs(own, durMs) else 0L
        if (inMs > 0 && tMs < inMs) {
            val p = (tMs.toFloat() / inMs).coerceIn(0f, 1f)
            when (own.kind) {
                TransitionKind.FADE -> alpha = p
                TransitionKind.SLIDE -> dx = (1f - AnimMath.easeOutCubic(p)) * 1f
                TransitionKind.ZOOM -> { scale = 0.6f + 0.4f * AnimMath.easeOutCubic(p); alpha = p }
                else -> {}
            }
        }
        if (next != null && next.kind == TransitionKind.FADE) {
            val out = effectiveMs(next, durMs)
            val left = durMs - tMs
            if (out > 0 && left < out) alpha = min(alpha, (left.toFloat() / out).coerceIn(0f, 1f))
        }
        return AnimFrame(alpha = alpha, scale = scale, dxFrac = dx)
    }
}
