package com.auto.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.auto.core.*
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val inFiles = ArrayList<File>()      // timestamps, script, narration (every one of them optional)
    private val zips = ArrayList<File>()
    private val bundleZips = ArrayList<File>()   // ZIPs picked in step 1: text/audio are taken out, the pictures stay inside
    private val pics = ArrayList<File>()
    private var analysis: Analysis? = null
    private var narration: File? = null
    private lateinit var status: TextView
    private lateinit var warnBox: LinearLayout
    private lateinit var scenesBox: LinearLayout
    private lateinit var scroll: ScrollView
    private var sceneRows: List<TextView> = emptyList()
    private lateinit var exportBtn: Button
    private lateinit var serverUrl: EditText
    private lateinit var serverToken: EditText
    private val prefs by lazy { getSharedPreferences("auto", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { onClick() } }.also { root.addView(it) }
        root.addView(TextView(this).apply { text = "Auto"; textSize = 26f })
        root.addView(TextView(this).apply { textSize = 13f; text = "Give Auto any of these - none is required: timestamps (SRT), narration audio, pictures, script. The timestamps decide the scenes and where every picture changes; pictures are placed by the number in their file name (7.jpg = scene 7). Pick ONE ZIP with everything, or several files: touch and hold the first file, then tap the others." })
        root.addView(TextView(this).apply { textSize = 13f; text = "AI audio server (optional). Only used when you give a script and audio but NO timestamps: it finds the exact time of every spoken word. Leave empty if you have timestamps." })
        serverUrl = EditText(this).apply { hint = "https://your-server.example"; setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI; setText(prefs.getString("url", "")) }.also { root.addView(it) }
        serverToken = EditText(this).apply { hint = "server token"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(prefs.getString("token", "")) }.also { root.addView(it) }
        btn("1. Choose files or one ZIP (timestamps, audio, script, pictures)") { pick(1) }
        btn("2. Choose pictures (ZIP files or images)") { pick(2) }
        btn("3. Analyse") { analyse() }
        exportBtn = btn("4. Export MP4") { export() }.also { it.isEnabled = false }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 24, 0, 24) }.also { root.addView(it) }
        warnBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }
        scenesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 24, 0, 0) }.also { root.addView(it) }
        scroll = ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        setContentView(scroll)
        status.text = "Choose your files (any of timestamps, audio, pictures, script), then press Analyse."
    }

    private fun pick(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
        @Suppress("DEPRECATION") startActivityForResult(i, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        data.data?.let { if (uris.isEmpty()) uris.add(it) }
        status.text = "Copying ${uris.size} file(s)..."
        thread {
            try {
                if (requestCode == 1) { inFiles.clear(); bundleZips.clear(); File(cacheDir, "in").deleteRecursively(); File(cacheDir, "bundle").deleteRecursively() }
                else { zips.clear(); pics.clear(); File(cacheDir, "zips").deleteRecursively(); File(cacheDir, "Pictures").deleteRecursively() }
                for (u in uris) {
                    val name = displayName(u)
                    val isImg = name.substringAfterLast('.', "").lowercase() in Pipeline.IMAGE_EXT
                    val isZip = name.endsWith(".zip", true)
                    val dir = File(cacheDir, if (requestCode == 1) (if (isZip) "bundle" else "in") else if (isZip) "zips" else "Pictures").apply { mkdirs() }
                    if (requestCode == 2 && !isZip && !isImg) continue
                    val f = unique(dir, name)
                    contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    if (requestCode == 1 && isZip) { bundleZips.add(f); inFiles.addAll(extractNonPictures(f)) }
                    else if (requestCode == 1) inFiles.add(f) else if (isZip) zips.add(f) else pics.add(f)
                }
                runOnUiThread { status.text = "Files: ${inFiles.size}   ZIPs: ${bundleZips.size + zips.size}   loose pictures: ${pics.size}" }
            } catch (e: Exception) { runOnUiThread { status.text = "Could not copy the files: ${e.message}" } }
        }
    }

    /** Text, caption, JSON and audio files inside a ZIP are copied out (zip-slip safe); the pictures stay in the ZIP. */
    private fun extractNonPictures(zip: File): List<File> {
        val out = ArrayList<File>(); val dir = File(cacheDir, "in").apply { mkdirs() }; val root = dir.canonicalFile
        val ok = setOf("txt", "md", "text", "docx", "srt", "vtt", "json") + com.auto.core.Bundle.AUDIO_EXT.map { it.removePrefix(".") }
        ZipFile(zip).use { z ->
            for (e in z.entries()) {
                if (e.isDirectory) continue
                val parts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (parts.isEmpty() || parts.any { it.startsWith(".") || it == "__MACOSX" }) continue
                if (parts.last().substringAfterLast('.', "").lowercase() !in ok) continue
                val f = unique(root, parts.last())
                if (!f.canonicalFile.path.startsWith(root.path + File.separator)) continue
                z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                out.add(f)
            }
        }
        return out
    }

    private fun displayName(u: Uri): String {
        contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val k = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (k >= 0) return c.getString(k) } }
        return u.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); var n = 1
        while (f.exists()) { f = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.', "")}"); n++ }
        return f
    }

    private fun analyse() {
        if (inFiles.isEmpty() && bundleZips.isEmpty() && zips.isEmpty() && pics.isEmpty()) { status.text = "Choose at least one file first: timestamps, audio, pictures or a script (all are optional)."; return }
        status.text = "Analysing..."; exportBtn.isEnabled = false; warnBox.removeAllViews(); scenesBox.removeAllViews()
        thread {
            try {
                val entries = inFiles.map { f -> Entry(f.name, f.length(), if (f.extension.lowercase().let { ".$it" } in com.auto.core.Bundle.AUDIO_EXT) AudioDecoder.durationSec(f) else null, { TextFiles.read(f) }) }
                val b = com.auto.core.Bundle.classify(entries, bundleZips.size + zips.size + pics.size)
                fun file(e: Entry?) = e?.let { x -> inFiles.first { it.name == x.name } }
                val extra = ArrayList<String>(b.notes)
                val scriptText = file(b.script)?.let { try { TextFiles.read(it) } catch (e: Exception) { extra.add("The script could not be read (${e.message}). It was skipped."); null } }
                val tsFile = file(b.timestamps)
                val tsText = tsFile?.let { try { TextFiles.read(it) } catch (e: Exception) { extra.add("The timestamps file could not be read (${e.message}). It was skipped."); null } }
                val audioF = file(b.audio)
                // the audio is only DECODED when the timing needs it (no timestamps); with timestamps only its length is read, which is instant
                var samples: FloatArray? = null; var seconds = audioF?.let { AudioDecoder.durationSec(it) } ?: 0.0
                var words: List<TimedWord>? = null
                val url = serverUrl.text.toString().trim(); val tok = serverToken.text.toString().trim()
                prefs.edit().putString("url", url).putString("token", tok).apply()
                if (tsText.isNullOrBlank() && audioF != null) {
                    try { val d = AudioDecoder.decode(audioF); samples = d.first; seconds = d.second }
                    catch (e: Exception) { extra.add("The audio could not be decoded (${e.message}). Its length is used, but pauses cannot be detected.") }
                    if (url.isNotEmpty() && !scriptText.isNullOrBlank()) {
                        try {
                            val r = BackendClient(BackendConfig(url, tok)).analyze(audioF, scriptText, "") { st, fr -> runOnUiThread { status.text = "AI analysis: $st (${(fr * 100).toInt()}%)" } }
                            words = r.words
                            extra.add("AI audio analysis OK (${r.alignment.ifEmpty { "whisper" }} word times, ${r.words.size} words).")
                            extra.addAll(r.warnings.map { "Audio: $it" })
                        } catch (e: Exception) { extra.add("The AI server could not be used (${e.message}). Picture changes were placed by pause detection instead - check the timing.") }
                    }
                } else if (url.isNotEmpty() && audioF != null) extra.add("The timestamps decide where the pictures change, so the AI server was not needed.")
                val sources = ArrayList<File>(bundleZips + zips)
                if (pics.isNotEmpty()) sources.add(File(cacheDir, "Pictures"))
                val work = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
                val a = Pipeline.analyse(tsText, tsFile?.name ?: "", scriptText, sources, work, samples, seconds, words, extra)
                analysis = a; narration = audioF
                val v = Validation.check(a)
                val issues = a.issues + v.warnings.map { AnalyseIssue(it, null, "warn", "dup") }
                runOnUiThread { show(a, issues) }
            } catch (e: Exception) { runOnUiThread { status.text = "Problem: ${e.message ?: e.toString()}" } }
        }
    }

    /** The scene list, and the warnings as a clickable list: tap a warning that points at a scene and the list jumps to it. */
    private fun show(a: Analysis, issues: List<AnalyseIssue>) {
        val nWarn = issues.count { it.level == "warn" }
        status.text = "${a.scenes.size} scene(s) ready, ${nWarn} warning(s). Tap a warning to jump to its scene. Nothing is blocked: warnings are only things to check."
        scenesBox.removeAllViews(); warnBox.removeAllViews()
        sceneRows = a.scenes.map { s ->
            TextView(this).apply {
                textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
                text = String.format(Locale.ROOT, "%3d %7.2f-%-7.2f %s%s", s.num, s.start, s.end, s.image?.let { Pics.nameOf(it) } ?: "(black + text)", if (s.cutCheck) "  !" else "")
            }.also { scenesBox.addView(it) }
        }
        for (i in issues) {
            TextView(this).apply {
                textSize = 13f; setPadding(0, 10, 0, 10)
                text = (if (i.level == "warn") "\u26A0 " else "\u2139 ") + i.text + (i.scene?.let { "  [scene $it]" } ?: "")
                setTextColor(if (i.level == "warn") 0xFFB45309.toInt() else 0xFF555555.toInt())
                val sc = i.scene
                if (sc != null && sc in 1..sceneRows.size) { isClickable = true; setOnClickListener { jumpTo(sc) } }
            }.also { warnBox.addView(it) }
        }
        exportBtn.isEnabled = true
    }

    private fun jumpTo(scene: Int) {
        sceneRows.forEach { it.setBackgroundColor(0) }
        val row = sceneRows.getOrNull(scene - 1) ?: return
        row.setBackgroundColor(0xFFFFF3B0.toInt())
        scroll.post { scroll.smoothScrollTo(0, maxOf(0, scenesBox.top + row.top - 200)) }
    }

    private fun export() {
        val a = analysis ?: return
        exportBtn.isEnabled = false; status.text = "Exporting..."
        try {
            val onP = { p: Int -> status.text = "Exporting... $p%" }
            val onD = { where: String -> status.text = "Done: $where"; exportBtn.isEnabled = true }
            val onE = { err: String -> status.text = "Export failed: $err"; exportBtn.isEnabled = true }
            // exact-boundary plan: every scene (picture, or black screen with its text) lasts from its cut to the next cut; the narration is one full-length item
            val tl = Timeline.fromScenes(a.scenes, a.duration)
            Exporter.exportTimeline(this, ExportPlan.fromTimeline(tl), narration, ExportSettings.P720, onP, onD, onE)
        } catch (e: Exception) { status.text = "Export failed: ${e.message}"; exportBtn.isEnabled = true }
    }
}
