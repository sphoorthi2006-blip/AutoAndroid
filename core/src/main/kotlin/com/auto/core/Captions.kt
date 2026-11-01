package com.auto.core

import kotlin.math.max

class PipelineError(message: String) : Exception(message)
data class TimedWord(val text: String, val start: Double, val end: Double)
data class Cue(val start: Double, val end: Double, val text: String)

/** Port of scriptvideo.engine.parse_timestamps / parse_cues: reads ANY caption file into timed words. */
object Captions {
    const val WORDS_PER_SECOND = 2.5
    private const val TS = "(?:\\d+:)?\\d{1,2}:\\d{2}(?:[.,]\\d{1,3})?"
    private val TS_RE = Regex(TS)
    private val TS_START = Regex("^$TS")
    private val WATERMARK = Regex("\\(\\s*Transcribed by [^)]*\\)\\s*", RegexOption.IGNORE_CASE)   // free-plan watermark
    private val GAP = Regex("[\\s\\-–—>\\]\\)=to]*")
    private val LEAD_JUNK = Regex("^[\\s\\]\\)\\-–—:>]+")
    private val LEAD_BRACKET = Regex("^[\\[\\(\\s]+")
    private val JSON_START = Regex("^\\s*(\\{|\\[\\s*[\\{\\[])")
    private val WS = Regex("\\s+")

    fun toSec(t: String): Double = t.replace(',', '.').split(':').fold(0.0) { acc, x -> acc * 60 + x.toDouble() }

    /** Spread the words of one timed line over a..b (by letters), giving word-level times. */
    fun spread(text: String, a: Double, b: Double, out: MutableList<TimedWord>) {
        val ws = text.trim().split(WS).filter { it.isNotEmpty() }
        if (ws.isEmpty()) return
        val bb = max(b, a + 0.05 * ws.size)
        val tot = ws.sumOf { (it.length + 1).toDouble() }
        var t = a
        for (w in ws) { val d = (bb - a) * (w.length + 1) / tot; out.add(TimedWord(w, t, t + d)); t += d }
    }

    /** raw = file text, fileName only used to spot ".json". Understands SRT, VTT, Whisper JSON (segments or words), and timed text lines. */
    fun parse(raw0: String, fileName: String = ""): List<TimedWord> {
        val raw = WATERMARK.replace(raw0, "")
        if (fileName.lowercase().endsWith(".json") || JSON_START.containsMatchIn(raw)) return parseJson(raw)
        val rows = ArrayList<Triple<Double, Double?, String>>()
        val lines = raw.replace("\r", "").split("\n").map { it.trimEnd() }
        var i = 0
        while (i < lines.size) {
            val ln = lines[i].trim()
            val found = TS_RE.findAll(ln).toList()
            if ("-->" in ln && found.size >= 2) {                       // SRT / VTT cue: text on the following lines
                val a = toSec(found[0].value); val b = toSec(found[1].value)
                val txt = ArrayList<String>(); i++
                while (i < lines.size && lines[i].isNotBlank()) { txt.add(lines[i].trim()); i++ }
                rows.add(Triple(a, b, txt.joinToString(" ")))
            } else if (found.isNotEmpty() && TS_START.containsMatchIn(LEAD_BRACKET.replace(ln, ""))) {
                val a = toSec(found[0].value)
                var b: Double? = null
                var endPos = found[0].range.last + 1
                if (found.size > 1 && GAP.matches(ln.substring(found[0].range.last + 1, found[1].range.first))) {
                    b = toSec(found[1].value); endPos = found[1].range.last + 1
                }
                rows.add(Triple(a, b, LEAD_JUNK.replace(ln.substring(endPos), "")))
            }
            i++
        }
        val out = ArrayList<TimedWord>()
        for ((k, r) in rows.withIndex()) {
            val (a, b0, txt) = r
            val b = b0 ?: if (k + 1 < rows.size) rows[k + 1].first else a + max(txt.split(WS).count { it.isNotEmpty() } / WORDS_PER_SECOND, 1.0)
            spread(txt, a, b, out)
        }
        if (out.isEmpty()) throw PipelineError("No timestamps were found in the timestamps file. Use SRT, or lines like '[00:01.20 - 00:03.50] text'.")
        return out
    }

    private fun d(x: Any?): Double = when (x) { is Number -> x.toDouble(); is String -> x.toDouble(); else -> throw PipelineError("bad time value") }
    private fun wordOf(w: Map<*, *>) = TimedWord((w["word"] ?: w["text"] ?: "").toString().trim(), d(w["start"]), d(w["end"]))

    private fun parseJson(raw: String): List<TimedWord> {
        val data = try { MiniJson(raw).parse() } catch (e: Exception) { throw PipelineError("Could not read the JSON captions file: ${e.message}") }
        val segs = if (data is Map<*, *>) data["segments"] else data
        val segList = (segs as? List<*>) ?: emptyList<Any?>()
        if (data is Map<*, *> && segList.isEmpty()) {
            val ws = data["words"] as? List<*>
            if (!ws.isNullOrEmpty()) return ws.map { wordOf(it as Map<*, *>) }
        }
        val out = ArrayList<TimedWord>()
        for (sg in segList) {
            sg as? Map<*, *> ?: continue
            val ws = sg["words"] as? List<*>
            if (!ws.isNullOrEmpty()) ws.forEach { out.add(wordOf(it as Map<*, *>)) }
            else spread((sg["text"] ?: "").toString(), d(sg["start"]), d(sg["end"]), out)
        }
        return out.filter { it.text.isNotEmpty() }
    }

    /** Caption lines: timed words grouped into sentences (end at . ? ! or after maxWords or a pause longer than maxGap). */
    fun parseCues(words: List<TimedWord>, maxWords: Int = 18, maxGap: Double = 1.0): List<Cue> {
        val out = ArrayList<Cue>(); val cur = ArrayList<TimedWord>()
        fun flush() { if (cur.isNotEmpty()) { out.add(Cue(cur.first().start, cur.last().end, cur.joinToString(" ") { it.text })); cur.clear() } }
        val sentEnd = Regex("[.?!][\"')\\]]*$"); val num = Regex("\\d+\\.")
        for (w in words) {
            if (cur.isNotEmpty() && w.start - cur.last().end > maxGap) flush()
            cur.add(w)
            if ((sentEnd.containsMatchIn(w.text) && !num.matches(w.text)) || cur.size >= maxWords) flush()
        }
        flush(); return out
    }
}
