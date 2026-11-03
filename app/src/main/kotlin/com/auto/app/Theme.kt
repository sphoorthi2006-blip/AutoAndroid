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

/**
 * Section 4: Midnight Violet Design System centralized in Theme.kt.
 * No orange anywhere. Electric Violet (#8B5CF6) is primary; Cyan Blue (#38BDF8) is sparing;
 * Lighter Violet (#C4B5FD) is available for high-contrast small accent text on dark cards.
 * Gradients and shadows are restrained (no excessive neon glow or decorative motion).
 */
object C {
    val Bg = Color(DesignTokens.BG)                     // #0B1020 Midnight Navy
    val Card = Color(DesignTokens.SURFACE)              // #121A2B Deep Slate
    val Lane = Color(DesignTokens.SURFACE_HI)           // #192338 Dark Slate
    val Line = Color(DesignTokens.LINE)                 // #273449 Slate Border
    val Text = Color(DesignTokens.TEXT)                 // #F8FAFC Soft White
    val Muted = Color(DesignTokens.TEXT_MUTED)          // #94A3B8 Cool Gray
    val Violet = Color(DesignTokens.PRIMARY)            // #8B5CF6 Electric Violet
    val VioletDeep = Color(DesignTokens.PRIMARY_DEEP)   // #6D28D9 Deep Violet (WCAG >= 4.5:1 with White)
    val VioletLight = Color(DesignTokens.PRIMARY_LIGHT) // #C4B5FD Lighter Violet for small text on cards
    val Cyan = Color(DesignTokens.CYAN)                 // #38BDF8 Cyan Blue (sparing secondary accent)
    val Success = Color(DesignTokens.SUCCESS)           // #34D399 Emerald
    val Warning = Color(DesignTokens.WARNING)           // #FBBF24 Amber
    val Error = Color(DesignTokens.ERROR)               // #F87171 Red
    val Pink = Error                                    // Legacy alias for Error/Danger
    val Navy = Color(DesignTokens.SURFACE_HI)           // Centralized to SURFACE_HI (no hard-coded drift)
    val Screen = Color(DesignTokens.BG)                 // Centralized to BG
    val Glow = Brush.horizontalGradient(listOf(VioletDeep, Violet))
    val Top = Brush.horizontalGradient(listOf(Card, Card))
    val Wave = Brush.horizontalGradient(listOf(VioletDeep, Violet))
    val TextTrack = Brush.horizontalGradient(listOf(VioletLight, Violet))
}

/** Restrained elevation shadow around a shape (Section 4 & Phase 9: no excessive neon glow). */
fun Modifier.glow(color: Color, radius: Dp = 6.dp, shape: Shape = RoundedCornerShape(DesignTokens.RADIUS_M.dp)): Modifier {
    val restrained = radius.coerceAtMost(8.dp)
    return this.shadow(restrained, shape, ambientColor = color.copy(alpha = 0.35f), spotColor = color.copy(alpha = 0.35f))
}

fun fmt(ms: Long): String {
    val s = max(0L, ms) / 1000
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** Small down-sampled picture loader with a memory cache (thumbnails stay cheap). */
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
        } catch (_: Throwable) {
            null
        }
    }
}
