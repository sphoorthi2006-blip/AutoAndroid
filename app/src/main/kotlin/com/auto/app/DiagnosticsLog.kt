package com.auto.app

import android.content.Context
import android.content.Intent
import android.os.Debug
import com.auto.core.DiagStage
import com.auto.core.DiagnosticRunReport
import com.auto.core.Diagnostics
import com.auto.core.StageMetric
import com.auto.core.UpscaleImageMetric
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Local-only diagnostics recorder stored in filesDir/diagnostics.json.
 * Uses monotonic `System.nanoTime()` for elapsed export timing and records per-image upscale breakdowns
 * (`decodeMs`, `inferenceMs`, `encodeWriteMs`, `median`, `p90`, `slowest`) plus visible service warnings.
 * Never transmits over the network and never records filenames or user paths.
 */
object DiagnosticsLog {
    private const val FILE_NAME = "diagnostics.json"
    private val activeStages = LinkedHashMap<DiagStage, StageMetric>()
    private val activeImageMetrics = ArrayList<UpscaleImageMetric>()
    private val activeWarnings = ArrayList<String>()
    private var activeStartNs: Long = 0L

    fun sampleJavaHeapMb(): Int {
        val rt = Runtime.getRuntime()
        return ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt().coerceAtLeast(0)
    }

    fun sampleNativeHeapMb(): Int = try {
        (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt().coerceAtLeast(0)
    } catch (_: Throwable) {
        0
    }

    @Synchronized
    fun beginRun() {
        activeStages.clear()
        activeImageMetrics.clear()
        activeWarnings.clear()
        activeStartNs = System.nanoTime()
    }

    @Synchronized
    fun recordImageMetric(metric: UpscaleImageMetric) {
        activeImageMetrics.add(metric)
    }

    @Synchronized
    fun recordWarning(warning: String) {
        val clean = Diagnostics.sanitizeStatus(warning)
        if (clean.isNotBlank() && clean !in activeWarnings) {
            activeWarnings.add(clean)
        }
    }

    @Synchronized
    fun currentWarnings(): List<String> = ArrayList(activeWarnings)

    @Synchronized
    fun recordStage(
        stage: DiagStage,
        durationMs: Long,
        itemsDone: Int,
        itemsTotal: Int,
        javaPeakMb: Int = sampleJavaHeapMb(),
        nativePeakMb: Int = sampleNativeHeapMb(),
        errors: Int = 0,
        skipped: Int = 0
    ) {
        activeStages[stage] = StageMetric(
            stage = stage,
            durationMs = durationMs.coerceAtLeast(0L),
            itemsDone = itemsDone.coerceAtLeast(0),
            itemsTotal = itemsTotal.coerceAtLeast(0),
            javaHeapPeakMb = maxOf(javaPeakMb, sampleJavaHeapMb()),
            nativeHeapPeakMb = maxOf(nativePeakMb, sampleNativeHeapMb()),
            errorCount = errors.coerceAtLeast(0),
            skippedCount = skipped.coerceAtLeast(0)
        )
    }

    @Synchronized
    fun finishRun(
        ctx: Context,
        resP: Int,
        fps: Int,
        aspect: String,
        upscaleFactor: Int,
        upscaleProfile: String,
        sceneCount: Int,
        videoDurationMs: Long,
        status: String
    ): DiagnosticRunReport {
        val nowMs = System.currentTimeMillis()
        val totalMs = if (activeStartNs > 0L) Diagnostics.nanosToMs(activeStartNs) else activeStages.values.sumOf { it.durationMs }
        val upStats = if (activeImageMetrics.isNotEmpty()) Diagnostics.aggregateUpscaleMetrics(activeImageMetrics) else null
        val report = DiagnosticRunReport(
            timestampMs = nowMs,
            resolutionP = resP,
            fps = fps,
            aspect = aspect,
            upscaleFactor = upscaleFactor,
            upscaleProfile = upscaleProfile,
            sceneCount = sceneCount,
            videoDurationMs = videoDurationMs,
            totalExportMs = totalMs,
            status = Diagnostics.sanitizeStatus(status),
            stages = DiagStage.values().mapNotNull { activeStages[it] },
            upscaleStats = upStats,
            warnings = ArrayList(activeWarnings)
        )
        val history = (loadHistory(ctx) + report).takeLast(Diagnostics.MAX_HISTORY)
        saveHistoryAtomic(ctx, history)
        return report
    }

    @Synchronized
    fun loadHistory(ctx: Context): List<DiagnosticRunReport> {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            Diagnostics.parseHistory(f.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun latestReport(ctx: Context): DiagnosticRunReport? = loadHistory(ctx).lastOrNull()

    @Synchronized
    fun clear(ctx: Context) {
        activeStages.clear()
        activeImageMetrics.clear()
        activeWarnings.clear()
        activeStartNs = 0L
        try {
            File(ctx.filesDir, FILE_NAME).delete()
            File(ctx.filesDir, "$FILE_NAME.tmp").delete()
        } catch (_: Exception) {}
    }

    fun shareLog(ctx: Context): Boolean {
        val history = loadHistory(ctx)
        if (history.isEmpty()) return false
        val json = Diagnostics.serializeHistory(history)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "AutoAndroid Diagnostics Log (Privacy-Safe JSON)")
            putExtra(Intent.EXTRA_TEXT, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(Intent.createChooser(intent, "Share Diagnostics Log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveHistoryAtomic(ctx: Context, history: List<DiagnosticRunReport>) {
        try {
            val f = File(ctx.filesDir, FILE_NAME)
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            val bytes = Diagnostics.serializeHistory(history).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {}
    }
}
