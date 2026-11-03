package com.auto.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.AspectRatio
import com.auto.core.ExportOptions
import com.auto.core.ExportPlan
import com.auto.core.IntermediateFormat
import com.auto.core.RenderCaps
import com.auto.core.Resolution
import com.auto.core.ImageClip
import com.auto.core.MediaAsset
import com.auto.core.MediaKind
import com.auto.core.TimeText
import java.util.Locale
import com.auto.core.UpscaleFactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

const val DP_PER_SEC = 60f

@Composable
fun EditorScreen(st: AutoState) {
    // playback clock: ~30 ticks a second while playing
    LaunchedEffect(st.playing) { while (st.playing) { delay(33); st.tick(33) } }
    BoxWithConstraints(Modifier.fillMaxSize().background(C.Bg)) {
        val wide = maxWidth > maxHeight || maxWidth >= 600.dp   // landscape or tablet: preview left, tools right
        if (wide) Column(Modifier.fillMaxSize()) {
            TopBar(st)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight()) { Stage(st, Modifier.weight(1f)); PlayRow(st) }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    TimelineArea(st)
                    if (st.selectedTextId != null) TextBar(st) else if (st.selectedId != null) ContextBar(st) else MainBar(st)
                }
            }
        } else Column(Modifier.fillMaxSize()) {
            TopBar(st)
            Stage(st, Modifier.weight(1f))
            PlayRow(st)
            TimelineArea(st)
            if (st.selectedTextId != null) TextBar(st) else if (st.selectedId != null) ContextBar(st) else MainBar(st)
        }
        SheetHost(st, wide)
    }
}

// ───────────────────────── top bar ─────────────────────────
@Composable
private fun TopBar(st: AutoState) {
    Row(Modifier.fillMaxWidth().glow(C.Violet, 14.dp, RoundedCornerShape(0.dp)).background(C.Top).statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TopIcon("back", true) { st.onHome() }
        TopIcon("undo", st.canUndo) { st.undo() }
        TopIcon("redo", st.canRedo) { st.redo() }
        Spacer(Modifier.weight(1f))
        TopIcon("scenes", true) { st.sheet = "scenes" }
        TopIcon("warning", true, st.warnings.size) { st.sheet = "warnings" }
        Box(Modifier.padding(start = 4.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(C.Glow).clickable { st.sheet = "export" }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text("Export", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun TopIcon(label: String, enabled: Boolean, badge: Int = 0, onClick: () -> Unit) {
    Box(Modifier.widthIn(min = 48.dp).height(48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AutoIcon(label, Color.White.copy(alpha = if (enabled) 1f else 0.4f), 22.dp)
            if (badge > 0) Text("$badge", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

// ───────────────────────── preview ─────────────────────────
@Composable
private fun Stage(st: AutoState, modifier: Modifier) {
    val tl = st.timeline
    val clip: ImageClip? = tl?.clipAt(st.playMs)
    val bmp by produceState<ImageBitmap?>(null, clip?.image) {
        value = clip?.image?.let { p -> withContext(Dispatchers.IO) { Pics.load(p, 900) } }
    }
    Box(modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(24.dp)
        Box(Modifier.then(if (st.aspect.ratio >= 1f) Modifier.fillMaxWidth() else Modifier.fillMaxHeight()).aspectRatio(st.aspect.ratio).glow(C.Violet, 22.dp, shape).clip(shape).background(C.Screen).border(1.dp, C.Cyan.copy(alpha = 0.4f), shape), contentAlignment = Alignment.Center) {
            val b = bmp
            if (tl == null) {
                Text("Open Inputs to add your files", color = C.Muted, fontSize = 15.sp)
            } else if (b != null) {
                Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = clip?.fx?.takeIf { !it.isIdentity }?.let { androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(it.rgbMatrix().let { m -> floatArrayOf(m[0],m[4],m[8],0f,m[12]*255f, m[1],m[5],m[9],0f,m[13]*255f, m[2],m[6],m[10],0f,m[14]*255f, 0f,0f,0f,1f,0f) })) })
                // captions + text clips are drawn in PreviewOverlays (same renderer as the export)
            } else if (clip != null) {   // no picture: black screen with the scene's text (as in the exported video)
                Text(if (clip.heading) clip.text.uppercase() else clip.text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            if (tl != null) PreviewOverlays(st, showCaption = st.captionsOn && b != null)
        }
    }
}

@Composable
private fun PlayRow(st: AutoState) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(st.playMs), color = C.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Box(Modifier.padding(horizontal = 16.dp).size(56.dp).glow(C.Cyan, 18.dp, CircleShape).background(Brush.linearGradient(listOf(C.Cyan, C.Violet)), CircleShape).clickable { st.togglePlay() }, contentAlignment = Alignment.Center) {
            AutoIcon(if (st.playing) "pause" else "play", Color.White, 24.dp)
        }
        Text(fmt(st.playEndMs()), color = C.Muted, fontSize = 17.sp)
    }
}

// ───────────────────────── timeline (playhead fixed in the middle, tracks scroll under it) ─────────────────────────
@Composable
private fun TimelineArea(st: AutoState) {
    val tl = st.timeline
    val scroll = rememberScrollState()
    val pxSec = with(LocalDensity.current) { DP_PER_SEC.dp.toPx() }
    var userScroll by remember { mutableStateOf(false) }   // true only while the finger (or its fling) moves the timeline, never for our own scrolls
    // while playing the timeline follows the playhead; when the user drags it the playhead follows the timeline
    LaunchedEffect(st.playing, st.playMs) { if (st.playing) scroll.scrollTo((st.playMs / 1000f * pxSec).toInt()) }
    LaunchedEffect(scroll) { scroll.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScroll = true } }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to scroll.value }.collect { (moving, v) ->
            if (userScroll && !st.playing) st.seekTo((v / pxSec * 1000).toLong())
            if (!moving) userScroll = false
        }
    }
    LaunchedEffect(st.jumpKey) { if (!st.playing) scroll.scrollTo((st.playMs / 1000f * pxSec).toInt()) }

    Box(Modifier.fillMaxWidth().height(256.dp).background(C.Bg).border(1.dp, C.Line)) {
        if (tl == null) { Text("The timeline appears after Analyse", color = C.Muted, modifier = Modifier.align(Alignment.Center)); return@Box }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val half = maxWidth / 2
            val viewPx = with(LocalDensity.current) { maxWidth.toPx() }
            val total = max(tl.audioMs, tl.endMs)
            val width = (total / 1000f * DP_PER_SEC).dp
            Row(Modifier.fillMaxSize().horizontalScroll(scroll)) {
                Spacer(Modifier.width(half))
                Box(Modifier.width(width).fillMaxHeight()) {
                    // ruler
                    val labelEvery = when { total > 600_000 -> 60; total > 120_000 -> 15; else -> 5 }
                    for (s in 0..(total / 1000).toInt() step labelEvery) Text("${s}s", color = C.Muted, fontSize = 11.sp, modifier = Modifier.offset(x = (s * DP_PER_SEC).dp - 8.dp, y = 4.dp))
                    // picture track
                    for (c in tl.clips) {
                        val sel = c.id == st.selectedId
                        val shape = RoundedCornerShape(16.dp)
                        Box(Modifier.offset(x = (c.startMs / 1000f * DP_PER_SEC).dp, y = 28.dp).width(max(6f, c.durMs / 1000f * DP_PER_SEC - 3f).dp).height(56.dp)
                            .then(if (sel) Modifier.glow(C.Cyan, 14.dp, shape) else Modifier).clip(shape).background(C.Lane)
                            .then(if (sel) Modifier.border(2.dp, C.Cyan, shape) else Modifier)
                            .clickable { st.selectClip(c.id) }) {
                            Thumb(c.image, Modifier.fillMaxSize())
                            Text("${c.sceneNum}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(6.dp))
                            if (c.hasReplacement) {
                                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(4.dp)).background(C.Violet.copy(alpha = 0.9f)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                    Text("REPL", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    // text track
                    for (c in tl.clips) if (c.text.isNotBlank()) {
                        Box(Modifier.offset(x = (c.startMs / 1000f * DP_PER_SEC).dp, y = 90.dp).width(max(6f, c.durMs / 1000f * DP_PER_SEC - 3f).dp).height(32.dp).clip(RoundedCornerShape(12.dp)).background(C.TextTrack).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(c.text, color = Color(0xFF04243A), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                    // free text clips (Text Studio)
                    for (tc in tl.texts) TextClipBox(tc, st, DP_PER_SEC)
                    // end of the narration: scenes after this line are not exported (the narration is never stretched)
                    if (st.hasNarration()) {
                        val endX = (tl.audioMs / 1000f * DP_PER_SEC).dp
                        if (total > tl.audioMs) Box(Modifier.offset(x = endX).width(((total - tl.audioMs) / 1000f * DP_PER_SEC).dp).fillMaxHeight().background(Color(0xAA060B1E)))
                        Box(Modifier.offset(x = endX).width(2.dp).fillMaxHeight().background(C.Pink))
                        Text(if (total > tl.audioMs) "end of audio · scenes after it are not exported" else "end of audio", color = C.Pink, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = endX + 5.dp, y = 210.dp))
                    }
                    // narration track (real waveform from the decoded narration; plain decorative wave until it is ready)
                    if (tl.audioMs > 0) Box(Modifier.offset(y = 162.dp).width((tl.audioMs / 1000f * DP_PER_SEC).dp).height(44.dp).clip(RoundedCornerShape(16.dp)).background(C.Wave)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val from = scroll.value.toFloat() - 40f; val to = from + viewPx + 80f
                            var i = max(0, (from / 9f).toInt())
                            while (i * 9f < to && i * 9f < size.width) {
                                val pk = st.peaks; val h = (if (pk != null && pk.isNotEmpty()) 0.08f + 0.9f * pk[((i * 9f) / pxSec * PEAKS_PER_SEC).toInt().coerceIn(0, pk.size - 1)] else 0.2f + 0.7f * abs(sin(i * 0.7f) * cos(i * 0.23f))) * size.height
                                drawLine(Color(0xFFE0F2FE), Offset(i * 9f + 4f, (size.height - h) / 2), Offset(i * 9f + 4f, (size.height + h) / 2), strokeWidth = 3f)
                                i++
                            }
                        }
                    }
                }
                Spacer(Modifier.width(half))
            }
            // fixed playhead
            Box(Modifier.align(Alignment.TopCenter).offset(y = 22.dp).width(3.dp).fillMaxHeight().glow(C.Cyan, 10.dp, RoundedCornerShape(2.dp)).background(Color.White))
        }
        // floating +
        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(48.dp).glow(C.Violet, 16.dp, CircleShape).background(C.Glow, CircleShape).clickable { st.sheet = "pictures" }, contentAlignment = Alignment.Center) {
            AutoIcon("plus", Color.White, 24.dp)
        }
    }
}

@Composable
private fun Thumb(path: String?, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, path) { value = path?.let { p -> withContext(Dispatchers.IO) { Pics.load(p, 200) } } }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color.Black))
}

// ───────────────────────── bottom bars ─────────────────────────
@Composable
private fun MainBar(st: AutoState) {
    Row(Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp)) {
        Tab("import", "Inputs", st.sheet == "inputs") { st.sheet = "inputs" }
        Tab("scenes", "Scenes", st.sheet == "scenes") { st.sheet = "scenes" }
        Tab("media", "Media", st.sheet == "pictures") { st.sheet = "pictures" }
        Tab("warning", "Warnings", st.sheet == "warnings") { st.sheet = "warnings" }
        Tab("text", "Text", st.sheet == "text") { st.sheet = "text" }
        Tab("captions", "Captions", st.sheet == "captions") { st.sheet = "captions" }
        Tab("music", "Audio", st.sheet == "audio") { st.sheet = "audio" }
        Tab("cc", if (st.captionsOn) "CC on" else "CC off", st.captionsOn) { st.captionsOn = !st.captionsOn; st.persistExportSettings() }
        Tab("export", "Export", st.sheet == "export") { st.sheet = "export" }
    }
}

@Composable
private fun TextBar(st: AutoState) {
    val id = st.selectedTextId ?: return
    Row(Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp)) {
        Tab("close", "Done", false) { st.selectText(null) }
        Tab("text", "Edit", false) { st.sheet = "text" }
        Tab("split", "Split", false) { st.splitText(id) }
        Tab("duplicate", "Duplicate", false) { st.duplicateText(id) }
        Tab("delete", "Delete", false) { st.deleteText(id) }
    }
}

@Composable
private fun ContextBar(st: AutoState) {
    val clip = st.selectedClip()
    Row(Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp)) {
        Tab("close", "Done", false) { st.selectedId = null }
        Tab("split", "Split", false) { st.split() }
        Tab("minus", "−1 s", false) { st.addLength(-1000) }
        Tab("plus", "+1 s", false) { st.addLength(1000) }
        Tab("length", "Length", false) { st.sheet = "length" }
        Tab("effects", "Effects", st.sheet == "effects") { st.sheet = "effects" }
        Tab("replace", "Replace", st.sheet == "replace") { st.openReplace() }
        if (clip?.hasReplacement == true) {
            Tab("delete", "Delete Replacement", false) { st.deleteReplacement() }
        }
        Tab("delete", "Delete", false) { st.delete() }
    }
}

@Composable
private fun Tab(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).widthIn(min = 72.dp).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).then(if (active) Modifier.glow(C.Cyan, 12.dp, CircleShape) else Modifier)
            .background(if (active) C.Glow else SolidColor(C.Lane), CircleShape), contentAlignment = Alignment.Center) {
            AutoIcon(icon, if (active) Color.White else C.Text, 22.dp)
        }
        Text(label, color = if (active) C.Cyan else C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}

// ───────────────────────── bottom sheets ─────────────────────────
@Composable
private fun BoxScope.SheetHost(st: AutoState, wide: Boolean) {
    val open = st.sheet != null
    val shown = remember { mutableStateOf("inputs") }
    st.sheet?.let { shown.value = it }
    AnimatedVisibility(open && !wide, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { st.sheet = null })
    }
    AnimatedVisibility(open, modifier = (if (wide) Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f).fillMaxHeight(0.96f) else Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.82f)), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        Column(Modifier.fillMaxSize().glow(C.Violet, 24.dp, shape).clip(shape).background(C.Card).border(1.dp, C.Cyan.copy(alpha = 0.45f), shape).navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            Box(Modifier.padding(vertical = 10.dp).align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Line).clickable { st.sheet = null })
            when (shown.value) {
                "inputs" -> InputsSheet(st)
                "scenes" -> ScenesSheet(st)
                "pictures" -> MediaSheet(st)
                "warnings" -> WarningsSheet(st)
                "export" -> ExportSheet(st)
                "length" -> LengthSheet(st)
                "text" -> TextStudioSheet(st)
                "captions" -> CaptionsSheet(st)
                "effects" -> EffectsSheet(st)
                "audio" -> AudioSheet(st)
                "replace" -> ReplaceSheet(st)
            }
        }
    }
}

@Composable
fun GlowButton(text: String, enabled: Boolean = true, outline: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).then(if (!outline && enabled) Modifier.glow(C.Violet, 16.dp, shape) else Modifier)
        .clip(shape).then(if (outline) Modifier.border(1.dp, C.Line, shape) else Modifier.background(if (enabled) C.Glow else SolidColor(C.Lane)))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else C.Muted.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SearchBox(value: String, hint: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).then(if (focused) Modifier.glow(C.Cyan, 16.dp, shape) else Modifier).border(1.dp, if (focused) C.Cyan else C.Line, shape).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AutoIcon("analyse", C.Muted, 20.dp)
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (value.isEmpty()) Text(hint, color = C.Muted, fontSize = 15.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 16.sp), cursorBrush = SolidColor(C.Cyan), modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.padding(end = 8.dp, bottom = 8.dp).height(40.dp).clip(shape).background(if (on) C.Cyan.copy(alpha = 0.15f) else Color.Transparent)
        .border(1.dp, if (on) C.Cyan else C.Line, shape).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(if (on) "✓ $label" else label, color = if (on) C.Cyan else C.Muted, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun InputsSheetBody(st: AutoState) {
    Column(Modifier.fillMaxWidth()) {
        Text("Inputs", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        GlowButton("Choose files or one ZIP") { st.pick(1) }
        GlowButton("Choose pictures (ZIP or images)") { st.pick(2) }
        GlowButton(if (st.busy) "Analysing…" else "Analyse", enabled = !st.busy, outline = true) { st.analyse() }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { Chip("Timestamps", "Timestamps" in st.kinds); Chip("Audio", "Audio" in st.kinds); Chip("Pictures", "Pictures" in st.kinds); Chip("Script", "Script" in st.kinds) }
        Text("All inputs are optional.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text(st.status, color = C.Text, fontSize = 14.sp)
    }
}

@Composable
private fun ScenesSheet(st: AutoState) {
    var q by remember { mutableStateOf("") }
    val clips = st.timeline?.clips.orEmpty().filter { q.isBlank() || it.text.contains(q, true) || "${it.sceneNum}" == q.trim() }
    Column(Modifier.fillMaxSize()) {
        SearchBox(q, "Search scenes") { q = it }
        Text("${clips.size} scenes", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(clips, key = { it.id }) { c ->
                val shape = RoundedCornerShape(20.dp)
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).border(1.dp, C.Line, shape).clickable { st.sheet = null; st.selectClip(c.id) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(c.image, Modifier.width(84.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Scene ${c.sceneNum} · ${fmt(c.startMs)}–${fmt(c.endMs)}", color = C.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(c.text.ifBlank { "(no text)" }, color = C.Text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PicturesSheet(st: AutoState) {
    val clips = st.timeline?.clips.orEmpty()
    Column(Modifier.fillMaxSize()) {
        GlowButton("Choose pictures (ZIP or images)") { st.pick(2) }
        Text("Pictures in this project", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(clips, key = { it.id }) { c ->
                Thumb(c.image, Modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).clickable { st.sheet = null; st.selectClip(c.id) })
            }
        }
    }
}

@Composable
private fun WarningsSheetBody(st: AutoState) {
    Column(Modifier.fillMaxWidth()) {
        Text("Warnings", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        if (st.warnings.isEmpty()) Text("Nothing to check.", color = C.Muted)
        Column {
            for (w in st.warnings) run {
                val shape = RoundedCornerShape(20.dp)
                Text(w, color = C.Text, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).border(1.dp, C.Line, shape).padding(12.dp))
            }
        }
    }
}

@Composable
fun OptionChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.padding(end = 8.dp, bottom = 8.dp).then(if (on) Modifier.glow(C.Cyan, 10.dp, shape) else Modifier).clip(shape).background(if (on) C.Cyan.copy(alpha = 0.15f) else Color.Transparent)
        .border(1.dp, if (on) C.Cyan else C.Line, shape).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) C.Cyan else C.Text, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
    }
}

@Composable
fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Box(Modifier.width(54.dp).height(30.dp).clip(RoundedCornerShape(50)).background(if (on) C.Glow else SolidColor(C.Lane)).padding(3.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
        }
        Text(if (on) "ON" else "OFF", color = if (on) C.Cyan else C.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(40.dp).padding(start = 8.dp))
    }
}

@Composable
private fun ExportSheet(st: AutoState) {
    val busy = st.exportPct != null
    val s = ExportOptions.settings(Resolution.of(st.resP), st.aspect, st.fps)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Export MP4", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        Text("Resolution", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row {
            OptionChip("720p", st.resP == 720) { if (!busy) { st.resP = 720; st.persistExportSettings() } }
            OptionChip("1080p", st.resP == 1080) { if (!busy) { st.resP = 1080; st.persistExportSettings() } }
            OptionChip("4K", st.resP == 2160) { if (!busy) { st.resP = 2160; st.persistExportSettings() } }
        }
        Text("Aspect ratio", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row { for (a in AspectRatio.values()) OptionChip(a.label, st.aspect == a) { if (!busy) { st.aspect = a; st.persistExportSettings() } } }
        Text("Pictures that do not match the shape are scaled to fit, with black bars.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text("Frame rate", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row {
            OptionChip("30 fps", st.fps == 30) { if (!busy) { st.fps = 30; st.persistExportSettings() } }
            OptionChip("60 fps", st.fps == 60) { if (!busy) { st.fps = 60; st.persistExportSettings() } }
        }
        Text("AI Image Upscaling", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row {
            for (u in UpscaleFactor.values()) {
                OptionChip(u.label, st.upscale == u) { if (!busy) { st.upscale = u; st.persistExportSettings() } }
            }
        }
        Text("Intermediate Image Format", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row {
            for (fmt in IntermediateFormat.values()) {
                OptionChip(fmt.label, st.upscaleFormat == fmt) { if (!busy) { st.upscaleFormat = fmt; st.persistExportSettings() } }
            }
        }
        ToggleRow("Captions on the video", st.captionsOn) { if (!busy) { st.captionsOn = !st.captionsOn; st.persistExportSettings() } }
        val upInfo = if (st.upscale == UpscaleFactor.OFF) "upscale off" else "upscale ${st.upscale.label} (${st.upscaleFormat.shortLabel})"
        Text("${s.width}×${s.height} · ${s.fps} fps · $upInfo · captions ${if (st.captionsOn) "on" else "off"} · ${fmt(st.playEndMs())} long (same as the narration)", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (st.resP == 2160 || st.fps == 60) Text("4K and 60 fps need a strong phone and take much longer. If the export fails, try 1080p / 30 fps.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        StageChip(st)
        val tlx = st.timeline
        if (tlx != null) {
            val notes = RenderCaps.notes(ExportPlan.fromTimeline(tlx, clampToAudio = st.hasNarration()), st.captionsOn)
            if (notes.isNotEmpty()) { Text("What this export uses", color = C.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)); for (n in notes) Text("• $n", color = C.Muted, fontSize = 12.sp) }
        }
        Text("The video is saved in Movies/Auto on this phone (Android 10 or newer).", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        GlowButton(if (st.timeline == null) "Start export (Press Analyse first)" else "Start export", enabled = st.timeline != null && !busy) { st.export() }
        if (st.timeline == null) Text("Open Inputs, add your files and press Analyse. Then you can export.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (busy) GlowButton("Cancel export", outline = true) { st.cancelExport() }
        if (st.lastExportUri != null && !busy) GlowButton("Share video", outline = true) { st.shareExport() }
        val p = st.exportPct
        if (p != null) Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Lane)) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0, 100) / 100f).fillMaxHeight().background(C.Glow))
        }
        if (st.status.startsWith("Export") || st.status.startsWith("Upscaling") || st.status.startsWith("Done")) Text(st.status, color = C.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Set the selected scene to any length. The scenes after it move; the narration does not (scenes after its end are not exported). */
@Composable
private fun LengthSheet(st: AutoState) {
    val tl = st.timeline; val c = st.selectedClip()
    if (tl == null || c == null) { Text("Tap a scene on the timeline first, then open Length.", color = C.Muted, fontSize = 15.sp); return }
    var text by remember(c.id, c.durMs) { mutableStateOf(TimeText.format(c.durMs)) }
    val typed = TimeText.parse(text)
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Scene ${c.sceneNum} length", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("starts ${TimeText.format(c.startMs)} · ends ${TimeText.format(c.endMs)}", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Row { OptionChip("−10 s", false) { st.addLength(-10_000) }; OptionChip("−1 s", false) { st.addLength(-1_000) }; OptionChip("+1 s", false) { st.addLength(1_000) }; OptionChip("+10 s", false) { st.addLength(10_000) }; OptionChip("+1 min", false) { st.addLength(60_000) } }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, if (typed == null) C.Pink else C.Cyan, shape).padding(horizontal = 16.dp, vertical = 14.dp)) {
            BasicTextField(text, { text = it }, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold), cursorBrush = SolidColor(C.Cyan), modifier = Modifier.fillMaxWidth())
        }
        Text(if (typed == null) "Not a time. Type seconds (90), minutes:seconds (1:30) or 10m." else "Type seconds (90), minutes:seconds (1:30) or 10m. Any length works.", color = if (typed == null) C.Pink else C.Muted, fontSize = 13.sp)
        Text("The scenes after this one move later or earlier. The narration stays exactly as it is.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        if (st.hasNarration() && c.endMs > tl.audioMs) Text("This scene ends ${TimeText.format(c.endMs - tl.audioMs)} after the narration ends. Everything after the narration is not exported.", color = C.Pink, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        Spacer(Modifier.height(4.dp))
        GlowButton("Apply", enabled = typed != null) { typed?.let { st.setLength(it) }; st.sheet = null }
    }
}

@Composable
private fun InputsSheet(st: AutoState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { StageChip(st); AnalysisCards(st); InputsSheetBody(st) }
}

@Composable
private fun WarningsSheet(st: AutoState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { StageChip(st); AnalysisCards(st); WarningsSheetBody(st) }
}

@Composable
private fun ReplaceSheet(st: AutoState) {
    val clip = st.selectedClip()
    if (clip == null) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("No scene selected", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Select a scene on the timeline to replace its image.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            GlowButton("Close") { st.sheet = null }
        }
        return
    }

    val origPath = clip.originalImage ?: clip.image
    val pendingPath = st.pendingReplacementPath
    val activeReplacementPath = clip.replacementImage
    val displayedOverlayPath = pendingPath ?: activeReplacementPath

    val origBmp by produceState<ImageBitmap?>(null, origPath) {
        value = origPath?.let { p -> withContext(Dispatchers.IO) { Pics.load(p, 900) } }
    }
    val replBmp by produceState<ImageBitmap?>(null, displayedOverlayPath) {
        value = displayedOverlayPath?.let { p -> withContext(Dispatchers.IO) { Pics.load(p, 900) } }
    }

    var query by remember { mutableStateOf("") }
    var mediaList by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }

    LaunchedEffect(Unit) {
        val all = withContext(Dispatchers.IO) { st.scanMedia().filter { it.kind == MediaKind.IMAGE } }
        mediaList = all
    }

    val shownMedia = remember(mediaList, query) {
        if (query.isBlank()) mediaList
        else mediaList.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        // Title & scene info header
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Replace Scene ${clip.sceneNum}", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (clip.hasReplacement) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.Violet).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("Replaced", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                val durSec = String.format(Locale.ROOT, "%.2f", clip.durMs / 1000f)
                Text("${fmt(clip.startMs)} – ${fmt(clip.endMs)} (${durSec}s) · Duration preserved", color = C.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(C.Lane).clickable { st.cancelReplace() }, contentAlignment = Alignment.Center) {
                AutoIcon("close", C.Muted, 18.dp)
            }
        }

        // Preview Area with Non-Destructive Overlay
        val previewShape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 210.dp)
                .aspectRatio(st.aspect.ratio.coerceIn(0.5f, 2.2f))
                .align(Alignment.CenterHorizontally)
                .glow(C.Violet, 12.dp, previewShape)
                .clip(previewShape)
                .background(C.Screen)
                .border(1.dp, C.Cyan.copy(alpha = 0.4f), previewShape),
            contentAlignment = Alignment.Center
        ) {
            // Base layer: Original scene image
            val ob = origBmp
            if (ob != null) {
                Image(ob, contentDescription = "Original scene image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (clip.text.isNotBlank()) {
                Text(if (clip.heading) clip.text.uppercase() else clip.text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            } else {
                Text("No original image", color = C.Muted, fontSize = 14.sp)
            }

            // Overlay layer: Replacement image
            val rb = replBmp
            if (rb != null) {
                Image(rb, contentDescription = "Replacement image overlay", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (pendingPath != null) C.Cyan.copy(alpha = 0.9f) else C.Violet.copy(alpha = 0.9f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (pendingPath != null) "New Replacement Overlay" else "Current Replacement Overlay",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Original Image", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        // Action controls
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.weight(1f)) {
                GlowButton("Cancel", outline = true) { st.cancelReplace() }
            }
            if (clip.hasReplacement) {
                Box(Modifier.weight(1.2f)) {
                    GlowButton("Delete Replacement", outline = true) {
                        st.deleteReplacement(clip.id)
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                GlowButton("Confirm", enabled = pendingPath != null) {
                    if (pendingPath != null) st.confirmReplacement(clip.id, pendingPath)
                }
            }
        }

        // In-app media library selection
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Select from Media Library", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("${shownMedia.size} images", color = C.Muted, fontSize = 12.sp)
        }
        SearchBox(query, "Search images by name") { query = it }

        if (shownMedia.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (mediaList.isEmpty()) "No images in the media library.\nAdd pictures via Inputs tab to use them for replacement."
                    else if (query.isNotBlank()) "No images match \"$query\"."
                    else "No images available.", 
                    color = C.Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(3),
                Modifier.weight(1f).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shownMedia, key = { it.path }) { asset ->
                    val isSelected = asset.path == (pendingPath ?: if (pendingPath == null && clip.replacementImage == asset.path) clip.replacementImage else null)
                    val cardShape = RoundedCornerShape(12.dp)
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(cardShape)
                            .background(C.Lane)
                            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) C.Cyan else C.Line, cardShape)
                            .clickable {
                                st.pendingReplacementPath = asset.path
                            }
                    ) {
                        Thumb(asset.path, Modifier.fillMaxSize())
                        if (isSelected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(C.Cyan),
                                contentAlignment = Alignment.Center
                            ) {
                                AutoIcon("check", Color.White, 14.dp)
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(Color(0xCC000000))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(asset.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
