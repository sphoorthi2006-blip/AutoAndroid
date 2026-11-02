package com.auto.app

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auto.core.DesignTokens
import java.util.Locale
import kotlin.math.max

/** Dark premium palette: charcoal surfaces, violet primary, lavender secondary. Values come from core DesignTokens (WCAG-checked in Check18); the NAMES are the old ones so no screen had to change
 *  (Cyan = lavender secondary accent, Pink = danger/warning red, Violet = primary). Gradients are kept subtle and the top bar is flat. */
object C {
    val Bg = Color(DesignTokens.BG)
    val Card = Color(DesignTokens.SURFACE)
    val Lane = Color(DesignTokens.SURFACE_HI)
    val Line = Color(DesignTokens.LINE)
    val Text = Color(DesignTokens.TEXT)
    val Muted = Color(DesignTokens.TEXT_MUTED)
    val Cyan = Color(DesignTokens.LAVENDER)
    val Violet = Color(DesignTokens.PRIMARY)
    val Pink = Color(DesignTokens.DANGER)
    val Navy = Color(0xFF2E1065)
    val Screen = Color(0xFF050507)
    val Glow = Brush.horizontalGradient(listOf(Color(DesignTokens.PRIMARY), Color(DesignTokens.PRIMARY_HI)))
    val Top = Brush.horizontalGradient(listOf(Color(DesignTokens.SURFACE), Color(DesignTokens.SURFACE)))
    val Wave = Brush.horizontalGradient(listOf(Color(DesignTokens.PRIMARY), Color(0xFF9F67FF)))
    val TextTrack = Brush.horizontalGradient(listOf(Color(DesignTokens.LAVENDER), Color(0xFFA78BFA)))
}

/** A coloured halo around a shape (used for the selected clip, active tab, focused search box, play button...). */
fun Modifier.glow(color: Color, radius: Dp = 14.dp, shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    this.shadow(radius, shape, ambientColor = color, spotColor = color)

fun fmt(ms: Long): String { val s = max(0L, ms) / 1000; return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }

/** Small down-sampled picture loader with a memory cache (the pictures are big; thumbnails must stay cheap). */
object Pics {
    private val cache = LruCache<String, ImageBitmap>(48)

    fun load(path: String, maxSide: Int): ImageBitmap? {
        val key = "$path@$maxSide"
        cache.get(key)?.let { return it }
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            var s = 1
            while (max(o.outWidth, o.outHeight) / s > maxSide * 2) s *= 2
            val b = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            val ib = b.asImageBitmap()
            cache.put(key, ib)
            ib
        } catch (e: Throwable) { null }
    }
}
