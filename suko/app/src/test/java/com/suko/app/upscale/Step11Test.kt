package com.suko.app.upscale

import com.suko.app.intake.FileKind
import com.suko.app.intake.IntakeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class Step11Test {
    private fun tmp(): File = Files.createTempDirectory("suko-up").toFile()
    private fun h(s: String) = UpscaleKeys.contentHash(ByteArrayInputStream(s.toByteArray()))
    private fun img(name: String, size: Long = 10) = IntakeEntry(name, FileKind.IMAGE, size, null, File(name))

    @Test
    fun keyDependsOnContentAndSettingsOnly() {
        val on = UpscaleSettings(removeMark = true)
        assertEquals(UpscaleKeys.key(h("aaa"), on), UpscaleKeys.key(h("aaa"), UpscaleSettings(true)))
        assertTrue(UpscaleKeys.key(h("aaa"), on) != UpscaleKeys.key(h("aab"), on))
        assertTrue(UpscaleKeys.key(h("aaa"), on) != UpscaleKeys.key(h("aaa"), UpscaleSettings(false)))
        assertTrue(UpscaleKeys.key(h("aaa"), on) != UpscaleKeys.key(h("aaa"), UpscaleSettings(true, scale = 4)))
        assertTrue(UpscaleKeys.key(h("aaa"), on) != UpscaleKeys.key(h("aaa"), UpscaleSettings(true, pipeline = "v2")))
        assertEquals(32, UpscaleKeys.key(h("aaa"), on).length)
    }

    @Test
    fun everyPictureGetsAJobWhateverItsSizeAndOtherKindsAreIgnored() {
        val cache = UpscaleCache(tmp())
        val entries = listOf(
            img("1.png", 10), img("2.png", 900_000_000L), img("3.jpg", 5),
            IntakeEntry("a.srt", FileKind.SRT, 5, null, File("a.srt")),
        )
        val plan = UpscalePlan.build(entries, cache, UpscaleSettings(true)) { e -> h(e.name) }
        assertEquals(3, plan.total)
        assertEquals(3, plan.todo.size)
        assertEquals(0, plan.cachedCount)
    }

    @Test
    fun finishedResultsAreReusedAndIdenticalPicturesAreDoneOnce() {
        val cache = UpscaleCache(tmp())
        val s = UpscaleSettings(true)
        val entries = listOf(img("a/1.png"), img("b/1copy.png"), img("2.png"))
        val hashOf = { e: IntakeEntry -> if (e.name == "2.png") h("two") else h("one") }
        val first = UpscalePlan.build(entries, cache, s, hashOf)
        assertEquals(2, first.todo.size)                 // 3 pictures, 2 distinct contents
        val key2 = first.jobs.first { it.entry.name == "2.png" }.key
        cache.tempFor(key2).writeBytes(ByteArray(100))
        assertTrue(cache.commit(key2))
        val second = UpscalePlan.build(entries, cache, s, hashOf)
        assertEquals(1, second.cachedCount)
        assertEquals(1, second.todo.size)
        assertEquals("1.png", second.todo[0].entry.name)
        val other = UpscalePlan.build(entries, cache, UpscaleSettings(false), hashOf)
        assertEquals("a different setting must not reuse the result", 0, other.cachedCount)
    }

    @Test
    fun halfWrittenResultsAreNeverUsed() {
        val cache = UpscaleCache(tmp())
        cache.tempFor("k1").writeBytes(ByteArray(50))
        assertFalse(cache.has("k1"))
        cache.fileFor("k2").writeBytes(ByteArray(0))
        assertFalse("an empty file is not a result", cache.has("k2"))
        assertFalse("nothing to commit", cache.commit("missing"))
        assertEquals(1, cache.cleanLeftovers())
        assertFalse(cache.tempFor("k1").exists())
        cache.tempFor("k3").writeBytes(ByteArray(7))
        cache.discard("k3")
        assertFalse(cache.tempFor("k3").exists())
    }

    @Test
    fun trimRemovesOldestFirstAndKeepsTheCurrentProject() {
        val cache = UpscaleCache(tmp())
        for ((i, k) in listOf("old", "mid", "new").withIndex()) {
            cache.fileFor(k).writeBytes(ByteArray(100))
            cache.fileFor(k).setLastModified(1_000_000L + i * 10_000L)
        }
        assertEquals(300L, cache.sizeBytes())
        assertEquals(1, cache.trimTo(250))
        assertFalse(cache.has("old"))
        assertEquals(1, cache.trimTo(100, keep = setOf("mid")))      // "new" goes, "mid" is protected
        assertTrue(cache.has("mid"))
        assertFalse(cache.has("new"))
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
    }
}
