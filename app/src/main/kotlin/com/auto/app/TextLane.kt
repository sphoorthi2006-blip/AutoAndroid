package com.auto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auto.core.TextClip
import com.auto.core.Timeline
import kotlin.math.max

/** Lane height inside the timeline and vertical position of the text lane. */
const val TEXT_LANE_Y = 126f
const val TEXT_LANE_H = 30f

/**
 * Step 30: one text clip on the text lane. Tap = select, double tap = open Text Studio, long-press and drag = move,
 * the two round edge handles of a selected clip = trim start/end. While a finger is down only this clip moves; ONE edit (one undo step) is made when it lifts.
 */
@Composable
fun TextClipBox(c: TextClip, st: AutoState, dpPerSec: Float) {
    val sel = c.id == st.selectedTextId
    val pxSec = LocalDensity.current.density * dpPerSec
    var move by remember(c.id, c.startMs, c.endMs) { mutableFloatStateOf(0f) }
    var trimL by remember(c.id, c.startMs, c.endMs) { mutableFloatStateOf(0f) }
    var trimR by remember(c.id, c.startMs, c.endMs) { mutableFloatStateOf(0f) }
    fun dMs(px: Float) = (px / pxSec * 1000f).toLong()
    val s = max(0L, c.startMs + dMs(move + trimL)); val e = max(s + Timeline.MIN_CLIP_MS, c.endMs + dMs(move + trimR))
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.offset(x = (s / 1000f * dpPerSec).dp, y = TEXT_LANE_Y.dp).width(max(10f, (e - s) / 1000f * dpPerSec - 2f).dp).height(TEXT_LANE_H.dp)
        .clip(shape).background(if (sel) C.Violet else C.Violet.copy(alpha = 0.55f)).then(if (sel) Modifier.border(2.dp, C.Cyan, shape) else Modifier)
        .pointerInput(c.id) { detectTapGestures(onTap = { st.selectText(c.id) }, onDoubleTap = { st.selectText(c.id); st.sheet = "text" }) }
        .pointerInput(c.id, c.startMs) {
            detectDragGesturesAfterLongPress(onDragStart = { move = 0f }, onDrag = { ch, a -> ch.consume(); move += a.x },
                onDragEnd = { val m = move; move = 0f; if (dMs(m) != 0L) st.moveText(c.id, c.startMs + dMs(m)) }, onDragCancel = { move = 0f })
        }, contentAlignment = Alignment.CenterStart) {
        Text(c.text.ifBlank { "(empty)" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(horizontal = 14.dp))
    }
    if (sel) {
        val hs = 22.dp
        Box(Modifier.offset(x = (s / 1000f * dpPerSec).dp - 6.dp, y = TEXT_LANE_Y.dp + 4.dp).width(hs).height(22.dp).clip(RoundedCornerShape(50)).background(C.Cyan)
            .pointerInput(c.id, c.startMs) { detectHorizontalDragGestures(onDragStart = { trimL = 0f }, onHorizontalDrag = { ch, a -> ch.consume(); trimL += a }, onDragEnd = { val t = trimL; trimL = 0f; if (dMs(t) != 0L) st.editText(c.id, startMs = max(0L, c.startMs + dMs(t)), endMs = c.endMs) }, onDragCancel = { trimL = 0f }) })
        Box(Modifier.offset(x = (e / 1000f * dpPerSec).dp - 16.dp, y = TEXT_LANE_Y.dp + 4.dp).width(hs).height(22.dp).clip(RoundedCornerShape(50)).background(C.Cyan)
            .pointerInput(c.id, c.endMs) { detectHorizontalDragGestures(onDragStart = { trimR = 0f }, onHorizontalDrag = { ch, a -> ch.consume(); trimR += a }, onDragEnd = { val t = trimR; trimR = 0f; if (dMs(t) != 0L) st.editText(c.id, endMs = c.endMs + dMs(t)) }, onDragCancel = { trimR = 0f }) })
    }
}
