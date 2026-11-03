package com.auto.core

import java.util.Locale

enum class MediaKind(val label: String) { VIDEO("Video"), IMAGE("Image"), AUDIO("Audio"), OTHER("Other") }
enum class MediaOrigin { IMPORTED, PROJECT }
enum class MediaFilter(val label: String) { ALL("All"), VIDEOS("Videos"), IMAGES("Images"), AUDIO("Audio"), IMPORTED("Imported files"), PROJECT("Project assets") }
enum class MediaSort(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), TYPE("Type") }

/** One file the Media tab shows. Thumbnails and duration/size-in-pixels are filled by the app (needs Android); everything else is plain data. */
data class MediaAsset(val path: String, val name: String, val kind: MediaKind, val sizeBytes: Long, val modifiedMs: Long, val origin: MediaOrigin, val durationMs: Long? = null, val width: Int? = null, val height: Int? = null) {
    /** Can the exporter use it? Pictures become scenes, audio becomes the music bed. Video clips are NOT supported by this app's picture-based timeline, so they are shown but cannot be added. */
    val usable get() = kind == MediaKind.IMAGE || kind == MediaKind.AUDIO
    val unusableReason get() = when (kind) { MediaKind.VIDEO -> "Video clips cannot be used in a project yet (this app builds videos from pictures and audio)."; MediaKind.OTHER -> "This file type is not supported."; else -> "" }
}

object MediaLibrary {
    private val VIDEO = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")
    fun classify(name: String): MediaKind { val e = name.substringAfterLast('.', "").lowercase(); return when { e in Pipeline.IMAGE_EXT -> MediaKind.IMAGE; e in VIDEO -> MediaKind.VIDEO; e in AUDIO -> MediaKind.AUDIO; else -> MediaKind.OTHER } }
    fun humanSize(b: Long): String = when { b < 1024 -> "$b B"; b < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", b / 1024.0); b < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", b / 1048576.0); else -> String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0) }
    fun humanDuration(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }

    /** The "file information" rows. Only facts that are known are listed. */
    fun info(a: MediaAsset): List<Pair<String, String>> = buildList {
        add("Name" to a.name); add("Type" to a.kind.label); add("Size" to humanSize(a.sizeBytes))
        if (a.width != null && a.height != null) add("Dimensions" to "${a.width} x ${a.height}")
        if (a.durationMs != null) add("Length" to humanDuration(a.durationMs))
        add("Source" to if (a.origin == MediaOrigin.PROJECT) "Project asset" else "Imported")
    }

    fun filter(all: List<MediaAsset>, f: MediaFilter, query: String = ""): List<MediaAsset> {
        val q = query.trim().lowercase()
        return all.filter { a ->
            (when (f) { MediaFilter.ALL -> true; MediaFilter.VIDEOS -> a.kind == MediaKind.VIDEO; MediaFilter.IMAGES -> a.kind == MediaKind.IMAGE; MediaFilter.AUDIO -> a.kind == MediaKind.AUDIO; MediaFilter.IMPORTED -> a.origin == MediaOrigin.IMPORTED; MediaFilter.PROJECT -> a.origin == MediaOrigin.PROJECT }) &&
                (q.isEmpty() || a.name.lowercase().contains(q))
        }
    }
    /** NAME sorts like a person expects (img2 before img10). DATE and SIZE: newest/largest first unless ascending. */
    fun sort(list: List<MediaAsset>, by: MediaSort, ascending: Boolean = true): List<MediaAsset> {
        val cmp: Comparator<MediaAsset> = when (by) {
            MediaSort.NAME -> Comparator { a, b -> Matching.naturalCompare(a.name.lowercase(), b.name.lowercase()) }
            MediaSort.DATE -> compareBy { it.modifiedMs }; MediaSort.SIZE -> compareBy { it.sizeBytes }
            MediaSort.TYPE -> compareBy<MediaAsset> { it.kind.ordinal }.thenComparator { a, b -> Matching.naturalCompare(a.name.lowercase(), b.name.lowercase()) }
        }
        val asc = list.sortedWith(cmp); return if (ascending) asc else asc.reversed()
    }
    fun counts(all: List<MediaAsset>): Map<MediaFilter, Int> = MediaFilter.values().associateWith { filter(all, it).size }
}

/** Multi-select state (immutable, so the UI state can simply hold it). */
data class MediaSelection(val paths: Set<String> = emptySet()) {
    val active get() = paths.isNotEmpty()
    fun toggle(p: String) = MediaSelection(if (p in paths) paths - p else paths + p)
    fun selectAll(list: List<MediaAsset>) = MediaSelection(list.map { it.path }.toSet())
    fun pick(all: List<MediaAsset>) = all.filter { it.path in paths }
    /** What "Add to project" can really add; the rest is reported by name. */
    fun addable(all: List<MediaAsset>) = pick(all).partition { it.usable }
}
