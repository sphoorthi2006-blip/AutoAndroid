package com.suko.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Helper shared by the step 4 and 5 tests: build a ZIP in memory. */
fun makeZip(vararg files: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
    val bo = ByteArrayOutputStream()
    ZipOutputStream(bo).use { z ->
        if (stored) z.setLevel(0)
        for ((n, b) in files) {
            z.putNextEntry(ZipEntry(n))
            z.write(b)
            z.closeEntry()
        }
    }
    return bo.toByteArray()
}

class Step4Test {
    @Test
    fun listsFilesWithKindAndRealSizeWithoutExtracting() {
        val zip = makeZip("1.png" to ByteArray(1000) { 1 }, "sub/script.txt" to ByteArray(10), "voice.mp3" to ByteArray(5))
        val r = ZipReader.list(ByteArrayInputStream(zip), "p.zip")
        assertEquals(listOf("p.zip/1.png", "p.zip/sub/script.txt", "p.zip/voice.mp3"), r.items.map { it.path })
        assertEquals(listOf(FileKind.IMAGE, FileKind.TEXT, FileKind.AUDIO), r.items.map { it.kind })
        assertEquals(1000L, r.items[0].size)
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun junkIsListedAsJunkAndUnsafePathsAreSkippedWithAReason() {
        val zip = makeZip("__MACOSX/._1.png" to ByteArray(3), "../evil.png" to ByteArray(3), "ok.png" to ByteArray(3))
        val r = ZipReader.list(ByteArrayInputStream(zip))
        assertEquals(listOf(FileKind.JUNK, FileKind.IMAGE), r.items.map { it.kind })
        assertEquals(1, r.problems.size)
        assertTrue(r.problems[0].reason.contains("unsafe"))
    }

    @Test
    fun aZipInsideAZipIsOnlyListedHere() {
        val inner = makeZip("a.png" to ByteArray(4))
        val r = ZipReader.list(ByteArrayInputStream(makeZip("inner.zip" to inner)))
        assertEquals(1, r.items.size)
        assertEquals(FileKind.ZIP, r.items[0].kind)
        assertEquals(inner.size.toLong(), r.items[0].size)
    }

    @Test
    fun notAZipAndDamagedZipGiveAProblemNotACrash() {
        val notZip = ZipReader.list(ByteArrayInputStream("hello".toByteArray()), "x.zip")
        assertTrue(notZip.items.isEmpty())
        assertEquals(1, notZip.problems.size)
        val good = makeZip("1.png" to ByteArray(50000) { (it % 251).toByte() }, "2.png" to ByteArray(50000) { (it % 241).toByte() })
        val cut = good.copyOf(good.size / 2)
        val r = ZipReader.list(ByteArrayInputStream(cut), "cut.zip")
        assertTrue(r.problems.isNotEmpty())
    }

    @Test
    fun entryLimitStopsWithAReasonAndKeepsWhatWasRead() {
        val zip = makeZip(*Array(10) { "$it.png" to ByteArray(1) })
        val r = ZipReader.list(ByteArrayInputStream(zip), "big.zip", Limits(maxEntries = 3))
        assertEquals(3, r.items.size)
        assertTrue(r.problems.any { it.reason.contains("too many files") })
    }
}
