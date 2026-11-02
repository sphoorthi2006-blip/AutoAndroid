package com.auto.core

import kotlin.math.max
import kotlin.math.min

/** One picture on the image track. All times are integer milliseconds (no floating-point drift while editing). */
data class ImageClip(val id: Int, val sceneNum: Int, val image: String?, val startMs: Long, val endMs: Long, val text: String = "", val conf: String = "", val heading: Boolean = false, val score: Int = 100, val cutCheck: Boolean = false) {
    val durMs get() = endMs - startMs
}

/** One caption block on the caption track. */
data class CaptionClip(val id: Int, val startMs: Long, val endMs: Long, val text: String)

enum class IssueKind { GAP, OVERLAP, MISSING_IMAGE, FIRST_CLIP_LATE, SPEECH_PAST_END, CLIP_PAST_AUDIO, TOO_SHORT, LOW_CONFIDENCE, LONG_PAUSE }
data class Issue(val kind: IssueKind, val clipId: Int?, val message: String)

enum class Edge { START, END }

/**
 * Immutable timeline snapshot. Every edit returns a NEW Timeline, so undo/redo is just a stack of snapshots (see TimelineEditor).
 * Clips are kept sorted by start time. Edits never touch the narration: audioMs is the real length of the original audio.
 */
data class Timeline(val audioMs: Long, val clips: List<ImageClip>, val captions: List<CaptionClip> = emptyList(), val nextId: Int = 1 + (clips.maxOfOrNull { it.id } ?: 0)) {
    companion object {
        const val MIN_CLIP_MS = 100L
        const val TOUCH_MS = 1L   // two edges closer than this count as touching

        /** Scenes from Pipeline.analyse() -> contiguous clips. Each clip ends exactly where the next starts (no rounding drift). */
        fun fromScenes(scenes: List<Scene>, audioSeconds: Double, words: List<TimedWord>? = null): Timeline {
            val audioMs = Math.round(audioSeconds * 1000)
            val starts = scenes.map { Math.round(it.start * 1000) }
            val clips = scenes.mapIndexed { k, s ->
                val a = if (k == 0) 0L else starts[k]
                val b = if (k + 1 < scenes.size) starts[k + 1] else max(Math.round(s.end * 1000), audioMs)
                ImageClip(k + 1, s.num, s.image, a, max(b, a + 1), s.text, s.conf, false, Confidence.score(s.conf, s.cutCheck, s.image == null, max(b, a + 1) - a), s.cutCheck)
            }
            val caps = words?.let { Captions.parseCues(it).mapIndexed { k, c -> CaptionClip(k + 1, Math.round(c.start * 1000), Math.round(c.end * 1000), c.text) } } ?: emptyList()
            return Timeline(audioMs, clips, caps)
        }
    }

    fun clip(id: Int) = clips.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No clip with id $id")
    val endMs get() = clips.maxOfOrNull { it.endMs } ?: 0L
    private fun withClips(list: List<ImageClip>, nid: Int = nextId) = copy(clips = list.sortedBy { it.startMs }, nextId = nid)
    private fun replace(c: ImageClip) = withClips(clips.map { if (it.id == c.id) c else it })

    /** Slide a clip, keeping its length, inside 0..audioMs. Neighbours are NOT pushed: overlaps/gaps are reported by validate(). */
    fun move(id: Int, newStartMs: Long): Timeline {
        val c = clip(id); val s = newStartMs.coerceIn(0L, max(0L, max(audioMs, endMs) - c.durMs))
        return replace(c.copy(startMs = s, endMs = s + c.durMs))
    }

    /** Drag one edge. rolling = true also moves the touching edge of the neighbour (a "rolling edit": the cut moves, nothing opens up). */
    fun trim(id: Int, edge: Edge, ms: Long, rolling: Boolean = true): Timeline {
        val c = clip(id); val i = clips.indexOfFirst { it.id == id }
        return if (edge == Edge.START) {
            val prev = clips.getOrNull(i - 1)?.takeIf { rolling && Math.abs(it.endMs - c.startMs) < TOUCH_MS }
            val lo = if (prev != null) prev.startMs + MIN_CLIP_MS else 0L
            val s = ms.coerceIn(lo, c.endMs - MIN_CLIP_MS)
            withClips(clips.map { when (it.id) { id -> it.copy(startMs = s); prev?.id -> it.copy(endMs = s); else -> it } })
        } else {
            val next = clips.getOrNull(i + 1)?.takeIf { rolling && Math.abs(it.startMs - c.endMs) < TOUCH_MS }
            val hi = if (next != null) next.endMs - MIN_CLIP_MS else max(audioMs, endMs)
            val e = ms.coerceIn(c.startMs + MIN_CLIP_MS, hi)
            withClips(clips.map { when (it.id) { id -> it.copy(endMs = e); next?.id -> it.copy(startMs = e); else -> it } })
        }
    }

    /** Cut one clip in two at atMs (both halves keep the picture and scene number). Returns this unchanged if either half would be too short. */
    fun split(id: Int, atMs: Long): Timeline {
        val c = clip(id)
        if (atMs < c.startMs + MIN_CLIP_MS || atMs > c.endMs - MIN_CLIP_MS) return this
        return withClips(clips.flatMap { if (it.id == id) listOf(c.copy(endMs = atMs), c.copy(id = nextId, startMs = atMs)) else listOf(it) }, nextId + 1)
    }

    /** Remove a clip. closeGap = true pulls every later clip left by the clip's length (ripple delete). */
    fun delete(id: Int, closeGap: Boolean = false): Timeline {
        val c = clip(id)
        return withClips(clips.filter { it.id != id }.map { if (closeGap && it.startMs >= c.endMs) it.copy(startMs = it.startMs - c.durMs, endMs = it.endMs - c.durMs) else it })
    }

    fun replaceImage(id: Int, path: String?) = replace(clip(id).copy(image = path))
    fun editCaption(id: Int, text: String? = null, startMs: Long? = null, endMs: Long? = null): Timeline {
        val c = captions.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No caption with id $id")
        val s = startMs ?: c.startMs; val e = max(endMs ?: c.endMs, s + 1)
        return copy(captions = captions.map { if (it.id == id) it.copy(text = text ?: it.text, startMs = s, endMs = e) else it }.sortedBy { it.startMs })
    }

    /** The clip showing at time t (the last one starting at or before t that has not ended), or null in a gap. */
    fun clipAt(ms: Long) = clips.lastOrNull { it.startMs <= ms && ms < it.endMs }

    /** Everything a person should look at. speechEndMs = end of the last spoken word (null = use audio length). */
    fun validate(speechEndMs: Long? = null): List<Issue> {
        val out = ArrayList<Issue>()
        if (clips.isEmpty()) return listOf(Issue(IssueKind.GAP, null, "The timeline has no clips."))
        if (clips.first().startMs > 0) out.add(Issue(IssueKind.FIRST_CLIP_LATE, clips.first().id, "Nothing is shown during the first ${clips.first().startMs} ms."))
        for (c in clips) {
            if (c.image == null) out.add(Issue(IssueKind.MISSING_IMAGE, c.id, "Scene ${c.sceneNum} has no picture."))
            if (c.durMs < 600) out.add(Issue(IssueKind.TOO_SHORT, c.id, "Scene ${c.sceneNum} lasts only ${c.durMs} ms."))
            if (c.endMs > audioMs + 50) out.add(Issue(IssueKind.CLIP_PAST_AUDIO, c.id, "Scene ${c.sceneNum} runs ${c.endMs - audioMs} ms past the end of the narration."))
        }
        for ((a, b) in clips.zipWithNext()) {
            if (b.startMs - a.endMs >= 1) out.add(Issue(IssueKind.GAP, a.id, "Gap of ${b.startMs - a.endMs} ms after scene ${a.sceneNum}."))
            else if (a.endMs - b.startMs >= 1) out.add(Issue(IssueKind.OVERLAP, b.id, "Scenes ${a.sceneNum} and ${b.sceneNum} overlap by ${a.endMs - b.startMs} ms."))
        }
        val speechEnd = min(speechEndMs ?: audioMs, audioMs)
        if (endMs < speechEnd - 50) out.add(Issue(IssueKind.SPEECH_PAST_END, clips.last().id, "Narration continues ${speechEnd - endMs} ms after the last picture."))
        return out
    }

    // ---- persistence (Step 7): plain JSON, readable with MiniJson, no extra dependency ----
    private fun q(s: String) = buildString { append('"'); for (ch in s) when { ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\"); ch == '\n' -> append("\\n"); ch == '\r' -> append("\\r"); ch == '\t' -> append("\\t"); ch < ' ' -> append(String.format("\\u%04x", ch.code)); else -> append(ch) }; append('"') }
    fun toJson(): String = buildString {
        append("{\"version\":1,\"audioMs\":$audioMs,\"nextId\":$nextId,\"clips\":[")
        append(clips.joinToString(",") { "{\"id\":${it.id},\"scene\":${it.sceneNum},\"image\":${it.image?.let(::q) ?: "null"},\"start\":${it.startMs},\"end\":${it.endMs},\"text\":${q(it.text)},\"conf\":${q(it.conf)},\"heading\":${it.heading},\"score\":${it.score},\"cutCheck\":${it.cutCheck}}" })
        append("],\"captions\":[")
        append(captions.joinToString(",") { "{\"id\":${it.id},\"start\":${it.startMs},\"end\":${it.endMs},\"text\":${q(it.text)}}" })
        append("]}")
    }

    object Json {
        private fun n(m: Map<*, *>, k: String) = (m[k] as? Double)?.let { Math.round(it) } ?: throw PipelineError("Project file: '$k' is missing or not a number.")
        fun parse(text: String): Timeline {
            val root = try { MiniJson(text).parse() as? Map<*, *> } catch (e: Exception) { null } ?: throw PipelineError("The project file is damaged (not valid JSON).")
            if (n(root, "version") != 1L) throw PipelineError("Unsupported project file version ${n(root, "version")}.")
            val clips = (root["clips"] as? List<*> ?: throw PipelineError("Project file has no clips.")).map { c ->
                c as Map<*, *>; ImageClip(n(c, "id").toInt(), n(c, "scene").toInt(), c["image"] as? String, n(c, "start"), n(c, "end"), c["text"] as? String ?: "", c["conf"] as? String ?: "", c["heading"] as? Boolean ?: false, (c["score"] as? Double)?.toInt() ?: 100, c["cutCheck"] as? Boolean ?: false)
            }
            val caps = (root["captions"] as? List<*> ?: emptyList<Any>()).map { c -> c as Map<*, *>; CaptionClip(n(c, "id").toInt(), n(c, "start"), n(c, "end"), c["text"] as? String ?: "") }
            return Timeline(n(root, "audioMs"), clips.sortedBy { it.startMs }, caps, n(root, "nextId").toInt())
        }
    }
}

/** Undo/redo around Timeline. The UI calls edit { it.split(...) } and shows `current`; new edits clear the redo stack. */
class TimelineEditor(initial: Timeline, private val limit: Int = 200) {
    private val undoStack = ArrayDeque<Timeline>(); private val redoStack = ArrayDeque<Timeline>()
    var current: Timeline = initial; private set
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()
    fun edit(f: (Timeline) -> Timeline): Timeline {
        val next = f(current)
        if (next != current) { undoStack.addLast(current); if (undoStack.size > limit) undoStack.removeFirst(); redoStack.clear(); current = next }
        return current
    }
    fun undo(): Timeline { if (canUndo) { redoStack.addLast(current); current = undoStack.removeLast() }; return current }
    fun redo(): Timeline { if (canRedo) { undoStack.addLast(current); current = redoStack.removeLast() }; return current }
}
