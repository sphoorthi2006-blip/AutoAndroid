package com.auto.core

enum class Stage(val label: String) { WAITING("Waiting for files"), IMPORTING("Importing"), ANALYSING("Analysing"), READY("Ready"), EXPORTING("Exporting"), COMPLETED("Completed"), FAILED("Failed") }
enum class Event { START_IMPORT, FILES_READY, ANALYSE, ANALYSED, FAIL, EXPORT, EXPORTED, EDIT, RESET }

/** The one rule book for what the project is doing. Events that make no sense in a stage are ignored (so a double tap cannot start two exports). */
object Workflow {
    fun next(s: Stage, e: Event): Stage = when (e) {
        Event.RESET -> if (s == Stage.ANALYSING || s == Stage.EXPORTING || s == Stage.IMPORTING) s else Stage.WAITING
        Event.START_IMPORT -> if (s == Stage.WAITING || s == Stage.FAILED || s == Stage.READY || s == Stage.COMPLETED) Stage.IMPORTING else s
        Event.FILES_READY -> if (s == Stage.IMPORTING) Stage.WAITING else s
        Event.ANALYSE -> if (s == Stage.WAITING || s == Stage.FAILED || s == Stage.READY || s == Stage.COMPLETED) Stage.ANALYSING else s
        Event.ANALYSED -> if (s == Stage.ANALYSING) Stage.READY else s
        Event.EXPORT -> if (s == Stage.READY || s == Stage.COMPLETED) Stage.EXPORTING else s
        Event.EXPORTED -> if (s == Stage.EXPORTING) Stage.COMPLETED else s
        Event.EDIT -> if (s == Stage.COMPLETED) Stage.READY else s
        Event.FAIL -> if (s == Stage.IMPORTING || s == Stage.ANALYSING || s == Stage.EXPORTING) Stage.FAILED else s
    }
    /** A failed EXPORT returns to READY (the project is fine, only the export failed); a failed import/analysis stays FAILED. */
    fun afterFail(wasExporting: Boolean) = if (wasExporting) Stage.READY else Stage.FAILED
    fun busy(s: Stage) = s == Stage.IMPORTING || s == Stage.ANALYSING || s == Stage.EXPORTING
    fun canExport(s: Stage) = s == Stage.READY || s == Stage.COMPLETED
}

enum class SceneState { OK, CHECK, MISSING }
class SceneCard(val clipId: Int, val num: Int, val startMs: Long, val endMs: Long, val image: String?, val text: String, val score: Int, val state: SceneState, val replacementColor: Int? = null) { val durMs get() = endMs - startMs }

/** What the Analysis screen shows: counts for the inputs, one card per scene, and the messages. */
class AnalysisReport(val inputs: Set<String>, val scenes: List<SceneCard>, val audioMs: Long, val videoMs: Long, val captionCount: Int, val textCount: Int, val issues: List<Issue>, val warnings: List<String>) {
    val sceneCount get() = scenes.size
    val withoutPicture get() = scenes.count { it.state == SceneState.MISSING }
    val needCheck get() = scenes.count { it.state == SceneState.CHECK }
    val ok get() = scenes.isNotEmpty() && withoutPicture == 0 && issues.none { it.kind == IssueKind.GAP || it.kind == IssueKind.OVERLAP }
    fun inputLine(kind: String) = if (kind in inputs) "$kind added" else "$kind missing"
    companion object {
        const val CHECK_BELOW = 75   // scenes scoring below this are marked CHECK (the existing Confidence scores: 100 clean, 55 uncertain match, 0 missing picture)
        fun from(t: Timeline, inputs: Set<String>, warnings: List<String>): AnalysisReport {
            val cards = t.clips.map { c ->
                SceneCard(
                    c.id, c.sceneNum, c.startMs, c.endMs, c.image, c.text, c.score,
                    when {
                        c.image == null && c.replacementColor == null && !c.heading -> SceneState.MISSING
                        c.score < CHECK_BELOW && c.replacementColor == null -> SceneState.CHECK
                        else -> SceneState.OK
                    },
                    c.replacementColor
                )
            }
            return AnalysisReport(inputs, cards, t.audioMs, t.endMs, t.captions.size, t.texts.size, t.validate(), warnings)
        }
    }
}
