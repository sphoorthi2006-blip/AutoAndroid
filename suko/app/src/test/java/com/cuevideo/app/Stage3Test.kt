package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cover-crop numbers come from the desktop engine; heading numbers were recomputed for the 86 % width (no zoom, step 12). */
class Stage3Test {
    private fun fake(t: String, sz: Int) = t.length * sz * 0.47     // same fake ruler used on the Python side

    @Test fun coverCropMatchesDesktop() {
        val want = listOf(
            listOf(1920, 1080, 1920, 1080, 0, 0),
            listOf(1000, 1000, 1920, 1920, 0, 420),
            listOf(4000, 3000, 1920, 1440, 0, 180),
            listOf(800, 1200, 1920, 2880, 0, 900),
        )
        for (w in want) {
            val c = SlideMath.coverCrop(w[0], w[1], 1920, 1080)
            assertEquals(listOf(w[2], w[3], w[4], w[5]), listOf(c.newW, c.newH, c.left, c.top))
        }
    }

    @Test fun shortHeadingIsOneLineInTheMiddle() {
        val lay = SlideMath.layout("54. of", 1920, 1080, 2.0, 200.0, SlideMath.headingMaxWidth(1920), 80.0, ::fake)
        assertEquals(400, lay.size)
        assertEquals(1, lay.lines.size)
        assertEquals(1920.0, lay.lines[0].cx, 1e-9)
        assertEquals(1080.0, lay.lines[0].cy, 1e-9)
    }

    @Test fun mediumHeadingShrinksToOneLine() {
        val lay = SlideMath.layout("7. of the of Constantinople great", 1920, 1080, 2.0, 200.0,
            SlideMath.headingMaxWidth(1920), 80.0, ::fake)
        assertEquals(212, lay.size)
        assertEquals(1, lay.lines.size)
    }

    @Test fun longHeadingWrapsAtMinSize() {
        val lay = SlideMath.layout(
            "Rome war the extraordinarily fell war Constantinople unbelievable war empire Rome of Rome",
            1920, 1080, 2.0, 200.0, SlideMath.headingMaxWidth(1920), 80.0, ::fake)
        assertEquals(160, lay.size)
        assertEquals(listOf("Rome war the extraordinarily fell war", "Constantinople unbelievable war empire Rome", "of Rome"),
            lay.lines.map { it.text })
        assertEquals(listOf(880.0, 1080.0, 1280.0), lay.lines.map { it.cy })
    }
}
