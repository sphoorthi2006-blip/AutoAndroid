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
import java.util.Locale
import kotlin.math.max

/** Navy colour set with glowing cyan -> violet -> pink gradients (same palette as the mobile layout prototype). */
object C {
    val Bg = Color(0xFF060B1E)
    val Card = Color(0xFF0E1736)
    val Lane = Color(0xFF12204A)
    val Line = Color(0xFF1F2D5C)
    val Text = Color(0xFFEAF1FF)
    val Muted = Color(0xFF8FA0C8)
    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFF6A4DFF)
    val Pink = Color(0xFFFF4DCB)
    val Navy = Color(0xFF1B2A8A)
    val Screen = Color(0xFF050818)
    val Glow = Brush.horizontalGradient(listOf(Cyan, Violet, Pink))
    val Top = Brush.horizontalGradient(listOf(Navy, Violet, Cyan))
    val Wave = Brush.horizontalGradient(listOf(Violet, Color(0xFFC026D3)))
    val TextTrack = Brush.horizontalGradient(listOf(Cyan, Color(0xFF38BDF8)))
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
