package com.auto.core

data class SceneItem(
    val num: Int?, val spoken: String, val full: String, val labels: String, val visual: String,
    val numbers: String = "", val emotion: String = "", val meaning: String = "", val type: String = "", val section: String = "",
)

/** Port of scriptvideo.engine: tokens, is_heading, parse_scene_file. */
object Details {
    private val BLOCK = Regex("^\\s*(?:image|scene|shot|frame)\\s*#?\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
    private val SECTION_WORDS = Regex(
        "^(intro(duction)?|outro|conclusion|epilogue|prologue|summary|overview|section|part|chapter|act|segment|hook|ending|closing|body|background|aftermath)\\b",
        RegexOption.IGNORE_CASE)
    private val FIELD = Regex("^(?:(?:image|scene|shot|frame)\\s*#?\\s*\\d+\\s*[-:\\u2013\\u2014]?\\s*)?([A-Za-z][A-Za-z ]{1,25}):\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val VISUAL_KEYS = listOf("subject", "action", "key object", "setting", "location", "scene description", "description", "visual",
        "characters", "background", "era", "time period")
    private const val JUNK = "#*=-_[]() \t"
    private val SMALL = setOf("of", "the", "a", "an", "and", "in", "on", "at", "to", "for", "vs", "or", "by", "with", "from")

    fun isBlock(line: String) = BLOCK.containsMatchIn(line)
    fun linesOf(text: String) = text.replace("\r", "\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    fun tokens(text: String): List<String> =
        Regex("[a-z0-9\\u00c0-\\u024f\\u0900-\\u0dff]+").findAll(text.lowercase().replace('\u2019', '\'').replace('\u2018', '\'').replace("'", "")).map { it.value }.toList()
    fun cleanTitle(t: String) = t.trim().trim { it in JUNK }.replace(Regex("\\s+"), " ").trim()

    /** A short title line such as 'INTRO', '## Section 2', 'Part 3: The Siege', '[Outro]'. */
    fun isHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 80 || isBlock(t)) return false
        val t2 = t.trim { it in JUNK }
        if (t2.isEmpty()) return false
        if (Regex("^(#{1,6}\\s|={2,}|\\*\\*)").containsMatchIn(t) || (t.startsWith("[") && t.endsWith("]"))) return true
        if (Regex("^\\d{1,2}\\s*[.)]\\s+\\S").containsMatchIn(t) && t2.split(Regex("\\s+")).size <= 10 && !t.endsWith(".") && !t.endsWith(",") && !t.endsWith(";")) return true
        if (t.last() in ".,;?!") return false
        if (":" in t2 && !SECTION_WORDS.containsMatchIn(t2)) return false
        if (SECTION_WORDS.containsMatchIn(t2)) return t2.split(Regex("\\s+")).size <= 10
        val words = t2.split(Regex("\\s+"))
        val titleLike = words.all { it.first().isUpperCase() || it.lowercase() in SMALL || it.first().isDigit() }
        val upper = t2.none { it.isLowerCase() } && t2.any { it.isUpperCase() }
        return words.size <= 8 && (upper || titleLike)
    }

    /** Scene details as "Image N - Script line: ..." blocks, or simply one scene per line. */
    fun parseSceneFile(text: String): List<SceneItem> {
        val raw = linesOf(text)
        val blocks = ArrayList<Pair<MutableList<String>, String>>()
        var cur: MutableList<String>? = null; var heading = ""; var curSec = ""
        for ((k, ln) in raw.withIndex()) {
            if (isBlock(ln)) {
                if (heading.isNotEmpty()) { curSec = heading; heading = "" }
                cur = mutableListOf(ln); blocks.add(cur to curSec)
            } else if (isHeading(ln) && k + 1 < raw.size && isBlock(raw[k + 1])) heading = cleanTitle(ln)
            else cur?.add(ln)
        }
        if (blocks.size < 2) return linesOf(text).map { SceneItem(null, it, it, "", it) }
        return blocks.map { (b, sec) ->
            val f = LinkedHashMap<String, String>()
            for (l in b) FIELD.find(l)?.let { m -> f.putIfAbsent(m.groupValues[1].trim().lowercase(), m.groupValues[2].trim()) }
            fun g(k: String) = f[k].orEmpty()
            var spoken = listOf("script line", "script", "narration", "line").map { g(it) }.firstOrNull { it.isNotEmpty() } ?: ""
            spoken = spoken.trim().trim('"', '\u201c', '\u201d').trim()
            if (spoken.isEmpty()) spoken = BLOCK.replaceFirst(b[0], "").trim { it in " -:" }
            val visual = VISUAL_KEYS.filter { g(it).isNotEmpty() }.joinToString(". ") { g(it) }
            var lab = g("labels")
            if (lab.trim().lowercase().startsWith("none") || lab.trim().lowercase() in setOf("n/a", "-")) lab = ""
            SceneItem(BLOCK.find(b[0])?.groupValues?.get(1)?.toInt(), spoken, b.joinToString(" "), lab, visual.ifEmpty { spoken },
                g("numbers"), g("emotion"), g("plain meaning"), g("line type"), sec)
        }
    }
}

/** Port of scoped_items: if the script has only SOME sections of the details, only those are used. */
fun scopedItems(items: List<SceneItem>, scriptText: String, warnings: MutableList<String>): List<SceneItem> {
    if (scriptText.isEmpty()) return items
    val det = ArrayList<String>()
    for (it in items) { val t = it.section; if (t.isNotEmpty() && (det.isEmpty() || det.last() != t)) det.add(t) }
    if (det.size < 2) return items
    val st = Details.tokens(scriptText)
    val sjoin = " " + st.joinToString(" ") + " "
    val shingles = HashSet<List<String>>(); for (i in 0 until st.size - 2) shingles.add(st.subList(i, i + 3))
    fun present(line: String): Boolean {
        val tk = Details.tokens(line)
        if (tk.isEmpty()) return true
        if (tk.size < 3) return (" " + tk.joinToString(" ") + " ") in sjoin
        val sh = (0 until tk.size - 2).map { tk.subList(it, it + 3) }
        return sh.count { it in shingles }.toDouble() / sh.size >= 0.6
    }
    val kept = ArrayList<String>(); val dropped = ArrayList<String>()
    for (t in det) {
        val its = items.filter { it.section == t }
        val share = its.count { present(it.spoken) }.toDouble() / maxOf(its.size, 1)
        (if (share >= 0.5) kept else dropped).add(t)
    }
    if (kept.isEmpty() || dropped.isEmpty()) return items
    warnings.add("Your script has only: " + kept.joinToString(", ") + ". The other sections of the scene details (" +
        dropped.joinToString(", ") + ") are not in the script and are ignored, whatever pictures you give.")
    return items.filter { it.section in kept }
}
