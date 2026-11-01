package com.auto.core

import kotlin.math.*

val STOP: Set<String> = ("a an the and or but so of to in on at for with from by as is are was were be been it its this that " +
    "these those i you he she we they my your his her our their me him them us not no do does did have has had will would " +
    "can could should just very then than there here also if when what which who how all any each more most one scene image " +
    "shot visual prompt").split(" ").toSet()

class Scene(val text: String, var start: Double, var end: Double) {
    var item: SceneItem? = null
    var num = 0
    var cutCheck = false      // the picture change before this scene could not be placed in a clear pause: check it
    var image: String? = null // picture path chosen for this scene (null = missing: blank screen + scene text)
    var conf = ""             // "?" = matching not certain, check it
    var missing = false
    var outStart = 0.0        // where this scene starts in the finished video
}

class AlignReport(val match: Double, val words: Int, var cutsMoved: Int = 0, var cutsUnchecked: Int = 0, var flagged: List<Int> = emptyList(), var anchored: Boolean = false)

/** Ports of segment_words, pull_in_spoken_titles, align, estimate_align from scriptvideo/engine.py. */
object Align {
    const val MIN_SCENE_SECONDS = 1.5
    private val NUM_WORDS = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
        "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "first" to "1", "second" to "2", "third" to "3", "fourth" to "4",
        "fifth" to "5", "sixth" to "6", "seventh" to "7", "eighth" to "8", "ninth" to "9", "tenth" to "10")
    fun tn(t: String) = NUM_WORDS[t] ?: t
    private fun wordScore(tok: String, s: Set<String>) = if (tok in s) (if (tok in STOP) 0.4 else 1.0) else (if (tok in STOP) -0.1 else -0.4)

    /** Assign every spoken word to a scene, in order, to best match each scene's text. Returns the start word index of each scene. */
    fun segmentWords(wtoks: List<String>, sceneToks: List<List<String>>): IntArray {
        val N = wtoks.size; val M = sceneToks.size
        if (N < M) throw PipelineError("The audio has only $N spoken words but there are $M scenes. The audio is too short or silent.")
        val sets = sceneToks.map { it.toSet() }
        val NEG = -1e12; val band = max(150, (0.25 * N).toInt())
        var prev = DoubleArray(N) { NEG }
        val back = ArrayList<ByteArray>()
        for (i in 0 until M) {
            val center = (i + 0.5) * N / M
            var lo = max(i, (center - band).toInt()); var hi = min(N - (M - i), (center + band).toInt())
            if (i == 0) lo = 0
            if (i == M - 1) hi = N - 1
            if (lo > hi) { lo = i; hi = N - (M - i) }
            val row = DoubleArray(N) { NEG }; val bk = ByteArray(N)
            for (j in lo..hi) {
                val s = wordScore(wtoks[j], sets[i])
                if (i == 0) {
                    if (j == 0) row[j] = s else if (row[j - 1] > NEG / 2) row[j] = row[j - 1] + s
                } else {
                    val stay = if (j > 0) row[j - 1] else NEG
                    val nw = if (j > 0) prev[j - 1] else NEG
                    if (stay >= nw && stay > NEG / 2) row[j] = stay + s
                    else if (nw > NEG / 2) { row[j] = nw + s; bk[j] = 1 }
                }
            }
            prev = row; back.add(bk)
        }
        if (prev[N - 1] <= NEG / 2) throw PipelineError("Could not line the scenes up with the audio. Check that the scene details match what is spoken.")
        val starts = IntArray(M)
        var i = M - 1; var j = N - 1
        while (i > 0) { if (back[i][j].toInt() == 1) { starts[i] = j; i-- }; j-- }
        starts[0] = 0
        return starts
    }

    /** A section heading with NO picture of its own is still spoken ("Section 3, The Fall"): move the section's first scene back to where the title begins. */
    fun pullInSpokenTitles(starts0: IntArray, wtoks: List<String>, sceneToks: List<List<String>>, heads: Map<Int, String>): IntArray {
        val starts = starts0.copyOf()
        for (i in heads.keys.sorted()) {
            if (i <= 0 || i >= starts.size) continue
            val tl = Details.tokens(heads[i]!!).map { tn(it) }
            val T = tl.toSet(); if (T.isEmpty()) continue
            val first = sceneToks[i].take(T.size + 1).map { tn(it) }
            if (first.isNotEmpty() && first.count { it in T } >= max(1, (0.6 * min(first.size, T.size)).toInt()) && sceneToks[i].size <= T.size + 3) continue
            val s = starts[i]; val room = s - (starts[i - 1] + 1)
            var bestK = 0; var bestSc = 0.0
            for (k in 1..min(tl.size + 2, room)) {
                val win = wtoks.subList(s - k, s).map { tn(it) }
                val left = tl.toMutableList(); val hits = ArrayList<Boolean>()
                for (w in win) { if (left.remove(w)) hits.add(true) else hits.add(false) }
                if (!hits[0]) continue
                val h = hits.count { it }; val sc = h - 0.6 * (k - h)
                if (sc > bestSc + 1e-9) { bestK = k; bestSc = sc }
            }
            if (bestK > 0 && bestSc >= 1.0 && wtoks.subList(s - bestK, s).any { tn(it) in T && it !in STOP }) starts[i] = s - bestK
        }
        return starts
    }

    /** words from the captions file (or speech recognition) + the scene lines -> scenes with start/end. audio = 16 kHz samples for pause snapping (optional). */
    fun align(words0: List<TimedWord>, sceneLines: List<String>, scriptText: String, audio: FloatArray? = null, heads: Map<Int, String>? = null, lead: Double = 0.12): Pair<List<Scene>, AlignReport> {
        if (words0.isEmpty()) throw PipelineError("No speech was found in the audio file.")
        val words = ArrayList<TimedWord>(); val wtoks = ArrayList<String>()
        for (w in words0) { val t = Details.tokens(w.text); if (t.isNotEmpty()) { wtoks.add(t.joinToString("")); words.add(w) } }
        var sceneToks = sceneLines.map { Details.tokens(it) }
        val scriptLines = if (scriptText.isNotEmpty()) Details.linesOf(scriptText) else emptyList()
        if (scriptLines.size == sceneLines.size) sceneToks = sceneToks.zip(scriptLines) { a, b -> a + Details.tokens(b) }
        val allSet = HashSet<String>(); sceneToks.forEach { allSet.addAll(it) }
        if (scriptText.isNotEmpty()) allSet.addAll(Details.tokens(scriptText))
        val informative = wtoks.filter { it !in STOP }
        val hit = informative.count { it in allSet }.toDouble() / max(informative.size, 1)
        if (hit < 0.45) throw PipelineError("The narration does not seem to match the script/scene text (only ${(hit * 100).roundToInt()}% of spoken words were found). " +
            "Check you picked the right audio and text files.")
        var starts = segmentWords(wtoks, sceneToks)
        if (!heads.isNullOrEmpty()) starts = pullInSpokenTitles(starts, wtoks, sceneToks, heads)
        val total = words.last().end
        var cuts: List<Double> = arrayListOf(0.0).also { c ->
            for (i in 1 until starts.size) {
                val a = starts[i]; val gap = words[a].start - words[a - 1].end
                c.add(max(words[a].start - min(lead, max(gap, 0.0) / 2), c.last() + 0.25))
            }
        }
        val rep = AlignReport(hit, words.size)
        if (audio != null) {
            try {
                val r = AudioPauses.refineCuts(audio, cuts, listOf(0.0) + starts.drop(1).map { words[it].start }, listOf<Double?>(null) + starts.drop(1).map { words[it - 1].end })
                cuts = r.cuts; rep.cutsMoved = r.moved; rep.cutsUnchecked = r.none; rep.flagged = r.flagged
            } catch (e: Exception) { /* the caption times are used as they are */ }
        }
        return Pair(sceneLines.mapIndexed { i, l -> Scene(l, cuts[i], if (i + 1 < cuts.size) cuts[i + 1] else total) }, rep)
    }

    /** No audio and no captions: each scene lasts as long as its own words take to say (2.5 words/s, min 1.5 s). */
    fun estimate(sceneLines: List<String>, scriptText: String = ""): List<Scene> {
        val sl = if (scriptText.isNotEmpty()) Details.linesOf(scriptText) else emptyList()
        val src = if (sl.size == sceneLines.size) sl else sceneLines
        var t = 0.0; val out = ArrayList<Scene>()
        for ((line, s) in sceneLines.zip(src)) {
            val d = max(Details.tokens(s).size / Captions.WORDS_PER_SECOND, MIN_SCENE_SECONDS)
            out.add(Scene(line, Math.rint(t * 1000) / 1000, Math.rint((t + d) * 1000) / 1000)); t += d
        }
        return out
    }
}

class TimingResult(val scenes: List<Scene>, val warnings: List<String>, val report: AlignReport?, val duration: Double)

/** The timing part of analyse(): details items + captions (+ audio) -> scenes. audio = 16 kHz mono samples or null; audioSeconds = its length. */
fun timeScenes(items: List<SceneItem>, scriptText: String, words: List<TimedWord>?, audio: FloatArray?, audioSeconds: Double, verified: Boolean = false): TimingResult {
    val warnings = ArrayList<String>()
    val lines = items.map { it.spoken }
    val scenes: List<Scene>; var rep: AlignReport? = null
    if (words == null) {
        scenes = Align.estimate(lines, scriptText)
        warnings.add(if (audio == null) "No narration audio and no captions: picture lengths are ESTIMATED from the number of words in each script line. " +
            "Add a captions file to get the real timing (the picture matching stays the same)."
            else "No captions file: picture lengths are ESTIMATED from the number of words in each script line. Add a captions file to get the real timing.")
    } else {
        val heads = HashMap<Int, String>()
        for ((k, it) in items.withIndex()) if (k > 0 && it.section.isNotEmpty() && it.section != items[k - 1].section) heads[k] = it.section
        // verified = word times come from speech recognition + forced alignment of the real audio (backend). They are exact, so the energy-based
        // pause snapping (which exists to repair APPROXIMATE caption times, and can move a cut early) is not applied.
        val r = Align.align(words, lines, scriptText, if (verified) null else audio, heads, if (verified) 0.06 else 0.12); scenes = r.first; rep = r.second
        if (verified) warnings.add("Picture changes use the exact spoken-word times from the audio analysis.")
        if (audio != null && !verified) {
            val res = try { AudioPauses.anchorCuts(audio, scenes.map { it.text }, scenes.map { it.start }) } catch (e: Exception) { null }
            if (res != null) {
                val (cuts, odd) = res
                scenes.forEachIndexed { k, s -> s.start = cuts[k]; s.end = if (k + 1 < scenes.size) cuts[k + 1] else max(scenes.last().end, audioSeconds) }
                rep.flagged = odd; rep.anchored = true
                warnings.add("Picture changes were placed in the real pauses of the audio (the captions file is only approximate).")
            }
        }
    }
    scenes.forEachIndexed { k, s -> s.item = items[k]; s.num = k + 1 }
    rep?.flagged?.forEach { if (it < scenes.size) scenes[it].cutCheck = true }
    if (!rep?.flagged.isNullOrEmpty()) warnings.add("Picture change timing is not certain for scene(s) " + rep!!.flagged.take(15).joinToString(", ") { (it + 1).toString() } +
        (if (rep.flagged.size > 15) " ..." else "") + " (no clear pause between the lines). They are marked with ! on the timeline - check them.")
    scenes.last().end = max(scenes.last().end, audioSeconds)
    val dur = max(audioSeconds, scenes.last().end)
    val short = scenes.filter { it.end - it.start < 0.6 }.map { it.num }
    if (short.isNotEmpty()) warnings.add("Very short scenes (under 0.6 s): " + short.take(15).joinToString(", "))
    return TimingResult(scenes, warnings.distinct(), rep, dur)
}
