package com.auto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.MediaLibrary
import com.auto.core.Stage

/** Step 37: project status chip (core Workflow Stage labels) + summary cards. */
@Composable
fun StageChip(st: AutoState) {
    val s = st.stage
    val col = when (s) { Stage.FAILED -> C.Pink; Stage.READY, Stage.COMPLETED -> C.Cyan; else -> C.Muted }
    Text(s.label, color = col, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun InfoCardRow(title: String, value: String, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(title, color = C.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (danger) C.Pink else C.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AnalysisCards(st: AutoState) {
    val tl = st.timeline ?: return
    val errors = st.warnings.count { it.startsWith("ERROR") }; val warns = st.warnings.size - errors
    val noPic = tl.clips.count { it.image == null }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(C.Card).padding(12.dp)) {
        InfoCardRow("Inputs", st.kinds.sorted().joinToString(", ").ifEmpty { "from a saved project" })
        InfoCardRow("Scenes", "${tl.clips.size}")
        InfoCardRow("Timing", "narration ${MediaLibrary.humanDuration(tl.audioMs)} · video ${MediaLibrary.humanDuration(tl.endMs)}")
        InfoCardRow("Picture matching", if (noPic == 0) "every scene has a picture" else "$noPic scene(s) without a picture", noPic > 0)
        InfoCardRow("Warnings", if (st.warnings.isEmpty()) "none" else "$errors error(s) · $warns warning(s)", errors > 0)
    }
}
