package com.suko.app.intake

import java.io.InputStream

/** A file or folder, whatever it really is (Android document tree, plain java.io.File, or a test double). */
interface FolderNode {
    val name: String
    val isDirectory: Boolean
    val size: Long
    /** Where the bytes can be found again (an Android Uri as text); defaults to the name. */
    val location: String get() = name
    fun children(): List<FolderNode>
    fun open(): InputStream
}

/** A file found in a folder tree. The node is kept so a ZIP found here can be opened later (step 7). */
class FolderFile(val path: String, val kind: FileKind, val size: Long, val node: FolderNode)

class FolderScan(
    val files: MutableList<FolderFile> = mutableListOf(),
    val problems: MutableList<IntakeProblem> = mutableListOf(),
)

/** Step 6: walks a chosen folder and all its subfolders. ZIP files are listed as kind ZIP, not opened. */
object FolderWalker {
    fun walk(root: FolderNode, limits: Limits = Limits(), maxFolderDepth: Int = 20, budget: Budget = Budget(limits)): FolderScan {
        val scan = FolderScan()
        try {
            visit(root, "", 0, maxFolderDepth, budget, scan)
        } catch (e: LimitReached) {
            scan.problems += IntakeProblem("", "stopped: ${e.reason}")
        }
        return scan
    }

    private fun visit(dir: FolderNode, rel: String, depth: Int, maxDepth: Int, budget: Budget, scan: FolderScan) {
        val kids = try {
            dir.children().sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            scan.problems += IntakeProblem(rel, "cannot read this folder (${e.message ?: "no access"})")
            return
        }
        for (k in kids) {
            val path = if (rel.isEmpty()) k.name else "$rel/${k.name}"
            if (k.isDirectory) {
                when {
                    k.name.startsWith(".") || k.name == "__MACOSX" ->
                        scan.problems += IntakeProblem(path, "hidden or system folder, skipped")
                    depth + 1 > maxDepth ->
                        scan.problems += IntakeProblem(path, "folders are nested too deep, not opened")
                    else -> visit(k, path, depth + 1, maxDepth, budget, scan)
                }
            } else {
                budget.entries++
                if (budget.entries > budget.limits.maxEntries) throw LimitReached("too many files")
                scan.files += FolderFile(path, FileKinds.of(path), k.size, k)
            }
        }
    }
}
