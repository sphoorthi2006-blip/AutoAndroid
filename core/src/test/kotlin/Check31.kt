import com.auto.core.*
import java.io.File
import java.nio.file.Files

/**
 * Check31: Corrective Specification Verification Suite (Phases C1–C4):
 * 1. StreamingPngWriter + PngFormatVerifier (strip-based PNG encoding without full-output Bitmap, boundary handling, and cancellation/failure cleanup).
 * 2. UpscaleMemoryGuard (4000x3000 at 2x and 4x allocation map, peak live memory reduction, overflow protection, and no silent 4x -> 2x reduction).
 * 3. UpscaleCache (pixel-complete cacheKey with quality/EXIF rotation and ucache_ ownership verification before eviction).
 * 4. ExportCancellationGuard & ExportJobStore (post-cancellation write prevention, system timeout interruption, and idempotent retry cleanup of pendingMediaStoreUri).
 * 5. WorkspaceSessionSimulator (all 10x10 = 100 workspace transitions and contextual timeline selection transitions with zero state loss).
 */
fun main() {
    val tmpRoot = Files.createTempDirectory("check31_correction").toFile()
    try {
        val pngFile = File(tmpRoot, "streamed_test.png")
        val w = 32
        val h = 20
        StreamingPngWriter.writeFile(pngFile, w, h, hasAlpha = false) { writer ->
            val strip1 = IntArray(w * 8) { idx -> 0xFF112233.toInt() or ((idx and 0xFF) shl 8) }
            val strip2 = IntArray(w * 8) { idx -> 0xFF445566.toInt() or (idx and 0xFF) }
            val strip3 = IntArray(w * 4) { 0xFF778899.toInt() }
            writer.writeStrip(strip1, rows = 8)
            writer.writeStrip(strip2, rows = 8)
            writer.writeStrip(strip3, rows = 4)
            check(writer.rowsWritten == h) { "Expected $h rows written, got ${writer.rowsWritten}" }
        }
        val parsed = PngFormatVerifier.inspect(pngFile)
        check(parsed.valid && parsed.width == w && parsed.height == h && parsed.idatChunks > 0 && parsed.hasIend) {
            "Streaming PNG verification failed: ${parsed.error}"
        }
        println("PASS: Check31.1 StreamingPngWriter + PngFormatVerifier multi-strip PNG verified")

        val cancelFile = File(tmpRoot, "cancelled_test.png")
        var threwCancel = false
        try {
            StreamingPngWriter.writeFile(cancelFile, 16, 16, hasAlpha = false) { writer ->
                writer.writeStrip(IntArray(16 * 8) { 0xFF000000.toInt() }, rows = 8)
                throw IllegalStateException("Simulated user cancellation mid-upscale")
            }
        } catch (_: IllegalStateException) {
            threwCancel = true
        }
        check(threwCancel && !cancelFile.exists()) { "Aborted StreamingPngWriter.writeFile must delete partial file" }
        println("PASS: Check31.2 StreamingPngWriter partial file cleanup on cancellation verified")

        val alloc2x = UpscaleMemoryGuard.computeAllocationMap(4000, 3000, 2)
        check(alloc2x.outWidth == 8000 && alloc2x.outHeight == 6000) { "2x dimensions must be 8000x6000" }
        check(alloc2x.fullOutputBitmapBytesIfMaterialized == 192_000_000L) { "Legacy full output bitmap at 8000x6000 is 192 MB" }

        val alloc4x = UpscaleMemoryGuard.computeAllocationMap(4000, 3000, 4)
        check(alloc4x.outWidth == 16000 && alloc4x.outHeight == 12000) { "4x dimensions must be 16000x12000" }
        check(alloc4x.fullOutputBitmapBytesIfMaterialized == 768_000_000L) { "Legacy full output bitmap at 16000x12000 is 768 MB" }
        check(alloc4x.peakStreamingLiveBytes < 80L * 1024L * 1024L) {
            "Peak streaming live memory for 4000x3000 at 4x must be < 80 MB, got ${alloc4x.peakStreamingLiveBytes}"
        }
        println("PASS: Check31.3 UpscaleMemoryGuard 4000x3000 at 2x/4x allocation map verified")

        val pfPng = UpscaleMemoryGuard.preflightCheck(4000, 3000, 4, IntermediateFormat.PNG, 256L * 1024L * 1024L)
        check(pfPng.safe && !pfPng.requiresUserChoice) { "4000x3000 at 4x Streaming PNG must pass preflight" }

        val pfJpeg = UpscaleMemoryGuard.preflightCheck(4000, 3000, 4, IntermediateFormat.JPEG, 256L * 1024L * 1024L)
        check(!pfJpeg.safe && pfJpeg.requiresUserChoice) {
            "4000x3000 at 4x JPEG must fail preflight and require explicit user choice instead of silent 4x -> 2x reduction"
        }
        check(pfJpeg.choices.any { it.contains("Streaming PNG") } && pfJpeg.choices.any { it.contains("2×") }) {
            "Preflight failure must offer Streaming PNG and 2x choices"
        }

        val pfOverflow = UpscaleMemoryGuard.preflightCheck(1_000_000, 1_000_000, 4, IntermediateFormat.PNG, 512L * 1024L * 1024L)
        check(!pfOverflow.safe && pfOverflow.requiresUserChoice) { "1,000,000 x 1,000,000 at 4x must be caught by overflow guard" }
        println("PASS: Check31.4 UpscaleMemoryGuard preflight & overflow protection verified")

        val sha = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val keyQ95Rot0 = UpscaleEngine.cacheKey(sha, scale = 2, format = IntermediateFormat.JPEG, quality = 95, exifRotationDeg = 0)
        val keyQ80Rot0 = UpscaleEngine.cacheKey(sha, scale = 2, format = IntermediateFormat.JPEG, quality = 80, exifRotationDeg = 0)
        val keyQ95Rot90 = UpscaleEngine.cacheKey(sha, scale = 2, format = IntermediateFormat.JPEG, quality = 95, exifRotationDeg = 90)
        check(keyQ95Rot0 != keyQ80Rot0 && keyQ95Rot0 != keyQ95Rot90) {
            "Cache key must differentiate JPEG quality and EXIF rotation"
        }

        val cacheDir = File(tmpRoot, "upscale_cache").apply { mkdirs() }
        val foreignFile = File(cacheDir, "user_important_photo.png").apply { writeText("DO NOT DELETE") }
        val cache = UpscaleCache(cacheDir, maxBytes = 512L)
        val cachedOut = cache.put(sha, scale = 2, format = IntermediateFormat.PNG, tempProducer = { tmp ->
            tmp.writeBytes(ByteArray(300) { 1 })
        })
        check(cachedOut.name.startsWith("ucache_") && cachedOut.exists()) { "Cached file must have ucache_ prefix" }
        cache.clear()
        check(foreignFile.exists()) { "UpscaleCache.clear() must never delete files without ucache_ prefix" }
        check(!cachedOut.exists()) { "UpscaleCache.clear() must delete ucache_ entries" }
        println("PASS: Check31.5 UpscaleCache pixel-complete keys and ucache_ ownership guard verified")

        val jobStore = ExportJobStore(File(tmpRoot, "jobs"))
        val guard = ExportCancellationGuard(jobStore)
        val activeJob = ExportJob(
            jobId = "job_test",
            projectId = "proj_1",
            projectName = "Test Project",
            status = ExportJobStatus.ACTIVE,
            stage = ExportStage.RENDERING,
            progressPct = 65,
            doneItems = 6,
            totalItems = 10,
            elapsedMs = 4000L,
            throughputImgPerMin = 90.0,
            etaMs = 2500L,
            pendingMediaStoreUri = "content://media/external/video/media/999"
        )
        jobStore.save(activeJob)
        val deletedUris = ArrayList<String>()
        val tempOutMp4 = File(tmpRoot, "auto_partial.mp4").apply { writeText("partial") }
        val cancelledJob = guard.cancelActive("job_test", listOf(tempOutMp4)) { uri -> deletedUris.add(uri) }
        check(cancelledJob?.status == ExportJobStatus.CANCELLED) { "Cancelled job must have CANCELLED status" }
        check(!tempOutMp4.exists()) { "Temporary output must be deleted on cancel" }
        check("content://media/external/video/media/999" in deletedUris) { "Pending MediaStore URI must be deleted on cancel" }

        var lateCallbackRan = false
        guard.runIfNotCancelled("job_test") { lateCallbackRan = true }
        check(!lateCallbackRan) { "Late callback after cancellation must not execute" }

        val job2 = activeJob.copy(
            jobId = "job_timeout",
            completedArtifacts = mapOf("/src/a.jpg" to "/cache/ucache_a.png"),
            pendingMediaStoreUri = "content://media/external/video/media/1000"
        )
        jobStore.save(job2)
        val interrupted = jobStore.markInterrupted("job_timeout", "OS mediaProcessing timeout")
        check(interrupted?.status == ExportJobStatus.INTERRUPTED && interrupted.completedArtifacts.size == 1) {
            "System timeout must transition job to INTERRUPTED while preserving completedArtifacts"
        }
        println("PASS: Check31.6 ExportCancellationGuard and system timeout recovery verified")

        val tl = Timeline.blank(12_000L)
            .insertClip("/tmp/pic1.jpg", null, 4000L)
            .insertClip("/tmp/pic2.jpg", null, 4000L)
            .addTextAt(1000L, "Hello World", OverlayStyle())
        val initialSession = WorkspaceSessionState(
            projectId = "proj_shared",
            timeline = tl,
            playheadMs = 2500L,
            selectedClipId = tl.clips.first().id,
            selectedTextId = null,
            selectedTextLayerIds = emptySet(),
            zoomDpPerSec = 90f,
            scrollOffsetPx = 225,
            canUndo = true,
            canRedo = false,
            aspect = AspectRatio.R16_9,
            exportResP = 1080,
            exportFps = 60,
            activeWorkspace = WorkspaceMode.CLASSIC_TIMELINE
        )
        val allPairs = WorkspaceSessionSimulator.verifyAllPairs(initialSession)
        check(allPairs.size == 100 && allPairs.all { it.allPreserved }) {
            "All 10x10 workspace transitions must preserve project, timeline, playhead, selection, zoom/scroll, undo/redo, and export settings"
        }
        val afterTextSelect = WorkspaceSessionSimulator.selectTextLayerAndFocus(initialSession, tl.texts.first().id)
        check(afterTextSelect.activeWorkspace == WorkspaceMode.TEXT_FOCUS && afterTextSelect.selectedTextId == tl.texts.first().id && afterTextSelect.timeline == tl) {
            "Selecting a text layer must transition to TEXT_FOCUS while preserving timeline"
        }
        val afterAudioSelect = WorkspaceSessionSimulator.selectAudioTrackAndFocus(afterTextSelect)
        check(afterAudioSelect.activeWorkspace == WorkspaceMode.AUDIO_FOCUS && afterAudioSelect.timeline == tl) {
            "Selecting audio track must transition to AUDIO_FOCUS while preserving timeline"
        }
        println("PASS: Check31.7 10x10 WorkspaceSessionSimulator and contextual transitions verified")
        println("ALL PASS (Check31)")
    } finally {
        tmpRoot.deleteRecursively()
    }
}
