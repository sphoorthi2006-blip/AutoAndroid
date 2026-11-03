package com.auto.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import com.auto.core.FontCatalog
import com.auto.core.FontFace
import com.auto.core.OverlayStyle

/**
 * Phase 2 (Section 11): Turns the font + weight + italic + text script of an OverlayStyle into a Typeface.
 * - Resolves script fallback automatically when text contains Devanagari, Telugu, or Malayalam characters
 *   not supported by a Latin-only selected font.
 * - Uses multi-weight static resource files (`weightFiles`) when shipped.
 * - Applies variable font axis settings (`'wght'`, `'wdth'`) on API 26+ when `face.variableAxes` is non-empty.
 */
object FontResolver {
    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun typeface(ctx: Context, style: OverlayStyle, textSample: String = ""): Typeface {
        val face = if (textSample.isNotEmpty()) FontCatalog.resolveForText(style.font, textSample) else FontCatalog.face(style.font)
        val sdk = Build.VERSION.SDK_INT
        val w = FontCatalog.effectiveWeight(style.weight, sdk)
        val italic = style.italic && face.hasItalic
        val resName = face.resourceForWeight(w, sdk) ?: ""
        val key = "${face.id}/$resName/$w/$italic"
        cache[key]?.let { return it }

        val base = bundledByName(ctx, resName) ?: Typeface.create(face.systemFamily ?: "sans-serif", Typeface.NORMAL)
        val resolved = when {
            sdk >= 28 && !face.isVariable -> Typeface.create(base, w, italic)
            else -> Typeface.create(
                base,
                when {
                    w >= 600 && italic -> Typeface.BOLD_ITALIC
                    w >= 600 && face.weightFiles.size <= 1 -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            )
        }
        cache[key] = resolved
        return resolved
    }

    /** Applies variable font variation axes (`wght`, `wdth`) directly on `paint` on API 26+ when supported by the face. */
    fun configurePaint(ctx: Context, paint: Paint, style: OverlayStyle, textSample: String = "") {
        val face = if (textSample.isNotEmpty()) FontCatalog.resolveForText(style.font, textSample) else FontCatalog.face(style.font)
        paint.typeface = typeface(ctx, style, textSample)
        if (Build.VERSION.SDK_INT >= 26 && face.variableAxes.isNotEmpty()) {
            val settings = face.variableAxes.joinToString(", ") { ax ->
                val v = when (ax.tag) {
                    "wght" -> ax.clamp(style.weight.toFloat())
                    else -> ax.defaultVal
                }
                "'${ax.tag}' $v"
            }
            try {
                paint.fontVariationSettings = settings
            } catch (_: Throwable) {}
        }
    }

    private fun bundledByName(ctx: Context, name: String): Typeface? {
        if (name.isBlank()) return null
        val id = ctx.resources.getIdentifier(name, "font", ctx.packageName)
        if (id == 0) return null
        return try {
            ctx.resources.getFont(id)
        } catch (_: Exception) {
            null
        }
    }
}
