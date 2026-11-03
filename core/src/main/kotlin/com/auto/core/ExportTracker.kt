package com.auto.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phase 4 & C2 (Section 14 & Priority 2): Export Engine — Progress, ETA math, Atomic Job Persistence,
 * Safe Resume, Idempotent Retry, Cancellation Guard, Storage Estimator/Guard, and Output Verification rules.
 */
enum class ExportStage(val label: String) {
    ANALYSING("Analysing"),
    PREPARING_IMAGES("Preparing Images"),
    UPSCALING("Upscaling"),
    RENDERING("Rendering"),
    FINALIZING("Finalizing")
}

enum class ExportJobStatus(val label: String) {
    ACTIVE("Active"),
    QUEUED("Queued"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    INTERRUPTED("Export interrupted — Resume"),
    CANCELLED("Cancelled")
}

data class StorageEstimate(
    val videoBytes: Long,
    val preparedImagesBytes: Long,
    val upscaleBytes: Long,
    val rawTotalBytes: Long,
    val requiredWithMarginBytes: Long,
    val freeBytes: Long,
    val sufficient: Boolean,
    val warningMessage: String?
)

data class OutputVerificationResult(
    val ok: Boolean,
    val playable: Boolean,
    val expectedDurationMs: Long,
    val actualDurationMs: Long,
    val expectedWidth: Int,
    val expectedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    val expectedAudio: Boolean,
    val actualAudio: Boolean,
    val failureReason: String = ""
)

data class ExportJob(
    val jobId: String,
    val projectId: String,
    val projectName: String,
    val status: ExportJobStatus,
    val stage: ExportStage,
    val progressPct: Int,
    val doneItems: Int,
    val totalItems: Int,
    val elapsedMs: Long,
    val throughputImgPerMin: Double,
    val etaMs: Long?,
    val javaHeapMb: Int = 0,
    val nativeHeapMb: Int = 0,
    val settingsFingerprint: String = "",
    val modelFingerprint: String = "",
    val sourceFingerprints: Map<String, String> = emptyMap(),
    val completedArtifacts: Map<String, String> = emptyMap(),
    val errorReason: String = "",
    val outputUri: String = "",
    val pendingMediaStoreUri: String = "",
    val updatedMs: Long = 0L
) {
    fun toJson(): String = buildString {
        append("{\"version\":1,")
        append("\"jobId\":${jsonQuote(jobId)},")
        append("\"projectId\":${jsonQuote(projectId)},")
        append("\"projectName\":${jsonQuote(projectName)},")
        append("\"status\":${jsonQuote(status.name)},")
        append("\"stage\":${jsonQuote(stage.name)},")
        append("\"progressPct\":$progressPct,")
        append("\"done\":$doneItems,")
        append("\"total\":$totalItems,")
        append("\"elapsedMs\":$elapsedMs,")
        append("\"throughput\":${(throughputImgPerMin * 10.0).roundToInt() / 10.0},")
        append("\"etaMs\":${etaMs ?: "null"},")
        append("\"javaMb\":$javaHeapMb,")
        append("\"nativeMb\":$nativeHeapMb,")
        append("\"settingsFp\":${jsonQuote(settingsFingerprint)},")
        append("\"modelFp\":${jsonQuote(modelFingerprint)},")
        append("\"sources\":{${sourceFingerprints.entries.joinToString(",") { (k, v) -> "${jsonQuote(k)}:${jsonQuote(v)}" }}},")
        append("\"artifacts\":{${completedArtifacts.entries.joinToString(",") { (k, v) -> "${jsonQuote(k)}:${jsonQuote(v)}" }}},")
        append("\"error\":${jsonQuote(errorReason)},")
        append("\"outputUri\":${jsonQuote(outputUri)},")
        append("\"pendingMediaStoreUri\":${jsonQuote(pendingMediaStoreUri)},")
        append("\"updatedMs\":$updatedMs}")
    }

    companion object {
        fun fromMap(m: Map<*, *>?): ExportJob? {
            if (m == null) return null
            val jobId = m["jobId"] as? String ?: return null
            fun l(k: String) = (m[k] as? Double)?.toLong() ?: 0L
            fun i(k: String) = (m[k] as? Double)?.toInt() ?: 0
            val st = ExportJobStatus.values().firstOrNull { it.name == m["status"] } ?: ExportJobStatus.FAILED
            val stg = ExportStage.values().firstOrNull { it.name == m["stage"] } ?: ExportStage.PREPARING_IMAGES
            val srcMap = (m["sources"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
            val artMap = (m["artifacts"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
            return ExportJob(
                jobId = jobId,
                projectId = (m["projectId"] as? String) ?: "",
                projectName = (m["projectName"] as? String) ?: "Project",
                status = st,
                stage = stg,
                progressPct = i("progressPct").coerceIn(0, 100),
                doneItems = max(0, i("done")),
                totalItems = max(0, i("total")),
                elapsedMs = max(0L, l("elapsedMs")),
                throughputImgPerMin = (m["throughput"] as? Double) ?: 0.0,
                etaMs = (m["etaMs"] as? Double)?.toLong(),
                javaHeapMb = max(0, i("javaMb")),
                nativeHeapMb = max(0, i("nativeMb")),
                settingsFingerprint = (m["settingsFp"] as? String) ?: "",
                modelFingerprint = (m["modelFp"] as? String) ?: "",
                sourceFingerprints = srcMap,
                completedArtifacts = artMap,
                errorReason = (m["error"] as? String) ?: "",
                outputUri = (m["outputUri"] as? String) ?: "",
                pendingMediaStoreUri = (m["pendingMediaStoreUri"] as? String) ?: "",
                updatedMs = l("updatedMs")
            )
        }

        fun parse(text: String): ExportJob? = try {
            fromMap(MiniJson(text).parse() as? Map<*, *>)
        } catch (_: Exception) {
            null
        }
    }
}

object ExportTracker {
    /** Minimum completed items before any ETA may be displayed (Section 14). */
    const val MIN_ITEMS_FOR_ETA = 5
    /** Maximum coefficient of variation (stdDev / mean) of recent per-item durations to consider throughput stable. */
    const val MAX_STABLE_CV = 0.45
    /** Documented safety margin: 1.25x estimated bytes + 150 MB headroom. */
    const val SAFETY_MULTIPLIER = 1.25
    const val SAFETY_HEADROOM_BYTES = 150L * 1024L * 1024L
    /** Duration verification tolerance: ±600 ms. */
    const val DURATION_TOLERANCE_MS = 600L

    /**
     * Computes throughput (items/min) and ETA (ms).
     * Rule (Section 14): ETA is returned ONLY after `done >= 5` items AND stable throughput (`itemDurationsMs` CV <= 0.45);
     * otherwise `etaMs` is `null` (show no ETA).
     */
    fun computeProgressAndEta(
        done: Int,
        total: Int,
        elapsedMs: Long,
        recentItemDurationsMs: List<Long>
    ): Pair<Double, Long?> {
        if (done <= 0 || elapsedMs <= 0L) return Pair(0.0, null)
        val throughputPerMin = (done * 60_000.0) / elapsedMs.toDouble()
        val remaining = total - done
        if (remaining <= 0 || done < MIN_ITEMS_FOR_ETA || recentItemDurationsMs.size < MIN_ITEMS_FOR_ETA) {
            return Pair(throughputPerMin, null)
        }
        val window = recentItemDurationsMs.takeLast(10).map { it.toDouble().coerceAtLeast(1.0) }
        val mean = window.average()
        if (mean <= 0.0) return Pair(throughputPerMin, null)
        val variance = window.sumOf { (it - mean) * (it - mean) } / window.size
        val cv = sqrt(variance) / mean
        if (cv > MAX_STABLE_CV) {
            return Pair(throughputPerMin, null)
        }
        val etaMs = (remaining * mean).toLong().coerceAtLeast(0L)
        return Pair(throughputPerMin, etaMs)
    }

    /**
     * Estimates prepared image intermediates, upscaled intermediates, and encoded MP4 size (bitrate x duration),
     * compares with `freeBytes` (from StatFs), and returns a detailed warning with actual numbers if low.
     */
    fun estimateStorage(
        plan: ExportPlan.Plan,
        settings: ExportSettings,
        upscale: UpscaleFactor,
        format: IntermediateFormat,
        freeBytes: Long
    ): StorageEstimate {
        val durationSec = max(1.0, plan.totalMs / 1000.0)
        // Approximate H.264 + AAC bitrate by resolution & fps
        val pixels = settings.width.toLong() * settings.height.toLong()
        val baseBps = when {
            pixels >= 3840L * 2160L -> 24_000_000L // 24 Mbps for 4K
            pixels >= 1920L * 1080L -> 10_000_000L // 10 Mbps for 1080p
            else -> 5_000_000L                     // 5 Mbps for 720p
        }
        val fpsFactor = if (settings.fps >= 60) 1.4 else 1.0
        val audioBps = 192_000L
        val totalBps = (baseBps * fpsFactor).toLong() + audioBps
        val videoBytes = ((totalBps / 8.0) * durationSec).toLong()

        val uniqueImages = ExportOptions.uniqueSourceImages(plan).size
        val captionBurnCount = plan.segments.count { it.image != null && it.replacementColor == null && it.text.isNotBlank() }
        val perPreparedBytes = if (format.png) pixels * 2L else pixels / 2L
        val preparedBytes = captionBurnCount * perPreparedBytes

        val upscaleBytes = if (upscale == UpscaleFactor.OFF) 0L else {
            val scaleArea = upscale.scale.toLong() * upscale.scale.toLong()
            val bytesPerUpscaled = if (format.png) (1920L * 1080L * scaleArea) / 2L else (1920L * 1080L * scaleArea) / 6L
            uniqueImages * bytesPerUpscaled.coerceAtLeast(512_000L)
        }

        val rawTotal = videoBytes + preparedBytes + upscaleBytes
        val requiredWithMargin = (rawTotal * SAFETY_MULTIPLIER).toLong() + SAFETY_HEADROOM_BYTES
        val sufficient = freeBytes >= requiredWithMargin
        val warning = if (!sufficient) {
            "Low storage warning: Export requires ~${MediaLibrary.humanSize(requiredWithMargin)} " +
                "(estimated output + intermediates ${MediaLibrary.humanSize(rawTotal)} plus 25% + 150 MB safety margin), " +
                "but only ${MediaLibrary.humanSize(freeBytes)} is free."
        } else null

        return StorageEstimate(
            videoBytes = videoBytes,
            preparedImagesBytes = preparedBytes,
            upscaleBytes = upscaleBytes,
            rawTotalBytes = rawTotal,
            requiredWithMarginBytes = requiredWithMargin,
            freeBytes = freeBytes,
            sufficient = sufficient,
            warningMessage = warning
        )
    }

    /**
     * Verifies output playability, duration tolerance, dimensions, and expected audio track.
     * Failed verification is a failed job with a readable reason (Section 14).
     */
    fun verifyOutputMetadata(
        fileExists: Boolean,
        fileLengthBytes: Long,
        playable: Boolean,
        expectedDurationMs: Long,
        actualDurationMs: Long,
        expectedWidth: Int,
        expectedHeight: Int,
        actualWidth: Int,
        actualHeight: Int,
        expectedAudio: Boolean,
        actualAudio: Boolean
    ): OutputVerificationResult {
        val reasons = ArrayList<String>()
        if (!fileExists || fileLengthBytes <= 0L) {
            reasons.add("Output video file is missing or empty (0 bytes).")
        } else if (!playable) {
            reasons.add("Output MP4 could not be parsed as playable video.")
        } else {
            if (abs(actualDurationMs - expectedDurationMs) > DURATION_TOLERANCE_MS) {
                reasons.add(
                    String.format(
                        Locale.ROOT,
                        "Output duration (%d ms) differs from expected (%d ms) by more than %d ms.",
                        actualDurationMs, expectedDurationMs, DURATION_TOLERANCE_MS
                    )
                )
            }
            if (actualWidth != expectedWidth || actualHeight != expectedHeight) {
                reasons.add("Output dimensions (${actualWidth}×${actualHeight}) do not match requested (${expectedWidth}×${expectedHeight}).")
            }
            if (expectedAudio && !actualAudio) {
                reasons.add("Expected audio track is missing from exported MP4.")
            }
        }
        return OutputVerificationResult(
            ok = reasons.isEmpty(),
            playable = playable && fileExists && fileLengthBytes > 0L,
            expectedDurationMs = expectedDurationMs,
            actualDurationMs = actualDurationMs,
            expectedWidth = expectedWidth,
            expectedHeight = expectedHeight,
            actualWidth = actualWidth,
            actualHeight = actualHeight,
            expectedAudio = expectedAudio,
            actualAudio = actualAudio,
            failureReason = reasons.joinToString(" ")
        )
    }

    /**
     * Filters persisted `completedArtifacts` on resume after process death so ONLY artifacts whose
     * source fingerprint, settings fingerprint, and model fingerprint match AND whose file exists on disk are reused.
     */
    fun validResumableArtifacts(
        previousJob: ExportJob,
        currentSettingsFp: String,
        currentModelFp: String,
        currentSourceFps: Map<String, String>,
        fileExistsAndValid: (String) -> Boolean = { File(it).let { f -> f.isFile && f.length() > 0L } }
    ): Map<String, String> {
        if (previousJob.settingsFingerprint != currentSettingsFp || previousJob.modelFingerprint != currentModelFp) {
            return emptyMap()
        }
        val valid = LinkedHashMap<String, String>()
        for ((srcKey, outPath) in previousJob.completedArtifacts) {
            val prevSrcFp = previousJob.sourceFingerprints[srcKey] ?: continue
            val curSrcFp = currentSourceFps[srcKey] ?: continue
            if (prevSrcFp == curSrcFp && fileExistsAndValid(outPath)) {
                valid[srcKey] = outPath
            }
        }
        return valid
    }
}

/**
 * Priority 2 (Section 3): Pure JVM coordinator for export cancellation, post-cancellation write prevention,
 * and idempotent retry/resume cleanup.
 */
class ExportCancellationGuard(private val store: ExportJobStore) {
    val cancelled = AtomicBoolean(false)

    /**
     * Prepares a retry or resume of `jobId` by deleting any stale `pendingMediaStoreUri` (via `deletePendingUri`)
     * and any partial temporary files, returning the cleaned prior job if present.
     */
    @Synchronized
    fun prepareIdempotentRetry(
        jobId: String,
        tempFilesToDelete: List<File> = emptyList(),
        deletePendingUri: (String) -> Unit = {}
    ): ExportJob? {
        for (f in tempFilesToDelete) {
            try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (_: Exception) {}
        }
        val prev = store.load(jobId) ?: return null
        if (prev.pendingMediaStoreUri.isNotBlank()) {
            try { deletePendingUri(prev.pendingMediaStoreUri) } catch (_: Exception) {}
        }
        return store.save(prev.copy(pendingMediaStoreUri = "", errorReason = ""))
    }

    /**
     * Cancels the active job immediately, deletes temporary and partial outputs, and ensures subsequent
     * `runIfNotCancelled` blocks (such as late Media3 completion callbacks) are rejected.
     */
    @Synchronized
    fun cancelActive(
        jobId: String,
        tempFilesToDelete: List<File> = emptyList(),
        deletePendingUri: (String) -> Unit = {}
    ): ExportJob? {
        cancelled.set(true)
        for (f in tempFilesToDelete) {
            try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (_: Exception) {}
        }
        val cur = store.load(jobId) ?: return null
        if (cur.pendingMediaStoreUri.isNotBlank()) {
            try { deletePendingUri(cur.pendingMediaStoreUri) } catch (_: Exception) {}
        }
        val updated = cur.copy(
            status = ExportJobStatus.CANCELLED,
            errorReason = "Cancelled by user.",
            pendingMediaStoreUri = "",
            outputUri = ""
        )
        return store.save(updated)
    }

    /**
     * Executes `block` only if cancellation has NOT occurred. Prevents late worker threads or callbacks
     * from publishing output or overwriting `CANCELLED` job state after user cancellation.
     */
    @Synchronized
    fun <T> runIfNotCancelled(jobId: String, block: () -> T): T? {
        if (cancelled.get()) return null
        val cur = store.load(jobId)
        if (cur?.status == ExportJobStatus.CANCELLED || cur?.status == ExportJobStatus.INTERRUPTED) return null
        return block()
    }
}

/**
 * Atomic persistence for ExportJob records on disk (`<root>/<jobId>.json`).
 * On startup (`recoverInterruptedJobs`), any job left in `ACTIVE` state from a killed process is transitioned
 * to `INTERRUPTED` ("Export interrupted — Resume") so the user can safely resume it.
 */
class ExportJobStore(val rootDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { rootDir.mkdirs() }

    private fun jobFile(jobId: String): File {
        val safe = jobId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "job" }
        return File(rootDir, "$safe.json")
    }

    @Synchronized
    fun save(job: ExportJob): ExportJob {
        val stamped = job.copy(updatedMs = clock())
        val f = jobFile(stamped.jobId)
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(stamped.toJson().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return stamped
    }

    @Synchronized
    fun load(jobId: String): ExportJob? {
        val f = jobFile(jobId)
        if (!f.exists()) return null
        return ExportJob.parse(f.readText(Charsets.UTF_8))
    }

    @Synchronized
    fun list(): List<ExportJob> =
        (rootDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList())
            .mapNotNull { runCatching { ExportJob.parse(it.readText(Charsets.UTF_8)) }.getOrNull() }
            .sortedByDescending { it.updatedMs }

    /** Marks any previously ACTIVE job as INTERRUPTED after app restart / process death. */
    @Synchronized
    fun recoverInterruptedJobs(): List<ExportJob> {
        val all = list()
        return all.map { j ->
            if (j.status == ExportJobStatus.ACTIVE) {
                save(j.copy(status = ExportJobStatus.INTERRUPTED, errorReason = "Export interrupted — Resume where safe."))
            } else j
        }
    }

    /** Marks a specific active job as INTERRUPTED (e.g. when Android 14 `onTimeout` fires on the foreground service). */
    @Synchronized
    fun markInterrupted(jobId: String, reason: String): ExportJob? {
        val cur = load(jobId) ?: return null
        if (cur.status != ExportJobStatus.ACTIVE) return cur
        return save(cur.copy(status = ExportJobStatus.INTERRUPTED, errorReason = reason, pendingMediaStoreUri = ""))
    }

    @Synchronized
    fun delete(jobId: String) {
        jobFile(jobId).delete()
        File(jobFile(jobId).path + ".tmp").delete()
    }
}
