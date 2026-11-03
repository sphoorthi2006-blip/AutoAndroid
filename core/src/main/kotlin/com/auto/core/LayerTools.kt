package com.auto.core

import kotlin.math.max
import kotlin.math.min

/**
 * Phase 7 (Section 10): Design Studio — Layer management (select, rename, show/hide, lock/unlock, reorder)
 * and Position & Align tools (numeric x/y/size/rotation, safe-area alignment, snapping, and >= 3 item distribution).
 * Every operation returns an immutable Timeline snapshot so undo/redo works automatically and preview + export match.
 */
enum class LayerKind(val label: String) {
    TEXT("Text Overlay"),
    CAPTION("Caption Track"),
    PICTURE("Picture Scene"),
    AUDIO("Audio / Music")
}

data class LayerEntry(
    val kind: LayerKind,
    val id: Int,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val zIndex: Int = 0
)

enum class SafeAlign(val label: String) {
    LEFT("Align Left"),
    CENTER_X("Center Horizontally"),
    RIGHT("Align Right"),
    TOP("Align Top"),
    CENTER_Y("Center Vertically"),
    BOTTOM("Align Bottom")
}

object LayerTools {
    /** Lists all layers in top-to-bottom visual stacking order (topmost text overlays first, then captions, pictures, audio). */
    fun listLayers(t: Timeline, captionsVisible: Boolean = true): List<LayerEntry> = buildList {
        // Text layers ordered from highest zIndex (topmost) to lowest
        for (tc in t.texts.sortedWith(compareByDescending<TextClip> { it.zIndex }.thenByDescending { it.startMs }.thenByDescending { it.id })) {
            add(LayerEntry(LayerKind.TEXT, tc.id, tc.displayName, tc.startMs, tc.endMs, tc.visible, tc.locked, tc.zIndex))
        }
        if (t.captions.isNotEmpty()) {
            add(LayerEntry(LayerKind.CAPTION, 0, "Captions (${t.captions.size} cues)", 0L, t.endMs, visible = captionsVisible, locked = false, zIndex = -10))
        }
        for (c in t.clips) {
            val kindLabel = when {
                c.replacementColor != null -> "Scene ${c.sceneNum} (Solid ${ColorText.hex(c.replacementColor)})"
                c.image != null -> "Scene ${c.sceneNum} (${c.image.substringAfterLast('/')})"
                else -> "Scene ${c.sceneNum} (Text Screen)"
            }
            add(LayerEntry(LayerKind.PICTURE, c.id, kindLabel, c.startMs, c.endMs, visible = true, locked = false, zIndex = -100))
        }
        if (t.audioMs > 0L) {
            add(LayerEntry(LayerKind.AUDIO, -1, "Narration Audio", 0L, t.audioMs, visible = !t.mix.narration.muted, locked = true, zIndex = -200))
        }
        t.mix.music?.let { m ->
            add(LayerEntry(LayerKind.AUDIO, -2, "Music: ${m.name}", 0L, max(t.audioMs, t.endMs), visible = !m.settings.muted, locked = false, zIndex = -201))
        }
    }

    fun renameTextLayer(t: Timeline, textId: Int, newName: String): Timeline =
        t.editText(textId, name = newName.trim().take(40))

    fun toggleTextVisibility(t: Timeline, textId: Int): Timeline {
        val cur = t.text(textId)
        return t.editText(textId, visible = !cur.visible)
    }

    fun toggleTextLock(t: Timeline, textId: Int): Timeline {
        val cur = t.text(textId)
        return t.editText(textId, locked = !cur.locked)
    }

    /** Moves a text layer up (`delta > 0`, drawn in front) or down (`delta < 0`, drawn behind) in z-order. */
    fun shiftTextZIndex(t: Timeline, textId: Int, delta: Int): Timeline {
        val cur = t.text(textId)
        if (cur.locked) return t
        return t.editText(textId, zIndex = (cur.zIndex + delta).coerceIn(-50, 50))
    }

    /** Sets exact numeric x (0..1), y (0..1), sizeFrac (0.015..0.2), and rotationDeg (-180..180) on an unlocked text layer. */
    fun setNumericTransform(
        t: Timeline,
        textId: Int,
        x: Float? = null,
        y: Float? = null,
        sizeFrac: Float? = null,
        rotationDeg: Float? = null
    ): Timeline {
        val cur = t.text(textId)
        if (cur.locked) return t
        val st = cur.style
        val updated = st.copy(
            x = x ?: st.x,
            y = y ?: st.y,
            sizeFrac = sizeFrac ?: st.sizeFrac,
            rotationDeg = rotationDeg ?: st.rotationDeg
        ).clamped()
        return t.editText(textId, style = updated)
    }

    /** Aligns an unlocked text layer to the safe-area edges or frame center. */
    fun alignToSafeArea(t: Timeline, textId: Int, align: SafeAlign, safeMargin: Float = Guides.SAFE_TITLE): Timeline {
        val cur = t.text(textId)
        if (cur.locked) return t
        val st = cur.style
        val m = safeMargin.coerceIn(0.02f, 0.2f)
        val newStyle = when (align) {
            SafeAlign.LEFT -> st.copy(x = m, align = TextAlign.LEFT)
            SafeAlign.CENTER_X -> st.copy(x = 0.5f, align = TextAlign.CENTER)
            SafeAlign.RIGHT -> st.copy(x = 1f - m, align = TextAlign.RIGHT)
            SafeAlign.TOP -> st.copy(y = m + st.sizeFrac * 0.6f)
            SafeAlign.CENTER_Y -> st.copy(y = 0.5f)
            SafeAlign.BOTTOM -> st.copy(y = 1f - m - st.sizeFrac * 0.6f)
        }.clamped()
        return t.editText(textId, style = newStyle)
    }

    /** Section 10: Distribute is enabled ONLY when >= 3 items are selected. */
    fun canDistribute(selectedIds: Collection<Int>): Boolean = selectedIds.size >= 3

    /** Distributes >= 3 selected unlocked text clips evenly along X (`horizontal = true`) or Y (`horizontal = false`). */
    fun distributeTextLayers(t: Timeline, selectedIds: Set<Int>, horizontal: Boolean): Timeline {
        val targets = t.texts.filter { it.id in selectedIds && !it.locked }
        if (targets.size < 3) return t
        val sorted = if (horizontal) targets.sortedBy { it.style.x } else targets.sortedBy { it.style.y }
        val minCoord = if (horizontal) sorted.first().style.x else sorted.first().style.y
        val maxCoord = if (horizontal) sorted.last().style.x else sorted.last().style.y
        val span = max(0.06f, maxCoord - minCoord)
        val step = span / (sorted.size - 1)
        var out = t
        sorted.forEachIndexed { idx, clip ->
            val v = (minCoord + idx * step).coerceIn(0.05f, 0.95f)
            val newStyle = if (horizontal) clip.style.copy(x = v) else clip.style.copy(y = v)
            out = out.editText(clip.id, style = newStyle.clamped())
        }
        return out
    }
}
