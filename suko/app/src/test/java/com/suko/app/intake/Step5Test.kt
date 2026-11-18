package com.suko.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Step5Test {
    @Test
    fun opensZipsInsideZipsAndKeepsThePathChain() {
        val c = makeZip("3.png" to ByteArray(7))
        val b = makeZip("2.png" to ByteArray(6), "c.zip" to c)
        val a = makeZip("1.png" to ByteArray(5), "b.zip" to b)
        val r = NestedZip.scan(ByteArrayInputStream(a), "a.zip")
        assertEquals(
            listOf("a.zip/1.png", "a.zip/b.zip/2.png", "a.zip/b.zip/c.zip/3.png"),
            r.items.map { it.path }.sorted(),
        )
        assertTrue("inner ZIPs are expanded, not listed", r.items.none { it.kind == FileKind.ZIP })
        assertEquals(7L, r.items.first { it.path.endsWith("3.png") }.size)
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun depthLimitStopsOpeningButSaysSo() {
        val c = makeZip("3.png" to ByteArray(7))
        val b = makeZip("c.zip" to c)
        val a = makeZip("b.zip" to b)
        val r = NestedZip.scan(ByteArrayInputStream(a), "a.zip", Limits(maxDepth = 2))
        assertTrue(r.problems.any { it.path == "a.zip/b.zip/c.zip" && it.reason.contains("too deep") })
        assertTrue(r.items.none { it.path.endsWith("3.png") })
    }

    @Test
    fun aFakeOrBrokenInnerZipDoesNotStopTheOuterOne() {
        val a = makeZip("fake.zip" to "not a zip".toByteArray(), "1.png" to ByteArray(2))
        val r = NestedZip.scan(ByteArrayInputStream(a), "a.zip")
        assertEquals(listOf("a.zip/1.png"), r.items.map { it.path })
        assertTrue(r.problems.any { it.path == "a.zip/fake.zip" })
    }

    @Test
    fun totalSizeLimitStopsAZipBombEarly() {
        val bomb = makeZip("zeros.bin" to ByteArray(5_000_000))
        val r = NestedZip.scan(ByteArrayInputStream(bomb), "bomb.zip", Limits(maxTotalBytes = 1_000_000))
        assertTrue(r.problems.any { it.reason.contains("safety limit") })
    }

    @Test
    fun storedNestedZipsWorkToo() {
        val inner = makeZip("x.srt" to ByteArray(9), stored = true)
        val outer = makeZip("in.zip" to inner, stored = true)
        val r = NestedZip.scan(ByteArrayInputStream(outer), "o.zip")
        assertEquals(listOf("o.zip/in.zip/x.srt"), r.items.map { it.path })
        assertEquals(FileKind.SRT, r.items[0].kind)
    }
}
