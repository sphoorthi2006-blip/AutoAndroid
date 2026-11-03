package com.auto.core

/**
 * What the exporter can really draw. The UI asks this registry before it shows a control, so there is never a button for something the video will not contain.
 * VERIFIED = checked on a phone. WRITTEN = code exists but has not been run on a phone yet (the UI shows a "Beta" tag). UNAVAILABLE = not offered at all, with the reason shown.
 * RULE (Section 20): a feature moves to VERIFIED only after the user confirms a phone export.
 */
enum class CapStatus { VERIFIED, WRITTEN, UNAVAILABLE }
class Feature(val id: String, val label: String, val status: CapStatus, val reason: String = "")

object RenderCaps {
    private const val LIB = "Needs a newer video library than the one in this app (Media3 1.3.1)."
    private const val SOON = "Soon: not wired into both preview and Media3 1.3.1 export in this release."
    private const val SCOPE = "Out of scope: offline deterministic picture-video pipeline."

    /**
     * Ids whose drawing code really exists in app/Exporter.kt.
     * HONESTY GUARD (Check18): no feature may be offered (status != UNAVAILABLE) unless its id is in this set.
     */
    val exporterImplements = setOf(
        "pictures", "aspect", "captions", "captionStyle", "text", "textAnim",
        "fx", "transitions", "volume", "music", "click", "solidColor"
    )

    val features = listOf(
        Feature("pictures", "Pictures + narration", CapStatus.WRITTEN),
        Feature("aspect", "Aspect ratios 16:9, 9:16, 1:1, 4:5", CapStatus.WRITTEN),
        Feature("captions", "Captions burned into the video", CapStatus.WRITTEN),
        Feature("captionStyle", "Caption styles (font, colour, outline, shadow, box)", CapStatus.WRITTEN),
        Feature("text", "Text overlays", CapStatus.WRITTEN),
        Feature("textAnim", "Text animations", CapStatus.WRITTEN),
        Feature("fx", "Brightness / contrast / saturation / opacity", CapStatus.WRITTEN),
        Feature("transitions", "Fade, slide-in and zoom-in transitions", CapStatus.WRITTEN),
        Feature("volume", "Narration volume, fades, mute", CapStatus.WRITTEN),
        Feature("music", "Music bed", CapStatus.WRITTEN),
        Feature("click", "Click sound at every picture change", CapStatus.WRITTEN),
        Feature("solidColor", "Solid-colour scene replacement (Black, White, Custom ARGB)", CapStatus.WRITTEN),
        // Explicitly documented UNAVAILABLE / Soon features (Sections 1, 6, 9, 10)
        Feature("blur", "Blur", CapStatus.UNAVAILABLE, LIB),
        Feature("sharpen", "Sharpen", CapStatus.UNAVAILABLE, LIB),
        Feature("vignette", "Vignette", CapStatus.UNAVAILABLE, LIB),
        Feature("dissolve", "Dissolve (cross-fade between pictures)", CapStatus.UNAVAILABLE, "Pictures cannot overlap in this version of the export."),
        Feature("wipe", "Wipe", CapStatus.UNAVAILABLE, "Pictures cannot overlap in this version of the export."),
        Feature("videoClips", "Video clips on the timeline", CapStatus.UNAVAILABLE, "The timeline is built from pictures and audio."),
        Feature("audioSplit", "Split the narration", CapStatus.UNAVAILABLE, "The video must stay exactly as long as the narration."),
        Feature("musicOffset", "Start the music later", CapStatus.UNAVAILABLE, LIB),
        Feature("crop", "Crop picture", CapStatus.UNAVAILABLE, SOON),
        Feature("rotate", "Rotate picture", CapStatus.UNAVAILABLE, SOON),
        Feature("flip", "Flip picture", CapStatus.UNAVAILABLE, SOON),
        Feature("speed", "Clip playback speed", CapStatus.UNAVAILABLE, "Picture scene durations are controlled directly via Trim / Length."),
        Feature("shapes", "Vector shapes & lines", CapStatus.UNAVAILABLE, SOON),
        Feature("keyframes", "Keyframes", CapStatus.UNAVAILABLE, SCOPE),
        Feature("masks", "Masks", CapStatus.UNAVAILABLE, SCOPE),
        Feature("chromaKey", "Chroma key", CapStatus.UNAVAILABLE, SCOPE),
    )

    fun get(id: String) = features.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown feature $id")
    fun available(id: String) = get(id).status != CapStatus.UNAVAILABLE
    fun isBeta(id: String) = get(id).status == CapStatus.WRITTEN

    /** Ids of the features this export actually uses. */
    fun used(plan: ExportPlan.Plan, captionsOn: Boolean): List<String> = buildList {
        add("pictures")
        if (plan.usesSolidColor) add("solidColor")
        if (captionsOn && plan.captions.isNotEmpty()) { add("captions"); if (plan.captionStyle != StylePresets.captionDefault) add("captionStyle") }
        if (plan.texts.isNotEmpty()) { add("text"); if (plan.texts.any { it.style.animIn != AnimKind.NONE || it.style.animOut != AnimKind.NONE }) add("textAnim") }
        if (plan.usesFx) add("fx"); if (plan.usesTransitions) add("transitions")
        if (!plan.mix.narration.isIdentity) add("volume"); if (plan.mix.music != null) add("music")
        if (plan.mix.click.enabled && ClickTrack.startsMs(plan).isNotEmpty()) add("click")
    }

    /** Short messages for the export screen: untested features in use (so the person checks the result), and anything unavailable that somehow got into the project. */
    fun notes(plan: ExportPlan.Plan, captionsOn: Boolean): List<String> {
        val u = used(plan, captionsOn).map { get(it) }
        return u.filter { it.status == CapStatus.UNAVAILABLE }.map { "${it.label} cannot be exported: ${it.reason}" } +
            u.filter { it.status == CapStatus.WRITTEN && it.id != "pictures" }.map { "${it.label}: new, not yet checked on a phone. Watch the exported video." }
    }
}
