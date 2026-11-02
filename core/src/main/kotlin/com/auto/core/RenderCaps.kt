package com.auto.core

/**
 * What the exporter can really draw. The UI asks this registry before it shows a control, so there is never a button for something the video will not contain.
 * VERIFIED = checked on a phone. WRITTEN = code exists but has not been run on a phone yet (the UI shows a "Beta" tag). UNAVAILABLE = not offered at all, with the reason shown.
 * RULE: a feature moves to VERIFIED only after someone has exported a video on a device and looked at it.
 */
enum class CapStatus { VERIFIED, WRITTEN, UNAVAILABLE }
class Feature(val id: String, val label: String, val status: CapStatus, val reason: String = "")

object RenderCaps {
    private const val LIB = "Needs a newer video library than the one in this app (Media3 1.3.1)."
    private const val SOON = "Planned: the export code for this is not written yet, so it is not offered."
    /** Ids whose drawing code really exists in app/Exporter.kt. HONESTY GUARD (Check18): no feature may be offered (status != UNAVAILABLE) unless its id is in this set. Add an id here in the same commit that writes its exporter code. */
    val exporterImplements = setOf("pictures", "aspect", "captions", "captionStyle", "text", "textAnim")
    val features = listOf(
        Feature("pictures", "Pictures + narration", CapStatus.WRITTEN),   // the base export was written before this redesign and has never run on a device either
        Feature("aspect", "Aspect ratios 16:9, 9:16, 1:1, 4:5", CapStatus.WRITTEN),
        Feature("captions", "Captions burned into the video", CapStatus.WRITTEN),
        Feature("captionStyle", "Caption styles (font, colour, outline, shadow, box)", CapStatus.WRITTEN),
        Feature("text", "Text overlays", CapStatus.WRITTEN),
        Feature("textAnim", "Text animations", CapStatus.WRITTEN),
        Feature("fx", "Brightness / contrast / saturation / opacity", CapStatus.UNAVAILABLE, SOON),
        Feature("transitions", "Fade, slide-in and zoom-in transitions", CapStatus.UNAVAILABLE, SOON),
        Feature("volume", "Narration volume, fades, mute", CapStatus.UNAVAILABLE, SOON),
        Feature("music", "Music bed", CapStatus.UNAVAILABLE, SOON),
        Feature("blur", "Blur", CapStatus.UNAVAILABLE, LIB), Feature("sharpen", "Sharpen", CapStatus.UNAVAILABLE, LIB), Feature("vignette", "Vignette", CapStatus.UNAVAILABLE, LIB),
        Feature("dissolve", "Dissolve (cross-fade between pictures)", CapStatus.UNAVAILABLE, "Pictures cannot overlap in this version of the export."),
        Feature("wipe", "Wipe", CapStatus.UNAVAILABLE, "Pictures cannot overlap in this version of the export."),
        Feature("videoClips", "Video clips on the timeline", CapStatus.UNAVAILABLE, "The timeline is built from pictures and audio."),
        Feature("audioSplit", "Split the narration", CapStatus.UNAVAILABLE, "The video must stay exactly as long as the narration."),
        Feature("musicOffset", "Start the music later", CapStatus.UNAVAILABLE, LIB),
    )
    fun get(id: String) = features.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown feature $id")
    fun available(id: String) = get(id).status != CapStatus.UNAVAILABLE
    fun isBeta(id: String) = get(id).status == CapStatus.WRITTEN

    /** Ids of the features this export actually uses. */
    fun used(plan: ExportPlan.Plan, captionsOn: Boolean): List<String> = buildList {
        add("pictures")
        if (captionsOn && plan.captions.isNotEmpty()) { add("captions"); if (plan.captionStyle != StylePresets.captionDefault) add("captionStyle") }
        if (plan.texts.isNotEmpty()) { add("text"); if (plan.texts.any { it.style.animIn != AnimKind.NONE || it.style.animOut != AnimKind.NONE }) add("textAnim") }
        if (plan.usesFx) add("fx"); if (plan.usesTransitions) add("transitions")
        if (!plan.mix.narration.isIdentity) add("volume"); if (plan.mix.music != null) add("music")
    }
    /** Short messages for the export screen: untested features in use (so the person checks the result), and anything unavailable that somehow got into the project. */
    fun notes(plan: ExportPlan.Plan, captionsOn: Boolean): List<String> {
        val u = used(plan, captionsOn).map { get(it) }
        return u.filter { it.status == CapStatus.UNAVAILABLE }.map { "${it.label} cannot be exported: ${it.reason}" } + u.filter { it.status == CapStatus.WRITTEN && it.id != "pictures" }.map { "${it.label}: new, not yet checked on a phone. Watch the exported video." }
    }
}
