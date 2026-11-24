package com.suko.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Step7Test {
    private fun tmp(): File = Files.createTempDirectory("suko").toFile()

    @Test
    fun mergesLooseFilesFoldersAndNestedZipsIntoOneList() {
        val inner = makeZip("deep/3.png" to byteArrayOf(3, 3, 3))
        val outerZip = makeZip("2.png" to byteArrayOf(2, 2), "bundle.zip" to inner, "notes.txt" to "hi".toByteArray())
        val sources = listOf(
            memFile("master.srt", "x".toByteArray()),
            memDir("proj", memFile("1.png", byteArrayOf(1)), memDir("sub", memFile("pack.zip", outerZip))),
        )
        val work = tmp()
        val r = Intake.scan(sources, work)
        assertEquals(
            listOf("master.srt", "proj/1.png", "proj/sub/pack.zip/2.png", "proj/sub/pack.zip/bundle.zip/deep/3.png", "proj/sub/pack.zip/notes.txt"),
            r.entries.map { it.path }.sorted(),
        )
        val three = r.entries.first { it.name == "3.png" }
        assertEquals(null, three.node)
        assertEquals(listOf<Byte>(3, 3, 3), three.file!!.readBytes().toList())
        assertEquals("3.png", three.file!!.name)
        assertTrue("loose and folder files stay where they are", r.entries.first { it.name == "1.png" }.node != null)
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun everythingNotUsedIsReportedWithAReason() {
        val zip = makeZip("__MACOSX/._a.png" to ByteArray(2), "b.docx" to ByteArray(2), "c.png" to ByteArray(2), "d.json" to ByteArray(2))
        val sources = listOf(memFile("pack.zip", zip), memFile("e.xyz"), memFile("f.png"))
        val r = Intake.scan(sources, tmp(), wanted = setOf(FileKind.IMAGE))
        assertEquals(listOf("f.png", "pack.zip/c.png"), r.entries.map { it.path }.sorted())
        val reasons = r.problems.associate { it.path to it.reason }
        assertEquals("hidden or system file", reasons["pack.zip/__MACOSX/._a.png"])
        assertEquals("file type not supported", reasons["pack.zip/b.docx"])
        assertEquals(Intake.NOT_USED, reasons["pack.zip/d.json"])
        assertEquals("file type not supported", reasons["e.xyz"])
    }

    @Test
    fun oneBudgetCoversAllSourcesAndStopsWithAReason() {
        val zip = makeZip(*Array(6) { "$it.png" to ByteArray(1) })
        val sources = listOf(memDir("f", memFile("a.png"), memFile("b.png")), memFile("z.zip", zip))
        val r = Intake.scan(sources, tmp(), limits = Limits(maxEntries = 4))
        assertEquals(4, r.entries.size)
        assertTrue(r.problems.any { it.reason.contains("too many files") })
    }

    @Test
    fun aBrokenZipAndAnUnwritableWorkFolderAreProblemsNotCrashes() {
        val bad = Intake.scan(listOf(memFile("bad.zip", "no zip".toByteArray())), tmp())
        assertTrue(bad.entries.isEmpty())
        assertTrue(bad.problems.any { it.path == "bad.zip" })
        val work = tmp()
        File(work, "0").writeText("a file where a folder is needed")
        val zip = makeZip("1.png" to ByteArray(10))
        val r = Intake.scan(listOf(memFile("z.zip", zip)), work)
        assertTrue(r.entries.isEmpty())
        assertTrue(r.problems.any { it.reason.contains("could not unpack") })
    }

    @Test
    fun summaryGroupsByKindAndByReason() {
        val files = Array(12) { memFile("${12 - it}.png") }
        val sources = listOf(memDir("imgs", *files), memFile("v.mp3"), memFile("a.srt"), memFile("a.zzz"), memFile("b.zzz"), memFile("~x"))
        val r = Intake.scan(sources, tmp())
        val sum = IntakeSummaries.of(r)
        assertEquals("Found 12 pictures, 1 audio file, 1 SRT file. Skipped or failed: 3.", sum.headline)
        assertEquals(listOf("1.png", "2.png", "3.png", "4.png", "5.png", "6.png"), sum.groups[0].names)
        assertEquals(12, sum.groups[0].count)
        val g = sum.skipped.first { it.reason == "file type not supported" }
        assertEquals(3, g.count)
        assertEquals("Nothing usable found.", IntakeSummaries.of(IntakeResult()).headline)
    }

    @Test
    fun resultsCanBeAddedTogether() {
        val a = Intake.scan(listOf(memFile("1.png")), tmp())
        val b = Intake.scan(listOf(memFile("2.png"), memFile("x.xyz")), tmp())
        val both = a + b
        assertEquals(2, both.entries.size)
        assertEquals(1, both.problems.size)
    }
}
