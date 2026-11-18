package com.suko.app.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** Logic tests for the parts of the upscale engine that need no phone: mark removal and the classic pipeline. */
class Step2Test {

    private fun gray(w: Int, h: Int, v: Int) = ImageProcessor.Img(w, h, IntArray(w * h) { (255 shl 24) or (v shl 16) or (v shl 8) or v })

    private fun ch(p: Int, shift: Int) = (p shr shift) and 255

    @Test
    fun markRemovalFillsTheStarWithTheSurroundingColour() {
        val w = 1376
        val h = 768
        val src = gray(w, h, 128)
        // draw the white 4-point star (same formula as the engine) at the fixed spot
        val cx = 1279.0
        val cy = 669.5
        val r = 30.0
        for (y in 630..710) for (x in 1240..1320) {
            val dx = abs(x - cx) / r
            val dy = abs(y - cy) / r
            if (dx.pow(2.0 / 3.0) + dy.pow(2.0 / 3.0) <= 1.0) src.px[y * w + x] = 0xFFFFFFFF.toInt()
        }
        assertEquals(255, ch(src.px[669 * w + 1279], 16))          // the star is there before
        val out = ImageProcessor.removeMark(src)
        for (shift in intArrayOf(16, 8, 0)) {
            assertTrue("star centre not filled", abs(ch(out.px[669 * w + 1279], shift) - 128) <= 2)
            assertTrue("star arm not filled", abs(ch(out.px[669 * w + 1262], shift) - 128) <= 2)
        }
        assertEquals("pixels far from the star must not change", src.px[100 * w + 100], out.px[100 * w + 100])
        assertEquals(w, out.w)
        assertEquals(h, out.h)
    }

    @Test
    fun classicPipelineDoublesSmallPicturesAndKeepsFlatColour() {
        val src = gray(64, 48, 100)
        val out = ImageProcessor.process(src, removeMark = false, cropStrip = false)   // no AI asked: classic path
        assertEquals("classic", ImageProcessor.lastMethod)
        assertEquals(128, out.w)
        assertEquals(96, out.h)
        for (p in out.px) assertTrue("flat colour drifted", abs(ch(p, 16) - 100) <= 2)
    }

    @Test
    fun markRemovalToggleOffLeavesThePictureAlone() {
        val src = gray(64, 48, 90)
        src.px[10 * 64 + 10] = 0xFFFFFFFF.toInt()
        val out = ImageProcessor.process(src, removeMark = false, cropStrip = false)
        // the white dot must survive (it is only softened by the classic scaling, not erased)
        var brightest = 0
        for (p in out.px) brightest = maxOf(brightest, ch(p, 16))
        assertTrue("dot was erased", brightest > 150)
    }
}
