package com.auto.core

import kotlin.math.max

data class ExportSettings(val width: Int = 1280, val height: Int = 720, val fps: Int = 30) {
    companion object {
        val FPS = setOf(24, 30, 60)   // must be declared before the presets that use it
        val P720 = ExportSettings(1280, 720, 30); val P1080 = ExportSettings(1920, 1080, 30)
    }
    init { require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "Width and height must be even and positive." }; require(fps in FPS) { "Frame rate must be 24, 30 or 60." } }
}

/** What the exporter must render: back-to-back picture segments whose durations come from ms BOUNDARIES (never from summed rounded lengths), so video length == narration length exactly. */
object ExportPlan {
    class Segment(val image: String?, val text: String, val heading: Boolean, val startMs: Long, val endMs: Long, val sceneNum: Int?) { val durMs get() = endMs - startMs }
    class Plan(val segments: List<Segment>, val totalMs: Long)

    /** Gaps become blank segments, overlaps are cut so the later clip starts where the earlier one ends, and the last picture is held until the narration ends. */
    fun fromTimeline(t: Timeline): Plan {
        val out = ArrayList<Segment>(); var cur = 0L
        for (c in t.clips.sortedBy { it.startMs }) {
            if (c.endMs <= cur) continue
            if (c.startMs > cur) out.add(Segment(null, "", false, cur, c.startMs, null))
            val s = max(c.startMs, cur)
            out.add(Segment(c.image, c.text, c.heading, s, c.endMs, c.sceneNum)); cur = c.endMs
        }
        val total = max(cur, t.audioMs)
        if (out.isEmpty()) out.add(Segment(null, "", false, 0, max(total, 1), null))
        else if (cur < total) { val l = out.removeAt(out.size - 1); out.add(Segment(l.image, l.text, l.heading, l.startMs, total, l.sceneNum)) }
        return Plan(out, total)
    }
}
