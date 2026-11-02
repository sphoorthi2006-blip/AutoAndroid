package com.auto.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.AudioSettings
import com.auto.core.RenderCaps
import java.util.Locale

/** Step 34: narration volume / fades / mute and the music bed. Rules shown to the person: narration is never trimmed or offset, music starts at 0. */
@Composable
fun AudioSheet(st: AutoState) {
    val tl = st.timeline
    if (tl == null) { Text("Press Analyse first: audio belongs to a project.", color = C.Muted, fontSize = 15.sp, modifier = Modifier.padding(16.dp)); return }
    val mix = tl.mix
    fun pc(v: Float) = "${(v * 100).toInt()}%"
    fun sec(ms: Int) = String.format(Locale.ROOT, "%.1f s", ms / 1000f)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("The narration is never trimmed or moved: the video is exactly as long as the narration. Music starts at 0.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Row { SecTitle("Click sound"); if (RenderCaps.available("click")) BetaTag("click") }
        val ck = mix.click
        Text("A short click at the start of every picture, including 0:00. It is built into the app. ${st.clickCount()} clicks in this project; they follow the pictures when you move or trim them.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        ToggleRow("Click at every picture change", ck.enabled) { st.setClick(ck.copy(enabled = !ck.enabled)) }
        if (ck.enabled) {
            StudioSlider("Click volume", ck.volume, 0f..1f, ::pc) { st.setClick(ck.copy(volume = it)) }
            GlowButton("Hear the click", outline = true) { st.hearClick() }
            if (!st.hasNarration()) Text("No narration: the exported video gets a silent audio track so the clicks are heard.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
        Row { SecTitle("Narration"); if (RenderCaps.available("volume")) BetaTag("volume") }
        if (!st.hasNarration()) Text("This project has no narration audio.", color = C.Muted, fontSize = 14.sp)
        else {
            val n = mix.narration
            ToggleRow("Mute narration", n.muted) { st.setNarrationAudio(n.copy(muted = !n.muted)) }
            StudioSlider("Volume", n.volume, 0f..2f, ::pc) { st.setNarrationAudio(n.copy(volume = it)) }
            StudioSlider("Fade in", n.fadeInMs / 1000f, 0f..10f, { sec((it * 1000).toInt()) }) { st.setNarrationAudio(n.copy(fadeInMs = (it * 1000).toInt())) }
            StudioSlider("Fade out", n.fadeOutMs / 1000f, 0f..10f, { sec((it * 1000).toInt()) }) { st.setNarrationAudio(n.copy(fadeOutMs = (it * 1000).toInt())) }
            GlowButton("Reset narration", enabled = !n.isIdentity, outline = true) { st.setNarrationAudio(AudioSettings()) }
        }
        Row { SecTitle("Music"); if (RenderCaps.available("music")) BetaTag("music") }
        val m = mix.music
        if (m == null) {
            Text("No music. Add an audio file; it plays under the narration from the start.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            GlowButton("Add music file") { st.pick(5) }
        } else {
            Text(m.name, color = C.Text, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
            val s = m.settings
            ToggleRow("Mute music", s.muted) { st.setMusic(m.copy(settings = s.copy(muted = !s.muted))) }
            ToggleRow("Loop until the video ends", m.loop) { st.setMusic(m.copy(loop = !m.loop)) }
            StudioSlider("Volume", s.volume, 0f..2f, ::pc) { st.setMusic(m.copy(settings = s.copy(volume = it))) }
            StudioSlider("Fade in", s.fadeInMs / 1000f, 0f..10f, { sec((it * 1000).toInt()) }) { st.setMusic(m.copy(settings = s.copy(fadeInMs = (it * 1000).toInt()))) }
            StudioSlider("Fade out", s.fadeOutMs / 1000f, 0f..10f, { sec((it * 1000).toInt()) }) { st.setMusic(m.copy(settings = s.copy(fadeOutMs = (it * 1000).toInt()))) }
            GlowButton("Change music file", outline = true) { st.pick(5) }
            GlowButton("Remove music", outline = true) { st.setMusic(null) }
        }
        SecTitle("Not available")
        for (id in listOf("audioSplit", "musicOffset")) {
            val f = RenderCaps.get(id)
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(f.label, color = C.Muted.copy(alpha = 0.6f), fontSize = 14.sp)
                Text("not available: ${f.reason}", color = C.Muted.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}
