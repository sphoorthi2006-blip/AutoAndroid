package com.auto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.MediaLibrary
import com.auto.core.ProjectSummary
import com.auto.core.RelativeTime

/** Step 36: saved projects (filesDir/projects via core ProjectStore). Open, rename, delete; the open project is autosaved after every edit. */
@Composable
fun ProjectsScreen(st: AutoState, shell: Shell) {
    LaunchedEffect(Unit) { st.saveProject { st.refreshProjects() } }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().background(C.Bg).statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Projects", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        Text("Every edit is saved automatically.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        GlowButton("＋  New project") { st.newProject(); shell.openEditor() }
        if (st.projects.isEmpty()) Text("No saved projects yet. Press Analyse in a new project and it appears here.", color = C.Muted, fontSize = 14.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(st.projects, key = { it.id }) { p ->
                val shape = RoundedCornerShape(18.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, if (p.id == st.projectId) C.Cyan else C.Line, shape).padding(14.dp)) {
                    Text(p.name + if (p.id == st.projectId) "  · open" else "", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(if (p.hasTimeline) "${p.scenes} scenes · ${MediaLibrary.humanDuration(p.durationMs)} · ${RelativeTime.label(p.updatedMs, now)}" else "No timeline saved · ${RelativeTime.label(p.updatedMs, now)}", color = C.Muted, fontSize = 13.sp)
                    if (p.warnings.isNotEmpty()) Text(p.warnings.first(), color = C.Pink, fontSize = 12.sp)
                    Row(Modifier.padding(top = 8.dp)) {
                        OptionChip("Open", p.hasTimeline) { st.openProject(p.id) { shell.openEditor() } }
                        OptionChip("Rename", false) { renaming = p }
                        OptionChip("Delete", false) { deleting = p }
                    }
                }
            }
        }
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(onDismissRequest = { renaming = null }, containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Muted,
            title = { Text("Rename project") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { st.renameProject(p.id, name); renaming = null }) { Text("Save", color = C.Cyan) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = C.Muted) } })
    }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Muted,
            title = { Text("Delete \"${p.name}\"?") }, text = { Text("The saved project and its copied files are removed from this phone. Exported videos are not touched.") },
            confirmButton = { TextButton(onClick = { st.deleteProject(p.id); deleting = null }) { Text("Delete", color = C.Pink) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = C.Muted) } })
    }
}
