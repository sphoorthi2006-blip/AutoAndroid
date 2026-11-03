import com.auto.core.*

/**
 * Phase 3 tests (Section 12): Solid-colour scene replacement.
 * - Black (#FF000000), White (#FFFFFFFF), and Custom ARGB (#FF8B5CF6) on 1s, 7s, and 10s scenes.
 * - Preserves scene timing, start/end/duration, audio sync, and narration.
 * - Timeline JSON v1 compatibility (old projects load; solid scenes round-trip).
 * - Validation does not flag solid screen as missing picture.
 * - ExportPlan and Upscaler skip solid screens while preserving overlay text and click placement.
 * - Switch picture <-> colour both ways; Delete Replacement restores original picture.
 * - Undo/redo through TimelineEditor.
 * - RenderCaps includes solidColor with status WRITTEN.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    val scenes = listOf(
        Scene("One second intro", 0.0, 1.0).apply { num = 1; image = "/p/img1.png" },
        Scene("Seven second middle", 1.0, 8.0).apply { num = 2; image = "/p/img2.png" },
        Scene("Ten second finale", 8.0, 18.0).apply { num = 3; image = "/p/img3.png" }
    )
    val base = Timeline.fromScenes(scenes, 18.0).addText(500, 7500, "Overlay Above Solid")
    val ed = TimelineEditor(base)

    // 1. Replace 1s scene with Black, 7s scene with White, 10s scene with Custom ARGB (#FF38BDF8)
    ed.edit { it.replaceColor(1, 0xFF000000.toInt()) }
    ed.edit { it.replaceColor(2, 0xFFFFFFFF.toInt()) }
    ed.edit { it.replaceColor(3, 0xFF38BDF8.toInt()) }
    val solidTl = ed.current

    check("Durations (1s, 7s, 10s) and start/end boundaries are strictly preserved",
        solidTl.clip(1).let { it.startMs == 0L && it.endMs == 1000L && it.durMs == 1000L } &&
        solidTl.clip(2).let { it.startMs == 1000L && it.endMs == 8000L && it.durMs == 7000L } &&
        solidTl.clip(3).let { it.startMs == 8000L && it.endMs == 18000L && it.durMs == 10000L } &&
        solidTl.audioMs == 18000L)

    check("replacementColor values stored and originalImage preserved",
        solidTl.clip(1).let { it.replacementColor == 0xFF000000.toInt() && it.originalImage == "/p/img1.png" && it.image == null && it.hasReplacement } &&
        solidTl.clip(2).let { it.replacementColor == 0xFFFFFFFF.toInt() && it.originalImage == "/p/img2.png" } &&
        solidTl.clip(3).let { it.replacementColor == 0xFF38BDF8.toInt() && it.originalImage == "/p/img3.png" })

    // 2. Validation does NOT flag solid screen as MISSING_IMAGE
    val issues = solidTl.validate()
    check("Validation does not flag solid-colour scenes as missing picture",
        issues.none { it.kind == IssueKind.MISSING_IMAGE })

    // 3. Timeline JSON v1 save/reopen round-trip
    val json = solidTl.toJson()
    check("Timeline JSON remains version 1", json.startsWith("{\"version\":1,"))
    val reopened = Timeline.Json.parse(json)
    check("Save/reopen preserves replacementColor, originalImage, timings, and overlay text",
        reopened == solidTl && reopened.texts.single().text == "Overlay Above Solid")

    // 4. ExportPlan & Upscaler skip solid screens
    val plan = ExportPlan.fromTimeline(solidTl)
    check("ExportPlan carries replacementColor and overlay text above solid segments",
        plan.usesSolidColor &&
        plan.segments.map { it.replacementColor } == listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF38BDF8.toInt()) &&
        plan.texts.single().text == "Overlay Above Solid")
    check("Upscaler (uniqueSourceImages) skips all solid-colour scenes",
        ExportOptions.uniqueSourceImages(plan) { it }.isEmpty())
    check("Click placement at scene starts (0s, 1s, 8s) remains identical",
        ClickTrack.startsMs(plan).toList() == listOf(0L, 1000L, 8000L))

    // 5. Switch colour <-> picture both ways & Delete Replacement restores original
    ed.edit { it.replaceImage(2, "/p/alt2.png") }
    check("Switch colour -> picture sets replacementImage, clears replacementColor, keeps originalImage",
        ed.current.clip(2).let { it.image == "/p/alt2.png" && it.replacementImage == "/p/alt2.png" && it.replacementColor == null && it.originalImage == "/p/img2.png" })
    ed.edit { it.replaceColor(2, 0xFF8B5CF6.toInt()) }
    check("Switch picture -> colour sets replacementColor, clears replacementImage, keeps originalImage",
        ed.current.clip(2).let { it.image == null && it.replacementImage == null && it.replacementColor == 0xFF8B5CF6.toInt() && it.originalImage == "/p/img2.png" })
    ed.edit { it.deleteReplacement(2) }
    check("Delete Replacement restores original picture (/p/img2.png) and clears replacement fields",
        ed.current.clip(2).let { it.image == "/p/img2.png" && !it.hasReplacement && it.originalImage == null && it.replacementImage == null && it.replacementColor == null })

    // 6. Undo / Redo
    ed.undo() // back to violet solid colour on scene 2
    check("Undo restores solid-colour replacement", ed.current.clip(2).replacementColor == 0xFF8B5CF6.toInt())
    ed.redo() // back to restored original picture on scene 2
    check("Redo re-applies Delete Replacement", ed.current.clip(2).image == "/p/img2.png" && !ed.current.clip(2).hasReplacement)

    // 7. RenderCaps capability check
    check("RenderCaps registers solidColor as WRITTEN and in exporterImplements",
        RenderCaps.available("solidColor") && RenderCaps.isBeta("solidColor") && "solidColor" in RenderCaps.exporterImplements && "solidColor" in RenderCaps.used(plan, true))

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
