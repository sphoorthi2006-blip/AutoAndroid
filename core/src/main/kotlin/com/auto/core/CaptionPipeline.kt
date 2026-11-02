package com.auto.core

import java.io.File
import kotlin.math.max

/**
 * Port of the desktop's new Analyse (scriptvideo.engine.analyse): FOUR OPTIONAL INPUTS - timestamps (captions), audio, pictures, script. No Details file.
 * Nothing is mandatory; whatever is missing or wrong becomes a WARNING and the timeline is still built. Only "nothing at all" throws.
 *
 *   scenes (what is on screen)  : timestamps cues  >  script lines  >  pictures  >  the audio alone
 *   timing (when pictures change): timestamps (exact cue starts)  >  verified word times (backend) for a script  >  estimate from words  >  3 s each
 *   pictures (which one where)  : the number in the file name (7.jpg = scene 7)  >  file-name order (with a warning)
 *   a scene with no picture -> black screen with its text;  pictures beyond the last scene -> left out
 */
object CaptionPipeline {
    const val LONG_SCENE_S = 7.0
    const val SHORT_SCENE_S = 0.6
    const val MIN_CUT_GAP_S = 0.2
    const val PICTURE_ONLY_SECONDS = 3.0
    private const val MAX_LISTED = 40

    private val NUM_IN_NAME = Regex("(?:image|img|scene|shot|frame|pic|picture|slide|prompt|cue)[\\s_\\-#.]*0*(\\d+)", RegexOption.IGNORE_CASE)
    private val COPY_SUFFIX = Regex("\\s*\\(\\d+\\)$")
    private val TAGS = Regex("<[^>]+>|\\{\\\\[^}]*\\}")
    private val WS = Regex("\\s+")

    private fun mmss(t: Double): String { val x = max(t, 0.0); return String.format(java.util.Locale.ROOT, "%d:%04.1f", (x / 60).toInt(), x % 60) }
    private fun cleanCue(t: String) = TAGS.replace(t, "").replace(WS, " ").trim()
    private fun tok(t: String) = Details.tokens(t)

    /** '7.jpg', '007.png', 'Image 7.jpg', 'scene_7_final.png', 'Prompt 7.png', '7 (1).jpg' -> 7. Null when the name has no number or several that cannot be told apart. */
    fun imageNumber(path: String): Int? {
        val stem = COPY_SUFFIX.replace(Matching.stemOf(path), "")
        val m = NUM_IN_NAME.find(stem)
        val runs = if (m != null) listOf(m.groupValues[1]) else Regex("\\d+").findAll(stem).map { it.value }.toList()
        if (runs.size != 1) return null
        val n = runs[0].toLongOrNull() ?: return null
        return if (n < 100000) n.toInt() else null            // a date or a long id, not a scene number
    }

    /** One scene per script line; a very long paragraph is cut into sentences so it does not become one long still picture. */
    fun scriptSceneLines(text: String): List<String> {
        val out = ArrayList<String>()
        val split = Regex("(?<=[.!?])[\"')\\]]*\\s+(?=[\"'(\\[]?[A-Z0-9])")
        for (raw in Details.linesOf(text)) {
            val ln = raw.replace(Regex("^\\s*#+\\s*"), "").trim()
            if (tok(ln).isEmpty()) continue
            if (tok(ln).size > 30) { val parts = ln.split(split).map { it.trim() }.filter { it.isNotEmpty() }; out.addAll(parts.ifEmpty { listOf(ln) }) } else out.add(ln)
        }
        return out
    }

    class Slots(val slots: List<String?>, val extras: List<String>, val mode: String)

    /** Pictures -> the n scenes. One picture (or null) per scene, the pictures left out, and how they were placed ("number" or "order"). */
    fun assignPictures(pics0: List<String>, n: Int, warn: MutableList<String>): Slots {
        val pics = pics0.sortedWith { a, b -> Matching.naturalCompare(a, b) }
        val nums = pics.associateWith { imageNumber(it) }
        val numbered = pics.filter { nums[it] != null }
        if (numbered.isNotEmpty() && numbered.size >= max(1, (0.6 * pics.size + 0.999).toInt())) {
            val shift = if (numbered.minOf { nums[it]!! } == 0 && numbered.none { nums[it] == n }) 1 else 0
            if (shift == 1) warn.add("The picture numbers start at 0: picture 0 was used for scene 1, picture 1 for scene 2, and so on.")
            val byNum = java.util.TreeMap<Int, MutableList<String>>()
            // '7.jpg' before the copy '7 (1).jpg'
            for (p in numbered.sortedWith(compareBy<String> { COPY_SUFFIX.containsMatchIn(Matching.stemOf(it)) }.thenComparator { a, b -> Matching.naturalCompare(a, b) }))
                byNum.getOrPut(nums[p]!! + shift) { ArrayList() }.add(p)
            val slots = (0 until n).map { k -> byNum[k + 1]?.first() }
            val extras = ArrayList<String>()
            for ((num, lst) in byNum) if (lst.size > 1) {
                warn.add("Picture number $num exists ${lst.size} times (${lst.take(4).joinToString(", ") { Matching.nameOf(it) }}). Used '${Matching.nameOf(lst[0])}', left out the others.")
                extras += lst.drop(1)
            }
            val beyond = byNum.keys.filter { it > n }
            if (beyond.isNotEmpty()) {
                extras += beyond.map { byNum[it]!![0] }
                warn.add("${beyond.size} picture(s) are numbered beyond the last scene ($n) and were left out: " + beyond.take(8).joinToString(", ") { Matching.nameOf(byNum[it]!![0]) } + if (beyond.size > 8) " ..." else "")
            }
            val loose = pics.filter { nums[it] == null }
            if (loose.isNotEmpty()) {
                extras += loose
                warn.add("${loose.size} picture(s) have no number in their file name and were not placed: " + loose.take(8).joinToString(", ") { Matching.nameOf(it) } + if (loose.size > 8) " ..." else "")
            }
            return Slots(slots, extras, "number")
        }
        val slots = (0 until n).map { k -> pics.getOrNull(k) }
        val extras = if (pics.size > n) pics.drop(n) else emptyList()
        if (pics.isNotEmpty()) warn.add("The picture file names carry no scene numbers, so the pictures were placed in file-name order. Name them 1, 2, 3 ... (or Image 1, Image 2 ...) to be sure each one lands on its own scene.")
        if (extras.isNotEmpty()) warn.add("${extras.size} extra picture(s) beyond the last scene ($n) were left out.")
        return Slots(slots, extras, "order")
    }

    private fun runs(nums: List<Int>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (n in nums.sorted()) if (out.isNotEmpty() && n == out.last().second + 1) out[out.size - 1] = Pair(out.last().first, n) else out.add(Pair(n, n))
        return out
    }

    /** Captions in time order, empty ones dropped. Problems become warnings. */
    private fun prepareRows(rows: List<Cue>, warn: MutableList<String>): List<Cue> {
        var out = rows.mapNotNull { r -> val t = cleanCue(r.text); if (t.isEmpty()) null else Cue(r.start, max(r.end, r.start), t) }
        val dropped = rows.size - out.size
        if (dropped > 0) warn.add("$dropped caption(s) without text were skipped; scene numbers count only the captions that have text.")
        if ((1 until out.size).any { out[it].start < out[it - 1].start }) {
            out = out.sortedBy { it.start }
            warn.add("The timestamps are not in time order, so they were sorted by start time. Scene numbers follow the sorted order.")
        }
        for (k in 1 until out.size) if (out[k].start < out[k - 1].end - 0.15)
            warn.add("Scene ${k + 1} starts at ${mmss(out[k].start)}, before the previous caption ends (${mmss(out[k - 1].end)}): overlapping timestamps.")
        return out
    }

    /** One scene per caption cue. The picture changes at the cue's start (a hair earlier when there is a gap, so it is on screen before the first word). */
    fun scenesFromCues(rows: List<Cue>, total: Double, lead: Double = 0.06): List<Scene> {
        val cuts = arrayListOf(0.0)
        for (k in 1 until rows.size) {
            val gap = rows[k].start - rows[k - 1].end
            cuts.add(max(rows[k].start - minOf(lead, max(gap, 0.0) / 2), cuts.last() + MIN_CUT_GAP_S))
        }
        val endAll = maxOf(total, rows.last().end, cuts.last() + MIN_CUT_GAP_S)
        return rows.mapIndexed { i, r -> Scene(r.text, cuts[i], if (i + 1 < cuts.size) cuts[i + 1] else endAll) }
    }

    private fun estimateScenes(texts: List<String>): List<Scene> {
        var t = 0.0; val out = ArrayList<Scene>()
        for (txt in texts) {
            val w = tok(txt).size
            val d = if (w > 0) max(w / Captions.WORDS_PER_SECOND, Align.MIN_SCENE_SECONDS) else PICTURE_ONLY_SECONDS
            out.add(Scene(txt, Math.rint(t * 1000) / 1000, Math.rint((t + d) * 1000) / 1000)); t += d
        }
        return out
    }

    private fun crossCheck(script: String, rows: List<Cue>, warn: MutableList<String>) {
        val st = tok(script); val ct = rows.flatMap { tok(it.text) }
        if (st.isEmpty() || ct.isEmpty()) return
        val cs = st.groupingBy { it }.eachCount(); val cc = ct.groupingBy { it }.eachCount()
        val common = cs.entries.sumOf { minOf(it.value, cc[it.key] ?: 0) }
        val inCaps = common.toDouble() / st.size; val inScript = common.toDouble() / ct.size
        val worst = minOf(inCaps, inScript)
        val msg = "${Math.round(inCaps * 100)}% of the script's words are in the timestamps and ${Math.round(inScript * 100)}% of the timestamp words are in the script"
        if (worst < 0.85) warn.add("The script and the timestamps do not match well: $msg. Check that both files belong to the same narration. The scenes follow the timestamps.")
        else if (worst < 0.97) warn.add("The script and the timestamps differ a little: $msg.")
        if (worst >= 0.5) {
            val vocab = st.toSet()
            rows.forEachIndexed { k, r -> val tk = tok(r.text); if (tk.size >= 3 && tk.count { it in vocab }.toDouble() / tk.size < 0.5) warn.add("Scene ${k + 1}: this caption is not in the script: \"${r.text.take(60)}\"") }
        }
        val nLines = scriptSceneLines(script).size
        if (nLines != rows.size) warn.add("The script has $nLines lines and the timestamps ${rows.size} captions. One scene per caption is used.")
    }

    /**
     * rows = caption cues (Captions.parseRows) or null; words = verified spoken words from the backend (only used when there are no cues);
     * audioSeconds = narration length (0 when no audio); pictureSources = folders / ZIPs / single pictures. Returns the same Analysis type the app already uses.
     */
    fun analyse(rows0: List<Cue>?, scriptText: String, words: List<TimedWord>?, audioSeconds: Double,
                pictureSources: List<File>, workDir: File, hasAudio: Boolean = audioSeconds > 0): Pipeline.Analysis {
        val warn = ArrayList<String>()
        val rows = if (rows0.isNullOrEmpty()) emptyList() else prepareRows(rows0, warn)
        if (!rows0.isNullOrEmpty() && rows.isEmpty()) warn.add("No captions were found in the timestamps file, so it was not used.")
        val lines = if (scriptText.isNotBlank()) scriptSceneLines(scriptText) else emptyList()
        val pics = if (pictureSources.isEmpty()) emptyList() else Pipeline.collectPictures(pictureSources, workDir).first.map { it.path }.sortedWith { a, b -> Matching.naturalCompare(a, b) }
        val dur = audioSeconds

        // 1) WHAT are the scenes
        val source: String; val texts: List<String>; var ordered = emptyList<String>()
        when {
            rows.isNotEmpty() -> { source = "timestamps"; texts = rows.map { it.text } }
            lines.isNotEmpty() -> { source = "script"; texts = lines }
            pics.isNotEmpty() -> { source = "pictures"; ordered = pics.sortedWith(compareBy<String> { imageNumber(it) == null }.thenBy { imageNumber(it) ?: 0 }.thenComparator { a, b -> Matching.naturalCompare(a, b) }); texts = ordered.map { "" } }
            hasAudio -> { source = "audio"; texts = listOf("") }
            else -> throw PipelineError("Nothing to analyse yet. Add at least one of: timestamps, audio, pictures or a script.")
        }
        val n = texts.size

        // 2) WHEN does each picture change
        val scenes: List<Scene>; val timing: String
        when {
            source == "timestamps" -> {
                scenes = scenesFromCues(rows, dur); timing = "timestamps"
                if (scriptText.isNotBlank()) crossCheck(scriptText, rows, warn)
                if (hasAudio) {
                    val last = rows.last().end
                    if (last > dur + 1.0) {
                        val firstOut = rows.indexOfFirst { it.start >= dur }.let { if (it < 0) n - 1 else it }
                        warn.add("The timestamps run to ${mmss(last)} but the audio ends at ${mmss(dur)}: the scenes after the end of the audio have no narration. (from scene ${firstOut + 1})")
                    } else if (dur - last > 3.0 && dur - scenes.last().start <= LONG_SCENE_S)
                        warn.add("The audio is ${"%.1f".format(java.util.Locale.ROOT, dur - last)} s longer than the last caption, so the last picture stays on screen until the end of the audio.")
                }
            }
            source == "script" && words != null && words.isNotEmpty() -> {
                val r = try { Align.align(words, texts, "", null, emptyMap(), 0.06) } catch (e: PipelineError) { null }
                if (r != null) { scenes = r.first; timing = "audio"; if (r.second.match < 0.8) warn.add("Only ${Math.round(r.second.match * 100)}% of the spoken words were found in the script. Check the timing of the cuts.") }
                else { scenes = estimateScenes(texts); timing = "estimate"; warn.add("The narration could not be matched to the script. Picture lengths were estimated from the words.") }
            }
            source == "audio" -> { scenes = listOf(Scene("", 0.0, dur)); timing = "even" }
            hasAudio && dur > 0 && source == "pictures" -> { val d = dur / n; scenes = texts.mapIndexed { i, t -> Scene(t, i * d, (i + 1) * d) }; timing = "even" }
            else -> {
                scenes = estimateScenes(texts); timing = "estimate"
                if (source == "script") warn.add("No timestamps and no timed audio: picture lengths are only ESTIMATED from the number of words in each script line. Add the timestamps and Analyse again for the real timing.")
                else if (source == "pictures") warn.add("No timestamps and no audio: every picture gets ${"%.0f".format(java.util.Locale.ROOT, PICTURE_ONLY_SECONDS)} s.")
            }
        }
        scenes.forEachIndexed { k, s -> s.num = k + 1 }
        scenes.last().end = max(scenes.last().end, dur)

        // 3) WHICH picture is on each scene
        var extras: List<String> = emptyList(); var mode = ""
        if (source == "pictures") {
            scenes.zip(ordered).forEach { (s, p) -> s.image = p }; mode = "pictures"
            val got = ordered.mapNotNull { imageNumber(it) }.distinct().sorted()
            val gaps = if (got.isEmpty()) emptyList() else (got.first()..got.last()).filter { it !in got }
            if (gaps.isNotEmpty()) warn.add("Picture numbers skip " + gaps.take(15).joinToString(", ") + (if (gaps.size > 15) " ..." else "") + ": a picture may be missing (the scenes simply follow each other without a gap).")
        } else if (source == "audio") warn.add("Only the audio was added: one black scene as long as the narration. Add pictures, timestamps or a script to build the video.")
        else if (pics.isNotEmpty()) {
            val sl = assignPictures(pics, n, warn); extras = sl.extras; mode = sl.mode
            scenes.zip(sl.slots).forEach { (s, p) -> s.image = p }
            for ((a, b) in runs(scenes.filter { it.image == null }.map { it.num })) {
                if (a == b) warn.add("Scene $a has no picture (picture number $a was not found): it is a black screen with its text.")
                else warn.add("Scenes $a-$b (${b - a + 1} scenes) have no picture" + (if (mode == "order") " (only ${pics.size} pictures for $n scenes)" else "") + ": black screens with their text.")
            }
        } else warn.add("No pictures were added: all $n scene(s) are black screens with their text.")
        // 4) are the pictures themselves fine?
        val bad = Validation.unreadable(scenes.mapNotNull { it.image }).toSet()
        for (s in scenes) if (s.image in bad) { warn.add("Scene ${s.num}: the picture '${Matching.nameOf(s.image!!)}' cannot be opened (damaged?). The scene is a black screen with its text."); s.image = null }
        // 5) how long does each picture stay?
        if (source != "audio") for (s in scenes) {
            val d = s.end - s.start
            if (d > LONG_SCENE_S) warn.add("Scene ${s.num} is ${"%.1f".format(java.util.Locale.ROOT, d)} s long: one picture stays on screen for a long time. Split that caption in the timestamps file, or add pictures.")
            else if (d < SHORT_SCENE_S) warn.add("Scene ${s.num} is only ${"%.2f".format(java.util.Locale.ROOT, d)} s long: the picture will flash by.")
        }
        var cum = 0.0; for (s in scenes) { s.outStart = cum; cum += max(s.end - s.start, 0.04) }
        val total = max(dur, scenes.last().end)
        val srcTxt = mapOf("timestamps" to "$n captions", "script" to "$n script lines", "pictures" to "$n pictures", "audio" to "the audio")[source]
        val timeTxt = mapOf("timestamps" to "timestamps (exact)", "audio" to "audio", "estimate" to "estimated from the words", "even" to "split evenly")[timing]
        var summary = "$n scene(s) from $srcTxt. Timing: $timeTxt. Length ${mmss(total)}."
        if (pics.isNotEmpty()) summary += " Pictures: ${pics.size} (${pics.size - extras.size} used, ${extras.size} left out)."
        // long lists of one kind are cut so the screen stays readable
        val shown = if (warn.size > MAX_LISTED * 3) warn.take(MAX_LISTED * 3) + "... and ${warn.size - MAX_LISTED * 3} more warnings." else warn
        val all = listOf(summary) + shown
        val tr = TimingResult(scenes, all, null, total)
        val mr = Matching.MatchResult(scenes, emptyList(), emptyList(), emptyList(), true, all)
        return Pipeline.Analysis(tr, mr)
    }
}
