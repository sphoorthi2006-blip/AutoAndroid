package com.suko.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Step 19: the Text Editor opens the Studio inside the app (offline web view), in text-only mode. */
class Step19Test {
    private val page = "web/studio/index.html?app=text"       // same text as TEXT_EDITOR_PAGE in HubActivity

    @Test
    fun theTextEditorPageIsServedFromTheAppItself() {
        val route = WebRules.route("${WebConfig.ORIGIN}/$page")
        assertTrue(route is Route.Asset)
        assertEquals("web/studio/index.html", (route as Route.Asset).path)     // the query part is not part of the file name
        assertTrue(WebRules.isOurs("${WebConfig.ORIGIN}/$page"))
    }

    @Test
    fun noOtherAddressIsEverServedSoNothingOpensInChromeOrOnTheInternet() {
        assertEquals(Route.Denied, WebRules.route("https://example.com/web/studio/index.html?app=text"))
        assertEquals(Route.Denied, WebRules.route("http://${WebConfig.HOST}/web/studio/index.html"))
        assertEquals(Route.Denied, WebRules.route("${WebConfig.ORIGIN}/web/../secret?app=text"))
    }

    @Test
    fun theHubOpensThePageThroughTheInAppWebHostAndTheBundleHasTextOnlyMode() {
        val hub = File("src/main/java/com/suko/app/HubActivity.kt").readText()
        assertTrue(hub.contains("TEXT_EDITOR_PAGE = \"$page\""))
        assertTrue(hub.contains("openWeb(TEXT_EDITOR_PAGE"))
        val js = File("src/main/assets/web/studio/assets").listFiles { x -> x.extension == "js" }!!.joinToString("\n") { it.readText() }
        assertTrue("bundle must read ?app=text", js.contains("\"app\")===\"text\"") || js.contains("get(\"app\")"))
        assertTrue("bundle must have the position controls", js.contains("Position up/down"))
    }
}
