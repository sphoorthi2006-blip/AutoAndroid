package com.suko.app

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuevideo.app.CueTheme
import com.cuevideo.app.GradientButton
import com.cuevideo.app.Section
import com.suko.app.upscale.Upscaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Step 2 self-test: pick ONE picture, remove the corner mark, AI-upscale 2x, save a PNG, show the result and timings. */
class UpscaleTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CueTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = Color(0xFF0B0D12)) {
                    UpscaleTestScreen(this@UpscaleTestActivity)
                }
            }
        }
    }
}

@Composable
private fun UpscaleTestScreen(ctx: Context) {
    val scope = rememberCoroutineScope()
    var removeMark by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Pick one picture to test the AI upscaler.") }
    var tileLine by remember { mutableStateOf("") }
    var resultLines by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !busy) {
            busy = true
            preview = null
            resultLines = ""
            tileLine = ""
            status = "Reading the picture..."
            scope.launch {
                try {
                    val name = displayName(ctx, uri)
                    val outcome = withContext(Dispatchers.Default) {
                        val src = Upscaler.decode(ctx, uri) ?: throw IllegalStateException("this file is not a readable picture")
                        withContext(Dispatchers.Main) { status = "Working on ${src.w}x${src.h} ..." }
                        val t0 = System.nanoTime()
                        val res = Upscaler.run(ctx, src, removeMark) { done, total ->
                            tileLine = "AI step $done/$total"
                        }
                        val totalMs = (System.nanoTime() - t0) / 1_000_000L
                        val bmp = Upscaler.toBitmap(res.img)
                        val where = savePng(ctx, bmp, name)
                        val scale = minOf(1f, 1200f / maxOf(bmp.width, bmp.height))
                        val small = if (scale < 1f)
                            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                        else bmp
                        if (small !== bmp) bmp.recycle()
                        Triple(Triple(src.w, src.h, res), Pair(where, totalMs), small)
                    }
                    val (a, b, small) = outcome
                    val (w, h, res) = a
                    val (where, totalMs) = b
                    val sb = StringBuilder()
                    sb.append("Size: ${w}x$h  ->  ${res.img.w}x${res.img.h}\n")
                    sb.append("Method: ${res.method}")
                    if (res.engine.isNotEmpty()) sb.append("  ·  ${res.engine}")
                    sb.append("\n")
                    sb.append(String.format(Locale.US, "Corner mark: %.1f s   Upscale: %.1f s   Total: %.1f s\n",
                        res.markMs / 1000.0, res.scaleMs / 1000.0, totalMs / 1000.0))
                    if (res.note != null) sb.append("Note: ${res.note}\n")
                    sb.append("Saved: $where")
                    resultLines = sb.toString()
                    preview = small
                    status = "Done."
                } catch (t: Throwable) {
                    status = "Failed: ${t.message ?: t.javaClass.simpleName}"
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Upscale test", fontSize = 28.sp, color = Color(0xFFF2F4F8))

        Section("Engine") {
            Text(
                "AI library: " + (if (Upscaler.libraryLoaded()) "loaded" else "NOT loaded (classic mode only)") +
                    "\nPhone memory: " + ramText(ctx),
                color = Color(0xFF8A93A6), fontSize = 14.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Remove the corner mark", color = Color(0xFFF2F4F8))
                Switch(checked = removeMark, onCheckedChange = { if (!busy) removeMark = it })
            }
        }

        GradientButton(if (busy) "Working..." else "Pick one picture", enabled = !busy) {
            picker.launch(arrayOf("image/*"))
        }

        Section("Status") {
            Text(status, color = Color(0xFFF2F4F8), fontSize = 15.sp)
            if (tileLine.isNotEmpty() && busy) Text(tileLine, color = Color(0xFF38BDF8), fontSize = 14.sp)
            if (resultLines.isNotEmpty()) Text(resultLines, color = Color(0xFF8A93A6), fontSize = 14.sp)
        }

        preview?.let {
            Image(
                bitmap = it.asImageBitmap(), contentDescription = "Upscaled result",
                modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun ramText(ctx: Context): String {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mi = ActivityManager.MemoryInfo()
    am.getMemoryInfo(mi)
    return String.format(Locale.US, "%.1f GB", mi.totalMem / (1024.0 * 1024.0 * 1024.0))
}

private fun displayName(ctx: Context, uri: Uri): String {
    try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
        }
    } catch (_: Exception) {
    }
    return "image"
}

/** Saves a PNG to Pictures/Suko (Android 10+) or to the app's own Pictures folder (older Android). Returns where. */
private fun savePng(ctx: Context, bmp: Bitmap, name: String): String {
    val fileName = "${name}_x2.png"
    if (Build.VERSION.SDK_INT >= 29) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Suko")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("could not create the output file")
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: throw IllegalStateException("could not write the output file")
        return "Pictures/Suko/$fileName"
    }
    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        ?: throw IllegalStateException("no storage available")
    val f = File(dir, fileName)
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return f.absolutePath
}
