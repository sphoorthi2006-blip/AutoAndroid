package com.auto.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Project origin/type (Section 9): Auto Create generated project vs Manual Editor / Blank project. */
enum class ProjectType(val label: String) {
    AUTO_CREATE("Auto Create"),
    MANUAL("Manual Editor");

    companion object {
        fun fromString(raw: String?): ProjectType =
            values().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO_CREATE
    }
}

/** One project on disk. inputs: role ("audio","script","captions","details","pictures"...) -> file name inside <project>/inputs. */
data class ProjectManifest(
    val id: String,
    val name: String,
    val createdMs: Long,
    val updatedMs: Long,
    val inputs: Map<String, List<String>> = emptyMap(),
    val settings: Map<String, String> = emptyMap()
) {
    val projectType: ProjectType get() = ProjectType.fromString(settings["project.type"])
    val parentProjectId: String? get() = settings["project.parentId"]

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
 * Step 7 & Phase 6 (Sections 9, 17). Folder per project below `root`:
 * <id>/manifest.json, <id>/timeline.json (+ .bak of the previous good copy), <id>/inputs/, <id>/work/ (temp, safe to delete).
 * Writes are atomic (temp file, flush to disk, rename), so a crash or killed process leaves either the old or the new file, never half of one.
 * Supports non-destructive `duplicate()` (Save As / Edit a Copy) which clones manifest, timeline, settings, and input references without mutating the original project.
 */
class ProjectStore(val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { root.mkdirs() }
    fun dir(id: String) = File(root, safeId(id))
    private fun safeId(id: String): String { require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "Bad project id" }; return id }

    fun create(name: String, type: ProjectType = ProjectType.AUTO_CREATE): ProjectManifest {
        val now = clock(); var id = "p$now"; var k = 1
        while (File(root, id).exists()) id = "p$now-${k++}"
        File(dir(id), "inputs").mkdirs(); File(dir(id), "work").mkdirs()
        val settings = if (type == ProjectType.AUTO_CREATE) emptyMap() else mapOf("project.type" to type.name)
        return ProjectManifest(id, name.ifBlank { "Untitled" }, now, now, emptyMap(), settings).also {
            writeAtomic(File(dir(id), "manifest.json"), it.toJson())
        }
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

    /**
     * Phase 6 (Sections 8, 9, 17): Save As / Edit a Copy.
     * Creates a new project duplicating the source project's timeline, settings, and media references without mutating the original project folder or files.
     */
    fun duplicate(sourceId: String, newName: String = "", asManualCopy: Boolean = true): LoadedProject {
        val src = load(sourceId)
        val copyTitle = newName.trim().ifBlank { "${src.manifest.name} (Copy)" }
        val newType = if (asManualCopy) ProjectType.MANUAL else src.manifest.projectType
        val created = create(copyTitle, newType)
        val srcInputsDir = File(dir(sourceId), "inputs")
        val dstInputsDir = File(dir(created.id), "inputs").also { it.mkdirs() }
        // Copy files in <sourceId>/inputs/ into <newId>/inputs/ so deleting or editing the copy never touches the original
        val pathMap = HashMap<String, String>()
        if (srcInputsDir.exists()) {
            srcInputsDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                val dstFile = File(dstInputsDir, f.name)
                Files.copy(f.toPath(), dstFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                pathMap[f.path] = dstFile.path
            }
        }
        val remappedTimeline = src.timeline?.let { tl ->
            var t2 = tl
            for (c in tl.clips) {
                val img = c.image?.let { pathMap[it] ?: it }
                val orig = c.originalImage?.let { pathMap[it] ?: it }
                val repl = c.replacementImage?.let { pathMap[it] ?: it }
                if (img != c.image || orig != c.originalImage || repl != c.replacementImage) {
                    t2 = t2.replaceClipImages(c.id, img, orig, repl)
                }
            }
            tl.mix.music?.let { mu ->
                val mp = pathMap[mu.path] ?: mu.path
                if (mp != mu.path) t2 = t2.setMix(t2.mix.copy(music = mu.copy(path = mp)))
            }
            t2
        }
        val mergedSettings = LinkedHashMap(src.manifest.settings).apply {
            put("project.type", newType.name)
            put("project.parentId", sourceId)
        }
        val savedManifest = save(
            created.copy(inputs = src.manifest.inputs, settings = mergedSettings),
            remappedTimeline
        )
        return LoadedProject(savedManifest, remappedTimeline, src.warnings)
    }

    /** Inspects all media file paths referenced by a project (clips, replacements, music, narration) and reports whether each exists on disk. */
    fun inspectMediaReferences(id: String): Map<String, Boolean> {
        val lp = load(id)
        val result = LinkedHashMap<String, Boolean>()
        val inputsDir = File(dir(id), "inputs")
        lp.manifest.inputs.values.flatten().forEach { name ->
            val f = File(inputsDir, name)
            result[f.path] = f.exists() && f.isFile
        }
        lp.timeline?.clips?.forEach { c ->
            c.image?.let { result[it] = File(it).isFile }
            c.originalImage?.let { result[it] = File(it).isFile }
            c.replacementImage?.let { result[it] = File(it).isFile }
        }
        lp.timeline?.mix?.music?.path?.let { result[it] = File(it).isFile }
        return result
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
