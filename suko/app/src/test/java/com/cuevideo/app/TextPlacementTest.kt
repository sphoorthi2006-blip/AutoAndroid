package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 1 of the text-placement work: range, checks and export plan. No drawing yet. */
class TextPlacementTest {
    private fun cue(i: Int, text: String) = Cue(i * 2.0, i * 2.0 + 2, text)
    private val cues = (0 until 8).map { cue(it, "words $it") }
    private val bf = IntArray(9) { it * 60 }
    private fun imgs(vararg n: Int) = n.map { ImageEntry("$it.jpg", "file:///$it.jpg") }

    @Test fun rangeParsing() {
        assertEquals(Pair(123, 125), TextPlacement.parseRange("123-125"))
        assertEquals(Pair(123, 125), TextPlacement.parseRange(" 123 \u2013 125 "))
        assertEquals(Pair(125, 125), TextPlacement.parseRange("125"))
        assertNull(TextPlacement.parseRange("abc"))
        assertNull(TextPlacement.parseRange("1-"))
    }

    @Test fun defaultsAreBlackBackgroundWhiteText() {
        val t = TextPlacement(2, 3, "x")
        assertTrue(t.dark)
        assertFalse(t.backgroundWhite)
        assertEquals(TextAlign.CENTER, t.align)
    }

    @Test fun textCuesNeedNoImage() {
        val t = listOf(TextPlacement(3, 5, "In the beginning"))
        val chk = Checker.check(cues, imgs(1, 2, 6, 7, 8), emptyList(), texts = t)
        assertTrue(chk.canExport)
        assertEquals(setOf(3, 4, 5), chk.textCovered)
        assertTrue(chk.missing.isEmpty())
    }

    @Test fun imagesUnderTextAreIgnoredWithWarning() {
        val chk = Checker.check(cues, imgs(1, 2, 3, 4, 5, 6, 7, 8), emptyList(),
            texts = listOf(TextPlacement(3, 4, "hi")))
        assertTrue(chk.canExport)
        assertTrue(chk.warnings.any { it.contains("under a text") && it.contains("3, 4") })
    }

    @Test fun overlapWithClipIsAnError() {
        val chk = Checker.check(cues, imgs(1, 2, 3, 4, 5, 6, 7, 8), listOf(ClipEntry("4-5.mp4", "v/1")),
            texts = listOf(TextPlacement(3, 4, "hi")))
        assertFalse(chk.canExport)
        assertTrue(chk.errors.any { it.contains("scene clip") })
    }

    @Test fun overlapBetweenTextsIsAnError() {
        val chk = Checker.check(cues, imgs(1, 2, 3, 4, 5, 6, 7, 8), emptyList(),
            texts = listOf(TextPlacement(2, 4, "a"), TextPlacement(4, 6, "b")))
        assertFalse(chk.canExport)
        assertTrue(chk.errors.any { it.contains("another text") })
    }

    @Test fun badRangesAndEmptyTextAreErrors() {
        val c = { t: TextPlacement -> Checker.check(cues, imgs(1, 2, 3, 4, 5, 6, 7, 8), emptyList(), texts = listOf(t)) }
        assertFalse(c(TextPlacement(0, 2, "a")).canExport)
        assertFalse(c(TextPlacement(5, 4, "a")).canExport)
        assertFalse(c(TextPlacement(7, 9, "a")).canExport)
        assertFalse(c(TextPlacement(2, 3, "   ")).canExport)
    }

    @Test fun headingInsideTextRangeIsNotCountedAsHeading() {
        val hc = cues.toMutableList().also { it[2] = cue(2, "1. A heading") }
        val chk = Checker.check(hc, imgs(1, 2, 3, 4, 5, 6, 7, 8), emptyList(),
            texts = listOf(TextPlacement(3, 3, "mine")))
        assertEquals(0, chk.headingCues)
        val plan = ExportPlanner.build(hc, bf, chk)
        assertEquals(SegKind.TEXT, plan.segments[2].kind)
    }

    @Test fun planMakesOneTextSegmentOverTheRange() {
        val tp = TextPlacement(3, 5, "  In the beginning  ", dark = false, align = TextAlign.LEFT)
        val chk = Checker.check(cues, imgs(1, 2, 6, 7, 8), emptyList(), texts = listOf(tp))
        val plan = ExportPlanner.build(cues, bf, chk)
        assertEquals(listOf(SegKind.STILL, SegKind.STILL, SegKind.TEXT, SegKind.STILL, SegKind.STILL, SegKind.STILL),
            plan.segments.map { it.kind })
        val seg = plan.segments[2]
        assertEquals(3, seg.firstCue); assertEquals(5, seg.lastCue)
        assertEquals(120, seg.startFrame); assertEquals(300, seg.endFrame)
        assertEquals("In the beginning", seg.text)
        assertNotNull(seg.placement)
        assertEquals(480, plan.totalFrames)
        // nothing is lost or doubled
        assertEquals(plan.totalFrames, plan.segments.sumOf { it.frames })
    }

    @Test fun reportNamesTextSource() {
        val chk = Checker.check(cues, imgs(1, 2, 6, 7, 8), emptyList(), texts = listOf(TextPlacement(3, 5, "x")))
        val plan = ExportPlanner.build(cues, bf, chk)
        val csv = ReportCsv.build(cues, bf, plan, chk, 30)
        assertEquals(3, Regex("TEXT \\(placed, handwritten\\)").findAll(csv).count())
    }

    @Test fun noTextsMeansOldBehaviour() {
        val chk = Checker.check(cues, imgs(1, 2, 3, 4, 5, 6, 7, 8), emptyList())
        assertTrue(chk.texts.isEmpty()); assertTrue(chk.textCovered.isEmpty())
        assertEquals(8, ExportPlanner.build(cues, bf, chk).segments.size)
    }
}
