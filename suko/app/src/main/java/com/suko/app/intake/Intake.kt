package com.suko.app.intake

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * One usable file. Exactly one of node / file is set:
 * node = the file stays where the user has it, file = it was unpacked from a ZIP into the work folder.
 */
class IntakeEntry(val path: String, val kind: FileKind, val size: Long, val node: FolderNode?, val file: File?) {
    val name: String get() = path.substringAfterLast('/')
}

/** Everything found: usable files, and every skipped or failed thing with its reason. */
class IntakeResult(val entries: List<IntakeEntry> = emptyList(), val problems: List<IntakeProblem> = emptyList()) {
    operator fun plus(o: IntakeResult) = IntakeResult(entries + o.entries, problems + o.problems)
    fun of(kind: FileKind): List<IntakeEntry> = entries.filter { it.kind == kind }
}

/** Step 7: loose files + ZIPs (nested) + folders (with ZIPs inside) -> ONE classified list. */
object Intake {
    val ALL_USABLE: Set<FileKind> = setOf(
        FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.SRT, FileKind.TEXT, FileKind.JSON, FileKind.FONT,
    )
    const val NOT_USED = "not used by this tool"

    /**
     * sources: each is a loose file or a folder. Files from ZIPs are unpacked into workDir (one numbered
     * sub-folder each, real file name kept); everything else stays where it is. wanted = the kinds the calling
     * tool uses; other valid kinds are reported as "not used by this tool". One safety budget covers all sources.
     */
    fun scan(
        sources: List<FolderNode>,
        workDir: File,
        wanted: Set<FileKind> = ALL_USABLE,
        limits: Limits = Limits(),
    ): IntakeResult {
        val entries = ArrayList<IntakeEntry>()
        val problems = ArrayList<IntakeProblem>()
        val budget = Budget(limits)
        var counter = 0

        /** True when the file is NOT to be used (and says why). */
        fun reject(path: String, kind: FileKind): Boolean {
            val why = FileKinds.skipReason(kind)
            if (why != null) { problems += IntakeProblem(path, why); return true }
            if (kind != FileKind.ZIP && kind !in wanted) { problems += IntakeProblem(path, NOT_USED); return true }
            return false
        }

        fun copyOut(src: InputStream, f: File, path: String): Boolean {
            var failed: IOException? = null
            val out = try { FileOutputStream(f) } catch (e: IOException) { failed = e; null }
            val buf = ByteArray(65536)
            while (failed == null) {
                val n = src.read(buf)
                if (n < 0) break
                try { out?.write(buf, 0, n) } catch (e: IOException) { failed = e }
            }
            try { out?.close() } catch (e: IOException) { }
            if (failed != null) {
                f.delete()
                problems += IntakeProblem(path, "could not unpack (${failed.message ?: "write error"})")
                return false
            }
            return true
        }

        fun expandZip(input: InputStream, path: String) {
            val sr = ScanResult()
            NestedZip.expand(input, path, 1, budget, sr) { p, kind, stream ->
                if (!reject(p, kind)) {
                    val dir = File(workDir, (counter++).toString())
                    dir.mkdirs()
                    val f = File(dir, p.substringAfterLast('/'))
                    if (copyOut(stream, f, p)) entries += IntakeEntry(p, kind, f.length(), null, f)
                }
                true
            }
            problems += sr.problems
        }

        fun nodeFile(path: String, kind: FileKind, size: Long, node: FolderNode) {
            if (kind == FileKind.ZIP) {
                try {
                    node.open().use { expandZip(it, path) }
                } catch (e: Exception) {
                    if (e is LimitReached) throw e
                    problems += IntakeProblem(path, "cannot open this ZIP (${e.message ?: "no access"})")
                }
            } else if (!reject(path, kind)) {
                entries += IntakeEntry(path, kind, size, node, null)
            }
        }

        try {
            for (src in sources) {
                if (budget.exceeded) break
                if (src.isDirectory) {
                    val fs = FolderWalker.walk(src, limits, 20, budget)
                    for (p in fs.problems) {
                        problems += IntakeProblem(if (p.path.isEmpty()) src.name else "${src.name}/${p.path}", p.reason)
                    }
                    for (f in fs.files) nodeFile("${src.name}/${f.path}", f.kind, f.size, f.node)
                } else {
                    nodeFile(src.name, FileKinds.of(src.name), src.size, src)
                }
            }
        } catch (e: LimitReached) {
            problems += IntakeProblem("", "stopped: ${e.reason}")
        }
        return IntakeResult(entries, problems)
    }
}
