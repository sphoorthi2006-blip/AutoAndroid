package com.suko.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Patrick Hand is the only text font (the owner's file) and the Text Editor exports 1080p at 30 or 60 fps (no 2K/4K). */
class Step23Test {
    private val assets = File("src/main/assets/web/studio/assets")
    private val js get() = assets.listFiles { x -> x.extension == "js" }!!.joinToString("\n") { it.readText() }

    @Test
    fun theOwnersPatrickHandFileIsBundledAndIsTheSameAsTheNativeAppsFont() {
        val bundled = assets.listFiles { x -> x.name.startsWith("PatrickHand-Regular") && x.extension == "ttf" }!!
        assertEquals(1, bundled.size)
        assertTrue(bundled[0].readBytes().contentEquals(File("src/main/assets/PatrickHand-Regular.ttf").readBytes()))
        assertEquals("font/ttf", WebRules.mimeFor("web/studio/assets/${bundled[0].name}"))
    }

    @Test
    fun noOtherTextFontIsBundledExceptTheSymbolFallback() {
        val fonts = assets.listFiles { x -> x.extension in setOf("ttf", "otf", "woff", "woff2") }!!.map { it.name }
        for (n in fonts) assertTrue("unexpected font $n", n.startsWith("PatrickHand-Regular") || n.startsWith("noto-sans-math"))
        assertFalse(js.contains("Handwriting (Caveat)"))
    }

    @Test
    fun theExportOffers30And60FramesAnd1080p() {
        assertTrue(js.contains("Export video") && js.contains("Frame rate") && js.contains("1080p"))
        assertTrue(js.contains("avc1.64002a"))        // H.264 level 4.2, needed for 1080p at 60 fps
        assertTrue(js.contains(" fps"))
    }

    /** 2026-11-21 owner decision: 1080p only, Patrick Hand only, handwriting only. The bundle must not offer 2K / 4K, font upload or the outline-then-fill motion. */
    @Test
    fun theBundleOffersNo2KOr4KNoFontUploadAndNoOutlineMotion() {
        assertFalse(js.contains("2560 x 1440"))
        assertFalse(js.contains("3840 x 2160"))
        assertFalse(js.contains("Upload font"))
        assertFalse(js.contains("Add font file"))
        assertFalse(js.contains("Outline, then fill"))
        assertTrue(js.contains("Patrick Hand (built in)"))
    }
}
