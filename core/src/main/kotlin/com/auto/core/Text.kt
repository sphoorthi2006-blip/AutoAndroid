package com.auto.core

/** Small text helpers shared by the whole core (they used to live in Details.kt). */
object Text {
    /** Lower-case words (letters, digits, Latin-extended and Indic scripts); apostrophes are dropped so "don't" = "dont". */
    fun tokens(text: String): List<String> =
        Regex("[a-z0-9\\u00c0-\\u024f\\u0900-\\u0dff]+").findAll(text.lowercase().replace('’', '\'').replace('‘', '\'').replace("'", "")).map { it.value }.toList()

    /** Non-empty trimmed lines. */
    fun linesOf(text: String) = text.replace("\r", "\n").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private val TAGS = Regex("<[^>]+>|\\{\\\\[^}]*\\}")
    private val WS = Regex("\\s+")
    /** A caption's text without <i> tags or {\an8} codes, on one line. */
    fun cleanCue(t: String) = WS.replace(TAGS.replace(t, ""), " ").trim()

    private val SENT = Regex("(?<=[.!?])[\"')\\]]*\\s+(?=[\"'(\\[]?[A-Z0-9])")
    /** One scene per script line. A very long paragraph (over 30 words) is cut into sentences so it does not become one long still picture. */
    fun scriptSceneLines(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in linesOf(text)) {
            val ln = raw.replace(Regex("^\\s*#+\\s*"), "").trim()
            if (tokens(ln).isEmpty()) continue
            if (tokens(ln).size > 30) { val parts = ln.split(SENT).map { it.trim() }.filter { it.isNotEmpty() }; out.addAll(parts.ifEmpty { listOf(ln) }) }
            else out.add(ln)
        }
        return out
    }
}
