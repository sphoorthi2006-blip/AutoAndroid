package com.auto.core

import java.util.Locale
import kotlin.math.max

/** One thing to tell the user. level "warn" = please check it, "info" = just so you know. scene (1-based) is where it points; the app jumps there when it is tapped. */
data class AnalyseIssue(val text: String, val scene: Int?, val level: String, val kind: String)

/** Collects the warnings. Each kind is listed at most MAX_LISTED times, then one summary line. */
class Issues {
    private val items = ArrayList<AnalyseIssue>(); private val count = LinkedHashMap<String, Int>()
    fun add(text: String, scene: Int?, level: String, kind: String) {
        val n = (count[kind] ?: 0) + 1; count[kind] = n
        if (kind.isNotEmpty() && n > Analyser.MAX_LISTED) return
        items.add(AnalyseIssue(text, scene, level, kind))
    }
    fun warn(text: String, scene: Int? = null, kind: String = "") = add(text, scene, "warn", kind)
    fun info(text: String, scene: Int? = null, kind: String = "") = add(text, scene, "info", kind)
    fun result(): List<AnalyseIssue> {
        val out = ArrayList(items)
        for ((k, n) in count) if (k.isNotEmpty() && n > Analyser.MAX_LISTED) out.add(AnalyseIssue("... and ${n - Analyser.MAX_LISTED} more warnings of the same kind ('$k').", null, "warn", k))
        return out
    }
}

/** The result of analyse(). Scenes are always contiguous and cover the whole length; a scene without a picture is a black screen with its text. */
class Analysis(
    val scenes: List<Scene>, val duration: Double, val extras: List<String>, val issues: List<AnalyseIssue>,
    val sceneSource: String,     // timestamps | script | pictures | audio
    val timingSource: String,    // timestamps | audio | estimate | even
    val pictureMode: String,     // number | order | pictures | ""
) {
    val warnings get() = issues.filter { it.level == "warn" }.map { it.text }
}

/**
 * ANALYSE - four optional inputs (timestamps, audio, pictures, script). Nothing is mandatory and nothing stops: whatever is missing or wrong
 * becomes a warning and the timeline is still built. Port of the desktop engine.analyse().
 *
 *   scenes  (what is on screen, how many)   : timestamps cues  >  script lines  >  pictures  >  the audio alone
 *   timing  (when each picture changes)     : timestamps (exact cue starts)  >  audio (AI-server word times, or pauses)  >  estimate from words  >  3 s each
 *   pictures (which picture is on a scene)  : the number in the file name (7.jpg = scene 7)  >  file-name order
 *   a scene with no picture -> black screen with its text;  pictures beyond the last scene -> left out
 */
object Analyser {
    const val LONG_SCENE_S = 7.0
    const val SHORT_SCENE_S = 0.6
    const val MIN_CUT_GAP_S = 0.2
    const val PICTURE_ONLY_SECONDS = 3.0
    const val MAX_LISTED = 40
    private const val LEAD_S = 0.06      // the picture changes this long BEFORE the cue's first word, so it is already there when the word is heard

    private fun mmss(t: Double) = String.format(Locale.ROOT, "%d:%04.1f", (max(t, 0.0) / 60).toInt(), max(t, 0.0) % 60)

    /** Cues cleaned and in time order. Problems become warnings. */
    fun prepareRows(rows: List<Cue>, iss: Issues): List<Cue> {
        var out = ArrayList<Cue>(); var dropped = 0
        for (r in rows) {
            val t = Text.cleanCue(r.text)
            if (t.isEmpty()) { dropped++; continue }
            out.add(Cue(r.start, max(r.end, r.start), t))
        }
        if (dropped > 0) iss.info("$dropped caption(s) without text were skipped; scene numbers count only the captions that have text.", null, "srt")
        if ((1 until out.size).any { out[it].start < out[it - 1].start }) {
            out = ArrayList(out.sortedBy { it.start })     // stable sort
            iss.warn("The timestamps are not in time order, so they were sorted by start time. Scene numbers follow the sorted order.", null, "srt")
        }
        for (k in 1 until out.size) if (out[k].start < out[k - 1].end - 0.15)
            iss.warn("Scene ${k + 1} starts at ${mmss(out[k].start)}, before the previous caption ends (${mmss(out[k - 1].end)}): overlapping timestamps.", k + 1, "overlap")
        return out
    }

    /** One scene per cue. The picture changes at the cue's start (a hair earlier). The first picture starts at 0, the last lasts to the end of the audio / last cue. */
    fun scenesFromCues(rows: List<Cue>, total: Double): List<Scene> {
        val cuts = arrayListOf(0.0)
        for (k in 1 until rows.size) {
            val gap = rows[k].start - rows[k - 1].end
            cuts.add(max(rows[k].start - minOf(LEAD_S, max(gap, 0.0) / 2), cuts.last() + MIN_CUT_GAP_S))
        }
        val endAll = maxOf(total, rows.last().end, cuts.last() + MIN_CUT_GAP_S)
        return rows.mapIndexed { i, r -> Scene(r.text, cuts[i], if (i + 1 < cuts.size) cuts[i + 1] else endAll) }
    }

    /** The script is optional. With timestamps it is only a cross-check: do both describe the same narration? */
    fun crosscheck(scriptText: String, rows: List<Cue>, iss: Issues) {
        val st = Text.tokens(scriptText); val ct = rows.flatMap { Text.tokens(it.text) }
        if (st.isEmpty() || ct.isEmpty()) return
        val cs = st.groupingBy { it }.eachCount(); val cc = ct.groupingBy { it }.eachCount()
        val common = cs.entries.sumOf { (w, n) -> minOf(n, cc[w] ?: 0) }
        val inCaps = common.toDouble() / st.size; val inScript = common.toDouble() / ct.size
        val worst = minOf(inCaps, inScript)
        val msg = "${Math.round(inCaps * 100)}% of the script's words are in the timestamps and ${Math.round(inScript * 100)}% of the timestamp words are in the script"
        if (worst < 0.85) iss.warn("The script and the timestamps do not match well: $msg. Check that both files belong to the same narration. The scenes follow the timestamps.", null, "script")
        else if (worst < 0.97) iss.info("The script and the timestamps differ a little: $msg.", null, "script")
        if (worst >= 0.5) {
            val vocab = st.toSet()
            for ((k, r) in rows.withIndex()) {
                val tk = Text.tokens(r.text)
                if (tk.size >= 3 && tk.count { it in vocab }.toDouble() / tk.size < 0.5) iss.warn("Scene ${k + 1}: this caption is not in the script: \"${r.text.take(60)}\"", k + 1, "script_line")
            }
        }
        val nLines = Text.scriptSceneLines(scriptText).size
        if (nLines != rows.size) iss.info("The script has $nLines lines and the timestamps ${rows.size} captions. One scene per caption is used.", null, "script")
    }

    private fun runs(nums: List<Int>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (n in nums.sorted()) if (out.isNotEmpty() && n == out.last().second + 1) out[out.size - 1] = Pair(out.last().first, n) else out.add(Pair(n, n))
        return out
    }

    /** No timing at all: a length in proportion to the words of each line (3 s for a scene without text). */
    fun estimateScenes(texts: List<String>): List<Scene> {
        var t = 0.0; val out = ArrayList<Scene>()
        for (txt in texts) {
            val w = Text.tokens(txt).size
            val d = if (w > 0) max(w / Captions.WORDS_PER_SECOND, Align.MIN_SCENE_SECONDS) else PICTURE_ONLY_SECONDS
            out.add(Scene(txt, Math.rint(t * 1000) / 1000, Math.rint((t + d) * 1000) / 1000)); t += d
        }
        return out
    }

    /** Scenes split over the audio in proportion to their words. */
    private fun evenScenes(texts: List<String>, total: Double): List<Scene> {
        val w = texts.map { max(Text.tokens(it).size, 1).toDouble() }; val sum = w.sum()
        var t = 0.0
        return texts.mapIndexed { i, s -> val a = t; t += total * w[i] / sum; Scene(s, a, if (i == texts.size - 1) total else t) }
    }

    /** Scenes from the script lines (or N empty scenes) timed with the narration: the AI server's exact word times if given, else the pauses of the audio. */
    private fun timingFromAudio(texts: List<String>, samples: FloatArray?, dur: Double, words: List<TimedWord>?, iss: Issues): Pair<List<Scene>, String> {
        if (words != null && texts.any { Text.tokens(it).isNotEmpty() }) {
            try {
                val (sc, rep) = Align.align(words, texts, "", null, LEAD_S)
                if (rep.match < 0.8) iss.warn("Only ${Math.round(rep.match * 100)}% of the spoken words were found in the script. Check the timing of the cuts.", null, "timing")
                else iss.info("Picture changes use the exact spoken-word times from the audio analysis.", null, "timing")
                return Pair(sc, "audio")
            } catch (e: PipelineError) {
                iss.warn("The narration could not be matched to the script (${e.message}) Cuts were placed by pauses and text length, so check the timing.", null, "timing")
            }
        }
        val scenes = evenScenes(texts, dur)
        if (samples != null && scenes.size > 1) {
            val res = try { AudioPauses.anchorCuts(samples, texts, scenes.map { it.start }) } catch (e: Exception) { null }
            if (res != null) {
                val (cuts, odd) = res
                val end = scenes.last().end
                scenes.forEachIndexed { k, s -> s.start = cuts[k]; s.end = if (k + 1 < scenes.size) cuts[k + 1] else max(end, cuts.last() + MIN_CUT_GAP_S) }
                odd.forEach { if (it < scenes.size) scenes[it].cutCheck = true }
                iss.info("There are no timestamps, so the picture changes were placed in the pauses of the narration" +
                    (if (odd.isEmpty()) "." else " (scenes ${odd.take(10).joinToString(", ") { (it + 1).toString() }} are marked ! - check them)."), null, "timing")
                return Pair(scenes, "audio")
            }
            iss.info("There are no timestamps and the audio has no clear pauses: the narration was split over the scenes by the number of words.", null, "timing")
        }
        return Pair(scenes, "even")
    }

    /**
     * pictures = full paths of every picture (any order). audioSeconds > 0 means a narration was given; samples = its 16 kHz mono samples (for pause detection, optional).
     * words = exact word times from the AI server (optional, only used when there are no timestamps). Throws PipelineError only when there is NOTHING to work on.
     */
    fun analyse(
        timestampsText: String? = null, timestampsName: String = "", scriptText: String? = null, pictures: List<String> = emptyList(),
        samples: FloatArray? = null, audioSeconds: Double = 0.0, words: List<TimedWord>? = null, startIssues: List<AnalyseIssue> = emptyList(),
    ): Analysis {
        val iss = Issues(); startIssues.forEach { iss.add(it.text, it.scene, it.level, it.kind) }
        val hasAudio = audioSeconds > 0.0 || samples != null
        val dur = if (audioSeconds > 0.0) audioSeconds else (samples?.size?.div(16000.0) ?: 0.0)
        var rows: List<Cue> = emptyList()
        if (!timestampsText.isNullOrBlank()) {
            try {
                rows = prepareRows(Captions.parseRows(timestampsText, timestampsName) ?: Captions.parseCues(Captions.parse(timestampsText, timestampsName)), iss)
            } catch (e: PipelineError) { iss.warn("${e.message} The timestamps were skipped.", null, "file") }
            catch (e: Exception) { iss.warn("The timestamps file could not be read (${e.message}). It was skipped.", null, "file") }
            if (rows.isEmpty()) iss.warn("No captions were found in the timestamps file, so it was not used.", null, "file")
        }
        val script = scriptText.orEmpty()
        val lines = if (script.isNotBlank()) Text.scriptSceneLines(script) else emptyList()
        val pics = pictures.distinct().sortedWith(Pics.NATURAL)

        // 1) WHAT are the scenes
        var ordered: List<String> = emptyList()
        val source: String; val texts: List<String>
        if (rows.isNotEmpty()) { source = "timestamps"; texts = rows.map { it.text } }
        else if (lines.isNotEmpty()) { source = "script"; texts = lines }
        else if (pics.isNotEmpty()) {
            source = "pictures"
            ordered = pics.sortedWith(compareBy<String>({ Pics.imageNumber(it) == null }, { Pics.imageNumber(it) ?: 0 }).thenComparator { a, b -> Pics.naturalCompare(a, b) })
            texts = ordered.map { "" }
        } else if (hasAudio) { source = "audio"; texts = listOf("") }
        else throw PipelineError("Nothing to analyse yet. Add at least one of: timestamps, audio, pictures or a script.")
        val n = texts.size

        // 2) WHEN does each picture change
        val scenes: List<Scene>; val timing: String
        if (source == "timestamps") {
            scenes = scenesFromCues(rows, dur); timing = "timestamps"
            if (script.isNotBlank()) crosscheck(script, rows, iss)
            if (hasAudio) {
                val last = rows.last().end
                if (last > dur + 1.0) {
                    val firstOut = rows.indexOfFirst { it.start >= dur }.let { if (it < 0) n - 1 else it }
                    iss.warn("The timestamps run to ${mmss(last)} but the audio ends at ${mmss(dur)}: the scenes after the end of the audio have no narration.", firstOut + 1, "audio_len")
                } else if (dur - last > 3.0 && dur - scenes.last().start <= LONG_SCENE_S)
                    iss.warn(String.format(Locale.ROOT, "The audio is %.1f s longer than the last caption, so the last picture stays on screen until the end of the audio.", dur - last), n, "audio_len")
            }
        } else if (hasAudio) {
            if (source == "audio") { scenes = evenScenes(texts, dur); timing = "even" }
            else { val r = timingFromAudio(texts, samples, dur, words, iss); scenes = r.first; timing = r.second }
        } else {
            scenes = estimateScenes(texts); timing = "estimate"
            if (source == "script") iss.warn("No timestamps and no audio: picture lengths are only ESTIMATED from the number of words in each script line. Add the timestamps or the audio and Analyse again for the real timing.", null, "timing")
            else iss.info("No timestamps and no audio: every picture gets ${PICTURE_ONLY_SECONDS.toInt()} s.", null, "timing")
        }
        scenes.forEachIndexed { k, s -> s.num = k + 1 }
        scenes.last().end = max(scenes.last().end, dur)

        // 3) WHICH picture is on each scene
        val extras = ArrayList<String>(); var mode = ""
        if (source == "pictures") {
            scenes.zip(ordered).forEach { (s, p) -> s.image = p }; mode = "pictures"
            val got = ordered.mapNotNull { Pics.imageNumber(it) }.distinct().sorted()
            val gaps = if (got.isEmpty()) emptyList() else (got.first()..got.last()).filter { it !in got }
            if (gaps.isNotEmpty()) iss.warn("Picture numbers skip " + gaps.take(15).joinToString(", ") + (if (gaps.size > 15) " ..." else "") +
                ": a picture may be missing (the scenes simply follow each other without a gap).", null, "pic_num")
        } else if (source == "audio") {
            iss.info("Only the audio was added: one black scene as long as the narration. Add pictures, timestamps or a script to build the video.", null, "inputs")
        } else if (pics.isNotEmpty()) {
            val a = Pics.assign(pics, n, iss); extras.addAll(a.extras); mode = a.mode
            scenes.zip(a.slots).forEach { (s, p) -> s.image = p }
            for ((x, y) in runs(scenes.filter { it.image == null }.map { it.num })) {
                if (x == y) iss.warn("Scene $x has no picture (picture number $x was not found): it is a black screen with its text.", x, "missing_pic")
                else iss.warn("Scenes $x-$y (${y - x + 1} scenes) have no picture" + (if (mode == "order") " (only ${pics.size} pictures for $n scenes)" else "") + ": black screens with their text.", x, "missing_pic")
            }
        } else iss.warn("No pictures were added: all $n scene(s) are black screens with their text.", null, "missing_pic")

        // 4) are the pictures themselves readable?
        val used = scenes.filter { it.image != null }
        val bad = Validation.unreadable(used.map { it.image!! }).toSet()
        for (s in used) if (s.image in bad) {
            iss.warn("Scene ${s.num}: the picture '${Pics.nameOf(s.image!!)}' cannot be opened (damaged?). The scene is a black screen with its text.", s.num, "bad_pic")
            extras.add(s.image!!); s.image = null
        }

        // 5) how long does each picture stay?
        if (source != "audio") for (s in scenes) {
            val d = s.end - s.start
            if (d > LONG_SCENE_S) iss.warn(String.format(Locale.ROOT, "Scene %d is %.1f s long: one picture stays on screen for a long time. Split that caption in the timestamps file, or add pictures.", s.num, d), s.num, "long_scene")
            else if (d < SHORT_SCENE_S) iss.warn(String.format(Locale.ROOT, "Scene %d is only %.2f s long: the picture will flash by.", s.num, d), s.num, "short_scene")
        }
        var t = 0.0; for (s in scenes) { s.outStart = t; t += max(s.end - s.start, 0.04) }
        val total = max(dur, scenes.last().end)
        val srcTxt = mapOf("timestamps" to "$n captions", "script" to "$n script lines", "pictures" to "$n pictures", "audio" to "the audio")[source]
        var timeTxt = mapOf("timestamps" to "timestamps (exact)", "audio" to "audio", "estimate" to "estimated from the words", "even" to "split evenly")[timing]
        if (source == "pictures" && timing == "estimate") timeTxt = "${PICTURE_ONLY_SECONDS.toInt()} s per picture"
        var summary = "$n scene(s) from $srcTxt. Timing: $timeTxt. Length ${mmss(total)}."
        if (pics.isNotEmpty()) summary += " Pictures: ${pics.size} (${pics.size - extras.size} used, ${extras.size} left out)."
        iss.info(summary, null, "summary")
        val out = iss.result().sortedWith(compareBy({ if (it.kind == "summary") 0 else 1 }, { if (it.scene == null) 0 else 1 }, { it.scene ?: 0 }))
        return Analysis(scenes, total, extras, out, source, timing, mode)
    }
}
