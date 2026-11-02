package com.auto.core

import kotlin.math.max
import kotlin.math.min

/** Small tested helpers for the Text Studio and Captions sheets (steps 28-31). */
object StudioTools {
    /** "1:05", "1:05.250", "0:01:05,250" or plain seconds "65.25" -> ms. null when it is not a time. */
    fun parseStamp(text: String): Long? {
        val t = text.trim().replace(',', '.'); if (t.isEmpty()) return null
        val parts = t.split(':'); if (parts.size > 3) return null
        var sec = 0.0
        for (p in parts) { val v = p.toDoubleOrNull() ?: return null; if (v < 0 || p.startsWith("-") || p.startsWith("+")) return null; sec = sec * 60 + v }
        if (parts.size >= 2 && parts.drop(1).any { (it.toDoubleOrNull() ?: 0.0) >= 60.0 }) return null
        return Math.round(sec * 1000)
    }
    /** ms -> "m:ss.mmm" for the caption time fields. */
    fun stamp(ms: Long): String { val m = max(0L, ms); return String.format(java.util.Locale.ROOT, "%d:%02d.%03d", m / 60_000, m / 1000 % 60, m % 1000) }
    /** Default length of a new text clip. */
    const val NEW_TEXT_MS = 3000L
}

private val Timeline.spanMs get() = max(audioMs, endMs)

/** New text at the playhead, NEW_TEXT_MS long; shifted back so it fits inside the video. The new id is the timeline's nextTextId from BEFORE the call. */
fun Timeline.addTextAt(playMs: Long, text: String, style: OverlayStyle, durMs: Long = StudioTools.NEW_TEXT_MS): Timeline {
    val s = playMs.coerceIn(0L, max(0L, spanMs - durMs)); return addText(s, s + durMs, text, style)
}
fun Timeline.textsAt(ms: Long): List<TextClip> = texts.filter { ms >= it.startMs && ms < it.endMs }
fun Timeline.captionAt(ms: Long): CaptionClip? = captions.firstOrNull { ms >= it.startMs && ms < it.endMs }
/** Id of the text to select when the person taps at ms: the topmost (last started) one, or null. */
fun Timeline.textIdAt(ms: Long): Int? = textsAt(ms).maxByOrNull { it.startMs }?.id
fun Timeline.addCaptionAt(playMs: Long, durMs: Long = 2000L): Timeline { val s = playMs.coerceIn(0L, max(0L, spanMs - 500L)); return addCaption(s, min(s + durMs, max(s + Timeline.MIN_CLIP_MS, spanMs)), "New caption") }

/** The caption line shown at ms: the timed caption cues when the project has any (imported / edited in the Captions sheet), otherwise the scene text of the picture on screen.
 *  Preview and export use this one rule, so what is edited is what is burned in. */
fun Timeline.captionLineAt(ms: Long): String? = if (captions.isNotEmpty()) captionAt(ms)?.text?.takeIf { it.isNotBlank() } else clips.firstOrNull { ms >= it.startMs && ms < it.endMs }?.text?.takeIf { it.isNotBlank() }
val Timeline.cuesDriveCaptions get() = captions.isNotEmpty()

/** One caption cue per scene that has text (start/end = the scene's), so the scene text can be edited/split/exported as captions. Does nothing to a project that already has cues. */
fun Timeline.captionsFromScenes(): Timeline {
    if (captions.isNotEmpty()) return this
    val c = clips.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }.mapIndexed { k, s -> CaptionClip(k + 1, s.startMs, s.endMs, s.text.trim()) }
    return withCaptions(c)
}
