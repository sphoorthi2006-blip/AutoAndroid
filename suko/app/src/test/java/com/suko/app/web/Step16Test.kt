package com.suko.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Step16Test {
    private val o = WebConfig.ORIGIN

    @Test
    fun onlyOurOwnAddressIsServedAndEverythingElseIsDenied() {
        val r = WebRules.route("$o/web/studio/index.html")
        assertTrue(r is Route.Asset && r.path == "web/studio/index.html")
        for (bad in listOf(
            "https://example.com/web/studio/index.html", "http://appassets.androidplatform.net/web/x.html",
            "https://appassets.androidplatform.net.evil.com/web/x.html", "https://evil.com/?https://appassets.androidplatform.net/web/x",
            "file:///android_asset/web/x.html", "content://media/1", "data:text/html,hi", "javascript:alert(1)", "$o/", "$o",
        )) assertFalse("should be denied: $bad", WebRules.isOurs(bad))
    }

    @Test
    fun pathTricksAreRefused() {
        for (bad in listOf(
            "$o/web/../secret.txt", "$o/web/%2e%2e/secret", "$o/web//x.html", "$o/web/./x.html", "$o/web\\x.html",
            "$o/other/x.html", "$o/secret.txt", "$o/web/", "$o/bridge/a/b", "$o/bridge/../x", "$o/input/-1", "$o/input/abc", "$o/input/1/2",
        )) assertFalse("should be denied: $bad", WebRules.isOurs(bad))
    }

    @Test
    fun queryAndHashAreIgnoredAndBridgeAndInputRoutesWork() {
        val a = WebRules.route("$o/web/studio/smoke.html?x=1#top")
        assertTrue(a is Route.Asset && a.path == "web/studio/smoke.html")
        val b = WebRules.route("$o/bridge/c1abc_2-X")
        assertTrue(b is Route.Bridge && b.id == "c1abc_2-X")
        val i = WebRules.route("$o/input/3")
        assertTrue(i is Route.Input && i.index == 3)
    }

    @Test
    fun theTypesTheStudioNeedsHaveTheRightMime() {
        assertEquals("text/html", WebRules.mimeFor("web/studio/index.html"))
        assertEquals("text/javascript", WebRules.mimeFor("web/studio/assets/index-AX3.js"))   // module scripts are refused with a wrong type
        assertEquals("text/css", WebRules.mimeFor("a/b.CSS"))
        assertEquals("font/woff", WebRules.mimeFor("a/b.woff"))
        assertEquals("font/ttf", WebRules.mimeFor("a/b.ttf"))
        assertEquals("image/svg+xml", WebRules.mimeFor("a/b.svg"))
        assertEquals("application/octet-stream", WebRules.mimeFor("a/noextension"))
        assertEquals("application/octet-stream", WebRules.mimeFor("a.b/noextension"))
    }
}
