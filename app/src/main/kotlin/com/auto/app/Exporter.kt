package com.auto.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.auto.core.ExportPlan
import com.auto.core.ExportSettings
import java.io.File
import kotlin.math.max

/** Builds the MP4: every scene = its picture for (end-start) seconds, the narration underneath. Scenes without a picture get a black screen with their text. Text over pictures, click sound and music are not done yet. */
object Exporter {
    const val W = 1280
    const val H = 720

    private fun blankImage(dir: File, w: Int = W, h: Int = H): File {
        val f = File(dir, "blank_${w}x$h.png")
        if (!f.exists()) { val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.BLACK); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return f
    }

    /** A scene without a picture: black screen with its text centred (a heading is shown in capitals). */
    private fun textScreen(dir: File, k: Int, text: String, W: Int = Exporter.W, H: Int = Exporter.H): File {
        val f = File(dir, "text_${W}x${H}_$k.png")
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); b.eraseColor(Color.BLACK)
        val c = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 54f * W / 1280f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        val lines = ArrayList<String>(); var cur = ""
        for (w in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(t) > W - 160 && cur.isNotEmpty()) { lines.add(cur); cur = w } else cur = t
        }
        if (cur.isNotEmpty()) lines.add(cur)
        val lh = paint.textSize * 1.3f; var y = (H - lh * lines.size) / 2f + paint.textSize
        for (l in lines.take(8)) { c.drawText(l, W / 2f, y, paint); y += lh }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }


    /** Exports an ExportPlan (see core/ExportPlan.kt): picture lengths come from exact ms boundaries and the narration is ONE full-length item,
     *  so video and audio cannot drift apart. Must be called on the main thread. NOT yet compiled/run on a device. */
    fun exportTimeline(ctx: Context, plan: ExportPlan.Plan, narration: File?, s: ExportSettings, onProgress: (Int) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val work = File(ctx.cacheDir, "export").apply { mkdirs() }
        val blank = blankImage(work, s.width, s.height)
        val effects = Effects(listOf(), listOf(Presentation.createForWidthAndHeight(s.width, s.height, Presentation.LAYOUT_SCALE_TO_FIT)))
        val video = plan.segments.mapIndexed { k, g ->
            val img = g.image ?: (if (g.text.isNotBlank()) textScreen(work, k, if (g.heading) g.text.uppercase() else g.text, s.width, s.height).path else blank.path)
            val mi = MediaItem.Builder().setUri(Uri.fromFile(File(img))).setImageDurationMs(g.durMs).build()
            EditedMediaItem.Builder(mi).setDurationUs(g.durMs * 1000).setFrameRate(s.fps).setEffects(effects).build()
        }
        val sequences = arrayListOf(EditedMediaItemSequence(video))
        if (narration != null) {
            val a = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(narration))).setRemoveVideo(true).build()
            sequences.add(EditedMediaItemSequence(listOf(a)))
        }
        val out = File(work, "auto_${System.currentTimeMillis()}.mp4")
        val handler = Handler(Looper.getMainLooper())
        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { handler.removeCallbacksAndMessages(null); onDone(publish(ctx, out)) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { handler.removeCallbacksAndMessages(null); onError(exportException.message ?: "Export failed") }
            }).build()
        transformer.start(Composition.Builder(sequences).build(), out.path)
        val holder = ProgressHolder()
        val poll = object : Runnable { override fun run() {
            if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress)
            handler.postDelayed(this, 500) } }
        handler.post(poll)
    }

    /** Copies the finished file to the phone's Movies folder (Android 10+); older phones keep it in the app folder. Returns where it is. */
    private fun publish(ctx: Context, f: File): String {
        if (Build.VERSION.SDK_INT < 29) return f.path
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Auto")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            "Movies/Auto/${f.name}"
        } catch (e: Exception) { "NOT copied to Movies (${e.message}). The file is only in the app cache, which Android may clear: ${f.path}" }
    }
}
