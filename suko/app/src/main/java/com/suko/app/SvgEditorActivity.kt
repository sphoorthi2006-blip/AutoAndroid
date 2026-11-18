package com.suko.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import com.suko.app.svg.SvgAudioViewModel
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

/** SVG Editor: add pictures + the JSON (files, ZIPs, folders). */
class SvgEditorActivity : ComponentActivity() {
    private val vm: IntakeViewModel by viewModels()
    private val audioVm: SvgAudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.wanted = SvgInputs.WANTED
        setContent {
            CueTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = Color(0xFF0B0D12)) {
                    SvgEditorScreen(vm, audioVm)
                }
            }
        }
    }
}

@Composable
private fun SvgEditorScreen(vm: IntakeViewModel, avm: SvgAudioViewModel) {
    val ui by vm.ui.collectAsState()
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l: List<Uri> ->
        if (l.isNotEmpty()) vm.addFiles(l)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) vm.addFolder(u)
    }
    val input = SvgInputs.from(ui.result)
    val au by avm.ui.collectAsState()
    LaunchedEffect(input.json?.path, input.json?.size) { avm.loadJson(input.json) }
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) avm.setVoice(u) }
    val pickClick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) avm.setClick(u) }
    val saveMix = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { u: Uri? -> if (u != null) avm.mix(u) }
    val muted = Color(0xFF8A93A6); val amber = Color(0xFFFBBF24); val green = Color(0xFF34D399); val red = Color(0xFFF87171)
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("SVG Editor") {
            Text(input.headline, fontSize = 15.sp, color = if (input.ready) Color(0xFF34D399) else Color(0xFFFBBF24))
            for (w in input.warnings) Text(w, fontSize = 12.sp, color = Color(0xFFFBBF24))
        }
        IntakePanel(
            ui = ui,
            onAddFiles = { pickFiles.launch(arrayOf("*/*")) },
            onAddFolder = { pickFolder.launch(null) },
            onClear = { vm.clear() },
        )
        Section("Voice-over / audio input") {
            Text(au.voice?.name ?: "No audio file yet (optional)", fontSize = 14.sp, color = if (au.voice != null) Color(0xFFF2F4F8) else muted)
            au.voiceSeconds?.let { Text("Length ${"%.1f".format(it)} s", fontSize = 12.sp, color = muted) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickVoice.launch(arrayOf("audio/*")) }, enabled = !au.busy) { Text(if (au.voice == null) "Add audio" else "Change") }
                if (au.voice != null) OutlinedButton(onClick = { avm.clearVoice() }, enabled = !au.busy) { Text("Remove") }
            }
        }
        Section("Click sound (per element)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = au.clicksEnabled, onCheckedChange = { avm.setClicksEnabled(it) }, enabled = !au.busy)
                Text("Add a click at every element / cue", fontSize = 14.sp, color = Color(0xFFF2F4F8))
            }
            Text(au.click?.name ?: "built-in click", fontSize = 13.sp, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickClick.launch(arrayOf("audio/*")) }, enabled = !au.busy && au.clicksEnabled) { Text("Custom click") }
                if (au.click != null) OutlinedButton(onClick = { avm.clearClick() }, enabled = !au.busy) { Text("Use built-in") }
            }
            val plan = au.plan
            when {
                au.planError != null -> Text(au.planError!!, fontSize = 12.sp, color = red)
                plan == null -> Text("Add the JSON to plan the clicks.", fontSize = 12.sp, color = muted)
                !au.clicksEnabled -> Text("Clicks are off.", fontSize = 12.sp, color = muted)
                else -> Text("${plan.times.size} clicks over ${plan.slides} slides. First: " +
                    plan.times.take(8).joinToString(", ") { "%.2f".format(it) } + if (plan.times.size > 8) ", ..." else "", fontSize = 13.sp)
            }
        }
        Section("Audio mix test") {
            Text("Mixes the optional audio and one click per element, then saves a limited AAC .m4a for checking.", fontSize = 13.sp)
            if (au.voice == null) Text(if (au.clicksEnabled) "No audio file: clicks only." else "No audio and clicks off: nothing to mix.", fontSize = 12.sp, color = muted)
            if (au.busy) {
                Text(au.label, color = amber)
                LinearProgressIndicator(progress = { au.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { avm.cancelMix() }) { Text("Cancel") }
            } else {
                Button(onClick = { saveMix.launch("svg_editor_audio_test.m4a") }, enabled = au.plan != null && (au.voice != null || au.clicksEnabled)) { Text("Mix audio and save .m4a") }
            }
            au.error?.let { Text(it, color = red) }
            au.result?.let { Text("Done. ${"%.2f".format(it.seconds)} s, ${it.clicks} clicks.", color = green) }
        }
    }
}
