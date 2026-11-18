package com.suko.app.upscale

import com.suko.app.upscale.ExportProgress.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step13Test {
    @Test
    fun theBarIsSplitByEstimatedTimeAndReachesExactlyOneHundred() {
        val p = ExportProgress(todoPictures = 10, renderSeconds = 90.0, secondsPerPicture = 9.0)   // 90 s + 90 s
        assertEquals(0.0, p.overall(Phase.UPSCALE, 0.0), 1e-9)
        assertEquals(0.25, p.overall(Phase.UPSCALE, 0.5), 1e-9)
        assertEquals(0.5, p.overall(Phase.UPSCALE, 1.0), 1e-9)
        assertEquals(0.5, p.overall(Phase.RENDER, 0.0), 1e-9)
        assertEquals(0.75, p.overall(Phase.RENDER, 0.5), 1e-9)
        assertEquals(100, p.percent(Phase.RENDER, 1.0))
    }

    @Test
    fun upscaleFractionCountsPicturesAndTiles() {
        val p = ExportProgress(4, 10.0)
        assertEquals(0.0, p.upscaleFraction(0, 0, 0), 1e-9)
        assertEquals(0.125, p.upscaleFraction(0, 128, 256), 1e-9)
        assertEquals(0.625, p.upscaleFraction(2, 128, 256), 1e-9)
        assertEquals(1.0, p.upscaleFraction(4, 0, 0), 1e-9)
    }

    @Test
    fun withNothingToUpscaleTheBarStartsInTheRenderPhase() {
        val p = ExportProgress(0, 60.0)
        assertEquals(0.0, p.overall(Phase.RENDER, 0.0), 1e-9)
        assertEquals(0.5, p.overall(Phase.RENDER, 0.5), 1e-9)
        assertEquals(1.0, p.upscaleFraction(0, 0, 0), 1e-9)
    }

    @Test
    fun theBarNeverGoesBackwardsWhenTheEstimateIsCorrected() {
        val p = ExportProgress(10, 90.0, 9.0)
        val a = p.overall(Phase.UPSCALE, 0.5)               // 0.25
        repeat(3) { p.pictureDone(30.0) }                    // the phone is much slower than assumed
        val b = p.overall(Phase.UPSCALE, 0.5)
        assertTrue("$a then $b", b >= a)
        var prev = b
        for (f in listOf(0.6, 0.7, 0.8, 1.0)) {
            val v = p.overall(Phase.UPSCALE, f)
            assertTrue(v >= prev)
            prev = v
        }
        assertEquals(100, p.percent(Phase.RENDER, 1.0))
    }

    @Test
    fun labelsAreInPlainWords() {
        val p = ExportProgress(12, 30.0)
        assertEquals("Upscaling picture 3 of 12", p.label(Phase.UPSCALE, 2))
        assertEquals("Upscaling picture 12 of 12", p.label(Phase.UPSCALE, 99))
        assertEquals("Rendering video", p.label(Phase.RENDER, 12))
    }
}
