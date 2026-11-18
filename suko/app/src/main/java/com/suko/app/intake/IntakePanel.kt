package com.suko.app.intake

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cuevideo.app.GradientButton
import com.cuevideo.app.Section

private val Muted = Color(0xFF8A93A6)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)
private val TextMain = Color(0xFFF2F4F8)

/** What intake found, grouped by kind, then what was skipped and why. Pure display: no picking here. */
@Composable
fun IntakeResultView(summary: IntakeSummary) {
    Text(summary.headline, color = if (summary.groups.isEmpty()) Amber else Green, fontSize = 14.sp)
    for (g in summary.groups) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${g.label} (${g.count})", color = TextMain, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            val more = g.count - g.names.size
            Text(
                g.names.joinToString(", ") + (if (more > 0) "  and $more more" else ""),
                color = Muted, fontSize = 12.sp,
            )
        }
    }
    if (summary.skipped.isNotEmpty()) {
        Text("Skipped or failed", color = TextMain, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        for (s in summary.skipped) {
            Text("${s.count} x ${s.reason}", color = Red, fontSize = 13.sp)
            Text(s.examples.joinToString(", "), color = Muted, fontSize = 12.sp)
        }
    }
}

/** The shared add-and-review panel: two buttons, a progress bar while reading, then the grouped result. */
@Composable
fun IntakePanel(
    ui: IntakeUi,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onClear: () -> Unit,
) {
    Section("Your files") {
        Text(
            "Add any mix: loose files, ZIPs (also ZIPs inside ZIPs), or folders with sub-folders.",
            fontSize = 13.sp, color = Muted,
        )
        GradientButton("+  Files or ZIPs", enabled = !ui.busy, onClick = onAddFiles)
        GradientButton("+  Folder", enabled = !ui.busy, onClick = onAddFolder)
        if (ui.busy) {
            Text("Reading your files...", color = Amber, fontSize = 13.sp)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Green, trackColor = Muted)
        }
        val err = ui.error
        if (err != null) Text(err, color = Red, fontSize = 13.sp)
        if (ui.result.entries.isNotEmpty() || ui.result.problems.isNotEmpty()) {
            IntakeResultView(ui.summary)
            TextButton(onClick = onClear) { Text("Clear all", color = Muted) }
        }
    }
}
