import com.auto.core.*

/**
 * Phase 8 & 9 tests (Sections 6, 7, 19):
 * - All 10 required workspaces exist in `WorkspaceMode` with numbers 1..10.
 * - Contextual transitions map `text`, `audio`, `quick`, `properties`, `design`, `compare`, and `vertical` accurately.
 * - ControlRegistry documents all 7 required inner-screen groups (`Text layer`, `Audio clip`, `Image`, `Picture clip`, `Layers`, `Export`, `Project`) with zero dead controls.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    // 1. All 10 required workspaces
    val modes = WorkspaceMode.values().toList()
    check("Exactly 10 workspace modes numbered 1..10",
        modes.size == 10 && modes.map { it.number } == (1..10).toList())
    check("All 10 workspace names match Section 6 specification",
        modes.map { it.name } == listOf(
            "CLASSIC_TIMELINE", "COMPACT_EDITOR", "ADVANCED_MULTI_PANEL", "TEXT_FOCUS", "AUDIO_FOCUS",
            "QUICK_EDIT", "DESIGN_STUDIO", "DUAL_PREVIEW", "VERTICAL_EDITING", "MINIMALIST"
        ))

    // 2. Contextual transitions
    check("Contextual transitions map text->TEXT_FOCUS, audio->AUDIO_FOCUS, quick->QUICK_EDIT, properties->ADVANCED_MULTI_PANEL, design->DESIGN_STUDIO, compare->DUAL_PREVIEW, vertical->VERTICAL_EDITING",
        WorkspaceTransitions.targetForContext("text") == WorkspaceMode.TEXT_FOCUS &&
        WorkspaceTransitions.targetForContext("audio") == WorkspaceMode.AUDIO_FOCUS &&
        WorkspaceTransitions.targetForContext("quick") == WorkspaceMode.QUICK_EDIT &&
        WorkspaceTransitions.targetForContext("properties") == WorkspaceMode.ADVANCED_MULTI_PANEL &&
        WorkspaceTransitions.targetForContext("design") == WorkspaceMode.DESIGN_STUDIO &&
        WorkspaceTransitions.targetForContext("compare") == WorkspaceMode.DUAL_PREVIEW &&
        WorkspaceTransitions.targetForContext("vertical") == WorkspaceMode.VERTICAL_EDITING)

    // 3. Section 7 Inner Screens & Contextual Control Registry (no dead controls)
    val requiredGroups = listOf("Text layer", "Audio clip", "Image", "Picture clip", "Layers", "Export", "Project")
    check("ControlRegistry covers all 7 required Section 7 groups",
        requiredGroups.all { ControlRegistry.byGroup(it).isNotEmpty() })
    check("Every control has non-blank modelField, previewBehavior, exportBehavior, persistence, and accessibilityLabel",
        ControlRegistry.controls.all {
            it.modelField.isNotBlank() &&
            it.previewBehavior.isNotBlank() &&
            it.exportBehavior.isNotBlank() &&
            it.persistence.isNotBlank() &&
            it.accessibilityLabel.isNotBlank()
        })
    check("Unavailable controls in ControlRegistry have an explicit Soon/unavailable reason from RenderCaps",
        ControlRegistry.controls.filter { !it.isAvailable }.all { it.soonReason.isNotBlank() })

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
