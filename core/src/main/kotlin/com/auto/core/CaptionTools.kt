package com.auto.core

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Caption import / export / editing on top of Timeline. The parsing itself is the existing Captions object (unchanged); this adds the editing a person does by hand. */
object CaptionTools {
    /** SRT, VTT, Whisper JSON or timed text lines -> caption clips (ids 1..n). */
    fun parse(text: String, fileName: String = ""): List<CaptionClip> {
        val rows = Captions.parseRows(text, fileName) ?: Captions.parseCues(Captions.parse(text, fileName))
        val list = rows.filter { it.text.isNotBlank() }.mapIndexed { k, c -> CaptionClip(k + 1, Math.round(c.start * 1000), max(Math.round(c.end * 1000), Math.round(c.start * 1000) + 1), c.text.trim()) }
        if (list.isEmpty()) throw PipelineError("No captions were found in '$fileName'. Use an SRT or VTT file.")
        return list.sortedBy { it.startMs }.mapIndexed { k, c -> c.copy(id = k + 1) }
    }
    private fun two(n: Long) = String.format(Locale.ROOT, "%02d", n)
    fun stamp(ms: Long, comma: Boolean): String { val m = max(0L, ms); return "${two(m / 3_600_000)}:${two(m / 60_000 % 60)}:${two(m / 1000 % 60)}${if (comma) "," else "."}${String.format(Locale.ROOT, "%03d", m % 1000)}" }
    fun toSrt(caps: List<CaptionClip>): String = caps.sortedBy { it.startMs }.mapIndexed { k, c -> "${k + 1}\n${stamp(c.startMs, true)} --> ${stamp(c.endMs, true)}\n${c.text}\n" }.joinToString("\n")
    fun toVtt(caps: List<CaptionClip>): String = "WEBVTT\n\n" + caps.sortedBy { it.startMs }.joinToString("\n") { "${stamp(it.startMs, false)} --> ${stamp(it.endMs, false)}\n${it.text}\n" }

    /** Chars per second above which captions are hard to read (common subtitle guideline: 17-21). */
    const val MAX_CPS = 21.0
    fun check(caps: List<CaptionClip>): List<String> {
        val out = ArrayList<String>(); val s = caps.sortedBy { it.startMs }
        for (c in s) {
            if (c.text.isBlank()) out.add("Caption ${c.id} is empty.")
            val cps = c.text.length * 1000.0 / max(1L, c.endMs - c.startMs); if (cps > MAX_CPS) out.add("Caption ${c.id} is too fast to read (${cps.toInt()} characters per second).")
        }
        for ((a, b) in s.zipWithNext()) if (a.endMs > b.startMs) out.add("Captions ${a.id} and ${b.id} overlap by ${a.endMs - b.startMs} ms.")
        return out
    }
}

fun Timeline.withCaptions(list: List<CaptionClip>) = copy(captions = list.sortedBy { it.startMs })
fun Timeline.caption(id: Int) = captions.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No caption with id $id")
val Timeline.nextCaptionId get() = 1 + (captions.maxOfOrNull { it.id } ?: 0)
fun Timeline.addCaption(startMs: Long, endMs: Long, text: String): Timeline { val s = max(0L, startMs); return withCaptions(captions + CaptionClip(nextCaptionId, s, max(endMs, s + Timeline.MIN_CLIP_MS), text)) }
fun Timeline.deleteCaption(id: Int): Timeline { caption(id); return withCaptions(captions.filter { it.id != id }) }

/** Cut one caption in two at atMs. The words are shared out in proportion to the time (split at a space, never in the middle of a word). Unchanged if a half would be too short. */
fun Timeline.splitCaption(id: Int, atMs: Long): Timeline {
    val c = caption(id); if (atMs < c.startMs + Timeline.MIN_CLIP_MS || atMs > c.endMs - Timeline.MIN_CLIP_MS) return this
    val words = c.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val k = if (words.size < 2) return this else Math.round(words.size * (atMs - c.startMs).toDouble() / (c.endMs - c.startMs)).toInt().coerceIn(1, words.size - 1)
    return withCaptions(captions.flatMap { if (it.id == id) listOf(c.copy(endMs = atMs, text = words.take(k).joinToString(" ")), CaptionClip(nextCaptionId, atMs, c.endMs, words.drop(k).joinToString(" "))) else listOf(it) })
}
/** Join a caption with the one that follows it in time. */
fun Timeline.mergeCaptionWithNext(id: Int): Timeline {
    val s = captions.sortedBy { it.startMs }; val i = s.indexOfFirst { it.id == id }; if (i < 0) throw IllegalArgumentException("No caption with id $id"); val n = s.getOrNull(i + 1) ?: return this
    val m = s[i].copy(endMs = max(s[i].endMs, n.endMs), text = (s[i].text.trim() + " " + n.text.trim()).trim())
    return withCaptions(s.filter { it.id != n.id }.map { if (it.id == id) m else it })
}
/** Move every caption by deltaMs (negative = earlier). Nothing moves before 0: the shift is limited so the first caption lands at 0 at the earliest. */
fun Timeline.shiftCaptions(deltaMs: Long): Timeline {
    val first = captions.minOfOrNull { it.startMs } ?: return this; val d = max(deltaMs, -first)
    return withCaptions(captions.map { it.copy(startMs = it.startMs + d, endMs = it.endMs + d) })
}
