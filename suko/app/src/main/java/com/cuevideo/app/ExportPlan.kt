package com.cuevideo.app

import java.util.Locale

enum class SegKind { STILL, BLACK, HEADING, SCENE }

/**
 * One piece of the video (section 9). Frames [startFrame, endFrame) are shown.
 * A scene clip is one segment that spans all cues it covers (firstCue..lastCue).
 */
data class Segment(
    val kind: SegKind,
    val firstCue: Int,
    val lastCue: Int,
    val startFrame: Int,
    val endFrame: Int,
    val image: ImageEntry? = null,
    val clip: ClipEntry? = null,
    val text: String = "",
) {
    val frames: Int get() = endFrame - startFrame
}

class ExportPlan(val segments: List<Segment>, val totalFrames: Int, val cueCount: Int)

/** Pure planning of the whole video from the check result and the frame numbers (sections 8, 9, 11). */
object ExportPlanner {
    fun build(cues: List<Cue>, bf: IntArray, chk: CheckResult): ExportPlan {
        val n = cues.size
        require(bf.size == n + 1) { "frame list does not match the master SRT" }
        val clipOf = HashMap<Int, ClipRow>()
        for (r in chk.clipRows) {
            val f = r.first
            val l = r.last
            if (f != null && l != null && f >= 1 && l >= f && l <= n) for (c in f..l) clipOf[c] = r
        }
        val segs = ArrayList<Segment>()
        var i = 1
        while (i <= n) {
            val row = clipOf[i]
            if (row != null) {
                val f = row.first!!
                val l = row.last!!
                segs.add(Segment(SegKind.SCENE, f, l, bf[f - 1], bf[l], clip = row.clip))
                i = l + 1
                continue
            }
            val text = cues[i - 1].text
            val s = bf[i - 1]
            val e = bf[i]
            when (CueClassifier.classify(text)) {
                CueKind.SUBSCRIBE -> segs.add(Segment(SegKind.BLACK, i, i, s, e, text = text))
                CueKind.HEADING -> segs.add(Segment(SegKind.HEADING, i, i, s, e, text = text.trim()))
                CueKind.NORMAL -> {
                    val img = chk.imageForCue[i] ?: throw IllegalStateException("Cue $i has no image.")
                    segs.add(Segment(SegKind.STILL, i, i, s, e, image = img, text = text))
                }
            }
            i++
        }
        return ExportPlan(segs, bf[n], n)
    }
}

/** Section 14: <video name>_report.csv */
object ReportCsv {
    private fun q(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun sec(frame: Int, fps: Int): String = String.format(Locale.US, "%.2f", frame.toDouble() / fps)

    fun build(cues: List<Cue>, bf: IntArray, plan: ExportPlan, chk: CheckResult, fps: Int): String {
        val sb = StringBuilder("cue,start,end,source,replaced_image,text\r\n")
        val segOf = HashMap<Int, Segment>()
        for (s in plan.segments) for (c in s.firstCue..s.lastCue) segOf[c] = s
        for (i in 1..cues.size) {
            val seg = segOf[i]
            val source = if (seg == null) "" else when (seg.kind) {
                SegKind.STILL -> seg.image!!.name
                SegKind.SCENE -> "SCENE " + seg.clip!!.name
                SegKind.HEADING -> "TEXT SLIDE (heading, handwritten)"
                SegKind.BLACK -> "TEXT SLIDE (subscribe, handwritten)"
            }
            val replaced = if (seg != null && seg.kind != SegKind.STILL) (chk.imageForCue[i]?.name ?: "") else ""
            val text = cues[i - 1].text.take(80)
            sb.append(i).append(',')
                .append(sec(bf[i - 1], fps)).append(',')
                .append(sec(bf[i], fps)).append(',')
                .append(q(source)).append(',')
                .append(q(replaced)).append(',')
                .append(q(text)).append("\r\n")
        }
        return sb.toString()
    }

    /** Images used out of images given, and the names of those not used. */
    fun usage(plan: ExportPlan, images: List<ImageEntry>): Pair<Int, List<String>> {
        val used = plan.segments.filter { it.kind == SegKind.STILL }.mapNotNull { it.image?.uri }.toSet()
        val unused = images.filter { it.uri !in used }.map { it.name }
        return Pair(used.size, unused)
    }
}
