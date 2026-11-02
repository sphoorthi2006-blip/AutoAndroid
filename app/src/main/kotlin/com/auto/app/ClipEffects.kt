package com.auto.app

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import com.auto.core.ClipFx
import com.auto.core.TransitionSpec
import com.auto.core.Transitions

/**
 * Per-picture effects for the export. Both read the SAME core maths the tests check (ClipFx.rgbMatrix, Transitions.frame), only the time base is Android's:
 * presentationTimeUs is taken as microseconds since the START OF THIS PICTURE (assumption to confirm on a device).
 * The picture is first fitted to the output frame (black bars), then these run, so a slide/zoom moves the whole frame.
 */
class ClipColor(private val fx: ClipFx, private val own: TransitionSpec, private val next: TransitionSpec?, private val durMs: Long) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray =
        fx.rgbMatrix(Transitions.frame(own, next, presentationTimeUs / 1000, durMs).alpha)
}

class ClipMove(private val own: TransitionSpec, private val next: TransitionSpec?, private val durMs: Long) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val f = Transitions.frame(own, next, presentationTimeUs / 1000, durMs)
        val m = Matrix()
        if (f.scale != 1f) m.postScale(f.scale, f.scale)
        if (f.dxFrac != 0f) m.postTranslate(f.dxFrac * 2f, 0f)   // frame fraction -> normalised device coordinates (the frame is 2 units wide)
        return m
    }
}
