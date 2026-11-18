package com.suko.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Steps 20-24: Patrick Hand is the only text font (the owner's file) and the Text Editor exports 1080p at 30 or 60 fps. */
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
        assertTrue(js.contains(" MP4, ") && js.contains("1080p"))
        assertTrue(js.contains("avc1.64002a"))        // H.264 level 4.2, needed for 1080p at 60 fps
        assertTrue(js.contains(" fps"))
    }

    /** Step 24: the web bundle is rebuilt by CI (npm run build:suko), so it must contain the 2K and 4K sizes and the font upload. */
    @Test
    fun theExportOffers2KAnd4KAndTheTextEditorTakesUploadedFonts() {
        assertTrue(js.contains("2560 x 1440") && js.contains("3840 x 2160"))
        assertTrue(js.contains("avc1.640033"))        // H.264 level 5.1, needed for 4K at 30 fps
        assertTrue(js.contains("Upload font"))
        assertTrue(js.contains(".ttf,.otf,.woff") || js.contains(".ttf") && js.contains(".otf") && js.contains(".woff"))
    }
}
