package com.auto.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.auto.core.MediaAsset
import com.auto.core.MediaFilter
import com.auto.core.MediaKind
import com.auto.core.MediaLibrary
import com.auto.core.MediaSelection
import com.auto.core.MediaSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Step 35: Media Library. Pictures + audio the app has (project assets and imported files). Videos are listed greyed: this app builds videos from pictures and audio. */
@Composable
fun MediaSheet(st: AutoState) {
    var filter by remember { mutableStateOf(MediaFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(MediaSort.NAME) }
    var asc by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf(MediaSelection()) }
    val all by produceState(emptyList<MediaAsset>(), st.timeline, st.status) { value = withContext(Dispatchers.IO) { st.scanMedia() } }
    val shown = MediaLibrary.sort(MediaLibrary.filter(all, filter, query), sort, asc)
    val counts = MediaLibrary.counts(all)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (f in MediaFilter.values()) OptionChip("${f.label} ${counts[f] ?: 0}", filter == f) { filter = f }
        }
        TextInput("mediaq", query, "Search by name") { query = it }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
            for (s in MediaSort.values()) OptionChip(s.label, sort == s) { if (sort == s) asc = !asc else { sort = s; asc = true } }
            OptionChip(if (asc) "↑ ascending" else "↓ descending", false) { asc = !asc }
        }
        Row {
            OptionChip("Add pictures (ZIP or images)", false) { st.pick(2) }
            OptionChip("Add music", false) { st.pick(5) }
        }
        if (filter == MediaFilter.VIDEOS || (filter == MediaFilter.ALL && all.any { it.kind == MediaKind.VIDEO }))
            Text("Videos: not supported. ${MediaAsset("", "", MediaKind.VIDEO, 0, 0, com.auto.core.MediaOrigin.IMPORTED).unusableReason}", color = C.Muted.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (sel.active) {
            val (ok, bad) = sel.addable(all)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                OptionChip("Select all", false) { sel = sel.selectAll(shown) }
                OptionChip("Clear (${sel.paths.size})", false) { sel = MediaSelection() }
                OptionChip("Add to project", ok.isNotEmpty()) { st.addMediaToProject(ok); if (bad.isNotEmpty()) st.say("Not added: " + bad.joinToString { it.name } + ". " + bad.first().unusableReason); sel = MediaSelection() }
            }
            val one = sel.pick(all).singleOrNull()
            if (one != null) InfoCard(one)
        }
        if (shown.isEmpty()) Text("Nothing here yet. Add pictures or music, or press Analyse to build a project.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.path }) { a ->
                val on = a.path in sel.paths
                val shape = RoundedCornerShape(14.dp)
                Box(Modifier.aspectRatio(1f).clip(shape).background(C.Lane).border(if (on) 2.dp else 1.dp, if (on) C.Cyan else C.Line, shape).clickable { sel = sel.toggle(a.path) }) {
                    if (a.kind == MediaKind.IMAGE) MediaThumb(a.path, Modifier.fillMaxSize())
                    else Text(if (a.kind == MediaKind.AUDIO) "♪" else "▶", color = C.Muted.copy(alpha = if (a.usable) 1f else 0.5f), fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
                    Text(a.name, color = C.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(C.Bg.copy(alpha = 0.7f)).padding(4.dp))
                    if (!a.usable) Text("not supported", color = C.Muted, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                }
            }
        }
    }
}

@Composable
private fun MediaThumb(path: String, modifier: Modifier) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { Pics.load(path, 200) } }
    val b = bmp
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop) else Box(modifier.background(androidx.compose.ui.graphics.Color.Black))
}

@Composable
private fun InfoCard(a: MediaAsset) {
    val full by produceState(a, a.path) {
        value = withContext(Dispatchers.IO) {
            try {
                when (a.kind) {
                    MediaKind.IMAGE -> { val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(a.path, o); if (o.outWidth > 0) a.copy(width = o.outWidth, height = o.outHeight) else a }
                    MediaKind.AUDIO -> AudioDecoder.durationSec(java.io.File(a.path))?.let { a.copy(durationMs = (it * 1000).toLong()) } ?: a
                    else -> a
                }
            } catch (_: Throwable) { a }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(C.Card).padding(12.dp)) {
        for ((k, v) in MediaLibrary.info(full)) Row { Text(k, color = C.Muted, fontSize = 12.sp, modifier = Modifier.size(width = 90.dp, height = 18.dp)); Text(v, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}
