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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuevideo.app.CueTheme
import com.cuevideo.app.Section
import com.suko.app.intake.IntakePanel
import com.suko.app.intake.IntakeViewModel
import com.suko.app.intake.SvgInputs

/** SVG Editor, step 24: add pictures + the JSON (files, ZIPs, folders). Preview and export come in later steps. */
class SvgEditorActivity : ComponentActivity() {
    private val vm: IntakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.wanted = SvgInputs.WANTED
        setContent {
            CueTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = Color(0xFF0B0D12)) {
                    SvgEditorScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun SvgEditorScreen(vm: IntakeViewModel) {
    val ui by vm.ui.collectAsState()
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l: List<Uri> ->
        if (l.isNotEmpty()) vm.addFiles(l)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) vm.addFolder(u)
    }
    val input = SvgInputs.from(ui.result)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("SVG Editor") {
            Text(input.headline, fontSize = 15.sp, color = if (input.ready) Color(0xFF34D399) else Color(0xFFFBBF24))
            for (w in input.warnings) Text(w, fontSize = 12.sp, color = Color(0xFFFBBF24))
            Text("Preview with the panel check comes in a later step.", fontSize = 12.sp, color = Color(0xFF8A93A6))
        }
        IntakePanel(
            ui = ui,
            onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
            onAddFolder = { pickFolder.launch(null) },
            onClear = { vm.clear() },
        )
    }
}
