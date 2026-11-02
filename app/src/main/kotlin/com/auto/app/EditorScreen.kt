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
import com.auto.core.ExportSettings
import com.auto.core.ImageClip
import com.auto.core.TimeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val DP_PER_SEC = 60f

@Composable
fun EditorScreen(st: AutoState) {
    // playback clock: ~30 ticks a second while playing
    LaunchedEffect(st.playing) { while (st.playing) { delay(33); st.tick(33) } }
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(st)
            Stage(st, Modifier.weight(1f))
            PlayRow(st)
            TimelineArea(st)
            if (st.selectedId != null) ContextBar(st) else MainBar(st)
        }
        SheetHost(st)
    }
}

// ───────────────────────── top bar ─────────────────────────
@Composable
private fun TopBar(st: AutoState) {
    Row(Modifier.fillMaxWidth().glow(C.Violet, 14.dp, RoundedCornerShape(0.dp)).background(C.Top).statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TopIcon("↶", st.canUndo) { st.undo() }
        TopIcon("↷", st.canRedo) { st.redo() }
        Spacer(Modifier.weight(1f))
        TopIcon("▤", true) { st.sheet = "scenes" }
        TopIcon(if (st.warnings.isEmpty()) "⚠" else "⚠ ${st.warnings.size}", true) { st.sheet = "warnings" }
        Box(Modifier.padding(start = 4.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable { st.sheet = "export" }.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Export", color = Color(0xFF2A1B8A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun TopIcon(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.widthIn(min = 40.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 20.sp)
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
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).glow(C.Violet, 22.dp, shape).clip(shape).background(C.Screen).border(1.dp, C.Cyan.copy(alpha = 0.4f), shape), contentAlignment = Alignment.Center) {
            val b = bmp
            if (tl == null) {
                Text("Open Inputs to add your files", color = C.Muted, fontSize = 15.sp)
            } else if (b != null) {
                Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                if (st.captionsOn && clip != null && clip.text.isNotBlank()) Text(clip.text, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 2f), 6f)), modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 14.dp))
            } else if (clip != null) {   // no picture: black screen with the scene's text (as in the exported video)
                Text(if (clip.heading) clip.text.uppercase() else clip.text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
        }
    }
}

@Composable
private fun PlayRow(st: AutoState) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(fmt(st.playMs), color = C.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Box(Modifier.padding(horizontal = 16.dp).size(52.dp).glow(C.Cyan, 18.dp, CircleShape).background(Brush.linearGradient(listOf(C.Cyan, C.Violet)), CircleShape).clickable { st.togglePlay() }, contentAlignment = Alignment.Center) {
            Text(if (st.playing) "❚❚" else "▶", color = Color.White, fontSize = 18.sp)
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

    Box(Modifier.fillMaxWidth().height(224.dp).background(C.Bg).border(1.dp, C.Line)) {
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
                        }
                    }
                    // text track
                    for (c in tl.clips) if (c.text.isNotBlank()) {
                        Box(Modifier.offset(x = (c.startMs / 1000f * DP_PER_SEC).dp, y = 90.dp).width(max(6f, c.durMs / 1000f * DP_PER_SEC - 3f).dp).height(32.dp).clip(RoundedCornerShape(12.dp)).background(C.TextTrack).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                            Text(c.text, color = Color(0xFF04243A), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip)
                        }
                    }
                    // end of the narration: scenes after this line are not exported (the narration is never stretched)
                    if (st.hasNarration()) {
                        val endX = (tl.audioMs / 1000f * DP_PER_SEC).dp
                        if (total > tl.audioMs) Box(Modifier.offset(x = endX).width(((total - tl.audioMs) / 1000f * DP_PER_SEC).dp).fillMaxHeight().background(Color(0xAA060B1E)))
                        Box(Modifier.offset(x = endX).width(2.dp).fillMaxHeight().background(C.Pink))
                        Text(if (total > tl.audioMs) "end of audio · scenes after it are not exported" else "end of audio", color = C.Pink, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = endX + 5.dp, y = 176.dp))
                    }
                    // narration track (decorative wave: only the visible part is drawn)
                    if (tl.audioMs > 0) Box(Modifier.offset(y = 128.dp).width((tl.audioMs / 1000f * DP_PER_SEC).dp).height(44.dp).clip(RoundedCornerShape(16.dp)).background(C.Wave)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val from = scroll.value.toFloat() - 40f; val to = from + viewPx + 80f
                            var i = max(0, (from / 9f).toInt())
                            while (i * 9f < to && i * 9f < size.width) {
                                val h = (0.2f + 0.7f * abs(sin(i * 0.7f) * cos(i * 0.23f))) * size.height
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
        Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).size(44.dp).glow(C.Violet, 16.dp, CircleShape).background(C.Glow, CircleShape).clickable { st.sheet = "pictures" }, contentAlignment = Alignment.Center) {
            Text("+", color = Color.White, fontSize = 24.sp)
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
        Tab("⤒", "Inputs", st.sheet == "inputs") { st.sheet = "inputs" }
        Tab("▦", "Scenes", st.sheet == "scenes") { st.sheet = "scenes" }
        Tab("▣", "Pictures", st.sheet == "pictures") { st.sheet = "pictures" }
        Tab("⚠", "Warnings", st.sheet == "warnings") { st.sheet = "warnings" }
        Tab("CC", "Captions", st.captionsOn) { st.captionsOn = !st.captionsOn }
        Tab("⬆", "Export", st.sheet == "export") { st.sheet = "export" }
    }
}

@Composable
private fun ContextBar(st: AutoState) {
    Row(Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp)) {
        Tab("✕", "Done", false) { st.selectedId = null }
        Tab("✂", "Split", false) { st.split() }
        Tab("−", "−1 s", false) { st.addLength(-1000) }
        Tab("+", "+1 s", false) { st.addLength(1000) }
        Tab("⏱", "Length", false) { st.sheet = "length" }
        Tab("⇄", "Replace", false) { st.pick(3) }
        Tab("🗑", "Delete", false) { st.delete() }
    }
}

@Composable
private fun Tab(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).widthIn(min = 70.dp).padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).then(if (active) Modifier.glow(C.Cyan, 12.dp, CircleShape) else Modifier)
            .background(if (active) C.Glow else SolidColor(C.Lane), CircleShape), contentAlignment = Alignment.Center) {
            Text(icon, color = if (active) Color.White else C.Text, fontSize = 19.sp)
        }
        Text(label, color = if (active) C.Cyan else C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
    }
}

// ───────────────────────── bottom sheets ─────────────────────────
@Composable
private fun BoxScope.SheetHost(st: AutoState) {
    val open = st.sheet != null
    val shown = remember { mutableStateOf("inputs") }
    st.sheet?.let { shown.value = it }
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { st.sheet = null })
    }
    AnimatedVisibility(open, modifier = Modifier.align(Alignment.BottomCenter).fillMaxHeight(0.82f), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        Column(Modifier.fillMaxSize().glow(C.Violet, 24.dp, shape).clip(shape).background(C.Card).border(1.dp, C.Cyan.copy(alpha = 0.45f), shape).navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            Box(Modifier.padding(vertical = 10.dp).align(Alignment.CenterHorizontally).width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Line).clickable { st.sheet = null })
            when (shown.value) {
                "inputs" -> InputsSheet(st)
                "scenes" -> ScenesSheet(st)
                "pictures" -> PicturesSheet(st)
                "warnings" -> WarningsSheet(st)
                "export" -> ExportSheet(st)
                "length" -> LengthSheet(st)
            }
        }
    }
}

@Composable
private fun GlowButton(text: String, enabled: Boolean = true, outline: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp).then(if (!outline && enabled) Modifier.glow(C.Violet, 16.dp, shape) else Modifier)
        .clip(shape).then(if (outline) Modifier.border(1.dp, C.Line, shape) else Modifier.background(if (enabled) C.Glow else SolidColor(C.Lane)))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else C.Muted, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun SearchBox(value: String, hint: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).then(if (focused) Modifier.glow(C.Cyan, 16.dp, shape) else Modifier).border(1.dp, if (focused) C.Cyan else C.Line, shape).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⌕", color = C.Muted, fontSize = 18.sp)
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (value.isEmpty()) Text(hint, color = C.Muted, fontSize = 15.sp)
            BasicTextField(value, onChange, singleLine = true, textStyle = TextStyle(color = C.Text, fontSize = 16.sp), cursorBrush = SolidColor(C.Cyan), modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.padding(end = 8.dp, bottom = 8.dp).then(if (on) Modifier.glow(C.Cyan, 10.dp, shape) else Modifier).clip(shape).background(if (on) C.Cyan.copy(alpha = 0.15f) else Color.Transparent)
        .border(1.dp, if (on) C.Cyan else C.Line, shape).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(if (on) "✓ $label" else label, color = if (on) C.Cyan else C.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun InputsSheet(st: AutoState) {
    Column(Modifier.fillMaxSize()) {
        Text("Inputs", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        GlowButton("Choose files or one ZIP") { st.pick(1) }
        GlowButton("Choose pictures (ZIP or images)") { st.pick(2) }
        GlowButton(if (st.busy) "Analysing…" else "Analyse", enabled = !st.busy, outline = true) { st.analyse() }
        Row { Chip("Timestamps", "Timestamps" in st.kinds); Chip("Audio", "Audio" in st.kinds); Chip("Pictures", "Pictures" in st.kinds); Chip("Script", "Script" in st.kinds) }
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
private fun WarningsSheet(st: AutoState) {
    Column(Modifier.fillMaxSize()) {
        Text("Warnings", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        if (st.warnings.isEmpty()) Text("Nothing to check.", color = C.Muted)
        LazyColumn(Modifier.weight(1f)) {
            items(st.warnings) { w ->
                val shape = RoundedCornerShape(20.dp)
                Text("⚠ $w", color = C.Text, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(shape).border(1.dp, C.Line, shape).padding(12.dp))
            }
        }
    }
}

@Composable
private fun OptionChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.padding(end = 8.dp, bottom = 8.dp).then(if (on) Modifier.glow(C.Cyan, 10.dp, shape) else Modifier).clip(shape).background(if (on) C.Cyan.copy(alpha = 0.15f) else Color.Transparent)
        .border(1.dp, if (on) C.Cyan else C.Line, shape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, color = if (on) C.Cyan else C.Text, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
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
    val s = ExportSettings.preset(st.resP, st.fps)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Export MP4", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        Text("Resolution", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row { OptionChip("720p", st.resP == 720) { st.resP = 720 }; OptionChip("1080p", st.resP == 1080) { st.resP = 1080 }; OptionChip("4K", st.resP == 2160) { st.resP = 2160 } }
        Text("Frame rate", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row { OptionChip("30 fps", st.fps == 30) { st.fps = 30 }; OptionChip("60 fps", st.fps == 60) { st.fps = 60 } }
        ToggleRow("Captions on the video", st.captionsOn) { st.captionsOn = !st.captionsOn }
        Text("${s.width}×${s.height} · ${s.fps} fps · captions ${if (st.captionsOn) "on" else "off"} · ${fmt(st.playEndMs())} long (same as the narration)", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (st.resP == 2160 || st.fps == 60) Text("4K and 60 fps need a strong phone and take much longer. If the export fails, try 1080p / 30 fps.", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        GlowButton("Start export", enabled = st.timeline != null && !busy) { st.export() }
        val p = st.exportPct
        if (p != null) Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Lane)) {
            Box(Modifier.fillMaxWidth(p.coerceIn(0, 100) / 100f).fillMaxHeight().background(C.Glow))
        }
        Text(st.status, color = C.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
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
