import com.auto.core.*

var fails13 = 0
fun ok13(name: String, c: Boolean) { println((if (c) "PASS " else "FAIL ") + name); if (!c) fails13++ }

/** Free scene lengths (no magnet), narration never changes, export limited to the narration, export presets, typed times. */
fun main() {
    val min = 60_000L
    // six 2-minute scenes over a 12-minute narration
    val tl = Timeline(12 * min, (0 until 6).map { ImageClip(it + 1, it + 1, "/p/$it.png", it * 2 * min, (it + 1) * 2 * min, "t$it") })

    val ext = tl.setLength(1, 10 * min)
    ok13("extend scene 1 to 10 min: it is 10 min long", ext.clip(1).startMs == 0L && ext.clip(1).endMs == 10 * min)
    ok13("extend: every later scene is pushed by 8 min, keeping its length", (2..6).all { ext.clip(it).startMs == tl.clip(it).startMs + 8 * min && ext.clip(it).durMs == 2 * min })
    ok13("extend: the narration length does not change", ext.audioMs == 12 * min)
    ok13("extend: no scene was squeezed or touched before scene 1", ext.clips.size == 6)

    val plan = ExportPlan.fromTimeline(ext)
    ok13("export: video is exactly as long as the narration (12 min)", plan.totalMs == 12 * min && plan.segments.last().endMs == 12 * min)
    ok13("export: scene 1 = 10 min, then only the scene that fits in the last 2 min", plan.segments.map { it.sceneNum } == listOf(1, 2) && plan.segments[0].durMs == 10 * min && plan.segments[1].startMs == 10 * min)
    ok13("export: scenes starting after the narration are left out, not squeezed in", plan.segments.none { (it.sceneNum ?: 0) >= 3 })
    ok13("export: contiguous", plan.segments.zipWithNext().all { (a, b) -> a.endMs == b.startMs })

    val cross = tl.setLength(1, 11 * min)   // scene 2 now starts at 11:00 and would end at 13:00 -> cut at 12:00
    val pc = ExportPlan.fromTimeline(cross)
    ok13("export: the scene that crosses the end of the narration is cut there", pc.segments.last().sceneNum == 2 && pc.segments.last().endMs == 12 * min && pc.segments.last().durMs == min)

    ok13("no narration (clampToAudio = false): every scene is kept", ExportPlan.fromTimeline(ext, false).segments.size == 6 && ExportPlan.fromTimeline(ext, false).totalMs == 20 * min)

    val sh = tl.setLength(2, 30_000L)
    ok13("shorten scene 2 to 30 s: later scenes are pulled earlier by 90 s", sh.clip(2).durMs == 30_000L && sh.clip(3).startMs == tl.clip(3).startMs - 90_000L && sh.clip(1) == tl.clip(1))
    ok13("shorten: undo-able snapshot (original untouched)", tl.clip(3).startMs == 4 * min)
    ok13("minimum length is 100 ms", tl.setLength(1, 5).clip(1).durMs == Timeline.MIN_CLIP_MS)
    ok13("same length -> same timeline object", tl.setLength(1, 2 * min) === tl)
    ok13("last scene can be extended past the narration, nothing else moves", tl.setLength(6, 20 * min).clip(6).endMs == 10 * min + 20 * min && tl.setLength(6, 20 * min).clip(5) == tl.clip(5))
    ok13("unknown scene id -> error", try { tl.setLength(99, 1000); false } catch (e: IllegalArgumentException) { true })
    val ed = TimelineEditor(tl); ed.edit { it.setLength(1, 10 * min) }
    ok13("undo / redo work with setLength", ed.canUndo && ed.undo().clip(1).endMs == 2 * min && ed.redo().clip(1).endMs == 10 * min)

    // export presets
    ok13("preset 720p/1080p/4K at 30 and 60 fps", ExportSettings.preset(720, 30) == ExportSettings(1280, 720, 30) && ExportSettings.preset(1080, 60) == ExportSettings(1920, 1080, 60) && ExportSettings.preset(2160, 60) == ExportSettings(3840, 2160, 60) && ExportSettings.P4K.width == 3840)
    ok13("preset: unknown resolution rejected", try { ExportSettings.preset(480, 30); false } catch (e: IllegalArgumentException) { true })

    // typed times
    val cases = mapOf("90" to 90_000L, "2.5" to 2_500L, "1:30" to 90_000L, "10:00" to 600_000L, "1:02:03" to 3_723_000L, "10m" to 600_000L, "45s" to 45_000L, "1m30s" to 90_000L, " 12 min " to 720_000L, "0,5" to 500L)
    for ((k, v) in cases) ok13("time text '$k' = $v ms", TimeText.parse(k) == v)
    for (bad in listOf("", "abc", "0", "-5", "1:", ":30", "1:2:3:4", "1.5:30", "99999999")) ok13("time text '$bad' rejected", TimeText.parse(bad) == null)
    ok13("time text format", TimeText.format(600_000) == "10:00" && TimeText.format(125_500) == "2:05.5" && TimeText.format(3_723_000) == "1:02:03")
    println(if (fails13 == 0) "ALL PASSED" else "$fails13 FAILED"); if (fails13 > 0) System.exit(1)
}
