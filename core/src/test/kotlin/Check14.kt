import com.auto.core.*

var fails14 = 0
fun ok14(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails14++ }

/** Export shapes: resolution x aspect ratio -> pixel sizes, and 16:9 must equal the existing presets. */
fun main() {
    fun sz(r: Resolution, a: AspectRatio) = ExportOptions.size(r, a)
    ok14("720p 16:9 = 1280x720", sz(Resolution.P720, AspectRatio.R16_9) == Pair(1280, 720))
    ok14("720p 9:16 = 720x1280", sz(Resolution.P720, AspectRatio.R9_16) == Pair(720, 1280))
    ok14("720p 1:1 = 720x720", sz(Resolution.P720, AspectRatio.R1_1) == Pair(720, 720))
    ok14("720p 4:5 = 720x900", sz(Resolution.P720, AspectRatio.R4_5) == Pair(720, 900))
    ok14("1080p 16:9 = 1920x1080", sz(Resolution.P1080, AspectRatio.R16_9) == Pair(1920, 1080))
    ok14("1080p 9:16 = 1080x1920", sz(Resolution.P1080, AspectRatio.R9_16) == Pair(1080, 1920))
    ok14("1080p 1:1 = 1080x1080", sz(Resolution.P1080, AspectRatio.R1_1) == Pair(1080, 1080))
    ok14("1080p 4:5 = 1080x1350", sz(Resolution.P1080, AspectRatio.R4_5) == Pair(1080, 1350))
    ok14("4K 16:9 = 3840x2160", sz(Resolution.P2160, AspectRatio.R16_9) == Pair(3840, 2160))
    ok14("4K 9:16 = 2160x3840", sz(Resolution.P2160, AspectRatio.R9_16) == Pair(2160, 3840))
    ok14("4K 4:5 = 2160x2700", sz(Resolution.P2160, AspectRatio.R4_5) == Pair(2160, 2700))
    var allEven = true; var allValid = true
    for (r in Resolution.values()) for (a in AspectRatio.values()) for (f in ExportSettings.FPS) {
        val (w, h) = sz(r, a); if (w % 2 != 0 || h % 2 != 0) allEven = false
        try { ExportOptions.settings(r, a, f) } catch (e: Exception) { allValid = false }
    }
    ok14("every offered size is even", allEven)
    ok14("every combination builds valid ExportSettings (24/30/60 fps)", allValid)
    ok14("16:9 equals the existing P720 / P1080 / P4K presets", ExportOptions.settings(Resolution.P720, AspectRatio.R16_9) == ExportSettings.P720 && ExportOptions.settings(Resolution.P1080, AspectRatio.R16_9) == ExportSettings.P1080 && ExportOptions.settings(Resolution.P2160, AspectRatio.R16_9) == ExportSettings.P4K)
    ok14("16:9 equals ExportSettings.preset at 60 fps", ExportOptions.settings(Resolution.P1080, AspectRatio.R16_9, 60) == ExportSettings.preset(1080, 60))
    ok14("Resolution.of maps 720/1080/2160", Resolution.of(720) == Resolution.P720 && Resolution.of(1080) == Resolution.P1080 && Resolution.of(2160) == Resolution.P2160)
    ok14("Resolution.of refuses other heights", try { Resolution.of(480); false } catch (e: IllegalArgumentException) { true })
    ok14("bad fps is refused", try { ExportOptions.settings(Resolution.P720, AspectRatio.R16_9, 25); false } catch (e: IllegalArgumentException) { true })
    ok14("ratio values", AspectRatio.R16_9.ratio > 1f && AspectRatio.R9_16.ratio < 1f && AspectRatio.R1_1.ratio == 1f && AspectRatio.R4_5.ratio == 0.8f)
    println(if (fails14 == 0) "ALL PASS" else "FAILED $fails14")
    if (fails14 > 0) kotlin.system.exitProcess(1)
}
