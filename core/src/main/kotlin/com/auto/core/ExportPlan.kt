package com.auto.core

import kotlin.math.max

data class ExportSettings(val width: Int = 1280, val height: Int = 720, val fps: Int = 30) {
    companion object {
        val FPS = setOf(24, 30, 60)   // must be declared before the presets that use it
        val P720 = ExportSettings(1280, 720, 30); val P1080 = ExportSettings(1920, 1080, 30); val P4K = ExportSettings(3840, 2160, 30)
        /** heightP = 720, 1080 or 2160 (4K); fps = 24, 30 or 60. All 16:9. */
        fun preset(heightP: Int, fps: Int): ExportSettings {
            val w = when (heightP) { 720 -> 1280; 1080 -> 1920; 2160 -> 3840; else -> throw IllegalArgumentException("Resolution must be 720, 1080 or 2160 (4K).") }
            return ExportSettings(w, heightP, fps)
        }
    }
    init { require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "Width and height must be even and positive." }; require(fps in FPS) { "Frame rate must be 24, 30 or 60." } }
}

/**
 * What the exporter must render: back-to-back picture/solid-colour segments whose durations come from ms BOUNDARIES
 * (never from summed rounded lengths), so video length == narration length (or last clip end when there is no narration) exactly.
 */
object ExportPlan {
    class Segment(
        val image: String?,
        val text: String,
        val heading: Boolean,
        val startMs: Long,
        val endMs: Long,
        val sceneNum: Int?,
        val fx: ClipFx = ClipFx(),
        val transition: TransitionSpec = TransitionSpec(),
        val nextTransition: TransitionSpec? = null,
        val replacementColor: Int? = null
    ) {
        val durMs get() = endMs - startMs
        val isSolidColor get() = replacementColor != null
    }

    /** captions / texts are already cut to the video length (nothing starts after the end, nothing runs past it). Hidden text layers are excluded. */
    class Plan(
        val segments: List<Segment>,
        val totalMs: Long,
        val captions: List<CaptionClip> = emptyList(),
        val texts: List<TextClip> = emptyList(),
        val captionStyle: OverlayStyle = StylePresets.captionDefault,
        val mix: AudioMix = AudioMix()
    ) {
        val usesFx get() = segments.any { !it.fx.isIdentity }
        val usesTransitions get() = segments.any { it.transition.active }
        val usesTexts get() = texts.isNotEmpty()
        val usesSolidColor get() = segments.any { it.replacementColor != null }
    }

    /**
     * Gaps become blank segments, overlaps are cut so the later clip starts where the earlier one ends, and the last picture is held until the narration ends.
     * clampToAudio (default, used whenever there is a narration): the video is exactly as long as the narration - the scene that crosses the end of the
     * narration is cut there and scenes that start after it are left out (the narration track is never stretched or changed by picture edits).
     * Without narration (`t.audioMs == 0` or `clampToAudio == false`), duration equals last clip end.
     */
    fun fromTimeline(t: Timeline, clampToAudio: Boolean = true): Plan {
        val limit = if (clampToAudio && t.audioMs > 0) t.audioMs else Long.MAX_VALUE
        val out = ArrayList<Segment>()
        var cur = 0L
        for (c in t.clips.sortedBy { it.startMs }) {
            if (c.startMs >= limit) break
            val end = minOf(c.endMs, limit)
            if (end <= cur) continue
            if (c.startMs > cur) out.add(Segment(null, "", false, cur, c.startMs, null))
            val s = max(c.startMs, cur)
            out.add(Segment(c.image, c.text, c.heading, s, end, c.sceneNum, c.fx, c.transition, null, c.replacementColor))
            cur = end
        }
        val total = if (clampToAudio && t.audioMs > 0) max(cur, t.audioMs) else max(cur, t.endMs)
        if (out.isEmpty()) out.add(Segment(null, "", false, 0, max(total, 1), null))
        else if (cur < total) {
            val l = out.removeAt(out.size - 1)
            out.add(Segment(l.image, l.text, l.heading, l.startMs, total, l.sceneNum, l.fx, l.transition, null, l.replacementColor))
        }
        // each segment learns the NEXT segment's transition (a FADE there also fades this one out)
        val linked = out.mapIndexed { k, g ->
            val n = out.getOrNull(k + 1)?.transition?.takeIf { it.active }
            if (n == null) g else Segment(g.image, g.text, g.heading, g.startMs, g.endMs, g.sceneNum, g.fx, g.transition, n, g.replacementColor)
        }
        val caps = t.captions.filter { it.startMs < total }.map { if (it.endMs > total) it.copy(endMs = total) else it }
        // Only visible texts are included, sorted by zIndex then startMs so export matches preview layer order
        val txt = t.texts
            .filter { it.visible && it.startMs < total }
            .map { if (it.endMs > total) it.copy(endMs = total) else it }
            .sortedWith(compareBy<TextClip> { it.zIndex }.thenBy { it.startMs }.thenBy { it.id })
        return Plan(linked, total, caps, txt, t.captionStyle, t.mix)
    }
}
