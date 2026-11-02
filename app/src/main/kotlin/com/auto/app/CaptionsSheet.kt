package com.auto.app

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.CaptionTools
import com.auto.core.RenderCaps
import com.auto.core.StylePresets
import com.auto.core.captionLineAt
import com.auto.core.cuesDriveCaptions

/** Step 31: caption cues (import / edit text + times / split / merge / shift / add / delete / export) and the caption style (same editor as Text Studio). */
@Composable
fun CaptionsSheet(st: AutoState) {
    val tl = st.timeline
    if (tl == null) { Text("Press Analyse first: captions belong to a project.", color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(16.dp)); return }
    var tab by remember { mutableIntStateOf(0) }
    val cues = tl.captions.sortedBy { it.startMs }
    val warn = CaptionTools.check(cues)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Captions", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            BetaTag("captionStyle")
            Text(if (st.captionsOn) "  burned in: ON" else "  burned in: OFF", color = C.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f).clickable { st.captionsOn = !st.captionsOn })
        }
        StylePreview(tl.captionLineAt(st.playMs) ?: "Sample caption text", tl.captionStyle, st.aspect.ratio, Modifier.padding(bottom = 6.dp))
        Row { OptionChip("Cues (${cues.size})", tab == 0) { tab = 0 }; OptionChip("Style", tab == 1) { tab = 1 } }
        if (tab == 1) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                StyleEditor(tl.captionStyle, st::setCaptionStyle, StylePresets.caption, st.styleLib, st::saveStyle, st::deleteSavedStyle, animations = false, positionControls = false)
            }
            return@Column
        }
        Text(if (tl.cuesDriveCaptions) "These cues are what is shown and burned into the video." else "No cues yet: the scene text is used as captions. Import an SRT/VTT file or tap From scenes to edit them here.",
            color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            OptionChip("Import SRT/VTT", false) { st.pick(4) }
            if (!tl.cuesDriveCaptions) OptionChip("From scenes", false) { st.captionsFromScenes() }
            OptionChip("+ Add at playhead", false) { st.addCaption() }
            OptionChip("Shift −0.5 s", false) { st.shiftCaptions(-500) }
            OptionChip("Shift +0.5 s", false) { st.shiftCaptions(500) }
            OptionChip("Share SRT", false) { st.shareCaptions(false) }
            OptionChip("Share VTT", false) { st.shareCaptions(true) }
            if (tl.cuesDriveCaptions) OptionChip("Clear all", false) { st.clearCaptions() }
        }
        if (warn.isNotEmpty()) Text(warn.take(4).joinToString("\n") + if (warn.size > 4) "\n… and ${warn.size - 4} more" else "", color = C.Pink, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(cues, key = { it.id }) { c ->
                val shape = RoundedCornerShape(16.dp)
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, C.Line, shape).padding(10.dp)) {
                    TextInput(c.id, c.text, "Caption text") { st.editCaption(c.id, text = it) }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField("Starts", c.startMs, Modifier.weight(1f)) { st.editCaption(c.id, startMs = it) }
                        TimeField("Ends", c.endMs, Modifier.weight(1f)) { st.editCaption(c.id, endMs = it) }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                        OptionChip("Go to", false) { st.seekTo(c.startMs + 1); st.jumpKey++ }
                        OptionChip("Split at playhead", false) { st.splitCaption(c.id) }
                        OptionChip("Merge with next", false) { st.mergeCaption(c.id) }
                        OptionChip("Delete", false) { st.deleteCaption(c.id) }
                    }
                }
            }
        }
    }
}
