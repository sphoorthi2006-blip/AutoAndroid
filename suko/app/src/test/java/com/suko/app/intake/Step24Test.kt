package com.suko.app.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Step24Test {
    private fun scan(vararg s: MemNode) =
        Intake.scan(s.toList(), Files.createTempDirectory("svg").toFile(), SvgInputs.WANTED)

    @Test
    fun picturesAreSortedByNumberAndOneJsonMakesItReady() {
        val r = scan(memDir("p", memFile("10.png"), memFile("2.png"), memFile("1.jpg")), memFile("layers.json", ByteArray(50)))
        val i = SvgInputs.from(r)
        assertEquals(listOf("1.jpg", "2.png", "10.png"), i.images.map { it.name })
        assertEquals("layers.json", i.json!!.name)
        assertTrue(i.ready)
        assertTrue(i.warnings.isEmpty())
        assertEquals("Ready: 3 picture(s) and JSON \"layers.json\".", i.headline)
    }

    @Test
    fun missingPartsAreNamedInPlainWords() {
        assertEquals("Add the pictures and the JSON file.", SvgInputs.from(scan()).headline)
        val onlyImg = SvgInputs.from(scan(memFile("1.png")))
        assertFalse(onlyImg.ready)
        assertEquals("1 picture(s) found, but no JSON file yet.", onlyImg.headline)
        val onlyJson = SvgInputs.from(scan(memFile("a.json")))
        assertEquals("JSON found, but no pictures yet.", onlyJson.headline)
    }

    @Test
    fun severalJsonFilesUseTheLargestAndSayWhichWereLeftOut() {
        val r = scan(memFile("small.json", ByteArray(5)), memFile("big.json", ByteArray(500)), memFile("1.png"))
        val i = SvgInputs.from(r)
        assertEquals("big.json", i.json!!.name)
        assertEquals(listOf("small.json"), i.otherJson.map { it.name })
        assertTrue(i.warnings[0].contains("small.json"))
    }

    @Test
    fun otherFileTypesAreReportedAndInsideZipsWorkToo() {
        val zip = makeZip("1.png" to ByteArray(3), "plan.json" to ByteArray(9), "voice.mp3" to ByteArray(4), "notes.txt" to ByteArray(1))
        val r = scan(memFile("bundle.zip", zip))
        val i = SvgInputs.from(r)
        assertTrue(i.ready)
        assertEquals(2, r.problems.count { it.reason == Intake.NOT_USED })
    }

    @Test
    fun sameNamedPicturesGetAWarning() {
        val r = scan(memDir("a", memFile("1.png")), memDir("b", memFile("1.png")), memFile("x.json"))
        val i = SvgInputs.from(r)
        assertEquals(2, i.images.size)
        assertTrue(i.warnings.any { it.contains("same name") })
    }
}
