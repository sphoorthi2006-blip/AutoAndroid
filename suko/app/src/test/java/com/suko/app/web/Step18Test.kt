package com.suko.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the built web bundle in the app's assets: every file it needs is there, is servable, and nothing points to the internet. */
class Step18Test {
    private val root = File("src/main/assets/web/studio")

    private fun refs(text: String, attr: String) = Regex("""$attr="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()

    @Test
    fun bothPagesAndEveryFileTheyNameArePresentAndServable() {
        for (page in listOf("index.html", "smoke.html")) {
            val f = File(root, page)
            assertTrue("$page is missing (run: cd web && npm ci && npm run build:suko)", f.isFile)
            val html = f.readText()
            val local = (refs(html, "src") + refs(html, "href")).filter { !it.startsWith("#") }
            assertTrue("$page references nothing", local.isNotEmpty() || page == "x")
            for (r in local) {
                assertFalse("$page points outside the app: $r", r.contains("://") || r.startsWith("//") || r.startsWith("/"))
                assertTrue("$page needs $r, which is missing", File(root, r).isFile)
                assertTrue(WebRules.isOurs("${WebConfig.ORIGIN}/web/studio/" + r.removePrefix("./")))
            }
        }
    }

    @Test
    fun everyBundledFileIsServedWithAUsefulType() {
        val files = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.size >= 8)     // step 20: only Patrick Hand + the symbol font are bundled now
        for (f in files) {
            val rel = f.relativeTo(File("src/main/assets")).path.replace('\\', '/')
            assertTrue("not servable: $rel", WebRules.route("${WebConfig.ORIGIN}/$rel") is Route.Asset)
            assertTrue("no MIME type for $rel", WebRules.mimeFor(rel) != "application/octet-stream")
        }
    }

    @Test
    fun theStylesheetNamesOnlyFilesThatExistAndNothingRemote() {
        val css = root.walkTopDown().filter { it.extension == "css" }.toList()
        assertTrue(css.isNotEmpty())
        for (c in css) {
            val text = c.readText()
            assertFalse("${c.name} loads something from the internet", Regex("""url\(\s*['"]?https?:""").containsMatchIn(text) || text.contains("@import url(http"))
            for (m in Regex("""url\(\s*['"]?([^'")]+)""").findAll(text)) {
                val u = m.groupValues[1]
                if (u.startsWith("data:")) continue
                assertTrue("${c.name} needs $u, which is missing", File(c.parentFile, u).isFile)
            }
        }
    }

    @Test
    fun theBundleContainsTheBridgeAndTheSmokeTestFindsItsParts() {
        val js = root.resolve("assets").listFiles { x -> x.extension == "js" }!!.joinToString("\n") { it.readText() }
        assertTrue(js.contains("__sukoDone") && js.contains("SukoBridge"))
        assertTrue(js.contains("/bridge/") && js.contains("/input/"))
        assertEquals(1, root.listFiles { x -> x.name == "index.html" }!!.size)
    }
}
