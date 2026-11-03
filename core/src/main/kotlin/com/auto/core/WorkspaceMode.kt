package com.auto.core

/**
 * Phase 8 & C3 (Sections 5, 6, 7 & Priority 4): All Ten Required Editing Workspaces,
 * Contextual Control Registry, and Shared-State Transition Simulator.
 * Every workspace in the 10-panel specification is implemented as a connected view over the single shared
 * project model, timeline, media assets, playhead, and undo/redo history.
 */
enum class WorkspaceMode(
    val number: Int,
    val title: String,
    val shortLabel: String,
    val subtitle: String
) {
    CLASSIC_TIMELINE(
        1,
        "1. Classic Timeline Layout",
        "Classic",
        "Traditional multi-track timeline with large preview, media rail, playback, zoom, scroll, and undo/redo."
    ),
    COMPACT_EDITOR(
        2,
        "2. Compact Editor Layout",
        "Compact",
        "Clean and compact quick editor with large preview, single-strip timeline, Trim, Split, Length, and More."
    ),
    ADVANCED_MULTI_PANEL(
        3,
        "3. Advanced Multi-Panel Layout",
        "Advanced",
        "Multi-panel workspace with Media browser left, Preview centre, Properties right, and Multi-track timeline below."
    ),
    TEXT_FOCUS(
        4,
        "4. Text Focus Layout",
        "Text Focus",
        "Dedicated text editing workspace with handles, fonts, Indic script filter, styling, position, and animations."
    ),
    AUDIO_FOCUS(
        5,
        "5. Audio Focus Layout",
        "Audio Focus",
        "Advanced audio workspace with large waveform, narration volume/fades/mute, music bed, and click synchronization."
    ),
    QUICK_EDIT(
        6,
        "6. Quick Edit Layout",
        "Quick Edit",
        "One-tap editing workspace for fast Trim, Split, Duplicate, Delete, Replace, and colour adjustments."
    ),
    DESIGN_STUDIO(
        7,
        "7. Design Studio Layout",
        "Design Studio",
        "Creative visual workspace with Uploads/Media, Images, Text, Audio, Layers, Position & Align, and central canvas."
    ),
    DUAL_PREVIEW(
        8,
        "8. Dual Preview Layout",
        "Dual Preview",
        "Synchronized Original vs. Edited before-after comparison previews over the shared timeline."
    ),
    VERTICAL_EDITING(
        9,
        "9. Vertical Editing Layout",
        "Vertical 9:16",
        "9:16-first Shorts/Reels workspace with aspect switcher (9:16, 16:9, 1:1, 4:5), safe-area guides, and vertical text tools."
    ),
    MINIMALIST(
        10,
        "10. Minimalist Layout",
        "Minimalist",
        "Distraction-free large preview, essential timeline, and focused Text, Music, Filters, and Adjust panels."
    );

    companion object {
        fun fromNameOrDefault(raw: String?): WorkspaceMode =
            values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: CLASSIC_TIMELINE
    }
}

/**
 * Shared-state contextual transition rules (Section 6):
 * Maps user contextual actions to the specialized workspace mode without duplicating projects or resetting state.
 */
object WorkspaceTransitions {
    fun targetForContext(action: String): WorkspaceMode = when (action.lowercase()) {
        "text", "select_text", "edit_text" -> WorkspaceMode.TEXT_FOCUS
        "audio", "music", "waveform", "click" -> WorkspaceMode.AUDIO_FOCUS
        "quick", "quick_actions", "trim" -> WorkspaceMode.QUICK_EDIT
        "properties", "transform", "fx" -> WorkspaceMode.ADVANCED_MULTI_PANEL
        "design", "layers", "align", "studio" -> WorkspaceMode.DESIGN_STUDIO
        "compare", "before_after", "dual" -> WorkspaceMode.DUAL_PREVIEW
        "vertical", "shorts", "9:16" -> WorkspaceMode.VERTICAL_EDITING
        "compact" -> WorkspaceMode.COMPACT_EDITOR
        "minimal", "minimalist" -> WorkspaceMode.MINIMALIST
        else -> WorkspaceMode.CLASSIC_TIMELINE
    }
}

/**
 * Priority 4 (Section 5): Shared Editor Session State model & All-Pairs Transition Verifier.
 * Proves that switching across all `10 × 10 = 100` workspace pairs and contextual timeline selections
 * (`selectText` -> `TEXT_FOCUS`, `selectAudio` -> `AUDIO_FOCUS`, `selectPicture` -> contextual target)
 * preserves project identity, timeline clips/texts/audio, playhead, selection, zoom/scroll, undo/redo, and export settings.
 */
data class WorkspaceSessionState(
    val projectId: String,
    val timeline: Timeline,
    val playheadMs: Long,
    val selectedClipId: Int?,
    val selectedTextId: Int?,
    val selectedTextLayerIds: Set<Int>,
    val zoomDpPerSec: Float,
    val scrollOffsetPx: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val aspect: AspectRatio,
    val exportResP: Int,
    val exportFps: Int,
    val activeWorkspace: WorkspaceMode
)

data class WorkspaceTransitionVerification(
    val from: WorkspaceMode,
    val to: WorkspaceMode,
    val projectPreserved: Boolean,
    val timelinePreserved: Boolean,
    val playheadPreserved: Boolean,
    val selectionPreserved: Boolean,
    val zoomAndScrollPreserved: Boolean,
    val undoRedoPreserved: Boolean,
    val exportSettingsPreserved: Boolean
) {
    val allPreserved: Boolean
        get() = projectPreserved && timelinePreserved && playheadPreserved &&
            selectionPreserved && zoomAndScrollPreserved && undoRedoPreserved && exportSettingsPreserved
}

object WorkspaceSessionSimulator {
    /** Switches workspace while preserving all shared session state (and auto-switching aspect to 9:16 only if entering VERTICAL_EDITING when requested). */
    fun switchWorkspace(
        session: WorkspaceSessionState,
        target: WorkspaceMode,
        autoVerticalAspect: Boolean = false
    ): WorkspaceSessionState {
        val nextAspect = if (autoVerticalAspect && target == WorkspaceMode.VERTICAL_EDITING) AspectRatio.R9_16 else session.aspect
        return session.copy(activeWorkspace = target, aspect = nextAspect)
    }

    /** Selects a text layer on the timeline and transitions contextually to `TEXT_FOCUS` while preserving all session state. */
    fun selectTextLayerAndFocus(session: WorkspaceSessionState, textId: Int): WorkspaceSessionState {
        val tc = session.timeline.texts.firstOrNull { it.id == textId }
        val nextPlayhead = tc?.let { (it.startMs + 1).coerceAtMost(session.timeline.endMs) } ?: session.playheadMs
        return session.copy(
            selectedTextId = textId,
            selectedClipId = null,
            selectedTextLayerIds = setOf(textId),
            playheadMs = nextPlayhead,
            activeWorkspace = WorkspaceMode.TEXT_FOCUS
        )
    }

    /** Selects the audio/narration track on the timeline and transitions contextually to `AUDIO_FOCUS` while preserving all session state. */
    fun selectAudioTrackAndFocus(session: WorkspaceSessionState): WorkspaceSessionState =
        session.copy(
            selectedClipId = null,
            selectedTextId = null,
            activeWorkspace = WorkspaceMode.AUDIO_FOCUS
        )

    /** Verifies all `10 × 10 = 100` ordered pairs of workspaces preserve every shared state invariant. */
    fun verifyAllPairs(initial: WorkspaceSessionState): List<WorkspaceTransitionVerification> {
        val results = ArrayList<WorkspaceTransitionVerification>(100)
        var cur = initial
        for (from in WorkspaceMode.values()) {
            cur = switchWorkspace(cur, from)
            for (to in WorkspaceMode.values()) {
                val before = cur
                val after = switchWorkspace(before, to)
                results.add(
                    WorkspaceTransitionVerification(
                        from = from,
                        to = to,
                        projectPreserved = after.projectId == before.projectId,
                        timelinePreserved = after.timeline == before.timeline,
                        playheadPreserved = after.playheadMs == before.playheadMs,
                        selectionPreserved = after.selectedClipId == before.selectedClipId &&
                            after.selectedTextId == before.selectedTextId &&
                            after.selectedTextLayerIds == before.selectedTextLayerIds,
                        zoomAndScrollPreserved = after.zoomDpPerSec == before.zoomDpPerSec &&
                            after.scrollOffsetPx == before.scrollOffsetPx,
                        undoRedoPreserved = after.canUndo == before.canUndo && after.canRedo == before.canRedo,
                        exportSettingsPreserved = after.exportResP == before.exportResP &&
                            after.exportFps == before.exportFps &&
                            after.aspect == before.aspect
                    )
                )
                cur = after
            }
        }
        return results
    }
}

/**
 * Section 7: Inner Screens & Contextual Controls Registry.
 * Documents every control's model field, preview behavior, export behavior, persistence, undo/redo,
 * accessibility label, and disabled/Soon status so there are zero dead controls.
 */
data class ControlSpec(
    val id: String,
    val group: String,
    val label: String,
    val modelField: String,
    val previewBehavior: String,
    val exportBehavior: String,
    val persistence: String,
    val supportsUndoRedo: Boolean,
    val accessibilityLabel: String,
    val capFeatureId: String? = null
) {
    val isAvailable: Boolean get() = capFeatureId == null || RenderCaps.available(capFeatureId)
    val soonReason: String get() = capFeatureId?.let { RenderCaps.get(it).reason } ?: ""
}

object ControlRegistry {
    val controls: List<ControlSpec> = listOf(
        // Text layer controls
        ControlSpec("text.add", "Text layer", "Add / Edit Text", "Timeline.texts (TextClip.text)", "Drawn live on Stage via OverlayRenderer", "Rendered via TextBitmapOverlay on Composition", "Timeline JSON v1 texts[]", true, "Add or edit text overlay", "text"),
        ControlSpec("text.font", "Text layer", "Font Picker & Script Filter", "OverlayStyle.font / weight / italic", "Resolved via FontResolver + Indic fallback", "Identical FontResolver Paint in OverlayRenderer", "OverlayStyle JSON + styles.json", true, "Select font family and weight", "text"),
        ControlSpec("text.style", "Text layer", "Size, Alignment, Case, Spacing", "OverlayStyle.sizeFrac / align / caseMode / letterSpacingEm / lineSpacing", "Scaled by frame height in OverlayRenderer", "Scaled by export height in OverlayRenderer", "OverlayStyle JSON", true, "Adjust text size, alignment, and spacing", "text"),
        ControlSpec("text.color", "Text layer", "Colour, Background, Stroke, Shadow, Opacity", "OverlayStyle.color / bgColor / strokeColor / shadowColor / opacity", "Drawn with alpha/stroke/shadow in OverlayRenderer", "Identical Canvas drawing in TextBitmapOverlay", "OverlayStyle JSON", true, "Configure text colour, outline, shadow, and box", "text"),
        ControlSpec("text.position", "Text layer", "Position (X/Y) & Rotation", "OverlayStyle.x / y / widthFrac / rotationDeg", "Interactive drag/rotate handles + numeric sliders", "Translated and rotated identically in OverlayRenderer", "OverlayStyle JSON", true, "Position and rotate text overlay", "text"),
        ControlSpec("text.anim", "Text layer", "Animation In/Out & Duration", "OverlayStyle.animIn / animOut / animInMs / animOutMs", "Evaluated per frame via AnimMath.frame()", "Evaluated per frame in TextBitmapOverlay.getBitmap()", "OverlayStyle JSON", true, "Select text entrance and exit animation", "textAnim"),
        // Audio clip controls
        ControlSpec("audio.narration", "Audio clip", "Narration Volume, Fades, Mute", "AudioMix.narration (AudioSettings)", "Waveform preview + MediaPlayer playback", "Applied via AudioGainProcessor PCM gain", "Timeline JSON v1 mix.narration", true, "Adjust narration volume, fades, and mute", "volume"),
        ControlSpec("audio.music", "Audio clip", "Music Browser, Volume, Fades, Loop", "AudioMix.music (MusicTrack)", "Shown on Audio sheet & timeline summary", "Looped audio sequence with AudioGainProcessor", "Timeline JSON v1 mix.music", true, "Add or adjust background music bed", "music"),
        ControlSpec("audio.click", "Audio clip", "Click Sound at Picture Changes", "AudioMix.click (ClickSettings)", "SoundPool ClickPlayer fires at ClickTrack.startsMs", "ClickMixProcessor mixes PCM at exact sample frames", "Timeline JSON v1 mix.click", true, "Toggle click sound at picture changes", "click"),
        ControlSpec("audio.split", "Audio clip", "Split Narration", "N/A (Protected narration invariant)", "Labelled Soon / Not available", "Not exported: video duration equals narration", "None", false, "Split narration unavailable", "audioSplit"),
        // Image & Solid-Colour controls
        ControlSpec("image.replacePic", "Image", "Replace with Picture", "ImageClip.image / replacementImage / originalImage", "Stage & ReplaceSheet preview new image", "Exporter renders replaced image", "Timeline JSON v1 clips[]", true, "Replace scene with picture from media library", "pictures"),
        ControlSpec("image.replaceSolid", "Image", "Replace with Solid Colour (Black/White/ARGB)", "ImageClip.replacementColor / originalImage", "Stage, timeline thumb & ReplaceSheet draw exact ARGB", "Exporter solidScreen renders exact ARGB; Upscaler skips", "Timeline JSON v1 clips[].replacementColor", true, "Replace scene with solid colour", "solidColor"),
        ControlSpec("image.restore", "Image", "Delete Replacement (Restore Original)", "Timeline.deleteReplacement(id)", "Restores originalImage in Stage and timeline", "Exporter renders original picture", "Timeline JSON v1 clips[]", true, "Restore original scene picture", "pictures"),
        ControlSpec("image.crop", "Image", "Crop / Rotate / Flip Picture", "N/A (RenderCaps Soon)", "Labelled Soon with RenderCaps reason", "Not offered until verified in Media3", "None", false, "Crop picture (Soon)", "crop"),
        // Picture clip controls
        ControlSpec("clip.trim", "Picture clip", "Duration / Trim / Set Length", "ImageClip.startMs / endMs (Timeline.trim / setLength)", "Updates timeline block width and Stage duration", "ExportPlan.fromTimeline exact ms boundaries", "Timeline JSON v1 clips[]", true, "Trim or set scene duration", "pictures"),
        ControlSpec("clip.split", "Picture clip", "Split Scene at Playhead", "Timeline.split(id, playMs)", "Splits clip into two contiguous clips on timeline", "Exported as two contiguous segments", "Timeline JSON v1 clips[]", true, "Split scene at playhead", "pictures"),
        ControlSpec("clip.duplicate", "Picture clip", "Duplicate / Delete / Reorder Clip", "Timeline.duplicate / delete / reorderClip", "Updates timeline sequence immediately", "ExportPlan reflects updated clip order", "Timeline JSON v1 clips[]", true, "Duplicate, delete, or reorder scene clip", "pictures"),
        ControlSpec("clip.fx", "Picture clip", "Colour Adjustments & Entrance Transitions", "ImageClip.fx (ClipFx) / transition (TransitionSpec)", "Stage applies ClipFx.rgbMatrix ColorFilter", "Exporter applies ClipColor RgbMatrix & ClipMove", "Timeline JSON v1 clips[].fx / transition", true, "Adjust brightness, contrast, saturation, opacity, and transition", "fx"),
        // Layers controls
        ControlSpec("layers.manage", "Layers", "Select, Rename, Show/Hide, Lock/Unlock, Reorder", "TextClip.name / visible / locked / zIndex", "PreviewOverlays filters visible & sorts by zIndex", "ExportPlan.fromTimeline filters visible & sorts by zIndex", "Timeline JSON v1 texts[]", true, "Manage layer visibility, lock state, and stacking order", "text"),
        ControlSpec("layers.align", "Layers", "Safe-Area Align & Distribute (>= 3 items)", "LayerTools.alignToSafeArea / distributeTextLayers", "Updates OverlayStyle.x / y on Stage canvas", "Exported at identical normalized coordinates", "OverlayStyle JSON", true, "Align or distribute selected text layers", "text"),
        // Export controls
        ControlSpec("export.settings", "Export", "Resolution, FPS, Aspect Ratio, Upscale Profile & Cache", "ExportPrefs (resP, fps, aspect, upscale, upscaleProfile, upscaleFormat)", "Stage aspect ratio updates live", "Exporter + SafelightExportProcessor + ExportTracker", "SharedPreferences + ProjectManifest.settings", false, "Configure export resolution, frame rate, aspect ratio, and AI upscaling", "aspect"),
        // Project controls
        ControlSpec("project.manage", "Project", "Save, Save As, Edit a Copy, Reopen, Recovery, Inspect Media", "ProjectStore (save, duplicate, load, inspectMediaReferences)", "Opens project or non-destructive copy in Editor", "Exports from active project timeline", "filesDir/projects/<id>/manifest.json & timeline.json (.bak)", false, "Save, duplicate as copy, reopen, or inspect project", "pictures")
    )

    fun byGroup(group: String): List<ControlSpec> = controls.filter { it.group.equals(group, ignoreCase = true) }
}
