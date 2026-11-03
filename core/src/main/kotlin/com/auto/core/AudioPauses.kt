package com.auto.core

import kotlin.math.*

/** Pause detection on 16 kHz mono samples (decode the narration to FloatArray on the phone). Port of refine_cuts / anchor_cuts_to_pauses. */
object AudioPauses {
    const val SR = 16000
    private const val HOP = 160                                  // 10 ms steps

    private fun percentile(v: DoubleArray, q: Double): Double {   // numpy default (linear)
        val s = v.sortedArray(); val idx = q / 100.0 * (s.size - 1)
        val lo = floor(idx).toInt(); val hi = ceil(idx).toInt()
        return s[lo] + (s[hi] - s[lo]) * (idx - lo)
    }

    private class Env(val n: Int, val voiced: BooleanArray)

    /** null when there is no clear difference between speech and quiet (then cuts are left alone). */
    private fun envelope(x: FloatArray, minN: Int): Env? {
        val n = x.size / HOP
        if (n < minN) return null
        val db = DoubleArray(n) { k ->
            var s = 0.0; val o = k * HOP
            for (t in 0 until HOP) { val d = x[o + t].toDouble(); s += d * d }
            20 * log10(sqrt(s / HOP) + 1e-7)
        }
        val floorDb = percentile(db, 5.0); val speech = percentile(db, 90.0)
        if (speech - floorDb < 12) return null
        val thr = max(floorDb + 0.35 * (speech - floorDb), speech - 30.0)
        return Env(n, BooleanArray(n) { db[it] > thr })
    }

    /** Silent gaps INSIDE the narration (not the lead-in or tail), as (startSec, endSec) pairs of at least minSec. null = no usable speech/quiet contrast. */
    fun silences(x: FloatArray, minSec: Double = 0.5): List<Pair<Double, Double>>? {
        val env = envelope(x, 50) ?: return null
        val n = env.n; val voiced = env.voiced; val out = ArrayList<Pair<Double, Double>>()
        var i = 0
        while (i < n) {
            if (!voiced[i]) { var j = i; while (j < n && !voiced[j]) j++; if (i > 0 && j < n && (j - i) / 100.0 >= minSec) out.add(Pair(i / 100.0, j / 100.0)); i = j } else i++
        }
        return out
    }

    class Refined(val cuts: List<Double>, val moved: Int, val none: Int, val flagged: List<Int>)

    /** Put every picture change in the real SILENCE between two lines (see the Python docstring). */
    fun refineCuts(x: FloatArray, cuts: List<Double>, firsts: List<Double>, lasts: List<Double?>?): Refined {
        val env = envelope(x, 50) ?: return Refined(cuts, 0, 0, emptyList())
        val n = env.n; val voiced = env.voiced
        var i = 0
        while (i < n) {                                          // tiny dips inside words are not pauses
            if (!voiced[i]) {
                var j = i; while (j < n && !voiced[j]) j++
                if (j - i < 6 && i > 0 && j < n) for (k in i until j) voiced[k] = true
                i = j
            } else i++
        }
        val cand = ArrayList<Pair<Double, Double>>(); var run = 0
        for (k in 0 until n) { if (voiced[k]) { if (run >= 12) cand.add(Pair(k / 100.0, run / 100.0)); run = 0 } else run++ }
        val tolEarly = 0.15; val tolLate = 0.60
        val out = arrayListOf(cuts[0]); var moved = 0; var none = 0; val flagged = ArrayList<Int>()
        for (c in 1 until cuts.size) {
            val w = firsts[c]
            val prevEnd = lasts?.get(c) ?: (w - 0.3)
            val lo = prevEnd - tolEarly
            var hi = w + tolLate
            if (c + 1 < cuts.size) hi = min(hi, firsts[c + 1] - 0.15)
            var best: Double? = null; var bt = 0.0; var bp = 0.0
            for ((t, pause) in cand) {
                if (t <= out.last() + 0.25 || t < lo || t > hi) continue
                val cost = abs(t - w) / 0.4 - min(pause, 0.8) / 0.25
                if (best == null || cost < best) { best = cost; bt = t; bp = pause }
            }
            if (best == null) {
                out.add(max(max(cuts[c], prevEnd + 0.02), out.last() + 0.25)); none++; flagged.add(c); continue
            }
            val nw = bt - min(0.05, bp / 2)
            if (abs(nw - cuts[c]) > 0.02) moved++
            if (bp < 0.2) flagged.add(c)
            out.add(max(nw, out.last() + 0.25))
        }
        return Refined(out, moved, none, flagged)
    }

    /** For approximate captions + real audio: put EVERY picture change into a real pause, chosen together so each scene lasts
     *  about as long as its words take to say. Returns (cuts, odd scene indices) or null when the audio has no usable pauses. */
    fun anchorCuts(x: FloatArray, sceneTexts: List<String>, sceneStarts: List<Double>, minScene: Double = 0.6): Pair<List<Double>, List<Int>>? {
        val K = sceneTexts.size
        val env = envelope(x, 100) ?: return null
        if (K < 2) return null
        val n = env.n; val voiced = env.voiced
        val pa = ArrayList<Pair<Double, Double>>(); var i = 0
        while (i < n) {
            if (!voiced[i]) {
                var j = i; while (j < n && !voiced[j]) j++
                if (j - i >= 15 && i > 0 && j < n) pa.add(Pair(i / 100.0, j / 100.0))
                i = j
            } else i++
        }
        if (pa.size < K - 1) return null
        val C = DoubleArray(pa.size) { pa[it].second - min(0.12, (pa[it].second - pa[it].first) / 2) }
        val M = C.size
        val first = voiced.indexOfFirst { it }.coerceAtLeast(0); val lastV = voiced.indexOfLast { it }
        val t0 = first / 100.0
        val tend = (n - (n - 1 - (if (lastV < 0) n - 1 else lastV))) / 100.0
        val W = DoubleArray(K) { max(Details.tokens(sceneTexts[it]).size, 1).toDouble() }
        val spw = (tend - t0) / W.sum()
        val Eb = DoubleArray(K - 1) { sceneStarts[it + 1] }
        val Kb = K - 1; val INF = 1e18
        val cost = Array(Kb) { DoubleArray(M) { INF } }; val back = Array(Kb) { IntArray(M) }
        for (j in 0 until M) cost[0][j] = abs(C[j] - Eb[0]) + abs(C[j] - t0 - W[0] * spw)
        for (k in 1 until Kb) {
            for (j in 1 until M) {
                var bm = 0; var bv = Double.MAX_VALUE
                for (m in 0 until j) {
                    val d = C[j] - C[m]
                    val tr = cost[k - 1][m] + abs(d - W[k] * spw) + (if (d < minScene) 1e6 else 0.0)
                    if (tr < bv) { bv = tr; bm = m }
                }
                cost[k][j] = abs(C[j] - Eb[k]) + bv; back[k][j] = bm
            }
        }
        var j = 0; for (q in 1 until M) if (cost[Kb - 1][q] < cost[Kb - 1][j]) j = q
        if (cost[Kb - 1][j] >= 1e5) return null
        val idx = arrayListOf(j)
        for (k in Kb - 1 downTo 1) { j = back[k][j]; idx.add(j) }
        val cuts = arrayListOf(0.0); idx.reversed().forEach { cuts.add(C[it]) }
        val ends = cuts + listOf(max(tend, cuts.last() + 0.5))
        val odd = (0 until K).filter { k -> val r = (ends[k + 1] - ends[k]) / (W[k] * spw); r < 0.4 || r > 2.0 }
        return Pair(cuts, odd)
    }
}
