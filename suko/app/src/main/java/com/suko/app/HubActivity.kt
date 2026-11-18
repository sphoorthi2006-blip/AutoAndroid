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

/** Suko home screen: three tools. Only the Auto Editor is live in step 1. */
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
                        onOpenUpscaleTest = {
                            startActivity(Intent(this, UpscaleTestActivity::class.java))
                        },
                        onOpenSvgEditor = {
                            startActivity(Intent(this, SvgEditorActivity::class.java))
                        },
                        onOpenIntakeTest = {
                            startActivity(Intent(this, IntakeTestActivity::class.java))
                        },
                        onOpenTextEditor = { openWeb(TEXT_EDITOR_PAGE, "Text Editor") },
                        onOpenWebTest = { openWeb("web/studio/smoke.html", "Web test", sample = true) },
                        onOpenStudio = { openWeb("web/studio/index.html", "Layer Studio") },
                    )
                }
            }
        }
    }
}

/** Step 19: the Text Editor is the Layer Studio's Text tab, opened inside the app (offline web view) with the other tabs hidden. */
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

@Composable
private fun HubScreen(
    onOpenAutoEditor: () -> Unit, onOpenUpscaleTest: () -> Unit, onOpenIntakeTest: () -> Unit, onOpenSvgEditor: () -> Unit,
    onOpenWebTest: () -> Unit, onOpenStudio: () -> Unit, onOpenTextEditor: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.app_name), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Text("Pick a tool", fontSize = 15.sp, color = Muted)
        Spacer(Modifier.height(6.dp))

        ToolCard(
            title = "Auto Editor",
            blurb = "Images, script, SRT files and voice-over become a video in 1080p, 2K or 4K.",
            status = null,
            onClick = onOpenAutoEditor,
        )
        ToolCard(
            title = "SVG Editor",
            blurb = "Layer editor: images and JSON become a drawn, animated video.",
            status = "Step 24: adding pictures and JSON works; preview and export come later",
            onClick = onOpenSvgEditor,
        )
        ToolCard(
            title = "Text Editor",
            blurb = "Type text in Patrick Hand, place it anywhere, upload your own font, export 1080p, 2K or 4K at 30 or 60 fps.",
            status = "Patrick Hand or your own font, position, preview, 1080p / 2K / 4K export at 30 or 60 fps",
            onClick = onOpenTextEditor,
        )

        Spacer(Modifier.height(10.dp))
        Text("Developer tools", fontSize = 13.sp, color = Muted)
        ToolCard(
            title = "Upscale test",
            blurb = "Step 2: try the AI upscaler on one picture.",
            status = null,
            onClick = onOpenUpscaleTest,
        )
        ToolCard(
            title = "Intake test",
            blurb = "Step 8: add files, ZIPs and folders and see how they are sorted.",
            status = null,
            onClick = onOpenIntakeTest,
        )
        ToolCard(
            title = "Web test",
            blurb = "Step 18: checks the offline web view, the bridge and saving. Has a button to open the Studio.",
            status = null,
            onClick = onOpenWebTest,
        )
        ToolCard(
            title = "Layer Studio (raw)",
            blurb = "Steps 16-18: the Image Layer Studio page itself. Picture separation is not built in yet.",
            status = null,
            onClick = onOpenStudio,
        )
    }
}

@Composable
private fun ToolCard(title: String, blurb: String, status: String?, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(22.dp)
    val live = onClick != null
    val base = Modifier.fillMaxWidth().clip(shape).background(PanelBg).border(1.dp, Hairline, shape)
    val mod = if (live) base.clickable(onClick = onClick!!) else base
    Row(modifier = mod.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                .background(
                    if (live) Brush.linearGradient(listOf(Accent, Accent2))
                    else Brush.linearGradient(listOf(Color(0xFF262B38), Color(0xFF262B38)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.first().toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(modifier = Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (live) TextMain else Muted)
            Text(blurb, fontSize = 13.sp, color = Muted)
            if (status != null) Text(status, fontSize = 12.sp, color = Accent2)
        }
    }
}
