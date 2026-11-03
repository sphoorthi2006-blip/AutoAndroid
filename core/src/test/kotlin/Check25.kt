import com.auto.core.*
import java.io.File

/**
 * Phase 4 tests (Section 14): Export foundation.
 * - Stage model (`ANALYSING`, `PREPARING_IMAGES`, `UPSCALING`, `RENDERING`, `FINALIZING`).
 * - ETA math: returns null when `< 5` items completed or when throughput is unstable (CV > 0.45); returns accurate ETA when `>= 5` items and stable.
 * - Atomic `ExportJobStore` persistence, process-death recovery (`ACTIVE` -> `INTERRUPTED`), and fingerprint-verified artifact reuse (`validResumableArtifacts`).
 * - `StorageEstimate` bitrate x duration + intermediate calculation and low-storage warning with actual numbers.
 * - `OutputVerificationResult` checks playability, duration tolerance (`±600 ms`), dimensions, and expected audio track.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    // 1. ETA rule: < 5 items -> no ETA
    val (tp3, eta3) = ExportTracker.computeProgressAndEta(done = 3, total = 20, elapsedMs = 3000L, recentItemDurationsMs = listOf(1000L, 1000L, 1000L))
    check("ETA is null when fewer than 5 items completed", tp3 > 59.0 && eta3 == null)

    // Unstable throughput (wildly varying item durations) -> no ETA
    val (_, etaUnstable) = ExportTracker.computeProgressAndEta(
        done = 6,
        total = 20,
        elapsedMs = 15_000L,
        recentItemDurationsMs = listOf(200L, 5000L, 100L, 6000L, 200L, 3500L)
    )
    check("ETA is null when throughput is unstable (CV > 0.45)", etaUnstable == null)

    // Stable throughput with >= 5 items -> returns valid ETA
    val (tpStable, etaStable) = ExportTracker.computeProgressAndEta(
        done = 5,
        total = 15,
        elapsedMs = 5_000L,
        recentItemDurationsMs = listOf(1000L, 1020L, 980L, 1010L, 990L)
    )
    check("ETA is computed when >= 5 items and throughput is stable (~10,000 ms for 10 remaining items)",
        kotlin.math.abs(tpStable - 60.0) < 0.5 && etaStable != null && etaStable in 9500L..10500L)

    // 2. Atomic ExportJobStore & process-death recovery
    val tmpDir = File.createTempFile("job_store", "").also { it.delete(); it.mkdirs() }
    var clock = 10_000L
    val store = ExportJobStore(tmpDir) { clock }
    val active = ExportJob(
        jobId = "j1",
        projectId = "p1",
        projectName = "My Video",
        status = ExportJobStatus.ACTIVE,
        stage = ExportStage.UPSCALING,
        progressPct = 35,
        doneItems = 7,
        totalItems = 20,
        elapsedMs = 14_000L,
        throughputImgPerMin = 30.0,
        etaMs = 26_000L,
        settingsFingerprint = "1080p_30_16:9",
        modelFingerprint = UpscaleEngine.MODEL_FINGERPRINT,
        sourceFingerprints = mapOf("/src/1.png" to "hash1", "/src/2.png" to "hash2"),
        completedArtifacts = mapOf("/src/1.png" to "/cache/up1.jpg", "/src/2.png" to "/cache/up2.jpg")
    )
    store.save(active)
    clock += 5000L
    val recovered = store.recoverInterruptedJobs()
    check("Process-death recovery transitions ACTIVE job to INTERRUPTED with resume message",
        recovered.single().status == ExportJobStatus.INTERRUPTED &&
        recovered.single().errorReason.contains("Export interrupted"))

    // 3. Fingerprint validation on resume (stale output never reused)
    val reusableSame = ExportTracker.validResumableArtifacts(
        previousJob = recovered.single(),
        currentSettingsFp = "1080p_30_16:9",
        currentModelFp = UpscaleEngine.MODEL_FINGERPRINT,
        currentSourceFps = mapOf("/src/1.png" to "hash1", "/src/2.png" to "CHANGED_HASH"),
        fileExistsAndValid = { true }
    )
    check("Resume reuses only matching source fingerprint (/src/1.png) and rejects modified source (/src/2.png)",
        reusableSame == mapOf("/src/1.png" to "/cache/up1.jpg"))

    val reusableChangedSettings = ExportTracker.validResumableArtifacts(
        previousJob = recovered.single(),
        currentSettingsFp = "2160p_60_16:9",
        currentModelFp = UpscaleEngine.MODEL_FINGERPRINT,
        currentSourceFps = mapOf("/src/1.png" to "hash1"),
        fileExistsAndValid = { true }
    )
    check("Resume rejects all artifacts when export settings fingerprint changed", reusableChangedSettings.isEmpty())
    tmpDir.deleteRecursively()

    // 4. Storage estimate & low-space guard
    val scenes = (1..10).map { k -> Scene("s$k", (k - 1) * 3.0, k * 3.0).apply { num = k; image = "/p/$k.png" } }
    val plan = ExportPlan.fromTimeline(Timeline.fromScenes(scenes, 30.0))
    val estLow = ExportTracker.estimateStorage(plan, ExportSettings.P1080, UpscaleFactor.X2, IntermediateFormat.JPEG, freeBytes = 10L * 1024L * 1024L)
    check("Storage estimator flags insufficient space (10 MB free) with actual numbers",
        !estLow.sufficient && estLow.warningMessage != null && estLow.warningMessage!!.contains("MB"))
    val estPlenty = ExportTracker.estimateStorage(plan, ExportSettings.P1080, UpscaleFactor.X2, IntermediateFormat.JPEG, freeBytes = 5L * 1024L * 1024L * 1024L)
    check("Storage estimator passes when 5 GB free", estPlenty.sufficient && estPlenty.warningMessage == null)

    // 5. Output verification rules
    val vOk = ExportTracker.verifyOutputMetadata(
        fileExists = true, fileLengthBytes = 1_000_000L, playable = true,
        expectedDurationMs = 30_000L, actualDurationMs = 30_120L,
        expectedWidth = 1920, expectedHeight = 1080, actualWidth = 1920, actualHeight = 1080,
        expectedAudio = true, actualAudio = true
    )
    check("Output verification passes when playable, duration within tolerance, dimensions & audio match", vOk.ok)

    val vBadDur = ExportTracker.verifyOutputMetadata(
        fileExists = true, fileLengthBytes = 1_000_000L, playable = true,
        expectedDurationMs = 30_000L, actualDurationMs = 25_000L,
        expectedWidth = 1920, expectedHeight = 1080, actualWidth = 1920, actualHeight = 1080,
        expectedAudio = true, actualAudio = true
    )
    check("Output verification fails with reason when duration drifts by 5s", !vBadDur.ok && vBadDur.failureReason.contains("duration"))

    val vNoAudio = ExportTracker.verifyOutputMetadata(
        fileExists = true, fileLengthBytes = 1_000_000L, playable = true,
        expectedDurationMs = 30_000L, actualDurationMs = 30_000L,
        expectedWidth = 1920, expectedHeight = 1080, actualWidth = 1920, actualHeight = 1080,
        expectedAudio = true, actualAudio = false
    )
    check("Output verification fails when expected audio track is missing", !vNoAudio.ok && vNoAudio.failureReason.contains("audio"))

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
