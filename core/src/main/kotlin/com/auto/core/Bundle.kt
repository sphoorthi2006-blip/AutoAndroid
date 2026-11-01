package com.auto.core

/** One file handed to Bundle. `readText` is only called when the content is needed; durationSec is null if it cannot be read. */
data class Entry(val name: String, val size: Long, val durationSec: Double? = null, val readText: () -> String? = { null }) {
    val ext get() = name.substringAfterLast('.', "").lowercase().let { if (it.isEmpty()) "" else ".$it" }
    val stem get() = name.substringBeforeLast('.').lowercase()
}

data class BundleResult(
    var audio: Entry? = null, var click: Entry? = null, var details: Entry? = null, var script: Entry? = null, var timestamps: Entry? = null,
    var nPictures: Int = 0, val notes: MutableList<String> = mutableListOf(),
)

/** Port of qt_app/bundle.py classify(): any mix of files -> which one is the narration, click, details, script, captions. */
object Bundle {
    val IMG_EXT = setOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tif", ".tiff")
    val AUDIO_EXT = setOf(".wav", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wma", ".aif", ".aiff")
    private val TEXT_EXT = setOf(".txt", ".md", ".text")
    private val IMAGE_BLOCK = Regex("^\\W*image\\s*#?\\s*\\d+\\b", RegexOption.IGNORE_CASE)
    private val ANY_BLOCK = Regex("^\\W*(?:image|scene|shot|frame)\\s*#?\\s*\\d+\\b", RegexOption.IGNORE_CASE)
    private val TIMED = Regex("^\\W*\\[?\\(?\\s*(?:\\d+:)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?")

    /** 'details' (Image N blocks), 'details_loose' (Scene/Shot N), 'timestamps' or 'script', decided from the content. */
    fun textRole(e: Entry): String {
        val txt = try { e.readText() } catch (ex: Exception) { null } ?: return "script"
        val lines = txt.replace("\r", "\n").split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return "script"
        if (lines.count { IMAGE_BLOCK.containsMatchIn(it) } >= 3) return "details"
        val blocks = lines.filter { ANY_BLOCK.containsMatchIn(it) }
        val body = lines.filterNot { ANY_BLOCK.containsMatchIn(it) }.map { it.length }.sorted()
        val median = if (body.isEmpty()) 0 else body[body.size / 2]
        if (blocks.size >= maxOf(3, lines.size / 4) && median < 100) return "details_loose"
        if (lines.count { TIMED.containsMatchIn(it) || "-->" in it } >= maxOf(5, lines.size / 3)) return "timestamps"
        return "script"
    }

    private fun isWhisperJson(e: Entry): Boolean {
        val data = try { MiniJson(e.readText() ?: return false).parse() } catch (ex: Exception) { return false }
        fun hasStart(l: Any?) = l is List<*> && l.isNotEmpty() && (l[0] as? Map<*, *>)?.containsKey("start") == true
        return when (data) {
            is Map<*, *> -> hasStart(data["segments"]) || hasStart(data["words"])
            is List<*> -> hasStart(data)
            else -> false
        }
    }

    private fun pick(c: List<Entry>, role: String, notes: MutableList<String>, prefer: List<String> = emptyList()): Entry? {
        if (c.isEmpty()) return null
        var best: Entry? = null
        for (w in prefer) { val hit = c.filter { w in it.stem }; if (hit.isNotEmpty()) { best = hit[0]; break } }
        if (best == null) best = c.sortedByDescending { it.size }.first()
        for (x in c) if (x !== best) notes.add("More than one $role file: using '${best.name}', left out '${x.name}'.")
        return best
    }

    /** files = every non-picture file; nPictures = how many pictures the picture layer found (zip / folder / loose). */
    fun classify(files: List<Entry>, nPictures: Int = 0): BundleResult {
        val res = BundleResult(nPictures = nPictures)
        val audios = files.filter { it.ext in AUDIO_EXT }
        val ts = files.filter { it.ext == ".srt" || it.ext == ".vtt" || (it.ext == ".json" && isWhisperJson(it)) }.toMutableList()
        val texts = ArrayList<Entry>()
        for (f in files) if (f.ext in TEXT_EXT) (if (textRole(f) == "timestamps") ts else texts).add(f)
        if (audios.isNotEmpty()) {
            val durs = audios.associateWith { a -> a.durationSec ?: run { res.notes.add("Could not read the length of '${a.name}': treated as 0 s."); 0.0 } }
            val named = audios.filter { "click" in it.stem }
            var rest = audios.filter { it !in named }
            val click0 = named.firstOrNull()
            if (rest.isEmpty() && audios.size > 1) rest = audios
            val short = rest.filter { durs[it]!! < 3.0 }; val long = rest.filter { durs[it]!! >= 3.0 }
            val click = click0 ?: if (short.isNotEmpty() && long.isNotEmpty()) short[0] else null
            val pool = long.ifEmpty { rest.filter { it != click }.ifEmpty { rest } }
            val narr = pool.maxByOrNull { durs[it]!! }
            res.audio = narr; res.click = if (click != null && click != narr) click else null
            audios.filter { it != narr && it != click }.forEach { res.notes.add("Extra audio file ignored: '${it.name}'.") }
        }
        val strict = texts.filter { textRole(it) == "details" }
        val loose = texts.filter { textRole(it) == "details_loose" }
        val details = strict.ifEmpty { loose }                       // "Scene N" files only count when no "Image N" file exists
        val scripts = texts.filter { it !in details }
        res.details = pick(details, "details", res.notes, listOf("detail", "scene"))
        res.script = pick(scripts, "script", res.notes, listOf("script"))
        res.timestamps = pick(ts, "timestamps", res.notes, listOf("clean"))
        if (res.details == null) res.notes.add("MISSING: scene details (a text file with 'Image N' blocks).")
        if (nPictures == 0) res.notes.add("MISSING: pictures.")
        if (res.audio == null && res.timestamps == null) res.notes.add("No audio and no timestamps: picture lengths will only be ESTIMATED from the script words.")
        if (res.audio != null && res.click == null) res.notes.add("No click sound found. That is fine: it is optional.")
        return res
    }
}
