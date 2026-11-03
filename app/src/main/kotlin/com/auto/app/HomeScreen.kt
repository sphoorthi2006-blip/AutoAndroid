package com.auto.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.DesignTokens
import com.auto.core.MediaLibrary
import com.auto.core.ProjectType
import com.auto.core.RelativeTime
import com.auto.core.WorkspaceMode

/**
 * Section 5: Home Dashboard & Navigation.
 * - Auto Create is the largest, most prominent primary Home action.
 * - Manual Editor and Design Studio are secondary actions (no ten competing primary buttons on Home).
 * - Recent projects show thumbnail (or solid colour), name, ProjectType badge, last edited time, and Open / Edit a Copy.
 * - Active, queued, interrupted, completed, and failed export jobs are accessible directly from Home.
 * - Storage indicator warns on Home ONLY when device storage is genuinely low (< 2 GB or < 10% free).
 * - Compact top bar for app identity, current project status, export progress access, and Settings.
 */
private class Shortcut(val glyph: String, val title: String, val ready: Boolean, val onClick: () -> Unit)

@Composable
fun HomeScreen(st: AutoState, shell: Shell) {
    val (freeBytes, _, isStorageLow) = remember(st.status, st.projects) { st.checkDeviceStorage() }
    val shortcuts = listOf(
        Shortcut("analyse", "Auto Create", true) {
            st.switchWorkspace(WorkspaceMode.CLASSIC_TIMELINE)
            st.sheet = "inputs"
            shell.openEditor()
        },
        Shortcut("editor", "Manual Editor", true) {
            st.switchWorkspace(WorkspaceMode.CLASSIC_TIMELINE)
            if (!st.hasProject) st.newBlankProject()
            shell.openEditor()
        },
        Shortcut("effects", "Design Studio", true) {
            st.switchWorkspace(WorkspaceMode.DESIGN_STUDIO)
            if (!st.hasProject) st.newBlankProject("Design Project")
            shell.openEditor()
        },
        Shortcut("text", "Text Studio", true) {
            if (st.hasProject) {
                st.switchWorkspace(WorkspaceMode.TEXT_FOCUS)
                shell.openEditor()
            } else shell.message("Text Studio", "Start Auto Create or a Blank Manual Project first.")
        },
        Shortcut("captions", "Captions", true) {
            if (st.hasProject) { st.sheet = "captions"; shell.openEditor() }
            else shell.message("Captions", "Start Auto Create or a Blank Manual Project first.")
        },
        Shortcut("media", "Media Library", true) {
            st.sheet = if (st.hasProject) "pictures" else "inputs"
            shell.openEditor()
        },
        Shortcut("music", "Audio Focus", true) {
            if (st.hasProject) {
                st.switchWorkspace(WorkspaceMode.AUDIO_FOCUS)
                shell.openEditor()
            } else shell.message("Audio Focus", "Start Auto Create or a Blank Manual Project first.")
        },
        Shortcut("export", "Export", true) {
            st.sheet = if (st.hasProject) "export" else "inputs"
            shell.openEditor()
        },
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        // Compact top bar for app identity, current project, progress access, and Settings
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.manva_logo),
                contentDescription = "App logo",
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("MANVA · AUTOANDROID", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    if (st.hasProject) "${st.projectName.ifBlank { "Active Project" }} · ${st.timeline?.clips?.size ?: 0} scenes"
                    else "Offline Deterministic Video Studio",
                    color = C.Muted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (st.exportPct != null) {
                OptionChip("Export ${st.exportPct}%", true) {
                    st.sheet = "export"
                    shell.openEditor()
                }
            }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { shell.openSettings() }
                    .semantics { contentDescription = "Open Settings" },
                contentAlignment = Alignment.Center
            ) {
                AutoIcon("settings", C.Muted, 24.dp)
            }
        }

        // Low-storage banner (shown ONLY when genuinely low)
        if (isStorageLow) {
            val warnShape = RoundedCornerShape(DesignTokens.RADIUS_M.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(warnShape)
                    .background(C.Lane)
                    .border(1.dp, C.Warning, warnShape)
                    .clickable { shell.openSettings() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AutoIcon("warning", C.Warning, 20.dp)
                Text(
                    "Low device storage: only ${MediaLibrary.humanSize(freeBytes)} free. Tap to clear temporary or upscale cache in Settings.",
                    color = C.Warning,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        // Primary Hero Card: Auto Create (largest, most prominent action) + Secondary Manual Editor & Design Studio
        val cardShape = RoundedCornerShape(DesignTokens.RADIUS_L.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(C.Card)
                .border(1.dp, C.Line, cardShape)
                .padding(16.dp)
        ) {
            Text("Auto Create Studio", color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.TITLE.sp)
            Text(
                "Convert script/details, captions, narration audio, and pictures into a finished MP4 with deterministic scene matching, narration sync, and click placement.",
                color = C.Muted,
                fontSize = DesignTokens.BODY.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            // Primary prominent action: Auto Create
            HomeButton("✦  Auto Create Video (Primary)", primary = true, enabled = true, prominent = true) {
                st.switchWorkspace(WorkspaceMode.CLASSIC_TIMELINE)
                st.sheet = "inputs"
                shell.openEditor()
            }
            Spacer(Modifier.height(10.dp))
            // Secondary actions: Manual Editor & Design Studio
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    HomeButton("✂  Manual Editor", primary = false, enabled = true) {
                        st.switchWorkspace(WorkspaceMode.CLASSIC_TIMELINE)
                        if (!st.hasProject) st.newBlankProject("Manual Project")
                        else st.sheet = null
                        shell.openEditor()
                    }
                }
                Box(Modifier.weight(1f)) {
                    HomeButton("▣  Design Studio", primary = false, enabled = true) {
                        st.switchWorkspace(WorkspaceMode.DESIGN_STUDIO)
                        if (!st.hasProject) st.newBlankProject("Design Studio Project")
                        else st.sheet = null
                        shell.openEditor()
                    }
                }
            }
            if (st.hasProject) {
                Spacer(Modifier.height(8.dp))
                HomeButton("Resume Open Project (${st.timeline?.clips?.size ?: 0} scenes)", primary = false, enabled = true) {
                    st.sheet = null
                    shell.openEditor()
                }
            }
        }

        // Active / Interrupted / Completed / Failed Export Activity accessible from Home
        if (st.exportJobs.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Export Activity", color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.SECTION.sp, modifier = Modifier.padding(bottom = 8.dp))
            for (job in st.exportJobs.take(3)) {
                ExportJobRow(st, job, onNavigateToEditor = { shell.openEditor() })
            }
        }

        // Recent projects with thumbnail, name, projectType badge, last edited, Resume / Open, and Edit a Copy
        if (st.projects.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Recent Projects", color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.SECTION.sp, modifier = Modifier.padding(bottom = 8.dp))
            val now = System.currentTimeMillis()
            for (p in st.projects.take(5)) {
                val pShape = RoundedCornerShape(DesignTokens.RADIUS_M.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(pShape)
                        .background(C.Card)
                        .border(1.dp, C.Line, pShape)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClipThumb(
                            p.thumbnailPath,
                            p.thumbnailColor,
                            Modifier
                                .width(72.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(DesignTokens.RADIUS_S.dp))
                                .border(1.dp, C.Line, RoundedCornerShape(DesignTokens.RADIUS_S.dp))
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.name, color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.CARD.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    p.projectType.label,
                                    color = C.VioletLight,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(C.Violet.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                "${p.scenes} scenes · ${MediaLibrary.humanDuration(p.durationMs)} · Edited ${RelativeTime.label(p.updatedMs, now)}",
                                color = C.Muted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        OptionChip("Resume / Open", p.hasTimeline) {
                            if (p.hasTimeline) st.openProject(p.id, openAsCopy = false) { shell.openEditor() }
                        }
                        if (p.hasTimeline && p.projectType == ProjectType.AUTO_CREATE) {
                            OptionChip("Edit a Copy (Protect Original)", false) {
                                st.openProject(p.id, openAsCopy = true) { shell.openEditor() }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Quick Studio Tools", color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.SECTION.sp, modifier = Modifier.padding(bottom = 10.dp))
        for (row in shortcuts.chunked(4)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sc in row) Box(Modifier.weight(1f)) { Tile(sc) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tile(sc: Shortcut) {
    val shape = RoundedCornerShape(DesignTokens.RADIUS_M.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 92.dp)
            .clip(shape)
            .background(C.Card)
            .border(1.dp, C.Line, shape)
            .clickable(onClick = sc.onClick)
            .semantics { contentDescription = sc.title }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (sc.ready) C.Violet.copy(alpha = 0.22f) else C.Lane),
            contentAlignment = Alignment.Center
        ) {
            AutoIcon(sc.glyph, if (sc.ready) C.VioletLight else C.Muted, 22.dp)
        }
        Text(sc.title, color = if (sc.ready) C.Text else C.Muted, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun HomeButton(text: String, primary: Boolean, enabled: Boolean, prominent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (primary) Modifier.background(if (enabled) C.Glow else SolidColor(C.Lane))
                else Modifier.background(C.Lane).border(1.dp, C.Line, shape)
            )
            .heightIn(min = if (prominent) 56.dp else 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = text }
            .padding(vertical = if (prominent) 15.dp else 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color.White else C.Muted,
            fontWeight = FontWeight.Bold,
            fontSize = if (prominent) 16.sp else 14.sp
        )
    }
}
