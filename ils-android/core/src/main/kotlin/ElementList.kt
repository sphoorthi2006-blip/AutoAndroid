package ils.core

/**
 * One element of a slide, as written by the prompt step (an AI model) and imported into the app.
 *
 * elements.json:
 * {
 *   "slides": [
 *     { "image": 6,
 *       "elements": [
 *         { "label": "baking soda box", "type": "icon",  "sentence": 0, "trigger": "baking soda", "row": 1, "col": 1 },
 *         { "label": "Skin",            "type": "text",  "sentence": 0, "trigger": "skin",        "row": 1, "col": 2 }
 *       ] } ] }
 *
 * image    = slide number from slides.json (also the image file name: 6.png / 6.jpg)
 * type     = "icon" (picture, with its label if it has one) or "text" (words only). Default "icon".
 * sentence = index of the sentence in slides.json (0 = first). Default 0. If the trigger is not in that sentence, the whole slide is searched.
 * trigger  = the word or words at which the element starts
 * row, col = where the element sits in the image, counted from 1: rows top to bottom, columns left to right.
 *            "slot": {"row": 1, "col": 2} is accepted too.
 */
data class ElementSpec(
    val label: String,
    val isText: Boolean,
    val sentence: Int,
    val trigger: String,
    val row: Int,
    val col: Int,
)

object ElementList {
    /** image number -> elements, in the order of the file (narration order). */
    fun parse(text: String): Map<Int, List<ElementSpec>> {
        val root = Json.parse(text)
        val slides: List<Any?> = when (root) {
            is Map<*, *> -> (root["slides"] as? List<*>) ?: throw IllegalArgumentException("elements.json: \"slides\" list not found.")
            is List<*> -> root
            else -> throw IllegalArgumentException("elements.json: expected an object with \"slides\".")
        }
        val out = LinkedHashMap<Int, List<ElementSpec>>()
        for (s in slides) {
            val m = s as? Map<*, *> ?: continue
            val image = num(m["image"])?.toInt() ?: throw IllegalArgumentException("elements.json: a slide has no \"image\" number.")
            val els = (m["elements"] as? List<*>) ?: emptyList<Any?>()
            out[image] = els.mapIndexed { i, e ->
                val em = e as? Map<*, *> ?: throw IllegalArgumentException("elements.json: image $image element ${i + 1} is not an object.")
                val slot = em["slot"] as? Map<*, *>
                val type = (em["type"] as? String)?.trim()?.lowercase() ?: "icon"
                ElementSpec(
                    label = (em["label"] as? String) ?: "",
                    isText = type == "text" || type == "heading" || type == "label",
                    sentence = num(em["sentence"])?.toInt() ?: 0,
                    trigger = (em["trigger"] as? String) ?: "",
                    row = (num(em["row"]) ?: num(slot?.get("row")))?.toInt() ?: 1,
                    col = (num(em["col"]) ?: num(slot?.get("col")))?.toInt() ?: (i + 1),
                )
            }
        }
        return out
    }

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}
