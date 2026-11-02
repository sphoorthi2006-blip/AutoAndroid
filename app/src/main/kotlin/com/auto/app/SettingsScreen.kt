package com.auto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(st: AutoState, shell: Shell) {
    val card = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Settings", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))
        Column(Modifier.fillMaxWidth().clip(card).background(C.Card).border(1.dp, C.Line, card).padding(16.dp)) {
            Text("Storage", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Deletes the imported copies, extracted pictures and old exports in the app's temporary folder, and closes the current project. Videos already saved in Movies/Auto are kept.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            val shape = RoundedCornerShape(50)
            Box(Modifier.fillMaxWidth().clip(shape).border(1.dp, C.Line, shape).clickable {
                st.clearTemp { n -> shell.message("Temporary files cleared", "Freed ${size(n)}. Import your files again to continue.") }
            }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) { Text("Clear temporary files", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(card).background(C.Card).border(1.dp, C.Line, card).padding(16.dp)) {
            Text("About", color = C.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Auto 0.2", color = C.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            Text("Export: MP4 (H.264 + AAC), 720p / 1080p / 4K, 16:9 · 9:16 · 1:1 · 4:5, 24 / 30 / 60 fps in the engine (the sheet offers 30 and 60).", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun size(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)
    else -> String.format(java.util.Locale.ROOT, "%.2f GB", b / 1073741824.0)
}
