package com.auto.app

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.AnimKind
import com.auto.core.CaseMode
import com.auto.core.ColorText
import com.auto.core.FontCatalog
import com.auto.core.NamedStyle
import com.auto.core.OverlayStyle
import com.auto.core.RenderCaps
import com.auto.core.StudioTools
import com.auto.core.StyleLibrary
import com.auto.core.StylePresets
import com.auto.core.Timeline
import com.auto.core.addTextAt
import kotlinx.coroutines.delay
import java.util.Locale

// ───────────── small shared widgets (also used by the Captions sheet) ─────────────

@Composable
fun BetaTag(id: String) {
    if (RenderCaps.isBeta(id)) Text("Beta", color = C.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(C.Violet.copy(alpha = 0.25f)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun SecTitle(t: String) = Text(t, color = C.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
fun StudioSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(fmt(v), color = C.Muted, fontSize = 13.sp)
        }
        Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onCommit(v) },
            colors = SliderDefaults.colors(thumbColor = C.Cyan, activeTrackColor = C.Violet, inactiveTrackColor = C.Lane))
    }
}

private fun pct(v: Float) = String.format(Locale.ROOT, "%.1f%%", v * 100f)
private fun em(v: Float) = String.format(Locale.ROOT, "%.2f em", v)
private fun deg(v: Float) = String.format(Locale.ROOT, "%.0f°", v)
private fun ms(v: Float) = String.format(Locale.ROOT, "%.0f ms", v)

@Composable
fun ColorRow(label: String, argb: Int, onChange: (Int) -> Unit) {
    var hex by remember { mutableStateOf(ColorText.hex(argb)) }
    LaunchedEffect(argb) { if (ColorText.parse(hex) != (argb or 0xFF000000.toInt())) hex = ColorText.hex(argb) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = C.Text, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (p in ColorText.PRESETS) {
                val on = (p and 0xFFFFFF) == (argb and 0xFFFFFF)
                Box(Modifier.padding(end = 8.dp).size(32.dp).clip(CircleShape).background(Color(p)).border(if (on) 3.dp else 1.dp, if (on) C.Cyan else C.Line, CircleShape).clickable { onChange(p) })
            }
            BasicTextField(hex, { hex = it; ColorText.parse(it)?.let { c -> onChange(c) } }, singleLine = true,
                textStyle = TextStyle(color = C.Text, fontSize = 14.sp), cursorBrush = SolidColor(C.Cyan),
                modifier = Modifier.width(96.dp).border(1.dp, if (ColorText.parse(hex) == null) C.Pink else C.Line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
        }
        if (ColorText.parse(hex) == null) Text("Use a colour like #7C3AED", color = C.Pink, fontSize = 11.sp)
    }
}

@Composable
private fun FontChip(faceId: String, label: String, on: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val family = remember(faceId) { try { FontFamily(FontResolver.typeface(ctx, OverlayStyle(font = faceId, weight = 400))) } catch (e: Exception) { FontFamily.Default } }
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.padding(end = 8.dp, bottom = 8.dp).clip(shape).background(if (on) C.Cyan.copy(alpha = 0.15f) else Color.Transparent).border(1.dp, if (on) C.Cyan else C.Line, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(FontCatalog.face(faceId).sample, color = if (on) C.Cyan else C.Text, fontFamily = family, fontSize = 18.sp)
        Text(label, color = C.Muted, fontSize = 11.sp)
    }
}

@Composable
fun TextInput(key: Any, value: String, hint: String, onCommit: (String) -> Unit) {
    var v by remember(key) { mutableStateOf(value) }
    LaunchedEffect(v) { if (v != value) { delay(600); onCommit(v) } }   // one undo step per pause in typing, not per letter
    Box(Modifier.fillMaxWidth().border(1.dp, C.Line, RoundedCornerShape(14.dp)).padding(12.dp)) {
        if (v.isEmpty()) Text(hint, color = C.Muted, fontSize = 15.sp)
        BasicTextField(v, { v = it }, textStyle = TextStyle(color = C.Text, fontSize = 16.sp), cursorBrush = SolidColor(C.Cyan), modifier = Modifier.fillMaxWidth())
    }
}

/** A time typed as m:ss.mmm; applied on Done or when the field loses focus; a wrong entry goes back to the old value. */
@Composable
fun TimeField(label: String, valueMs: Long, modifier: Modifier = Modifier, onCommit: (Long) -> Unit) {
    var s by remember(valueMs) { mutableStateOf(StudioTools.stamp(valueMs)) }
    fun commit() { val p = StudioTools.parseStamp(s); if (p == null) s = StudioTools.stamp(valueMs) else if (p != valueMs) onCommit(p) }
    Column(modifier) {
        Text(label, color = C.Muted, fontSize = 11.sp)
        BasicTextField(s, { s = it }, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 14.sp), cursorBrush = SolidColor(C.Cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() }.border(1.dp, C.Line, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** What the style looks like on a dark frame, drawn by the SAME OverlayRenderer as the export. */
@Composable
fun StylePreview(text: String, style: OverlayStyle, ratio: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.height(170.dp).aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(Color(0xFF3A3A46), Color(0xFF0B0B0F)))).border(1.dp, C.Line, RoundedCornerShape(14.dp))) {
            Canvas(Modifier.fillMaxSize()) { drawIntoCanvas { OverlayRenderer.draw(ctx, it.nativeCanvas, size.width.toInt(), size.height.toInt(), text, style) } }
        }
    }
}

// ───────────── the style editor (Text Studio and Captions use this one composable) ─────────────

@Composable
fun StyleEditor(style: OverlayStyle, onChange: (OverlayStyle) -> Unit, presets: List<NamedStyle>, lib: StyleLibrary, onSave: (String) -> Unit, onDeleteSaved: (String) -> Unit, animations: Boolean, positionControls: Boolean) {
    val sdk = Build.VERSION.SDK_INT
    Column(Modifier.fillMaxWidth()) {
        SecTitle("PRESETS")
        Row(Modifier.horizontalScroll(rememberScrollState())) { for (p in presets) OptionChip(p.name, style == p.style) { onChange(p.style) } }
        if (lib.items.isNotEmpty()) {
            Text("My styles", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                for (p in lib.items) { OptionChip(p.name, false) { onChange(p.style) }; Text("✕", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(end = 12.dp, bottom = 8.dp).clickable { onDeleteSaved(p.name) }) }
            }
        }
        var name by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 14.sp), cursorBrush = SolidColor(C.Cyan),
                decorationBox = { inner -> if (name.isEmpty()) Text("Name for this style", color = C.Muted, fontSize = 14.sp); inner() },
                modifier = Modifier.weight(1f).border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
            Spacer(Modifier.width(8.dp))
            OptionChip("Save style", false) { onSave(name); name = "" }
        }

        SecTitle("FONT")
        Row(Modifier.horizontalScroll(rememberScrollState())) { for (f in FontCatalog.faces) FontChip(f.id, f.label, style.font == f.id) { onChange(style.copy(font = f.id, italic = style.italic && f.hasItalic)) } }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (w in FontCatalog.offeredWeights(sdk)) OptionChip(w.label, FontCatalog.effectiveWeight(style.weight, sdk) == FontCatalog.effectiveWeight(w.value, sdk)) { onChange(style.copy(weight = w.value)) }
        }
        if (sdk < 28) Text("Medium, Semibold and Extra Bold need Android 9 or newer.", color = C.Muted, fontSize = 11.sp)
        StudioSlider("Size (of frame height)", style.sizeFrac, 0.015f..0.2f, ::pct) { onChange(style.copy(sizeFrac = it)) }
        StudioSlider("Letter spacing", style.letterSpacingEm, -0.1f..0.5f, ::em) { onChange(style.copy(letterSpacingEm = it)) }
        StudioSlider("Line spacing", style.lineSpacing, 0.8f..2.5f, { String.format(Locale.ROOT, "%.2f", it) }) { onChange(style.copy(lineSpacing = it)) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            OptionChip("Left", style.align == com.auto.core.TextAlign.LEFT) { onChange(style.copy(align = com.auto.core.TextAlign.LEFT)) }
            OptionChip("Center", style.align == com.auto.core.TextAlign.CENTER) { onChange(style.copy(align = com.auto.core.TextAlign.CENTER)) }
            OptionChip("Right", style.align == com.auto.core.TextAlign.RIGHT) { onChange(style.copy(align = com.auto.core.TextAlign.RIGHT)) }
            OptionChip("Aa", style.caseMode == CaseMode.NONE) { onChange(style.copy(caseMode = CaseMode.NONE)) }
            OptionChip("AA", style.caseMode == CaseMode.UPPER) { onChange(style.copy(caseMode = CaseMode.UPPER)) }
            OptionChip("aa", style.caseMode == CaseMode.LOWER) { onChange(style.copy(caseMode = CaseMode.LOWER)) }
        }
        if (FontCatalog.face(style.font).hasItalic) ToggleRow("Italic", style.italic) { onChange(style.copy(italic = !style.italic)) } else Text("This font has no italic.", color = C.Muted, fontSize = 11.sp)
        ToggleRow("Underline", style.underline) { onChange(style.copy(underline = !style.underline)) }
        ToggleRow("Strikethrough", style.strike) { onChange(style.copy(strike = !style.strike)) }

        SecTitle("COLOUR")
        ColorRow("Text colour", style.color) { onChange(style.copy(color = it)) }
        StudioSlider("Opacity", style.opacity, 0f..1f, { "${(it * 100).toInt()}%" }) { onChange(style.copy(opacity = it)) }
        ToggleRow("Gradient text", style.gradientEnd != null) { onChange(style.copy(gradientEnd = if (style.gradientEnd == null) 0xFF7C3AED.toInt() else null)) }
        style.gradientEnd?.let { g -> ColorRow("Gradient end colour", g) { onChange(style.copy(gradientEnd = it)) } }

        SecTitle("OUTLINE")
        ColorRow("Outline colour", style.strokeColor) { onChange(style.copy(strokeColor = it)) }
        StudioSlider("Thickness", style.strokeEm, 0f..0.4f, ::em) { onChange(style.copy(strokeEm = it)) }

        SecTitle("SHADOW")
        ColorRow("Shadow colour", style.shadowColor) { onChange(style.copy(shadowColor = it)) }
        StudioSlider("Shadow opacity (0 = no shadow)", style.shadowOpacity, 0f..1f, { "${(it * 100).toInt()}%" }) { onChange(style.copy(shadowOpacity = it)) }
        StudioSlider("Blur", style.shadowBlurEm, 0f..0.5f, ::em) { onChange(style.copy(shadowBlurEm = it)) }
        StudioSlider("Offset X", style.shadowDxEm, -0.5f..0.5f, ::em) { onChange(style.copy(shadowDxEm = it)) }
        StudioSlider("Offset Y", style.shadowDyEm, -0.5f..0.5f, ::em) { onChange(style.copy(shadowDyEm = it)) }

        SecTitle("BACKGROUND BOX")
        ColorRow("Box colour", style.bgColor) { onChange(style.copy(bgColor = it)) }
        StudioSlider("Box opacity (0 = no box)", style.bgOpacity, 0f..1f, { "${(it * 100).toInt()}%" }) { onChange(style.copy(bgOpacity = it)) }
        StudioSlider("Corner radius", style.bgRadiusEm, 0f..1f, ::em) { onChange(style.copy(bgRadiusEm = it)) }
        StudioSlider("Padding", style.paddingEm, 0f..1.5f, ::em) { onChange(style.copy(paddingEm = it)) }

        SecTitle(if (positionControls) "POSITION (you can also drag it in the preview)" else "POSITION")
        StudioSlider("Vertical position", style.y, 0f..1f, { "${(it * 100).toInt()}%" }) { onChange(style.copy(y = it)) }
        if (positionControls) {
            StudioSlider("Horizontal position", style.x, 0f..1f, { "${(it * 100).toInt()}%" }) { onChange(style.copy(x = it)) }
            StudioSlider("Width", style.widthFrac, 0.1f..1f, { "${(it * 100).toInt()}%" }) { onChange(style.copy(widthFrac = it)) }
            StudioSlider("Rotation", style.rotationDeg, -180f..180f, ::deg) { onChange(style.copy(rotationDeg = it)) }
        }

        if (animations) {
            SecTitle("ANIMATION")
            Text("In", color = C.Text, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) { for (k in AnimKind.values().filter { it.allowedIn }) OptionChip(k.label, style.animIn == k) { onChange(style.copy(animIn = k)) } }
            if (style.animIn != AnimKind.NONE) StudioSlider("In duration", style.animInMs.toFloat(), 0f..3000f, ::ms) { onChange(style.copy(animInMs = it.toInt())) }
            Text("Out", color = C.Text, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) { for (k in AnimKind.values().filter { it.allowedOut }) OptionChip(k.label, style.animOut == k) { onChange(style.copy(animOut = k)) } }
            if (style.animOut != AnimKind.NONE) StudioSlider("Out duration", style.animOutMs.toFloat(), 0f..3000f, ::ms) { onChange(style.copy(animOutMs = it.toInt())) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ───────────── the Text Studio sheet ─────────────

@Composable
fun TextStudioSheet(st: AutoState) {
    val tl = st.timeline
    if (tl == null) { Text("Press Analyse first: Text Studio edits the text of a project.", color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(16.dp)); return }
    val sel = st.selectedText()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text Studio", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            BetaTag("text")
            Spacer(Modifier.weight(1f))
            OptionChip("+ Text", false) { st.addText() }
        }
        if (tl.texts.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (t in tl.texts) OptionChip("${t.text.take(14).ifBlank { "(empty)" }} · ${fmt(t.startMs)}", t.id == st.selectedTextId) { st.selectText(t.id) }
        }
        if (sel == null) {
            Text(if (tl.texts.isEmpty()) "No text yet. Tap + Text to add one at the playhead (3 seconds, Subtitle look). Then pick a font, colour, animation or a preset." else "Tap a text above, or tap one in the timeline, to edit it.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            return@Column
        }
        StylePreview(sel.text.ifBlank { "Your text" }, sel.style, st.aspect.ratio, Modifier.padding(vertical = 6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TextInput(sel.id, sel.text, "Type your text") { st.editText(sel.id, text = it) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Starts (m:ss.mmm)", sel.startMs, Modifier.weight(1f)) { st.editText(sel.id, startMs = it) }
                TimeField("Ends", sel.endMs, Modifier.weight(1f)) { st.editText(sel.id, endMs = it) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                OptionChip("Split at playhead", false) { st.splitText(sel.id) }
                OptionChip("Duplicate", false) { st.duplicateText(sel.id) }
                OptionChip("Delete", false) { st.deleteText(sel.id) }
            }
            StyleEditor(sel.style, { st.editText(sel.id, style = it) }, StylePresets.text, st.styleLib, st::saveStyle, st::deleteSavedStyle,
                animations = RenderCaps.available("textAnim"), positionControls = true)
        }
    }
}
