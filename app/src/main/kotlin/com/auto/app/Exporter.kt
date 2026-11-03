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
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
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
import com.auto.core.DiagStage
import com.auto.core.Diagnostics
import com.auto.core.ExportJob
import com.auto.core.ExportJobStatus
import com.auto.core.ExportJobStore
import com.auto.core.ExportOptions
import com.auto.core.ExportPlan
import com.auto.core.ExportSettings
import com.auto.core.ExportStage
import com.auto.core.ExportTracker
import com.auto.core.IntermediateFormat
import com.auto.core.MediaLibrary
import com.auto.core.OutputVerificationResult
import com.auto.core.OverlayStyle
import com.auto.core.Pipeline
import com.auto.core.StorageEstimate
import com.auto.core.StreamingPngWriter
import com.auto.core.TransitionKind
import com.auto.core.UpscaleEngine
import com.auto.core.UpscaleFactor
import com.auto.core.UpscaleProfile
import com.auto.core.WavFile
import com.google.common.collect.ImmutableList
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Deflater
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Builds the MP4: every scene = its picture or solid-colour replacement for (end-start) seconds, the narration underneath.
 * Phase 1, 3, 4, 5, C2 & Performance Directive Phases 1 & 4 upgrades:
 * - High-resolution monotonic `System.nanoTime()` instrumentation across all pipeline stages (`PROJECT_PREP`, `UPSCALE_QUEUE_WAIT`,
 *   `CACHE_LOOKUP`, `IMAGE_DECODE`, `UPSCALE`, `PNG_ENCODE_WRITE`, `AUDIO_PREP`, `PREPARE`, `RENDER`, `FINALIZE`, `MEDIASTORE_PUBLISH`).
 * - Parallel audio track preparation overlapped with image upscaling.
 * - Deterministic deduplication of prepared caption frames (`preparedCaptionCache`) and fast lossless `StreamingPngWriter` (`Deflater.BEST_SPEED = 1`)
 *   so repeated scenes never re-decode or re-encode the same image.
 * - Visible foreground-service startup warning surfacing (`ExportForegroundService.notifyProgress`).
 * - Post-cancellation write prevention and idempotent retry cleanup of any stale `IS_PENDING = 1` `MediaStore` URI.
 */
object Exporter {
    private var current: Transformer? = null
    private var currentHandler: Handler? = null
    private var currentCancelFlag: AtomicBoolean? = null
    // Thread-safe: the audio-prep worker thread and the export thread both register temp files.
    private var currentTempFiles: MutableList<File> = java.util.Collections.synchronizedList(ArrayList<File>())
    @Volatile var activeJob: ExportJob? = null
        private set

    /** MediaStore uri of the last published video (null when it could not be copied); used by Share. */
    var lastUri: Uri? = null

    fun jobStore(ctx: Context): ExportJobStore = ExportJobStore(File(ctx.filesDir, "export_jobs"))

    @Synchronized
    fun cancel(ctx: Context? = null) {
        currentCancelFlag?.set(true)
        try { current?.cancel() } catch (_: Exception) {}
        currentHandler?.removeCallbacksAndMessages(null)
        cleanupCurrentTemp(deleteOutput = true)
        activeJob?.let { j ->
            if (ctx != null && j.pendingMediaStoreUri.isNotBlank()) {
                deletePendingMediaStoreUri(ctx, j.pendingMediaStoreUri)
            }
            val cancelledJob = j.copy(
                status = ExportJobStatus.CANCELLED,
                errorReason = "Cancelled by user.",
                pendingMediaStoreUri = "",
                outputUri = ""
            )
            activeJob = cancelledJob
            if (ctx != null) {
                try { jobStore(ctx).save(cancelledJob) } catch (_: Exception) {}
            }
        }
        if (ctx != null) {
            ExportForegroundService.stop(ctx)
        }
        current = null
        currentHandler = null
        currentCancelFlag = null
    }

    @Synchronized
    fun markInterruptedBySystem(ctx: Context, reason: String) {
        currentCancelFlag?.set(true)
        try { current?.cancel() } catch (_: Exception) {}
        currentHandler?.removeCallbacksAndMessages(null)
        cleanupCurrentTemp(deleteOutput = true)
        activeJob?.let { j ->
            if (j.pendingMediaStoreUri.isNotBlank()) {
                deletePendingMediaStoreUri(ctx, j.pendingMediaStoreUri)
            }
            val interruptedJob = j.copy(
                status = ExportJobStatus.INTERRUPTED,
                errorReason = reason,
                pendingMediaStoreUri = ""
            )
            activeJob = interruptedJob
            try { jobStore(ctx).save(interruptedJob) } catch (_: Exception) {}
        }
        ExportForegroundService.stop(ctx)
        current = null
        currentHandler = null
        currentCancelFlag = null
    }

    private fun deletePendingMediaStoreUri(ctx: Context, uriStr: String) {
        if (uriStr.isBlank()) return
        try {
            ctx.contentResolver.delete(Uri.parse(uriStr), null, null)
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun cleanupCurrentTemp(deleteOutput: Boolean) {
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

    private fun blankImage(dir: File, w: Int = W, h: Int = H): File =
        solidScreen(dir, Color.BLACK, null, w, h, null)

    /** Fast StreamingPngWriter helper for encoding an in-memory `Bitmap` at `Deflater.BEST_SPEED = 1`. */
    private fun writeBitmapFastPng(bm: Bitmap, outFile: File) {
        val w = bm.width
        val h = bm.height
        val stripRows = min(h, 384)
        val stripPx = IntArray(w * stripRows)
        StreamingPngWriter.writeFile(outFile, w, h, hasAlpha = false, compressionLevel = Deflater.BEST_SPEED) { writer ->
            var y = 0
            while (y < h) {
                val rows = min(stripRows, h - y)
                bm.getPixels(stripPx, 0, w, 0, y, w, rows)
                writer.writeStrip(stripPx, rows = rows, stripOffset = 0, stripStride = w)
                y += rows
            }
        }
    }

    /** Phase 3 (Section 12): Solid-colour frame (Black, White, or Custom ARGB) with optional burned-in scene caption text above it. */
    private fun solidScreen(
        dir: File,
        argbColor: Int,
        k: Int?,
        w: Int = W,
        h: Int = H,
        captionText: String? = null,
        ctx: Context? = null,
        style: OverlayStyle? = null
    ): File {
        val hex = String.format("%08X", argbColor)
        val suffix = if (captionText.isNullOrBlank()) "solid_${hex}_${w}x$h.png" else "solid_cap_${hex}_${w}x${h}_${k ?: 0}.png"
        val f = File(dir, suffix)
        if (captionText.isNullOrBlank() && f.exists() && f.length() > 0L) return f
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(argbColor or (0xFF shl 24))
            if (!captionText.isNullOrBlank() && ctx != null && style != null) {
                val c = Canvas(b)
                OverlayRenderer.draw(ctx, c, w, h, captionText, style)
            }
            writeBitmapFastPng(b, f)
        } finally {
            b.recycle()
        }
        return f
    }

    /** The picture fitted into a W x H black frame with the scene's text burned in using the plan's caption style. */
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
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        try {
            out.eraseColor(Color.BLACK)
            val c = Canvas(out)
            val sc = min(W.toFloat() / src.width, H.toFloat() / src.height)
            val dw = src.width * sc
            val dh = src.height * sc
            c.drawBitmap(src, null, RectF((W - dw) / 2f, (H - dh) / 2f, (W + dw) / 2f, (H + dh) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            OverlayRenderer.draw(ctx, c, W, H, text, style)
            if (png) {
                writeBitmapFastPng(out, f)
            } else {
                f.outputStream().buffered(65536).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }
        } finally {
            if (!src.isRecycled) src.recycle()
            out.recycle()
        }
        return f
    }

    /** A scene without a picture or replacement colour: black screen with its text centred (a heading is shown in capitals). */
    private fun textScreen(dir: File, k: Int, text: String, W: Int = Exporter.W, H: Int = Exporter.H): File {
        val f = File(dir, "text_${W}x${H}_$k.png")
        val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(Color.BLACK)
            val c = Canvas(b)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 54f * W / 1280f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val lines = ArrayList<String>()
            var cur = ""
            for (w in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val t = if (cur.isEmpty()) w else "$cur $w"
                if (paint.measureText(t) > W - 160 && cur.isNotEmpty()) { lines.add(cur); cur = w } else cur = t
            }
            if (cur.isNotEmpty()) lines.add(cur)
            val lh = paint.textSize * 1.3f
            var y = (H - lh * lines.size) / 2f + paint.textSize
            for (l in lines.take(8)) { c.drawText(l, W / 2f, y, paint); y += lh }
            writeBitmapFastPng(b, f)
        } finally {
            b.recycle()
        }
        return f
    }

    /** A silent mono 44.1 kHz WAV as long as the video: the base track for the click sound when the project has no narration. */
    private fun silentTrack(dir: File, ms: Long): File {
        val rate = 44100
        val frames = ms * rate / 1000
        val f = File(dir, "silence_$ms.wav")
        if (!f.exists() || f.length() != 44 + frames * 2) f.outputStream().buffered().use { o ->
            o.write(WavFile.header(rate, 1, frames))
            val z = ByteArray(65536)
            var left = frames * 2
            while (left > 0) {
                val n = min(left, z.size.toLong()).toInt()
                o.write(z, 0, n)
                left -= n
            }
        }
        return f
    }

    /** Queries available free storage via StatFs and runs ExportTracker.estimateStorage. */
    fun checkStorage(
        ctx: Context,
        plan: ExportPlan.Plan,
        s: ExportSettings,
        upscale: UpscaleFactor,
        format: IntermediateFormat
    ): StorageEstimate {
        val freeBytes = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
        return ExportTracker.estimateStorage(plan, s, upscale, format, freeBytes)
    }

    /** Verifies exported MP4 playability, duration tolerance, dimensions, and audio track using MediaMetadataRetriever in a finally block. */
    fun verifyOutputFile(
        outFile: File,
        expectedDurationMs: Long,
        expectedSettings: ExportSettings,
        expectedAudio: Boolean
    ): OutputVerificationResult {
        if (!outFile.isFile || outFile.length() <= 0L) {
            return ExportTracker.verifyOutputMetadata(
                fileExists = false,
                fileLengthBytes = 0L,
                playable = false,
                expectedDurationMs = expectedDurationMs,
                actualDurationMs = 0L,
                expectedWidth = expectedSettings.width,
                expectedHeight = expectedSettings.height,
                actualWidth = 0,
                actualHeight = 0,
                expectedAudio = expectedAudio,
                actualAudio = false
            )
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(outFile.path)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != null || (w > 0 && h > 0)
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.let {
                it.equals("yes", ignoreCase = true) || it == "1" || it.equals("true", ignoreCase = true)
            } ?: false
            ExportTracker.verifyOutputMetadata(
                fileExists = true,
                fileLengthBytes = outFile.length(),
                playable = hasVideo && durMs > 0L,
                expectedDurationMs = expectedDurationMs,
                actualDurationMs = max(0L, durMs),
                expectedWidth = expectedSettings.width,
                expectedHeight = expectedSettings.height,
                actualWidth = w,
                actualHeight = h,
                expectedAudio = expectedAudio,
                actualAudio = hasAudio
            )
        } catch (_: Throwable) {
            ExportTracker.verifyOutputMetadata(
                fileExists = true,
                fileLengthBytes = outFile.length(),
                playable = false,
                expectedDurationMs = expectedDurationMs,
                actualDurationMs = 0L,
                expectedWidth = expectedSettings.width,
                expectedHeight = expectedSettings.height,
                actualWidth = 0,
                actualHeight = 0,
                expectedAudio = expectedAudio,
                actualAudio = false
            )
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /**
     * Exports an ExportPlan with full Phase 1–6 & C1–C4 observability, monotonic `System.nanoTime()` timers,
     * parallel audio preparation, ForegroundService execution, atomic job persistence, idempotent retry cleanup,
     * storage guard, persistent upscale caching, and output verification.
     */
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
        upscaleFormat: IntermediateFormat = IntermediateFormat.PNG,
        upscaleProfile: UpscaleProfile = UpscaleProfile.MANUAL,
        strictUpscale: Boolean = false,
        projectId: String = "active",
        projectName: String = "Project",
        onStatus: (String) -> Unit = {},
        onJobUpdate: (ExportJob) -> Unit = {}
    ) {
        val exportStartNs = System.nanoTime()
        val appCtx = ctx.applicationContext
        val work = File(appCtx.cacheDir, "export").apply { mkdirs() }
        val store = jobStore(appCtx)
        DiagnosticsLog.beginRun()

        // 1. Storage estimate and guard before starting export
        val storageEst = checkStorage(appCtx, plan, s, upscale, upscaleFormat)
        if (!storageEst.sufficient && storageEst.freeBytes < storageEst.rawTotalBytes) {
            val msg = storageEst.warningMessage ?: "Insufficient free storage to safely complete export."
            onError(msg)
            return
        }

        val cancelFlag = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        currentCancelFlag = cancelFlag
        currentHandler = handler
        current = null
        lastUri = null
        currentTempFiles.clear()

        val settingsFp = "${s.width}x${s.height}@${s.fps}_cap=${captions}_up=${upscale.name}_${upscaleProfile.name}_${upscaleFormat.name}"
        val uniqueImgs = ExportOptions.uniqueSourceImages(plan)
        val srcFps = uniqueImgs.associateWith { p ->
            val f = File(p)
            "${f.length()}_${f.lastModified()}"
        }
        val jobId = "job_${projectId.ifBlank { "default" }}"

        // Idempotent retry cleanup: if a prior run of this job left a pending MediaStore URI, remove it before starting
        val prevJob = store.load(jobId)
        if (prevJob != null && prevJob.pendingMediaStoreUri.isNotBlank()) {
            deletePendingMediaStoreUri(appCtx, prevJob.pendingMediaStoreUri)
        }
        val reusableArtifacts = if (prevJob != null) {
            ExportTracker.validResumableArtifacts(
                previousJob = prevJob,
                currentSettingsFp = settingsFp,
                currentModelFp = UpscaleEngine.MODEL_FINGERPRINT,
                currentSourceFps = srcFps
            )
        } else emptyMap()

        val projPrepMs = Diagnostics.nanosToMs(exportStartNs)
        DiagnosticsLog.recordStage(DiagStage.PROJECT_PREP, projPrepMs, plan.segments.size, plan.segments.size)

        var job = ExportJob(
            jobId = jobId,
            projectId = projectId,
            projectName = projectName,
            status = ExportJobStatus.ACTIVE,
            stage = ExportStage.PREPARING_IMAGES,
            progressPct = 0,
            doneItems = 0,
            totalItems = plan.segments.size,
            elapsedMs = projPrepMs,
            throughputImgPerMin = 0.0,
            etaMs = null,
            javaHeapMb = DiagnosticsLog.sampleJavaHeapMb(),
            nativeHeapMb = DiagnosticsLog.sampleNativeHeapMb(),
            settingsFingerprint = settingsFp,
            modelFingerprint = UpscaleEngine.MODEL_FINGERPRINT,
            sourceFingerprints = srcFps,
            completedArtifacts = reusableArtifacts
        )
        activeJob = store.save(job)
        onJobUpdate(job)
        val fgWarn = ExportForegroundService.notifyProgress(appCtx, 0, "Preparing images for ${projectName}...")
        if (fgWarn != null) {
            onStatus(fgWarn)
        }

        // Phase 4 Stage Overlap: Prepare silent base audio track (if needed for clicks without narration) on a background thread while upscaling runs
        val clickStarts = ClickTrack.startsMs(plan)
        val clickOn = plan.mix.click.enabled && clickStarts.isNotEmpty()
        val audioPrepExec = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-prep").apply { isDaemon = true } }
        val audioPrepFuture: Future<Pair<File?, Long>> = audioPrepExec.submit(Callable {
            val tAudio0 = System.nanoTime()
            val nf = narration ?: if (clickOn) silentTrack(work, plan.totalMs).also {
                synchronized(Exporter) { currentTempFiles.add(it) }
            } else null
            Pair(nf, Diagnostics.nanosToMs(tAudio0))
        })
        audioPrepExec.shutdown()

        val wantsUpscale = (upscaleProfile != UpscaleProfile.MANUAL || upscale != UpscaleFactor.OFF) && uniqueImgs.any { File(it).isFile }
        if (!wantsUpscale) {
            startMedia3Pipeline(
                ctx = appCtx,
                work = work,
                plan = plan,
                narration = narration,
                audioPrepFuture = audioPrepFuture,
                s = s,
                captions = captions,
                usePngCaptions = false,
                upscaleActive = false,
                upscaleFactor = 1,
                upscaleProfileName = upscaleProfile.name,
                job = job,
                store = store,
                exportStartNs = exportStartNs,
                cancelFlag = cancelFlag,
                handler = handler,
                onProgress = onProgress,
                onStatus = onStatus,
                onJobUpdate = onJobUpdate,
                onDone = onDone,
                onError = onError
            )
            return
        }

        val queueDispatchNs = System.nanoTime()
        thread(name = "export-upscaler") {
            val queueWaitMs = Diagnostics.nanosToMs(queueDispatchNs)
            DiagnosticsLog.recordStage(DiagStage.UPSCALE_QUEUE_WAIT, queueWaitMs, 1, 1)

            val upStartNs = System.nanoTime()
            val itemDurations = ArrayList<Long>()
            var lastItemTickNs = upStartNs
            var lastDoneIdx = 0
            try {
                val res = SafelightExportProcessor.processPlanDetailed(
                    ctx = appCtx,
                    plan = plan,
                    factor = upscale,
                    format = upscaleFormat,
                    profile = upscaleProfile,
                    targetSettings = s,
                    strictMode = strictUpscale,
                    cancelled = cancelFlag,
                    onProgress = { pct, done, total, statusMsg ->
                        if (cancelFlag.get()) return@processPlanDetailed
                        val nowNs = System.nanoTime()
                        if (done > lastDoneIdx) {
                            itemDurations.add(Diagnostics.nanosToMs(lastItemTickNs, nowNs).coerceAtLeast(1L))
                            lastItemTickNs = nowNs
                            lastDoneIdx = done
                        }
                        val upElapsedMs = Diagnostics.nanosToMs(upStartNs, nowNs)
                        val (tp, eta) = ExportTracker.computeProgressAndEta(done, total, upElapsedMs, itemDurations)
                        val etaSuffix = eta?.let { " · ETA ${MediaLibrary.humanDuration(it)}" } ?: ""
                        val updated = job.copy(
                            stage = ExportStage.UPSCALING,
                            progressPct = pct,
                            doneItems = done,
                            totalItems = total,
                            elapsedMs = Diagnostics.nanosToMs(exportStartNs, nowNs),
                            throughputImgPerMin = tp,
                            etaMs = eta,
                            javaHeapMb = DiagnosticsLog.sampleJavaHeapMb(),
                            nativeHeapMb = DiagnosticsLog.sampleNativeHeapMb()
                        )
                        job = updated
                        activeJob = store.save(updated)
                        ExportForegroundService.notifyProgress(appCtx, pct, statusMsg + etaSuffix)
                        handler.post {
                            if (!cancelFlag.get()) {
                                onProgress(pct)
                                onStatus(statusMsg + etaSuffix)
                                onJobUpdate(updated)
                            }
                        }
                    }
                )
                if (cancelFlag.get()) {
                    cleanupCurrentTemp(deleteOutput = true)
                    ExportForegroundService.stop(appCtx)
                    return@thread
                }
                val upElapsed = Diagnostics.nanosToMs(upStartNs)
                DiagnosticsLog.recordStage(
                    stage = DiagStage.UPSCALE,
                    durationMs = res.aggregateStats?.totalInferenceMs ?: upElapsed,
                    itemsDone = res.upscaledCount,
                    itemsTotal = uniqueImgs.size,
                    errors = res.failedCount,
                    skipped = res.skippedCount
                )
                val completedMap = res.queue.filter { it.state == com.auto.core.UpscaleItemState.DONE && it.outputPath != null }
                    .associate { it.sourcePath to it.outputPath!! }
                job = store.save(job.copy(completedArtifacts = completedMap))

                handler.post {
                    if (cancelFlag.get()) {
                        cleanupCurrentTemp(deleteOutput = true)
                        ExportForegroundService.stop(appCtx)
                        return@post
                    }
                    onProgress(50)
                    onStatus("Rendering video... 50%")
                    ExportForegroundService.notifyProgress(appCtx, 50, "Rendering video... 50%")
                    startMedia3Pipeline(
                        ctx = appCtx,
                        work = work,
                        plan = res.plan,
                        narration = narration,
                        audioPrepFuture = audioPrepFuture,
                        s = s,
                        captions = captions,
                        usePngCaptions = upscaleFormat.png || res.queue.any { it.outputPath?.endsWith(".png", true) == true },
                        upscaleActive = true,
                        upscaleFactor = upscale.scale,
                        upscaleProfileName = upscaleProfile.name,
                        job = job,
                        store = store,
                        exportStartNs = exportStartNs,
                        cancelFlag = cancelFlag,
                        handler = handler,
                        onProgress = onProgress,
                        onStatus = onStatus,
                        onJobUpdate = onJobUpdate,
                        onDone = onDone,
                        onError = onError
                    )
                }
            } catch (_: Upscaler.Cancelled) {
                cleanupCurrentTemp(deleteOutput = true)
                ExportForegroundService.stop(appCtx)
            } catch (e: Throwable) {
                cleanupCurrentTemp(deleteOutput = true)
                ExportForegroundService.stop(appCtx)
                if (cancelFlag.get()) return@thread
                val failedJob = job.copy(
                    status = ExportJobStatus.FAILED,
                    errorReason = e.message ?: "AI image upscaling failed"
                )
                activeJob = store.save(failedJob)
                DiagnosticsLog.finishRun(appCtx, s.height, s.fps, "${s.width}x${s.height}", upscale.scale, upscaleProfile.name, plan.segments.size, plan.totalMs, "FAILED")
                handler.post {
                    if (!cancelFlag.get()) {
                        onJobUpdate(failedJob)
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
        audioPrepFuture: Future<Pair<File?, Long>>?,
        s: ExportSettings,
        captions: Boolean,
        usePngCaptions: Boolean,
        upscaleActive: Boolean,
        upscaleFactor: Int,
        upscaleProfileName: String,
        job: ExportJob,
        store: ExportJobStore,
        exportStartNs: Long,
        cancelFlag: AtomicBoolean,
        handler: Handler,
        onProgress: (Int) -> Unit,
        onStatus: (String) -> Unit,
        onJobUpdate: (ExportJob) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (cancelFlag.get()) {
            cleanupCurrentTemp(deleteOutput = true)
            ExportForegroundService.stop(ctx)
            return
        }
        val prepStartNs = System.nanoTime()
        var curJob = job.copy(stage = ExportStage.PREPARING_IMAGES)
        activeJob = store.save(curJob)
        onJobUpdate(curJob)

        try {
            val blank = blankImage(work, s.width, s.height)
            currentTempFiles.add(blank)
            val totalSegs = plan.segments.size
            // Phase 4: Deduplicate prepared caption/solid/text frames across segments with identical parameters
            val preparedFrameCache = HashMap<String, String>()
            val video = plan.segments.mapIndexed { k, g ->
                val list = ArrayList<Effect>()
                list.add(Presentation.createForWidthAndHeight(s.width, s.height, Presentation.LAYOUT_SCALE_TO_FIT))
                val own = g.transition
                val nextFade = g.nextTransition?.kind == TransitionKind.FADE
                if (own.kind == TransitionKind.SLIDE || own.kind == TransitionKind.ZOOM) list.add(ClipMove(own, g.nextTransition, g.durMs))
                if (!g.fx.isIdentity || own.kind == TransitionKind.FADE || own.kind == TransitionKind.ZOOM || nextFade) list.add(ClipColor(g.fx, own, g.nextTransition, g.durMs))
                val effects = Effects(listOf(), list)
                val burnSceneCaption = captions && g.text.isNotBlank() && plan.captions.isEmpty()
                val img = when {
                    g.replacementColor != null -> {
                        val capKey = "solid_${g.replacementColor}_${s.width}x${s.height}_${if (burnSceneCaption) g.text else ""}"
                        preparedFrameCache.getOrPut(capKey) {
                            val sf = solidScreen(
                                dir = work,
                                argbColor = g.replacementColor,
                                k = k,
                                w = s.width,
                                h = s.height,
                                captionText = if (burnSceneCaption) g.text else null,
                                ctx = ctx,
                                style = plan.captionStyle
                            )
                            currentTempFiles.add(sf)
                            sf.path
                        }
                    }
                    g.image != null && burnSceneCaption -> {
                        val capKey = "cap_${g.image}_${s.width}x${s.height}_${usePngCaptions}_${g.text}"
                        preparedFrameCache.getOrPut(capKey) {
                            val cf = captioned(ctx, work, k, g.image, g.text, s.width, s.height, plan.captionStyle, png = usePngCaptions)
                            if (cf.path != g.image) currentTempFiles.add(cf)
                            cf.path
                        }
                    }
                    g.image != null -> g.image
                    g.text.isNotBlank() -> {
                        val dispText = if (g.heading) g.text.uppercase() else g.text
                        val capKey = "text_${s.width}x${s.height}_$dispText"
                        preparedFrameCache.getOrPut(capKey) {
                            val tf = textScreen(work, k, dispText, s.width, s.height)
                            currentTempFiles.add(tf)
                            tf.path
                        }
                    }
                    else -> blank.path
                }
                val mi = MediaItem.Builder().setUri(Uri.fromFile(File(img))).setImageDurationMs(g.durMs).build()
                EditedMediaItem.Builder(mi).setDurationUs(g.durMs * 1000).setFrameRate(s.fps).setEffects(effects).build()
            }
            val prepElapsed = Diagnostics.nanosToMs(prepStartNs)
            DiagnosticsLog.recordStage(
                stage = DiagStage.PREPARE,
                durationMs = prepElapsed,
                itemsDone = totalSegs,
                itemsTotal = totalSegs
            )

            // Phase 4: Collect parallel audio preparation result and record AUDIO_PREP stage
            val audioStartNs = System.nanoTime()
            val sequences = arrayListOf(EditedMediaItemSequence(video))
            val clickStarts = ClickTrack.startsMs(plan)
            val clickOn = plan.mix.click.enabled && clickStarts.isNotEmpty()
            val (narrFile, asyncAudioMs) = try {
                audioPrepFuture?.get() ?: Pair(narration ?: if (clickOn) silentTrack(work, plan.totalMs).also { currentTempFiles.add(it) } else null, 0L)
            } catch (_: Exception) {
                Pair(narration ?: if (clickOn) silentTrack(work, plan.totalMs).also { currentTempFiles.add(it) } else null, 0L)
            }
            val expectedAudio = narrFile != null || (plan.mix.music?.let { File(it.path).exists() } == true)
            if (narrFile != null) {
                val ns = if (narration != null) plan.mix.narration else AudioSettings()
                val procs = ArrayList<AudioProcessor>()
                if (!ns.isIdentity) procs.add(AudioGainProcessor(ns, plan.totalMs))
                if (clickOn) procs.add(ClickMixProcessor(ClickSound.wav(ctx), clickStarts, plan.mix.click.volume))
                val a = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(narrFile))).setRemoveVideo(true).setEffects(Effects(procs, listOf())).build()
                sequences.add(EditedMediaItemSequence(listOf(a)))
            }
            plan.mix.music?.takeIf { File(it.path).exists() }?.let { m ->
                val ms = m.settings
                val procs = if (ms.isIdentity) listOf<AudioProcessor>() else listOf<AudioProcessor>(AudioGainProcessor(ms, plan.totalMs))
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(m.path)))).setRemoveVideo(true).setEffects(Effects(procs, listOf())).build()
                sequences.add(EditedMediaItemSequence(listOf(item), m.loop))
            }
            val audioPrepMs = asyncAudioMs + Diagnostics.nanosToMs(audioStartNs)
            DiagnosticsLog.recordStage(DiagStage.AUDIO_PREP, audioPrepMs, if (expectedAudio) 1 else 0, 1)

            val cueTexts = if (captions) plan.captions.map { com.auto.core.TextClip(-it.id, it.startMs, it.endMs, it.text, plan.captionStyle) } else emptyList()
            val overlayTexts = plan.texts + cueTexts
            val compositionEffects = if (overlayTexts.isEmpty()) Effects.EMPTY else Effects(listOf(), listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(TextBitmapOverlay(ctx, s.width, s.height, overlayTexts)))))
            val out = File(work, "auto_${System.currentTimeMillis()}.mp4")
            currentTempFiles.add(out)

            val renderStartNs = System.nanoTime()
            curJob = curJob.copy(
                stage = ExportStage.RENDERING,
                doneItems = 0,
                totalItems = totalSegs,
                elapsedMs = Diagnostics.nanosToMs(exportStartNs, renderStartNs)
            )
            activeJob = store.save(curJob)
            onJobUpdate(curJob)

            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacksAndMessages(null)
                        // Post-cancellation write & publish prevention guard (Priority 2)
                        if (cancelFlag.get()) {
                            cleanupCurrentTemp(deleteOutput = true)
                            ExportForegroundService.stop(ctx)
                            return
                        }
                        val renderElapsed = Diagnostics.nanosToMs(renderStartNs)
                        DiagnosticsLog.recordStage(
                            stage = DiagStage.RENDER,
                            durationMs = renderElapsed,
                            itemsDone = totalSegs,
                            itemsTotal = totalSegs
                        )

                        val finStartNs = System.nanoTime()
                        curJob = curJob.copy(stage = ExportStage.FINALIZING, progressPct = 98, doneItems = totalSegs, totalItems = totalSegs)
                        activeJob = store.save(curJob)
                        onJobUpdate(curJob)
                        onStatus("Finalizing & verifying MP4...")
                        ExportForegroundService.notifyProgress(ctx, 98, "Finalizing & verifying MP4...")

                        // Verify output playability, duration tolerance, dimensions, and audio track before publishing
                        val verification = verifyOutputFile(out, plan.totalMs, s, expectedAudio)
                        val verifyElapsed = Diagnostics.nanosToMs(finStartNs)
                        if (!verification.ok) {
                            cleanupCurrentTemp(deleteOutput = true)
                            ExportForegroundService.stop(ctx)
                            val failJob = curJob.copy(status = ExportJobStatus.FAILED, errorReason = verification.failureReason, pendingMediaStoreUri = "")
                            activeJob = store.save(failJob)
                            DiagnosticsLog.recordStage(DiagStage.FINALIZE, verifyElapsed, 0, 1, errors = 1)
                            DiagnosticsLog.finishRun(ctx, s.height, s.fps, "${s.width}x${s.height}", upscaleFactor, upscaleProfileName, totalSegs, plan.totalMs, "FAILED_VERIFY")
                            onJobUpdate(failJob)
                            onError("Output verification failed: ${verification.failureReason}")
                            return
                        }
                        DiagnosticsLog.recordStage(DiagStage.FINALIZE, verifyElapsed, 1, 1)

                        if (cancelFlag.get()) {
                            cleanupCurrentTemp(deleteOutput = true)
                            ExportForegroundService.stop(ctx)
                            return
                        }

                        val pubStartNs = System.nanoTime()
                        val where = publish(ctx, out, cancelFlag, store, curJob)
                        val pubElapsed = Diagnostics.nanosToMs(pubStartNs)
                        DiagnosticsLog.recordStage(DiagStage.MEDIASTORE_PUBLISH, pubElapsed, 1, 1)
                        ExportForegroundService.stop(ctx)
                        if (cancelFlag.get()) {
                            cleanupCurrentTemp(deleteOutput = true)
                            return
                        }
                        DiagnosticsLog.finishRun(ctx, s.height, s.fps, "${s.width}x${s.height}", upscaleFactor, upscaleProfileName, totalSegs, plan.totalMs, "COMPLETED")

                        val doneJob = curJob.copy(
                            status = ExportJobStatus.COMPLETED,
                            stage = ExportStage.FINALIZING,
                            progressPct = 100,
                            doneItems = totalSegs,
                            totalItems = totalSegs,
                            elapsedMs = Diagnostics.nanosToMs(exportStartNs),
                            outputUri = lastUri?.toString() ?: where,
                            pendingMediaStoreUri = ""
                        )
                        activeJob = store.save(doneJob)
                        cleanupCurrentTemp(deleteOutput = lastUri != null)
                        onJobUpdate(doneJob)
                        onDone(where)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacksAndMessages(null)
                        cleanupCurrentTemp(deleteOutput = true)
                        ExportForegroundService.stop(ctx)
                        if (cancelFlag.get()) return
                        val msg = exportException.message ?: "Export failed"
                        val failJob = curJob.copy(status = ExportJobStatus.FAILED, errorReason = msg, elapsedMs = Diagnostics.nanosToMs(exportStartNs), pendingMediaStoreUri = "")
                        activeJob = store.save(failJob)
                        DiagnosticsLog.recordStage(DiagStage.RENDER, Diagnostics.nanosToMs(renderStartNs), 0, totalSegs, errors = 1)
                        DiagnosticsLog.finishRun(ctx, s.height, s.fps, "${s.width}x${s.height}", upscaleFactor, upscaleProfileName, totalSegs, plan.totalMs, "FAILED_RENDER")
                        onJobUpdate(failJob)
                        onError(msg)
                    }
                }).build()

            current = transformer
            transformer.start(Composition.Builder(sequences).setEffects(compositionEffects).build(), out.path)
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (cancelFlag.get()) return
                    if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        val rawPct = holder.progress.coerceIn(0, 100)
                        val p = if (upscaleActive) 50 + (rawPct / 2) else rawPct
                        val doneScenes = (totalSegs * rawPct) / 100
                        val renderElapsed = Diagnostics.nanosToMs(renderStartNs).coerceAtLeast(1L)
                        val tp = if (doneScenes > 0) (doneScenes * 60_000.0) / renderElapsed else 0.0
                        val eta = if (doneScenes >= ExportTracker.MIN_ITEMS_FOR_ETA && rawPct in 10..95) {
                            ((renderElapsed.toDouble() / rawPct) * (100 - rawPct)).toLong()
                        } else null
                        val etaText = eta?.let { " · ETA ${MediaLibrary.humanDuration(it)}" } ?: ""
                        val statusLine = "Rendering... ${p.coerceIn(0, 99)}% ($doneScenes/$totalSegs scenes)$etaText"
                        curJob = curJob.copy(
                            stage = ExportStage.RENDERING,
                            progressPct = p.coerceIn(0, 99),
                            doneItems = doneScenes,
                            totalItems = totalSegs,
                            elapsedMs = Diagnostics.nanosToMs(exportStartNs),
                            throughputImgPerMin = tp,
                            etaMs = eta,
                            javaHeapMb = DiagnosticsLog.sampleJavaHeapMb(),
                            nativeHeapMb = DiagnosticsLog.sampleNativeHeapMb()
                        )
                        activeJob = store.save(curJob)
                        ExportForegroundService.notifyProgress(ctx, p.coerceIn(0, 99), statusLine)
                        onProgress(p.coerceIn(0, 100))
                        onStatus(statusLine)
                        onJobUpdate(curJob)
                    }
                    handler.postDelayed(this, 500)
                }
            }
            handler.post(poll)
        } catch (e: Throwable) {
            cleanupCurrentTemp(deleteOutput = true)
            ExportForegroundService.stop(ctx)
            if (cancelFlag.get()) return
            val msg = e.message ?: "Export failed"
            val failJob = curJob.copy(status = ExportJobStatus.FAILED, errorReason = msg, pendingMediaStoreUri = "")
            activeJob = store.save(failJob)
            onJobUpdate(failJob)
            onError(msg)
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
        val poll = object : Runnable {
            override fun run() {
                if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress)
                handler.postDelayed(this, 500)
            }
        }
        handler.post(poll)
    }

    /**
     * Phase 1 & C2 (Section 13 & Priority 2): Publishes the finished MP4 to MediaStore (`Movies/Auto`) using `IS_PENDING = 1`
     * on Android 10+, recording `pendingMediaStoreUri` in `ExportJobStore` before streaming bytes, checking `cancelFlag`
     * before flipping `IS_PENDING = 0`, and deleting any partial row if cancelled or if an exception occurs.
     */
    private fun publish(
        ctx: Context,
        f: File,
        cancelFlag: AtomicBoolean? = null,
        store: ExportJobStore? = null,
        curJob: ExportJob? = null
    ): String {
        if (Build.VERSION.SDK_INT < 29) return f.path
        val resolver = ctx.contentResolver
        var pendingUri: Uri? = null
        return try {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Auto")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("MediaStore refused video entry")
            pendingUri = uri
            if (store != null && curJob != null) {
                activeJob = store.save(curJob.copy(pendingMediaStoreUri = uri.toString()))
            }
            resolver.openOutputStream(uri)?.use { o ->
                f.inputStream().buffered(65536).use { input ->
                    val buf = ByteArray(65536)
                    while (true) {
                        if (cancelFlag?.get() == true) {
                            throw IllegalStateException("Cancelled during MediaStore publish")
                        }
                        val r = input.read(buf)
                        if (r < 0) break
                        o.write(buf, 0, r)
                    }
                }
            } ?: throw IllegalStateException("Could not open MediaStore output stream")

            if (cancelFlag?.get() == true) {
                throw IllegalStateException("Cancelled before finalizing MediaStore publish")
            }
            val doneValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, doneValues, null, null)
            lastUri = uri
            "Movies/Auto/${f.name}"
        } catch (e: Exception) {
            pendingUri?.let { u ->
                try { resolver.delete(u, null, null) } catch (_: Exception) {}
            }
            "NOT copied to Movies (${e.message}). The file is only in the app cache, which Android may clear: ${f.path}"
        }
    }
}
