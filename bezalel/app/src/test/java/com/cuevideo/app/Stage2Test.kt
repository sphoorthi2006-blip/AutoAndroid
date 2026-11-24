package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected numbers were produced by the desktop engine make_video.py (slide_starts). */
class Stage2Test {
    private val master = "1\n0:00:00,000 --> 0:00:04,000\nRome was a small city on a river.\n\n" +
        "2\n0:00:04,000 --> 0:00:06,000\n1. The first king\n\n" +
        "3\n0:00:06,000 --> 0:00:10,000\nHe built walls of stone and he ruled for years.\n\n" +
        "4\n0:00:10,000 --> 0:00:12,000\nPlease subscribe\n\n" +
        "5\n0:00:12,000 --> 0:00:16,000\nThen came the empire."
    private val original = "1\n0:00:00,000 --> 0:00:02,000\n(Transcribed by TurboScribe. Go Unlimited to remove this message.)\n\n" +
        "2\n0:00:00,500 --> 0:00:04,500\nRome was a small city [Music] on a river\n\n" +
        "3\n0:00:05,000 --> 0:00:09,000\nhe built walls of stone and he ruled\n\n" +
        "4\n0:00:11,000 --> 0:00:15,000\nThen came the big empire"

    @Test fun matchesDesktop() {
        val r = Timing.slideStarts(Srt.parseText(master), Srt.parseText(original))
        val want = doubleArrayOf(0.0, 4.5, 5.0, 9.0, 11.0)
        for (i in want.indices) assertEquals(want[i], r.starts[i], 1e-9)
        assertEquals(listOf(2, 4), r.estimated)
        assertEquals(0.7142857142857143, r.ratio!!, 1e-12)
    }

    @Test fun noOriginalUsesMasterTimes() {
        val m = Srt.parseText(master)
        val r = Timing.slideStarts(m, null)
        assertEquals(6.0, r.starts[2], 1e-9)
        assertTrue(r.ratio == null)
        assertTrue(r.exact.all { it })
    }

    @Test fun cleaning() {
        assertEquals("hello", Timing.cleanOriginal("(Transcribed by Turbo Scribe. Go unlimited to remove this message.) Hello"
            .replace("Hello", "hello")))
        assertEquals("a b", Timing.cleanOriginal("a [Music] (applause) b"))
        assertEquals(listOf("it's", "caf", "1066"), Timing.words("It's café 1066!"))
    }

    @Test fun seqMatcherLikeDifflib() {
        // difflib: a=[a,b,c,d,e] b=[c,d,x,a,b] -> longest "a b"? both size 2; earliest in a wins
        val blocks = SeqMatcher(listOf("a", "b", "c", "d", "e"), listOf("c", "d", "x", "a", "b")).matchingBlocks()
        assertEquals(listOf(SeqMatcher.Block(0, 3, 2)), blocks)
    }

    @Test fun framesRoundHalfEven() {
        // 0.5 s * 1 fps = 0.5 -> 0 ; 1.5 -> 2 ; 2.5 -> 2  (Python round)
        val bf = Timing.frameBounds(doubleArrayOf(0.5, 1.5, 2.5), 10.0, 1)
        assertEquals(listOf(0, 2, 3, 10), bf.toList())   // 2.5 -> 2 but must stay > previous (2) -> 3
    }
}
