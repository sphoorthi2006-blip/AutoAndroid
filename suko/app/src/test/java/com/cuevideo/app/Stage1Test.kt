package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage1Test {
    @Test fun srtBasics() {
        val txt = "\uFEFF1\r\n0:00:01,500 --> 0:00:03,250\r\nHello\r\nworld  \r\n\r\n" +
            "2\r\n00:00:04.000 --> 00:00:05.100\r\n1. First heading\r\n\r\n\r\n" +
            "junk block without timing\r\n"
        val cues = Srt.parseText(txt)
        assertEquals(2, cues.size)
        assertEquals(1.5, cues[0].start, 1e-9)
        assertEquals(3.25, cues[0].end, 1e-9)
        assertEquals("Hello world", cues[0].text)
        assertEquals(4.0, cues[1].start, 1e-9)
        assertEquals("1. First heading", cues[1].text)
    }

    @Test fun classify() {
        assertEquals(CueKind.SUBSCRIBE, CueClassifier.classify("Please SUBSCRIBE now"))
        assertEquals(CueKind.HEADING, CueClassifier.classify("  3) The third thing"))
        assertEquals(CueKind.HEADING, CueClassifier.classify("12. Rome"))
        assertEquals(CueKind.NORMAL, CueClassifier.classify("3.5 million people"))
        assertEquals(CueKind.NORMAL, CueClassifier.classify("1."))
        assertEquals(CueKind.SUBSCRIBE, CueClassifier.classify("1. Subscribe"))
    }

    @Test fun clipNames() {
        assertEquals(Pair(53, 53), CueNames.clipRange("53.mp4"))
        assertEquals(Pair(53, 53), CueNames.clipRange("n53.mp4"))
        assertEquals(Pair(53, 53), CueNames.clipRange("anything_53.mp4"))
        assertEquals(Pair(12, 14), CueNames.clipRange("12-14.mp4"))
        assertEquals(Pair(7, 7), CueNames.clipRange("scene_007.mov"))
        assertEquals(Pair(12, 14), CueNames.clipRange("take2_12-14.mp4"))
        assertEquals(Pair(9, 9), CueNames.clipRange("12-14_then9.mp4"))
        assertEquals(null, CueNames.clipRange("intro.mp4"))
    }

    @Test fun imageNames() {
        assertEquals(1, CueNames.imageCue("1.jpg"))
        assertEquals(54, CueNames.imageCue("054.png"))
        assertEquals(null, CueNames.imageCue("cover.png"))
        assertTrue(isJunkName("__MACOSX/1.jpg"))
        assertTrue(isJunkName("a/.hidden.png"))
        assertFalse(isJunkName("a/1.png"))
    }

    private fun cues(vararg t: String) = t.mapIndexed { i, s -> Cue(i.toDouble(), i + 1.0, s) }
    private fun imgs(vararg n: String) = n.map { ImageEntry(it, "u/$it") }

    @Test fun missingImages() {
        val c = cues("a", "b", "2. head", "d", "Subscribe!")
        val r = Checker.check(c, imgs("1.jpg", "2.png", "5.jpg", "9.jpg", "x.png"), emptyList())
        assertEquals(listOf(3, 4), r.missing)
        assertEquals("Missing images: 3, 4", r.missingText)
        assertFalse(r.canExport)
        assertEquals(2, r.extraImages.size)
        assertEquals(1, r.headingCues)
        assertEquals(1, r.subscribeCues)
        val ok = Checker.check(c, imgs("1.jpg", "2.png", "3.png", "4.jpg", "5.jpg"), emptyList())
        assertTrue(ok.canExport)
    }

    @Test fun clipsReplaceImages() {
        val c = cues("a", "b", "c", "d", "e")
        val clips = listOf(ClipEntry("2-3.mp4", "v/1"))
        val r = Checker.check(c, imgs("1.jpg", "3.jpg", "4.jpg", "5.jpg"), clips)
        assertTrue(r.canExport)
        assertEquals(setOf(2, 3), r.covered)
        assertEquals(listOf(3), r.ignoredUnderClips)
        val r2 = Checker.check(c, imgs("1.jpg", "5.jpg"), clips)
        assertEquals(listOf(4), r2.missing)
    }

    @Test fun clipErrors() {
        val c = cues("a", "b", "c", "d", "e")
        val all = imgs("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg")
        val out = Checker.check(c, all, listOf(ClipEntry("9.mp4", "v/1")))
        assertFalse(out.canExport); assertEquals(1, out.errors.size)
        val back = Checker.check(c, all, listOf(ClipEntry("4-2.mp4", "v/1")))
        assertFalse(back.canExport)
        val dup = Checker.check(c, all, listOf(ClipEntry("1-3.mp4", "v/1"), ClipEntry("3.mp4", "v/2")))
        assertFalse(dup.canExport)
        assertTrue(dup.errors[0].contains("same cue"))
        val none = Checker.check(c, all, listOf(ClipEntry("intro.mp4", "v/1")))
        assertFalse(none.canExport)
        // hand correction fixes it
        val fixed = Checker.check(c, all, listOf(ClipEntry("intro.mp4", "v/1")),
            mapOf("v/1" to Pair(2, 2)))
        assertTrue(fixed.canExport)
        assertEquals("intro.mp4 -> cue 2", fixed.clipRows[0].label)
    }

    @Test fun rolesGuess() {
        val a = FileInfo("voice.mp3", "u1", 500)
        val b = FileInfo("click.wav", "u2", 900)
        val m = FileInfo("story_master.srt", "u3", 10)
        val o = FileInfo("story.srt", "u4", 10)
        val g = Roles.guess(listOf(a, b, m, o)) { "" }
        assertEquals("voice.mp3", g.audio?.name)
        assertEquals("click.wav", g.click?.name)
        assertEquals("story_master.srt", g.master?.name)
        assertEquals("story.srt", g.original?.name)
        val x = FileInfo("a.srt", "u5", 10)
        val y = FileInfo("b.srt", "u6", 10)
        val body = "1\n0:00:00,000 --> 0:00:01,000\nhi\n\n2\n0:00:01,000 --> 0:00:02,000\nyo\n"
        val g2 = Roles.guess(listOf(x, y)) { if (it == x) "(Transcribed by TurboScribe)\n" + body else "1\n0:00:00,000 --> 0:00:01,000\nhi\n" }
        assertEquals("a.srt", g2.original?.name)
        assertEquals("b.srt", g2.master?.name)
        val g3 = Roles.guess(listOf(x, y)) { if (it == x) body else "1\n0:00:00,000 --> 0:00:01,000\nhi\n" }
        assertEquals("a.srt", g3.original?.name)
    }
}
