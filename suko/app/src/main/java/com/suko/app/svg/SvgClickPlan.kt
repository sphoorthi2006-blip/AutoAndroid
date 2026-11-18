package com.suko.app.svg

import org.json.JSONObject

/**
 * SVG Editor audio: when each click plays.
 * One click per element (panel) at the panel's own start time (the cue where it appears).
 * A slide with no panels (a heading or a single splash image) gets one click at the slide start.
 */
class SvgClickPlan(val times: List<Double>, val slides: Int, val endSeconds: Double)

object SvgClicks {
    /** Pure and total: bad JSON gives an error message instead of an exception. */
    fun parse(jsonText: String): Pair<SvgClickPlan?, String?> {
        val root = try { JSONObject(jsonText) } catch (e: Exception) { return Pair(null, "The JSON file could not be read: ${e.message}") }
        val slides = root.optJSONArray("slides") ?: return Pair(null, "The JSON has no \"slides\" list.")
        val t = ArrayList<Double>()
        var end = 0.0
        for (i in 0 until slides.length()) {
            val s = slides.optJSONObject(i) ?: continue
            val start = s.optDouble("start", Double.NaN)
            val e = s.optDouble("end", Double.NaN)
            if (!e.isNaN()) end = maxOf(end, e)
            val panels = s.optJSONArray("panels")
            var any = false
            if (panels != null) for (j in 0 until panels.length()) {
                val p = panels.optJSONObject(j) ?: continue
                val ps = p.optDouble("start", Double.NaN)
                if (!ps.isNaN() && ps >= 0) { t += ps; any = true }
            }
            if (!any && !start.isNaN() && start >= 0) t += start
        }
        if (t.isEmpty()) return Pair(null, "The JSON has no timed elements (no \"start\" values).")
        t.sort()
        return Pair(SvgClickPlan(t, slides.length(), maxOf(end, t.last())), null)
    }
}
