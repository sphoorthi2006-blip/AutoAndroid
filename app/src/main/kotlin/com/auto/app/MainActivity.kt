package com.auto.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.auto.core.*
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val inFiles = ArrayList<File>()      // details, script, captions, narration
    private val zips = ArrayList<File>()
    private val bundleZips = ArrayList<File>()   // ZIPs picked in step 1: text/audio are taken out, the pictures stay inside
    private val pics = ArrayList<File>()
    private var analysis: Pipeline.Analysis? = null
    private var narration: File? = null
    private lateinit var status: TextView
    private lateinit var warnBox: LinearLayout
    private lateinit var scenesBox: LinearLayout
    private lateinit var scroll: ScrollView
    private var sceneRows: List<TextView> = emptyList()
    private lateinit var exportBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { onClick() } }.also { root.addView(it) }
        root.addView(TextView(this).apply { text = "Auto"; textSize = 26f })
        root.addView(TextView(this).apply { textSize = 13f; text = "Step 1: pick ONE ZIP with everything (details, script, captions, narration, pictures), or several files: touch and hold the first file, then tap the others." })
        btn("1. Choose files or one ZIP (details, script, captions, narration)") { pick(1) }
        btn("2. Choose pictures (ZIP files or images)") { pick(2) }
        btn("3. Analyse") { analyse() }
        exportBtn = btn("4. Export MP4") { export() }.also { it.isEnabled = false }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 24, 0, 24) }.also { root.addView(it) }
        warnBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { root.addView(it) }
        scenesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 24, 0, 0) }.also { root.addView(it) }
        scroll = ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        setContentView(scroll)
        status.text = "Choose your ZIP (or files and pictures), then press Analyse."
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
        if (inFiles.isEmpty() && bundleZips.isEmpty() && zips.isEmpty() && pics.isEmpty()) { status.text = "Add at least one of: the captions (timestamps) file, the narration audio, the pictures or the script."; return }
        status.text = "Analysing..."; exportBtn.isEnabled = false; warnBox.removeAllViews(); scenesBox.removeAllViews()
        thread {
            try {
                // Same inputs as the desktop app: timestamps (captions), audio, pictures, script. NO details file. None is mandatory.
                val entries = inFiles.map { f -> Entry(f.name, f.length(), if (f.extension.lowercase().let { ".$it" } in com.auto.core.Bundle.AUDIO_EXT) AudioDecoder.durationSec(f) else null, { TextFiles.read(f) }) }
                val b = com.auto.core.Bundle.classify(entries, bundleZips.size + zips.size + pics.size)
                fun file(e: Entry?) = e?.let { x -> inFiles.first { it.name == x.name } }
                val scriptText = file(b.script)?.let { TextFiles.read(it) } ?: ""
                val tsF = file(b.timestamps)
                val captionRaw = tsF?.readText()
                // cue rows exactly as written; a word-level JSON file is grouped into sentences instead (same as the desktop)
                val tsName = tsF?.name ?: ""
                val rows = captionRaw?.let { Captions.parseRows(it, tsName) ?: Captions.parseCues(Captions.parse(it, tsName)) }
                val audioF = file(b.audio)
                val audioSec = audioF?.let { AudioDecoder.durationSec(it) } ?: 0.0
                val sources = ArrayList<File>(bundleZips + zips)
                if (pics.isNotEmpty()) sources.add(File(cacheDir, "Pictures"))
                val work = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
                val a = CaptionPipeline.analyse(rows, scriptText, null, audioSec, sources, work, hasAudio = audioF != null)
                analysis = a; narration = audioF
                val v = Validation.check(a)
                val msgs = (v.errors.map { "ERROR: $it" } + v.warnings + b.notes.filterNot { it.startsWith("MISSING: scene details") } + a.warnings).distinct()
                runOnUiThread { show(a, msgs) }
            } catch (e: Exception) { runOnUiThread { status.text = "Problem: ${e.message ?: e.toString()}" } }
        }
    }

    /** Scene list + the warnings as a clickable list: tap a warning that names a scene ("Scene 12 ...", "Scenes 5-9 ...") and the list jumps to it. */
    private fun show(a: Pipeline.Analysis, msgs: List<String>) {
        status.text = "${a.scenes.size} scenes ready, ${msgs.size} note(s). Tap a note to jump to its scene. Nothing is blocked: notes are only things to check."
        warnBox.removeAllViews(); scenesBox.removeAllViews()
        sceneRows = a.scenes.map { sc ->
            TextView(this).apply {
                textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
                text = String.format(Locale.ROOT, "%3d %7.2f-%-7.2f %s", sc.num, sc.start, sc.end, sc.image?.let { Matching.nameOf(it) } ?: "(black screen + text)")
            }.also { scenesBox.addView(it) }
        }
        val num = Regex("[Ss]cenes? (\\d+)")
        for (m in msgs) {
            TextView(this).apply {
                textSize = 13f; setPadding(0, 10, 0, 10); text = "\u2022 $m"
                val sc = num.find(m)?.groupValues?.get(1)?.toIntOrNull()
                if (sc != null && sc in 1..sceneRows.size) { setTextColor(0xFFB45309.toInt()); isClickable = true; setOnClickListener { jumpTo(sc) } }
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
            if (a.match.full) {   // whole script covered: exact-boundary plan, one narration item
                val tl = Timeline.fromScenes(a.scenes, a.timing.duration)
                Exporter.exportTimeline(this, ExportPlan.fromTimeline(tl), narration, ExportSettings.P720, onP, onD, onE)
            } else Exporter.export(this, a, narration, onP, onD, onE)
        } catch (e: Exception) { status.text = "Export failed: ${e.message}"; exportBtn.isEnabled = true }
    }
}
