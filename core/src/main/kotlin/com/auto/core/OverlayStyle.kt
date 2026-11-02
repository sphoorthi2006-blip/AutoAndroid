package com.auto.core

import kotlin.math.max
import kotlin.math.min

enum class TextAlign { LEFT, CENTER, RIGHT }
enum class CaseMode { NONE, UPPER, LOWER }

/** Where a font comes from: a file bundled in the app (res/font/<res>) or an Android system family name. */
class FontFace(val id: String, val label: String, val bundledRes: String?, val systemFamily: String?, val hasItalic: Boolean, val sample: String = "Aa Bb 123")

/** Weights a person can pick. Static font files only have Regular and Bold; the in-between weights need Android 9+ (API 28) and are applied by Typeface.create(face, weight, italic). */
enum class FontWeightOpt(val label: String, val value: Int) { REGULAR("Regular", 400), MEDIUM("Medium", 500), SEMIBOLD("Semibold", 600), BOLD("Bold", 700), EXTRABOLD("Extra Bold", 800) }

object FontCatalog {
    /** Bundled files are copied from the desktop app's fonts/bundled folder (all SIL Open Font License). */
    val faces = listOf(
        FontFace("inter", "Inter", "inter_regular", null, true),
        FontFace("sans", "Sans", null, "sans-serif", true),
        FontFace("condensed", "Condensed", null, "sans-serif-condensed", true),
        FontFace("notoserif", "Noto Serif", "notoserif_regular", null, false),
        FontFace("serif", "Serif", null, "serif", true),
        FontFace("mono", "Mono", "nimbusmono_regular", null, false),
        FontFace("script", "Script", "z003_mediumitalic", null, false),
        FontFace("casual", "Casual", null, "casual", false),
    )
    fun face(id: String) = faces.firstOrNull { it.id == id } ?: faces[0]

    /** The weight that is really drawn on a phone with this Android API level (below 28 only 400 and 700 exist). */
    fun effectiveWeight(weight: Int, sdk: Int): Int = if (sdk >= 28) weight.coerceIn(100, 900) else if (weight >= 600) 700 else 400
    /** Weights the UI should offer on this phone, so no choice looks different from what is exported. */
    fun offeredWeights(sdk: Int): List<FontWeightOpt> = FontWeightOpt.values().filter { sdk >= 28 || it == FontWeightOpt.REGULAR || it == FontWeightOpt.BOLD }
}

/** "#RRGGBB", "#AARRGGBB" or "RGB" (with or without #) <-> ARGB int. */
object ColorText {
    fun parse(text: String): Int? {
        var t = text.trim().removePrefix("#"); if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 && t.length != 8) return null
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = t.toLong(16); return if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }
    fun hex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
    val PRESETS = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD60A.toInt(), 0xFFFF453A.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF9F0A.toInt(), 0xFFE9D5FF.toInt(), 0xFF9CA3AF.toInt())
}

/**
 * ONE look for any text drawn over the video (captions AND free text). Every size is a FRACTION of the frame height (or of the font size), never pixels,
 * so the preview, a 720p export and a 4K export look identical in every aspect ratio. Plain data: the app turns it into Canvas/Paint calls.
 */
data class OverlayStyle(
    val font: String = "inter", val weight: Int = 700, val italic: Boolean = false, val underline: Boolean = false, val strike: Boolean = false,
    val sizeFrac: Float = 0.055f, val letterSpacingEm: Float = 0f, val lineSpacing: Float = 1.15f, val align: TextAlign = TextAlign.CENTER, val caseMode: CaseMode = CaseMode.NONE,
    val color: Int = 0xFFFFFFFF.toInt(), val gradientEnd: Int? = null, val opacity: Float = 1f,
    val strokeColor: Int = 0xFF000000.toInt(), val strokeEm: Float = 0.12f,
    val shadowColor: Int = 0xFF000000.toInt(), val shadowOpacity: Float = 0f, val shadowBlurEm: Float = 0.08f, val shadowDxEm: Float = 0.04f, val shadowDyEm: Float = 0.06f,
    val bgColor: Int = 0xFF000000.toInt(), val bgOpacity: Float = 0f, val bgRadiusEm: Float = 0.25f, val paddingEm: Float = 0.3f,
    val x: Float = 0.5f, val y: Float = 0.88f, val widthFrac: Float = 0.88f, val rotationDeg: Float = 0f,
    val animIn: AnimKind = AnimKind.NONE, val animOut: AnimKind = AnimKind.NONE, val animInMs: Int = 400, val animOutMs: Int = 300,
) {
    /** Every number forced into the range the renderer can draw (a typed or loaded value can never break the export). */
    fun clamped() = copy(
        weight = weight.coerceIn(100, 900), sizeFrac = sizeFrac.coerceIn(0.015f, 0.2f), letterSpacingEm = letterSpacingEm.coerceIn(-0.1f, 0.5f), lineSpacing = lineSpacing.coerceIn(0.8f, 2.5f),
        opacity = opacity.coerceIn(0f, 1f), strokeEm = strokeEm.coerceIn(0f, 0.4f), shadowOpacity = shadowOpacity.coerceIn(0f, 1f), shadowBlurEm = shadowBlurEm.coerceIn(0f, 0.5f),
        shadowDxEm = shadowDxEm.coerceIn(-0.5f, 0.5f), shadowDyEm = shadowDyEm.coerceIn(-0.5f, 0.5f), bgOpacity = bgOpacity.coerceIn(0f, 1f), bgRadiusEm = bgRadiusEm.coerceIn(0f, 1f), paddingEm = paddingEm.coerceIn(0f, 1.5f),
        x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f), widthFrac = widthFrac.coerceIn(0.1f, 1f), rotationDeg = ((rotationDeg % 360f) + 540f) % 360f - 180f,
        animInMs = animInMs.coerceIn(0, 3000), animOutMs = animOutMs.coerceIn(0, 3000),
        font = if (FontCatalog.faces.any { it.id == font }) font else "inter", animIn = if (animIn.allowedIn) animIn else AnimKind.NONE, animOut = if (animOut.allowedOut) animOut else AnimKind.NONE)

    fun display(text: String) = when (caseMode) { CaseMode.UPPER -> text.uppercase(); CaseMode.LOWER -> text.lowercase(); CaseMode.NONE -> text }
    /** Alpha-applied colours the renderer needs. */
    fun fillArgb() = withAlpha(color, opacity)
    fun gradientEndArgb() = gradientEnd?.let { withAlpha(it, opacity) }
    fun shadowArgb() = withAlpha(shadowColor, shadowOpacity)
    fun bgArgb() = withAlpha(bgColor, bgOpacity)
    val hasShadow get() = shadowOpacity > 0f
    val hasBackground get() = bgOpacity > 0f
    val hasStroke get() = strokeEm > 0f

    // ---- JSON (MiniJson reads it back). Unknown or missing fields fall back to the defaults, so files from older versions still load. ----
    fun toJson(): String = buildString {
        fun f(k: String, v: Float) = "\"$k\":${if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()}"
        append("{"); append("\"font\":${jsonQuote(font)},\"weight\":$weight,\"italic\":$italic,\"underline\":$underline,\"strike\":$strike,")
        append(listOf(f("size", sizeFrac), f("spacing", letterSpacingEm), f("line", lineSpacing)).joinToString(",")); append(",\"align\":\"${align.name}\",\"case\":\"${caseMode.name}\",")
        append("\"color\":$color,\"gradEnd\":${gradientEnd ?: "null"},"); append(listOf(f("opacity", opacity), f("strokeEm", strokeEm)).joinToString(",")); append(",\"stroke\":$strokeColor,")
        append("\"shadow\":$shadowColor,"); append(listOf(f("shadowOp", shadowOpacity), f("shadowBlur", shadowBlurEm), f("shadowDx", shadowDxEm), f("shadowDy", shadowDyEm)).joinToString(",")); append(",")
        append("\"bg\":$bgColor,"); append(listOf(f("bgOp", bgOpacity), f("bgRadius", bgRadiusEm), f("pad", paddingEm), f("x", x), f("y", y), f("w", widthFrac), f("rot", rotationDeg)).joinToString(","))
        append(",\"animIn\":\"${animIn.name}\",\"animOut\":\"${animOut.name}\",\"animInMs\":$animInMs,\"animOutMs\":$animOutMs}")
    }

    companion object {
        private fun withAlpha(argb: Int, a: Float): Int { val base = (argb ushr 24) / 255f; return ((base * a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (argb and 0xFFFFFF) }
        fun fromMap(m: Map<*, *>): OverlayStyle {
            val d = OverlayStyle()
            fun fl(k: String, def: Float) = (m[k] as? Double)?.toFloat() ?: def
            fun it(k: String, def: Int) = (m[k] as? Double)?.toLong()?.toInt() ?: def
            fun <T : Enum<T>> en(k: String, all: Array<T>, def: T) = all.firstOrNull { e -> e.name == m[k] } ?: def
            return OverlayStyle(
                font = m["font"] as? String ?: d.font, weight = it("weight", d.weight), italic = m["italic"] as? Boolean ?: d.italic, underline = m["underline"] as? Boolean ?: d.underline, strike = m["strike"] as? Boolean ?: d.strike,
                sizeFrac = fl("size", d.sizeFrac), letterSpacingEm = fl("spacing", d.letterSpacingEm), lineSpacing = fl("line", d.lineSpacing), align = en("align", TextAlign.values(), d.align), caseMode = en("case", CaseMode.values(), d.caseMode),
                color = it("color", d.color), gradientEnd = (m["gradEnd"] as? Double)?.toLong()?.toInt(), opacity = fl("opacity", d.opacity), strokeColor = it("stroke", d.strokeColor), strokeEm = fl("strokeEm", d.strokeEm),
                shadowColor = it("shadow", d.shadowColor), shadowOpacity = fl("shadowOp", d.shadowOpacity), shadowBlurEm = fl("shadowBlur", d.shadowBlurEm), shadowDxEm = fl("shadowDx", d.shadowDxEm), shadowDyEm = fl("shadowDy", d.shadowDyEm),
                bgColor = it("bg", d.bgColor), bgOpacity = fl("bgOp", d.bgOpacity), bgRadiusEm = fl("bgRadius", d.bgRadiusEm), paddingEm = fl("pad", d.paddingEm),
                x = fl("x", d.x), y = fl("y", d.y), widthFrac = fl("w", d.widthFrac), rotationDeg = fl("rot", d.rotationDeg),
                animIn = en("animIn", AnimKind.values(), d.animIn), animOut = en("animOut", AnimKind.values(), d.animOut), animInMs = it("animInMs", d.animInMs), animOutMs = it("animOutMs", d.animOutMs)).clamped()
        }
        fun parse(text: String): OverlayStyle { val m = try { MiniJson(text).parse() as? Map<*, *> } catch (e: Exception) { null } ?: throw PipelineError("Style is damaged (not valid JSON)."); return fromMap(m) }
    }
}

class NamedStyle(val name: String, val style: OverlayStyle)

object StylePresets {
    private val W = 0xFFFFFFFF.toInt(); private val K = 0xFF000000.toInt()
    private val base = OverlayStyle()
    val text: List<NamedStyle> = listOf(
        NamedStyle("Minimal", base.copy(weight = 400, sizeFrac = 0.06f, strokeEm = 0f, y = 0.5f, shadowOpacity = 0.35f)),
        NamedStyle("Bold Title", base.copy(weight = 800, sizeFrac = 0.11f, caseMode = CaseMode.UPPER, strokeEm = 0.1f, y = 0.4f, animIn = AnimKind.POP)),
        NamedStyle("Subtitle", base.copy(weight = 600, sizeFrac = 0.05f, bgOpacity = 0.55f, strokeEm = 0f, paddingEm = 0.35f, y = 0.9f, animIn = AnimKind.FADE, animOut = AnimKind.FADE)),
        NamedStyle("Cinematic", base.copy(font = "notoserif", weight = 400, sizeFrac = 0.06f, letterSpacingEm = 0.25f, caseMode = CaseMode.UPPER, strokeEm = 0f, shadowOpacity = 0.6f, y = 0.5f, animIn = AnimKind.FADE, animOut = AnimKind.FADE, animInMs = 900, animOutMs = 700)),
        NamedStyle("Modern", base.copy(font = "inter", weight = 700, sizeFrac = 0.07f, color = 0xFFE9D5FF.toInt(), strokeEm = 0f, shadowOpacity = 0.5f, y = 0.5f, animIn = AnimKind.SLIDE)),
        NamedStyle("Social Media", base.copy(weight = 800, sizeFrac = 0.075f, color = 0xFFFFD60A.toInt(), strokeEm = 0.18f, y = 0.72f, animIn = AnimKind.BOUNCE)),
        NamedStyle("Highlight", base.copy(weight = 700, sizeFrac = 0.06f, color = K, strokeEm = 0f, bgColor = 0xFFFFD60A.toInt(), bgOpacity = 1f, bgRadiusEm = 0.2f, paddingEm = 0.3f, y = 0.5f, animIn = AnimKind.POP)),
        NamedStyle("Lower Third", base.copy(weight = 600, sizeFrac = 0.045f, align = TextAlign.LEFT, x = 0.08f, y = 0.82f, widthFrac = 0.6f, bgColor = 0xFF7C3AED.toInt(), bgOpacity = 0.9f, strokeEm = 0f, bgRadiusEm = 0.15f, paddingEm = 0.4f, animIn = AnimKind.SLIDE, animOut = AnimKind.SLIDE)),
    )
    val caption: List<NamedStyle> = listOf(
        NamedStyle("Classic", base),
        NamedStyle("Clean box", base.copy(weight = 600, sizeFrac = 0.05f, strokeEm = 0f, bgOpacity = 0.6f, paddingEm = 0.3f)),
        NamedStyle("Yellow", base.copy(weight = 800, color = 0xFFFFD60A.toInt(), strokeEm = 0.16f)),
        NamedStyle("Soft shadow", base.copy(weight = 600, strokeEm = 0f, shadowOpacity = 0.7f, shadowBlurEm = 0.15f)),
        NamedStyle("Top", base.copy(y = 0.1f, weight = 600, sizeFrac = 0.05f)),
        NamedStyle("Serif", base.copy(font = "notoserif", weight = 400, strokeEm = 0.1f, sizeFrac = 0.055f)),
    )
    val captionDefault: OverlayStyle get() = caption[0].style
    fun byName(name: String) = (text + caption).firstOrNull { it.name == name }?.style
}

/** Styles the person saved (JSON list). Names are unique (saving again replaces); the list is capped so it cannot grow without end. */
class StyleLibrary(val items: List<NamedStyle> = emptyList()) {
    companion object {
        const val MAX = 50
        fun parse(text: String): StyleLibrary {
            val l = try { MiniJson(text).parse() as? List<*> } catch (e: Exception) { null } ?: return StyleLibrary()
            return StyleLibrary(l.mapNotNull { e -> (e as? Map<*, *>)?.let { m -> val n = m["name"] as? String; val s = m["style"] as? Map<*, *>; if (n != null && s != null) NamedStyle(n, OverlayStyle.fromMap(s)) else null } })
        }
    }
    fun with(name: String, style: OverlayStyle): StyleLibrary { val n = name.trim().ifEmpty { "My style" }.take(40); return StyleLibrary((items.filter { it.name != n } + NamedStyle(n, style.clamped())).takeLast(MAX)) }
    fun without(name: String) = StyleLibrary(items.filter { it.name != name })
    fun toJson() = "[" + items.joinToString(",") { "{\"name\":${jsonQuote(it.name)},\"style\":${it.style.toJson()}}" } + "]"
}

/** Greedy line breaking: `measure` returns the drawn width of a string, so core needs no Android classes. Words wider than maxWidth stay on their own line. */
object TextWrap {
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float, maxLines: Int = Int.MAX_VALUE): List<String> {
        val out = ArrayList<String>()
        for (para in text.split("\n")) {
            var cur = ""
            for (w in para.split(Regex("\\s+")).filter { it.isNotEmpty() }) { val t = if (cur.isEmpty()) w else "$cur $w"; if (cur.isNotEmpty() && measure(t) > maxWidth) { out.add(cur); cur = w } else cur = t }
            if (cur.isNotEmpty() || para.isEmpty()) out.add(cur)
        }
        return if (out.size <= maxLines) out else out.take(maxLines)
    }
}
