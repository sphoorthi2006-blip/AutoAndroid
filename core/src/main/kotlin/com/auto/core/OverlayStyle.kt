package com.auto.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TextAlign { LEFT, CENTER, RIGHT }
enum class CaseMode { NONE, UPPER, LOWER }

/** Phase 2 (Section 11): Script tags for multilingual typography and script detection/fallback. */
enum class ScriptTag(val label: String, val sample: String) {
    LATIN("Latin", "Aa Bb 123"),
    DEVANAGARI("Devanagari", "अ आ क ख १२३"),
    TELUGU("Telugu", "అ ఆ క ఖ ౧౨౩"),
    MALAYALAM("Malayalam", "അ ആ ക ഖ ൧൨൩")
}

/** Font picker categories (Section 11). */
enum class FontCategory(val label: String) {
    SANS("Sans-Serif"),
    SERIF("Serif"),
    DISPLAY("Display & Impact"),
    HANDWRITING("Script & Handwriting"),
    MONO("Monospace"),
    INDIC("Indian Scripts")
}

/** Variable font axis descriptor (e.g. wght 100..900, wdth 75..100, opsz 14..32). */
data class FontAxis(val tag: String, val minVal: Float, val defaultVal: Float, val maxVal: Float) {
    fun clamp(v: Float): Float = v.coerceIn(minVal, maxVal)
}

/**
 * Where a font comes from: a file bundled in the app (res/font/<res>) or an Android system family name.
 * Extended additively in Phase 2 with weightFiles, variableAxes, category, scripts, and availability state.
 */
class FontFace(
    val id: String,
    val label: String,
    val bundledRes: String?,
    val systemFamily: String?,
    val hasItalic: Boolean,
    val sample: String = "Aa Bb 123",
    val weightFiles: Map<Int, String> = if (bundledRes != null) mapOf(400 to bundledRes) else emptyMap(),
    val variableAxes: List<FontAxis> = emptyList(),
    val category: FontCategory = FontCategory.SANS,
    val scripts: Set<ScriptTag> = setOf(ScriptTag.LATIN),
    val available: Boolean = true,
    val unavailableReason: String = ""
) {
    val isVariable: Boolean get() = variableAxes.any { it.tag == "wght" }

    /** Real weights this font can render on the given Android SDK level without fake distortion. */
    fun shippedWeights(sdk: Int): List<Int> {
        if (!available) return emptyList()
        if (isVariable && sdk >= 26) return listOf(400, 500, 600, 700, 800)
        if (weightFiles.size > 1) return weightFiles.keys.sorted()
        return if (sdk >= 28) listOf(400, 500, 600, 700, 800) else listOf(400, 700)
    }

    /** Resolves the best bundled resource name for a requested weight (400..900). */
    fun resourceForWeight(weight: Int, sdk: Int): String? {
        if (!available) return null
        if (isVariable && sdk >= 26 && bundledRes != null) return bundledRes
        if (weightFiles.isEmpty()) return bundledRes
        return weightFiles[weight] ?: weightFiles.entries.minByOrNull { abs(it.key - weight) }?.value ?: bundledRes
    }

    fun supportsScript(script: ScriptTag): Boolean = script in scripts
}

/** Weights a person can pick. Static font files expose only real shipped weights or API 28+ synthesized weights; variable fonts set wght axis on API 26+. */
enum class FontWeightOpt(val label: String, val value: Int) {
    REGULAR("Regular", 400),
    MEDIUM("Medium", 500),
    SEMIBOLD("Semibold", 600),
    BOLD("Bold", 700),
    EXTRABOLD("Extra Bold", 800)
}

/** Script detection and shaping safety for Latin, Devanagari, Telugu, and Malayalam. */
object ScriptDetector {
    fun scriptOfCodePoint(cp: Int): ScriptTag? = when (cp) {
        in 0x0900..0x097F, in 0xA8E0..0xA8FF, in 0x1CD0..0x1CFF -> ScriptTag.DEVANAGARI
        in 0x0C00..0x0C7F -> ScriptTag.TELUGU
        in 0x0D00..0x0D7F -> ScriptTag.MALAYALAM
        in 0x0041..0x005A, in 0x0061..0x007A, in 0x00C0..0x024F, in 0x1E00..0x1EFF -> ScriptTag.LATIN
        else -> null
    }

    fun detectScripts(text: String): Set<ScriptTag> {
        val found = LinkedHashSet<ScriptTag>()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            scriptOfCodePoint(cp)?.let { found.add(it) }
            i += Character.charCount(cp)
        }
        if (found.isEmpty()) found.add(ScriptTag.LATIN)
        return found
    }

    fun hasIndic(text: String): Boolean {
        val s = detectScripts(text)
        return ScriptTag.DEVANAGARI in s || ScriptTag.TELUGU in s || ScriptTag.MALAYALAM in s
    }
}

object FontCatalog {
    /**
     * The 8 legacy faces preserved at index 0..7 in `faces` so existing projects and Check15 ("FontCatalog.faces.size == 8")
     * work identically without breaking any legacy ID.
     */
    val faces: List<FontFace> = listOf(
        FontFace(
            id = "inter",
            label = "Inter",
            bundledRes = "inter_regular",
            systemFamily = null,
            hasItalic = true,
            weightFiles = mapOf(400 to "inter_regular", 500 to "inter_opsz_wght", 600 to "inter_opsz_wght", 700 to "inter_opsz_wght", 800 to "inter_opsz_wght"),
            variableAxes = listOf(FontAxis("opsz", 14f, 14f, 32f), FontAxis("wght", 100f, 400f, 900f)),
            category = FontCategory.SANS,
            scripts = setOf(ScriptTag.LATIN)
        ),
        FontFace("sans", "Sans", null, "sans-serif", true, category = FontCategory.SANS, scripts = setOf(ScriptTag.LATIN, ScriptTag.DEVANAGARI, ScriptTag.TELUGU, ScriptTag.MALAYALAM)),
        FontFace("condensed", "Condensed", null, "sans-serif-condensed", true, category = FontCategory.SANS, scripts = setOf(ScriptTag.LATIN)),
        FontFace("notoserif", "Noto Serif", "notoserif_regular", null, false, category = FontCategory.SERIF, scripts = setOf(ScriptTag.LATIN)),
        FontFace("serif", "Serif", null, "serif", true, category = FontCategory.SERIF, scripts = setOf(ScriptTag.LATIN)),
        FontFace("mono", "Mono", "nimbusmono_regular", null, false, category = FontCategory.MONO, scripts = setOf(ScriptTag.LATIN)),
        FontFace("script", "Script", "z003_mediumitalic", null, false, category = FontCategory.HANDWRITING, scripts = setOf(ScriptTag.LATIN)),
        FontFace("casual", "Casual", null, "casual", false, category = FontCategory.HANDWRITING, scripts = setOf(ScriptTag.LATIN)),
    )

    /** Additional bundled families from FONTS.zip (58 TTFs) + Indian script catalog entries. */
    val extendedFaces: List<FontFace> = listOf(
        // Devanagari + Latin bundled families in FONTS.zip
        FontFace(
            id = "poppins",
            label = "Poppins",
            bundledRes = "poppins_regular",
            systemFamily = null,
            hasItalic = false,
            sample = "Aa अ आ 123",
            weightFiles = mapOf(400 to "poppins_regular", 600 to "poppins_semibold", 700 to "poppins_bold"),
            category = FontCategory.INDIC,
            scripts = setOf(ScriptTag.LATIN, ScriptTag.DEVANAGARI)
        ),
        FontFace(
            id = "baloo2",
            label = "Baloo 2 (Devanagari)",
            bundledRes = "baloo2_wght",
            systemFamily = null,
            hasItalic = false,
            sample = "नमस्ते Aa 123",
            weightFiles = mapOf(400 to "baloo2_wght", 500 to "baloo2_wght", 600 to "baloo2_wght", 700 to "baloo2_wght", 800 to "baloo2_wght"),
            variableAxes = listOf(FontAxis("wght", 400f, 400f, 800f)),
            category = FontCategory.INDIC,
            scripts = setOf(ScriptTag.LATIN, ScriptTag.DEVANAGARI)
        ),
        // Telugu & Malayalam entries: explicitly marked unavailable/Soon because no Telugu/Malayalam TTF was in FONTS.zip (never guess filenames!)
        FontFace(
            id = "telugu_bundled",
            label = "Telugu Bundled Font",
            bundledRes = null,
            systemFamily = "sans-serif",
            hasItalic = false,
            sample = "తెలుగు Aa 123",
            category = FontCategory.INDIC,
            scripts = setOf(ScriptTag.TELUGU, ScriptTag.LATIN),
            available = false,
            unavailableReason = "Soon: Dedicated Telugu font file was not present in FONTS.zip; system Telugu shaping fallback is used."
        ),
        FontFace(
            id = "malayalam_bundled",
            label = "Malayalam Bundled Font",
            bundledRes = null,
            systemFamily = "sans-serif",
            hasItalic = false,
            sample = "മലയാളം Aa 123",
            category = FontCategory.INDIC,
            scripts = setOf(ScriptTag.MALAYALAM, ScriptTag.LATIN),
            available = false,
            unavailableReason = "Soon: Dedicated Malayalam font file was not present in FONTS.zip; system Malayalam shaping fallback is used."
        ),
        // Variable Sans-Serif families from FONTS.zip
        FontFace("roboto", "Roboto", "roboto_wdth_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 100f, 400f, 900f), FontAxis("wdth", 75f, 100f, 100f)), category = FontCategory.SANS),
        FontFace("opensans", "Open Sans", "opensans_wdth_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 300f, 400f, 800f), FontAxis("wdth", 75f, 100f, 100f)), category = FontCategory.SANS),
        FontFace("oswald", "Oswald", "oswald_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 200f, 400f, 700f)), category = FontCategory.SANS),
        FontFace("comfortaa", "Comfortaa", "comfortaa_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 300f, 400f, 700f)), category = FontCategory.SANS),
        FontFace("orbitron", "Orbitron", "orbitron_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 400f, 400f, 900f)), category = FontCategory.DISPLAY),
        FontFace("leaguegothic", "League Gothic", "leaguegothic_wdth", null, false,
            variableAxes = listOf(FontAxis("wdth", 75f, 100f, 100f)), category = FontCategory.DISPLAY),
        // Static multi-weight Sans-Serif families from FONTS.zip
        FontFace("lato", "Lato", "lato_regular", null, false,
            weightFiles = mapOf(400 to "lato_regular", 700 to "lato_bold", 800 to "lato_black"), category = FontCategory.SANS),
        FontFace("bevietnampro", "Be Vietnam Pro", "bevietnampro_regular", null, false,
            weightFiles = mapOf(400 to "bevietnampro_regular", 700 to "bevietnampro_bold"), category = FontCategory.SANS),
        FontFace("kanit", "Kanit", "kanit_regular", null, false,
            weightFiles = mapOf(400 to "kanit_regular", 700 to "kanit_bold"), category = FontCategory.SANS),
        FontFace("ptsans", "PT Sans", "pt_sans_web_regular", null, false,
            weightFiles = mapOf(400 to "pt_sans_web_regular", 700 to "pt_sans_web_bold"), category = FontCategory.SANS),
        FontFace("fjallaone", "Fjalla One", "fjallaone_regular", null, false, category = FontCategory.SANS),
        // Serif families from FONTS.zip
        FontFace("playfairdisplay", "Playfair Display", "playfairdisplay_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 400f, 400f, 900f)), category = FontCategory.SERIF),
        FontFace("cinzel", "Cinzel", "cinzel_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 400f, 400f, 900f)), category = FontCategory.SERIF),
        FontFace("ptserif", "PT Serif", "pt_serif_web_regular", null, false,
            weightFiles = mapOf(400 to "pt_serif_web_regular", 700 to "pt_serif_web_bold"), category = FontCategory.SERIF),
        FontFace("alice", "Alice", "alice_regular", null, false, category = FontCategory.SERIF),
        FontFace("abrilfatface", "Abril Fatface", "abrilfatface_regular", null, false, category = FontCategory.SERIF),
        FontFace("alfaslabone", "Alfa Slab One", "alfaslabone_regular", null, false, category = FontCategory.SERIF),
        // Display & Headline families from FONTS.zip
        FontFace("anton", "Anton", "anton_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("bebasneue", "Bebas Neue", "bebasneue_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("archivoblack", "Archivo Black", "archivoblack_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("bangers", "Bangers", "bangers_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("blackopsone", "Black Ops One", "blackopsone_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("bowlbyone", "Bowlby One", "bowlbyone_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("bungee", "Bungee", "bungee_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("bungeeshade", "Bungee Shade", "bungeeshade_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("changaone", "Changa One", "changaone_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("creepster", "Creepster", "creepster_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("fugazone", "Fugaz One", "fugazone_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("monoton", "Monoton", "monoton_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("passionone", "Passion One", "passionone_regular", null, false,
            weightFiles = mapOf(400 to "passionone_regular", 700 to "passionone_bold"), category = FontCategory.DISPLAY),
        FontFace("rammettoone", "Rammetto One", "rammettoone_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("righteous", "Righteous", "righteous_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("russoone", "Russo One", "russoone_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("staatliches", "Staatliches", "staatliches_regular", null, false, category = FontCategory.DISPLAY),
        FontFace("titanone", "Titan One", "titanone_regular", null, false, category = FontCategory.DISPLAY),
        // Script & Handwriting families from FONTS.zip
        FontFace("dancingscript", "Dancing Script", "dancingscript_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 400f, 400f, 700f)), category = FontCategory.HANDWRITING),
        FontFace("caveat", "Caveat", "caveat_wght", null, false,
            variableAxes = listOf(FontAxis("wght", 400f, 400f, 700f)), category = FontCategory.HANDWRITING),
        FontFace("amaticsc", "Amatic SC", "amaticsc_regular", null, false,
            weightFiles = mapOf(400 to "amaticsc_regular", 700 to "amaticsc_bold"), category = FontCategory.HANDWRITING),
        FontFace("courgette", "Courgette", "courgette_regular", null, false, category = FontCategory.HANDWRITING),
        FontFace("greatvibes", "Great Vibes", "greatvibes_regular", null, false, category = FontCategory.HANDWRITING),
        FontFace("indieflower", "Indie Flower", "indieflower_regular", null, false, category = FontCategory.HANDWRITING),
        FontFace("kaushanscript", "Kaushan Script", "kaushanscript_regular", null, false, category = FontCategory.HANDWRITING),
        FontFace("lobster", "Lobster", "lobster_regular", null, false, category = FontCategory.HANDWRITING),
        FontFace("pacifico", "Pacifico", "pacifico_regular", null, false, category = FontCategory.HANDWRITING),
        FontFace("patrickhand", "Patrick Hand", "patrickhand_regular", null, false, category = FontCategory.HANDWRITING),
        FontFace("sacramento", "Sacramento", "sacramento_regular", null, false, category = FontCategory.HANDWRITING),
    )

    /** Complete catalog combining the 8 legacy faces and all 47 extended families. */
    val allFaces: List<FontFace> = faces + extendedFaces

    fun face(id: String): FontFace = allFaces.firstOrNull { it.id == id } ?: faces[0]

    fun byCategory(cat: FontCategory): List<FontFace> = allFaces.filter { it.category == cat }

    fun byScript(script: ScriptTag): List<FontFace> = allFaces.filter { script in it.scripts }

    /** Selects the best fallback font when text contains scripts not supported by `requested`. */
    fun resolveForText(requestedId: String, text: String): FontFace {
        val req = face(requestedId)
        val scripts = ScriptDetector.detectScripts(text)
        if (req.available && scripts.all { req.supportsScript(it) }) return req
        if (ScriptTag.DEVANAGARI in scripts && (ScriptTag.TELUGU !in scripts && ScriptTag.MALAYALAM !in scripts)) {
            return face("poppins")
        }
        return face("sans") // Android system sans-serif shapes Devanagari, Telugu, Malayalam via HarfBuzz
    }

    /** The weight that is really drawn on a phone with this Android API level (below 28 only 400 and 700 exist for static single-file fonts). */
    fun effectiveWeight(weight: Int, sdk: Int): Int = if (sdk >= 28) weight.coerceIn(100, 900) else if (weight >= 600) 700 else 400

    /** Weights the UI should offer on this phone for the legacy catalog. */
    fun offeredWeights(sdk: Int): List<FontWeightOpt> =
        FontWeightOpt.values().filter { sdk >= 28 || it == FontWeightOpt.REGULAR || it == FontWeightOpt.BOLD }

    /** Weights offered for a specific FontFace (exposes only real shipped weights for static fonts on API < 28, or variable weights on API 26+). */
    fun offeredWeightsForFace(face: FontFace, sdk: Int): List<FontWeightOpt> {
        val shipped = face.shippedWeights(sdk).toSet()
        return FontWeightOpt.values().filter { it.value in shipped }
    }
}

/** "#RRGGBB", "#AARRGGBB" or "RGB" (with or without #) <-> ARGB int. No orange anywhere in PRESETS (Section 4). */
object ColorText {
    fun parse(text: String): Int? {
        var t = text.trim().removePrefix("#"); if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 && t.length != 8) return null
        if (!t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = t.toLong(16); return if (t.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }
    fun hex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
    val PRESETS = listOf(
        0xFFFFFFFF.toInt(), // Soft White
        0xFF000000.toInt(), // Pure Black
        0xFF8B5CF6.toInt(), // Electric Violet (Primary)
        0xFFC4B5FD.toInt(), // Lighter Violet
        0xFF38BDF8.toInt(), // Cyan Blue
        0xFF34D399.toInt(), // Emerald
        0xFFFBBF24.toInt(), // Amber
        0xFFF87171.toInt(), // Red
        0xFFE9D5FF.toInt(), // Soft Lavender
        0xFF94A3B8.toInt()  // Cool Gray
    )
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
        font = if (FontCatalog.allFaces.any { it.id == font && it.available }) font else "inter",
        animIn = if (animIn.allowedIn) animIn else AnimKind.NONE, animOut = if (animOut.allowedOut) animOut else AnimKind.NONE
    )

    /** Section 11: Indic spacing/capitalization must not break shaping. Case transforms apply only when no Indic script is present, or affect only ASCII Latin characters. */
    fun display(text: String): String {
        if (ScriptDetector.hasIndic(text)) {
            return when (caseMode) {
                CaseMode.UPPER -> buildString(text.length) { for (ch in text) append(if (ch in 'a'..'z') ch.uppercaseChar() else ch) }
                CaseMode.LOWER -> buildString(text.length) { for (ch in text) append(if (ch in 'A'..'Z') ch.lowercaseChar() else ch) }
                CaseMode.NONE -> text
            }
        }
        return when (caseMode) { CaseMode.UPPER -> text.uppercase(); CaseMode.LOWER -> text.lowercase(); CaseMode.NONE -> text }
    }

    /** Section 11: Positive/negative letter-spacing breaks HarfBuzz ligatures in Devanagari, Telugu, and Malayalam; force 0f when Indic script is detected. */
    fun effectiveLetterSpacingEm(text: String): Float = if (ScriptDetector.hasIndic(text)) 0f else letterSpacingEm

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
