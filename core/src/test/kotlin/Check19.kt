import com.auto.core.*
var fails19 = 0
fun ok19(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails19++ }
fun tl19() = Timeline(10000, listOf(ImageClip(1, 1, null, 0, 5000, "a"), ImageClip(2, 2, null, 5000, 10000, "b")))

/** Steps 28-31 helpers: stamps, text at playhead, lookups, caption import/export, style persistence. */
fun main() {
    ok19("parseStamp forms", StudioTools.parseStamp("1:05") == 65000L && StudioTools.parseStamp("1:05.250") == 65250L && StudioTools.parseStamp("0:01:05,250") == 65250L && StudioTools.parseStamp("65.25") == 65250L && StudioTools.parseStamp(" 2 ") == 2000L)
    ok19("parseStamp rejects junk", listOf("", "abc", "1:75", "-3", "1:2:3:4", "1::2").all { StudioTools.parseStamp(it) == null })
    ok19("stamp format round-trips", StudioTools.stamp(65250) == "1:05.250" && StudioTools.parseStamp(StudioTools.stamp(123456)) == 123456L)
    val t = tl19(); val id = t.nextTextId; val a = t.addTextAt(2000, "Hi", StylePresets.byName("Bold Title")!!)
    ok19("addTextAt at playhead, 3 s, id = nextTextId", a.text(id).startMs == 2000L && a.text(id).endMs == 5000L && a.text(id).style.caseMode == CaseMode.UPPER)
    val e = t.addTextAt(9500, "End", OverlayStyle())
    ok19("addTextAt near the end is shifted back inside the video", e.text(e.nextTextId - 1).endMs == 10000L && e.text(e.nextTextId - 1).durMs == 3000L)
    ok19("addTextAt keeps old timeline unchanged (immutable)", t.texts.isEmpty() && a.texts.size == 1)
    val b = a.addTextAt(3000, "Two", OverlayStyle())
    ok19("textsAt / textIdAt pick the topmost", b.textsAt(3500).size == 2 && b.textIdAt(3500) == b.nextTextId - 1 && b.textIdAt(9000) == null && b.textsAt(2500).size == 1)
    val ed = TimelineEditor(t); ed.edit { it.addTextAt(1000, "X", OverlayStyle()) }; ed.edit { it.editText(1, style = OverlayStyle(color = 0xFFFF0000.toInt(), gradientEnd = 0xFF0000FF.toInt())) }
    ok19("style edit lands in the timeline and undo removes it step by step", ed.current.text(1).style.gradientEnd == 0xFF0000FF.toInt() && ed.undo().let { ed.current.text(1).style.gradientEnd == null } && ed.undo().let { ed.current.texts.isEmpty() })
    val rt = Timeline.Json.parse(a.addTextAt(0, "Q\"uote", OverlayStyle(font = "mono", gradientEnd = 0xFF112233.toInt())).toJson())
    ok19("texts with gradient/quotes survive JSON", rt.texts.size == 2 && rt.texts.any { it.text == "Q\"uote" && it.style.font == "mono" && it.style.gradientEnd == 0xFF112233.toInt() })
    // captions
    val srt = "1\n00:00:00,000 --> 00:00:02,000\nHello there\n\n2\n00:00:02,000 --> 00:00:04,500\nSecond cue\n"
    val caps = CaptionTools.parse(srt, "a.srt"); val c = t.withCaptions(caps)
    ok19("import SRT gives 2 captions", caps.size == 2 && caps[1].startMs == 2000L && caps[1].endMs == 4500L)
    ok19("export SRT then import gives the same cues", CaptionTools.parse(CaptionTools.toSrt(c.captions), "x.srt").map { Triple(it.startMs, it.endMs, it.text) } == caps.map { Triple(it.startMs, it.endMs, it.text) })
    ok19("export VTT starts with WEBVTT and uses dots", CaptionTools.toVtt(c.captions).let { it.startsWith("WEBVTT") && it.contains("00:00:02.000 --> 00:00:04.500") })
    ok19("captionAt", c.captionAt(100)?.text == "Hello there" && c.captionAt(2000)?.text == "Second cue" && c.captionAt(9000) == null)
    val ce = c.editCaption(1, text = "Changed", startMs = StudioTools.parseStamp("0:00.500"), endMs = StudioTools.parseStamp("0:01.900"))
    ok19("editCaption with typed times", ce.caption(1).text == "Changed" && ce.caption(1).startMs == 500L && ce.caption(1).endMs == 1900L)
    ok19("addCaptionAt / split / merge / delete / shift", c.addCaptionAt(6000).captions.size == 3 && c.splitCaption(2, 3000).captions.size == 3 && c.mergeCaptionWithNext(1).captions.size == 1 && c.deleteCaption(1).captions.size == 1 && c.shiftCaptions(500).captions[0].startMs == 500L)
    ok19("addCaptionAt near the end stays valid", t.addCaptionAt(9900).captions[0].let { it.endMs > it.startMs && it.endMs <= 10000L })
    ok19("reading-speed check flags a fast caption", CaptionTools.check(listOf(CaptionClip(1, 0, 500, "this is far too many characters for half a second"))).any { it.contains("too fast") })
    ok19("caption style change goes through setCaptionStyle and JSON", Timeline.Json.parse(c.setCaptionStyle(StylePresets.byName("Yellow")!!).toJson()).captionStyle.color == 0xFFFFD60A.toInt())
    // style library persistence
    val lib = StyleLibrary().with("Mine", OverlayStyle(color = 0xFF00FF00.toInt(), gradientEnd = 0xFF0000FF.toInt())).with("Other", OverlayStyle())
    val back = StyleLibrary.parse(lib.toJson())
    ok19("StyleLibrary saved text parses back with styles intact", back.items.map { it.name } == listOf("Mine", "Other") && back.items[0].style.gradientEnd == 0xFF0000FF.toInt())
    ok19("StyleLibrary: damaged text gives an empty library, same name replaces", StyleLibrary.parse("{{{").items.isEmpty() && lib.with("Mine", OverlayStyle()).items.size == 2 && lib.without("Mine").items.size == 1)
    ok19("hex colour field", ColorText.parse("#7C3AED") == 0xFF7C3AED.toInt() && ColorText.parse("zz") == null && ColorText.hex(0xFF7C3AED.toInt()) == "#7C3AED")
    ok19("every preset keeps animations legal and sizes in range", (StylePresets.text + StylePresets.caption).all { it.style == it.style.clamped() })
    if (fails19 > 0) { println("FAILED $fails19"); System.exit(1) } else println("ALL PASS")
}
