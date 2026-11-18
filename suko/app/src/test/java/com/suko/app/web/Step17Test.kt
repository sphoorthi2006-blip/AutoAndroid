package com.suko.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64

class Step17Test {
    private fun tmp(): File = Files.createTempDirectory("suko-web").toFile()

    private class MemTarget(val name: String) : SaveTarget {
        override val out = ByteArrayOutputStream()
        override val where: String get() = "Download/Suko/$name"
        var committed = false
        var aborted = false
        override fun commit() { committed = true }
        override fun abort() { aborted = true }
    }

    @Test
    fun aNativeAnswerIsReadOnceAndUnknownMethodsAreRefused() {
        val core = BridgeCore(NativeMethods.defaults(), tmp())
        val r = core.run("c1", "health", "")
        assertTrue(r.ok)
        assertEquals("""{"engine":"suko-native","ready":false}""", String(core.take("c1")!!))
        assertNull(core.take("c1"))                                  // one-time
        assertFalse(core.run("c2", "nope", "").ok)
        assertFalse(core.run("../x", "health", "").ok)               // bad id
        assertNull(core.take("../x"))
        assertEquals("""{"chars":3000000}""", core.run("c3", "size", "a".repeat(3_000_000)).let { String(core.take("c3")!!) })
    }

    @Test
    fun anUnfinishedEngineSaysSoInsteadOfFailingSilently() {
        val core = BridgeCore(NativeMethods.defaults(), tmp())
        for (m in listOf("process", "panels")) {
            assertTrue(core.run("j$m", m, "AAAA").ok)
            val text = String(core.take("j$m")!!)
            assertTrue(text.contains("\"error\":\"not_ready\"") && text.contains("steps 26-28"))
        }
    }

    @Test
    fun aMethodThatThrowsGivesAFailureNotACrash() {
        val core = BridgeCore(mapOf("bad" to { _: String -> throw IllegalStateException("boom") }), tmp())
        val r = core.run("j1", "bad", "")
        assertFalse(r.ok)
        assertEquals("boom", r.message)
        assertNull(core.take("j1"))
    }

    @Test
    fun aFileSavedInChunksArrivesByteForByte() {
        val targets = HashMap<String, MemTarget>()
        val reg = SaveRegistry { name, _ -> MemTarget(name).also { targets[name] = it } }
        val data = ByteArray(7000) { (it % 251).toByte() }
        reg.begin("s1", "../../evil/out:put?.mp4", "video/mp4")
        for (at in data.indices step 3000) reg.chunk("s1", Base64.getEncoder().encodeToString(data.copyOfRange(at, minOf(at + 3000, data.size))))
        assertEquals("Download/Suko/output.mp4", reg.end("s1"))       // path parts and illegal characters removed
        val t = targets.getValue("output.mp4")
        assertTrue(t.committed && !t.aborted)
        assertTrue(t.out.toByteArray().contentEquals(data))
    }

    @Test
    fun aBrokenSaveIsRemovedAndUnknownIdsAreRefused() {
        var target: MemTarget? = null
        val reg = SaveRegistry { n, _ -> MemTarget(n).also { target = it } }
        reg.begin("s1", "a.txt", "")
        runCatching { reg.chunk("s1", "!!!not base64!!!") }
        assertTrue(target!!.aborted && !target!!.committed)           // half file never stays
        assertTrue(runCatching { reg.end("s1") }.isFailure)
        assertTrue(runCatching { reg.chunk("nope", "AAAA") }.isFailure)
        reg.begin("s2", "b.txt", "")
        reg.abortAll()
        assertTrue(target!!.aborted)
        assertTrue(runCatching { reg.begin("../x", "c", "") }.isFailure)
    }

    @Test
    fun fileNamesAndJsStringsAreSafe() {
        assertEquals("file", WebRules.safeFileName("..."))
        assertEquals("a b.png", WebRules.safeFileName("C:\\x\\a b.png"))
        assertTrue(WebRules.safeFileName("x".repeat(300) + ".mp4").length <= 100)
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", WebRules.jsString("a\"b\\c\nd\u0001"))
        assertEquals("\"\\u2028\"", WebRules.jsString("\u2028"))
    }

    @Test
    fun theFilesGivenToThePageAreListedAndOpened() {
        val reg = InputRegistry(listOf(InputFile("hello.txt", "text/plain", 10) { ByteArrayInputStream("hello suko".toByteArray()) }))
        assertEquals("""[{"name":"hello.txt","mime":"text/plain","size":10}]""", reg.listJson())
        assertEquals("hello suko", String(reg.get(0)!!.open().readBytes()))
        assertNull(reg.get(1))
        reg.set(emptyList())
        assertEquals("[]", reg.listJson())
        assertNotNull(WebInputs.files)
    }
}
