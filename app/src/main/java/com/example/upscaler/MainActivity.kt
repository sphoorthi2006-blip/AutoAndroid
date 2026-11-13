package com.example.upscaler

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

/** Slim gradient progress bar. */
private class SlimBar(ctx: Context) : View(ctx) {
    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262638.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        c.drawRoundRect(0f, 0f, w, h, r, r, track)
        if (fraction > 0f) {
            fill.shader = LinearGradient(0f, 0f, w, 0f, 0xFF7C5CFF.toInt(), 0xFFFF5CA8.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(0f, 0f, max(w * fraction, h), h, r, r, fill)
        }
    }
}

class MainActivity : Activity() {

    private companion object {
        const val BG = 0xFF0A0A12.toInt()
        const val SURFACE = 0xFF14141F.toInt()
        const val STROKE = 0xFF26263A.toInt()
        const val TEXT = 0xFFF4F4FA.toInt()
        const val MUTED = 0xFF9A9AB2.toInt()
        const val A1 = 0xFF7C5CFF.toInt()
        const val A2 = 0xFFFF5CA8.toInt()
        const val GOOD = 0xFF4ADE80.toInt()
        const val WARN = 0xFFFFB84D.toInt()

        /** More than this many pictures are delivered as ZIP file(s) instead of single PNGs. */
        const val ZIP_ABOVE = 10
        /** Maximum pictures inside one ZIP file. */
        const val PER_ZIP = 50
    }

    private lateinit var mainButton: TextView
    private lateinit var stateCard: LinearLayout
    private lateinit var stateLabel: TextView
    private lateinit var stateMain: TextView
    private lateinit var stateBar: SlimBar
    private lateinit var stateDetail: TextView
    private lateinit var noticeView: TextView
    private lateinit var compareBtn: TextView
    private lateinit var mainView: View

    // before / after viewer
    private val results = mutableListOf<Result>()
    private var compareRoot: LinearLayout? = null
    private lateinit var compareView: CompareView
    private lateinit var compareTitle: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private var compareIndex = 0
    private var compareToken = 0
    private var inCompare = false

    private val PICK_CODE = 42
    private val FOLDER_CODE = 43

    private var running = false
    private var zipCounter = 0
    private val readProblems = mutableListOf<String>()

    private data class ImageInput(val name: String, val open: () -> InputStream?)

    /** One finished picture: where the result was saved, plus what is needed to rebuild the "before" side. */
    private class Result(val name: String, val input: ImageInput, val after: Uri, val srcH: Int)

    private class Decoded(val img: ImageProcessor.Img, val ms: Long)
    private class Saved(val uri: Uri?, val ms: Long)

    /** A picture that is being written to disk on the save thread while the next one is processed. */
    private class Pending(
        val name: String, val input: ImageInput, val srcH: Int,
        val future: Future<Saved>
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- small UI helpers

    private fun rounded(color: Int, radius: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun gradientBg(radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(A1, A2)).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun ripple(bg: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun pillButton(text: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = if (filled) ripple(gradientBg(24)) else ripple(rounded(SURFACE, 24, STROKE, 1))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun chip(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFC9BDFF.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0xFF1E1A38.toInt(), 14, 0xFF372F66.toInt(), 1)
        }

    // ---------------------------------------------------------------- main screen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val crashPrefs = getSharedPreferences("upscaler", MODE_PRIVATE)
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val where = e.stackTrace.firstOrNull()?.toString() ?: ""
                crashPrefs.edit().putString("last_java_crash", "${e} at $where".take(400)).commit()
            } catch (_: Throwable) { }
            prevHandler?.uncaughtException(t, e)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(44), dp(22), dp(28))
        }

        // header: logo badge + name
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "\u2726"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = gradientBg(14)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        titles.addView(label("Sukko", 28f, TEXT, true))
        titles.addView(label("AI image upscaler", 13f, MUTED))
        header.addView(titles)
        root.addView(header)

        // hero card
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 28, STROKE, 1)
            setPadding(dp(24), dp(26), dp(24), dp(24))
        }
        hero.addView(TextView(this).apply {
            text = "\u2726"
            textSize = 40f
            setTextColor(A2)
        })
        hero.addView(label("Sharper pictures,\nin one tap", 26f, TEXT, true).apply {
            setPadding(0, dp(10), 0, dp(8))
        })
        hero.addView(label(
            "Removes the corner mark and upscales every picture 2\u00D7 with AI. " +
                "Give it one image, many images, a folder or ZIP files.",
            14f, MUTED
        ).apply { setLineSpacing(0f, 1.15f) })
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        for ((i, t) in listOf("AI 2\u00D7", "Mark removed", "PNG").withIndex()) {
            chips.addView(chip(t), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) leftMargin = dp(8) })
        }
        hero.addView(chips)
        root.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26) })

        // the one and only action
        mainButton = TextView(this).apply {
            text = "Upscale Images (PNG)"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = ripple(gradientBg(30))
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener { if (!running) showSourceSheet() }
        }
        root.addView(mainButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(62)
        ).apply { topMargin = dp(22) })
        root.addView(label(
            "Up to $ZIP_ABOVE pictures are saved as PNG files. More than $ZIP_ABOVE come as ZIP files " +
                "(max $PER_ZIP pictures per ZIP).",
            12f, MUTED
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), 0)
        })

        noticeView = label("", 12f, WARN).apply {
            visibility = View.GONE
            setPadding(dp(4), dp(14), dp(4), 0)
        }
        root.addView(noticeView)
        lastCloseNote()?.let { noticeView.text = it; noticeView.visibility = View.VISIBLE }

        // progress / result card
        stateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 24, STROKE, 1)
            setPadding(dp(22), dp(20), dp(22), dp(20))
            visibility = View.GONE
        }
        stateLabel = label("", 11f, A2, true).apply { letterSpacing = 0.14f }
        stateMain = label("", 24f, TEXT, true).apply { setPadding(0, dp(4), 0, dp(14)) }
        stateBar = SlimBar(this)
        stateDetail = label("", 13f, MUTED).apply {
            setPadding(0, dp(14), 0, 0)
            setLineSpacing(0f, 1.15f)
        }
        stateCard.addView(stateLabel)
        stateCard.addView(stateMain)
        stateCard.addView(stateBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        stateCard.addView(stateDetail)
        root.addView(stateCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        compareBtn = pillButton("Before / After", false) { openCompare(0) }.apply { visibility = View.GONE }
        root.addView(compareBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        mainView = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
        }
        setContentView(mainView)
    }

    /** Shows the card: small label, big line, progress (0..1) and details. */
    private fun showState(labelText: String, main: String, detail: String, fraction: Float, color: Int = A2) {
        stateCard.visibility = View.VISIBLE
        stateLabel.text = labelText
        stateLabel.setTextColor(color)
        stateMain.text = main
        stateBar.fraction = fraction
        stateDetail.text = detail
        stateDetail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The only choice the app asks for: where the pictures come from. */
    private fun showSourceSheet() {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 28, STROKE, 1)
            setPadding(dp(18), dp(22), dp(18), dp(14))
        }
        box.addView(label("What do you want to upscale?", 18f, TEXT, true).apply {
            setPadding(dp(6), 0, 0, dp(10))
        })
        fun row(title: String, sub: String, onClick: () -> Unit): View {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(rounded(0xFF1B1B2A.toInt(), 18))
                setPadding(dp(18), dp(14), dp(18), dp(14))
                isClickable = true
                setOnClickListener { d.dismiss(); onClick() }
            }
            r.addView(label(title, 16f, TEXT, true))
            r.addView(label(sub, 12f, MUTED).apply { setPadding(0, dp(2), 0, 0) })
            return r
        }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        box.addView(row("Images or ZIP files", "One, many, or a mix of both") { pickFiles() }, lp())
        box.addView(row("A folder", "Images and ZIPs inside, subfolders included") { pickFolder() }, lp())
        val wrap = FrameLayout(this).apply {
            setPadding(dp(12), 0, dp(12), dp(12))
            addView(box)
        }
        d.setContentView(wrap)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    // ---------------------------------------------------------------- before / after viewer

    private fun buildCompareUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(40), dp(12), dp(14))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = pillButton("Back", false) { closeCompare() }
        compareTitle = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(8), 0, dp(8), 0)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        prevBtn = pillButton("\u2039", false) { showCompare(compareIndex - 1) }
        nextBtn = pillButton("\u203A", false) { showCompare(compareIndex + 1) }
        bar.addView(back)
        bar.addView(compareTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(prevBtn)
        bar.addView(nextBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        root.addView(bar)

        compareView = CompareView(this)
        root.addView(compareView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "Drag the slider  \u00B7  pinch or double-tap to zoom"
            textSize = 12f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        compareRoot = root
    }

    private fun openCompare(index: Int) {
        if (results.isEmpty()) return
        if (compareRoot == null) buildCompareUi()
        inCompare = true
        setContentView(compareRoot!!)
        showCompare(index)
    }

    private fun closeCompare() {
        inCompare = false
        compareToken++
        if (compareRoot != null) compareView.setImages(null, null)
        setContentView(mainView)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inCompare) closeCompare() else super.onBackPressed()
    }

    /** Decodes an image, halving it until its longest side fits the GPU texture limit. */
    private fun decodeSampled(open: () -> InputStream?, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun showCompare(index: Int) {
        if (results.isEmpty()) return
        compareIndex = index.coerceIn(0, results.size - 1)
        val r = results[compareIndex]
        compareTitle.text = "${compareIndex + 1} / ${results.size}   ${r.name}"
        prevBtn.isEnabled = compareIndex > 0
        prevBtn.alpha = if (prevBtn.isEnabled) 1f else 0.35f
        nextBtn.isEnabled = compareIndex < results.size - 1
        nextBtn.alpha = if (nextBtn.isEnabled) 1f else 0.35f
        compareView.setImages(null, null)
        val token = ++compareToken
        thread {
            try {
                val after = decodeSampled({ contentResolver.openInputStream(r.after) }, 4096)
                val before = decodeSampled({ r.input.open() }, 4096)
                runOnUiThread {
                    if (token != compareToken || !inCompare) {
                        before?.recycle(); after?.recycle()
                    } else if (before == null || after == null) {
                        compareView.setImages(null, null, "Could not load this picture")
                    } else {
                        compareView.setImages(before, after)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    if (token == compareToken) compareView.setImages(null, null, "Could not load: ${e.message}")
                }
            }
        }
    }

    /** One picker for everything that is a file: any number of images and ZIPs, mixed together. */
    @Suppress("DEPRECATION")
    private fun pickFiles() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                android.content.Intent.EXTRA_MIME_TYPES,
                arrayOf("image/*", "application/zip", "application/x-zip-compressed", "application/octet-stream")
            )
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, PICK_CODE)
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(i, FOLDER_CODE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            PICK_CODE -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { c ->
                    for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri)
                }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                if (uris.isEmpty()) return
                runJob { collectPickedFiles(uris) }
            }
            FOLDER_CODE -> {
                data.data?.let { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, data.flags and
                            (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                    } catch (_: Exception) { }
                    runJob { collectFolderImages(uri) }
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "image"
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return name
    }

    private fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic")

    private fun isZipName(name: String): Boolean = name.lowercase().endsWith(".zip")

    /** Picked files: each one is either an image or a ZIP (a ZIP is opened and its images are used). */
    private fun collectPickedFiles(uris: List<Uri>): List<ImageInput> {
        clearZipCache()
        val found = mutableListOf<ImageInput>()
        for (uri in uris) {
            val name = displayName(uri)
            val mime = contentResolver.getType(uri) ?: ""
            if (isZipName(name) || mime.contains("zip")) {
                try {
                    found.addAll(extractZipImages(uri))
                } catch (e: Exception) {
                    readProblems.add("$name (${e.message})")
                }
            } else if (mime.startsWith("image/") || isImageName(name)) {
                found.add(ImageInput(name) { contentResolver.openInputStream(uri) })
            }
        }
        return found
    }

    /** A folder: every image inside it and inside its subfolders, and the images inside any ZIP found there. */
    private fun collectFolderImages(treeUri: Uri): List<ImageInput> {
        clearZipCache()
        val found = mutableListOf<ImageInput>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        fun visit(documentId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: "image"
                    val mime = cursor.getString(mimeCol) ?: ""
                    if (name.startsWith(".")) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        visit(id)
                    } else if (isZipName(name) || mime.contains("zip")) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        try {
                            found.addAll(extractZipImages(uri))
                        } catch (e: Exception) {
                            readProblems.add("$name (${e.message})")
                        }
                    } else if (mime.startsWith("image/") || isImageName(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        found.add(ImageInput(name) { contentResolver.openInputStream(uri) })
                    }
                }
            }
        }
        visit(rootId)
        return found
    }

    private fun zipDir(): File = File(cacheDir, "zip_images").apply { mkdirs() }

    private fun clearZipCache() {
        zipCounter = 0
        zipDir().listFiles()?.forEach { it.delete() }
    }

    /** Unpacks the images of one ZIP into the cache. Several ZIPs can be unpacked one after another. */
    private fun extractZipImages(uri: Uri): List<ImageInput> {
        val found = mutableListOf<ImageInput>()
        val dir = zipDir()
        val zipStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open ZIP archive")
        ZipInputStream(zipStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val path = entry.name.replace('\\', '/')
                val base = path.substringAfterLast('/')
                if (!entry.isDirectory && isImageName(path) &&
                    !path.startsWith("__MACOSX") && !base.startsWith(".")) {
                    val safeName = base.ifBlank { "image_$zipCounter" }
                    val output = File(dir, "${zipCounter}_$safeName")
                    zipCounter++
                    FileOutputStream(output).use { out -> zip.copyTo(out) }
                    found.add(ImageInput(safeName) { output.inputStream() })
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return found
    }

    /**
     * Streams finished pictures straight into ZIP files in Downloads/Sukko (no temporary copies).
     * A new ZIP is started after [PER_ZIP] pictures. PNG data is already compressed, so it is stored as is.
     */
    private inner class ZipSink(private val stamp: String, private val multi: Boolean) {
        private var zip: ZipOutputStream? = null
        private var uri: Uri? = null
        private var inPart = 0
        private var partNo = 0
        private var names = HashSet<String>()
        var parts = 0
            private set

        private fun openNext() {
            close()
            partNo++
            val fileName = "Sukko_$stamp" + (if (multi) "_part$partNo" else "") + ".zip"
            val raw: OutputStream
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Sukko")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                    ?: throw IllegalStateException("Could not create the ZIP file")
                uri = u
                raw = contentResolver.openOutputStream(u)
                    ?: throw IllegalStateException("Could not open the ZIP file")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Sukko")
                dir.mkdirs()
                raw = FileOutputStream(File(dir, fileName))
            }
            zip = ZipOutputStream(BufferedOutputStream(raw, 256 * 1024)).apply {
                setLevel(Deflater.NO_COMPRESSION)
            }
            inPart = 0
            names = HashSet()
            parts++
        }

        fun add(name: String, bmp: Bitmap) {
            if (zip == null || inPart >= PER_ZIP) openNext()
            val z = zip ?: return
            var entry = "$name.png"
            var n = 1
            while (!names.add(entry.lowercase(Locale.ROOT))) {
                entry = "$name (${n++}).png"
            }
            z.putNextEntry(ZipEntry(entry))
            bmp.compress(Bitmap.CompressFormat.PNG, 100, z)
            z.closeEntry()
            inPart++
        }

        fun close() {
            val z = zip ?: return
            zip = null
            try {
                z.close()
            } finally {
                val u = uri
                uri = null
                if (u != null) {
                    try {
                        contentResolver.update(u, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                    } catch (_: Throwable) { }
                }
            }
        }
    }

    /** Reads and decodes one picture into ARGB pixels (null if it is not a readable picture). */
    private fun decodeImage(input: ImageInput): Decoded? {
        val t0 = System.nanoTime()
        val bmp = input.open()?.use { BitmapFactory.decodeStream(it) } ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val img = ImageProcessor.Img(bmp.width, bmp.height, px)
        bmp.recycle()
        return Decoded(img, (System.nanoTime() - t0) / 1_000_000L)
    }

    private fun setBusy(busy: Boolean) {
        mainButton.isEnabled = !busy
        mainButton.alpha = if (busy) 0.5f else 1f
    }

    /** Reads the files (in the background), then removes the mark and upscales every image found. */
    private fun runJob(collect: () -> List<ImageInput>) {
        if (running) return
        running = true
        val prefs = getSharedPreferences("upscaler", MODE_PRIVATE)
        prefs.edit().putBoolean("run_in_progress", true).commit()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readProblems.clear()
        // if the app dies inside the GPU driver, this flag is still set next time and that run uses the CPU
        val useGpu = !prefs.getBoolean("gpu_crashed", false)
        if (useGpu) prefs.edit().putBoolean("gpu_crashed", true).commit()
        setBusy(true)
        results.clear()
        compareBtn.visibility = View.GONE
        noticeView.visibility = View.GONE
        showState("READING", "Looking for pictures\u2026", "", 0f)
        thread {
            val inputs = try {
                collect()
            } catch (e: Throwable) {
                runOnUiThread {
                    showState("PROBLEM", "Could not read the files", e.message ?: "", 0f, WARN)
                    finishUi()
                }
                return@thread
            }
            if (inputs.isEmpty()) {
                runOnUiThread {
                    showState("NOTHING FOUND", "No pictures found",
                        "Pick images, ZIP files, or a folder that contains them.", 0f, WARN)
                    finishUi()
                }
                return@thread
            }
            val total = inputs.size
            val zipMode = total > ZIP_ABOVE
            val sink: ZipSink? = if (zipMode) {
                ZipSink(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()), total > PER_ZIP)
            } else null
            var ok = 0
            val aiNotes = mutableListOf<String>()
            val finished = mutableListOf<Result>()
            // The next picture is read while the current one is processed, and the previous one is
            // written to disk while the next one is processed (one background thread each).
            val decoder = Executors.newSingleThreadExecutor()
            val saver = Executors.newSingleThreadExecutor()
            var nextDecode: Future<Decoded?>? =
                decoder.submit(Callable<Decoded?> { decodeImage(inputs[0]) })
            var pending: Pending? = null
            val saveErrors = mutableListOf<String>()

            fun progressText(done: Int, name: String, extra: String = "") {
                val detail = if (extra.isEmpty()) name else "$name  \u00B7  $extra"
                runOnUiThread {
                    showState("UPSCALING", "$done / $total", detail, done.toFloat() / total)
                }
            }

            fun finishPending(p: Pending) {
                try {
                    val si = try {
                        p.future.get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                    if (si.uri != null) finished.add(Result(p.name, p.input, si.uri, p.srcH))
                    ok++
                } catch (e: Throwable) {
                    saveErrors.add("${p.name} (${e.message})")
                }
            }

            inputs.forEachIndexed { idx, input ->
                progressText(idx, input.name)
                try {
                    val cur = nextDecode
                    nextDecode = if (idx + 1 < total) {
                        decoder.submit(Callable<Decoded?> { decodeImage(inputs[idx + 1]) })
                    } else null
                    val dec = try {
                        cur?.get()
                    } catch (e: ExecutionException) {
                        throw e.cause ?: e
                    }
                    if (dec == null) {
                        readProblems.add("${input.name} (could not be read as a picture)")
                    } else {
                        val src = dec.img
                        // always: remove the mark, no crop, AI 2x (falls back to classic by itself if AI fails)
                        val out = ImageProcessor.process(
                            src, true, false, 2, assets, useGpu
                        ) { done, steps ->
                            progressText(idx, input.name, "AI step $done/$steps")
                        }
                        ImageProcessor.aiNote?.let { aiNotes.add("${input.name}: $it") }
                        // at most one save in flight: wait for the previous one before starting another
                        pending?.let { finishPending(it) }
                        pending = null
                        val outBmp = Bitmap.createBitmap(out.px, out.w, out.h, Bitmap.Config.ARGB_8888)
                        outBmp.setHasAlpha(false)
                        val baseName = input.name.substringBeforeLast('.').ifBlank { "image_$idx" }
                        val saveFuture = saver.submit(Callable<Saved> {
                            val ts = System.nanoTime()
                            try {
                                val uri: Uri? = if (sink != null) {
                                    sink.add(baseName, outBmp)
                                    null
                                } else {
                                    savePng(outBmp, baseName)
                                }
                                Saved(uri, (System.nanoTime() - ts) / 1_000_000L)
                            } finally {
                                outBmp.recycle()
                            }
                        })
                        val p = Pending(baseName, input, src.h, saveFuture)
                        // very large results: save right away instead of holding them in memory during the next picture
                        pending = if (out.w.toLong() * out.h > 12_000_000L) {
                            finishPending(p)
                            null
                        } else p
                    }
                } catch (e: Throwable) {
                    saveErrors.add("${input.name} (${e.message})")
                }
                progressText(idx + 1, input.name)
            }
            pending?.let { finishPending(it) }
            decoder.shutdown()
            saver.shutdown()
            var zipParts = 0
            try {
                sink?.close()
                zipParts = sink?.parts ?: 0
            } catch (e: Throwable) {
                saveErrors.add("ZIP (${e.message})")
            }
            // the unpacked ZIP images stay in the cache so the slider can show the originals;
            // they are cleared when the next batch starts
            prefs.edit().putBoolean("gpu_crashed", false).apply()
            val where = if (zipMode) {
                "Saved in $zipParts ZIP file" + (if (zipParts == 1) "" else "s") + " \u2192 Downloads/Sukko"
            } else {
                "Saved as PNG \u2192 Pictures/Upscaled"
            }
            val extra = StringBuilder(where)
            if (aiNotes.isNotEmpty()) extra.append("\nNote: ").append(aiNotes.take(2).joinToString("; "))
            val skipped = readProblems + saveErrors
            if (skipped.isNotEmpty()) extra.append("\nSkipped: ").append(skipped.take(5).joinToString("; "))
            runOnUiThread {
                showState("FINISHED", "$ok of $total upscaled", extra.toString(), 1f, GOOD)
                results.clear()
                results.addAll(finished)
                if (finished.isNotEmpty()) {
                    compareBtn.text = "Before / After (${finished.size})"
                    compareBtn.visibility = View.VISIBLE
                }
                finishUi()
            }
        }
    }

    /** Explains why the app last closed, if it died in the middle of a run. */
    private fun lastCloseNote(): String? {
        val prefs = getSharedPreferences("upscaler", MODE_PRIVATE)
        val javaCrash = prefs.getString("last_java_crash", null)
        val interrupted = prefs.getBoolean("run_in_progress", false)
        prefs.edit().remove("last_java_crash").putBoolean("run_in_progress", false).apply()
        if (javaCrash != null) return "The app closed last time because of an error: $javaCrash"
        if (!interrupted) return null
        var why = "reason unknown"
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val info = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
                if (info != null) {
                    val name = when (info.reason) {
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash inside the native AI library / GPU driver"
                        android.app.ApplicationExitInfo.REASON_CRASH -> "app error"
                        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed because the phone ran low on memory"
                        android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by the system (signal ${info.status})"
                        android.app.ApplicationExitInfo.REASON_ANR -> "app stopped responding"
                        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by you"
                        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "used too many resources"
                        else -> "system code ${info.reason}"
                    }
                    why = name + (info.description?.let { " ($it)" } ?: "")
                }
            } catch (_: Throwable) { }
        }
        return "The app closed in the middle of the last run. Reason: $why."
    }

    private fun finishUi() {
        getSharedPreferences("upscaler", MODE_PRIVATE).edit().putBoolean("run_in_progress", false).apply()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        running = false
        setBusy(false)
    }

    /** Saves one picture as a PNG in Pictures/Upscaled. */
    private fun savePng(bmp: Bitmap, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Upscaled")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Upscaled")
            dir.mkdirs()
            val file = File(dir, "$name.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return Uri.fromFile(file)
        }
    }
}
