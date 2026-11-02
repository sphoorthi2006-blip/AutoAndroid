package com.auto.core

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
}
