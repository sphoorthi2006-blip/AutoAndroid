package com.suko.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuevideo.app.CueTheme

/** Bezalel home screen: two tools, Auto Editor and Text Editor. */
class HubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CueTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = HubBg) {
                    HubScreen(
                        onOpenAutoEditor = {
                            startActivity(Intent(this, com.cuevideo.app.MainActivity::class.java))
                        },
                        onOpenTextEditor = { openWeb(TEXT_EDITOR_PAGE, "Text Editor") },
                    )
                }
            }
        }
    }
}

/** The Text Editor web page, opened inside the app (offline web view). The page always shows the Text Editor; the ?app= part is ignored. */
const val TEXT_EDITOR_PAGE = "web/studio/index.html?app=text"

/** Steps 16-18: opens a page from the app's own web assets. [sample] hands the page one small file so the smoke test can read it. */
private fun android.app.Activity.openWeb(page: String, title: String, sample: Boolean = false) {
    com.suko.app.web.WebInputs.files = if (sample) listOf(
        com.suko.app.web.InputFile("hello.txt", "text/plain", 10) { java.io.ByteArrayInputStream("hello suko".toByteArray()) },
    ) else emptyList()
    startActivity(
        Intent(this, com.suko.app.web.WebHostActivity::class.java)
            .putExtra(com.suko.app.web.WebHostActivity.EXTRA_PAGE, page)
            .putExtra(com.suko.app.web.WebHostActivity.EXTRA_TITLE, title),
    )
}

private val HubBg = Color(0xFF0B0D12)
private val PanelBg = Color(0xFF151821)
private val Hairline = Color(0x1AFFFFFF)
private val TextMain = Color(0xFFF2F4F8)
private val Muted = Color(0xFF8A93A6)
private val Accent = Color(0xFF8B5CF6)
private val Accent2 = Color(0xFF38BDF8)
private val CardTop = Color(0xFF1A1E2B)

@Composable
private fun HubScreen(
    onOpenAutoEditor: () -> Unit, onOpenTextEditor: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2))),
                contentAlignment = Alignment.Center,
            ) {
                Text("B", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(stringResource(R.string.app_name), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextMain)
                Text("Offline video studio", fontSize = 13.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("PICK A TOOL", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted, letterSpacing = 1.2.sp)

        ToolCard(
            title = "Auto Editor",
            tag = "Video",
            blurb = "Images, script, SRT files and voice-over become a 1080p video.",
            colors = listOf(Accent, Accent2),
            onClick = onOpenAutoEditor,
        )
        ToolCard(
            title = "Text Editor",
            tag = "Text",
            blurb = "Type text and watch it written by hand. Export a 1080p video at 30 or 60 fps.",
            colors = listOf(Color(0xFFFBBF24), Color(0xFFF472B6)),
            onClick = onOpenTextEditor,
        )
        Spacer(Modifier.height(6.dp))
        Text("Everything runs on your phone. Nothing is uploaded.", fontSize = 12.sp, color = Muted)
    }
}

@Composable
private fun ToolCard(title: String, tag: String, blurb: String, colors: List<Color>, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(24.dp)
    val live = onClick != null
    val base = Modifier.fillMaxWidth().clip(shape)
        .background(Brush.linearGradient(listOf(CardTop, PanelBg)))
        .border(1.dp, Hairline, shape)
    val mod = if (live) base.clickable(onClick = onClick!!) else base
    Row(modifier = mod.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(18.dp))
                .background(
                    if (live) Brush.linearGradient(colors)
                    else Brush.linearGradient(listOf(Color(0xFF262B38), Color(0xFF262B38)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.first().toString(), fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(tag.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.first(), letterSpacing = 1.sp)
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (live) TextMain else Muted)
            Text(blurb, fontSize = 13.sp, color = Muted)
        }
        Text("\u203A", fontSize = 30.sp, color = Muted, modifier = Modifier.padding(start = 8.dp))
    }
}
