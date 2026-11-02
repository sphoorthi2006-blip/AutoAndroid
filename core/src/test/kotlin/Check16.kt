import com.auto.core.*
var fails16 = 0
fun ok16(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails16++ }
fun n16(a: Float, b: Float, e: Float = 2e-3f) = Math.abs(a - b) <= e

fun base(): Timeline {
    val sc = listOf(Scene("a", 0.0, 4.0), Scene("b", 4.0, 8.0), Scene("c", 8.0, 12.0)).onEachIndexed { k, s -> s.num = k + 1; s.image = "/p/${k + 1}.png" }
    return Timeline.fromScenes(sc, 12.0)
}

/** Step 14: effects matrix, transitions, text track, duplicate, JSON, export plan. */
fun main() {
    // ---- effects matrix
    val id = ClipFx().rgbMatrix()
    ok16("identity fx -> identity matrix", ClipFx().isIdentity && ClipFx.apply(id, 0.2f, 0.5f, 0.9f).let { n16(it.first, 0.2f) && n16(it.second, 0.5f) && n16(it.third, 0.9f) })
    ok16("brightness +0.4 adds 0.2", ClipFx.apply(ClipFx(brightness = 0.4f).rgbMatrix(), 0.3f, 0.3f, 0.3f).first.let { n16(it, 0.5f) })
    ok16("brightness -1 darkens, +1 clips at 1", ClipFx.apply(ClipFx(brightness = -1f).rgbMatrix(), 0.3f, 0.3f, 0.3f).first == 0f && ClipFx.apply(ClipFx(brightness = 1f).rgbMatrix(), 0.8f, 0.8f, 0.8f).first == 1f)
    ok16("contrast +1 doubles distance from grey", ClipFx.apply(ClipFx(contrast = 1f).rgbMatrix(), 0.6f, 0.4f, 0.5f).let { n16(it.first, 0.7f) && n16(it.second, 0.3f) && n16(it.third, 0.5f) })
    ok16("contrast -1 -> flat grey", ClipFx.apply(ClipFx(contrast = -1f).rgbMatrix(), 0.9f, 0.1f, 0.4f).let { n16(it.first, 0.5f) && n16(it.second, 0.5f) })
    ok16("saturation -1 -> grey (R=G=B = luma)", ClipFx.apply(ClipFx(saturation = -1f).rgbMatrix(), 1f, 0f, 0f).let { n16(it.first, 0.2126f) && n16(it.second, 0.2126f) && n16(it.third, 0.2126f) })
    ok16("saturation +1 pushes red further from grey", ClipFx.apply(ClipFx(saturation = 1f).rgbMatrix(), 0.6f, 0.4f, 0.4f).let { it.first > 0.6f && it.second < 0.4f })
    ok16("saturation leaves grey unchanged", ClipFx.apply(ClipFx(saturation = 0.7f).rgbMatrix(), 0.4f, 0.4f, 0.4f).let { n16(it.first, 0.4f) && n16(it.second, 0.4f) })
    ok16("opacity 0.5 halves the colours; extraAlpha multiplies", ClipFx.apply(ClipFx(opacity = 0.5f).rgbMatrix(), 0.8f, 0.4f, 0.2f).let { n16(it.first, 0.4f) && n16(it.second, 0.2f) } && ClipFx.apply(ClipFx().rgbMatrix(0.25f), 1f, 1f, 1f).first.let { n16(it, 0.25f) })
    ok16("matrix is 16 floats, last = 1", ClipFx(brightness = 0.3f, contrast = 0.2f).rgbMatrix().let { it.size == 16 && it[15] == 1f })
    ok16("clamped() limits typed values", ClipFx(5f, -5f, 9f, 3f).clamped() == ClipFx(1f, -1f, 1f, 1f))
    ok16("catalog: 4 rendered effects, 3 listed as unavailable", EffectCatalog.available.map { it.id } == listOf("brightness", "contrast", "saturation", "opacity") && EffectCatalog.unavailable.map { it.id }.toSet() == setOf("blur", "sharpen", "vignette"))
    // ---- transitions
    val fade = TransitionSpec(TransitionKind.FADE, 1000)
    ok16("only rendered transitions are offered", Transitions.offered == listOf(TransitionKind.NONE, TransitionKind.FADE, TransitionKind.SLIDE, TransitionKind.ZOOM) && Transitions.unavailable == listOf(TransitionKind.DISSOLVE, TransitionKind.WIPE))
    ok16("an unavailable kind is refused by clamped()", TransitionSpec(TransitionKind.WIPE, 500).clamped().kind == TransitionKind.NONE)
    ok16("fade-in: 0 at start, .5 half way, 1 after", Transitions.frame(fade, null, 0, 4000).alpha == 0f && n16(Transitions.frame(fade, null, 500, 4000).alpha, 0.5f) && Transitions.frame(fade, null, 1000, 4000).alpha == 1f)
    ok16("next picture's FADE fades THIS one out at its end", Transitions.frame(TransitionSpec(), fade, 3999, 4000).alpha < 0.01f && Transitions.frame(TransitionSpec(), fade, 2000, 4000).alpha == 1f)
    ok16("transition is cut to half the picture (600 ms picture, 1000 ms fade -> 300 ms)", Transitions.effectiveMs(fade, 600) == 300L)
    ok16("slide-in starts a full frame to the side and ends at 0", Transitions.frame(TransitionSpec(TransitionKind.SLIDE, 500), null, 0, 4000).dxFrac == 1f && Transitions.frame(TransitionSpec(TransitionKind.SLIDE, 500), null, 500, 4000).dxFrac == 0f)
    ok16("zoom-in grows from .6 to 1", Transitions.frame(TransitionSpec(TransitionKind.ZOOM, 500), null, 0, 4000).scale.let { n16(it, 0.6f) } && Transitions.frame(TransitionSpec(TransitionKind.ZOOM, 500), null, 500, 4000).scale == 1f)
    // ---- timeline: fx / transitions / duplicate
    var t = base()
    t = t.setFx(2, ClipFx(brightness = 0.2f)).setTransition(3, fade)
    ok16("setFx / setTransition stored on the right clips", t.clip(2).fx.brightness == 0.2f && t.clip(3).transition == fade && t.clip(1).fx.isIdentity)
    ok16("JSON round trip with fx + transition", Timeline.Json.parse(t.toJson()) == t)
    ok16("default clips write no fx/transition keys (small files)", !base().toJson().contains("\"fx\"") && !base().toJson().contains("\"transition\""))
    val d = base().duplicate(1)
    ok16("duplicate: copy follows the original, later clips move by its length, still contiguous", d.clips.size == 4 && d.clips.map { it.startMs } == listOf(0L, 4000L, 8000L, 12000L) && d.clips[1].image == "/p/1.png" && d.clips.zipWithNext().all { (a, b) -> a.endMs == b.startMs } && d.validate().none { it.kind == IssueKind.GAP || it.kind == IssueKind.OVERLAP })
    ok16("duplicate: new unique id, copy has no transition", d.clips.map { it.id }.toSet().size == 4 && base().setTransition(1, fade).duplicate(1).clips[1].transition == TransitionSpec())
    ok16("duplicate is one undo step", TimelineEditor(base()).also { it.edit { x -> x.duplicate(1) } }.undo() == base())
    // ---- text track
    var x = base(); val id1 = x.nextTextId
    x = x.addText(1000, 3000, "Hello", StylePresets.byName("Bold Title")!!)
    ok16("addText: id 1, sorted, style kept", id1 == 1 && x.texts.size == 1 && x.text(1).style == StylePresets.byName("Bold Title")!!.clamped())
    x = x.addText(500, 700, "Early")
    ok16("texts stay sorted by start", x.texts.map { it.text } == listOf("Early", "Hello"))
    ok16("addText past the end is pulled inside; min length enforced", base().addText(99_000, 99_001, "z").texts[0].let { it.endMs <= 12000 && it.durMs >= Timeline.MIN_CLIP_MS })
    x = x.editText(1, text = "Edited", endMs = 3500)
    ok16("editText changes text and end", x.text(1).let { it.text == "Edited" && it.endMs == 3500L })
    ok16("editText can't make end <= start", x.editText(1, endMs = 0).text(1).durMs == Timeline.MIN_CLIP_MS)
    x = x.moveText(1, 10_000)
    ok16("moveText keeps length and stays in the video", x.text(1).let { it.startMs + it.durMs <= 12000 && it.durMs == 2500L })
    val y = x.duplicateText(1)
    ok16("duplicateText: new id, same text/style", y.texts.size == 3 && y.texts.count { it.text == "Edited" } == 2 && y.texts.map { it.id }.toSet().size == 3 && y.texts.all { it.endMs <= 12000 })
    val sp = base().addText(1000, 5000, "split me").splitText(1, 3000)
    ok16("splitText: two halves, same text; too close to the edge = unchanged", sp.texts.size == 2 && sp.texts[0].endMs == 3000L && sp.texts[1].startMs == 3000L && base().addText(1000, 5000, "q").let { it.splitText(1, 1010) == it })
    ok16("deleteText", x.deleteText(1).texts.map { it.id } == listOf(2) && try { x.deleteText(99); false } catch (e: IllegalArgumentException) { true })
    ok16("textsAt finds overlapping texts at one time", base().addText(0, 4000, "a").addText(2000, 6000, "b").textsAt(3000).size == 2 && base().addText(0, 4000, "a").textsAt(4000).isEmpty())
    ok16("JSON round trip with texts + caption style", Timeline.Json.parse(y.setCaptionStyle(StylePresets.byName("Yellow")!!).toJson()) == y.setCaptionStyle(StylePresets.byName("Yellow")!!))
    ok16("a project file written before Step 14 still loads (no texts, default caption style)", Timeline.Json.parse("{\"version\":1,\"audioMs\":1000,\"nextId\":2,\"clips\":[{\"id\":1,\"scene\":1,\"image\":null,\"start\":0,\"end\":1000,\"text\":\"x\",\"conf\":\"\",\"heading\":false}],\"captions\":[]}").let { it.texts.isEmpty() && it.captionStyle == StylePresets.captionDefault })
    ok16("text edits undo/redo through TimelineEditor", TimelineEditor(base()).let { e -> e.edit { it.addText(0, 1000, "u") }; val added = e.current; e.undo() == base() && e.redo() == added })
    // ---- export plan
    val tl = base().setFx(1, ClipFx(contrast = 0.3f)).setTransition(2, fade)
        .copy(texts = listOf(TextClip(1, 0, 20_000, "long"), TextClip(2, 13_000, 14_000, "after end")), captions = listOf(CaptionClip(1, 0, 2000, "one"), CaptionClip(2, 11_000, 15_000, "cut"), CaptionClip(3, 12_500, 13_000, "gone")))
    val p = ExportPlan.fromTimeline(tl)
    ok16("plan: fx and transitions reach the segments", p.segments[0].fx.contrast == 0.3f && p.segments[1].transition == fade && p.usesFx && p.usesTransitions && p.usesTexts)
    ok16("plan: the segment BEFORE a fade knows about it", p.segments[0].nextTransition == fade && p.segments[1].nextTransition == null)
    ok16("plan: texts and captions are cut to the video length, those after the end dropped", p.texts.size == 1 && p.texts[0].endMs == 12000L && p.captions.map { it.id } == listOf(1, 2) && p.captions[1].endMs == 12000L)
    ok16("plan: caption style travels with the plan", p.captionStyle == tl.captionStyle)
    ok16("plan without effects reports none", ExportPlan.fromTimeline(base()).let { !it.usesFx && !it.usesTransitions && !it.usesTexts })
    ok16("plan: gap segments carry no fx", ExportPlan.fromTimeline(base().delete(2)).segments.first { it.image == null }.fx.isIdentity)
    println(if (fails16 == 0) "ALL PASS" else "FAILED $fails16"); if (fails16 > 0) kotlin.system.exitProcess(1)
}
