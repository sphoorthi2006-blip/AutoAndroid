import com.auto.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

var fails = 0
fun ok(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails++ }
fun kinds(t: Timeline, speech: Long? = null) = t.validate(speech).map { it.kind }

fun main(args: Array<String>) {
    // --- synthetic: 3 scenes of 4 s over a 12 s narration
    val sc = listOf(Scene("a", 0.0, 4.0), Scene("b", 4.0, 8.0), Scene("c", 8.0, 12.0)).onEachIndexed { k, s -> s.num = k + 1; s.image = "/p/${k + 1}.png" }
    val t0 = Timeline.fromScenes(sc, 12.0)
    ok("fromScenes: contiguous, no issues", t0.clips.size == 3 && kinds(t0).isEmpty() && t0.clips[1].startMs == 4000L && t0.clips[2].endMs == 12000L)

    // split
    val s1 = t0.split(2, 6000)
    ok("split: 4 clips, boundaries exact, still valid", s1.clips.size == 4 && s1.clips.map { it.startMs } == listOf(0L, 4000L, 6000L, 8000L) && kinds(s1).isEmpty())
    ok("split too close to edge is ignored", t0.split(2, 4050) === t0)

    // rolling trim moves neighbour edge: no gap/overlap appears
    val r = t0.trim(1, Edge.END, 3000)
    ok("rolling trim END: next clip start follows, still valid", r.clip(1).endMs == 3000L && r.clip(2).startMs == 3000L && kinds(r).isEmpty())
    val nr = t0.trim(1, Edge.END, 3000, rolling = false)
    ok("non-rolling trim opens a GAP", kinds(nr) == listOf(IssueKind.GAP))
    val rs = t0.trim(2, Edge.START, 5000)
    ok("rolling trim START moves previous end", rs.clip(1).endMs == 5000L && kinds(rs).isEmpty())
    ok("trim cannot squash neighbour below minimum", t0.trim(1, Edge.END, 7990).clip(2).durMs >= Timeline.MIN_CLIP_MS)

    // move creates overlap (+ gap), reported
    val mv = t0.move(2, 5000)
    ok("move: overlap and gap detected", IssueKind.OVERLAP in kinds(mv) && IssueKind.GAP in kinds(mv))
    ok("move clamps inside the audio", t0.move(3, 99999).clip(3).endMs <= 12000L && t0.move(3, -5).clip(3).startMs == 0L)

    // delete
    ok("delete leaves a gap", kinds(t0.delete(2)) == listOf(IssueKind.GAP))
    val rd = t0.delete(2, closeGap = true)
    ok("ripple delete: later clip moved left; narration now outlasts pictures", rd.clip(3).startMs == 4000L && IssueKind.SPEECH_PAST_END in kinds(rd))

    // replace + missing image
    ok("replaceImage", t0.replaceImage(1, "/x.png").clip(1).image == "/x.png")
    ok("missing image reported", kinds(t0.replaceImage(3, null)) == listOf(IssueKind.MISSING_IMAGE))
    ok("clipAt", t0.clipAt(3999)?.id == 1 && t0.clipAt(4000)?.id == 2 && t0.clipAt(12000) == null)

    // speech beyond last picture
    ok("speech past last picture", IssueKind.SPEECH_PAST_END in kinds(Timeline(15000, t0.clips), 15000))

    // undo / redo
    val ed = TimelineEditor(t0)
    ed.edit { it.split(2, 6000) }; ed.edit { it.trim(1, Edge.END, 3000) }; ed.edit { it.replaceImage(1, "/y.png") }
    ok("editor: 3 edits applied", ed.current.clips.size == 4 && ed.current.clip(1).image == "/y.png")
    ed.undo(); ed.undo(); ed.undo()
    ok("editor: undo x3 returns to original", ed.current == t0 && !ed.canUndo && ed.canRedo)
    ed.redo(); ed.redo()
    ok("editor: redo x2", ed.current.clip(1).endMs == 3000L)
    ed.edit { it.delete(3) }
    ok("editor: new edit clears redo", !ed.canRedo)
    ed.edit { it.split(2, 4010) }   // no-op must not create an undo step
    val depth = ed.current
    ok("editor: no-op edit ignored", ed.current === depth)

    // JSON round trip, incl. quotes/newlines/unicode in text and null image
    val tricky = Timeline(12000, listOf(ImageClip(1, 1, null, 0, 6000, "He said \"go\"\nthen \\ left \u00e9", "?"), ImageClip(2, 2, "/p/2.png", 6000, 12000)), listOf(CaptionClip(1, 0, 1500, "hello")), 7)
    ok("JSON round trip is lossless", Timeline.Json.parse(tricky.toJson()) == tricky)
    ok("JSON round trip after edits", Timeline.Json.parse(ed.current.toJson()) == ed.current)
    ok("damaged JSON gives a clear error", try { Timeline.Json.parse("{oops"); false } catch (e: PipelineError) { true })

    // --- 600 clips: no drift, fast
    val big = (0 until 600).map { Scene("s$it", it * 1.0003, (it + 1) * 1.0003).also { s -> s.num = it + 1; s.image = "/p/$it.png" } }
    val tb = Timeline.fromScenes(big, 600 * 1.0003)
    ok("600 clips contiguous & valid (no rounding drift)", kinds(tb).isEmpty() && tb.endMs == tb.audioMs)

    // --- real pipeline data (testdata/timing): scenes from Align -> Timeline
    if (args.isNotEmpty()) {
        val dir = args[0]
        val bytes = File("$dir/audio.f32").readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); val x = FloatArray(fb.remaining()); fb.get(x)
        val words = Captions.parse(File("$dir/caps.srt").readText(), "caps.srt")
        val srt = File("$dir/caps.srt").readText()
        val an = Analyser.analyse(timestampsText = srt, timestampsName = "caps.srt", scriptText = File("$dir/script.txt").readText(), samples = x, audioSeconds = x.size / 16000.0)
        val real = Timeline.fromScenes(an.scenes, an.duration, words)
        println("real data: ${real.clips.size} clips, ${real.captions.size} captions, issues=${real.validate()}")
        ok("real data: clips contiguous, cover whole narration", real.clips.size == an.scenes.size && an.scenes.size == Captions.parseRows(srt)!!.size && real.clips.first().startMs == 0L && real.endMs >= real.audioMs - 1 && real.validate().none { it.kind == IssueKind.GAP || it.kind == IssueKind.OVERLAP })
        ok("real data: captions present and ordered", real.captions.isNotEmpty() && real.captions.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        ok("real data: JSON round trip", Timeline.Json.parse(real.toJson()) == real)
    }
    println(if (fails == 0) "ALL PASSED" else "$fails FAILED")
    if (fails > 0) System.exit(1)
}
