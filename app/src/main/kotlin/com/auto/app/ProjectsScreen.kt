package com.auto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.DesignTokens
import com.auto.core.MediaLibrary
import com.auto.core.ProjectSummary
import com.auto.core.RelativeTime

/**
 * Step 36 & Phase 6 (Sections 7, 9, 17): Saved projects screen (`filesDir/projects` via core `ProjectStore`).
 * Supports Open, Edit a Copy / Save As (preserving original Auto Create project unmodified), Rename,
 * Inspect Media References, and Delete.
 */
@Composable
fun ProjectsScreen(st: AutoState, shell: Shell) {
    LaunchedEffect(Unit) { st.saveProject { st.refreshProjects() } }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var duplicating by remember { mutableStateOf<ProjectSummary?>(null) }
    var inspecting by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Projects", color = C.Text, fontWeight = FontWeight.Bold, fontSize = DesignTokens.TITLE.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text("Every edit is saved atomically. Opening an Auto Create project via 'Edit a Copy' keeps the original untouched.", color = C.Muted, fontSize = DesignTokens.BODY.sp, modifier = Modifier.padding(bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                GlowButton("＋ Auto Create Project") { st.newProject(); shell.openEditor() }
            }
            Column(Modifier.weight(1f)) {
                GlowButton("＋ Blank Manual Project", outline = true) { st.newBlankProject(); shell.openEditor() }
            }
        }
        if (st.projects.isEmpty()) Text("No saved projects yet. Run Auto Create or start a Blank Manual Project.", color = C.Muted, fontSize = 14.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(st.projects, key = { it.id }) { p ->
                val shape = RoundedCornerShape(DesignTokens.RADIUS_M.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(C.Card)
                        .border(1.dp, if (p.id == st.projectId) C.Violet else C.Line, shape)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ClipThumb(
                            p.thumbnailPath,
                            p.thumbnailColor,
                            Modifier
                                .width(76.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(DesignTokens.RADIUS_S.dp))
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                p.name + if (p.id == st.projectId) "  · open" else "",
                                color = C.Text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${p.projectType.label} · " +
                                    if (p.hasTimeline) "${p.scenes} scenes · ${MediaLibrary.humanDuration(p.durationMs)} · ${RelativeTime.label(p.updatedMs, now)}"
                                    else "No timeline saved · ${RelativeTime.label(p.updatedMs, now)}",
                                color = C.Muted,
                                fontSize = 13.sp
                            )
                            if (p.warnings.isNotEmpty()) Text(p.warnings.first(), color = C.Warning, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                        OptionChip("Open", p.hasTimeline) { st.openProject(p.id, openAsCopy = false) { shell.openEditor() } }
                        if (p.hasTimeline) {
                            OptionChip("Edit a Copy / Save As", false) { duplicating = p }
                        }
                        OptionChip("Rename", false) { renaming = p }
                        OptionChip("Inspect Media", false) { inspecting = p }
                        OptionChip("Delete", false) { deleting = p }
                    }
                }
            }
        }
    }

    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = C.Card,
            titleContentColor = C.Text,
            textContentColor = C.Muted,
            title = { Text("Rename project") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { st.renameProject(p.id, name); renaming = null }) { Text("Save", color = C.VioletLight) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = C.Muted) } }
        )
    }

    duplicating?.let { p ->
        var copyName by remember(p.id) { mutableStateOf("${p.name} (Copy)") }
        AlertDialog(
            onDismissRequest = { duplicating = null },
            containerColor = C.Card,
            titleContentColor = C.Text,
            textContentColor = C.Muted,
            title = { Text("Edit a Copy / Save As") },
            text = {
                Column {
                    Text("Creates an independent Manual Editor copy so the original project '${p.name}' and its scene assignments are never modified.", modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(copyName, { copyName = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetId = p.id
                    val targetName = copyName
                    duplicating = null
                    st.editProjectCopy(targetId, targetName) { shell.openEditor() }
                }) { Text("Create & Open Copy", color = C.VioletLight) }
            },
            dismissButton = { TextButton(onClick = { duplicating = null }) { Text("Cancel", color = C.Muted) } }
        )
    }

    inspecting?.let { p ->
        val refs = remember(p.id) { st.inspectProjectMedia(p.id) }
        AlertDialog(
            onDismissRequest = { inspecting = null },
            containerColor = C.Card,
            titleContentColor = C.Text,
            textContentColor = C.Muted,
            title = { Text("Media References — ${p.name}") },
            text = {
                Column {
                    if (refs.isEmpty()) {
                        Text("No file references recorded (blank or solid-colour project).")
                    } else {
                        val okCount = refs.values.count { it }
                        Text("$okCount of ${refs.size} referenced media files present on disk:", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        for ((path, exists) in refs.entries.take(12)) {
                            Text(
                                "${if (exists) "✓" else "✗"} ${path.substringAfterLast('/')}",
                                color = if (exists) C.Success else C.Error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { inspecting = null }) { Text("Close", color = C.VioletLight) } }
        )
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = C.Card,
            titleContentColor = C.Text,
            textContentColor = C.Muted,
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("The saved project and its copied project-local files are removed. Original user media and exported videos are never touched.") },
            confirmButton = { TextButton(onClick = { st.deleteProject(p.id); deleting = null }) { Text("Delete", color = C.Error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = C.Muted) } }
        )
    }
}
