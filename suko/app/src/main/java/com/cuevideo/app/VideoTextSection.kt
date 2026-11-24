package com.cuevideo.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * "Text on video": type a cue range and a text; it is handwritten straight into the final video
 * in place of those cues' images (no separate export). Two looks only: black with white text,
 * or white with black text.
 */
@Composable
fun TextOnVideoSection(s: UiState, vm: AppViewModel) {
    Section("Text on video") {
        if (s.masterCues.isEmpty()) {
            Text("Choose the master SRT first. Then you can write a verse or any text over a range of cues.", fontSize = 13.sp, color = Muted)
            return@Section
        }
        val total = s.masterCues.size
        var range by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var dark by remember { mutableStateOf(true) }
        var center by remember { mutableStateOf(true) }
        var size by remember { mutableStateOf(1f) }
        var px by remember { mutableStateOf(0f) }
        var py by remember { mutableStateOf(0f) }

        Text(
            "Type the cues, for example 123-125 (or just 125), then the text. It is written by hand over those cues instead of their images. Cues 1 to $total.",
            fontSize = 12.sp, color = Muted,
        )
        val parsed = TextPlacement.parseRange(range)
        OutlinedTextField(
            value = range,
            onValueChange = { v -> range = v.filter { ch -> ch.isDigit() || ch == '-' || ch == ' ' || ch == '\u2013' }.take(12) },
            label = { Text("Cues (like 123-125)") }, singleLine = true,
            isError = range.isNotBlank() && parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            label = { Text("Your text (a new line stays a new line)") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        // live picture of the finished text: drag it to place it
        val draft by vm.draft.collectAsState()
        LaunchedEffect(text, dark, center, size, px, py) {
            vm.showDraft(
                TextPlacement(
                    1, 1, text, dark = dark,
                    align = if (center) TextAlign.CENTER else TextAlign.LEFT,
                    size = size.toDouble(), x = px.toDouble(), y = py.toDouble(),
                )
            )
        }
        val shown = draft
        if (shown != null) {
            Image(
                bitmap = shown, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        px = (px + drag.x / this.size.width).coerceIn(-0.5f, 0.5f)
                        py = (py + drag.y / this.size.height).coerceIn(-0.5f, 0.5f)
                    }
                },
            )
            Text("Drag the text to move it. This is how it looks once it is fully written.", fontSize = 12.sp, color = Muted)
        }
        Text("Look", fontSize = 13.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Black, white text", dark) { dark = true }
            Pill("White, black text", !dark) { dark = false }
        }
        Text("Alignment", fontSize = 13.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Centre", center) { center = true }
            Pill("Left", !center) { center = false }
        }
        Text("Size ${String.format(Locale.US, "%.1f", size)}x (1.0 = biggest that fits)", fontSize = 13.sp, color = Muted)
        Slider(value = size, onValueChange = { size = it }, valueRange = 0.3f..2.0f)
        Text("Move left / right", fontSize = 13.sp, color = Muted)
        Slider(value = px, onValueChange = { px = it }, valueRange = -0.5f..0.5f)
        Text("Move up / down", fontSize = 13.sp, color = Muted)
        Slider(value = py, onValueChange = { py = it }, valueRange = -0.5f..0.5f)
        TextButton(onClick = { size = 1f; px = 0f; py = 0f }) { Text("Reset size and position") }

        val ok = parsed != null && text.isNotBlank()
        GradientButton("Add to video", enabled = ok) {
            val r = parsed ?: return@GradientButton
            vm.addText(
                TextPlacement(
                    first = r.first, last = r.second, text = text.trim(), dark = dark,
                    align = if (center) TextAlign.CENTER else TextAlign.LEFT,
                    size = size.toDouble(), x = px.toDouble(), y = py.toDouble(),
                )
            )
            text = ""
        }

        if (s.texts.isNotEmpty()) {
            Text("Added texts", fontSize = 14.sp, color = TextMain)
            for ((i, t) in s.texts.withIndex()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${t.label}: ${t.text.replace('\n', ' ').take(40)}", color = TextMain, fontSize = 14.sp)
                        Text(
                            (if (t.dark) "black, white text" else "white, black text") +
                                (if (t.align == TextAlign.CENTER) ", centre" else ", left"),
                            fontSize = 11.sp, color = Muted,
                        )
                    }
                    TextButton(onClick = { vm.showPreview(t.first, 0) }) { Text("Preview") }
                    TextButton(onClick = {
                        range = if (t.first == t.last) "${t.first}" else "${t.first}-${t.last}"
                        text = t.text; dark = t.dark; center = t.align == TextAlign.CENTER
                        size = t.size.toFloat(); px = t.x.toFloat(); py = t.y.toFloat()
                        vm.removeText(i)
                    }) { Text("Edit") }
                    TextButton(onClick = { vm.removeText(i) }) { Text("Remove") }
                }
            }
            Text("Preview shows in the Slide preview section below. Problems (overlaps, cues out of range) are listed in the Export section.", fontSize = 12.sp, color = Muted)
        }
    }
}
