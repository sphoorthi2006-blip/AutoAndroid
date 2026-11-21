package com.cuevideo.app

import kotlin.math.max
import kotlin.math.min

/**
 * Handwriting timing for a heading or subscribe cue (pure Kotlin: no Android classes).
 * The pen engine ([Handwriting], [Pen]) works on a 0/1 bitmap of the finished text (1 = ink).
 * For each frame of the cue this gives a [WriteFrame]: pen-wide lines that uncover the ink
 * (letters left to right, line by line) plus a final share of ink shown by the last fade.
 * Frame 0 shows nothing; the last writing frame (and every frame after it) shows all of it.
 * The writing time in seconds is typed in by the owner (Auto Editor, "Handwriting time").
 */
object HeadingWrite {
    const val DEFAULT_SECONDS = 2.0
    /** The writing never takes more than this share of the cue, so the finished text is held for a moment. */
    const val MAX_CUE_SHARE = 0.8

    /** Seconds the pen really takes: what the owner typed, but at most [MAX_CUE_SHARE] of the cue. 0 = shown finished at once. */
    fun writeSeconds(seconds: Double, cueSeconds: Double): Double =
        min(max(0.0, seconds), cueSeconds * MAX_CUE_SHARE)

    /** Number of frames from the first frame to the fully written one (1 = no animation, shown finished). */
    fun writeFrames(n: Int, fps: Int, seconds: Double): Int {
        if (n <= 1) return 1
        val sec = writeSeconds(seconds, n.toDouble() / fps)
        val wf = Math.rint(sec * fps).toInt()
        return if (wf < 2) 1 else min(n, wf)
    }

    /** Progress u (0..1) of the whole writing at frame [f] of a cue of [n] frames. Frame 0 is 0.0 (unless there is no animation); from writeFrames-1 on it is 1.0. */
    fun progress(f: Int, n: Int, fps: Int, seconds: Double): Double {
        val wf = writeFrames(n, fps, seconds)
        if (wf <= 1) return 1.0
        return (f.toDouble() / (wf - 1)).coerceIn(0.0, 1.0)
    }

    fun plan(bits: ByteArray, w: Int, h: Int): WritePlan = Handwriting.plan(bits, w, h)

    /** What to draw at frame [f] (0-based) of a cue with [n] frames. */
    fun frame(plan: WritePlan, f: Int, n: Int, fps: Int, seconds: Double): WriteFrame =
        plan.frame(progress(f, n, fps, seconds))
}
