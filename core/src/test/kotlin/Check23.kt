import com.auto.core.*

/**
 * Phase 2 tests (Section 11):
 * - Legacy font IDs preserved (inter, sans, condensed, notoserif, serif, mono, script, casual) and FontCatalog.faces.size == 8.
 * - Extended FontCatalog with weightFiles, variableAxes, categories, and ScriptTags (Latin, Devanagari, Telugu, Malayalam).
 * - Unavailable/Soon fonts accurately flagged without guessing filenames.
 * - ScriptDetector & automatic script fallback for Devanagari, Telugu, Malayalam, and mixed-script text.
 * - Indic shaping protection (zero letter-spacing when Indic scripts present; ASCII-only case folding on mixed Indic text).
 * - Grapheme-safe typewriter animation across Devanagari, Telugu, Malayalam conjunct aksharas and emoji.
 */
fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { println("PASS: $name"); pass++ }
        else { println("FAIL: $name"); fail++ }
    }

    // 1. Legacy IDs and faces list compatibility
    val legacyIds = listOf("inter", "sans", "condensed", "notoserif", "serif", "mono", "script", "casual")
    check("Legacy 8 IDs preserved in FontCatalog.faces", FontCatalog.faces.map { it.id } == legacyIds)
    check("All legacy IDs resolve and are available", legacyIds.all { FontCatalog.face(it).id == it && FontCatalog.face(it).available })

    // 2. Extended FontCatalog & weight/variable metadata
    val poppins = FontCatalog.face("poppins")
    check("Poppins has 3 static weight files (400, 600, 700) and supports Devanagari",
        poppins.weightFiles.keys == setOf(400, 600, 700) &&
        poppins.resourceForWeight(600, 26) == "poppins_semibold" &&
        poppins.supportsScript(ScriptTag.DEVANAGARI))

    val baloo = FontCatalog.face("baloo2")
    check("Baloo 2 is variable wght(400..800) and supports Devanagari",
        baloo.isVariable && baloo.supportsScript(ScriptTag.DEVANAGARI) && baloo.shippedWeights(26).size == 5)

    val tel = FontCatalog.face("telugu_bundled")
    val mal = FontCatalog.face("malayalam_bundled")
    check("Unshipped Telugu & Malayalam dedicated fonts are marked unavailable with Soon reason and null resource",
        !tel.available && tel.bundledRes == null && tel.unavailableReason.contains("Soon") &&
        !mal.available && mal.bundledRes == null && mal.unavailableReason.contains("Soon"))

    // 3. Script detection & fallback
    val latinText = "Dream Big 2026"
    val devaText = "नमस्ते भारत"
    val teluguText = "నమస్కారం తెలుగు"
    val malayalamText = "നമസ്കാരം മലയാളം"
    val mixedText = "AutoAndroid: नमस्ते • తెలుగు • മലയാളം"

    check("ScriptDetector identifies Latin, Devanagari, Telugu, Malayalam",
        ScriptDetector.detectScripts(latinText) == setOf(ScriptTag.LATIN) &&
        ScriptDetector.detectScripts(devaText) == setOf(ScriptTag.DEVANAGARI) &&
        ScriptDetector.detectScripts(teluguText) == setOf(ScriptTag.TELUGU) &&
        ScriptDetector.detectScripts(malayalamText) == setOf(ScriptTag.MALAYALAM) &&
        ScriptDetector.detectScripts(mixedText) == setOf(ScriptTag.LATIN, ScriptTag.DEVANAGARI, ScriptTag.TELUGU, ScriptTag.MALAYALAM))

    check("FontCatalog.resolveForText keeps Latin font for Latin text, falls back to Poppins for Devanagari, and system sans for Telugu/Malayalam",
        FontCatalog.resolveForText("anton", latinText).id == "anton" &&
        FontCatalog.resolveForText("anton", devaText).id == "poppins" &&
        FontCatalog.resolveForText("anton", teluguText).id == "sans" &&
        FontCatalog.resolveForText("anton", malayalamText).id == "sans")

    // 4. Indic shaping protection (spacing & capitalization)
    val spacedStyle = OverlayStyle(letterSpacingEm = 0.25f, caseMode = CaseMode.UPPER)
    check("effectiveLetterSpacingEm is 0f for Indic text and 0.25f for Latin text",
        spacedStyle.effectiveLetterSpacingEm(teluguText) == 0f &&
        spacedStyle.effectiveLetterSpacingEm(devaText) == 0f &&
        spacedStyle.effectiveLetterSpacingEm(latinText) == 0.25f)
    check("display() uppercases ASCII letters without corrupting Indic codepoints",
        spacedStyle.display("hi नमस्ते") == "HI नमस्ते")

    // 5. Grapheme-safe typewriter animation on Indic conjuncts (aksharas)
    // Devanagari "नमस्ते" = न (1) + म (2) + स्ते (स + ् + त + े = 1 conjunct syllable) -> 3 aksharas
    val devaEnds = AnimMath.graphemeEnds(devaText)
    check("Grapheme typewriter never ends on a dangling Virama (U+094D / U+0C4D / U+0D4D)",
        listOf(devaText, teluguText, malayalamText, mixedText).all { sample ->
            (1..20).all { step ->
                val vis = AnimMath.visibleText(sample, AnimFrame(visibleChars = step / 20f))
                vis.isEmpty() || vis.last().code !in setOf(0x094D, 0x0C4D, 0x0D4D)
            }
        } && devaEnds.isNotEmpty())

    if (fail > 0) { println("FAILED ($fail)"); kotlin.system.exitProcess(1) }
    println("ALL PASS ($pass)")
}
