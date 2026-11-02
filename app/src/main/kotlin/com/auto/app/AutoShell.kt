package com.auto.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Page(val glyph: String, val label: String) { Home("⌂", "Home"), Projects("▤", "Projects"), Editor("✂", "Editor"), Media("▣", "Media"), Settings("⚙", "Settings") }

/** Which page is showing + the small "this is not built yet" dialog. The Editor page is full screen (it has its own bottom tabs), so the bar shows on Home and Settings only. */
@Stable
class Shell {
    var page by mutableStateOf(Page.Home)
    var notice by mutableStateOf<Pair<String, String>?>(null)
    fun openEditor() { page = Page.Editor }
    fun openSettings() { page = Page.Settings }
    fun soon(feature: String) { notice = feature to "$feature is not built yet, so its controls are not shown. It is planned for a later batch." }
    fun message(title: String, text: String) { notice = title to text }
}

@Composable
fun AutoShell(st: AutoState) {
    val shell = remember { Shell() }
    st.onHome = { shell.page = Page.Home }
    androidx.compose.runtime.LaunchedEffect(Unit) { st.refreshProjects() }
    BackHandler(enabled = shell.page != Page.Home) { if (st.sheet != null) st.sheet = null else shell.page = Page.Home }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (shell.page) {
                    Page.Home -> HomeScreen(st, shell)
                    Page.Editor, Page.Media -> EditorScreen(st)
                    Page.Projects -> ProjectsScreen(st, shell)
                    Page.Settings -> SettingsScreen(st, shell)
                }
            }
            if (shell.page != Page.Editor) NavBar(st, shell)
        }
        shell.notice?.let { n ->
            AlertDialog(onDismissRequest = { shell.notice = null }, containerColor = C.Card, titleContentColor = C.Text, textContentColor = C.Muted,
                title = { Text(n.first, fontWeight = FontWeight.Bold) }, text = { Text(n.second) },
                confirmButton = { TextButton(onClick = { shell.notice = null }) { Text("OK", color = C.Cyan) } })
        }
    }
}

@Composable
private fun NavBar(st: AutoState, shell: Shell) {
    Row(Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().padding(vertical = 8.dp)) {
        for (p in Page.values()) {
            val on = shell.page == p
            Column(Modifier.weight(1f).clickable {
                when (p) {
                    Page.Editor -> { if (!st.hasProject) st.sheet = "inputs"; shell.openEditor() }
                    Page.Media -> { st.sheet = "pictures"; shell.openEditor() }
                    else -> shell.page = p
                }
            }.heightIn(min = 56.dp).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                AutoIcon(when (p) { Page.Home -> "home"; Page.Projects -> "projects"; Page.Editor -> "editor"; Page.Media -> "media"; Page.Settings -> "settings" }, if (on) C.Cyan else C.Muted, 24.dp)
                Text(p.label, color = if (on) C.Cyan else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
