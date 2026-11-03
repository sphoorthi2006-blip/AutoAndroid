package com.auto.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.AspectRatio
import com.auto.core.DesignTokens
import com.auto.core.ImageClip
import com.auto.core.RenderCaps
import com.auto.core.WorkspaceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 8 (Section 6): All Ten Required Editing Workspaces connected over the single shared AutoState.
 * Switching workspace modes changes the interface layout while preserving project data, timeline,
 * playhead, selection, zoom/scroll, undo/redo, autosave, and export configuration.
 */
@Composable
fun WorkspaceSwitcherStrip(st: AutoState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.Card)
            .border(1.dp, C.Line)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (mode in WorkspaceMode.values()) {
            val active = st.workspaceMode == mode
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(shape)
                    .background(if (active) C.Violet.copy(alpha = 0.25f) else Color.Transparent)
                    .border(1.dp, if (active) C.Violet else C.Line, shape)
                    .clickable { st.switchWorkspace(mode) }
                    .semantics { contentDescription = "Switch to ${mode.title}" }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${mode.number}. ${mode.shortLabel}",
                    color = if (active) C.Text else C.Muted,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Workspace 2: Compact Editor Layout — Large preview, compact timeline, Trim, Split, Speed (Soon), Crop (Soon), and More.
 */
@Composable
fun CompactEditorWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        stageContent(Modifier.weight(1f))
        playRowContent()
        Box(Modifier.fillMaxWidth().height(132.dp)) { timelineContent() }
        Row(
            Modifier
                .fillMaxWidth()
                .background(C.Card)
                .navigationBarsPadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OptionChip("Trim / Length", st.sheet == "length") {
                if (st.selectedId == null) st.say("Select a scene on the timeline first.") else st.sheet = "length"
            }
            OptionChip("Split", false) {
                if (st.selectedId == null) st.say("Select a scene on the timeline first.") else st.split()
            }
            OptionChip("Replace", st.sheet == "replace") {
                if (st.selectedId == null) st.say("Select a scene on the timeline first.") else st.openReplace()
            }
            OptionChip("Speed (Soon)", false) { st.say(RenderCaps.get("speed").reason) }
            OptionChip("Crop (Soon)", false) { st.say(RenderCaps.get("crop").reason) }
            OptionChip("More Tools", false) { st.switchWorkspace(WorkspaceMode.CLASSIC_TIMELINE) }
        }
    }
}

/**
 * Workspace 3: Advanced Multi-Panel Layout — Media browser left, preview centre, selected-item properties right, multi-track timeline below.
 */
@Composable
fun AdvancedMultiPanelWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val wide = maxWidth > maxHeight || maxWidth >= 600.dp
        if (wide) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // Left: Media Browser
                    Column(
                        Modifier
                            .weight(0.9f)
                            .fillMaxHeight()
                            .background(C.Card)
                            .border(1.dp, C.Line)
                            .padding(8.dp)
                    ) {
                        Text("Media Browser", color = C.VioletLight, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                        MediaSheet(st)
                    }
                    // Centre: Preview + PlayRow
                    Column(Modifier.weight(1.2f).fillMaxHeight()) {
                        stageContent(Modifier.weight(1f))
                        playRowContent()
                    }
                    // Right: Selected-item properties (exposes only supported transforms)
                    Column(
                        Modifier
                            .weight(0.9f)
                            .fillMaxHeight()
                            .background(C.Card)
                            .border(1.dp, C.Line)
                            .padding(8.dp)
                    ) {
                        Text("Properties", color = C.VioletLight, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                        if (st.selectedTextId != null) {
                            PositionAndAlignPanel(st)
                        } else {
                            EffectsSheet(st)
                        }
                    }
                }
                timelineContent()
            }
        } else {
            // Phone Portrait: Preview + Properties & Media tabs + Timeline below
            Column(Modifier.fillMaxSize()) {
                stageContent(Modifier.weight(0.42f))
                playRowContent()
                Box(
                    Modifier
                        .weight(0.35f)
                        .fillMaxWidth()
                        .background(C.Card)
                        .border(1.dp, C.Line)
                        .padding(8.dp)
                ) {
                    if (st.selectedTextId != null) PositionAndAlignPanel(st) else EffectsSheet(st)
                }
                timelineContent()
            }
        }
    }
}

/**
 * Workspace 4: Text Focus Layout — Dedicated text preview, selection handles, font/style/Indic script/animation & duration controls.
 */
@Composable
fun TextFocusWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        stageContent(Modifier.weight(0.45f))
        playRowContent()
        Box(
            Modifier
                .weight(0.55f)
                .fillMaxWidth()
                .background(C.Card)
                .border(1.dp, C.Line)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            TextStudioSheet(st)
        }
    }
}

/**
 * Workspace 5: Audio Focus Layout — Waveform, audio timeline, volume, fades, mute, music, click sound & synchronization.
 */
@Composable
fun AudioFocusWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        stageContent(Modifier.weight(0.32f))
        playRowContent()
        timelineContent()
        Box(
            Modifier
                .weight(0.45f)
                .fillMaxWidth()
                .background(C.Card)
                .border(1.dp, C.Line)
                .padding(vertical = 8.dp)
        ) {
            AudioSheet(st)
        }
    }
}

/**
 * Workspace 6: Quick Edit Layout — Fast one-tap Trim, Split, Duplicate, Delete, Replace, and quick adjustments; unsupported ops labelled Soon.
 */
@Composable
fun QuickEditWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        stageContent(Modifier.weight(1f))
        playRowContent()
        timelineContent()
        Column(
            Modifier
                .fillMaxWidth()
                .background(C.Card)
                .border(1.dp, C.Line)
                .navigationBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("One-Tap Quick Edit Tools", color = C.Text, fontSize = DesignTokens.CARD.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip("−1s Trim", false) { st.addLength(-1000) }
                OptionChip("+1s Trim", false) { st.addLength(1000) }
                OptionChip("Exact Length", false) { st.sheet = "length" }
                OptionChip("Split", false) { st.split() }
                OptionChip("Duplicate", false) { st.duplicateClip() }
                OptionChip("Move Left ←", false) { st.moveClipOrder(-1) }
                OptionChip("Move Right →", false) { st.moveClipOrder(+1) }
                OptionChip("Replace / Solid", false) { st.openReplace() }
                OptionChip("Delete", false) { st.delete() }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip("Colour & Transitions", false) { st.sheet = "effects" }
                OptionChip("Speed (Soon)", false) { st.say(RenderCaps.get("speed").reason) }
                OptionChip("Crop (Soon)", false) { st.say(RenderCaps.get("crop").reason) }
                OptionChip("Rotate (Soon)", false) { st.say(RenderCaps.get("rotate").reason) }
                OptionChip("Flip (Soon)", false) { st.say(RenderCaps.get("flip").reason) }
            }
        }
    }
}

/**
 * Workspace 8: Dual Preview Layout — Synchronized Original vs. Edited previews with before-after comparison and shared timeline.
 */
@Composable
fun DualPreviewWorkspace(
    st: AutoState,
    editedStageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    val tl = st.timeline
    val clip: ImageClip? = tl?.clipAt(st.playMs)
    val origPath = clip?.let { it.originalImage ?: it.image }
    val origBmp by produceState<ImageBitmap?>(null, origPath) {
        value = origPath?.let { p -> withContext(Dispatchers.IO) { Pics.load(p, 900) } }
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Original Unedited Scene Preview
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Original", color = C.Muted, fontSize = DesignTokens.BODY.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                val shape = RoundedCornerShape(DesignTokens.RADIUS_M.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .aspectRatio(st.aspect.ratio, matchHeightConstraintsFirst = false)
                        .clip(shape)
                        .background(C.Screen)
                        .border(1.dp, C.Line, shape),
                    contentAlignment = Alignment.Center
                ) {
                    val b = origBmp
                    if (tl == null) {
                        Text("No project", color = C.Muted, fontSize = 13.sp)
                    } else if (b != null) {
                        Image(b, contentDescription = "Original scene preview", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else if (clip != null) {
                        Text(clip.text.ifBlank { "Original text screen" }, color = C.Text, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                    }
                }
            }
            // Right: Edited Preview (includes replacement image/solid colour, ClipFx, and text/caption overlays)
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Edited", color = C.VioletLight, fontSize = DesignTokens.BODY.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                editedStageContent(Modifier.weight(1f))
            }
        }
        playRowContent()
        timelineContent()
    }
}

/**
 * Workspace 9: Vertical Editing Layout — 9:16-first workspace for Shorts; optional 16:9, 1:1, 4:5; safe-area guides, vertical preview/timeline and text positioning.
 */
@Composable
fun VerticalEditingWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                stageContent(Modifier.fillMaxSize())
            }
            // Right-side aspect ratio pills & Shorts safe-area toggles (matches Panel 9 in specification)
            Column(
                Modifier
                    .padding(start = 8.dp)
                    .width(88.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                for (a in listOf(AspectRatio.R9_16, AspectRatio.R16_9, AspectRatio.R1_1, AspectRatio.R4_5)) {
                    OptionChip(a.label, st.aspect == a) {
                        st.aspect = a
                        st.persistExportSettings()
                    }
                }
                OptionChip(if (st.showSafeAreaGuides) "Safe ON" else "Safe Area", st.showSafeAreaGuides) {
                    st.showSafeAreaGuides = !st.showSafeAreaGuides
                    st.persistExportSettings()
                }
                OptionChip("+ Text", false) { st.addText(); st.sheet = "text" }
            }
        }
        playRowContent()
        timelineContent()
    }
}

/**
 * Workspace 10: Minimalist Layout — Distraction-free large preview, essential timeline, Text, Music, Filters, and Adjust; advanced tools in secondary panels.
 */
@Composable
fun MinimalistWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        stageContent(Modifier.weight(1f))
        playRowContent()
        Box(Modifier.fillMaxWidth().height(120.dp)) { timelineContent() }
        Row(
            Modifier
                .fillMaxWidth()
                .background(C.Card)
                .border(1.dp, C.Line)
                .navigationBarsPadding()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MinimalTrigger("text", "Text", st.sheet == "text") { st.sheet = "text" }
            MinimalTrigger("music", "Music", st.sheet == "audio") { st.sheet = "audio" }
            MinimalTrigger("effects", "Filters", st.sheet == "effects") { st.sheet = "effects" }
            MinimalTrigger("replace", "Adjust", st.sheet == "replace") {
                if (st.selectedId != null) st.openReplace() else st.sheet = "effects"
            }
        }
    }
}

@Composable
private fun MinimalTrigger(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .widthIn(min = 68.dp)
            .heightIn(min = DesignTokens.MIN_TOUCH_DP.dp)
            .clip(RoundedCornerShape(DesignTokens.RADIUS_M.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(if (active) C.Glow else androidx.compose.ui.graphics.SolidColor(C.Lane), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AutoIcon(icon, if (active) Color.White else C.Text, 20.dp)
        }
        Text(label, color = if (active) C.VioletLight else C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}
