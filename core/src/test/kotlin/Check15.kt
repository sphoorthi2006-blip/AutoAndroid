import com.auto.core.*
var fails15 = 0
fun ok15(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails15++ }
fun near(a: Float, b: Float, e: Float = 1e-3f) = Math.abs(a - b) <= e

/** Step 12: style model, fonts, colours, presets. Step 13: animation maths, guides, wrapping. */
fun main() {
    // ---- colours
    ok15("HEX #FF8800", ColorText.parse("#FF8800") == 0xFFFF8800.toInt())
    ok15("HEX short f80", ColorText.parse("f80") == 0xFFFF8800.toInt())
    ok15("HEX with alpha", ColorText.parse("#80FF8800") == 0x80FF8800.toInt())
    ok15("HEX bad input refused", ColorText.parse("#GG0000") == null && ColorText.parse("12345") == null && ColorText.parse("") == null)
    ok15("hex() round trip", ColorText.hex(ColorText.parse("#12ab9F")!!) == "#12AB9F")
    // ---- fonts
    ok15("8 font families, unique ids", FontCatalog.faces.size == 8 && FontCatalog.faces.map { it.id }.toSet().size == 8)
    ok15("unknown font falls back to Inter", FontCatalog.face("nope").id == "inter")
    ok15("weight on API 26: 500->400, 600->700, 800->700", FontCatalog.effectiveWeight(500, 26) == 400 && FontCatalog.effectiveWeight(600, 26) == 700 && FontCatalog.effectiveWeight(800, 26) == 700)
    ok15("weight on API 30: exact", FontCatalog.effectiveWeight(800, 30) == 800)
    ok15("API 26 offers only Regular+Bold, API 30 all five", FontCatalog.offeredWeights(26).size == 2 && FontCatalog.offeredWeights(30).size == 5)
    // ---- style
    val d = OverlayStyle()
    ok15("JSON round trip (default)", OverlayStyle.parse(d.toJson()) == d)
    val rich = d.copy(font = "serif", weight = 600, italic = true, underline = true, strike = true, sizeFrac = 0.08f, letterSpacingEm = 0.1f, lineSpacing = 1.4f, align = TextAlign.RIGHT, caseMode = CaseMode.UPPER, color = 0xFF112233.toInt(), gradientEnd = 0xFFAA00FF.toInt(), opacity = 0.8f, strokeEm = 0.2f, shadowOpacity = 0.5f, bgOpacity = 0.4f, bgColor = 0xFF123456.toInt(), x = 0.2f, y = 0.3f, widthFrac = 0.5f, rotationDeg = 15f, animIn = AnimKind.BOUNCE, animOut = AnimKind.ZOOM)
    ok15("JSON round trip (every field)", OverlayStyle.parse(rich.toJson()) == rich.clamped())
    ok15("missing fields use defaults", OverlayStyle.parse("{\"font\":\"mono\"}") == d.copy(font = "mono"))
    ok15("damaged JSON refused", try { OverlayStyle.parse("{oops"); false } catch (e: PipelineError) { true })
    val wild = d.copy(sizeFrac = 9f, opacity = -3f, strokeEm = 5f, x = 4f, y = -1f, widthFrac = 0f, rotationDeg = 725f, font = "zzz", animIn = AnimKind.NONE, animOut = AnimKind.TYPEWRITER, animInMs = 99999).clamped()
    ok15("clamped(): size/opacity/stroke/x/y/width in range", wild.sizeFrac == 0.2f && wild.opacity == 0f && wild.strokeEm == 0.4f && wild.x == 1f && wild.y == 0f && wild.widthFrac == 0.1f)
    ok15("clamped(): rotation wraps to -180..180 (725 -> 5)", near(wild.rotationDeg, 5f))
    ok15("clamped(): unknown font -> inter; typewriter OUT refused; time capped", wild.font == "inter" && wild.animOut == AnimKind.NONE && wild.animInMs == 3000)
    ok15("display(): upper / lower / none", d.copy(caseMode = CaseMode.UPPER).display("Ab") == "AB" && d.copy(caseMode = CaseMode.LOWER).display("Ab") == "ab" && d.display("Ab") == "Ab")
    ok15("alpha mixing: opacity .5 -> alpha 128", (d.copy(opacity = 0.5f).fillArgb() ushr 24) == 128 && (d.fillArgb() ushr 24) == 255)
    ok15("shadow/background are off at opacity 0", !d.hasShadow && !d.hasBackground && d.hasStroke && !d.copy(strokeEm = 0f).hasStroke)
    // ---- presets
    ok15("8 text presets with the requested names", StylePresets.text.map { it.name } == listOf("Minimal", "Bold Title", "Subtitle", "Cinematic", "Modern", "Social Media", "Highlight", "Lower Third"))
    ok15("6 caption presets", StylePresets.caption.size == 6)
    ok15("every preset is already valid (clamped() changes nothing)", (StylePresets.text + StylePresets.caption).all { it.style == it.style.clamped() })
    ok15("every preset survives JSON", (StylePresets.text + StylePresets.caption).all { OverlayStyle.parse(it.style.toJson()) == it.style })
    ok15("presets differ from each other", StylePresets.text.map { it.style }.toSet().size == 8)
    ok15("Lower Third is left aligned with a background", StylePresets.byName("Lower Third")!!.let { it.align == TextAlign.LEFT && it.hasBackground })
    // ---- saved styles
    var lib = StyleLibrary().with("Mine", d.copy(color = 0xFFFF0000.toInt())).with("Other", d).with("Mine", d.copy(color = 0xFF00FF00.toInt()))
    ok15("saving same name replaces, 2 left", lib.items.size == 2 && lib.items.last().style.color == 0xFF00FF00.toInt())
    ok15("library JSON round trip", StyleLibrary.parse(lib.toJson()).items.map { it.name to it.style } == lib.items.map { it.name to it.style })
    ok15("delete by name", lib.without("Mine").items.map { it.name } == listOf("Other"))
    ok15("library capped at 50", (1..60).fold(StyleLibrary()) { l, i -> l.with("s$i", d) }.items.size == StyleLibrary.MAX)
    ok15("garbage library text -> empty", StyleLibrary.parse("nonsense").items.isEmpty())
    // ---- animation
    val fade = d.copy(animIn = AnimKind.FADE, animOut = AnimKind.FADE, animInMs = 500, animOutMs = 500)
    ok15("fade: start alpha 0, middle 1, end near 0", AnimMath.frame(fade, 0, 4000).alpha == 0f && AnimMath.frame(fade, 2000, 4000) == AnimFrame.REST && AnimMath.frame(fade, 3999, 4000).alpha < 0.01f)
    ok15("fade: halfway through fade-in = 0.5", near(AnimMath.frame(fade, 250, 4000).alpha, 0.5f))
    ok15("outside 0..dur the text is hidden", AnimMath.frame(fade, -1, 4000).alpha == 0f && AnimMath.frame(fade, 4000, 4000).alpha == 0f)
    ok15("no animation = always at rest", AnimMath.frame(d, 0, 1000) == AnimFrame.REST && AnimMath.frame(d, 999, 1000) == AnimFrame.REST)
    val pop = d.copy(animIn = AnimKind.POP, animInMs = 400)
    ok15("pop: starts tiny, overshoots above 1, ends at exactly 1", AnimMath.frame(pop, 0, 3000).scale < 0.1f && (0..400 step 10).any { AnimMath.frame(pop, it.toLong(), 3000).scale > 1.02f } && near(AnimMath.frame(pop, 400, 3000).scale, 1f))
    val tw = d.copy(animIn = AnimKind.TYPEWRITER, animInMs = 1000)
    ok15("typewriter: 0 letters -> half -> all", AnimMath.visibleText("abcdefgh", AnimMath.frame(tw, 0, 5000)) == "" && AnimMath.visibleText("abcdefgh", AnimMath.frame(tw, 500, 5000)) == "abcd" && AnimMath.visibleText("abcdefgh", AnimMath.frame(tw, 1000, 5000)) == "abcdefgh")
    ok15("typewriter never splits an emoji", AnimMath.visibleText("a😀b", AnimFrame(visibleChars = 0.5f)).let { it == "a" || it == "a😀" })
    val sl = d.copy(animIn = AnimKind.SLIDE, animInMs = 400)
    ok15("slide-in: starts below its place, ends at 0", AnimMath.frame(sl, 0, 3000).dyFrac > 0.05f && near(AnimMath.frame(sl, 400, 3000).dyFrac, 0f))
    val bo = d.copy(animIn = AnimKind.BOUNCE, animInMs = 800)
    ok15("bounce: starts above, ends at 0", AnimMath.frame(bo, 0, 3000).dyFrac < -0.1f && near(AnimMath.frame(bo, 800, 3000).dyFrac, 0f))
    val zo = d.copy(animIn = AnimKind.ZOOM, animOut = AnimKind.ZOOM, animInMs = 300, animOutMs = 300)
    ok15("zoom: in grows to 1, out grows past 1", AnimMath.frame(zo, 0, 3000).scale < 0.7f && AnimMath.frame(zo, 2999, 3000).scale > 1.3f)
    val tight = d.copy(animIn = AnimKind.FADE, animOut = AnimKind.FADE, animInMs = 1000, animOutMs = 1000)
    ok15("short text (500 ms, 1000+1000 requested): in/out shrink to 250+250, so t=100 is fading in, t=250 is full, t=400 fading out", AnimMath.frame(tight, 100, 500).alpha in 0.3f..0.5f && AnimMath.frame(tight, 250, 500).alpha == 1f && AnimMath.frame(tight, 400, 500).alpha in 0.3f..0.7f)
    ok15("easing curves hit 0 and 1", near(AnimMath.easeOutBounce(0f), 0f) && near(AnimMath.easeOutBounce(1f), 1f) && near(AnimMath.easeOutBack(1f), 1f) && near(AnimMath.easeOutCubic(1f), 1f))
    ok15("typewriter is offered for IN only", AnimKind.values().filter { it.allowedIn }.size == 7 && AnimKind.values().filter { it.allowedOut }.size == 5 && !AnimKind.TYPEWRITER.allowedOut)
    // ---- guides
    ok15("snap to centre within 1.5 %", Guides.snap(0.505f).let { it.value == 0.5f && it.guide == 0.5f } && Guides.snap(0.62f).guide == null)
    ok15("snap to thirds", near(Guides.snap(0.335f).value, 1f / 3f) && near(Guides.snap(0.66f).value, 2f / 3f))
    ok15("keepInside pulls a text back into the 5 % safe area", Guides.keepInside(0.99f, 0.01f, 0.2f, 0.05f).let { near(it.first, 0.75f) && near(it.second, 0.10f) })
    ok15("keepInside: a text bigger than the area is centred", Guides.keepInside(0.1f, 0.9f, 0.6f, 0.6f) == Pair(0.5f, 0.5f))
    ok15("insideSafe (10 %)", Guides.insideSafe(0.5f, 0.5f, 0.3f, 0.1f) && !Guides.insideSafe(0.5f, 0.95f, 0.3f, 0.1f))
    // ---- wrapping (1 unit per letter)
    val m = { s: String -> s.length.toFloat() }
    ok15("wrap: breaks at width", TextWrap.wrap("aa bb cc dd", 5f, m) == listOf("aa bb", "cc dd"))
    ok15("wrap: long word stays whole", TextWrap.wrap("abcdefghij kk", 5f, m) == listOf("abcdefghij", "kk"))
    ok15("wrap: keeps explicit new lines, limits line count", TextWrap.wrap("a\nb\nc", 9f, m) == listOf("a", "b", "c") && TextWrap.wrap("a\nb\nc", 9f, m, 2).size == 2)
    println(if (fails15 == 0) "ALL PASS" else "FAILED $fails15"); if (fails15 > 0) kotlin.system.exitProcess(1)
}
