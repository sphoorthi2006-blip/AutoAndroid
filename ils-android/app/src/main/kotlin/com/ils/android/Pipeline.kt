package com.ils.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaExtractor
import android.net.Uri
import android.provider.DocumentsContract
import ils.core.ElementList
import ils.core.ElementSettings
import ils.core.ElementSpec
import ils.core.Choreography
import ils.core.Elements
import ils.core.SlideMap
import ils.core.SlideMapSettings
import ils.core.toReport
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Inputs(
    val script: Uri,
    val srt: Uri,
    val outTree: Uri,
    val audio: Uri?,
    val imagesTree: Uri?,
    val elements: Uri?,
    val splitAfterSec: Double,
    val videoHeight: Int,
    val fps: Int,
)

/**
 * The whole job:
 *  1. slide map (script + raw SRT)  -> slides.json, slides.srt, report.txt
 *  2. if audio and an image folder are given: elements -> handwriting/pop-in -> MP4 with the audio
 */
class Pipeline(private val ctx: Context) {
    @Volatile var cancelled = false

    private fun check() { if (cancelled) throw CancellationException("Cancelled") }

    fun run(inp: Inputs, progress: (String, Double) -> Unit): String {
        val log = StringBuilder()
        val slideSettings = SlideMapSettings(splitAfterSec = inp.splitAfterSec)
        val st = ElementSettings()

        // ---- 1. slide map
        progress("Building the slide map", 0.0)
        val r = SlideMap.build(readText(inp.script), readText(inp.srt), slideSettings)
        val report = SlideMap.toReport(r)
        writeText(inp.outTree, "slides.json", "application/json", SlideMap.toJson(r))
        writeText(inp.outTree, "slides.srt", "application/x-subrip", SlideMap.toSrt(r))
        writeText(inp.outTree, "report.txt", "text/plain", report)
        log.appendLine("Slide map done: slides.json, slides.srt and report.txt are in the output folder.")
        log.appendLine()
        log.append(report)
        if (inp.audio == null || inp.imagesTree == null) {
            log.appendLine()
            log.appendLine("No video made: pick the audio file and the image folder to make the MP4.")
            return log.toString()
        }

        // ---- 2. inputs for the video
        val specs: Map<Int, List<ElementSpec>> = inp.elements?.let { ElementList.parse(readText(it)) } ?: emptyMap()
        val images = listImages(inp.imagesTree)
        val missing = r.slides.map { it.image }.filter { it !in images }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Images missing in the folder (named 1.png, 2.jpg, ...): " + missing.joinToString(", "))
        }
        val unknown = specs.keys.filter { k -> r.slides.none { it.image == k } }
        if (unknown.isNotEmpty()) log.appendLine("Note: elements.json has entries for image(s) ${unknown.joinToString(", ")} that are not in the slide map.")

        val h = inp.videoHeight.coerceIn(240, 2160) / 2 * 2
        val w = (h * 16.0 / 9.0).roundToInt() / 2 * 2
        val fps = inp.fps.coerceIn(10, 60)
        val total = max(1, ceil(r.audioEnd * fps).toInt())

        val tmpVideo = File(ctx.cacheDir, "ils-video.mp4")
        val tmpAudio = File(ctx.cacheDir, "ils-audio.m4a")
        val tmpFinal = File(ctx.cacheDir, "ils-final.mp4")
        listOf(tmpVideo, tmpAudio, tmpFinal).forEach { it.delete() }
        val warnings = ArrayList<String>()
        val writer = VideoWriter(tmpVideo.absolutePath, w, h, fps)
        try {
            // ---- 3. frames
            var frameNo = 0
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val argb = IntArray(w * h)
            for ((k, slide) in r.slides.withIndex()) {
                check()
                val frameEnd = if (k == r.slides.size - 1) total else min(total, (slide.end * fps).roundToInt())
                if (frameEnd <= frameNo) continue
                progress("Slide ${slide.image} of ${r.slides.size}: preparing", 0.05 + 0.75 * frameNo / total)
                val px = loadPixels(images.getValue(slide.image), w, h)
                val plan = Choreography.plan(slide, px, w, h, specs[slide.image] ?: emptyList(), st)
                plan.warnings.forEach { warnings.add("Image ${slide.image}: $it") }
                val renderer = SlideRenderer(w, h, px, plan, Elements.estimateBackground(px), st)
                var prev: IntArray? = null
                while (frameNo < frameEnd) {
                    check()
                    val t = frameNo.toDouble() / fps
                    val state = plan.state(t)
                    if (prev != null && state.contentEquals(prev)) writer.write(null)
                    else {
                        renderer.draw(canvas, t)
                        bmp.getPixels(argb, 0, w, 0, 0, w, h)
                        writer.write(argb)
                        prev = state
                    }
                    frameNo++
                    if (frameNo % fps == 0) progress("Slide ${slide.image} of ${r.slides.size}: drawing", 0.05 + 0.75 * frameNo / total)
                }
                renderer.recycle()
            }
            bmp.recycle()
            writer.finish()
        } catch (t: Throwable) {
            writer.release()
            throw t
        }

        // ---- 4. audio
        progress("Preparing the audio", 0.80)
        val audioEx = MediaExtractor()
        try {
            if (AudioTranscoder.isAac(ctx, inp.audio)) {
                audioEx.setDataSource(ctx, inp.audio, null)
            } else {
                AudioTranscoder.toAac(ctx, inp.audio, tmpAudio.absolutePath, { cancelled }) { progress("Converting the audio to AAC", 0.80 + 0.12 * it) }
                audioEx.setDataSource(tmpAudio.absolutePath)
            }
            // ---- 5. one MP4
            progress("Joining video and audio", 0.93)
            Mp4Mux.combine(tmpVideo.absolutePath, audioEx, tmpFinal.absolutePath, (r.audioEnd * 1_000_000).toLong() + 500_000L) { cancelled }
        } finally {
            audioEx.release()
        }

        // ---- 6. copy to the chosen folder
        progress("Saving the video", 0.97)
        copyToTree(inp.outTree, "video.mp4", "video/mp4", tmpFinal)
        listOf(tmpVideo, tmpAudio, tmpFinal).forEach { it.delete() }

        log.appendLine()
        log.appendLine("Video done: video.mp4 (${w}x$h, $fps fps, ${"%.1f".format(total.toDouble() / fps)} s) is in the output folder.")
        if (warnings.isNotEmpty()) {
            log.appendLine()
            log.appendLine("Element warnings:")
            warnings.forEach { log.appendLine("  $it") }
        }
        return log.toString()
    }

    // ------------------------------------------------------------------ files

    private fun readText(uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw IllegalStateException("Cannot open the file.")

    private fun createIn(tree: Uri, name: String, mime: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name)
            ?: throw IllegalStateException("Cannot create $name in the output folder.")
    }

    private fun writeText(tree: Uri, name: String, mime: String, text: String) {
        val doc = createIn(tree, name, mime)
        ctx.contentResolver.openOutputStream(doc, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: throw IllegalStateException("Cannot write $name.")
    }

    private fun copyToTree(tree: Uri, name: String, mime: String, file: File) {
        val doc = createIn(tree, name, mime)
        ctx.contentResolver.openOutputStream(doc, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("Cannot write $name.")
    }

    /** image number -> file. A file counts when its name ends in a number (1.png, 02.jpg, slide-3.webp). */
    private fun listImages(tree: Uri): Map<Int, Uri> {
        val out = HashMap<Int, Uri>()
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (!mime.startsWith("image/")) continue
                val n = Regex("(\\d+)$").find(name.substringBeforeLast('.'))?.value?.toIntOrNull() ?: continue
                out.putIfAbsent(n, DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))
            }
        }
        return out
    }

    /** Decodes an image, scales it to fit w x h, centres it on its own background colour. */
    private fun loadPixels(uri: Uri, w: Int, h: Int): IntArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0) { "Cannot read an image file." }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Cannot decode an image file.")
        val scale = min(w.toDouble() / src.width, h.toDouble() / src.height)
        val nw = max(1, (src.width * scale).roundToInt()).coerceAtMost(w)
        val nh = max(1, (src.height * scale).roundToInt()).coerceAtMost(h)
        val flat = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
        val c = Canvas(flat)
        c.drawColor(Color.WHITE)
        c.drawBitmap(Bitmap.createScaledBitmap(src, nw, nh, true), 0f, 0f, null)
        val px = IntArray(nw * nh)
        flat.getPixels(px, 0, nw, 0, 0, nw, nh)
        src.recycle(); flat.recycle()
        return Elements.letterbox(px, nw, nh, w, h)
    }
}
