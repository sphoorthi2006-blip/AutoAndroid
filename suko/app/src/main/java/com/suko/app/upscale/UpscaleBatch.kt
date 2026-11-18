package com.suko.app.upscale

import java.io.File
import java.io.InputStream

/** Where the Upscale Images tool puts its results. Both modes write into a folder the user chooses. */
enum class OutputMode { PNG_FOLDER, ZIPS }

/** One finished picture and where it goes: [group] = the input it came from, [relPath] = its path inside that group (always ends in .png). */
class OutItem(val group: String, val relPath: String, val job: UpscaleJob)

/**
 * Pure rules of the Upscale Images tool (no Android classes, tested on the JVM).
 *
 * Inputs are the same as everywhere in Suko: loose pictures, ZIPs (also nested) and folders. An intake path looks like
 * "1.jpg" (loose), "Folder/sub/1.jpg" (folder) or "pack.zip/inner/1.jpg" (ZIP). The first part names the group.
 * File names keep their number and only change the extension to .png, because the Auto Editor and the SVG Editor
 * match pictures by the number in the name.
 */
object UpscaleBatch {
    const val LOOSE_GROUP = "pictures"
    const val OUT_FOLDER = "Suko upscaled"

    private val BAD = Regex("[\\\\:*?\"<>|]")

    private fun clean(part: String): String = part.replace(BAD, "_").trim().trim('.')
    private fun noZip(part: String): String = if (part.endsWith(".zip", ignoreCase = true)) part.dropLast(4) else part

    private fun parts(path: String): List<String> =
        path.replace('\\', '/').split('/').map { clean(it) }.filter { it.isNotEmpty() && it != ".." }

    /** The input a picture belongs to: its ZIP or folder name, or [LOOSE_GROUP] for loose pictures. */
    fun groupOf(path: String): String {
        val p = parts(path)
        return if (p.size <= 1) LOOSE_GROUP else noZip(p[0]).ifEmpty { LOOSE_GROUP }
    }

    /** "inner/1.jpg" -> "inner/1.png". A ZIP inside a ZIP becomes a plain folder name. */
    fun relPathOf(path: String): String {
        val p = parts(path)
        val inside = if (p.size <= 1) p else p.drop(1)
        if (inside.isEmpty()) return "image.png"
        val dirs = inside.dropLast(1).map { noZip(it) }
        val file = inside.last()
        val stem = file.substringBeforeLast('.', file).ifEmpty { file }
        return (dirs + "$stem.png").joinToString("/")
    }

    /** Name of the ZIP written for a group: "pack" -> "pack_x2.zip". */
    fun zipName(group: String): String = "${group.ifEmpty { LOOSE_GROUP }}_x2.zip"

    /**
     * Output items for the pictures that have a result, in input order. Two pictures that would get the same output
     * name inside one group (1.jpg and 1.png) get _2, _3 ... so nothing is overwritten.
     */
    fun layout(jobs: List<UpscaleJob>): List<OutItem> {
        val used = HashSet<String>()
        val out = ArrayList<OutItem>()
        for (j in jobs) {
            val group = groupOf(j.entry.path)
            val first = relPathOf(j.entry.path)
            val dir = first.substringBeforeLast('/', "")
            val stem = first.substringAfterLast('/').removeSuffix(".png")
            var rel = first
            var n = 2
            while (!used.add(group.lowercase() + "|" + rel.lowercase())) {
                rel = (if (dir.isEmpty()) "" else "$dir/") + "${stem}_${n++}.png"
            }
            out += OutItem(group, rel, j)
        }
        return out
    }

    /** Where the bytes of one picture come from, whether it was unpacked from a ZIP (file) or stays where the user has it (node). */
    fun open(job: UpscaleJob): InputStream =
        job.entry.file?.inputStream() ?: job.entry.node?.open() ?: throw java.io.IOException("no source for ${job.entry.name}")

    /** One plain sentence for the result screen. */
    fun summary(saved: Int, total: Int, mode: OutputMode, outputs: Int): String {
        val pics = if (saved == 1) "1 picture" else "$saved pictures"
        val where = if (mode == OutputMode.ZIPS) (if (outputs == 1) "into 1 ZIP" else "into $outputs ZIPs") else "as PNG files"
        return if (saved == total) "Saved $pics $where." else "Saved $saved of $total pictures $where."
    }

    fun cacheFile(cache: UpscaleCache, job: UpscaleJob): File = cache.fileFor(job.key)
}
