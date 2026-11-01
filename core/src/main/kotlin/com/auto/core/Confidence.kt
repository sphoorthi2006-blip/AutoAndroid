package com.auto.core

/** Step 27: a 0-100 score for every picture clip, computed from evidence the pipeline already has. Deterministic; no model involved. */
object Confidence {
    const val REVIEW_BELOW = 70
    /** conf "?" = the matcher was not sure of the picture; cutCheck = the cut could not be put into a clear pause; missing = no picture at all. */
    fun score(conf: String, cutCheck: Boolean, missing: Boolean, durMs: Long): Int {
        if (missing) return 0
        var s = 100
        if (conf == "?") s -= 45
        if (cutCheck) s -= 25
        if (durMs < 1000) s -= 10
        return s.coerceIn(0, 100)
    }
    fun reasons(c: ImageClip): List<String> = buildList {
        if (c.image == null && !c.heading) add("no picture")
        if (c.conf == "?") add("picture match not certain")
        if (c.cutCheck) add("cut not in a clear pause")
        if (c.durMs < 1000) add("very short")
    }
    /** Clips a person must look at, lowest score first. */
    fun needsReview(t: Timeline): List<Issue> = t.clips.filter { it.score < REVIEW_BELOW && !(it.heading && it.image == null) }.sortedBy { it.score }
        .map { Issue(IssueKind.LOW_CONFIDENCE, it.id, "Scene ${it.sceneNum}: confidence ${it.score}/100 (${reasons(it).joinToString(", ")}).") }
}

/** Step 26: what to do with pauses in the narration. KEEP = the picture stays through every pause (default; images only change at scene cuts). FLAG_LONG = same, but pauses of longPauseSec or more are reported for review. */
enum class PauseMode { KEEP, FLAG_LONG }
data class PausePolicy(val mode: PauseMode = PauseMode.KEEP, val longPauseSec: Double = 2.5) {
    /** silences = AudioPauses.silences(). Never moves a clip; only reports. */
    fun review(t: Timeline, silences: List<Pair<Double, Double>>): List<Issue> {
        if (mode == PauseMode.KEEP) return emptyList()
        return silences.filter { it.second - it.first >= longPauseSec }.map { (a, b) ->
            val ms = Math.round(a * 1000); val c = t.clipAt(ms)
            Issue(IssueKind.LONG_PAUSE, c?.id, "Pause of %.1f s at %.1f s (scene %s): picture stays on screen.".format(b - a, a, c?.sceneNum?.toString() ?: "-"))
        }
    }
}
