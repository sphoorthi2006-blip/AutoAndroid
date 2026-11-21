package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 13: the handwriting order and timing, on small synthetic "letters" (no Android, no fonts). */
class Step13Test {
    private val W = 200
    private val H = 60

    /** Three thick vertical bars, left to right (like "III"), with a dot-sized blob above the middle one. */
    private fun bits(): ByteArray {
        val b = ByteArray(W * H)
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int) { for (y in y0..y1) for (x in x0..x1) b[y * W + x] = 1 }
        rect(20, 15, 25, 50)
        rect(80, 15, 85, 50)
        rect(140, 15, 145, 50)
        rect(81, 4, 84, 7)     // a dot above the middle bar
        return b
    }

    private fun drawn(fr: WriteFrame) = fr.lines.sumOf { Pen.polyLength(it.first) }

    @Test fun nothingAtFrameZeroEverythingAtTheEnd() {
        val plan = HeadingWrite.plan(bits(), W, H)
        val n = 90; val fps = 30; val secs = 2.0
        val first = HeadingWrite.frame(plan, 0, n, fps, secs)
        assertTrue(first.lines.isEmpty())
        assertEquals(0.0, first.finish, 1e-12)
        val last = HeadingWrite.frame(plan, n - 1, n, fps, secs)
        assertEquals(1.0, last.finish, 1e-12)
        assertEquals(plan.strokes.size, last.lines.size)
        // and it is fully written well before the cue ends, then held
        val held = HeadingWrite.frame(plan, (n * 0.85).toInt(), n, fps, secs)
        assertEquals(1.0, held.finish, 1e-12)
    }

    @Test fun neverGoesBackwards() {
        val plan = HeadingWrite.plan(bits(), W, H)
        val n = 120; val fps = 60; val secs = 2.0
        var prevLen = -1.0; var prevFinish = -1.0; var prevStrokes = -1; var prevU = -1.0
        for (f in 0 until n) {
            val u = HeadingWrite.progress(f, n, fps, secs)
            val fr = HeadingWrite.frame(plan, f, n, fps, secs)
            assertTrue(u >= prevU); assertTrue(fr.finish >= prevFinish)
            assertTrue(fr.lines.size >= prevStrokes); assertTrue(drawn(fr) >= prevLen - 1e-9)
            prevU = u; prevFinish = fr.finish; prevStrokes = fr.lines.size; prevLen = drawn(fr)
        }
    }

    @Test fun lettersAreWrittenLeftToRight() {
        val plan = HeadingWrite.plan(bits(), W, H)
        assertTrue(plan.strokes.size >= 3)
        val xs = plan.strokes.map { s -> s.pts.map { it.x }.average() }
        // the dot belongs to the middle bar, so the x order of stroke centres never jumps back by more than a bar
        for (i in 1 until xs.size) assertTrue("stroke $i starts left of stroke ${i - 1}", xs[i] >= xs[i - 1] - 6.0)
    }

    @Test fun typedSecondsAreUsedAndCapped() {
        assertEquals(2.0, HeadingWrite.writeSeconds(2.0, 20.0), 1e-9)
        assertEquals(0.8, HeadingWrite.writeSeconds(5.0, 1.0), 1e-9)       // 1 s cue: writing takes at most 80 %
        assertEquals(0.0, HeadingWrite.writeSeconds(-3.0, 20.0), 1e-9)
        assertEquals(60, HeadingWrite.writeFrames(300, 30, 2.0))           // 2 s at 30 fps
        assertEquals(120, HeadingWrite.writeFrames(300, 60, 2.0))
        assertEquals(24, HeadingWrite.writeFrames(30, 30, 5.0))            // 1 s cue: capped to 0.8 s
        // the last writing frame is exactly 1.0, the one before is not
        assertEquals(1.0, HeadingWrite.progress(59, 300, 30, 2.0), 1e-12)
        assertTrue(HeadingWrite.progress(58, 300, 30, 2.0) < 1.0)
        assertEquals(0.0, HeadingWrite.progress(0, 300, 30, 2.0), 1e-12)
    }

    @Test fun zeroSecondsOrOneFrameShowsTheFinishedText() {
        assertEquals(1.0, HeadingWrite.progress(0, 90, 30, 0.0), 1e-12)
        assertEquals(1.0, HeadingWrite.progress(0, 1, 30, 2.0), 1e-12)
        assertEquals(1.0, HeadingWrite.progress(1, 2, 30, 2.0), 1e-12)
    }
}
