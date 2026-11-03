import com.auto.core.*
import java.io.File

/**
 * Phase 5 tests (Section 15): AI image upscaling engine, smart factor, profiles, persistent content-hash cache,
 * LRU eviction, and thermal/memory thread adaptation.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    // 1. Smart factor rules
    check("smartFactor skips already adequate 1920x1080 image on 1080p export",
        UpscaleEngine.smartFactor(1920, 1080, 1920, 1080) == UpscaleFactor.OFF)
    check("smartFactor selects 2x for 1280x720 image on 1920x1080 export",
        UpscaleEngine.smartFactor(1280, 720, 1920, 1080) == UpscaleFactor.X2)
    check("smartFactor selects 4x for 640x360 image on 1920x1080 export",
        UpscaleEngine.smartFactor(640, 360, 1920, 1080) == UpscaleFactor.X4)

    // 2. UpscaleProfile resolution (Fast, Balanced, Maximum, Manual)
    check("FAST profile skips adequate image and caps small image at 2x JPEG",
        UpscaleEngine.resolveForImage(UpscaleProfile.FAST, UpscaleFactor.X4, IntermediateFormat.PNG, 1920, 1080, 1920, 1080).first == UpscaleFactor.OFF &&
        UpscaleEngine.resolveForImage(UpscaleProfile.FAST, UpscaleFactor.X4, IntermediateFormat.PNG, 640, 360, 1920, 1080) == Pair(UpscaleFactor.X2, IntermediateFormat.JPEG))

    check("BALANCED profile uses smart factor + JPEG",
        UpscaleEngine.resolveForImage(UpscaleProfile.BALANCED, UpscaleFactor.OFF, IntermediateFormat.PNG, 640, 360, 1920, 1080) == Pair(UpscaleFactor.X4, IntermediateFormat.JPEG))

    check("MAXIMUM profile always uses 4x PNG and never silently steps down to 2x",
        UpscaleEngine.resolveForImage(UpscaleProfile.MAXIMUM, UpscaleFactor.OFF, IntermediateFormat.JPEG, 1000, 800, 1920, 1080) == Pair(UpscaleFactor.X4, IntermediateFormat.PNG) &&
        UpscaleEngine.resolveForImage(UpscaleProfile.MAXIMUM, UpscaleFactor.OFF, IntermediateFormat.JPEG, 2000, 1500, 1920, 1080) == Pair(UpscaleFactor.X4, IntermediateFormat.PNG))

    // 3. Persistent UpscaleCache: content-hash lookup, reuse on second run, invalidation on content change, LRU eviction, clear
    val tmpDir = File.createTempFile("upscale_cache_test", "").also { it.delete(); it.mkdirs() }
    var now = 100_000L
    val cache = UpscaleCache(tmpDir, maxBytes = 2L * 1024L * 1024L) { now } // 2 MB cap for test

    val shaA = "a".repeat(64)
    val shaB = "b".repeat(64)
    val shaC = "c".repeat(64)

    check("Cache miss before first put", cache.get(shaA, 2, IntermediateFormat.JPEG) == null)
    val savedA = cache.put(shaA, 2, IntermediateFormat.JPEG, tempProducer = { f -> f.writeBytes(ByteArray(900_000) { 1 }) })
    now += 1000L
    val hitA = cache.get(shaA, 2, IntermediateFormat.JPEG)
    check("Second run reuses cached output for identical hash + scale + format", hitA != null && hitA.path == savedA.path)
    check("Different scale (4x) or format (PNG) or content hash (shaB) invalidates/misses cache",
        cache.get(shaA, 4, IntermediateFormat.JPEG) == null &&
        cache.get(shaA, 2, IntermediateFormat.PNG) == null &&
        cache.get(shaB, 2, IntermediateFormat.JPEG) == null)

    now += 1000L
    cache.put(shaB, 2, IntermediateFormat.JPEG, tempProducer = { f -> f.writeBytes(ByteArray(900_000) { 2 }) })
    // Touch shaA so shaB becomes the oldest (LRU) entry
    now += 1000L
    cache.get(shaA, 2, IntermediateFormat.JPEG)
    now += 1000L
    // Adding shaC (900 KB) exceeds 2 MB cap -> should evict shaB (oldest) and keep shaA + shaC
    cache.put(shaC, 2, IntermediateFormat.JPEG, tempProducer = { f -> f.writeBytes(ByteArray(900_000) { 3 }) })
    check("LRU eviction removes least-recently-used entry (shaB) and keeps recently used (shaA) + new (shaC)",
        cache.get(shaB, 2, IntermediateFormat.JPEG) == null &&
        cache.get(shaA, 2, IntermediateFormat.JPEG) != null &&
        cache.get(shaC, 2, IntermediateFormat.JPEG) != null &&
        cache.currentSizeBytes() <= 2L * 1024L * 1024L)

    val freed = cache.clear()
    check("Clear Upscale Cache removes all cached entries and returns freed byte count",
        freed > 0L && cache.entryCount() == 0 && cache.currentSizeBytes() == 0L)
    tmpDir.deleteRecursively()

    // 4. Thermal / memory thread adaptation
    check("recommendedThreads adapts to thermal throttling and low RAM",
        UpscaleEngine.recommendedThreads(cpuCores = 8, availableRamMb = 4096, thermalSeverity = 0) == 4 && // performance cores only
        UpscaleEngine.recommendedThreads(cpuCores = 8, availableRamMb = 800, thermalSeverity = 1) == 2 &&
        UpscaleEngine.recommendedThreads(cpuCores = 8, availableRamMb = 4096, thermalSeverity = 2) == 1)

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
