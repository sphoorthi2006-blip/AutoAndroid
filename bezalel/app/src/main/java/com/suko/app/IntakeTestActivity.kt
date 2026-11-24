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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cuevideo.app.CueTheme
import com.suko.app.intake.IntakePanel
import com.suko.app.intake.IntakeViewModel

/** Step 8 developer screen: try the shared intake with any files, ZIPs and folders. Nothing is used or exported. */
class IntakeTestActivity : ComponentActivity() {
    private val vm: IntakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CueTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = Color(0xFF0B0D12)) {
                    IntakeTestScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun IntakeTestScreen(vm: IntakeViewModel) {
    val ui by vm.ui.collectAsState()
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l: List<Uri> ->
        if (l.isNotEmpty()) vm.addFiles(l)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) vm.addFolder(u)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IntakePanel(
            ui = ui,
            onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
            onAddFolder = { pickFolder.launch(null) },
            onClear = { vm.clear() },
        )
    }
}
