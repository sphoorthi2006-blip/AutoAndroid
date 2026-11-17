package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected numbers come from the desktop engine (draw_text_layer, render_zoom_heading, normalize_image). */
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
        val lay = SlideMath.layout("54. of", 1920, 1080, 2.0, 200.0, SlideMath.animMaxWidth(1920, 0.12), 80.0, ::fake)
        assertEquals(400, lay.size)
        assertEquals(1, lay.lines.size)
        assertEquals(1920.0, lay.lines[0].cx, 1e-9)
        assertEquals(1080.0, lay.lines[0].cy, 1e-9)
    }

    @Test fun mediumHeadingShrinksToOneLine() {
        val lay = SlideMath.layout("7. of the of Constantinople great", 1920, 1080, 2.0, 200.0,
            SlideMath.animMaxWidth(1920, 0.12), 80.0, ::fake)
        assertEquals(190, lay.size)
        assertEquals(1, lay.lines.size)
    }

    @Test fun longHeadingWrapsAtMinSize() {
        val lay = SlideMath.layout(
            "Rome war the extraordinarily fell war Constantinople unbelievable war empire Rome of Rome",
            1920, 1080, 2.0, 200.0, SlideMath.animMaxWidth(1920, 0.12), 80.0, ::fake)
        assertEquals(160, lay.size)
        assertEquals(listOf("Rome war the extraordinarily fell war", "Constantinople unbelievable war empire", "Rome of Rome"),
            lay.lines.map { it.text })
        assertEquals(listOf(880.0, 1080.0, 1280.0), lay.lines.map { it.cy })
    }

    @Test fun zoomAndFade() {
        assertEquals(1.0, SlideMath.zoomFactor(0, 90), 1e-12)
        assertEquals(1.06, SlideMath.zoomFactor(45, 90), 1e-12)
        assertEquals(1.119955884774, SlideMath.zoomFactor(89, 90), 1e-9)
        assertEquals(9, SlideMath.fadeFrames(30))
        assertEquals(18, SlideMath.fadeFrames(60))
        assertEquals(0.0, SlideMath.fadeBrightness(0, 9), 1e-12)
        assertEquals(1.0, SlideMath.fadeBrightness(45, 9), 1e-12)
        assertTrue(SlideMath.fadeBrightness(4, 9) in 0.0..1.0)
        val b = SlideMath.cropBox(3840, 2160, 1.06)
        assertEquals(108.679245283, b.left, 1e-6)
        assertEquals(2098.867924528, b.bottom, 1e-6)
    }
}
