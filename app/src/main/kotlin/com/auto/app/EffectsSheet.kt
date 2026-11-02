package com.auto.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import com.auto.core.ClipFx
import com.auto.core.EffectCatalog
import com.auto.core.RenderCaps
import com.auto.core.TransitionKind
import com.auto.core.TransitionSpec
import com.auto.core.Transitions

/** Steps 32+33: colour effects and entrance transitions for the SELECTED picture. Only what the export really renders is a control; the rest is greyed with its reason. */
@Composable
fun EffectsSheet(st: AutoState) {
    var tab by remember { mutableIntStateOf(0) }
    val clip = st.selectedClip()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row { OptionChip("Effects", tab == 0) { tab = 0 }; OptionChip("Transitions", tab == 1) { tab = 1 } }
        if (clip == null) {
            Text("Select a picture on the timeline first. Effects and transitions belong to one picture.", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
            return@Column
        }
        if (tab == 0) {
            val fx = clip.fx
            Row { SecTitle("Colour of this picture"); if (RenderCaps.available("fx")) BetaTag("fx") }
            fun pc(v: Float) = "${(v * 100).toInt()}%"
            for (e in EffectCatalog.available) {
                when (e.id) {
                    "brightness" -> StudioSlider(e.label, fx.brightness, -1f..1f, ::pc) { st.setFx(clip.id, fx.copy(brightness = it)) }
                    "contrast" -> StudioSlider(e.label, fx.contrast, -1f..1f, ::pc) { st.setFx(clip.id, fx.copy(contrast = it)) }
                    "saturation" -> StudioSlider(e.label, fx.saturation, -1f..1f, ::pc) { st.setFx(clip.id, fx.copy(saturation = it)) }
                    "opacity" -> StudioSlider(e.label + " (fades towards black)", fx.opacity, 0f..1f, ::pc) { st.setFx(clip.id, fx.copy(opacity = it)) }
                }
            }
            GlowButton("Reset", enabled = !fx.isIdentity, outline = true) { st.setFx(clip.id, ClipFx()) }
            SecTitle("Not available")
            for (e in EffectCatalog.unavailable) UnavailableRow(e.label, RenderCaps.get(e.id).reason)
        } else {
            val tr = clip.transition
            Row { SecTitle("Entrance of this picture"); if (RenderCaps.available("transitions")) BetaTag("transitions") }
            Row(Modifier.horizontalScroll(rememberScrollState())) { for (k in Transitions.offered) OptionChip(k.label, tr.kind == k) { st.setTransition(clip.id, tr.copy(kind = k)) } }
            if (tr.active) StudioSlider("Duration", tr.ms / 1000f, 0.1f..2f, { String.format(java.util.Locale.ROOT, "%.1f s", it) }) { st.setTransition(clip.id, tr.copy(ms = (it * 1000).toInt())) }
            Text("Never longer than half of the picture. Fade also fades the previous picture out (dip through black).", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            GlowButton("Apply to all pictures", enabled = tr.active) { st.setTransitionAll(tr) }
            SecTitle("Not available")
            for (k in Transitions.unavailable) UnavailableRow(k.label, RenderCaps.get(k.name.lowercase()).reason)
        }
    }
}

@Composable
private fun UnavailableRow(label: String, reason: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = C.Muted.copy(alpha = 0.6f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("not available: $reason", color = C.Muted.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}
