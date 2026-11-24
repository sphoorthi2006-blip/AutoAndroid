package com.cuevideo.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TimingResult(
    val starts: DoubleArray,        // seconds, one per master cue
    val exact: BooleanArray,        // true = start came from a matched spoken word
    val ratio: Double?,             // word match 0..1, null when there is no original SRT
) {
    val estimated: List<Int> get() = exact.indices.filter { !exact[it] }.map { it + 1 }
}

object Timing {
    private val WATERMARK = Regex(
        "\\(?\\s*transcribed\\s+by\\s+turbo\\s*scribe\\.?\\s*" +
            "(?:go\\s+unlimited\\s+to\\s+remove\\s+this\\s+message\\.?)?\\s*\\)?",
        RegexOption.IGNORE_CASE
    )
    private val GO_UNLIMITED = Regex(
        "go\\s+unlimited\\s+to\\s+remove\\s+this\\s+message\\.?", RegexOption.IGNORE_CASE
    )
    private val TAGS = Regex(
        "[\\[(](?:music|applause|laughter|silence|noise)[\\])]", RegexOption.IGNORE_CASE
    )
    private val SPACES = Regex("\\s+")
    private val WORD = Regex("[a-z0-9']+")

    fun cleanOriginal(t: String): String {
        var x = WATERMARK.replace(t, " ")
        x = GO_UNLIMITED.replace(x, " ")
        x = TAGS.replace(x, " ")
        return SPACES.replace(x, " ").trim()
    }

    /** ASCII words only, lower-case. Same as desktop words(). */
    fun words(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }.toList()

    /** Port of desktop slide_starts(). A slide starts when its first word is spoken. */
    fun slideStarts(master: List<Cue>, original: List<Cue>?): TimingResult {
        val n = master.size
        if (original == null || original.isEmpty()) {
            return TimingResult(DoubleArray(n) { master[it].start }, BooleanArray(n) { true }, null)
        }

        // word timeline of the original: cue time shared out by word length
        val oWords = ArrayList<String>()
        val oTimes = ArrayList<Double>()
        val oEnds = ArrayList<Double>()
        for (c in original) {
            val ws = words(cleanOriginal(c.text))
            if (ws.isEmpty()) continue
            var total = ws.sumOf { it.length }
            if (total == 0) total = 1
            var t = c.start
            for (w in ws) {
                oWords.add(w)
                oTimes.add(t)
                t += (c.end - c.start) * w.length / total
                oEnds.add(t)
            }
        }

        val mWords = ArrayList<String>()
        val mSlide = ArrayList<Int>()
        for ((i, c) in master.withIndex()) for (w in words(c.text)) {
            mWords.add(w); mSlide.add(i)
        }

        val wordTime = HashMap<Int, Double>()
        val wordEnd = HashMap<Int, Double>()
        var matched = 0
        for (blk in SeqMatcher(mWords, oWords).matchingBlocks()) {
            for (k in 0 until blk.size) {
                wordTime[blk.a + k] = oTimes[blk.b + k]
                wordEnd[blk.a + k] = oEnds[blk.b + k]
                matched++
            }
        }
        val ratio = matched.toDouble() / max(1, mWords.size)

        val starts = arrayOfNulls<Double>(n)
        val spokenEnd = arrayOfNulls<Double>(n)
        var idx = 0
        for (i in 0 until n) {
            var first: Double? = null
            while (idx < mSlide.size && mSlide[idx] == i) {
                if (first == null && wordTime.containsKey(idx)) first = wordTime[idx]
                if (wordEnd.containsKey(idx)) spokenEnd[i] = wordEnd[idx]
                idx++
            }
            starts[i] = first
        }
        val exact = BooleanArray(n) { starts[it] != null }

        if (starts[0] == null) starts[0] = 0.0
        val known = (0 until n).filter { starts[it] != null }
        for (p in 0 until known.size - 1) {
            val a = known[p]
            val b = known[p + 1]
            val ma = master[a].start
            val mb = master[b].start
            val sa = starts[a]!!
            val sb = starts[b]!!
            for (k in a + 1 until b) {
                var t = if (mb > ma) sa + (sb - sa) * (master[k].start - ma) / (mb - ma)
                else sa + (sb - sa) * (k - a) / (b - a)
                val se = spokenEnd[a]
                var lo: Double? = if (k == a + 1 && se != null && se != 0.0) se else null
                if (lo == null) lo = starts[k - 1]!! + 0.05
                t = max(t, min(lo, sb - 0.05))
                starts[k] = min(t, sb - 0.05)
            }
        }
        val last = known.last()
        for (k in last + 1 until n) {
            starts[k] = starts[last]!! + (master[k].start - master[last].start)
        }

        starts[0] = 0.0
        val out = DoubleArray(n) { starts[it]!! }
        for (i in 1 until n) {
            if (out[i] < out[i - 1] + 0.05) out[i] = out[i - 1] + 0.05
        }
        return TimingResult(out, exact, ratio)
    }

    /**
     * Frame numbers (section 8). Returns n+1 entries: slide i shows frames bf[i] until bf[i+1]-1,
     * and the last entry is the total frame count. Rounding is half-to-even like Python.
     */
    fun frameBounds(starts: DoubleArray, total: Double, fps: Int): IntArray {
        val n = starts.size
        val bf = IntArray(n + 1)
        for (i in 0 until n) bf[i] = Math.rint(starts[i] * fps).toInt()
        bf[n] = max(1, Math.rint(total * fps).toInt())
        for (i in 1..n) if (bf[i] <= bf[i - 1]) bf[i] = bf[i - 1] + 1
        return bf
    }

    /** The "biggest shifts" list: more than 1 s from the master time, top 6, largest first. */
    fun biggestShifts(master: List<Cue>, starts: DoubleArray): List<Pair<Int, Double>> =
        master.indices
            .map { Pair(abs(starts[it] - master[it].start), it) }
            .sortedWith(compareByDescending<Pair<Double, Int>> { it.first }.thenByDescending { it.second })
            .filter { it.first > 1.0 }
            .take(6)
            .map { Pair(it.second + 1, starts[it.second] - master[it.second].start) }

    /** Slides shorter than 1 s that are not under a scene clip (1-based cue numbers). */
    fun shortSlides(starts: DoubleArray, total: Double, covered: Set<Int>): List<Int> {
        val n = starts.size
        val out = ArrayList<Int>()
        for (i in 0 until n) {
            val d = (if (i < n - 1) starts[i + 1] else total) - starts[i]
            if (d < 1.0 && (i + 1) !in covered) out.add(i + 1)
        }
        return out
    }
}
