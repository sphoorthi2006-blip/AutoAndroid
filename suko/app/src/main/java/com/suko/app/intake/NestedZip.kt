package com.suko.app.intake

import java.io.InputStream

/** Step 5: opens ZIPs inside ZIPs (streamed, depth-limited). The inner ZIP itself is not listed, its files are. */
object NestedZip {
    fun scan(input: InputStream, rootName: String = "", limits: Limits = Limits()): ScanResult {
        val r = ScanResult()
        val budget = Budget(limits)
        try {
            expand(input, rootName, 1, budget, r)
        } catch (e: LimitReached) {
            r.problems += IntakeProblem(rootName, "stopped: ${e.reason}")
        }
        return r
    }

    /**
     * Also used by the merge (Intake). depth 1 = the outermost ZIP. leaf sees every non-ZIP file and returns true
     * when it took care of it (for example unpacked it), so it is not listed in result.
     */
    fun expand(
        input: InputStream,
        prefix: String,
        depth: Int,
        budget: Budget,
        result: ScanResult,
        leaf: (path: String, kind: FileKind, stream: InputStream) -> Boolean = { _, _, _ -> false },
    ) {
        ZipReader.walk(input, prefix, budget, result) { path, kind, stream ->
            when {
                kind != FileKind.ZIP -> leaf(path, kind, stream)
                depth >= budget.limits.maxDepth -> {
                    result.problems += IntakeProblem(path, "ZIP is nested too deep, not opened")
                    false
                }
                else -> {
                    expand(stream, path, depth + 1, budget, result, leaf)
                    true
                }
            }
        }
    }
}
