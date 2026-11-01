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
    private lateinit var scenesView: TextView
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
        scenesView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }.also { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) })
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
        val ok = setOf("txt", "md", "text", "srt", "vtt", "json") + com.auto.core.Bundle.AUDIO_EXT.map { it.removePrefix(".") }
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
        if (inFiles.isEmpty() || (bundleZips.isEmpty() && zips.isEmpty() && pics.isEmpty())) { status.text = "Choose a ZIP with everything (step 1), or the files (step 1) and the pictures (step 2), first."; return }
        status.text = "Analysing... (decoding audio can take a minute)"; exportBtn.isEnabled = false; scenesView.text = ""
        thread {
            try {
                val entries = inFiles.map { f -> Entry(f.name, f.length(), if (f.extension.lowercase().let { ".$it" } in com.auto.core.Bundle.AUDIO_EXT) AudioDecoder.durationSec(f) else null, { f.readText() }) }
                val b = com.auto.core.Bundle.classify(entries, bundleZips.size + zips.size + pics.size)
                fun file(e: Entry?) = e?.let { x -> inFiles.first { it.name == x.name } }
                val detailsF = file(b.details) ?: throw PipelineError(b.notes.joinToString(" ").ifEmpty { "No scene details file found." })
                val scriptText = file(b.script)?.readText() ?: ""
                val words = file(b.timestamps)?.let { Captions.parse(it.readText(), it.name) }
                val audioF = file(b.audio)
                val dec = audioF?.let { AudioDecoder.decode(it) }
                val sources = ArrayList<File>(bundleZips + zips)
                if (pics.isNotEmpty()) sources.add(File(cacheDir, "Pictures"))
                val work = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
                val a = Pipeline.analyse(detailsF.readText(), scriptText, words, dec?.first, dec?.second ?: 0.0, sources, work)
                analysis = a; narration = audioF
                val sb = StringBuilder()
                for (s in a.scenes) sb.append(String.format(Locale.ROOT, "%3d %7.2f-%-7.2f %s%s%s\n", s.num, s.start, s.end,
                    s.image?.let { Matching.nameOf(it) } ?: "(MISSING)", if (s.conf == "?") "  ?" else "", if (s.cutCheck) "  !" else ""))
                val msg = (b.notes + a.warnings).joinToString("\n\n")
                runOnUiThread { status.text = "${a.scenes.size} scenes ready.\n\n$msg"; scenesView.text = sb.toString(); exportBtn.isEnabled = true }
            } catch (e: Exception) { runOnUiThread { status.text = "Problem: ${e.message ?: e.toString()}" } }
        }
    }

    private fun export() {
        val a = analysis ?: return
        exportBtn.isEnabled = false; status.text = "Exporting..."
        try {
            Exporter.export(this, a, narration,
                { p -> status.text = "Exporting... $p%" },
                { where -> status.text = "Done: $where"; exportBtn.isEnabled = true },
                { err -> status.text = "Export failed: $err"; exportBtn.isEnabled = true })
        } catch (e: Exception) { status.text = "Export failed: ${e.message}"; exportBtn.isEnabled = true }
    }
}
