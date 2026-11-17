package com.ils.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ils.core.SlideMapSettings
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/**
 * One minimal screen: pick files, run, progress, result.
 * Needed: script, raw SRT, output folder (gives slides.json, slides.srt, report.txt).
 * Optional, for the MP4: audio, image folder (1.png, 2.png, ...), elements.json.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_SCRIPT = 1
        const val REQ_SRT = 2
        const val REQ_OUT = 3
        const val REQ_AUDIO = 4
        const val REQ_IMAGES = 5
        const val REQ_ELEMENTS = 6
    }

    private var scriptUri: Uri? = null
    private var srtUri: Uri? = null
    private var outTree: Uri? = null
    private var audioUri: Uri? = null
    private var imagesTree: Uri? = null
    private var elementsUri: Uri? = null

    private lateinit var scriptBtn: Button
    private lateinit var srtBtn: Button
    private lateinit var outBtn: Button
    private lateinit var audioBtn: Button
    private lateinit var imagesBtn: Button
    private lateinit var elementsBtn: Button
    private lateinit var runBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var splitEdit: EditText
    private lateinit var heightEdit: EditText
    private lateinit var fpsEdit: EditText
    private lateinit var progress: ProgressBar
    private lateinit var stage: TextView
    private lateinit var result: TextView

    private var pipeline: Pipeline? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Script + raw SRT → slide map.  Add audio + images (+ elements.json) → MP4."
            textSize = 15f
        })

        scriptBtn = button("1. Script (.txt)") { pickFile(REQ_SCRIPT) }
        srtBtn = button("2. Raw SRT") { pickFile(REQ_SRT) }
        outBtn = button("3. Output folder") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OUT) }
        audioBtn = button("4. Audio (optional, for the video)") { pickFile(REQ_AUDIO) }
        imagesBtn = button("5. Image folder (optional: 1.png, 2.png, …)") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_IMAGES) }
        elementsBtn = button("6. elements.json (optional)") { pickFile(REQ_ELEMENTS) }
        listOf(scriptBtn, srtBtn, outBtn, audioBtn, imagesBtn, elementsBtn).forEach { root.addView(it) }

        splitEdit = number(SlideMapSettings().splitAfterSec.toString(), decimal = true)
        heightEdit = number("720")
        fpsEdit = number("30")
        root.addView(row("Split paragraphs longer than (s, 0 = never): ", splitEdit))
        root.addView(row("Video height (px): ", heightEdit))
        root.addView(row("Frames per second: ", fpsEdit))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        runBtn = Button(this).apply { text = "Run"; isEnabled = false; setOnClickListener { run() } }
        cancelBtn = Button(this).apply { text = "Cancel"; isEnabled = false; setOnClickListener { pipeline?.cancelled = true } }
        buttons.addView(runBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        root.addView(progress)
        stage = TextView(this).apply { textSize = 13f }
        root.addView(stage)

        result = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(result) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun number(initial: String, decimal: Boolean = false) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        setText(initial)
        minEms = 4
    }

    private fun row(label: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply { text = label })
        addView(field)
    }

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_SCRIPT -> { scriptUri = uri; scriptBtn.text = "1. Script: ${displayName(uri)}" }
            REQ_SRT -> { srtUri = uri; srtBtn.text = "2. SRT: ${displayName(uri)}" }
            REQ_OUT -> { outTree = uri; outBtn.text = "3. Output: ${displayName(uri)}" }
            REQ_AUDIO -> { audioUri = uri; audioBtn.text = "4. Audio: ${displayName(uri)}" }
            REQ_IMAGES -> { imagesTree = uri; imagesBtn.text = "5. Images: ${displayName(uri)}" }
            REQ_ELEMENTS -> { elementsUri = uri; elementsBtn.text = "6. Elements: ${displayName(uri)}" }
        }
        runBtn.isEnabled = scriptUri != null && srtUri != null && outTree != null
    }

    private fun displayName(uri: Uri): String {
        if (DocumentsContract.isTreeUri(uri)) {
            return DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').ifEmpty { "(root)" }
        }
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun run() {
        val inputs = Inputs(
            script = scriptUri ?: return,
            srt = srtUri ?: return,
            outTree = outTree ?: return,
            audio = audioUri,
            imagesTree = imagesTree,
            elements = elementsUri,
            splitAfterSec = splitEdit.text.toString().toDoubleOrNull() ?: SlideMapSettings().splitAfterSec,
            videoHeight = heightEdit.text.toString().toIntOrNull() ?: 720,
            fps = fpsEdit.text.toString().toIntOrNull() ?: 30,
        )
        val p = Pipeline(applicationContext)
        pipeline = p

        runBtn.isEnabled = false
        cancelBtn.isEnabled = true
        progress.progress = 0
        progress.visibility = View.VISIBLE
        stage.text = "Starting…"
        result.text = ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // the video is made while the app is open

        thread(name = "ils-pipeline") {
            val message = try {
                p.run(inputs) { text, fraction ->
                    runOnUiThread {
                        stage.text = text
                        progress.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
                    }
                }
            } catch (c: CancellationException) {
                "Cancelled."
            } catch (t: Throwable) {
                "Failed: ${t.javaClass.simpleName}: ${t.message}"
            }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progress.visibility = View.GONE
                stage.text = ""
                result.text = message
                cancelBtn.isEnabled = false
                runBtn.isEnabled = true
                pipeline = null
            }
        }
    }
}
