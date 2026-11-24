package com.suko.app.intake

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads ZIP files as a stream: nothing is written to disk and nothing is held in memory. */
object ZipReader {

    /** Cleans a ZIP entry name. Returns null for unsafe names (".." parts, drive letters). */
    fun safePath(raw: String): String? {
        val p = raw.replace('\\', '/').trimStart('/')
        val parts = p.split('/')
        if (parts.any { it == ".." }) return null
        if (parts.first().length == 2 && parts.first()[1] == ':') return null
        val clean = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        return clean.ifEmpty { null }
    }

    /**
     * Walks every file entry of one ZIP stream. onFile gets the path, the kind and the entry's bytes;
     * it returns true when it handled the entry itself (e.g. opened a nested ZIP), so it is not listed.
     * A damaged ZIP keeps what was found before the damage and adds a problem.
     */
    fun walk(
        input: InputStream,
        prefix: String,
        budget: Budget,
        result: ScanResult,
        onFile: (path: String, kind: FileKind, stream: InputStream) -> Boolean,
    ) {
        val zis = ZipInputStream(input)
        var seen = 0
        try {
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                seen++
                budget.entries++
                if (budget.entries > budget.limits.maxEntries) throw LimitReached("too many files")
                val safe = safePath(e.name)
                if (safe == null) {
                    result.problems += IntakeProblem(join(prefix, e.name), "unsafe path inside the ZIP, skipped")
                    continue
                }
                val full = join(prefix, safe)
                val kind = FileKinds.of(safe)
                val stream = Counting(zis, budget)
                val handled = onFile(full, kind, stream)
                stream.drain()
                if (!handled) result.items += IntakeItem(full, kind, stream.count)
            }
            if (seen == 0) result.problems += IntakeProblem(prefix, "no files found (empty, or not a real ZIP)")
        } catch (e: IOException) {
            result.problems += IntakeProblem(prefix, "ZIP is damaged, used only what could be read (${e.message ?: "read error"})")
        }
    }

    /** Step 4: list ONE ZIP. A ZIP inside it is listed as kind ZIP, not opened (see NestedZip). */
    fun list(input: InputStream, rootName: String = "", limits: Limits = Limits()): ScanResult {
        val r = ScanResult()
        try {
            walk(input, rootName, Budget(limits), r) { _, _, _ -> false }
        } catch (e: LimitReached) {
            r.problems += IntakeProblem(rootName, "stopped: ${e.reason}")
        }
        return r
    }

    private fun join(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix/$name"

    /** Entry stream that counts bytes against the shared budget and never closes the ZIP underneath. */
    private class Counting(src: InputStream, val budget: Budget) : FilterInputStream(src) {
        var count = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) add(1)
            return b
        }
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            val n = super.read(buf, off, len)
            if (n > 0) add(n.toLong())
            return n
        }
        override fun skip(n: Long): Long {
            val b = ByteArray(minOf(n, 65536L).toInt().coerceAtLeast(1))
            val r = read(b, 0, b.size)
            return if (r < 0) 0 else r.toLong()
        }
        private fun add(n: Long) {
            count += n
            budget.bytes += n
            if (budget.bytes > budget.limits.maxTotalBytes) throw LimitReached("unpacked size is over the safety limit")
        }
        override fun close() {}
        fun drain() {
            val b = ByteArray(65536)
            while (read(b) >= 0) { /* count the rest */ }
        }
    }
}
