package com.auto.app

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.DesignTokens
import com.auto.core.LayerEntry
import com.auto.core.LayerKind
import com.auto.core.LayerTools
import com.auto.core.RenderCaps
import com.auto.core.SafeAlign
import java.util.Locale

/**
 * Phase 7 (Section 10): Design Studio Workspace.
 * - Left rail (or bottom rail on phone portrait): Uploads/Media, Images, Text, Audio, Layers, Position & Align.
 * - Reuses MediaSheet, ReplaceSheet, TextStudioSheet, AudioSheet, Stage, and PreviewOverlays.
 * - Tablet/landscape shows left tool rail, center canvas + timeline, and right contextual properties panel.
 * - Phone portrait opens contextual panels cleanly as sheets or dockable drawers.
 * - Exposes only features that both preview and Exporter honor; Shapes/Lines are labelled Soon with RenderCaps reason;
 *   no sticker library, decorative asset packs, or template marketplace.
 */
private data class StudioTool(val id: String, val icon: String, val label: String)

private val STUDIO_TOOLS = listOf(
    StudioTool("media", "import", "Uploads"),
    StudioTool("images", "media", "Images"),
    StudioTool("text", "text", "Text"),
    StudioTool("audio", "music", "Audio"),
    StudioTool("layers", "scenes", "Layers"),
    StudioTool("align", "effects", "Position"),
)

@Composable
fun DesignStudioWorkspace(
    st: AutoState,
    stageContent: @Composable (Modifier) -> Unit,
    playRowContent: @Composable () -> Unit,
    timelineContent: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val wide = maxWidth > maxHeight || maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                // Left Tool Rail
                Column(
                    Modifier
                        .width(78.dp)
                        .fillMaxHeight()
                        .background(C.Card)
                        .border(1.dp, C.Line)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (tool in STUDIO_TOOLS) {
                        val active = st.designStudioTab == tool.id
                        Column(
                            Modifier
                                .width(68.dp)
                                .heightIn(min = DesignTokens.MIN_TOUCH_DP.dp)
                                .clip(RoundedCornerShape(DesignTokens.RADIUS_S.dp))
                                .background(if (active) C.Violet.copy(alpha = 0.22f) else Color.Transparent)
                                .border(1.dp, if (active) C.Violet else Color.Transparent, RoundedCornerShape(DesignTokens.RADIUS_S.dp))
                                .clickable { st.designStudioTab = tool.id }
                                .semantics { contentDescription = "Design Studio ${tool.label}" }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            AutoIcon(tool.icon, if (active) C.VioletLight else C.Muted, 20.dp)
                            Text(
                                tool.label,
                                color = if (active) C.Text else C.Muted,
                                fontSize = DesignTokens.LABEL.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
                // Center Canvas + PlayRow + Compact Timeline
                Column(Modifier.weight(1.15f).fillMaxHeight()) {
                    stageContent(Modifier.weight(1f))
                    playRowContent()
                    timelineContent()
                }
                // Right Contextual Properties Panel
                Column(
                    Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .background(C.Card)
                        .border(1.dp, C.Line)
                        .padding(12.dp)
                ) {
                    DesignStudioToolPanel(st)
                }
            }
        } else {
            // Phone Portrait: Canvas top, compact timeline middle, tool rail + active panel below
            var showMobilePanel by remember { mutableStateOf(true) }
            Column(Modifier.fillMaxSize()) {
                stageContent(Modifier.weight(if (showMobilePanel) 0.45f else 0.75f))
                playRowContent()
                if (showMobilePanel) {
                    Box(
                        Modifier
                            .weight(0.45f)
                            .fillMaxWidth()
                            .background(C.Card)
                            .border(1.dp, C.Line)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        DesignStudioToolPanel(st)
                    }
                } else {
                    timelineContent()
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(C.Card)
                        .border(1.dp, C.Line)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (tool in STUDIO_TOOLS) {
                        val active = showMobilePanel && st.designStudioTab == tool.id
                        OptionChip(tool.label, active) {
                            if (st.designStudioTab == tool.id && showMobilePanel) {
                                showMobilePanel = false
                            } else {
                                st.designStudioTab = tool.id
                                showMobilePanel = true
                            }
                        }
                    }
                    OptionChip(if (showMobilePanel) "Timeline" else "Tools", !showMobilePanel) {
                        showMobilePanel = !showMobilePanel
                    }
                }
            }
        }
    }
}

@Composable
fun DesignStudioToolPanel(st: AutoState) {
    when (st.designStudioTab) {
        "media" -> MediaSheet(st)
        "images" -> DesignImagesPanel(st)
        "text" -> TextStudioSheet(st)
        "audio" -> AudioSheet(st)
        "layers" -> LayersPanel(st)
        "align" -> PositionAndAlignPanel(st)
        else -> LayersPanel(st)
    }
}

@Composable
private fun DesignImagesPanel(st: AutoState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Project Images & Scene Replacement", color = C.Text, fontSize = DesignTokens.SECTION.sp, fontWeight = FontWeight.Bold)
        Text(
            "Insert pictures or solid-colour screens into the timeline, or replace the selected scene non-destructively.",
            color = C.Muted,
            fontSize = DesignTokens.BODY.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                GlowButton("+ Black Screen", outline = true) {
                    st.insertClipToTimeline(null, atPlayhead = false, solidColor = 0xFF000000.toInt())
                }
            }
            Box(Modifier.weight(1f)) {
                GlowButton("+ White Screen", outline = true) {
                    st.insertClipToTimeline(null, atPlayhead = false, solidColor = 0xFFFFFFFF.toInt())
                }
            }
        }
        if (st.selectedId != null) {
            GlowButton("Replace Selected Scene (Picture / Solid Colour)") { st.openReplace() }
        } else {
            Text("Tip: Tap any scene on the timeline to replace its picture or switch it to a solid colour.", color = C.VioletLight, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        val shapesCap = RenderCaps.get("shapes")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(DesignTokens.RADIUS_M.dp))
                .background(C.Lane)
                .border(1.dp, C.Line, RoundedCornerShape(DesignTokens.RADIUS_M.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shapesCap.label, color = C.Muted, fontSize = DesignTokens.CARD.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Soon", color = C.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(shapesCap.reason, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun LayersPanel(st: AutoState) {
    val tl = st.timeline
    if (tl == null) {
        Text("Open or create a project first to inspect and manage layers.", color = C.Muted, fontSize = DesignTokens.CARD.sp)
        return
    }
    val layers = LayerTools.listLayers(tl, st.captionsOn)
    var renamingId by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Layers (${layers.size})", color = C.Text, fontSize = DesignTokens.SECTION.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OptionChip("+ Text Layer", false) { st.addText() }
        }
        Text(
            "Visibility (Show/Hide) and Z-order (Up/Down) are honored identically in both live preview and exported MP4.",
            color = C.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(layers, key = { "${it.kind.name}_${it.id}" }) { layer ->
                LayerCard(st, layer, onStartRename = { id, cur -> renamingId = id; renameText = cur })
            }
        }
        renamingId?.let { tid ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(DesignTokens.RADIUS_M.dp))
                    .background(C.Lane)
                    .border(1.dp, C.Violet, RoundedCornerShape(DesignTokens.RADIUS_M.dp))
                    .padding(12.dp)
            ) {
                Text("Rename Text Layer #$tid", color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.CARD.sp)
                TextInput(tid, renameText, "Layer name") { renameText = it }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OptionChip("Save Name", true) { st.renameLayer(tid, renameText); renamingId = null }
                    OptionChip("Cancel", false) { renamingId = null }
                }
            }
        }
    }
}

@Composable
private fun LayerCard(st: AutoState, layer: LayerEntry, onStartRename: (Int, String) -> Unit) {
    val isText = layer.kind == LayerKind.TEXT
    val selected = (isText && (layer.id == st.selectedTextId || layer.id in st.selectedTextLayerIds)) ||
        (layer.kind == LayerKind.PICTURE && layer.id == st.selectedId)
    val shape = RoundedCornerShape(DesignTokens.RADIUS_M.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) C.Lane else C.Bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Violet else C.Line, shape)
            .clickable {
                when (layer.kind) {
                    LayerKind.TEXT -> st.toggleLayerSelection(layer.id)
                    LayerKind.PICTURE -> st.selectClip(layer.id)
                    LayerKind.CAPTION -> st.sheet = "captions"
                    LayerKind.AUDIO -> st.sheet = "audio"
                }
            }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                layer.kind.label.uppercase(Locale.ROOT),
                color = C.VioletLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                layer.label,
                color = if (layer.visible) C.Text else C.Muted,
                fontSize = DesignTokens.CARD.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("${fmt(layer.startMs)}–${fmt(layer.endMs)}", color = C.Muted, fontSize = 11.sp)
        }
        if (isText) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                OptionChip(if (layer.visible) "Shown" else "Hidden", layer.visible) { st.toggleLayerVisible(layer.id) }
                OptionChip(if (layer.locked) "Locked" else "Unlocked", layer.locked) { st.toggleLayerLocked(layer.id) }
                OptionChip("Rename", false) { onStartRename(layer.id, layer.label) }
                OptionChip("Bring Forward ↑", false) { st.shiftLayerOrder(layer.id, +1) }
                OptionChip("Send Backward ↓", false) { st.shiftLayerOrder(layer.id, -1) }
            }
        }
    }
}

@Composable
fun PositionAndAlignPanel(st: AutoState) {
    val sel = st.selectedText()
    val multiCount = st.selectedTextLayerIds.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Position, Safe-Area Align & Distribute", color = C.Text, fontSize = DesignTokens.SECTION.sp, fontWeight = FontWeight.Bold)
        ToggleRow("Snap to center & rule-of-thirds guides", st.snappingEnabled) {
            st.snappingEnabled = !st.snappingEnabled
            st.persistExportSettings()
        }
        ToggleRow("Show TV / Shorts safe-area guides", st.showSafeAreaGuides) {
            st.showSafeAreaGuides = !st.showSafeAreaGuides
            st.persistExportSettings()
        }
        if (sel == null) {
            Text(
                "Select a text layer on the canvas, timeline, or Layers panel to adjust its numeric X/Y position, size, rotation, and safe-area alignment.",
                color = C.Muted,
                fontSize = DesignTokens.BODY.sp,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            Sec("SAFE-AREA ALIGNMENT (${sel.displayName})") {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (al in SafeAlign.values()) {
                        OptionChip(al.label, false) { st.alignLayerSafe(sel.id, al) }
                    }
                }
            }
            Sec("NUMERIC TRANSFORM") {
                StudioSlider("Horizontal X", sel.style.x, 0f..1f, { "${(it * 100).toInt()}%" }) {
                    st.setLayerNumericTransform(sel.id, x = it)
                }
                StudioSlider("Vertical Y", sel.style.y, 0f..1f, { "${(it * 100).toInt()}%" }) {
                    st.setLayerNumericTransform(sel.id, y = it)
                }
                StudioSlider("Size (% of frame height)", sel.style.sizeFrac, 0.015f..0.2f, { String.format(Locale.ROOT, "%.1f%%", it * 100f) }) {
                    st.setLayerNumericTransform(sel.id, sizeFrac = it)
                }
                StudioSlider("Rotation", sel.style.rotationDeg, -180f..180f, { "${it.toInt()}°" }) {
                    st.setLayerNumericTransform(sel.id, rotationDeg = it)
                }
            }
        }
        Sec("DISTRIBUTE LAYERS (Requires ≥ 3 selected text layers · Currently $multiCount)") {
            val canDist = LayerTools.canDistribute(st.selectedTextLayerIds)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    GlowButton("Distribute Horizontally", enabled = canDist, outline = true) {
                        st.distributeSelectedLayers(horizontal = true)
                    }
                }
                Box(Modifier.weight(1f)) {
                    GlowButton("Distribute Vertically", enabled = canDist, outline = true) {
                        st.distributeSelectedLayers(horizontal = false)
                    }
                }
            }
            if (!canDist) {
                Text(
                    "Tap 3 or more text layers in the Layers tab to enable even horizontal/vertical distribution.",
                    color = C.Muted,
                    fontSize = 12.sp
                )
            }
        }
    }
}
