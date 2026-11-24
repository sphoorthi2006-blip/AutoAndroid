package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 of the text-placement work: layout maths of a placed text (drawing itself is checked on the phone). */
class TextLayoutTest {
    private fun fake(t: String, sz: Int) = t.length * sz * 0.47
    private fun lay(text: String, align: TextAlign = TextAlign.CENTER, scale: Double = 1.0, x: Double = 0.0, y: Double = 0.0) =
        TextLayout.layout(text, 1920, 1080, align, scale, x, y, ::fake)

    @Test fun shortTextIsOneCentredLineAtTopSize() {
        val l = lay("In the beginning")
        assertEquals(200, l.size)
        assertEquals(1, l.lines.size)
        assertEquals(960.0, l.lines[0].x, 1e-9)
        assertEquals(540.0, l.lines[0].cy, 1e-9)
    }

    @Test fun longVerseWrapsAndStaysInsideTheScreen() {
        val verse = "The Lord is my shepherd I shall not want He makes me lie down in green pastures he leads me beside quiet waters"
        val l = lay(verse)
        assertTrue(l.lines.size > 1)
        val maxW = 1920 * 0.86
        for (ln in l.lines) assertTrue(fake(ln.text, l.size) <= maxW + 1e-6)
        assertTrue(l.lines.size * l.size * 1.25 <= 1080 * 0.8 + 1e-6)
        // block is vertically centred: first and last line are mirror images around the middle
        assertEquals(1080.0, l.lines.first().cy + l.lines.last().cy, 1e-6)
    }

    @Test fun typedLineBreaksAreKept() {
        val l = lay("Psalm 23:1\nThe Lord")
        assertEquals(listOf("Psalm 23:1", "The Lord"), l.lines.map { it.text })
    }

    @Test fun leftAlignAnchorsAtTheLeftEdgeOfTheTextArea() {
        val l = lay("Hello world", TextAlign.LEFT)
        assertEquals((1920 - 1920 * 0.86) / 2.0, l.lines[0].x, 1e-9)
        assertEquals(TextAlign.LEFT, l.align)
    }

    @Test fun sizeScaleAndPositionMoveTheBlock() {
        val base = lay("Hello world")
        val big = lay("Hello world", scale = 0.5)
        assertEquals(base.size / 2, big.size)
        val moved = lay("Hello world", x = 0.1, y = -0.2)
        assertEquals(960.0 + 192.0, moved.lines[0].x, 1e-9)
        assertEquals(540.0 - 216.0, moved.lines[0].cy, 1e-9)
        // absurd values are clamped, never off the world
        val far = lay("Hello", x = 9.0, y = -9.0)
        assertEquals(960.0 + 960.0, far.lines[0].x, 1e-9)
        assertEquals(0.0, far.lines[0].cy, 1e-9)
    }

    @Test fun blankTextHasNoLines() {
        assertEquals(0, lay("   ").lines.size)
    }

    @Test fun placedTextClicksOnceAtStartLikeAClip() {
        val bf = IntArray(7) { it * 30 }                    // 6 cues, 1 s each at 30 fps
        // cues 2-3 and 6 are placed texts: clicks at cue 2 start, cue 4, cue 5, cue 6 start; none inside
        assertEquals(listOf(1.0, 3.0, 4.0, 5.0), ClickPlan.times(bf, listOf(Pair(2, 3), Pair(6, 6)), 30))
    }
}
