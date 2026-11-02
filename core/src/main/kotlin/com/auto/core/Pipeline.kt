package com.auto.core

import java.io.File
import java.util.zip.ZipFile

/** ZIP + folder handling and the one-call analyse() that the phone app uses. Pure JVM (java.util.zip) - works on Android too. */
object Pipeline {
    val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "bmp")
    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT
    private fun skip(parts: List<String>) = parts.any { it.startsWith(".") || it == "__MACOSX" } 

    /** Pictures inside a ZIP, extracted below outDir (kept in sub-folders). root = the ZIP path, name = the ZIP's name without .zip. */
    fun picturesFromZip(zip: File, outDir: File): List<PicRef> {
        val out = ArrayList<PicRef>(); val root = outDir.canonicalFile
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty() || skip(parts) || !isImage(parts.last())) continue
                val dest = File(root, parts.joinToString("/")).canonicalFile
                if (!dest.path.startsWith(root.path + File.separator)) continue   // zip-slip guard
                dest.parentFile.mkdirs()
                z.getInputStream(e).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
                out.add(PicRef(zip.path, parts, dest.path))
            }
        }
        return out
    }

    /** Pictures in a folder tree. root = the folder, rel = path parts below it. */
    fun picturesFromFolder(dir: File): List<PicRef> =
        dir.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val parts = f.relativeTo(dir).path.replace('\\', '/').split('/')
            if (skip(parts) || !isImage(f.name)) null else PicRef(dir.path, parts, f.path)
        }.toList()

    /** Pictures from any mix of folders and ZIPs the user chose. */
    fun collectPictures(sources: List<File>, workDir: File): Pair<List<PicRef>, Map<String, String>> {
        val refs = ArrayList<PicRef>(); val names = HashMap<String, String>()
        for ((k, s) in sources.withIndex()) {
            if (s.isDirectory) { refs += picturesFromFolder(s); names[s.path] = s.name }
            else if (s.extension.equals("zip", true)) { refs += picturesFromZip(s, File(workDir, "zip$k")); names[s.path] = s.nameWithoutExtension }
            else if (isImage(s.name)) { refs.add(PicRef(s.parent ?: "", listOf(s.name), s.path)); names[s.parent ?: ""] = s.parentFile?.name ?: "Pictures" }
        }
        return Pair(refs, names)
    }

    class Analysis(val timing: TimingResult, val match: Matching.MatchResult) {
        val scenes get() = match.scenes
        val warnings get() = match.warnings
    }

    /** details + script + (captions) + (audio) + picture folders/ZIPs -> timed scenes, each with its picture. */
    fun analyse(detailsText: String, scriptText: String, words: List<TimedWord>?, audio: FloatArray?, audioSeconds: Double,
                pictureSources: List<File>, workDir: File, match: String = "number", verified: Boolean = false, rows: List<Cue>? = null): Analysis {
        val warn = ArrayList<String>()
        var items = scopedItems(Details.parseSceneFile(detailsText), scriptText, warn)
        if (items.isEmpty()) throw PipelineError("No scenes were found in the details file.")
        val (refs, rootNames) = collectPictures(pictureSources, workDir)
        if (refs.isEmpty()) throw PipelineError("No pictures (png, jpg, webp) were found in the chosen folders or ZIP files.")
        val groups = Matching.imageGroups(refs, rootNames)
        // a section heading the script has and the narrator says (but the details do not list) becomes a scene of its own
        val spoken = words?.map { Details.tokens(it.text).joinToString("") }?.filter { it.isNotEmpty() }
        items = Matching.addHeadingItems(items, scriptText, groups, warn, spoken)
        val timing = timeScenes(items, scriptText, words, audio, audioSeconds, verified, rows)
        val res = Matching.matchPictures(items, timing.scenes, groups, scriptText, match, warnings0 = warn + timing.warnings,
            mtime = { File(it).lastModified() })
        return Analysis(timing, res)
    }
}
