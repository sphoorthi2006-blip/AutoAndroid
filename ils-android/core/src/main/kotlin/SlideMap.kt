package ils.core

import kotlin.math.abs
import kotlin.math.ceil

enum class Kind(val label: String) { INTRO("intro"), HEADING("heading"), BODY("body"), DISCLAIMER("disclaimer"), SUBSCRIBE("subscribe") }

/** Every tunable value of the slide map lives here. Nothing is hard-coded elsewhere. */
data class SlideMapSettings(
    /** A paragraph longer than this (seconds) is split at sentence ends. 0 = never split. */
    val splitAfterSec: Double = 30.0,
    val minSentencesToSplit: Int = 4,
    val minSentencesPerPart: Int = 2,
    /** A script line of which fewer words than this share were heard in the audio is not spoken (for example the video title) and gets no image. */
    val minSpokenRatio: Double = 0.5,
    /** A short line that starts like "1. Skin Uses", "Tip 2: ...", "Step 3 ..." is a section heading and gets its own image. */
    val headingRegex: String = """^\s*(?:\d{1,3}\s*[.):]|(?:tip|step|chapter|part|day|rule|lesson|reason)\s*\d{1,3}\s*[.):\-]?)\s*\S.*$""",
    val headingMaxWords: Int = 14,
    val disclaimerRegex: String = """^\s*disclaimer\b""",
    val subscribeRegex: String = """\bsubscribe\b""",
    val subscribeMaxWords: Int = 30,
)

/** One script word with its time in the audio (used to start an element at its trigger word). */
data class TimedWord(val text: String, val start: Double, val end: Double)

data class Sentence(val text: String, val start: Double, val end: Double, val words: List<TimedWord> = emptyList())

data class Slide(
    val image: Int, val kind: Kind, val heading: String?, val start: Double, val end: Double,
    val sentences: List<Sentence>, val scriptLine: Int, val part: Int, val parts: Int,
) {
    val duration get() = end - start
}

class SlideMapResult(val slides: List<Slide>, val report: List<String>, val audioEnd: Double)

object SlideMap {
    private class Blk(val line: Int, val text: String, var kind: Kind, val sents: MutableList<String> = ArrayList())
    private class SentTok(val blk: Int, val sent: Int, val from: Int, val to: Int)

    fun build(scriptText: String, srtText: String, st: SlideMapSettings = SlideMapSettings()): SlideMapResult {
        val rep = ArrayList<String>()
        val parsed = Srt.parse(srtText)
        if (parsed.isEmpty()) throw IllegalArgumentException("No captions found in the SRT file.")
        val cleaned = Srt.clean(parsed)
        if (cleaned.watermarksRemoved > 0) rep.add("Watermark removed from ${cleaned.watermarksRemoved} place(s).")
        if (cleaned.soundTagsRemoved > 0) rep.add("Sound tags removed: ${cleaned.soundTagsRemoved}.")
        val cues = cleaned.cues
        val audioEnd = cues.last().end

        // 1. script lines -> blocks, with a kind
        val headRe = Regex(st.headingRegex, RegexOption.IGNORE_CASE)
        val discRe = Regex(st.disclaimerRegex, RegexOption.IGNORE_CASE)
        val subRe = Regex(st.subscribeRegex, RegexOption.IGNORE_CASE)
        val blocks = ArrayList<Blk>()
        for ((ln, raw) in scriptText.removePrefix("﻿").lines().withIndex()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val wc = Words.split(t).size
            if (wc == 0) continue
            val kind = when {
                discRe.containsMatchIn(t) -> Kind.DISCLAIMER
                wc <= st.headingMaxWords && headRe.matches(t) && !t.trimEnd().let { it.endsWith("?") || it.endsWith("!") } -> Kind.HEADING
                wc <= st.subscribeMaxWords && subRe.containsMatchIn(t) -> Kind.SUBSCRIBE
                else -> Kind.BODY
            }
            blocks.add(Blk(ln + 1, t, kind))
        }

        // 2. sentences and a flat word list
        val tokens = ArrayList<String>()
        val raws = ArrayList<String>()
        val sentToks = ArrayList<SentTok>()
        for ((bi, b) in blocks.withIndex()) {
            val parts = if (b.kind == Kind.BODY) splitSentences(b.text) else listOf(b.text)
            for ((si, s) in parts.withIndex()) {
                val from = tokens.size
                Words.split(s).forEach { tokens.add(Words.norm(it)); raws.add(it) }
                if (tokens.size > from) { b.sents.add(s); sentToks.add(SentTok(bi, b.sents.size - 1, from, tokens.size)) }
            }
        }

        // 3. align script words to the spoken words
        val al = Align.align(tokens, Align.srtWords(cues), audioEnd)
        val matchedShare = al.matched.count { it }.toDouble() / tokens.size.coerceAtLeast(1)
        rep.add("Script words found in the audio: ${"%.1f".format(matchedShare * 100)}% (${al.matched.count { it }} of ${tokens.size}).")
        val missed = tokens.indices.filter { !al.matched[it] }
        if (missed.isNotEmpty()) rep.add("Script words not heard in the audio (timed from their neighbours): " + missed.take(20).joinToString(", ") { raws[it] } + if (missed.size > 20) ", ..." else "")
        rep.add("Spoken words that are not in the script: ${al.srtUnmatched} of ${al.srtTotal}.")

        // 4. drop lines that were not spoken
        val keep = BooleanArray(blocks.size) { true }
        for ((bi, b) in blocks.withIndex()) {
            val mine = sentToks.filter { it.blk == bi }
            if (mine.isEmpty()) { keep[bi] = false; continue }
            val tot = mine.sumOf { it.to - it.from }
            val hit = mine.sumOf { s -> (s.from until s.to).count { al.matched[it] } }
            if (hit.toDouble() / tot < st.minSpokenRatio) {
                keep[bi] = false
                rep.add("Script line ${b.line} is not in the audio, so it gets no image: \"${b.text.take(60)}\"")
            }
        }

        // 5. one slide per kept block (long body paragraphs are split)
        val slides = ArrayList<Slide>()
        var seenHeading = false
        for ((bi, b) in blocks.withIndex()) {
            if (!keep[bi]) continue
            val sents = sentToks.filter { it.blk == bi }.map {
                Sentence(b.sents[it.sent], al.start[it.from], al.end[it.to - 1],
                    (it.from until it.to).map { k -> TimedWord(raws[k], al.start[k], al.end[k]) })
            }
            val kind = when {
                b.kind == Kind.HEADING -> { seenHeading = true; Kind.HEADING }
                b.kind == Kind.BODY && !seenHeading -> Kind.INTRO
                else -> b.kind
            }
            val cuts = if (kind == Kind.BODY || kind == Kind.INTRO) splitCuts(sents, st) else emptyList()
            val bounds = listOf(0) + cuts + listOf(sents.size)
            for (p in 0 until bounds.size - 1) {
                val part = sents.subList(bounds[p], bounds[p + 1])
                slides.add(Slide(0, kind, if (kind == Kind.HEADING) b.text else null, part.first().start, part.last().end,
                    part, b.line, p + 1, bounds.size - 1))
            }
        }
        if (slides.isEmpty()) throw IllegalArgumentException("None of the script was found in the SRT. Is it the right script?")

        // 6. no gaps: each image runs until the next one starts; the first starts at 0, the last ends with the audio
        val out = ArrayList<Slide>()
        for ((k, s) in slides.withIndex()) {
            val a = if (k == 0) 0.0 else s.start
            val z = if (k == slides.size - 1) audioEnd else slides[k + 1].start
            out.add(s.copy(image = k + 1, start = a, end = maxOf(a, z)))
        }
        val counts = Kind.values().joinToString(", ") { kd -> "${kd.label}: ${out.count { it.kind == kd }}" }
        rep.add("Images: ${out.size} ($counts). Video length ${Srt.time(audioEnd)}.")
        val split = out.filter { it.parts > 1 && it.part == 1 }
        if (split.isNotEmpty()) rep.add("Long paragraphs split at a sentence end: ${split.size} (script lines ${split.joinToString { it.scriptLine.toString() }}).")
        return SlideMapResult(out, rep, audioEnd)
    }

    /** Splits at . ? ! when a capital letter or a digit follows. Decimals like 7.35 and numbers like 2,300 stay whole. */
    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        val re = Regex("""(?<=[.!?])["'”’)]*\s+(?=["'“(]?[A-Z0-9])""")
        for (m in re.findAll(text)) {
            val quoteLen = m.value.takeWhile { !it.isWhitespace() }.length   // closing quote or bracket right after the full stop
            out.add(text.substring(from, m.range.first + quoteLen).trim())
            from = m.range.last + 1
        }
        out.add(text.substring(from).trim())
        return out.filter { it.isNotEmpty() }
    }

    /** Indexes (of the first sentence of each new part) where a long paragraph is cut: near an even split, preferring a longer pause. */
    fun splitCuts(s: List<Sentence>, st: SlideMapSettings): List<Int> {
        if (st.splitAfterSec <= 0 || s.size < st.minSentencesToSplit) return emptyList()
        val dur = s.last().end - s.first().start
        if (dur <= st.splitAfterSec) return emptyList()
        val parts = minOf(ceil(dur / st.splitAfterSec).toInt(), s.size / st.minSentencesPerPart)
        if (parts < 2) return emptyList()
        val cuts = ArrayList<Int>()
        var lo = st.minSentencesPerPart
        for (k in 1 until parts) {
            val target = s.first().start + k * dur / parts
            val hi = s.size - st.minSentencesPerPart * (parts - k)
            if (lo > hi) break
            var best = -1
            var bestCost = Double.MAX_VALUE
            for (c in lo..hi) {
                val pause = s[c].start - s[c - 1].end
                val cost = abs(s[c].start - target) - pause
                if (cost < bestCost) { bestCost = cost; best = c }
            }
            if (best < 0) break
            cuts.add(best)
            lo = best + st.minSentencesPerPart
        }
        return cuts
    }

    // ---------------------------------------------------------------- output files
    fun toJson(r: SlideMapResult): String {
        val slides = r.slides.map { s ->
            val m = LinkedHashMap<String, Any?>()
            m["image"] = s.image
            m["kind"] = s.kind.label
            if (s.heading != null) m["heading"] = s.heading
            m["start"] = s.start
            m["end"] = s.end
            if (s.parts > 1) { m["part"] = s.part; m["parts"] = s.parts }
            m["script_line"] = s.scriptLine
            m["sentences"] = s.sentences.mapIndexed { i, x -> linkedMapOf("index" to i, "text" to x.text, "start" to x.start, "end" to x.end) }
            m["elements"] = emptyList<Any>()
            m
        }
        return Json.stringify(linkedMapOf("audio_end" to r.audioEnd, "slides" to slides))
    }

    /** One caption per image, so image numbers and the editing timeline use the same numbers. */
    fun toSrt(r: SlideMapResult): String {
        val sb = StringBuilder()
        for (s in r.slides) {
            sb.append(s.image).append('\n')
            sb.append(Srt.time(s.start)).append(" --> ").append(Srt.time(s.end)).append('\n')
            if (s.heading != null) sb.append(s.heading).append('\n')
            else sb.append(s.sentences.joinToString(" ") { it.text }).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }
}
