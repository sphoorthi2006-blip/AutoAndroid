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
        val n = 90; val fps = 30; val letters = 3
        val first = HeadingWrite.frame(plan, 0, n, fps, letters)
        assertTrue(first.lines.isEmpty())
        assertEquals(0.0, first.finish, 1e-12)
        val last = HeadingWrite.frame(plan, n - 1, n, fps, letters)
        assertEquals(1.0, last.finish, 1e-12)
        assertEquals(plan.strokes.size, last.lines.size)
        // and it is fully written well before the cue ends, then held
        val held = HeadingWrite.frame(plan, (n * 0.85).toInt(), n, fps, letters)
        assertEquals(1.0, held.finish, 1e-12)
    }

    @Test fun neverGoesBackwards() {
        val plan = HeadingWrite.plan(bits(), W, H)
        val n = 120; val fps = 60
        var prevLen = -1.0; var prevFinish = -1.0; var prevStrokes = -1; var prevU = -1.0
        for (f in 0 until n) {
            val u = HeadingWrite.progress(f, n, fps, 3)
            val fr = HeadingWrite.frame(plan, f, n, fps, 3)
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

    @Test fun timingIsCappedAndShortCuesStillFinish() {
        assertEquals(0.5 + 0.09 * 10, HeadingWrite.writeSeconds(10, 20.0), 1e-9)
        assertEquals(3.0, HeadingWrite.writeSeconds(200, 20.0), 1e-9)
        assertEquals(0.8, HeadingWrite.writeSeconds(10, 1.0), 1e-9)       // 1 s cue: writing takes 80 %
        assertEquals(1.0, HeadingWrite.progress(0, 1, 30, 5), 1e-12)      // a one-frame cue is simply finished
        assertEquals(1.0, HeadingWrite.progress(1, 2, 30, 5), 1e-12)
        assertEquals(0.0, HeadingWrite.progress(0, 2, 30, 5), 1e-12)
    }
}
