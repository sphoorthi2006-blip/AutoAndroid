package com.suko.app.intake

/** One usable or listed file found by intake. path uses "/" and, for ZIP content, starts with the ZIP name(s). */
data class IntakeItem(val path: String, val kind: FileKind, val size: Long)

/** Something intake could not or would not use, with a plain-words reason for the user. */
data class IntakeProblem(val path: String, val reason: String)

class ScanResult(
    val items: MutableList<IntakeItem> = mutableListOf(),
    val problems: MutableList<IntakeProblem> = mutableListOf(),
)

/** Safety limits. Defaults are generous for a phone project and stop zip bombs and endless nesting. */
data class Limits(
    val maxDepth: Int = 4,
    val maxEntries: Int = 50_000,
    val maxTotalBytes: Long = 16L * 1024 * 1024 * 1024,
)

/** Shared counter so nested ZIPs and folders all count against ONE limit. */
class Budget(val limits: Limits) {
    var entries = 0
    var bytes = 0L
    val exceeded: Boolean get() = entries > limits.maxEntries || bytes > limits.maxTotalBytes
}

class LimitReached(val reason: String) : Exception(reason)
