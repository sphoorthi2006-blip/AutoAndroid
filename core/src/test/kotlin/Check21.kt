import com.auto.core.*

/** Phase 0 tests: Diagnostics stage timers, throughput calculation, JSON round-trip, and privacy sanitization. */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    val m1 = StageMetric(DiagStage.UPSCALE, durationMs = 30_000L, itemsDone = 15, itemsTotal = 20, javaHeapPeakMb = 142, nativeHeapPeakMb = 210)
    check("throughput 15 items in 30s = 30.0/min", kotlin.math.abs(m1.itemsPerMin - 30.0) < 0.01)
    check("formatted throughput", m1.formattedItemsPerMin() == "30.0/min")

    val mZero = StageMetric(DiagStage.FINALIZE, durationMs = 50L, itemsDone = 0, itemsTotal = 1)
    check("zero items throughput is 0", mZero.itemsPerMin == 0.0 && mZero.formattedItemsPerMin() == "—")

    val sanitized = Diagnostics.sanitizeStatus("Failed on /storage/emulated/0/Pictures/secret_photo.jpg and 'my_script.docx'")
    check("sanitizeStatus strips unix paths", !sanitized.contains("/storage") && !sanitized.contains("secret_photo"))
    check("sanitizeStatus strips quoted filenames", !sanitized.contains("my_script.docx"))

    val report = DiagnosticRunReport(
        timestampMs = 1762150000000L,
        resolutionP = 1080,
        fps = 30,
        aspect = "16:9",
        upscaleFactor = 2,
        upscaleProfile = "BALANCED",
        sceneCount = 200,
        videoDurationMs = 600_000L,
        totalExportMs = 120_000L,
        status = "Failed at /data/user/0/com.auto.app/files/private.png",
        stages = listOf(
            StageMetric(DiagStage.IMPORT, 1200L, 200, 200, 64, 32),
            m1,
            StageMetric(DiagStage.RENDER, 88_000L, 200, 200, 160, 195)
        )
    )
    val json = Diagnostics.serializeHistory(listOf(report))
    check("serialized JSON contains no raw paths", !json.contains("private.png") && !json.contains("/data/user"))
    val parsed = Diagnostics.parseHistory(json)
    check("history round-trip count = 1", parsed.size == 1)
    val r2 = parsed.first()
    check("round-trip resP & scenes", r2.resolutionP == 1080 && r2.sceneCount == 200 && r2.upscaleProfile == "BALANCED")
    check("round-trip stages count = 3", r2.stages.size == 3 && r2.peakJavaMb == 160 && r2.peakNativeMb == 210)

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
