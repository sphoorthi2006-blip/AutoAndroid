import com.auto.core.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Phase 1 tests:
 * 1. Streaming waveform (PeakStreamer) & PagedSampleBuffer vs full-array Waveform.peaks and AudioPauses parity on long synthetic narration.
 * 2. AutosaveCoordinator out-of-order completion race guard and TaskToken cancellation state safety.
 * 3. ZipSafety zip-slip defense + entry-count, single-entry-size, and total-expanded-size limits.
 * 4. Section 4 Midnight Violet DesignTokens exact hex values, no-orange check, and WCAG contrast verification.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    // ---- 1. Bounded audio & streaming waveform parity ----
    val sr = AudioPauses.SR
    val durSec = 60 // 60s synthetic narration with clear voiced segments and 0.6s pauses every 5s
    val totalSamples = sr * durSec
    val streamer = AudioStream.PeakStreamer(sr, 20)
    val paged = AudioStream.PagedSampleBuffer()
    for (i in 0 until totalSamples) {
        val sec = i.toDouble() / sr
        val inPause = (sec % 5.0) >= 4.3 && (sec % 5.0) < 4.95
        val s = if (inPause) 0.0001f else (0.4 * sin(2.0 * PI * 220.0 * sec)).toFloat()
        streamer.accept(s)
        paged.add(s)
    }
    val fullArray = paged.toFloatArray()
    val streamedPeaks = streamer.finish(durSec * 20)
    val fullPeaks = Waveform.peaks(fullArray, durSec * 20)
    check("PagedSampleBuffer exact length", fullArray.size == totalSamples)
    check("Streamed peaks match full-array Waveform.peaks within 1e-5",
        streamedPeaks.size == fullPeaks.size && streamedPeaks.indices.all { abs(streamedPeaks[it] - fullPeaks[it]) < 1e-5f })
    val silences = AudioPauses.silences(fullArray, 0.5)
    check("AudioPauses detects identical silences on paged narration", silences != null && silences.size >= 10)

    // ---- 2. Autosave ordering & cancellation state safety ----
    val coordinator = AutosaveCoordinator()
    val seq1 = coordinator.nextSequence()
    val seq2 = coordinator.nextSequence()
    var savedState = "initial"
    // Newer save (seq2) finishes first
    val r2 = coordinator.runIfNewest(seq2) { savedState = "new_state_2"; savedState }
    // Older save (seq1) finishes later (out-of-order completion) -> must NOT overwrite savedState
    val r1 = coordinator.runIfNewest(seq1) { savedState = "old_state_1"; savedState }
    check("AutosaveCoordinator commits newest save", r2 == "new_state_2" && savedState == "new_state_2")
    check("AutosaveCoordinator rejects out-of-order older save", r1 == null && savedState == "new_state_2")

    val token = TaskToken()
    var mutated = false
    token.runIfActive { mutated = true }
    check("Active TaskToken runs block", mutated)
    mutated = false
    token.cancel()
    val ranAfterCancel = token.runIfActive { mutated = true }
    check("Cancelled TaskToken never mutates state", !ranAfterCancel && !mutated)

    // ---- 3. ZIP limits & zip-slip defense ----
    val tmpDir = File.createTempFile("zip_test", "").also { it.delete(); it.mkdirs() }
    val zipFile = File(tmpDir, "test.zip")
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
        for (i in 1..4) {
            zos.putNextEntry(ZipEntry("img$i.png"))
            zos.write(ByteArray(1024) { 0x42 })
            zos.closeEntry()
        }
    }
    val outDir = File(tmpDir, "out")
    val tooManyEntries = runCatching {
        ZipSafety.extractPicturesFromZip(zipFile, outDir, limits = ZipLimits(maxEntries = 2))
    }.exceptionOrNull()
    check("ZIP maxEntries limit enforced with clear message",
        tooManyEntries is PipelineError && tooManyEntries.message!!.contains("entries"))

    val singleTooBig = runCatching {
        ZipSafety.extractPicturesFromZip(zipFile, outDir, limits = ZipLimits(maxSingleEntryBytes = 512L))
    }.exceptionOrNull()
    check("ZIP maxSingleEntryBytes limit enforced with clear message",
        singleTooBig is PipelineError && singleTooBig.message!!.contains("single-file limit"))

    val totalTooBig = runCatching {
        ZipSafety.extractPicturesFromZip(zipFile, outDir, limits = ZipLimits(maxTotalExpandedBytes = 2048L))
    }.exceptionOrNull()
    check("ZIP maxTotalExpandedBytes limit enforced with clear message",
        totalTooBig is PipelineError && totalTooBig.message!!.contains("total extracted size"))

    val okRefs = ZipSafety.extractPicturesFromZip(zipFile, outDir, limits = ZipLimits(maxEntries = 10, maxSingleEntryBytes = 4096, maxTotalExpandedBytes = 10_000))
    check("Valid ZIP within limits extracts all 4 images", okRefs.size == 4)
    tmpDir.deleteRecursively()

    // ---- 4. Midnight Violet DesignTokens & WCAG contrast ----
    val D = DesignTokens
    check("Exact Midnight Violet token values match Section 4 table",
        D.BG == 0xFF0B1020.toInt() &&
        D.SURFACE == 0xFF121A2B.toInt() &&
        D.SURFACE_HI == 0xFF192338.toInt() &&
        D.LINE == 0xFF273449.toInt() &&
        D.TEXT == 0xFFF8FAFC.toInt() &&
        D.TEXT_MUTED == 0xFF94A3B8.toInt() &&
        D.PRIMARY == 0xFF8B5CF6.toInt() &&
        D.CYAN == 0xFF38BDF8.toInt() &&
        D.SUCCESS == 0xFF34D399.toInt() &&
        D.ERROR == 0xFFF87171.toInt() &&
        D.WARNING == 0xFFFBBF24.toInt())
    check("TEXT and TEXT_MUTED pass >= 4.5:1 on BG, SURFACE, and SURFACE_HI",
        listOf(D.BG, D.SURFACE, D.SURFACE_HI).all { D.contrast(D.TEXT, it) >= 4.5 && D.contrast(D.TEXT_MUTED, it) >= 4.5 })
    check("Lighter violet (PRIMARY_LIGHT) passes >= 4.5:1 for small text on cards",
        listOf(D.BG, D.SURFACE, D.SURFACE_HI).all { D.contrast(D.PRIMARY_LIGHT, it) >= 4.5 })
    check("No orange in ColorText.PRESETS", ColorText.PRESETS.none { (it and 0xFFFFFF) == 0xFF9F0A })

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
