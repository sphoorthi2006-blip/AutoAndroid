package com.auto.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.media3.effect.BitmapOverlay
import com.auto.core.AnimFrame
import com.auto.core.AnimMath
import com.auto.core.TextClip

/**
 * One full-frame transparent picture that holds every text visible at a given moment of the video. Media3 asks for it on every frame (presentationTimeUs = time in the composition);
 * the same Bitmap object is returned while nothing changes (so it is not uploaded again), a new one is drawn only while an animation moves or a text appears/disappears.
 * Texts use OverlayRenderer, so they look exactly like the preview. NOT yet run on a device (whether presentationTimeUs is composition time is an assumption to test).
 */
class TextBitmapOverlay(private val ctx: Context, private val w: Int, private val h: Int, private val texts: List<TextClip>) : BitmapOverlay() {
    private var lastKey: String? = null
    private var lastBmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val tMs = presentationTimeUs / 1000
        val shown = ArrayList<Pair<TextClip, AnimFrame>>()
        val key = StringBuilder()
        for (t in texts) {
            if (tMs < t.startMs || tMs >= t.endMs) continue
            val f = AnimMath.frame(t.style.clamped(), tMs - t.startMs, t.durMs)
            if (f.alpha <= 0f) continue
            shown.add(t to f); key.append(t.id).append(':').append(f.hashCode()).append(';')
        }
        val k = key.toString()
        if (k == lastKey) return lastBmp
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)   // starts fully transparent
        if (shown.isNotEmpty()) { val c = Canvas(b); for ((t, f) in shown) OverlayRenderer.draw(ctx, c, w, h, t.text, t.style, f) }
        lastKey = k; lastBmp = b
        return b
    }
}
