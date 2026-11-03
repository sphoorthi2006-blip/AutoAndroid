package com.auto.core

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 0, Section 16 & Performance Directive Phase 1:
 * High-resolution monotonic stage timers, per-image upscale breakdown (decode vs inference vs PNG write),
 * and aggregate upscale latency statistics (`median`, `p90`, `slowest`, `images/min`).
 * Privacy rule: JSON logs contain NO personal data, NO file paths/filenames, and NO network transmission.
 */
enum class DiagStage(val id: String, val label: String) {
    IMPORT("import", "Import"),
    ANALYSIS("analysis", "Analysing"),
    PROJECT_PREP("project_prep", "Project Preparation"),
    IMAGE_DECODE("image_decode", "Image Decode"),
    PREPARE("prepare", "Preparing Images"),
    UPSCALE_QUEUE_WAIT("upscale_queue_wait", "Upscale Queue Wait"),
    CACHE_LOOKUP("cache_lookup", "Cache Lookup & Validation"),
    UPSCALE("upscale", "Upscaling (ONNX Inference)"),
    PNG_ENCODE_WRITE("png_encode_write", "PNG Encoding & Disk Write"),
    AUDIO_PREP("audio_prep", "Audio Preparation"),
    RENDER("render", "Video Rendering"),
    FINALIZE("finalize", "Muxing & Final Verification"),
    MEDIASTORE_PUBLISH("mediastore_publish", "MediaStore Publishing");

    companion object {
        fun fromId(id: String?): DiagStage? = values().firstOrNull { it.id == id }
    }
}

data class StageMetric(
    val stage: DiagStage,
    val durationMs: Long,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val javaHeapPeakMb: Int = 0,
    val nativeHeapPeakMb: Int = 0,
    val errorCount: Int = 0,
    val skippedCount: Int = 0
) {
    /** Throughput in items/minute when duration >= 100 ms and itemsDone > 0; 0.0 otherwise. */
    val itemsPerMin: Double
        get() = if (durationMs >= 100L && itemsDone > 0) (itemsDone * 60_000.0) / durationMs.toDouble() else 0.0

    fun formattedItemsPerMin(): String =
        if (itemsPerMin <= 0.0) "—" else String.format(Locale.ROOT, "%.1f/min", itemsPerMin)

    fun toJson(): String = buildString {
        append("{")
        append("\"stage\":${jsonQuote(stage.id)},")
        append("\"durationMs\":${max(0L, durationMs)},")
        append("\"done\":${max(0, itemsDone)},")
        append("\"total\":${max(0, itemsTotal)},")
        append("\"imgPerMin\":${(itemsPerMin * 10.0).roundToInt() / 10.0},")
        append("\"javaMb\":${max(0, javaHeapPeakMb)},")
        append("\"nativeMb\":${max(0, nativeHeapPeakMb)},")
        append("\"errors\":${max(0, errorCount)},")
        append("\"skipped\":${max(0, skippedCount)}")
        append("}")
    }

    companion object {
        fun fromMap(m: Map<*, *>?): StageMetric? {
            if (m == null) return null
            val st = DiagStage.fromId(m["stage"] as? String) ?: return null
            fun l(k: String) = (m[k] as? Double)?.toLong() ?: 0L
            fun i(k: String) = (m[k] as? Double)?.toInt() ?: 0
            return StageMetric(
                stage = st,
                durationMs = max(0L, l("durationMs")),
                itemsDone = max(0, i("done")),
                itemsTotal = max(0, i("total")),
                javaHeapPeakMb = max(0, i("javaMb")),
                nativeHeapPeakMb = max(0, i("nativeMb")),
                errorCount = max(0, i("errors")),
                skippedCount = max(0, i("skipped"))
            )
        }
    }
}

/**
 * Performance Directive Phase 1: Per-image high-resolution telemetry separating cache lookup,
 * strip decode, ONNX inference, and streaming PNG encoding.
 */
data class UpscaleImageMetric(
    val index: Int,
    val srcWidth: Int,
    val srcHeight: Int,
    val fileSizeBytes: Long,
    val exifOrientation: Int = 1,
    val chosenScale: Int,
    val modelId: String,
    val runtimeProvider: String,
    val cacheHit: Boolean,
    val skipped: Boolean = false,
    val decodeMs: Long = 0L,
    val inferenceMs: Long = 0L,
    val encodeWriteMs: Long = 0L,
    val totalMs: Long = 0L,
    val outWidth: Int = srcWidth * max(1, chosenScale),
    val outHeight: Int = srcHeight * max(1, chosenScale),
    val tilesRun: Int = 0,
    val retries: Int = 0,
    val failed: Boolean = false,
    val failureReason: String = ""
)

data class UpscaleAggregateStats(
    val totalImages: Int,
    val upscaledCount: Int,
    val cacheHits: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val medianMs: Long,
    val p90Ms: Long,
    val slowestMs: Long,
    val slowestImageIndex: Int,
    val imagesPerMinute: Double,
    val totalDecodeMs: Long,
    val totalInferenceMs: Long,
    val totalEncodeWriteMs: Long,
    val totalUpscaleMs: Long
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"totalImages\":$totalImages,")
        append("\"upscaled\":$upscaledCount,")
        append("\"cacheHits\":$cacheHits,")
        append("\"skipped\":$skippedCount,")
        append("\"failed\":$failedCount,")
        append("\"medianMs\":$medianMs,")
        append("\"p90Ms\":$p90Ms,")
        append("\"slowestMs\":$slowestMs,")
        append("\"slowestIdx\":$slowestImageIndex,")
        append("\"imgPerMin\":${(imagesPerMinute * 10.0).roundToInt() / 10.0},")
        append("\"decodeMs\":$totalDecodeMs,")
        append("\"inferMs\":$totalInferenceMs,")
        append("\"encodeMs\":$totalEncodeWriteMs,")
        append("\"totalMs\":$totalUpscaleMs")
        append("}")
    }

    companion object {
        fun fromMap(m: Map<*, *>?): UpscaleAggregateStats? {
            if (m == null) return null
            fun l(k: String) = (m[k] as? Double)?.toLong() ?: 0L
            fun i(k: String) = (m[k] as? Double)?.toInt() ?: 0
            return UpscaleAggregateStats(
                totalImages = max(0, i("totalImages")),
                upscaledCount = max(0, i("upscaled")),
                cacheHits = max(0, i("cacheHits")),
                skippedCount = max(0, i("skipped")),
                failedCount = max(0, i("failed")),
                medianMs = max(0L, l("medianMs")),
                p90Ms = max(0L, l("p90Ms")),
                slowestMs = max(0L, l("slowestMs")),
                slowestImageIndex = max(0, i("slowestIdx")),
                imagesPerMinute = (m["imgPerMin"] as? Double) ?: 0.0,
                totalDecodeMs = max(0L, l("decodeMs")),
                totalInferenceMs = max(0L, l("inferMs")),
                totalEncodeWriteMs = max(0L, l("encodeMs")),
                totalUpscaleMs = max(0L, l("totalMs"))
            )
        }
    }
}

data class DiagnosticRunReport(
    val timestampMs: Long,
    val resolutionP: Int = 720,
    val fps: Int = 30,
    val aspect: String = "16:9",
    val upscaleFactor: Int = 1,
    val upscaleProfile: String = "OFF",
    val sceneCount: Int = 0,
    val videoDurationMs: Long = 0L,
    val totalExportMs: Long = 0L,
    val status: String = "COMPLETED",
    val stages: List<StageMetric> = emptyList(),
    val upscaleStats: UpscaleAggregateStats? = null,
    val warnings: List<String> = emptyList()
) {
    val peakJavaMb: Int get() = stages.maxOfOrNull { it.javaHeapPeakMb } ?: 0
    val peakNativeMb: Int get() = stages.maxOfOrNull { it.nativeHeapPeakMb } ?: 0

    fun toJson(): String = buildString {
        append("{\"version\":1,")
        append("\"timestampMs\":$timestampMs,")
        append("\"resP\":$resolutionP,")
        append("\"fps\":$fps,")
        append("\"aspect\":${jsonQuote(aspect)},")
        append("\"upscaleFactor\":$upscaleFactor,")
        append("\"upscaleProfile\":${jsonQuote(upscaleProfile)},")
        append("\"sceneCount\":$sceneCount,")
        append("\"videoDurationMs\":$videoDurationMs,")
        append("\"totalExportMs\":$totalExportMs,")
        append("\"status\":${jsonQuote(Diagnostics.sanitizeStatus(status))},")
        if (upscaleStats != null) {
            append("\"upscaleStats\":${upscaleStats.toJson()},")
        }
        if (warnings.isNotEmpty()) {
            append("\"warnings\":[${warnings.take(10).joinToString(",") { jsonQuote(Diagnostics.sanitizeStatus(it)) }}],")
        }
        append("\"stages\":[${stages.joinToString(",") { it.toJson() }}]}")
    }

    companion object {
        fun fromMap(m: Map<*, *>?): DiagnosticRunReport? {
            if (m == null) return null
            fun l(k: String, d: Long = 0L) = (m[k] as? Double)?.toLong() ?: d
            fun i(k: String, d: Int = 0) = (m[k] as? Double)?.toInt() ?: d
            val stList = (m["stages"] as? List<*>)?.mapNotNull { StageMetric.fromMap(it as? Map<*, *>) } ?: emptyList()
            val upStats = UpscaleAggregateStats.fromMap(m["upscaleStats"] as? Map<*, *>)
            val warns = (m["warnings"] as? List<*>)?.mapNotNull { (it as? String)?.let(Diagnostics::sanitizeStatus) } ?: emptyList()
            return DiagnosticRunReport(
                timestampMs = l("timestampMs"),
                resolutionP = i("resP", 720),
                fps = i("fps", 30),
                aspect = (m["aspect"] as? String) ?: "16:9",
                upscaleFactor = i("upscaleFactor", 1),
                upscaleProfile = (m["upscaleProfile"] as? String) ?: "OFF",
                sceneCount = i("sceneCount", 0),
                videoDurationMs = l("videoDurationMs"),
                totalExportMs = l("totalExportMs"),
                status = Diagnostics.sanitizeStatus((m["status"] as? String) ?: "COMPLETED"),
                stages = stList,
                upscaleStats = upStats,
                warnings = warns
            )
        }
    }
}

object Diagnostics {
    const val MAX_HISTORY = 10

    /** Converts monotonic nanoTime delta (`endNs - startNs`) to milliseconds (`>= 0`). */
    fun nanosToMs(startNs: Long, endNs: Long = System.nanoTime()): Long =
        max(0L, (endNs - startNs) / 1_000_000L)

    /** Aggregates per-image upscale metrics into count, median, p90, slowest image, images/min, and stage totals. */
    fun aggregateUpscaleMetrics(metrics: List<UpscaleImageMetric>): UpscaleAggregateStats {
        if (metrics.isEmpty()) {
            return UpscaleAggregateStats(0, 0, 0, 0, 0, 0L, 0L, 0L, 0, 0.0, 0L, 0L, 0L, 0L)
        }
        val active = metrics.filter { !it.skipped && !it.failed }
        val sortedByTotal = (if (active.isNotEmpty()) active else metrics).sortedBy { it.totalMs }
        val medianMs = sortedByTotal[sortedByTotal.size / 2].totalMs
        val p90Idx = min(sortedByTotal.size - 1, ((sortedByTotal.size * 0.90).toInt()))
        val p90Ms = sortedByTotal[p90Idx].totalMs
        val slowest = metrics.maxByOrNull { it.totalMs }
        val totalDecode = metrics.sumOf { max(0L, it.decodeMs) }
        val totalInfer = metrics.sumOf { max(0L, it.inferenceMs) }
        val totalEncode = metrics.sumOf { max(0L, it.encodeWriteMs) }
        val totalUpscale = max(1L, metrics.sumOf { max(0L, it.totalMs) })
        val completedCount = metrics.count { !it.failed }
        val imgPerMin = if (completedCount > 0) (completedCount * 60_000.0) / totalUpscale.toDouble() else 0.0

        return UpscaleAggregateStats(
            totalImages = metrics.size,
            upscaledCount = metrics.count { !it.cacheHit && !it.skipped && !it.failed },
            cacheHits = metrics.count { it.cacheHit },
            skippedCount = metrics.count { it.skipped },
            failedCount = metrics.count { it.failed },
            medianMs = medianMs,
            p90Ms = p90Ms,
            slowestMs = slowest?.totalMs ?: 0L,
            slowestImageIndex = slowest?.index ?: 0,
            imagesPerMinute = imgPerMin,
            totalDecodeMs = totalDecode,
            totalInferenceMs = totalInfer,
            totalEncodeWriteMs = totalEncode,
            totalUpscaleMs = metrics.sumOf { max(0L, it.totalMs) }
        )
    }

    /** Strips any file paths, Unix/Windows separators, or quoted filenames so diagnostic status codes stay 100% privacy-safe. */
    fun sanitizeStatus(raw: String): String {
        val cleaned = raw
            .replace(Regex("(/[A-Za-z0-9._-]+)+"), "<path>")
            .replace(Regex("([A-Za-z]:\\\\[A-Za-z0-9._\\\\-]+)"), "<path>")
            .replace(Regex("'[^']*'"), "<item>")
            .replace(Regex("\"[^\"]*\""), "<item>")
            .trim()
        return cleaned.take(120).ifEmpty { "OK" }
    }

    fun parseHistory(jsonText: String): List<DiagnosticRunReport> {
        val list = try { MiniJson(jsonText).parse() as? List<*> } catch (_: Exception) { null } ?: return emptyList()
        return list.mapNotNull { DiagnosticRunReport.fromMap(it as? Map<*, *>) }.takeLast(MAX_HISTORY)
    }

    fun serializeHistory(reports: List<DiagnosticRunReport>): String =
        "[" + reports.takeLast(MAX_HISTORY).joinToString(",") { it.toJson() } + "]"
}
