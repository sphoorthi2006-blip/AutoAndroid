package com.cuevideo.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CueTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = Bg) {
                    MainScreen(vm)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ look

private val Bg = Color(0xFF0B0D12)
private val PanelBg = Color(0xFF151821)
private val Hairline = Color(0x1AFFFFFF)
private val TextMain = Color(0xFFF2F4F8)
private val Muted = Color(0xFF8A93A6)
private val Accent = Color(0xFF8B5CF6)
private val Accent2 = Color(0xFF38BDF8)
private val Red = Color(0xFFF87171)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFFBBF24)

@Composable
fun CueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Color.White, secondary = Accent2,
            background = Bg, surface = Bg, onSurface = TextMain, onBackground = TextMain,
            surfaceVariant = PanelBg, outline = Color(0x33FFFFFF),
        ),
        content = content,
    )
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier.fillMaxWidth().clip(shape).background(PanelBg).border(1.dp, Hairline, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain)
        content()
    }
}

@Composable
fun GradientButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (enabled) Brush.horizontalGradient(listOf(Accent, Accent2))
    else Brush.horizontalGradient(listOf(Color(0xFF262B38), Color(0xFF262B38)))
    Box(
        modifier = Modifier.fillMaxWidth().height(56.dp).clip(shape).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Color.White else Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier.clip(shape)
            .background(if (selected) Accent else Color.Transparent)
            .border(1.dp, if (selected) Accent else Hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) { Text(text, color = if (selected) Color.White else Muted, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

/** level: 0 = found, 1 = optional and missing, 2 = needed and missing */
@Composable
fun FoundRow(title: String, value: String?, level: Int, onChange: (() -> Unit)?, onClear: (() -> Unit)?) {
    val dot = when (level) { 0 -> Green; 1 -> Amber; else -> Red }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = TextMain)
            Text(value ?: "not found", fontSize = 12.sp, color = if (level == 0) Muted else dot, maxLines = 2)
        }
        if (onChange != null) TextButton(onClick = onChange) { Text(if (level == 0) "Change" else "Choose") }
        if (onClear != null && level == 0) TextButton(onClick = onClear) { Text("Remove", color = Muted) }
    }
}

// ------------------------------------------------------------------ screen

@Composable
fun MainScreen(vm: AppViewModel) {
    val s by vm.state.collectAsState()
    var fixing by remember { mutableStateOf<ClipRow?>(null) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

    val pickAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l: List<Uri> ->
        if (l.isNotEmpty()) vm.addAny(l)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) vm.addFolderAny(u)
    }
    val pickMaster = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.setMaster(u)
    }
    val pickOriginal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.setOriginal(u)
    }
    val pickScript = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.setScript(u)
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.setAudio(u)
    }
    val pickClick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) vm.setClick(u)
    }

    val au by vm.audio.collectAsState()
    val ex by vm.export.collectAsState()
    val view = LocalView.current
    DisposableEffect(au.busy, ex.busy) {
        view.keepScreenOn = au.busy || ex.busy
        onDispose { view.keepScreenOn = false }
    }
    val pickExportFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) vm.startExport(u)
    }
    val saveAudio = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { u: Uri? ->
        if (u != null) vm.startAudio(u)
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2))),
                contentAlignment = Alignment.Center,
            ) { Text("C", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Cue Video", fontSize = 26.sp, fontWeight = FontWeight.Black, color = TextMain)
                Text("Images, captions and voice-over to a 1080p video", fontSize = 12.sp, color = Muted)
            }
        }

        // ---- one input button
        Section("Your project") {
            Text(
                "Add everything with one button: a ZIP bundle, a folder, or separate files. " +
                    "Pictures, the two timestamp SRT files, the script, the voice-over and scene clips are sorted automatically.",
                fontSize = 13.sp, color = Muted,
            )
            Box {
                GradientButton("+  Add project") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Files or ZIP bundle") },
                        onClick = { menu = false; pickAny.launch(arrayOf("*/*")) },
                    )
                    DropdownMenuItem(
                        text = { Text("Folder") },
                        onClick = { menu = false; pickFolder.launch(null) },
                    )
                }
            }
            val busy = s.busy
            if (busy != null) {
                Text(busy, color = Amber, fontSize = 13.sp)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Hairline)
            }
            val note = s.notice
            if (note != null) Text(note, color = Green, fontSize = 13.sp)
            val msg = s.message
            if (msg != null) Text(msg, color = Red, fontSize = 13.sp)
            if (s.master != null || s.images.isNotEmpty() || s.audio != null) {
                TextButton(onClick = { vm.resetAll() }) { Text("Start over", color = Muted) }
            }
        }

        // ---- what was found
        Section("What I found") {
            val c = s.check
            FoundRow(
                "Master SRT  (what each slide says)",
                s.master?.let { it.name + "  -  " + s.masterCues.size + " cues" },
                if (s.master != null) 0 else 2,
                { pickMaster.launch(arrayOf("*/*")) }, null,
            )
            FoundRow(
                "Original SRT  (when each slide starts)",
                s.original?.let { it.name + "  -  " + s.originalCueList.size + " cues" },
                if (s.original != null) 0 else 1,
                { pickOriginal.launch(arrayOf("*/*")) }, { vm.clearOriginal() },
            )
            FoundRow(
                "Script  (text file)",
                s.script?.name,
                if (s.script != null) 0 else 1,
                { pickScript.launch(arrayOf("*/*")) }, { vm.clearScript() },
            )
            FoundRow(
                "Voice-over",
                s.audio?.name,
                if (s.audio != null) 0 else 2,
                { pickAudio.launch(arrayOf("audio/*")) }, null,
            )
            val missingN = c?.missing?.size ?: 0
            FoundRow(
                "Pictures",
                if (s.images.isEmpty()) null
                else s.images.size.toString() + " loaded" + if (c != null && missingN > 0) "  -  $missingN cue(s) still need one" else if (c != null) "  -  every cue covered" else "",
                if (s.images.isEmpty()) (if (s.master != null) 2 else 1) else if (missingN > 0) 2 else 0,
                null, null,
            )
            FoundRow(
                "Scene clips  (optional)",
                if (s.clips.isEmpty()) null else s.clips.size.toString() + " clip(s)",
                if (s.clips.isEmpty()) 1 else 0,
                null, null,
            )
            FoundRow(
                "Click sound",
                s.click?.name ?: "built-in click",
                0,
                { pickClick.launch(arrayOf("audio/*")) }, if (s.click != null) ({ vm.clearClick() }) else null,
            )
        }

        // ---- scene clips
        if (s.clips.isNotEmpty()) {
            Section("Scene clips") {
                Text("Each clip replaces the cues in its name: 53.mp4 is cue 53, 12-14.mp4 is cues 12 to 14. Check the list and press Fix if a number was read wrongly.", fontSize = 12.sp, color = Muted)
                val rows = s.check?.clipRows ?: Checker.clipRows(s.clips, s.corrections)
            for (r in rows) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.label, color = if (r.first == null) Red else TextMain, fontSize = 14.sp)
                        if (r.corrected) Text("(set by hand)", fontSize = 11.sp, color = Muted)
                    }
                    TextButton(onClick = { fixing = r }) { Text("Fix") }
                    TextButton(onClick = { vm.removeClip(r.clip.uri) }) { Text("Remove") }
                }
            }
            }
        }

        // ---- export
        Section("Export") {
            val c = s.check
            val ready = c != null && c.canExport && s.audio != null && s.timing != null
            if (c == null) {
                Text("Add your files to get started. The master SRT decides how many slides there are.", fontSize = 13.sp, color = Muted)
            } else {
                Text(
                    if (ready) "Ready to export" else "Not ready yet",
                    color = if (ready) Green else Red, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                Text("${c.cueCount} cues  -  ${c.headingCues} headings  -  ${c.subscribeCues} subscribe  -  ${c.covered.size} under scene clips", fontSize = 12.sp, color = Muted)
                for (e in c.errors) Text(e, color = Red, fontSize = 13.sp)
                if (c.missing.isNotEmpty()) Text(c.missingText, color = Red, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                if (s.audio == null) Text("Voice-over not found yet.", color = Red, fontSize = 13.sp)
                if (c.missing.isNotEmpty() || c.headingCues + c.subscribeCues > 0)
                    Text("Heading and subscribe cues also need a picture file. It is never shown, but it must be there.", fontSize = 11.sp, color = Muted)
                for (w in c.warnings) Text(w, color = Amber, fontSize = 12.sp)
                if (s.original == null) Text("No original SRT: master timestamps will be used.", color = Amber, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Frame rate", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                for (f in listOf(30, 60)) Pill("$f fps", s.fps == f) { vm.setFps(f) }
            }
            if (ex.busy) {
                Text(ex.label, color = Amber, fontSize = 13.sp)
                LinearProgressIndicator(progress = { ex.progress.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Hairline)
                OutlinedButton(onClick = { vm.cancelExport() }, border = BorderStroke(1.dp, Hairline)) { Text("Cancel", color = TextMain) }
                Text("Keep the app open and the phone plugged in.", fontSize = 11.sp, color = Muted)
            } else {
                GradientButton("Export video", enabled = ready && !au.busy) { pickExportFolder.launch(null) }
                if (ready) Text("You choose the folder. The MP4 and a report CSV are saved there.", fontSize = 11.sp, color = Muted)
            }
            val err = ex.error
            if (err != null) Text(err, color = Red, fontSize = 13.sp)
            val r = ex.result
            if (r != null) {
                Text("Done: ${r.videoName}", color = Green, fontWeight = FontWeight.Bold)
                Text("Report: ${r.reportName}", fontSize = 13.sp, color = Muted)
                Text("${r.frames} frames, ${"%.1f".format(r.seconds)} s of video, made in ${"%.0f".format(r.took)} s. ${r.clicks} clicks, voice-over ${"%.1f".format(r.lufs)} LUFS.", fontSize = 13.sp, color = Muted)
                if (r.breakdown.isNotEmpty()) Text(r.breakdown, fontSize = 11.sp, color = Muted)
                Text("Pictures used: ${r.usedImages} of ${r.givenImages}.", fontSize = 13.sp, color = Muted)
                if (r.unusedImages.isNotEmpty())
                    Text("Not used: " + r.unusedImages.take(30).joinToString(", ") + if (r.unusedImages.size > 30) ", ..." else "", fontSize = 12.sp, color = Amber)
            }
        }

        // ---- tools
        TextButton(onClick = { details = !details }) {
            Text(if (details) "Hide tools and details" else "Show tools and details  (timing, slide preview, audio test)", color = Muted)
        }
        if (details) {
        Section("Timing") {
            val t = s.timing
            var showTable by remember { mutableStateOf(false) }
            if (t == null) {
                Text("Timing appears when the master SRT is chosen.", color = Muted, fontSize = 13.sp)
            } else {
                val secs = t.totalSeconds
                Text("Total length: ${(secs / 60).toInt()}:${"%02d".format((secs % 60).toInt())}  (${t.frames[t.frames.size - 1]} frames at ${s.fps} fps)")
                if (s.audio != null && s.audioSeconds == null) Text("Voice-over length unknown, master end time used.", color = Amber, fontSize = 13.sp)
                if (t.ratio == null) {
                    Text("No original SRT: master start times are used.", color = Amber)
                } else {
                    Text("Word match between the two SRTs: ${"%.1f".format(t.ratio * 100)}%", color = if (t.noMatch) Red else Green, fontWeight = FontWeight.Medium)
                    if (t.noMatch) Text("The two SRT files don't look like the same script.", color = Red)
                    else {
                        if (t.estimated.isNotEmpty()) Text("Slides placed by estimate (${t.estimated.size}): " + t.estimated.take(40).joinToString(", ") + if (t.estimated.size > 40) ", ..." else "", fontSize = 13.sp)
                        if (t.shifts.isNotEmpty()) Text("Biggest shifts vs master times: " + t.shifts.joinToString(", ") { "cue ${it.first} ${"%+.1f".format(it.second)}s" }, fontSize = 13.sp)
                    }
                }
                if (t.shortSlides.isNotEmpty()) Text("Slides shorter than 1 s: " + t.shortSlides.joinToString(", "), color = Amber, fontSize = 13.sp)
                OutlinedButton(onClick = { showTable = !showTable }) { Text(if (showTable) "Hide cue times" else "Show cue times") }
                if (showTable) {
                    val sb = StringBuilder("cue  start    end\n")
                    val n = t.starts.size
                    for (i in 0 until n) {
                        sb.append("%-4d %-8.2f %-8.2f".format(i + 1, t.frames[i].toDouble() / s.fps, t.frames[i + 1].toDouble() / s.fps))
                        sb.append(if (i in t.estimated.map { it - 1 }) "  est\n" else "\n")
                    }
                    Text(sb.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }


        Section("Slide preview") {
            val pv by vm.preview.collectAsState()
            val total = s.check?.cueCount ?: 0
            var cueText by remember { mutableStateOf("1") }
            var slider by remember { mutableStateOf(0f) }
            if (total == 0) {
                Text("Choose the master SRT to preview slides.", color = Muted, fontSize = 13.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val c = ((cueText.toIntOrNull() ?: 1) - 1).coerceIn(1, total)
                        cueText = c.toString(); slider = 0f; vm.showPreview(c, 0)
                    }) { Text("-") }
                    OutlinedTextField(
                        value = cueText,
                        onValueChange = { v ->
                            cueText = v.filter { ch -> ch.isDigit() }.take(5)
                        },
                        label = { Text("Cue (1-$total)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        val c = ((cueText.toIntOrNull() ?: 0) + 1).coerceIn(1, total)
                        cueText = c.toString(); slider = 0f; vm.showPreview(c, 0)
                    }) { Text("+") }
                }
                Button(onClick = {
                    val c = (cueText.toIntOrNull() ?: 1).coerceIn(1, total)
                    cueText = c.toString(); slider = 0f; vm.showPreview(c, 0)
                }) { Text("Show this cue") }
                if (pv.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val img = pv.image
                if (img != null) {
                    Image(
                        bitmap = img, contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                }
                if (pv.frames > 1) {
                    Slider(
                        value = slider,
                        onValueChange = { v -> slider = v; vm.showPreview(pv.cue, v.toInt()) },
                        valueRange = 0f..(pv.frames - 1).toFloat(),
                    )
                    Text("Slide the bar to see the zoom and the fade-in frame by frame.", fontSize = 12.sp, color = Muted)
                }
                if (pv.info.isNotEmpty()) Text(pv.info, fontSize = 13.sp)
            }
        }


        Section("Audio mix test") {
            val times = clickTimesFor(s)
            Text("Mixes the voice-over with the clicks (loudness-matched, limited, AAC 192 kbps) and saves a .m4a you can play to check the clicks.", fontSize = 13.sp)
            if (times == null) {
                Text("Choose the master SRT first.", color = Muted, fontSize = 13.sp)
            } else {
                Text("${times.size} clicks planned at ${s.fps} fps. First: " +
                    times.take(8).joinToString(", ") { "%.2f".format(it) } + if (times.size > 8) ", ..." else "", fontSize = 13.sp)
            }
            val canMix = times != null && s.audio != null && (s.check?.errors?.isEmpty() == true) && !au.busy && !ex.busy
            if (s.audio == null) Text("Voice-over not chosen yet.", color = Red, fontSize = 13.sp)
            if (au.busy) {
                Text(au.label, color = Amber)
                LinearProgressIndicator(progress = { au.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.cancelAudio() }) { Text("Cancel") }
            } else {
                Button(onClick = { saveAudio.launch("cuevideo_audio_test.m4a") }, enabled = canMix) { Text("Mix audio and save .m4a") }
            }
            val err = au.error
            if (err != null) Text(err, color = Red)
            val r = au.result
            if (r != null) {
                Text("Done. Length ${"%.2f".format(r.seconds)} s, ${r.clicks} clicks.", color = Green, fontWeight = FontWeight.Medium)
                Text("Voice-over loudness: ${"%.1f".format(r.lufs)} LUFS" + if (r.lufsMeasured) "" else " (could not be measured, -20 used)", fontSize = 13.sp)
                Text("Click: rms ${"%.1f".format(r.clickRmsDb)} dB, peak ${"%.3f".format(r.clickPeak)}, gain ${"%+.1f".format(r.gainDb)} dB", fontSize = 13.sp)
            }
        }
        }
        Spacer(Modifier.height(24.dp))
    }

    val fx = fixing
    if (fx != null) {
        var a by remember(fx.clip.uri) { mutableStateOf((fx.first ?: 1).toString()) }
        var b by remember(fx.clip.uri) { mutableStateOf((fx.last ?: fx.first ?: 1).toString()) }
        AlertDialog(
            onDismissRequest = { fixing = null },
            title = { Text("Which cues does ${fx.clip.name} replace?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = a, onValueChange = { a = it.filter { ch -> ch.isDigit() } },
                        label = { Text("First cue") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = b, onValueChange = { b = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Last cue (same number for one cue)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val first = a.toIntOrNull()
                    val last = b.toIntOrNull()
                    if (first != null && last != null) vm.setCorrection(fx.clip.uri, first, last)
                    fixing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { fixing = null }) { Text("Cancel") } },
        )
    }
}
