import com.auto.core.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max

var fails11 = 0
fun ok11(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails11++ }

private fun ts(x: Double) = String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", (x / 3600).toInt(), (x % 3600 / 60).toInt(), (x % 60).toInt(), Math.round((x % 1) * 1000).toInt())
private fun srt(rows: List<Triple<Double, Double, String>>) = rows.mapIndexed { k, r -> "${k + 1}\n${ts(r.first)} --> ${ts(r.second)}\n${r.third}\n" }.joinToString("\n")

/** Exact caption timing (TIMING_FIX_README): args = timing dir with details.txt + script.txt (testdata/timing). */
fun main(args: Array<String>) {
    val dir = args[0]
    val scr = File("$dir/script.txt").readText()
    val items = scopedItems(Details.parseSceneFile(File("$dir/details.txt").readText()), scr, mutableListOf())
    val lines = items.map { it.spoken }
    val n = lines.size
    val SR = 16000

    // ---- file reading
    val one = "[00:01.20 - 00:03.50] hello there\n[00:04.00] second line only start\n"
    val r1 = Captions.parseRows(one, "x.txt")!!
    ok11("parseRows: rows as written, a start-only row ends where the next starts / default length",
        r1.size == 2 && r1[0].start == 1.2 && r1[0].end == 3.5 && r1[1].start == 4.0 && r1[1].end > 4.0 && r1[1].text.startsWith("second"))
    ok11("parseRows: JSON file has no cue rows (null)", Captions.parseRows("{\"segments\":[]}", "a.json") == null)
    val rowsA = lines.mapIndexed { k, l -> Triple(1.0 + 4.5 * k, 1.0 + 4.5 * k + 4.0, l) }
    val srtA = srt(rowsA)
    ok11("parse() still gives the same words as before (rows -> spread)", Captions.parse(srtA, "a.srt").size == lines.sumOf { it.trim().split(Regex("\\s+")).size })
    val back = (0 until 12).map { Triple(it * 3.0, (it + 1) * 3.0, "w$it") }                      // Clipchamp: even, back-to-back
    ok11("pauseAware: TurboScribe-style gaps -> true", Captions.pauseAware(Captions.parseRows(srtA, "a.srt")) || n < 10)
    ok11("pauseAware: even back-to-back chunks -> false", !Captions.pauseAware(Captions.parseRows(srt(back), "b.srt")))
    ok11("pauseAware: fewer than 10 cues -> false", !Captions.pauseAware(Captions.parseRows(srt(back.take(5)), "c.srt")))

    // ---- audio: speech everywhere in each cue, but a 1.2 s silence 0.4 s into every cue (the old pause detector is drawn to it)
    val total = rowsA.last().second + 1.0
    val rng = java.util.Random(5)
    val x = FloatArray((total * SR).toInt()) { (rng.nextGaussian() * 0.0005).toFloat() }
    for (r in rowsA) {
        var t = r.first
        while (t < r.second) { val inGap = t >= r.first + 0.4 && t < r.first + 1.6; if (!inGap) { val i = (t * SR).toInt(); if (i < x.size) x[i] = (rng.nextGaussian() * 0.2).toFloat() }; t += 1.0 / SR }
    }
    val words = Captions.parse(srtA, "a.srt")
    val rows = Captions.parseRows(srtA, "a.srt")
    val expected = DoubleArray(n) { k -> if (k == 0) 0.0 else max(rowsA[k].first - minOf(0.06, (rowsA[k].first - rowsA[k - 1].second).coerceAtLeast(0.0) / 2), 0.0) }

    val tOld = timeScenes(items, scr, words, x, x.size.toDouble() / SR)                    // old behaviour: rows not given
    val tNew = timeScenes(items, scr, words, x, x.size.toDouble() / SR, rows = rows)
    val devOld = tOld.scenes.indices.maxOf { abs(tOld.scenes[it].start - expected[it]) }
    val devNew = tNew.scenes.indices.maxOf { abs(tNew.scenes[it].start - expected[it]) }
    println("INFO old path: worst cut off the caption time = ${"%.2f".format(devOld)} s ; new path: ${"%.3f".format(devNew)} s")
    ok11("one cue per scene: every picture change is its cue start (0.06 s early), exactly", devNew < 1e-9 && tNew.scenes.size == n)
    ok11("one cue per scene: scenes are contiguous and end at the narration end", tNew.scenes.zipWithNext().all { (a, b) -> a.end == b.start } && tNew.scenes.last().end >= x.size.toDouble() / SR - 1e-6)
    ok11("one cue per scene: says so in the warnings, no 'placed in the real pauses'", tNew.warnings.any { it.contains("exact start times") } && tNew.warnings.none { it.contains("real pauses of the audio") })
    // NOTE: this synthetic audio does NOT reproduce the old early-cut bug (the README reports 20 cuts moved >0.6 s on the real TurboScribe project);
    // so the real-project check below is only a regression guard for the new rule, not proof that the old failure is fixed on real audio.

    // ---- cues do not line up 1:1 with scenes (each scene split into two cues) but the file keeps real pauses: caption times are kept, no snapping
    val rowsB = ArrayList<Triple<Double, Double, String>>()
    for ((k, r) in rowsA.withIndex()) {
        val ws = lines[k].trim().split(Regex("\\s+")); val h = ws.size / 2; val mid = (r.first + r.second) / 2
        rowsB.add(Triple(r.first, mid - 0.1, ws.take(h).joinToString(" "))); rowsB.add(Triple(mid + 0.1, r.second, ws.drop(h).joinToString(" ")))
    }
    val srtB = srt(rowsB)
    val wB = Captions.parse(srtB, "b.srt"); val rB = Captions.parseRows(srtB, "b.srt")
    val oldB = timeScenes(items, scr, wB, x, x.size.toDouble() / SR)
    val newB = timeScenes(items, scr, wB, x, x.size.toDouble() / SR, rows = rB)
    val dB = newB.scenes.indices.drop(1).maxOf { abs(newB.scenes[it].start - rowsA[it].first) }
    val dOldB = oldB.scenes.indices.drop(1).maxOf { abs(oldB.scenes[it].start - rowsA[it].first) }
    println("INFO split cues: old path worst = ${"%.2f".format(dOldB)} s ; new path worst = ${"%.2f".format(dB)} s")
    ok11("split cues + real pauses: no pause snapping, cuts stay within 0.5 s of the scene's first caption", dB < 0.5 && newB.scenes.none { it.cutCheck } && !newB.report!!.anchored)
    ok11("split cues + real pauses: says the caption times are used as they are", newB.warnings.any { it.contains("keep the real pauses") })

    // ---- Clipchamp-style (even, no gaps) captions still use the pause repair exactly as before
    val evenRows = (0 until 12).map { Triple(it * 3.0, (it + 1) * 3.0, lines[it % n]) }
    val evenSrt = srt(evenRows)
    val wE = Captions.parse(evenSrt, "e.srt"); val rE = Captions.parseRows(evenSrt, "e.srt")
    val a = timeScenes(items, scr, wE, x, x.size.toDouble() / SR)
    val b = timeScenes(items, scr, wE, x, x.size.toDouble() / SR, rows = rE)
    ok11("Clipchamp-style captions: result identical with or without rows (unchanged behaviour)", a.scenes.size == b.scenes.size && a.scenes.indices.all { a.scenes[it].start == b.scenes[it].start && a.scenes[it].end == b.scenes[it].end })

    // ---- a wrong file (different text) never takes the exact path
    val wrong = Align.exactCueScenes(rows!!.map { Cue(it.start, it.end, "completely different words here") }, lines, 100.0)
    ok11("cues with other words than the scenes -> not used as exact (null)", wrong == null)
    ok11("wrong number of cues -> not used as exact (null)", Align.exactCueScenes(rows.drop(1), lines, 100.0) == null)

    println(if (fails11 == 0) "ALL PASSED" else "$fails11 FAILED")
    if (fails11 > 0) System.exit(1)
}
