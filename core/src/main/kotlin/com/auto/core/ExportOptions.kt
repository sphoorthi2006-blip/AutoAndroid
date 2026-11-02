package com.auto.core

import java.io.File
import kotlin.math.roundToInt

/** Output resolution = length of the SHORTER side in pixels (720p, 1080p, 4K = 2160). */
enum class Resolution(val label: String, val shortSide: Int) { P720("720p", 720), P1080("1080p", 1080), P2160("4K", 2160);
    companion object { fun of(heightP: Int) = values().firstOrNull { it.shortSide == heightP } ?: throw IllegalArgumentException("Resolution must be 720, 1080 or 2160 (4K).") }
}

/** Aspect ratio as width:height. Pictures that do not match are scaled to fit (black bars) by the exporter. */
enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    R16_9("16:9", 16, 9), R9_16("9:16", 9, 16), R1_1("1:1", 1, 1), R4_5("4:5", 4, 5);
    val ratio get() = w.toFloat() / h
}

/** AI image upscaling factor applied to unique source images during export before video encoding. Independent of video resolution. */
enum class UpscaleFactor(val label: String, val scale: Int) {
    OFF("Off", 1),
    X2("2×", 2),
    X4("4×", 4);

    companion object {
        fun of(scale: Int): UpscaleFactor = values().firstOrNull { it.scale == scale } ?: OFF
        fun fromString(raw: String?): UpscaleFactor {
            if (raw == null) return OFF
            raw.toIntOrNull()?.let { s -> values().firstOrNull { it.scale == s }?.let { return it } }
            return values().firstOrNull { it.name.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true) } ?: OFF
        }
    }
}

/** Intermediate file format for upscaled images stored in the temporary export workspace. */
enum class IntermediateFormat(val label: String, val shortLabel: String, val ext: String, val mime: String, val png: Boolean) {
    JPEG("JPEG", "JPEG", "jpg", "image/jpeg", false),
    PNG("PNG (lossless)", "PNG", "png", "image/png", true);

    companion object {
        fun fromString(raw: String?): IntermediateFormat = when (raw?.trim()?.uppercase()) {
            "PNG", "PNG (LOSSLESS)", "TRUE" -> PNG
            "JPEG", "JPG", "FALSE" -> JPEG
            else -> JPEG
        }
    }
}

/** Resolution x aspect ratio -> even pixel width/height (video encoders need even sizes). 16:9 gives exactly the old presets. */
object ExportOptions {
    private fun even(v: Double): Int { val n = v.roundToInt(); return if (n % 2 == 0) n else n + 1 }

    fun size(res: Resolution, aspect: AspectRatio): Pair<Int, Int> {
        val s = res.shortSide
        return if (aspect.w >= aspect.h) Pair(even(s.toDouble() * aspect.w / aspect.h), s)
        else Pair(s, even(s.toDouble() * aspect.h / aspect.w))
    }

    fun settings(res: Resolution, aspect: AspectRatio, fps: Int = 30): ExportSettings {
        val (w, h) = size(res, aspect); return ExportSettings(w, h, fps)
    }

    /** Canonical deduplication key for a source image path so identical files are processed only once. */
    fun canonicalKey(path: String): String = try {
        val f = File(path)
        if (f.exists()) f.canonicalPath else f.absolutePath
    } catch (_: Exception) {
        path
    }

    /** Unique non-blank source image paths referenced by the finalized export plan, in first-encounter order. */
    fun uniqueSourceImages(plan: ExportPlan.Plan, normalize: (String) -> String = ::canonicalKey): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (seg in plan.segments) {
            val img = seg.image?.takeIf { it.isNotBlank() } ?: continue
            val key = normalize(img)
            if (seen.add(key)) result.add(img)
        }
        return result
    }

    /** Replaces source image paths in the finalized export plan with their processed temporary image paths, preserving all edits. */
    fun withProcessedImages(
        plan: ExportPlan.Plan,
        processedByKey: Map<String, String>,
        normalize: (String) -> String = ::canonicalKey
    ): ExportPlan.Plan {
        if (processedByKey.isEmpty()) return plan
        val remapped = plan.segments.map { g ->
            val img = g.image
            val newImg = if (img != null) processedByKey[normalize(img)] ?: processedByKey[img] ?: img else null
            if (newImg == img) g
            else ExportPlan.Segment(newImg, g.text, g.heading, g.startMs, g.endMs, g.sceneNum, g.fx, g.transition, g.nextTransition)
        }
        return ExportPlan.Plan(remapped, plan.totalMs, plan.captions, plan.texts, plan.captionStyle, plan.mix)
    }
}
