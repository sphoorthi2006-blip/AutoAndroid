package com.auto.core

import java.util.Locale
import kotlin.math.roundToLong

/** Typing and showing scene lengths: "90", "2.5", "1:30", "1:02:03", "10m", "45s", "1m30s". */
object TimeText {
    /** Milliseconds, or null when the text is not a time. Zero and negative values are rejected. */
    fun parse(text: String): Long? {
        val t = text.trim().lowercase(Locale.ROOT).replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return null
        fun num(x: String): Double? = if (Regex("^\\d+(\\.\\d+)?$").matches(x)) x.toDouble() else null
        val ms: Double? = when {
            t.contains(':') -> {
                val parts = t.split(':'); if (parts.size !in 2..3) return null
                val n = parts.map { num(it) ?: return null }
                if (n.dropLast(1).any { it != Math.floor(it) }) return null
                if (parts.size == 2) (n[0] * 60 + n[1]) * 1000 else (n[0] * 3600 + n[1] * 60 + n[2]) * 1000
            }
            Regex("^(\\d+(\\.\\d+)?m(in)?)?(\\d+(\\.\\d+)?s(ec)?)?$").matches(t) && (t.contains('m') || t.contains('s')) -> {
                val m = Regex("(\\d+(\\.\\d+)?)m").find(t)?.groupValues?.get(1)?.toDouble() ?: 0.0
                val s = Regex("(\\d+(\\.\\d+)?)s").find(t)?.groupValues?.get(1)?.toDouble() ?: 0.0
                (m * 60 + s) * 1000
            }
            else -> num(t)?.times(1000)
        }
        if (ms == null || ms <= 0.0 || ms > 24 * 3600 * 1000.0) return null
        return ms.roundToLong()
    }

    /** 125500 -> "2:05.5", 600000 -> "10:00". */
    fun format(ms: Long): String {
        val tenths = (maxOf(0L, ms) + 50) / 100; val s = tenths / 10; val f = tenths % 10
        val base = if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        return if (f == 0L) base else "$base.$f"
    }
}
