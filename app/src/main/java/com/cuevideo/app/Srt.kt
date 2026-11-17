package com.cuevideo.app

/** One caption: start and end in seconds, plus its text. */
data class Cue(val start: Double, val end: Double, val text: String)

object Srt {
    private val TIMING = Regex(
        "^\\s*(\\d+:\\d+:\\d+[,.]\\d+)\\s*-->\\s*(\\d+:\\d+:\\d+[,.]\\d+)"
    )
    private val BLOCK_SPLIT = Regex("\\n\\s*\\n")

    /** "H:MM:SS,mmm" (a dot is accepted too) -> seconds. Same as desktop parse_time. */
    fun parseTime(t: String): Double {
        val parts = t.trim().split(":")
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        val rest = parts[2].replace('.', ',').split(",")
        val s = rest[0].toInt()
        val ms = rest[1].toInt()
        return h * 3600.0 + m * 60.0 + s + ms / 1000.0
    }

    /** Bytes -> cues. UTF-8, BOM dropped, bad bytes replaced. */
    fun parseBytes(bytes: ByteArray): List<Cue> =
        parseText(String(bytes, Charsets.UTF_8))

    fun parseText(input: String): List<Cue> {
        var raw = input
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1)
        raw = raw.replace("\r\n", "\n").replace("\r", "\n").trim()
        val cues = ArrayList<Cue>()
        if (raw.isEmpty()) return cues
        for (block in BLOCK_SPLIT.split(raw)) {
            val lines = block.split("\n").filter { it.isNotBlank() }
            for ((i, line) in lines.withIndex()) {
                val m = TIMING.find(line)
                if (m != null) {
                    val text = lines.drop(i + 1).joinToString(" ") { it.trim() }
                    cues.add(Cue(parseTime(m.groupValues[1]), parseTime(m.groupValues[2]), text))
                    break
                }
            }
        }
        return cues
    }
}

enum class CueKind { NORMAL, HEADING, SUBSCRIBE }

object CueClassifier {
    private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+\\S")

    /** Same order as desktop classify(): subscribe first, then numbered heading. */
    fun classify(text: String): CueKind {
        val t = text.trim()
        if (t.contains("subscribe", ignoreCase = true)) return CueKind.SUBSCRIBE
        if (NUMBERED.containsMatchIn(t)) return CueKind.HEADING
        return CueKind.NORMAL
    }
}
