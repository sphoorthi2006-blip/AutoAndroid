package com.cuevideo.app

import kotlin.math.min

/**
 * Handwriting timing for a heading or subscribe cue (pure Kotlin: no Android classes).
 * The pen engine ([Handwriting], [Pen]) works on a 0/1 bitmap of the finished text (1 = ink).
 * For each frame of the cue this gives a [WriteFrame]: pen-wide lines that uncover the ink
 * (letters left to right, line by line) plus a final share of ink shown by the last fade.
 * Frame 0 shows nothing. The writing is automatic: it takes [WRITE_SHARE] (80 %) of the cue,
 * so the app needs no setting; the last 20 % holds the finished text.
 */
object HeadingWrite {
    /** The pen uses this share of the cue length; the rest of the cue shows the finished text. */
    const val WRITE_SHARE = 0.8

    /** Number of frames from the first frame to the fully written one (1 = no animation, shown finished). */
    fun writeFrames(n: Int, fps: Int): Int {
        if (n <= 1) return 1
        val wf = Math.rint(n * WRITE_SHARE).toInt()
        return if (wf < 2) 1 else min(n, wf)
    }

    /** Progress u (0..1) of the whole writing at frame [f] of a cue of [n] frames. Frame 0 is 0.0; from writeFrames-1 on it is 1.0. */
    fun progress(f: Int, n: Int, fps: Int): Double {
        val wf = writeFrames(n, fps)
        if (wf <= 1) return 1.0
        return (f.toDouble() / (wf - 1)).coerceIn(0.0, 1.0)
    }

    fun plan(bits: ByteArray, w: Int, h: Int): WritePlan = Handwriting.plan(bits, w, h)

    /** What to draw at frame [f] (0-based) of a cue with [n] frames. */
    fun frame(plan: WritePlan, f: Int, n: Int, fps: Int): WriteFrame =
        plan.frame(progress(f, n, fps))
}
