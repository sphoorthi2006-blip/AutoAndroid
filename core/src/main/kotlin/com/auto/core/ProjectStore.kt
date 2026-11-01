package com.auto.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One project on disk. inputs: role ("audio","script","captions","details","pictures"...) -> file name inside <project>/inputs. */
data class ProjectManifest(val id: String, val name: String, val createdMs: Long, val updatedMs: Long, val inputs: Map<String, List<String>> = emptyMap(), val settings: Map<String, String> = emptyMap()) {
    fun toJson() = "{\"version\":1,\"id\":${jsonQuote(id)},\"name\":${jsonQuote(name)},\"created\":$createdMs,\"updated\":$updatedMs,\"inputs\":{" +
        inputs.entries.joinToString(",") { (k, v) -> "${jsonQuote(k)}:[${v.joinToString(",") { jsonQuote(it) }}]" } + "},\"settings\":{" +
        settings.entries.joinToString(",") { (k, v) -> "${jsonQuote(k)}:${jsonQuote(v)}" } + "}}"
    companion object {
        fun parse(text: String): ProjectManifest {
            val m = try { MiniJson(text).parse() as? Map<*, *> } catch (e: Exception) { null } ?: throw PipelineError("Project manifest is damaged.")
            fun l(k: String) = (m[k] as? Double)?.let { Math.round(it) } ?: throw PipelineError("Project manifest: '$k' missing.")
            val inputs = (m["inputs"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to ((v as? List<*>)?.map { it.toString() } ?: emptyList()) } ?: emptyMap()
            val settings = (m["settings"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
            return ProjectManifest(m["id"] as? String ?: throw PipelineError("Project manifest: 'id' missing."), m["name"] as? String ?: "Untitled", l("created"), l("updated"), inputs, settings)
        }
    }
}

class LoadedProject(val manifest: ProjectManifest, val timeline: Timeline?, val warnings: List<String>)

/**
 * Step 7. Folder per project below `root`:  <id>/manifest.json, <id>/timeline.json (+ .bak of the previous good copy), <id>/inputs/, <id>/work/ (temp, safe to delete).
 * Writes are atomic (temp file, flush to disk, rename), so a crash or killed process leaves either the old or the new file, never half of one.
 * If the current file is unreadable, load() falls back to the .bak copy and says so in `warnings` instead of silently losing the project.
 */
class ProjectStore(val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { root.mkdirs() }
    fun dir(id: String) = File(root, safeId(id))
    private fun safeId(id: String): String { require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Bad project id" }; return id }

    fun create(name: String): ProjectManifest {
        val now = clock(); var id = "p$now"; var k = 1
        while (File(root, id).exists()) id = "p$now-${k++}"
        File(dir(id), "inputs").mkdirs(); File(dir(id), "work").mkdirs()
        return ProjectManifest(id, name.ifBlank { "Untitled" }, now, now).also { writeAtomic(File(dir(id), "manifest.json"), it.toJson()) }
    }

    /** Copy a user file into the project (once), so later steps never depend on a temporary URI/cache path. Returns the stored file. */
    fun importFile(id: String, src: File, name: String = src.name): File {
        val safe = name.replace('\\', '_').replace('/', '_').ifBlank { "file" }
        var dest = File(File(dir(id), "inputs").also { it.mkdirs() }, safe); var k = 1
        while (dest.exists()) dest = File(dest.parentFile, "${dest.nameWithoutExtension}_${k++}${if (dest.extension.isEmpty()) "" else "." + dest.extension}")
        Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING); return dest
    }

    fun save(manifest: ProjectManifest, timeline: Timeline?): ProjectManifest {
        val m = manifest.copy(updatedMs = clock())
        if (timeline != null) writeAtomic(File(dir(m.id), "timeline.json"), timeline.toJson()) { Timeline.Json.parse(it) }
        writeAtomic(File(dir(m.id), "manifest.json"), m.toJson()) { ProjectManifest.parse(it) }; return m
    }

    fun load(id: String): LoadedProject {
        val w = ArrayList<String>()
        val m = readGood(File(dir(id), "manifest.json"), w, "project details") { ProjectManifest.parse(it) } ?: throw PipelineError("Project '$id' cannot be opened: its details file is missing or damaged and there is no backup.")
        val tf = File(dir(id), "timeline.json")
        val t = if (tf.exists() || File(tf.path + ".bak").exists()) readGood(tf, w, "timeline") { Timeline.Json.parse(it) } else null
        if (t == null && (tf.exists() || File(tf.path + ".bak").exists())) w.add("The saved timeline could not be read; the project opens without it (re-run Analyse).")
        return LoadedProject(m, t, w)
    }

    /** Newest first. Damaged projects are skipped here; open one by id to see why. */
    fun list(): List<ProjectManifest> = (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { d ->
        try { load(d.name).manifest } catch (e: Exception) { null }
    }.sortedByDescending { it.updatedMs }

    fun delete(id: String) { dir(id).deleteRecursively() }
    /** Remove temp data only (analysis scratch, extracted ZIPs); inputs and saved state are kept. */
    fun cleanTemp(id: String): Long { val w = File(dir(id), "work"); val n = w.walkTopDown().filter { it.isFile }.sumOf { it.length() }; w.deleteRecursively(); w.mkdirs(); return n }

    private fun <T> readGood(f: File, warn: MutableList<String>, what: String, parse: (String) -> T): T? {
        for ((k, c) in listOf(f, File(f.path + ".bak")).withIndex()) {
            if (!c.exists()) continue
            try { return parse(c.readText(Charsets.UTF_8)).also { if (k == 1) warn.add("The $what file was damaged; the previous saved copy was restored.") } } catch (e: Exception) { /* try backup */ }
        }
        return null
    }

    /** check = parser for the CURRENT file; the .bak is only refreshed from a current file that is itself valid, so a damaged file never overwrites the good backup. */
    private fun writeAtomic(f: File, text: String, check: ((String) -> Unit)? = null) {
        f.parentFile.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { o -> o.write(text.toByteArray(Charsets.UTF_8)); o.flush(); o.fd.sync() }
        if (f.exists() && (check == null || runCatching { check(f.readText(Charsets.UTF_8)) }.isSuccess)) Files.copy(f.toPath(), File(f.path + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
        try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (e: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}
