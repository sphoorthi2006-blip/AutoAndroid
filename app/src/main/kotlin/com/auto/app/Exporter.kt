package com.auto.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import com.auto.core.Pipeline
import java.io.File
import kotlin.math.max

/** Builds the MP4: every scene = its picture for (end-start) seconds, the narration underneath. Text overlay, click sound and music are not done yet. */
object Exporter {
    private const val W = 1280
    private const val H = 720

    private fun blankImage(dir: File): File {
        val f = File(dir, "blank.png")
        if (!f.exists()) { val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); b.eraseColor(Color.BLACK); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return f
    }

    /** Must be called on the main thread. */
    fun export(ctx: Context, a: Pipeline.Analysis, narration: File?, onProgress: (Int) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val work = File(ctx.cacheDir, "export").apply { mkdirs() }
        val blank = blankImage(work)
        val video = a.scenes.map { s ->
            val dur = max(s.end - s.start, 0.04)
            val mi = MediaItem.Builder().setUri(Uri.fromFile(File(s.image ?: blank.path))).setImageDurationMs((dur * 1000).toLong()).build()
            EditedMediaItem.Builder(mi).setDurationUs((dur * 1_000_000).toLong()).setFrameRate(30)
                .setEffects(Effects(listOf(), listOf(Presentation.createForWidthAndHeight(W, H, Presentation.LAYOUT_SCALE_TO_FIT)))).build()
        }
        val sequences = ArrayList<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(video))
        if (narration != null) {
            val audio = a.match.used.filter { it.scenes.isNotEmpty() }.map { g ->
                val clip = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((g.scenes.first().start * 1000).toLong()).setEndPositionMs((g.scenes.last().end * 1000).toLong()).build()
                val mi = MediaItem.Builder().setUri(Uri.fromFile(narration)).setClippingConfiguration(clip).build()
                EditedMediaItem.Builder(mi).setRemoveVideo(true).build()
            }
            if (audio.isNotEmpty()) sequences.add(EditedMediaItemSequence(audio))
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
        } catch (e: Exception) { f.path }
    }
}
