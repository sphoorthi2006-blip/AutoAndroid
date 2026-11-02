package com.auto.app

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import com.auto.core.FontCatalog
import com.auto.core.FontFace
import com.auto.core.OverlayStyle

/** Turns the font + weight + italic of an OverlayStyle into a Typeface. Bundled files live in res/font (names in FontCatalog); a missing file falls back to the system sans. */
object FontResolver {
    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun typeface(ctx: Context, style: OverlayStyle): Typeface {
        val face = FontCatalog.face(style.font)
        val w = FontCatalog.effectiveWeight(style.weight, Build.VERSION.SDK_INT)
        val italic = style.italic && face.hasItalic
        val key = "${face.id}/$w/$italic"
        cache[key]?.let { return it }
        val base = bundled(ctx, face) ?: Typeface.create(face.systemFamily ?: "sans-serif", Typeface.NORMAL)
        val t = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, w, italic)
        else Typeface.create(base, when { w >= 600 && italic -> Typeface.BOLD_ITALIC; w >= 600 -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL })
        cache[key] = t
        return t
    }

    private fun bundled(ctx: Context, face: FontFace): Typeface? {
        val name = face.bundledRes ?: return null
        val id = ctx.resources.getIdentifier(name, "font", ctx.packageName)
        if (id == 0) return null
        return try { ctx.resources.getFont(id) } catch (e: Exception) { null }
    }
}
