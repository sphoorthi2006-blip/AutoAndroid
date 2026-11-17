package ils.core

/** One spoken word from the SRT with an estimated time (cue edges are real, words inside a cue are spread by letter count). */
class SrtWord(val norm: String, val start: Double, val end: Double)

object Align {
    fun srtWords(cues: List<Cue>): List<SrtWord> {
        val out = ArrayList<SrtWord>()
        for (c in cues) {
            val ws = Words.split(c.text)
            if (ws.isEmpty()) continue
            val weights = ws.map { it.length + 1.0 }
            val total = weights.sum()
            var acc = 0.0
            for ((k, w) in ws.withIndex()) {
                val a = c.start + (c.end - c.start) * acc / total
                acc += weights[k]
                val b = c.start + (c.end - c.start) * acc / total
                out.add(SrtWord(Words.norm(w), a, b))
            }
        }
        return out
    }

    class Result(val start: DoubleArray, val end: DoubleArray, val matched: BooleanArray, val srtUnmatched: Int, val srtTotal: Int)

    /**
     * Matches the script's words to the spoken words (longest common subsequence) and gives every script word a time.
     * Script words that were not heard get a time between their matched neighbours.
     */
    fun align(script: List<String>, srt: List<SrtWord>, audioEnd: Double): Result {
        val n = script.size
        val m = srt.size
        val w = m + 1
        val dp = ShortArray((n + 1) * w)
        for (i in 1..n) {
            val a = script[i - 1]
            for (j in 1..m) {
                var v = maxOf(dp[(i - 1) * w + j], dp[i * w + j - 1]).toInt()
                if (Words.same(a, srt[j - 1].norm)) v = maxOf(v, dp[(i - 1) * w + j - 1] + 1)
                dp[i * w + j] = v.toShort()
            }
        }
        val matchOf = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val here = dp[i * w + j].toInt()
            if (Words.same(script[i - 1], srt[j - 1].norm) && here == dp[(i - 1) * w + j - 1] + 1) {
                matchOf[i - 1] = j - 1; i--; j--
            } else if (dp[(i - 1) * w + j] >= dp[i * w + j - 1]) i-- else j--
        }
        val start = DoubleArray(n)
        val end = DoubleArray(n)
        val matched = BooleanArray(n) { matchOf[it] >= 0 }
        for (k in 0 until n) if (matched[k]) { start[k] = srt[matchOf[k]].start; end[k] = srt[matchOf[k]].end }
        var k = 0
        val firstT = if (m > 0) srt[0].start else 0.0
        while (k < n) {
            if (matched[k]) { k++; continue }
            var e = k
            while (e + 1 < n && !matched[e + 1]) e++
            val t0 = if (k > 0) end[k - 1] else firstT
            val t1 = maxOf(t0, if (e + 1 < n) start[e + 1] else audioEnd)
            val weights = (k..e).map { script[it].length + 1.0 }
            val total = weights.sum()
            var acc = 0.0
            for (q in k..e) {
                start[q] = t0 + (t1 - t0) * acc / total
                acc += weights[q - k]
                end[q] = t0 + (t1 - t0) * acc / total
            }
            k = e + 1
        }
        return Result(start, end, matched, m - matchOf.count { it >= 0 }, m)
    }
}
