package com.suko.app.intake

/**
 * What the SVG Editor needs from intake: pictures and ONE JSON file.
 * images are sorted by their first number (2 before 10). If several JSON files come in, the largest one is used and the
 * others are named in warnings, so nothing is ignored silently. ready = pictures present and a JSON chosen.
 */
class SvgInput(
    val images: List<IntakeEntry>,
    val json: IntakeEntry?,
    val otherJson: List<IntakeEntry>,
    val warnings: List<String>,
) {
    val ready: Boolean get() = images.isNotEmpty() && json != null

    /** One plain sentence for the screen. */
    val headline: String get() = when {
        ready -> "Ready: ${images.size} picture(s) and JSON \"${json!!.name}\"."
        images.isEmpty() && json == null -> "Add the pictures and the JSON file."
        images.isEmpty() -> "JSON found, but no pictures yet."
        else -> "${images.size} picture(s) found, but no JSON file yet."
    }
}

object SvgInputs {
    /** The kinds the SVG Editor reads; pass this as `wanted` to Intake.scan. */
    val WANTED: Set<FileKind> = setOf(FileKind.IMAGE, FileKind.JSON)

    private val FIRST_NUMBER = Regex("\\d+")

    fun from(r: IntakeResult): SvgInput {
        val images = r.of(FileKind.IMAGE).sortedWith(
            compareBy<IntakeEntry>({ FIRST_NUMBER.find(it.name)?.value?.toLongOrNull() ?: Long.MAX_VALUE }, { it.name.lowercase() }),
        )
        val jsons = r.of(FileKind.JSON).sortedWith(compareByDescending<IntakeEntry> { it.size }.thenBy { it.name.lowercase() })
        val json = jsons.firstOrNull()
        val others = jsons.drop(1)
        val warnings = ArrayList<String>()
        if (others.isNotEmpty()) {
            warnings += "Several JSON files were added. Using the largest (\"${json!!.name}\"); not used: " +
                others.joinToString(", ") { "\"${it.name}\"" } + "."
        }
        val names = images.map { it.name.lowercase() }
        val dup = names.groupBy { it }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) warnings += "Pictures with the same name: " + dup.take(3).joinToString(", ") + " (check you did not add a picture twice)."
        return SvgInput(images, json, others, warnings)
    }
}
