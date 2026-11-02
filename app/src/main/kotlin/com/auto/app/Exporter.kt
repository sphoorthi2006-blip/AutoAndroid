package com.auto.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.auto.core.AudioSettings
import com.auto.core.ClickTrack
import com.auto.core.ExportOptions
import com.auto.core.ExportPlan
import com.auto.core.ExportSettings
import com.auto.core.IntermediateFormat
import com.auto.core.OverlayStyle
import com.auto.core.Pipeline
import com.auto.core.TransitionKind
import com.auto.core.UpscaleFactor
import com.auto.core.WavFile
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Builds the MP4: every scene = its picture for (end-start) seconds, the narration underneath. Scenes without a picture get a black screen with their text. Captions, text, effects, music and the click sound (mixed into the narration) are in exportTimeline. */
object Exporter {
    /** The running export (main thread only): lets Cancel stop both AI upscaling and Media3 encoding and clean up temporary files. */
    private var current: Transformer? = null
    private var currentHandler: Handler? = null
    private var currentCancelFlag: AtomicBoolean? = null
    private var currentUpscaleDir: File? = null
    private var currentTempFiles: MutableList<File> = ArrayList()

    /** MediaStore uri of the last published video (null when it could not be copied); used by Share. */
    var lastUri: Uri? = null

    fun cancel() {
        currentCancelFlag?.set(true)
        try { current?.cancel() } catch (_: Exception) {}
        currentHandler?.removeCallbacksAndMessages(null)
        cleanupCurrentTemp(deleteOutput = true)
        current = null
        currentHandler = null
        currentCancelFlag = null
    }

    @Synchronized
    private fun cleanupCurrentTemp(deleteOutput: Boolean) {
        try {
            currentUpscaleDir?.deleteRecursively()
        } catch (_: Exception) {}
        currentUpscaleDir = null
        val snapshot = ArrayList(currentTempFiles)
        currentTempFiles.clear()
        for (f in snapshot) {
            if (!deleteOutput && f.name.startsWith("auto_") && f.name.endsWith(".mp4")) continue
            try {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } catch (_: Exception) {}
        }
    }

    const val W = 1280
    const val H = 720

    private fun blankImage(dir: File, w: Int = W, h: Int = H): File {
        val f = File(dir, "blank_${w}x$h.png")
        if (!f.exists()) { val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.BLACK); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return f
    }

    /** The picture fitted into a W x H black frame with the scene's text burned in using the plan's caption style (OverlayRenderer: font, colour, outline, shadow, box, position). */
    private fun captioned(
        ctx: Context,
        dir: File,
        k: Int,
        imagePath: String,
        text: String,
        W: Int,
        H: Int,
        style: OverlayStyle,
        png: Boolean = false
    ): File {
        val ext = if (png) "png" else "jpg"
        val f = File(dir, "cap_${W}x${H}_$k.$ext")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max(W, H)) sample *= 2
        val src = BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return File(imagePath)
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); out.eraseColor(Color.BLACK)
        val c = Canvas(out)
        val sc = min(W.toFloat() / src.width, H.toFloat() / src.height); val dw = src.width * sc; val dh = src.height * sc
        c.drawBitmap(src, null, RectF((W - dw) / 2f, (H - dh) / 2f, (W + dw) / 2f, (H + dh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        OverlayRenderer.draw(ctx, c, W, H, text, style)
        f.outputStream().use {
            if (png) out.compress(Bitmap.CompressFormat.PNG, 100, it)
            else out.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        out.recycle()
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


    /** A silent mono 44.1 kHz WAV as long as the video: the base track for the click sound when the project has no narration. */
    private fun silentTrack(dir: File, ms: Long): File {
        val rate = 44100; val frames = ms * rate / 1000; val f = File(dir, "silence_$ms.wav")
        if (!f.exists() || f.length() != 44 + frames * 2) f.outputStream().buffered().use { o ->
            o.write(WavFile.header(rate, 1, frames))
            val z = ByteArray(65536); var left = frames * 2
            while (left > 0) { val n = min(left, z.size.toLong()).toInt(); o.write(z, 0, n); left -= n }
        }
        return f
    }

    /** Exports an ExportPlan (see core/ExportPlan.kt; resolution and fps come from ExportSettings, captions = burn the scene text over the pictures): picture lengths come from exact ms boundaries and the narration is ONE full-length item,
     *  so video and audio cannot drift apart. When AI Upscaling is enabled (2x or 4x), unique source images in the plan are upscaled once into an app-private temporary folder before Media3 rendering begins. Must be called on the main thread. */
    fun exportTimeline(
        ctx: Context,
        plan: ExportPlan.Plan,
        narration: File?,
        s: ExportSettings,
        captions: Boolean,
        onProgress: (Int) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
        upscale: UpscaleFactor = UpscaleFactor.OFF,
        upscaleFormat: IntermediateFormat = IntermediateFormat.JPEG,
        onStatus: (String) -> Unit = {}
    ) {
        val work = File(ctx.cacheDir, "export").apply { mkdirs() }
        // Clean up any stale upscaled temp folders from previous runs
        work.listFiles()?.filter { it.isDirectory && it.name.startsWith("upscaled_") }?.forEach {
            try { it.deleteRecursively() } catch (_: Exception) {}
        }

        val cancelFlag = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        currentCancelFlag = cancelFlag
        currentHandler = handler
        current = null
        lastUri = null
        currentTempFiles.clear()

        val hasUpscaleWork = upscale != UpscaleFactor.OFF && ExportOptions.uniqueSourceImages(plan).any { File(it).isFile }
        if (!hasUpscaleWork) {
            startMedia3Pipeline(
                ctx = ctx,
                work = work,
                plan = plan,
                narration = narration,
                s = s,
                captions = captions,
                usePngCaptions = false,
                upscaleActive = false,
                cancelFlag = cancelFlag,
                handler = handler,
                onProgress = onProgress,
                onDone = onDone,
                onError = onError
            )
            return
        }

        val upscaleDir = File(work, "upscaled_${System.currentTimeMillis()}").apply { mkdirs() }
        currentUpscaleDir = upscaleDir

        thread(name = "export-upscaler") {
            try {
                val processedPlan = SafelightExportProcessor.processPlan(
                    ctx = ctx,
                    plan = plan,
                    factor = upscale,
                    format = upscaleFormat,
                    tempDir = upscaleDir,
                    cancelled = cancelFlag,
                    onProgress = { pct, statusMsg ->
                        handler.post {
                            if (!cancelFlag.get()) {
                                onProgress(pct)
                                onStatus(statusMsg)
                            }
                        }
                    }
                )
                handler.post {
                    if (cancelFlag.get()) {
                        cleanupCurrentTemp(deleteOutput = true)
                        return@post
                    }
                    onProgress(50)
                    onStatus("Exporting... 50%")
                    startMedia3Pipeline(
                        ctx = ctx,
                        work = work,
                        plan = processedPlan,
                        narration = narration,
                        s = s,
                        captions = captions,
                        usePngCaptions = upscaleFormat.png,
                        upscaleActive = true,
                        cancelFlag = cancelFlag,
                        handler = handler,
                        onProgress = onProgress,
                        onDone = onDone,
                        onError = onError
                    )
                }
            } catch (_: Upscaler.Cancelled) {
                cleanupCurrentTemp(deleteOutput = true)
            } catch (e: Throwable) {
                cleanupCurrentTemp(deleteOutput = true)
                handler.post {
                    if (!cancelFlag.get()) {
                        onError(e.message ?: "AI image upscaling failed")
                    }
                }
            }
        }
    }

    private fun startMedia3Pipeline(
        ctx: Context,
        work: File,
        plan: ExportPlan.Plan,
        narration: File?,
        s: ExportSettings,
        captions: Boolean,
        usePngCaptions: Boolean,
        upscaleActive: Boolean,
        cancelFlag: AtomicBoolean,
        handler: Handler,
        onProgress: (Int) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (cancelFlag.get()) {
            cleanupCurrentTemp(deleteOutput = true)
            return
        }
        try {
            val blank = blankImage(work, s.width, s.height)
            currentTempFiles.add(blank)
            val video = plan.segments.mapIndexed { k, g ->
                // fit to the output frame first, then (only when this picture needs it) slide/zoom movement and colour/opacity/fade
                val list = ArrayList<Effect>()
                list.add(Presentation.createForWidthAndHeight(s.width, s.height, Presentation.LAYOUT_SCALE_TO_FIT))
                val own = g.transition; val nextFade = g.nextTransition?.kind == TransitionKind.FADE
                if (own.kind == TransitionKind.SLIDE || own.kind == TransitionKind.ZOOM) list.add(ClipMove(own, g.nextTransition, g.durMs))
                if (!g.fx.isIdentity || own.kind == TransitionKind.FADE || own.kind == TransitionKind.ZOOM || nextFade) list.add(ClipColor(g.fx, own, g.nextTransition, g.durMs))
                val effects = Effects(listOf(), list)
                val img = when {
                    g.image != null && captions && g.text.isNotBlank() && plan.captions.isEmpty() -> {
                        val cf = captioned(ctx, work, k, g.image!!, g.text, s.width, s.height, plan.captionStyle, png = usePngCaptions)
                        if (cf.path != g.image) currentTempFiles.add(cf)
                        cf.path // captions switch ON
                    }
                    g.image != null -> g.image!!
                    g.text.isNotBlank() -> {
                        val tf = textScreen(work, k, if (g.heading) g.text.uppercase() else g.text, s.width, s.height)
                        currentTempFiles.add(tf)
                        tf.path // a scene without a picture shows its text
                    }
                    else -> blank.path
                }
                val mi = MediaItem.Builder().setUri(Uri.fromFile(File(img))).setImageDurationMs(g.durMs).build()
                EditedMediaItem.Builder(mi).setDurationUs(g.durMs * 1000).setFrameRate(s.fps).setEffects(effects).build()
            }
            val sequences = arrayListOf(EditedMediaItemSequence(video))
            // narration (with volume/fades/mute), then the click sound mixed in at every picture start. No narration but the click is on: a silent base track carries the clicks.
            val clickStarts = ClickTrack.startsMs(plan)
            val clickOn = plan.mix.click.enabled && clickStarts.isNotEmpty()
            val narrFile = narration ?: if (clickOn) silentTrack(work, plan.totalMs).also { currentTempFiles.add(it) } else null
            if (narrFile != null) {
                val ns = if (narration != null) plan.mix.narration else AudioSettings()
                val procs = ArrayList<AudioProcessor>()
                if (!ns.isIdentity) procs.add(AudioGainProcessor(ns, plan.totalMs))
                if (clickOn) procs.add(ClickMixProcessor(ClickSound.wav(ctx), clickStarts, plan.mix.click.volume))   // after the gain: narration volume/mute never change the click
                val a = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(narrFile))).setRemoveVideo(true).setEffects(Effects(procs, listOf())).build()
                sequences.add(EditedMediaItemSequence(listOf(a)))
            }
            // music bed: starts at 0, own gain, loops (looping sequence ends with the longest non-looping one = the video). Missing file = skipped.
            plan.mix.music?.takeIf { File(it.path).exists() }?.let { m ->
                val ms = m.settings
                val procs = if (ms.isIdentity) listOf<AudioProcessor>() else listOf<AudioProcessor>(AudioGainProcessor(ms, plan.totalMs))
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(m.path)))).setRemoveVideo(true).setEffects(Effects(procs, listOf())).build()
                sequences.add(EditedMediaItemSequence(listOf(item), m.loop))
            }
            // text overlays (with their animations) are drawn over the whole composition, so their times are video times
            // caption CUES (imported / edited in the Captions sheet) are timed overlays with the caption style; without cues the scene text is burned into the pictures (above)
            val cueTexts = if (captions) plan.captions.map { com.auto.core.TextClip(-it.id, it.startMs, it.endMs, it.text, plan.captionStyle) } else emptyList()
            val overlayTexts = plan.texts + cueTexts
            val compositionEffects = if (overlayTexts.isEmpty()) Effects.EMPTY else Effects(listOf(), listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(TextBitmapOverlay(ctx, s.width, s.height, overlayTexts)))))
            val out = File(work, "auto_${System.currentTimeMillis()}.mp4")
            currentTempFiles.add(out)
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacksAndMessages(null)
                        val where = publish(ctx, out)
                        cleanupCurrentTemp(deleteOutput = lastUri != null)
                        onDone(where)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacksAndMessages(null)
                        cleanupCurrentTemp(deleteOutput = true)
                        onError(exportException.message ?: "Export failed")
                    }
                }).build()
            current = transformer
            transformer.start(Composition.Builder(sequences).setEffects(compositionEffects).build(), out.path)
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (cancelFlag.get()) return
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        val p = if (upscaleActive) 50 + (holder.progress / 2) else holder.progress
                        onProgress(p.coerceIn(0, 100))
                    }
                    handler.postDelayed(this, 500)
                }
            }
            handler.post(poll)
        } catch (e: Throwable) {
            cleanupCurrentTemp(deleteOutput = true)
            onError(e.message ?: "Export failed")
        }
    }

    /** Older per-group export, still used when the pictures do not cover the whole script. Must be called on the main thread. */
    fun export(ctx: Context, a: Pipeline.Analysis, narration: File?, onProgress: (Int) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit) {
        val work = File(ctx.cacheDir, "export").apply { mkdirs() }
        val blank = blankImage(work)
        val video = a.scenes.mapIndexed { k, s ->
            val dur = max(s.end - s.start, 0.04)
            val img = s.image ?: (if (s.text.isNotBlank()) textScreen(work, k, if (s.item?.heading == true) s.text.uppercase() else s.text).path else blank.path)
            val mi = MediaItem.Builder().setUri(Uri.fromFile(File(img))).setImageDurationMs((dur * 1000).toLong()).build()
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
            lastUri = uri
            "Movies/Auto/${f.name}"
        } catch (e: Exception) { "NOT copied to Movies (${e.message}). The file is only in the app cache, which Android may clear: ${f.path}" }
    }
}
