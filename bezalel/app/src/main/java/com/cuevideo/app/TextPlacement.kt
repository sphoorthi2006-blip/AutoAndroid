package com.cuevideo.app

enum class TextAlign { CENTER, LEFT }

/**
 * Text written by hand over a range of cues, in place of their images (Bezalel "text on video").
 * Only two looks exist: black background with white text (dark = true, the default),
 * or white background with black text (dark = false). Colours are never set separately.
 * size / x / y follow the old Text Editor: size is a scale (1.0 = automatic best fit),
 * x and y move the block as a share of the screen (-0.5..0.5, 0 = centred).
 */
data class TextPlacement(
    val first: Int,
    val last: Int,
    val text: String,
    val dark: Boolean = true,
    val align: TextAlign = TextAlign.CENTER,
    val size: Double = 1.0,
    val x: Double = 0.0,
    val y: Double = 0.0,
) {
    val backgroundWhite: Boolean get() = !dark
    val label: String
        get() = if (first == last) "cue $first" else "cues $first to $last"

    companion object {
        /** "123-125", "123 - 125", "123" -> (123,123)..(123,125). Null when it is not a range. */
        fun parseRange(input: String): Pair<Int, Int>? {
            val m = Regex("^\\s*(\\d+)\\s*(?:[-\\u2013,]\\s*(\\d+))?\\s*$").find(input) ?: return null
            val a = m.groupValues[1].toIntOrNull() ?: return null
            val bText = m.groupValues[2]
            val b = if (bText.isEmpty()) a else (bText.toIntOrNull() ?: return null)
            return Pair(a, b)
        }
    }
}
