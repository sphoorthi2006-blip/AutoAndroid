import com.auto.core.*

/**
 * Phase 7 tests (Section 10): Design Studio — Layer visibility, locking, z-order reordering in preview and export,
 * numeric transforms, safe-area alignment, and >= 3 item distribution.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    var tl = Timeline.blank(10_000L)
        .insertClip("/p/1.png", durationMs = 10_000L)
        .addText(0, 8000, "Back Text", OverlayStyle(x = 0.2f, y = 0.2f))
        .addText(0, 8000, "Middle Text", OverlayStyle(x = 0.8f, y = 0.8f))
        .addText(0, 8000, "Front Text", OverlayStyle(x = 0.4f, y = 0.6f))

    // 1. Rename & z-order reorder honored identically by preview (textsAt) and export (ExportPlan.fromTimeline)
    tl = LayerTools.renameTextLayer(tl, 1, "Hero Headline")
    tl = LayerTools.shiftTextZIndex(tl, 1, +10) // Move id=1 in front of id=2 and id=3
    val previewOrder = tl.textsAt(2000L).map { it.id }
    val exportOrder = ExportPlan.fromTimeline(tl).texts.map { it.id }
    check("Layer rename and zIndex reorder are honored identically in preview and ExportPlan (id 1 drawn last/topmost)",
        tl.text(1).displayName == "Hero Headline" &&
        previewOrder == listOf(2, 3, 1) &&
        exportOrder == previewOrder)

    // 2. Show/Hide layer visibility honored in preview and export
    val hiddenTl = LayerTools.toggleTextVisibility(tl, 2)
    check("Hidden text layer is excluded from both preview (textsAt) and ExportPlan",
        hiddenTl.textsAt(2000L).none { it.id == 2 } &&
        ExportPlan.fromTimeline(hiddenTl).texts.none { it.id == 2 })

    // 3. Lock/Unlock prevents accidental move/transform/reorder
    val lockedTl = LayerTools.toggleTextLock(tl, 1)
    val attemptedMove = lockedTl.moveText(1, 5000L)
    val attemptedTransform = LayerTools.setNumericTransform(lockedTl, 1, x = 0.9f)
    check("Locked layer ignores moveText and setNumericTransform",
        attemptedMove == lockedTl && attemptedTransform == lockedTl)

    // 4. Safe-area alignment
    val aligned = LayerTools.alignToSafeArea(tl, 3, SafeAlign.CENTER_X)
    check("alignToSafeArea(CENTER_X) centers x at 0.5 and sets TextAlign.CENTER",
        aligned.text(3).style.x == 0.5f && aligned.text(3).style.align == TextAlign.CENTER)

    // 5. Distribute requires >= 3 items
    check("canDistribute is false for 2 items and true for 3 items",
        !LayerTools.canDistribute(setOf(1, 2)) && LayerTools.canDistribute(setOf(1, 2, 3)))
    val distX = LayerTools.distributeTextLayers(tl, setOf(1, 2, 3), horizontal = true)
    val xs = distX.texts.map { it.style.x }.sorted()
    check("Distribute horizontally spaces 3 text layers evenly (0.2, 0.5, 0.8)",
        kotlin.math.abs(xs[0] - 0.2f) < 1e-4f &&
        kotlin.math.abs(xs[1] - 0.5f) < 1e-4f &&
        kotlin.math.abs(xs[2] - 0.8f) < 1e-4f)

    // 6. Timeline JSON round-trip preserves layer name, visible, locked, and zIndex
    val complex = hiddenTl.let { LayerTools.toggleTextLock(it, 3) }
    val parsed = Timeline.Json.parse(complex.toJson())
    check("Timeline JSON v1 round-trip preserves layer name, visible, locked, and zIndex",
        parsed == complex)

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
