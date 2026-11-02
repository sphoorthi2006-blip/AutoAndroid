package com.auto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One shortcut tile. ready = false: drawn dimmer, tagged "Soon", and tapping it says so (never a silent no-op). */
private class Shortcut(val glyph: String, val title: String, val ready: Boolean, val onClick: () -> Unit)

@Composable
fun HomeScreen(st: AutoState, shell: Shell) {
    val shortcuts = listOf(
        Shortcut("＋", "New Project", true) { st.newProject(); shell.openEditor() },
        Shortcut("◎", "Auto Analyse", true) { st.sheet = "inputs"; shell.openEditor() },
        Shortcut("Aa", "Text Studio", true) { if (st.hasProject) { st.sheet = "text"; shell.openEditor() } else shell.message("Text Studio", "Add your files and press Analyse first. Text Studio edits the text of a project.") },
        Shortcut("▭", "Captions", true) { if (st.hasProject) { st.sheet = "captions"; shell.openEditor() } else shell.message("Captions", "Add your files and press Analyse first. Captions belong to a project.") },
        Shortcut("▣", "Media Library", true) { st.sheet = if (st.hasProject) "pictures" else "inputs"; shell.openEditor() },
        Shortcut("♪", "Music & Audio", false) { shell.soon("Music & Audio studio") },
        Shortcut("✦", "Effects", false) { shell.soon("Effects and transitions") },
        Shortcut("⬆", "Export", true) { st.sheet = if (st.hasProject) "export" else "inputs"; shell.openEditor() },
    )
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(C.Glow), contentAlignment = Alignment.Center) { Text("A", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("AUTO", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (st.hasProject) "Project open · ${st.timeline?.clips?.size ?: 0} scenes" else "No project open", color = C.Muted, fontSize = 12.sp)
            }
            Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable { shell.openSettings() }.padding(10.dp)) { Text("⚙", color = C.Muted, fontSize = 22.sp) }
        }
        Spacer(Modifier.height(26.dp))
        Text("Create something amazing", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Text("Everything you need to create professional videos.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(18.dp))
        val card = RoundedCornerShape(22.dp)
        Column(Modifier.fillMaxWidth().clip(card).background(C.Card).border(1.dp, C.Line, card).padding(16.dp)) {
            HomeButton("＋  New Project", primary = true, enabled = true) { st.newProject(); shell.openEditor() }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { HomeButton("Import Project", primary = false, enabled = true) { st.sheet = "inputs"; shell.openEditor() } }
                Box(Modifier.weight(1f)) { HomeButton("Continue Editing", primary = false, enabled = st.hasProject) { st.sheet = null; shell.openEditor() } }
            }
            if (st.hasProject) Text("Recent project: ${st.timeline?.clips?.size ?: 0} scenes · ${fmt(st.playEndMs())}", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text("Quick shortcuts", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 10.dp))
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
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(C.Card).border(1.dp, C.Line, shape).clickable(onClick = sc.onClick).padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (sc.ready) C.Violet.copy(alpha = 0.25f) else C.Lane), contentAlignment = Alignment.Center) {
            Text(sc.glyph, color = if (sc.ready) C.Cyan else C.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(sc.title, color = if (sc.ready) C.Text else C.Muted, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        if (!sc.ready) Text("Soon", color = C.Cyan, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(50)).background(C.Violet.copy(alpha = 0.25f)).padding(horizontal = 8.dp, vertical = 2.dp))
        else Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun HomeButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().then(if (primary && enabled) Modifier.glow(C.Violet, 14.dp, shape) else Modifier).clip(shape)
        .then(if (primary) Modifier.background(if (enabled) C.Glow else SolidColor(C.Lane)) else Modifier.border(1.dp, C.Line, shape))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else C.Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
