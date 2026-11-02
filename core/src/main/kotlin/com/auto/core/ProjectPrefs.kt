package com.auto.core

/** The export choices of a project, stored in ProjectManifest.settings (strings) so a reopened project exports the same way. Anything invalid falls back to the default. */
data class ExportPrefs(val resP: Int = 720, val fps: Int = 30, val aspect: AspectRatio = AspectRatio.R16_9, val captionsOn: Boolean = true) {
    fun toMap() = mapOf("export.res" to resP.toString(), "export.fps" to fps.toString(), "export.aspect" to aspect.name, "export.captions" to captionsOn.toString())
    fun settings() = ExportOptions.settings(Resolution.of(resP), aspect, fps)
    companion object {
        fun fromMap(m: Map<String, String>): ExportPrefs {
            val d = ExportPrefs()
            val r = m["export.res"]?.toIntOrNull()?.takeIf { x -> Resolution.values().any { it.shortSide == x } } ?: d.resP
            val f = m["export.fps"]?.toIntOrNull()?.takeIf { it in ExportSettings.FPS } ?: d.fps
            val a = AspectRatio.values().firstOrNull { it.name == m["export.aspect"] } ?: d.aspect
            return ExportPrefs(r, f, a, m["export.captions"]?.toBooleanStrictOrNull() ?: d.captionsOn)
        }
    }
}

/** One line of the Projects list / the Home "recent project" card. */
data class ProjectSummary(val id: String, val name: String, val updatedMs: Long, val scenes: Int, val durationMs: Long, val hasTimeline: Boolean, val warnings: List<String>)

fun ProjectStore.summaries(): List<ProjectSummary> = list().map { m ->
    try { val l = load(m.id); ProjectSummary(m.id, m.name, m.updatedMs, l.timeline?.clips?.size ?: 0, l.timeline?.audioMs ?: 0L, l.timeline != null, l.warnings) }
    catch (e: Exception) { ProjectSummary(m.id, m.name, m.updatedMs, 0, 0L, false, listOf(e.message ?: "unreadable")) }
}
fun ProjectStore.rename(id: String, name: String): ProjectManifest = save(load(id).manifest.copy(name = name.trim().ifBlank { "Untitled" }), null)
fun ProjectStore.saveWithPrefs(manifest: ProjectManifest, timeline: Timeline?, prefs: ExportPrefs) = save(manifest.copy(settings = manifest.settings + prefs.toMap()), timeline)

object RelativeTime {
    fun label(thenMs: Long, nowMs: Long): String {
        val d = (nowMs - thenMs) / 60_000
        return when { d < 1 -> "Just now"; d < 60 -> "$d min ago"; d < 24 * 60 -> "${d / 60} h ago"; d < 48 * 60 -> "Yesterday"; d < 30L * 24 * 60 -> "${d / (24 * 60)} days ago"; else -> "Long ago" }
    }
}
