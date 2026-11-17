package ils.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Settings of the handwriting reveal. Nothing about timing is fixed here: the caller says how long the writing takes. */
data class WriteSettings(
    /** Share of the writing time spent on pen strokes. The rest fades in anything the pen missed (tiny leftovers). */
    val penShare: Double = 0.92,
    /** Pause between letters, in pen widths of pen travel (so a letter with a short stroke still takes some time). */
    val letterPause: Double = 1.5,
    /** A piece smaller than this share of the typical letter height is a mark (i-dot, full stop, accent) and is written right after its letter. */
    val markHeight: Double = 0.4,
)

/** One pen stroke: the points to follow, the pen width, and when it is written (fractions 0..1 of the pen phase). */
class WStroke(val pts: List<Pt>, val pen: Double, val length: Double, val begin: Double, val dur: Double)

/** What to draw at one moment: pen-wide lines that uncover the ink, and how much of the whole ink is shown (the last fade). */
class WriteFrame(val lines: List<Pair<List<Pt>, Double>>, val finish: Double)

class WritePlan(val strokes: List<WStroke>, val settings: WriteSettings) {
    /** u = 0..1 of the whole writing time. Everything is relative, so any duration works. */
    fun frame(u: Double): WriteFrame {
        val phase = (u / settings.penShare).coerceIn(0.0, 1.0)
        val lines = ArrayList<Pair<List<Pt>, Double>>()
        for (s in strokes) {
            if (phase <= s.begin) break
            val f = ((phase - s.begin) / s.dur).coerceIn(0.0, 1.0)
            lines.add((if (f >= 1.0) s.pts else partial(s.pts, f)) to s.pen)
        }
        val finish = ((u - settings.penShare) / (1.0 - settings.penShare)).coerceIn(0.0, 1.0)
        return WriteFrame(lines, finish)
    }

    companion object {
        /** The first `frac` of a polyline by length. */
        fun partial(p: List<Pt>, frac: Double): List<Pt> {
            if (p.size < 2) return p
            val total = Pen.polyLength(p)
            var left = total * frac
            val out = ArrayList<Pt>()
            out.add(p[0])
            for (i in 1 until p.size) {
                val seg = kotlin.math.hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y)
                if (seg >= left) {
                    val t = if (seg == 0.0) 0.0 else left / seg
                    out.add(Pt(p[i - 1].x + (p[i].x - p[i - 1].x) * t, p[i - 1].y + (p[i].y - p[i - 1].y) * t))
                    return out
                }
                left -= seg
                out.add(p[i])
            }
            return out
        }
    }
}

/**
 * Writes text that already exists as pixels (a label or heading inside a generated image) like handwriting:
 * letters one after another, left to right and line by line, each followed along its centre line by a pen.
 * The ink is uncovered under the pen. It is never filled or wiped in.
 * This is the same pen engine as the desktop app's Text option.
 */
object Handwriting {
    private class Comp(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val pixels: IntArray) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
        val cx get() = (x0 + x1) / 2.0
        val cy get() = (y0 + y1) / 2.0
    }

    private fun components(bits: ByteArray, w: Int, h: Int): List<Comp> {
        val label = IntArray(w * h)
        val out = ArrayList<Comp>()
        val stack = IntArray(w * h)
        for (s in 0 until w * h) {
            if (bits[s].toInt() == 0 || label[s] != 0) continue
            var sp = 0
            stack[sp++] = s; label[s] = out.size + 1
            val px = ArrayList<Int>()
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (sp > 0) {
                val i = stack[--sp]; px.add(i)
                val x = i % w; val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (bits[j].toInt() != 0 && label[j] == 0) { label[j] = out.size + 1; stack[sp++] = j }
                }
            }
            out.add(Comp(x0, y0, x1, y1, px.toIntArray()))
        }
        return out
    }

    /** Reading order: lines top to bottom, letters left to right; small marks come right after the letter they belong to. */
    private fun readingOrder(cs: List<Comp>, st: WriteSettings): List<Comp> {
        if (cs.size < 2) return cs
        val heights = cs.map { it.h }.sorted()
        val typical = heights[(heights.size * 3 / 4).coerceAtMost(heights.size - 1)].toDouble()
        val marks = cs.filter { it.h < typical * st.markHeight && it.w < typical * 1.2 }
        val letters = cs.filter { it !in marks }.ifEmpty { return cs.sortedBy { it.cx } }
        // group letters into lines by vertical overlap
        class Line(var y0: Int, var y1: Int, val items: ArrayList<Comp> = ArrayList())
        val lines = ArrayList<Line>()
        for (c in letters.sortedBy { it.cy }) {
            val ln = lines.firstOrNull { l ->
                val ov = min(l.y1, c.y1) - max(l.y0, c.y0)
                ov >= 0.4 * min(l.y1 - l.y0 + 1, c.h)
            }
            if (ln == null) lines.add(Line(c.y0, c.y1, arrayListOf(c)))
            else { ln.items.add(c); ln.y0 = min(ln.y0, c.y0); ln.y1 = max(ln.y1, c.y1) }
        }
        lines.sortBy { it.y0 }
        val ordered = ArrayList<Comp>()
        for (ln in lines) {
            val row = ln.items.sortedBy { it.cx }.toMutableList()
            for (m in marks.sortedBy { it.cx }) {
                // belongs to this line when it sits in or just above/below the line band
                val near = m.cy >= ln.y0 - typical * 0.8 && m.cy <= ln.y1 + typical * 0.8
                if (!near) continue
                if (lines.any { o -> o !== ln && m.cy >= o.y0 - typical * 0.8 && m.cy <= o.y1 + typical * 0.8 && abs(m.cy - (o.y0 + o.y1) / 2.0) < abs(m.cy - (ln.y0 + ln.y1) / 2.0) }) continue
                val host = row.filter { it.w >= 1 }.maxByOrNull { min(it.x1, m.x1) - max(it.x0, m.x0) }
                val overlap = if (host == null) -1 else min(host.x1, m.x1) - max(host.x0, m.x0)
                if (host != null && overlap >= 0) row.add(row.indexOf(host) + 1, m)
                else { val at = row.indexOfFirst { it.cx > m.cx }; if (at < 0) row.add(m) else row.add(at, m) }
            }
            ordered.addAll(row)
        }
        for (m in marks) if (m !in ordered) ordered.add(m)   // marks far from every line: still written, last
        return ordered
    }

    /** bits: 1 = ink, 0 = background, bitmap size w x h. Points are in the bitmap's own pixel coordinates. */
    fun plan(bits: ByteArray, w: Int, h: Int, st: WriteSettings = WriteSettings()): WritePlan {
        val pad = 6
        class Part(val strokes: List<List<Pt>>, val pen: Double)
        val parts = ArrayList<Part>()
        for (c in readingOrder(components(bits, w, h), st)) {
            val cw = c.w + 2 * pad; val ch = c.h + 2 * pad
            val sub = ByteArray(cw * ch)
            for (i in c.pixels) sub[(i / w - c.y0 + pad) * cw + (i % w - c.x0 + pad)] = 1
            val sk = Pen.skeletonStrokes(sub, cw, ch)
            val dx = (c.x0 - pad).toDouble(); val dy = (c.y0 - pad).toDouble()
            parts.add(Part(sk.strokes.map { s -> s.map { Pt(it.x + dx, it.y + dy) } }, sk.pen))
        }
        // time is shared by pen travel, plus a small pause per letter
        var total = 0.0
        val weights = parts.map { p -> p.strokes.sumOf { Pen.polyLength(it) } + st.letterPause * p.pen }
        total = weights.sum().coerceAtLeast(1e-9)
        val out = ArrayList<WStroke>()
        var t = 0.0
        for ((k, p) in parts.withIndex()) {
            val lens = p.strokes.map { max(1.0, Pen.polyLength(it)) }
            val travel = lens.sum()
            val share = weights[k] / total
            val pause = st.letterPause * p.pen / weights[k] * share
            val usable = share - pause
            for ((i, s) in p.strokes.withIndex()) {
                val d = usable * lens[i] / travel
                out.add(WStroke(s, p.pen, lens[i], t, d))
                t += d
            }
            t += pause
        }
        return WritePlan(out, st)
    }
}
