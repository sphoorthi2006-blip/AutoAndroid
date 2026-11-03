package com.auto.core

import kotlin.math.max
import kotlin.math.min

/**
 * One picture (or solid-colour replacement screen) on the image track.
 * All times are integer milliseconds (no floating-point drift while editing).
 * Phase 3 (Section 12): Adds optional `replacementColor` (ARGB Int) additively while preserving `replacementImage` and `originalImage`.
 */
data class ImageClip(
    val id: Int,
    val sceneNum: Int,
    val image: String?,
    val startMs: Long,
    val endMs: Long,
    val text: String = "",
    val conf: String = "",
    val heading: Boolean = false,
    val score: Int = 100,
    val cutCheck: Boolean = false,
    val fx: ClipFx = ClipFx(),
    val transition: TransitionSpec = TransitionSpec(),
    val originalImage: String? = null,
    val replacementImage: String? = null,
    val replacementColor: Int? = null
) {
    val durMs get() = endMs - startMs
    val hasReplacement get() = replacementImage != null || replacementColor != null
    val isSolidColor get() = replacementColor != null
}

/**
 * A free text on the text track (Text Studio / Design Studio).
 * Phase 7 (Section 10): Adds optional additive `name`, `visible` (honored by preview & export),
 * `locked` (prevents drag/edit when locked), and `zIndex` (layer ordering honored by preview & export).
 */
data class TextClip(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val style: OverlayStyle = OverlayStyle(),
    val name: String = "",
    val visible: Boolean = true,
    val locked: Boolean = false,
    val zIndex: Int = 0
) {
    val durMs get() = endMs - startMs
    val displayName get() = name.ifBlank { text.take(18).ifBlank { "Text $id" } }
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
data class Timeline(
    val audioMs: Long,
    val clips: List<ImageClip>,
    val captions: List<CaptionClip> = emptyList(),
    val nextId: Int = 1 + (clips.maxOfOrNull { it.id } ?: 0),
    val texts: List<TextClip> = emptyList(),
    val captionStyle: OverlayStyle = StylePresets.captionDefault,
    val mix: AudioMix = AudioMix()
) {
    companion object {
        const val MIN_CLIP_MS = 100L
        const val TOUCH_MS = 1L   // two edges closer than this count as touching
        const val DEFAULT_BLANK_CLIP_MS = 3000L

        /** Phase 6 (Section 9): Creates an empty timeline for a Blank Manual Editor project. */
        fun blank(audioMs: Long = 0L): Timeline = Timeline(audioMs = max(0L, audioMs), clips = emptyList(), nextId = 1)

        /** Scenes from Pipeline.analyse() -> contiguous clips. Each clip ends exactly where the next starts (no rounding drift). */
        fun fromScenes(scenes: List<Scene>, audioSeconds: Double, words: List<TimedWord>? = null): Timeline {
            val audioMs = Math.round(audioSeconds * 1000)
            val starts = scenes.map { Math.round(it.start * 1000) }
            val clips = scenes.mapIndexed { k, s ->
                val a = if (k == 0) 0L else starts[k]
                val b = if (k + 1 < scenes.size) starts[k + 1] else max(Math.round(s.end * 1000), audioMs)
                val hd = s.item?.heading == true
                ImageClip(k + 1, s.num, s.image, a, max(b, a + 1), s.text, s.conf, hd, Confidence.score(s.conf, s.cutCheck, s.image == null && !hd, max(b, a + 1) - a), s.cutCheck)
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

    /**
     * Set one scene's length to ANY value (even longer than the narration). No magnet: the scene keeps its start, every LATER scene is pushed
     * later (or pulled earlier when shortened) by the same difference, nothing is squeezed and nothing is clamped to the audio.
     */
    fun setLength(id: Int, durMs: Long): Timeline {
        val i = clips.indexOfFirst { it.id == id }; if (i < 0) throw IllegalArgumentException("No clip with id $id")
        val c = clips[i]; val d = max(MIN_CLIP_MS, durMs)
        val later = clips.drop(i + 1)
        val shift = if (later.isEmpty()) 0L else max(d - c.durMs, -(later.minOf { it.startMs }))   // never push a scene before 0
        if (d == c.durMs && shift == 0L) return this
        return withClips(clips.mapIndexed { k, x -> when { k == i -> x.copy(endMs = x.startMs + d); k > i -> x.copy(startMs = x.startMs + shift, endMs = x.endMs + shift); else -> x } })
    }

    /** Cut one clip in two at atMs (both halves keep the picture/colour and scene number). Returns this unchanged if either half would be too short. */
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

    /** Phase 6 (Section 9): Insert a picture or solid-colour clip into a blank or manual timeline at `insertAtMs` (null = at end) with `durationMs`. */
    fun insertClip(
        imagePath: String?,
        insertAtMs: Long? = null,
        durationMs: Long = DEFAULT_BLANK_CLIP_MS,
        text: String = "",
        replacementColor: Int? = null
    ): Timeline {
        val dur = max(MIN_CLIP_MS, durationMs)
        val targetStart = (insertAtMs ?: endMs).coerceAtLeast(0L)
        // A playhead strictly inside a clip snaps to that clip's end; exactly on a boundary it inserts there. Later clips ripple forward by `dur`.
        val snapStart = clips.firstOrNull { targetStart > it.startMs && targetStart < it.endMs }?.endMs ?: targetStart
        val shifted = clips.map { c ->
            if (c.startMs >= snapStart) c.copy(startMs = c.startMs + dur, endMs = c.endMs + dur) else c
        }
        val newClip = ImageClip(
            id = nextId,
            sceneNum = shifted.size + 1,
            image = if (replacementColor != null) null else imagePath,
            startMs = snapStart,
            endMs = snapStart + dur,
            text = text,
            originalImage = imagePath,
            replacementColor = replacementColor?.let { (0xFF000000L or (it.toLong() and 0xFFFFFFL)).toInt() }
        )
        val renumbered = (shifted + newClip).sortedBy { it.startMs }.mapIndexed { idx, c -> c.copy(sceneNum = idx + 1) }
        return copy(clips = renumbered, nextId = nextId + 1)
    }

    /** Phase 6 & 7: Reorder picture clip `id` to `newIndex` (0-based), keeping all clips contiguous from 0 ms. */
    fun reorderClip(id: Int, newIndex: Int): Timeline {
        val idx = clips.indexOfFirst { it.id == id }
        if (idx < 0) throw IllegalArgumentException("No clip with id $id")
        val target = newIndex.coerceIn(0, clips.size - 1)
        if (idx == target) return this
        val mut = ArrayList(clips)
        val item = mut.removeAt(idx)
        mut.add(target, item)
        var cur = 0L
        val rebuilt = mut.mapIndexed { i, c ->
            val d = c.durMs
            val updated = c.copy(sceneNum = i + 1, startMs = cur, endMs = cur + d)
            cur += d
            updated
        }
        return copy(clips = rebuilt)
    }

    /** Replace scene with a picture (clears any solid-colour replacement while preserving originalImage). */
    fun replaceImage(id: Int, path: String?): Timeline {
        val c = clip(id)
        val orig = if (c.hasReplacement) c.originalImage else c.image
        return replace(c.copy(image = path, originalImage = orig, replacementImage = path, replacementColor = null))
    }

    /** Phase 3 (Section 12): Replace scene with a solid ARGB colour (Black, White, or Custom) while preserving originalImage and scene timing. */
    fun replaceColor(id: Int, argb: Int): Timeline {
        val c = clip(id)
        val opaqueArgb = (0xFF000000L or (argb.toLong() and 0xFFFFFFL)).toInt()
        val orig = if (c.hasReplacement) c.originalImage else c.image
        return replace(c.copy(image = null, originalImage = orig, replacementImage = null, replacementColor = opaqueArgb))
    }

    /** Phase 3 (Section 12): Delete Replacement restores original picture and clears both replacementImage and replacementColor. */
    fun deleteReplacement(id: Int): Timeline {
        val c = clip(id)
        val orig = if (c.hasReplacement) c.originalImage else c.image
        return replace(c.copy(image = orig, originalImage = null, replacementImage = null, replacementColor = null))
    }

    fun replaceClipImages(id: Int, image: String?, originalImage: String?, replacementImage: String?): Timeline {
        val c = clip(id)
        return replace(c.copy(image = image, originalImage = originalImage, replacementImage = replacementImage))
    }

    fun editCaption(id: Int, text: String? = null, startMs: Long? = null, endMs: Long? = null): Timeline {
        val c = captions.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No caption with id $id")
        val s = startMs ?: c.startMs; val e = max(endMs ?: c.endMs, s + 1)
        return copy(captions = captions.map { if (it.id == id) it.copy(text = text ?: it.text, startMs = s, endMs = e) else it }.sortedBy { it.startMs })
    }

    // ---- Step 14: duplicate, effects, transitions, text track ----
    /** Copy a picture and put the copy right after it; every later picture moves later by the copy's length (nothing overlaps). */
    fun duplicate(id: Int): Timeline {
        val c = clip(id)
        val moved = clips.map { if (it.id != id && it.startMs >= c.endMs) it.copy(startMs = it.startMs + c.durMs, endMs = it.endMs + c.durMs) else it }
        return withClips(moved + c.copy(id = nextId, startMs = c.endMs, endMs = c.endMs + c.durMs, transition = TransitionSpec()), nextId + 1)
    }
    fun setFx(id: Int, fx: ClipFx) = replace(clip(id).copy(fx = fx.clamped()))
    fun setTransition(id: Int, t: TransitionSpec) = replace(clip(id).copy(transition = t.clamped()))
    fun setCaptionStyle(s: OverlayStyle) = copy(captionStyle = s.clamped())
    fun setMix(m: AudioMix) = copy(mix = AudioMix(m.narration.copy(trimStartMs = 0L).clamped(), m.music?.copy(settings = m.music.settings.clamped()), m.click.clamped()))

    val nextTextId get() = 1 + (texts.maxOfOrNull { it.id } ?: 0)
    private val limitMs get() = max(audioMs, endMs)
    private fun withTexts(l: List<TextClip>) = copy(texts = l.sortedBy { it.startMs })
    fun text(id: Int) = texts.firstOrNull { it.id == id } ?: throw IllegalArgumentException("No text with id $id")

    /** New text clip with id = nextTextId (read it before calling). Times are kept inside the video and at least MIN_CLIP_MS long. */
    fun addText(startMs: Long, endMs: Long, text: String, style: OverlayStyle = OverlayStyle()): Timeline {
        val hi = max(limitMs, MIN_CLIP_MS * 10)
        val s = startMs.coerceIn(0L, max(0L, hi - MIN_CLIP_MS)); val e = endMs.coerceIn(s + MIN_CLIP_MS, max(s + MIN_CLIP_MS, hi))
        return withTexts(texts + TextClip(nextTextId, s, e, text, style.clamped()))
    }
    fun editText(
        id: Int,
        text: String? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        style: OverlayStyle? = null,
        name: String? = null,
        visible: Boolean? = null,
        locked: Boolean? = null,
        zIndex: Int? = null
    ): Timeline {
        val c = text(id)
        val s = (startMs ?: c.startMs).coerceAtLeast(0L)
        val e = max(endMs ?: c.endMs, s + MIN_CLIP_MS)
        return withTexts(texts.map {
            if (it.id == id) it.copy(
                text = text ?: it.text,
                startMs = s,
                endMs = e,
                style = style?.clamped() ?: it.style,
                name = name ?: it.name,
                visible = visible ?: it.visible,
                locked = locked ?: it.locked,
                zIndex = zIndex ?: it.zIndex
            ) else it
        })
    }
    fun moveText(id: Int, newStartMs: Long): Timeline {
        val c = text(id)
        if (c.locked) return this
        val s = newStartMs.coerceIn(0L, max(0L, limitMs - c.durMs))
        return editText(id, startMs = s, endMs = s + c.durMs)
    }
    fun deleteText(id: Int): Timeline { text(id); return withTexts(texts.filter { it.id != id }) }
    /** The copy starts where the original ends (shifted back if it would leave the video). */
    fun duplicateText(id: Int): Timeline { val c = text(id); val s = min(c.endMs, max(0L, limitMs - c.durMs)); return withTexts(texts + c.copy(id = nextTextId, startMs = s, endMs = s + c.durMs)) }
    fun splitText(id: Int, atMs: Long): Timeline {
        val c = text(id); if (atMs < c.startMs + MIN_CLIP_MS || atMs > c.endMs - MIN_CLIP_MS) return this
        return withTexts(texts.flatMap { if (it.id == id) listOf(c.copy(endMs = atMs), c.copy(id = nextTextId, startMs = atMs)) else listOf(it) })
    }
    /** Active visible texts at `ms`, ordered by `zIndex` then `startMs` so preview and export honor layer order identically. */
    fun textsAt(ms: Long) = texts.filter { it.visible && it.startMs <= ms && ms < it.endMs }.sortedWith(compareBy<TextClip> { it.zIndex }.thenBy { it.startMs }.thenBy { it.id })
    fun captionAt(ms: Long) = captions.lastOrNull { it.startMs <= ms && ms < it.endMs }

    /** The clip showing at time t (the last one starting at or before t that has not ended), or null in a gap. */
    fun clipAt(ms: Long) = clips.lastOrNull { it.startMs <= ms && ms < it.endMs }

    /** Everything a person should look at. speechEndMs = end of the last spoken word (null = use audio length). Solid-colour scenes are never flagged as MISSING_IMAGE. */
    fun validate(speechEndMs: Long? = null): List<Issue> {
        val out = ArrayList<Issue>()
        if (clips.isEmpty()) return listOf(Issue(IssueKind.GAP, null, "The timeline has no clips."))
        if (clips.first().startMs > 0) out.add(Issue(IssueKind.FIRST_CLIP_LATE, clips.first().id, "Nothing is shown during the first ${clips.first().startMs} ms."))
        for (c in clips) {
            if (c.image == null && c.replacementColor == null) out.add(Issue(IssueKind.MISSING_IMAGE, c.id, "Scene ${c.sceneNum} has no picture."))
            if (c.durMs < 600) out.add(Issue(IssueKind.TOO_SHORT, c.id, "Scene ${c.sceneNum} lasts only ${c.durMs} ms."))
            if (audioMs > 0 && c.endMs > audioMs + 50) out.add(Issue(IssueKind.CLIP_PAST_AUDIO, c.id, "Scene ${c.sceneNum} runs ${c.endMs - audioMs} ms past the end of the narration."))
        }
        for ((a, b) in clips.zipWithNext()) {
            if (b.startMs - a.endMs >= 1) out.add(Issue(IssueKind.GAP, a.id, "Gap of ${b.startMs - a.endMs} ms after scene ${a.sceneNum}."))
            else if (a.endMs - b.startMs >= 1) out.add(Issue(IssueKind.OVERLAP, b.id, "Scenes ${a.sceneNum} and ${b.sceneNum} overlap by ${a.endMs - b.startMs} ms."))
        }
        if (audioMs > 0) {
            val speechEnd = min(speechEndMs ?: audioMs, audioMs)
            if (endMs < speechEnd - 50) out.add(Issue(IssueKind.SPEECH_PAST_END, clips.last().id, "Narration continues ${speechEnd - endMs} ms after the last picture."))
        }
        return out
    }

    // ---- persistence (Step 7 & Phase 3/7): plain JSON v1, readable with MiniJson, optional additive fields ----
    private fun q(s: String) = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append(String.format("\\u%04x", ch.code))
            else -> append(ch)
        }
        append('"')
    }

    fun toJson(): String = buildString {
        append("{\"version\":1,\"audioMs\":$audioMs,\"nextId\":$nextId,\"clips\":[")
        append(clips.joinToString(",") {
            "{\"id\":${it.id},\"scene\":${it.sceneNum},\"image\":${it.image?.let(::q) ?: "null"},\"start\":${it.startMs},\"end\":${it.endMs},\"text\":${q(it.text)},\"conf\":${q(it.conf)},\"heading\":${it.heading},\"score\":${it.score},\"cutCheck\":${it.cutCheck}" +
                (if (it.fx.isIdentity) "" else ",\"fx\":" + it.fx.toJson()) +
                (if (it.transition.active) ",\"transition\":" + it.transition.toJson() else "") +
                (if (it.originalImage != null) ",\"originalImage\":" + q(it.originalImage) else "") +
                (if (it.replacementImage != null) ",\"replacementImage\":" + q(it.replacementImage) else "") +
                (if (it.replacementColor != null) ",\"replacementColor\":${it.replacementColor}" else "") +
                "}"
        })
        append("],\"captions\":[")
        append(captions.joinToString(",") { "{\"id\":${it.id},\"start\":${it.startMs},\"end\":${it.endMs},\"text\":${q(it.text)}}" })
        append("],\"texts\":[")
        append(texts.joinToString(",") {
            "{\"id\":${it.id},\"start\":${it.startMs},\"end\":${it.endMs},\"text\":${q(it.text)},\"style\":${it.style.toJson()}" +
                (if (it.name.isNotEmpty()) ",\"name\":${q(it.name)}" else "") +
                (if (!it.visible) ",\"visible\":false" else "") +
                (if (it.locked) ",\"locked\":true" else "") +
                (if (it.zIndex != 0) ",\"z\":${it.zIndex}" else "") +
                "}"
        })
        append("],\"captionStyle\":${captionStyle.toJson()}${if (mix.isDefault) "" else ",\"mix\":" + mix.toJson()}}")
    }

    object Json {
        private fun n(m: Map<*, *>, k: String) = (m[k] as? Double)?.let { Math.round(it) } ?: throw PipelineError("Project file: '$k' is missing or not a number.")
        fun parse(text: String): Timeline {
            val root = try { MiniJson(text).parse() as? Map<*, *> } catch (e: Exception) { null } ?: throw PipelineError("The project file is damaged (not valid JSON).")
            if (n(root, "version") != 1L) throw PipelineError("Unsupported project file version ${n(root, "version")}.")
            val clips = (root["clips"] as? List<*> ?: throw PipelineError("Project file has no clips.")).map { c ->
                c as Map<*, *>
                ImageClip(
                    id = n(c, "id").toInt(),
                    sceneNum = n(c, "scene").toInt(),
                    image = c["image"] as? String,
                    startMs = n(c, "start"),
                    endMs = n(c, "end"),
                    text = c["text"] as? String ?: "",
                    conf = c["conf"] as? String ?: "",
                    heading = c["heading"] as? Boolean ?: false,
                    score = (c["score"] as? Double)?.toInt() ?: 100,
                    cutCheck = c["cutCheck"] as? Boolean ?: false,
                    fx = ClipFx.fromMap(c["fx"] as? Map<*, *>),
                    transition = TransitionSpec.fromMap(c["transition"] as? Map<*, *>),
                    originalImage = c["originalImage"] as? String,
                    replacementImage = c["replacementImage"] as? String,
                    replacementColor = (c["replacementColor"] as? Double)?.toLong()?.toInt()
                )
            }
            val caps = (root["captions"] as? List<*> ?: emptyList<Any>()).map { c -> c as Map<*, *>; CaptionClip(n(c, "id").toInt(), n(c, "start"), n(c, "end"), c["text"] as? String ?: "") }
            val texts = (root["texts"] as? List<*> ?: emptyList<Any>()).mapNotNull { c ->
                (c as? Map<*, *>)?.let {
                    TextClip(
                        id = n(it, "id").toInt(),
                        startMs = n(it, "start"),
                        endMs = n(it, "end"),
                        text = it["text"] as? String ?: "",
                        style = OverlayStyle.fromMap(it["style"] as? Map<*, *> ?: emptyMap<String, Any>()),
                        name = it["name"] as? String ?: "",
                        visible = it["visible"] as? Boolean ?: true,
                        locked = it["locked"] as? Boolean ?: false,
                        zIndex = (it["z"] as? Double)?.toInt() ?: 0
                    )
                }
            }
            val cs = (root["captionStyle"] as? Map<*, *>)?.let { OverlayStyle.fromMap(it) } ?: StylePresets.captionDefault
            return Timeline(n(root, "audioMs"), clips.sortedBy { it.startMs }, caps, n(root, "nextId").toInt(), texts.sortedBy { it.startMs }, cs, AudioMix.fromMap(root["mix"] as? Map<*, *>))
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
