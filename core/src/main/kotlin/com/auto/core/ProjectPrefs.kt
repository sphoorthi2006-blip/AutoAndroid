package com.auto.core

/**
 * The export choices of a project, stored in ProjectManifest.settings (strings) so a reopened project exports the same way.
 * Anything invalid falls back to the default.
 * Phase 5 (Section 15): Adds optional `upscaleProfile` (only written when non-default so old projects and Check18 remain 100% compatible).
 */
data class ExportPrefs(
    val resP: Int = 720,
    val fps: Int = 30,
    val aspect: AspectRatio = AspectRatio.R16_9,
    val captionsOn: Boolean = true,
    val upscale: UpscaleFactor = UpscaleFactor.OFF,
    val upscaleFormat: IntermediateFormat = IntermediateFormat.JPEG,
    val upscaleProfile: UpscaleProfile = UpscaleProfile.MANUAL
) {
    fun toMap(): Map<String, String> = buildMap {
        put("export.res", resP.toString())
        put("export.fps", fps.toString())
        put("export.aspect", aspect.name)
        put("export.captions", captionsOn.toString())
        put("export.upscale", upscale.scale.toString())
        put("export.upscaleFormat", upscaleFormat.name)
        if (upscaleProfile != UpscaleProfile.MANUAL) {
            put("export.upscaleProfile", upscaleProfile.name)
        }
    }

    fun settings() = ExportOptions.settings(Resolution.of(resP), aspect, fps)

    companion object {
        fun fromMap(m: Map<String, String>): ExportPrefs {
            val d = ExportPrefs()
            val r = m["export.res"]?.toIntOrNull()?.takeIf { x -> Resolution.values().any { it.shortSide == x } } ?: d.resP
            val f = m["export.fps"]?.toIntOrNull()?.takeIf { it in ExportSettings.FPS } ?: d.fps
            val a = AspectRatio.values().firstOrNull { it.name == m["export.aspect"] } ?: d.aspect
            val c = m["export.captions"]?.toBooleanStrictOrNull() ?: d.captionsOn
            val u = m["export.upscale"]?.let { raw ->
                raw.toIntOrNull()?.let { s -> UpscaleFactor.values().firstOrNull { it.scale == s } }
                    ?: UpscaleFactor.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            } ?: d.upscale
            val fmt = m["export.upscaleFormat"]?.let { raw ->
                IntermediateFormat.values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            } ?: d.upscaleFormat
            val prof = m["export.upscaleProfile"]?.let { UpscaleProfile.fromString(it) } ?: d.upscaleProfile
            return ExportPrefs(r, f, a, c, u, fmt, prof)
        }
    }
}

/** One line of the Projects list / the Home "recent project" card (includes first picture thumbnail path and projectType). */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedMs: Long,
    val scenes: Int,
    val durationMs: Long,
    val hasTimeline: Boolean,
    val warnings: List<String>,
    val thumbnailPath: String? = null,
    val thumbnailColor: Int? = null,
    val projectType: ProjectType = ProjectType.AUTO_CREATE
)

fun ProjectStore.summaries(): List<ProjectSummary> = list().map { m ->
    try {
        val l = load(m.id)
        val tl = l.timeline
        val firstClip = tl?.clips?.firstOrNull { it.image != null || it.replacementColor != null }
        val dur = tl?.let { if (it.audioMs > 0) it.audioMs else it.endMs } ?: 0L
        ProjectSummary(
            id = m.id,
            name = m.name,
            updatedMs = m.updatedMs,
            scenes = tl?.clips?.size ?: 0,
            durationMs = dur,
            hasTimeline = tl != null,
            warnings = l.warnings,
            thumbnailPath = firstClip?.image,
            thumbnailColor = firstClip?.replacementColor,
            projectType = m.projectType
        )
    } catch (e: Exception) {
        ProjectSummary(m.id, m.name, m.updatedMs, 0, 0L, false, listOf(e.message ?: "unreadable"), projectType = m.projectType)
    }
}

fun ProjectStore.rename(id: String, name: String): ProjectManifest =
    save(load(id).manifest.copy(name = name.trim().ifBlank { "Untitled" }), null)

fun ProjectStore.saveWithPrefs(manifest: ProjectManifest, timeline: Timeline?, prefs: ExportPrefs) =
    save(manifest.copy(settings = manifest.settings + prefs.toMap()), timeline)

object RelativeTime {
    fun label(thenMs: Long, nowMs: Long): String {
        val d = (nowMs - thenMs) / 60_000
        return when {
            d < 1 -> "Just now"
            d < 60 -> "$d min ago"
            d < 24 * 60 -> "${d / 60} h ago"
            d < 48 * 60 -> "Yesterday"
            d < 30L * 24 * 60 -> "${d / (24 * 60)} days ago"
            else -> "Long ago"
        }
    }
}
