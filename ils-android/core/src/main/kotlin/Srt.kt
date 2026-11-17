package ils.core

import java.util.Locale

data class Cue(val index: Int, val start: Double, val end: Double, val text: String)

object Srt {
    private val timeRe = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""")

    private fun secs(h: String, m: String, s: String, ms: String): Double =
        h.toInt() * 3600.0 + m.toInt() * 60.0 + s.toInt() + ms.padEnd(3, '0').toInt() / 1000.0

    fun parse(raw: String): List<Cue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<Cue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.trim().split('\n')
            val ti = lines.indexOfFirst { timeRe.containsMatchIn(it) }
            if (ti < 0) continue
            val m = timeRe.find(lines[ti])!!.groupValues
            val body = lines.drop(ti + 1).joinToString(" ") { it.trim() }.trim()
            out.add(Cue(out.size + 1, secs(m[1], m[2], m[3], m[4]), secs(m[5], m[6], m[7], m[8]), body))
        }
        return out
    }

    /** Transcriber advertising that must never be treated as speech. Matched by wording, not by position. */
    private val watermarkRes = listOf(
        Regex("""\(\s*Transcribed by TurboScribe[^)]*\)""", RegexOption.IGNORE_CASE),
        Regex("""Transcribed by TurboScribe\.?(\s*Go Unlimited to remove this message\.?)?""", RegexOption.IGNORE_CASE),
        Regex("""Go Unlimited to remove this message\.?""", RegexOption.IGNORE_CASE),
        Regex("""\(?\s*Transcribed by (https?://)?otter\.ai[^)\n]*\)?""", RegexOption.IGNORE_CASE),
        Regex("""\(?\s*Transcribed by Rev\.com[^)\n]*\)?""", RegexOption.IGNORE_CASE),
        Regex("""Subtitles by the Amara\.org community""", RegexOption.IGNORE_CASE),
    )
    private val soundTagRes = listOf(
        Regex("""\[[^\]]*\]"""),
        Regex("""\(\s*(music|applause|laughter|silence|noise)[^)]*\)""", RegexOption.IGNORE_CASE),
    )

    class Cleaned(val cues: List<Cue>, val watermarksRemoved: Int, val soundTagsRemoved: Int, val emptyCuesDropped: Int)

    /** Removes watermarks and sound tags. A cue keeps its times; a cue left empty is dropped. */
    fun clean(cues: List<Cue>): Cleaned {
        var wm = 0; var tags = 0; var dropped = 0
        val out = ArrayList<Cue>()
        for (c in cues) {
            var t = c.text
            for (re in watermarkRes) { val n = re.findAll(t).count(); if (n > 0) { wm += n; t = re.replace(t, " ") } }
            for (re in soundTagRes) { val n = re.findAll(t).count(); if (n > 0) { tags += n; t = re.replace(t, " ") } }
            t = t.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) { dropped++; continue }
            out.add(Cue(out.size + 1, c.start, c.end, t))
        }
        return Cleaned(out, wm, tags, dropped)
    }

    fun time(t: Double): String {
        val ms = Math.round(t * 1000.0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
