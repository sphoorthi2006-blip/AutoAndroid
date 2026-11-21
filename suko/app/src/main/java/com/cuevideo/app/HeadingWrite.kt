package com.cuevideo.app

import kotlin.math.max
import kotlin.math.min

/**
 * Handwriting timing for a heading cue (step 13, pure Kotlin: no Android classes).
 * The pen engine ([Handwriting], [Pen]) works on a 0/1 bitmap of the finished heading text (1 = ink).
 * For each frame of the cue this gives a [WriteFrame]: pen-wide lines that uncover the ink
 * (letters left to right, line by line) plus a final share of ink shown by the last fade.
 * Frame 0 shows nothing; the last writing frame (and every frame after it) shows all of it.
 */
object HeadingWrite {
    const val BASE_SECONDS = 0.5
    const val PER_LETTER_SECONDS = 0.09
    const val MAX_SECONDS = 3.0
    /** The writing never takes more than this share of the cue, so the finished heading is held for a moment. */
    const val MAX_CUE_SHARE = 0.8

    /** Seconds the pen needs for [letters] letters in a cue of [cueSeconds]. */
    fun writeSeconds(letters: Int, cueSeconds: Double): Double =
        min(min(BASE_SECONDS + PER_LETTER_SECONDS * letters, MAX_SECONDS), cueSeconds * MAX_CUE_SHARE)

    /** Progress u (0..1) of the whole writing at frame [f] of a cue of [n] frames. Frame 0 is 0.0; from [writeFrames]-1 on it is 1.0. */
    fun progress(f: Int, n: Int, fps: Int, letters: Int): Double {
        if (n <= 1) return 1.0
        val wf = max(2, min(n, Math.rint(writeSeconds(letters, n.toDouble() / fps) * fps).toInt()))
        return (f.toDouble() / (wf - 1)).coerceIn(0.0, 1.0)
    }

    fun plan(bits: ByteArray, w: Int, h: Int): WritePlan = Handwriting.plan(bits, w, h)

    /** What to draw at frame [f] (0-based) of a cue with [n] frames. */
    fun frame(plan: WritePlan, f: Int, n: Int, fps: Int, letters: Int): WriteFrame =
        plan.frame(progress(f, n, fps, letters))
}
