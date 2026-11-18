package com.suko.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuevideo.app.CueTheme
import com.cuevideo.app.GradientButton
import com.cuevideo.app.Pill
import com.cuevideo.app.Section
import com.suko.app.intake.FileKind
import com.suko.app.intake.IntakePanel
import com.suko.app.intake.IntakeViewModel
import com.suko.app.upscale.OutputMode
import com.suko.app.upscale.UpscaleImagesViewModel

/** Upscale Images: pictures, ZIPs and folders in; PNG files or ZIPs out. AI 2x, same engine as the Auto Editor export. */
class UpscaleImagesActivity : ComponentActivity() {
    private val intake: IntakeViewModel by viewModels()
    private val run: UpscaleImagesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intake.wanted = setOf(FileKind.IMAGE)
        setContent {
            CueTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = Color(0xFF0B0D12)) {
                    UpscaleImagesScreen(intake, run)
                }
            }
        }
    }
}

private val Muted = Color(0xFF8A93A6)
private val TextMain = Color(0xFFF2F4F8)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)

@Composable
private fun UpscaleImagesScreen(intake: IntakeViewModel, run: UpscaleImagesViewModel) {
    val ui by intake.ui.collectAsState()
    val r by run.ui.collectAsState()
    var mode by remember { mutableStateOf(OutputMode.PNG_FOLDER) }
    var removeMark by remember { mutableStateOf(true) }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(r.busy) { view.keepScreenOn = r.busy; onDispose { view.keepScreenOn = false } }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l: List<Uri> ->
        if (l.isNotEmpty()) intake.addFiles(l)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) intake.addFolder(u)
    }
    val pickOut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) run.start(ui.result.entries, u, mode, removeMark)
    }
    val pics = ui.result.entries.size

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Upscale Images", fontSize = 26.sp, color = TextMain)
        Text("AI upscale 2x. Add pictures, ZIPs or folders; get PNG files or ZIPs back.", fontSize = 13.sp, color = Muted)

        IntakePanel(
            ui = ui,
            onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
            onAddFolder = { pickFolder.launch(null) },
            onClear = { intake.clear(); run.reset() },
        )

        Section("Output") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("PNG folder", mode == OutputMode.PNG_FOLDER) { if (!r.busy) mode = OutputMode.PNG_FOLDER }
                Pill("ZIPs", mode == OutputMode.ZIPS) { if (!r.busy) mode = OutputMode.ZIPS }
            }
            Text(
                if (mode == OutputMode.PNG_FOLDER) "PNG files, in the same sub-folders as your input. Names keep their numbers (1.jpg becomes 1.png)."
                else "One ZIP per input (each ZIP or folder you added; loose pictures go into one ZIP).",
                fontSize = 12.sp, color = Muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Remove the corner mark", color = TextMain)
                Switch(checked = removeMark, onCheckedChange = { if (!r.busy) removeMark = it })
            }
            Text("You choose the folder; a \"Bezalel upscaled\" folder is created inside it.", fontSize = 12.sp, color = Muted)
        }

        if (r.busy) {
            Section("Working") {
                Text(r.label, color = Amber, fontSize = 14.sp)
                LinearProgressIndicator(progress = { r.progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Color(0xFF8B5CF6), trackColor = Color(0x1AFFFFFF))
                OutlinedButton(onClick = { run.cancel() }) { Text("Cancel", color = TextMain) }
                Text("Keep the app open and the phone plugged in.", fontSize = 11.sp, color = Muted)
            }
        } else {
            GradientButton(
                if (pics == 0) "Add pictures first" else "Upscale $pics picture${if (pics == 1) "" else "s"}",
                enabled = pics > 0 && !ui.busy,
            ) { pickOut.launch(null) }
        }
        r.error?.let { Text(it, color = Red, fontSize = 13.sp) }
        r.result?.let { d ->
            Section("Done") {
                Text(d.summary, color = Green, fontSize = 15.sp)
                Text("Saved in: ${d.where}", fontSize = 12.sp, color = Muted)
                if (d.outputs.isNotEmpty()) Text(d.outputs.take(20).joinToString(", ") + if (d.outputs.size > 20) ", ..." else "", fontSize = 12.sp, color = Muted)
                d.note?.let { Text(it, fontSize = 12.sp, color = Amber) }
                if (d.notSaved.isNotEmpty()) {
                    Text("Not saved (${d.notSaved.size}): " + d.notSaved.take(10).joinToString(", ") + if (d.notSaved.size > 10) ", ..." else "", fontSize = 12.sp, color = Red)
                }
            }
        }
    }
}
