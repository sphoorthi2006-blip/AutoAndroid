package com.auto.core

import java.io.File
import java.util.zip.ZipFile

/** ZIP + folder handling and the one-call analyse() that the phone app uses (the logic itself is in Analyser). Pure JVM (java.util.zip) - works on Android too. */
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

    /** Everything is optional. pictureSources = ZIPs / folders / loose picture files. Returns the scenes, the timing and the warning list. */
    fun analyse(timestampsText: String?, timestampsName: String, scriptText: String?, pictureSources: List<File>, workDir: File,
                samples: FloatArray?, audioSeconds: Double, words: List<TimedWord>? = null, notes: List<String> = emptyList()): Analysis {
        val start = notes.map { AnalyseIssue(it, null, "info", "input") }.toMutableList()
        val paths = ArrayList<String>()
        for ((k, src) in pictureSources.withIndex()) {
            try {
                paths += if (src.isDirectory) picturesFromFolder(src).map { it.path }
                else if (src.extension.equals("zip", true)) picturesFromZip(src, File(workDir, "zip$k")).map { it.path }
                else if (isImage(src.name)) listOf(src.path) else emptyList()
            } catch (e: Exception) { start.add(AnalyseIssue("Could not read the pictures in '${src.name}': ${e.message}", null, "warn", "file")) }
        }
        return Analyser.analyse(timestampsText, timestampsName, scriptText, paths, samples, audioSeconds, words, start)
    }
}
