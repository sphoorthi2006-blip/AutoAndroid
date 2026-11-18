package com.suko.app.intake

/** One line of the "what was found" list: e.g. 12 pictures, with the first few names. */
class KindGroup(val kind: FileKind, val label: String, val count: Int, val names: List<String>)

/** Skipped things that share one reason: e.g. 14 x "hidden or system file", with a few examples. */
class SkippedGroup(val reason: String, val count: Int, val examples: List<String>)

class IntakeSummary(val groups: List<KindGroup>, val skipped: List<SkippedGroup>, val headline: String)

object IntakeSummaries {
    private val LABELS: List<Triple<FileKind, String, String>> = listOf(
        Triple(FileKind.IMAGE, "picture", "pictures"),
        Triple(FileKind.VIDEO, "video", "videos"),
        Triple(FileKind.AUDIO, "audio file", "audio files"),
        Triple(FileKind.SRT, "SRT file", "SRT files"),
        Triple(FileKind.TEXT, "text file", "text files"),
        Triple(FileKind.JSON, "JSON file", "JSON files"),
        Triple(FileKind.FONT, "font", "fonts"),
    )

    private val FIRST_NUMBER = Regex("\\d+")

    /** Names sorted by their first number (2 before 10), then alphabetically. */
    fun naturalNames(names: List<String>): List<String> =
        names.sortedWith(compareBy<String>({ FIRST_NUMBER.find(it)?.value?.toLongOrNull() ?: Long.MAX_VALUE }, { it.lowercase() }))

    private fun noun(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"

    fun of(r: IntakeResult, maxNames: Int = 6, maxExamples: Int = 3): IntakeSummary {
        val groups = LABELS.mapNotNull { (kind, one, many) ->
            val list = r.of(kind)
            if (list.isEmpty()) null
            else KindGroup(kind, many.replaceFirstChar { it.uppercase() }, list.size, naturalNames(list.map { it.name }).take(maxNames))
        }
        val skipped = r.problems.groupBy { it.reason }.map { (reason, ps) ->
            SkippedGroup(reason, ps.size, ps.map { it.path.ifEmpty { "(whole input)" } }.take(maxExamples))
        }
        val found = LABELS.mapNotNull { (kind, one, many) ->
            val n = r.of(kind).size
            if (n == 0) null else noun(n, one, many)
        }
        val head = if (found.isEmpty()) "Nothing usable found." else "Found " + found.joinToString(", ") + "."
        val tail = if (r.problems.isEmpty()) "" else " Skipped or failed: ${r.problems.size}."
        return IntakeSummary(groups, skipped, head + tail)
    }
}
