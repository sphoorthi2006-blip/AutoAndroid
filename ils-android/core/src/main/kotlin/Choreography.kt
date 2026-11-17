package ils.core

import kotlin.math.max
import kotlin.math.min

/**
 * One element on a slide with its times.
 * Picture part: pops in during [bodyStart, bodyEnd]. Label part: written by the pen during [labelStart, labelEnd].
 * Before its start an element is hidden; after its end it stays fully drawn until the slide ends.
 */
class PlacedElement(
    val d: Detected, val split: Split, val spec: ElementSpec?,
    val start: Double, val end: Double,
    val bodyStart: Double, val bodyEnd: Double,
    val labelStart: Double, val labelEnd: Double,
) {
    val hasBody get() = split.body != null
    val hasLabel get() = split.label != null && split.labelInk != null

    /** Pen strokes of the label, in the element box's own pixel coordinates. */
    val writePlan: WritePlan? by lazy { split.labelInk?.let { Handwriting.plan(it, d.w, d.h) } }

    fun bodyProgress(t: Double): Double = progress(t, bodyStart, bodyEnd, hasBody)
    fun labelProgress(t: Double): Double = progress(t, labelStart, labelEnd, hasLabel)

    private fun progress(t: Double, a: Double, b: Double, has: Boolean): Double {
        if (!has) return 0.0
        if (b <= a) return if (t >= a) 1.0 else 0.0
        return ((t - a) / (b - a)).coerceIn(0.0, 1.0)
    }
}

class SlidePlan(val slide: Slide, val placed: List<PlacedElement>, val warnings: List<String>) {
    /** What is on screen at time t, as numbers. When two moments give equal states the frames are identical. */
    fun state(t: Double): IntArray {
        val s = IntArray(placed.size * 2)
        for ((i, p) in placed.withIndex()) {
            s[2 * i] = (p.bodyProgress(t) * 1000).toInt()
            s[2 * i + 1] = (p.labelProgress(t) * 1000).toInt()
        }
        return s
    }
}

object Triggers {
    /** Time of the first word of the trigger phrase: in the named sentence first, then anywhere in the slide. Null if not found. */
    fun find(slide: Slide, sentence: Int, trigger: String): Double? {
        val toks = Words.split(trigger).map { Words.norm(it) }
        if (toks.isEmpty()) return null
        fun inSentence(s: Sentence): Double? {
            val ws = s.words
            for (i in 0..ws.size - toks.size) {
                if (toks.indices.all { Words.same(Words.norm(ws[i + it].text), toks[it]) }) return ws[i].start
            }
            return null
        }
        slide.sentences.getOrNull(sentence)?.let { s -> inSentence(s)?.let { return it } }
        for (s in slide.sentences) inSentence(s)?.let { return it }
        return null
    }
}

object Matcher {
    /** Matches element entries to detected elements by slot. Entries with no exact slot take the nearest unused detected element. */
    fun match(det: Detection, specs: List<ElementSpec>, warn: MutableList<String>): List<Pair<ElementSpec, Detected>> {
        val result = arrayOfNulls<Detected>(specs.size)
        val used = HashSet<Detected>()
        for ((i, s) in specs.withIndex()) {
            val d = det.elements.firstOrNull { it.row == s.row && it.col == s.col && it !in used }
            if (d != null) { result[i] = d; used.add(d) }
        }
        for ((i, s) in specs.withIndex()) {
            if (result[i] != null) continue
            val d = det.elements.filter { it !in used }
                .minByOrNull { kotlin.math.abs(it.row - s.row) * 100 + kotlin.math.abs(it.col - s.col) }
            if (d == null) { warn.add("No element left in the image for \"${s.label}\" (row ${s.row}, col ${s.col}); skipped."); continue }
            result[i] = d; used.add(d)
            warn.add("\"${s.label}\": nothing at row ${s.row}, col ${s.col}; used the element at row ${d.row}, col ${d.col}.")
        }
        val extra = det.elements.count { it !in used }
        if (extra > 0) warn.add("$extra element(s) found in the image have no entry in the list; they stay visible.")
        return specs.indices.mapNotNull { i -> result[i]?.let { specs[i] to it } }
    }
}

object Choreography {
    /**
     * Plans one slide. px = the slide's image as ARGB (w x h).
     * With element entries: elements start at their trigger words. Without: a heading image is written as handwriting,
     * every other image is shown as it is.
     */
    fun plan(slide: Slide, px: IntArray, w: Int, h: Int, specs: List<ElementSpec>, st: ElementSettings = ElementSettings()): SlidePlan {
        val warn = ArrayList<String>()
        if (specs.isEmpty()) {
            if (slide.kind != Kind.HEADING) return SlidePlan(slide, emptyList(), warn)
            val d = Elements.wholeInk(px, w, h, st) ?: return SlidePlan(slide, emptyList(), warn)
            val a = slide.start + st.headingDelaySec
            val b = a + min(st.headingMaxWriteSec, slide.duration * st.headingWriteShare)
            val sp = Elements.split(d, true, st)
            return SlidePlan(slide, listOf(PlacedElement(d, sp, null, a, b, a, a, a, b)), warn)
        }

        val det = Elements.detect(px, w, h, st)
        val matched = Matcher.match(det, specs, warn)
        val n = matched.size
        if (n == 0) return SlidePlan(slide, emptyList(), warn)

        // 1. start times from the trigger words
        val starts = DoubleArray(n)
        for ((k, m) in matched.withIndex()) {
            val t = Triggers.find(slide, m.first.sentence, m.first.trigger)
            if (t == null) {
                warn.add("Trigger \"${m.first.trigger}\" for \"${m.first.label}\" not found on image ${slide.image}; spread evenly.")
                starts[k] = slide.start + (k + 0.5) / n * slide.duration
            } else starts[k] = t
        }
        val order = (0 until n).sortedBy { starts[it] }       // narration order, stable
        val items = order.map { matched[it] }
        val s = DoubleArray(n) { starts[order[it]] }
        val lastAllowed = max(slide.start, slide.end - st.endMargin - st.minDrawSec)
        for (k in 0 until n) s[k] = s[k].coerceIn(slide.start, lastAllowed)
        for (k in 1 until n) s[k] = max(s[k], s[k - 1] + st.minStartGap)
        for (k in 0 until n) s[k] = min(s[k], lastAllowed)
        for (k in n - 2 downTo 0) s[k] = min(s[k], s[k + 1] - st.minStartGap)
        for (k in 0 until n) s[k] = max(s[k], slide.start)

        // 2. draw times (by type), cut short so each element ends before the next starts
        val placed = ArrayList<PlacedElement>()
        for ((k, m) in items.withIndex()) {
            val (spec, d) = m
            val sp = Elements.split(d, spec.isText, st)
            val letterSec = min(st.textSecPerLetter * sp.letters, st.textMaxSec)
            val wanted = when {
                sp.body == null -> min(st.textBaseSec + letterSec, st.textMaxSec)     // text only
                sp.label == null -> st.iconDrawSec                                    // picture only
                else -> st.iconDrawSec + letterSec                                    // picture + label
            }
            val limit = if (k < n - 1) s[k + 1] - st.gapBeforeNext else slide.end - st.endMargin
            val e = max(s[k] + st.minDrawSec, min(s[k] + wanted, limit))
            val split = if (sp.body != null && sp.label != null) s[k] + (e - s[k]) * st.iconBodyShare else s[k]
            val (bs, be) = if (sp.body == null) s[k] to s[k] else s[k] to (if (sp.label != null) split else e)
            val (ls, le) = if (sp.label == null) e to e else (if (sp.body != null) split else s[k]) to e
            placed.add(PlacedElement(d, sp, spec, s[k], e, bs, be, ls, le))
        }
        return SlidePlan(slide, placed, warn)
    }
}
