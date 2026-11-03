import com.auto.core.*
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

var fails9 = 0
fun ok9(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails9++ }

/** 16 kHz test audio: list of (seconds, loud?) segments; "loud" is a 220 Hz tone, quiet is faint noise. */
fun audio(vararg seg: Pair<Double, Boolean>): FloatArray {
    val out = ArrayList<Float>(); val r = java.util.Random(1)
    for ((sec, loud) in seg) for (i in 0 until (sec * 16000).toInt()) out.add(if (loud) (0.3 * sin(2 * PI * 220 * i / 16000.0)).toFloat() else (r.nextGaussian() * 0.0005).toFloat())
    return out.toFloatArray()
}

fun main(args: Array<String>) {
    // ---- numeric confidence
    ok9("score: clean = 100", Confidence.score("", false, false, 4000) == 100)
    ok9("score: uncertain match = 55", Confidence.score("?", false, false, 4000) == 55)
    ok9("score: uncertain + cut check + short = 20", Confidence.score("?", true, false, 500) == 20)
    ok9("score: missing picture = 0", Confidence.score("", false, true, 4000) == 0)
    val sc = listOf(Scene("a", 0.0, 4.0), Scene("b", 4.0, 8.0), Scene("c", 8.0, 12.0)).onEachIndexed { k, s -> s.num = k + 1; s.image = "/p/${k + 1}.png" }
    sc[1].conf = "?"; sc[2].cutCheck = true; sc[2].image = null
    val t = Timeline.fromScenes(sc, 12.0)
    ok9("fromScenes carries scores 100/55/0", t.clips.map { it.score } == listOf(100, 55, 0))
    ok9("cutCheck carried", t.clips[2].cutCheck && !t.clips[0].cutCheck)
    val rv = Confidence.needsReview(t)
    ok9("needsReview: lowest first, clean clip excluded", rv.map { it.clipId } == listOf(3, 2) && rv.all { it.kind == IssueKind.LOW_CONFIDENCE })
    val rt = Timeline.Json.parse(t.toJson())
    ok9("JSON round trip keeps score + cutCheck", rt == t)
    val old = "{\"version\":1,\"audioMs\":1000,\"nextId\":2,\"clips\":[{\"id\":1,\"scene\":1,\"image\":null,\"start\":0,\"end\":1000,\"text\":\"x\",\"conf\":\"\",\"heading\":false}],\"captions\":[]}"
    ok9("old project file (no score) still loads, score=100", Timeline.Json.parse(old).clips[0].let { it.score == 100 && !it.cutCheck })
    ok9("edits keep score", t.split(1, 2000).clips.all { it.id != 1 && it.id != 4 || it.score == 100 })

    // ---- silences + pause policy
    val x = audio(1.0 to false, 3.0 to true, 3.0 to false, 3.0 to true, 0.6 to false, 3.0 to true, 1.0 to false)  // 14.6 s: pauses of 3.0 s at 4.0 and 0.6 s at 10.0
    val sil = AudioPauses.silences(x, 0.5)!!
    ok9("silences: lead-in and tail ignored, two inner pauses found", sil.size == 2)
    ok9("silences: positions within 0.1 s", Math.abs(sil[0].first - 4.0) < 0.1 && Math.abs(sil[0].second - 7.0) < 0.1 && Math.abs(sil[1].first - 10.0) < 0.1)
    ok9("silences: minSec filter", AudioPauses.silences(x, 1.0)!!.size == 1)
    ok9("silences: all-silence audio -> null (no guessing)", AudioPauses.silences(audio(8.0 to false), 0.5) == null)
    val tl = Timeline(14600, listOf(ImageClip(1, 1, "/a", 0, 8000), ImageClip(2, 2, "/b", 8000, 14600)))
    ok9("KEEP policy reports nothing", PausePolicy(PauseMode.KEEP).review(tl, sil).isEmpty())
    val fl = PausePolicy(PauseMode.FLAG_LONG, 2.5).review(tl, sil)
    ok9("FLAG_LONG flags only the 3 s pause, in scene 1, and moves no clip", fl.size == 1 && fl[0].kind == IssueKind.LONG_PAUSE && fl[0].clipId == 1)
    ok9("FLAG_LONG threshold 0.5 flags both", PausePolicy(PauseMode.FLAG_LONG, 0.5).review(tl, sil).size == 2)

    // ---- project store
    val root = File(args[0]); root.deleteRecursively()
    var now = 1000L; val st = ProjectStore(root) { now++ }
    val m0 = st.create("My \"story\"")
    ok9("create: folders exist", File(st.dir(m0.id), "inputs").isDirectory && File(st.dir(m0.id), "manifest.json").isFile)
    val src = File(root, "narr.mp3"); src.writeText("audio-bytes")
    val a = st.importFile(m0.id, src); val b = st.importFile(m0.id, src)
    ok9("importFile copies once per call, never overwrites", a.readText() == "audio-bytes" && a.name == "narr.mp3" && b.name == "narr_1.mp3")
    ok9("importFile cannot escape the project folder", st.importFile(m0.id, src, "../../evil.txt").canonicalPath.startsWith(st.dir(m0.id).canonicalPath))
    val m1 = st.save(m0.copy(inputs = mapOf("audio" to listOf(a.name), "pictures" to listOf("a.zip", "b.zip")), settings = mapOf("pause" to "FLAG_LONG")), t)
    val l1 = st.load(m0.id)
    ok9("load: manifest + timeline identical to what was saved", l1.manifest == m1 && l1.timeline == t && l1.warnings.isEmpty() && l1.manifest.name == "My \"story\"")
    ok9("save updates the modified time", m1.updatedMs > m0.updatedMs)
    val t2 = t.split(1, 2000); st.save(m1, t2)
    File(st.dir(m0.id), "timeline.json").writeText("{ half a fi")   // simulated crash / corruption
    val l2 = st.load(m0.id)
    ok9("corrupt timeline -> previous saved copy restored with a warning", l2.timeline == t && l2.warnings.any { "restored" in it })
    st.save(m1, t2)  // saving over a damaged file must not poison the backup
    ok9("saving after corruption keeps a valid backup", Timeline.Json.parse(File(st.dir(m0.id), "timeline.json.bak").readText()) == t)
    File(st.dir(m0.id), "timeline.json").writeText("garbage"); File(st.dir(m0.id), "timeline.json.bak").writeText("garbage")
    val l3 = st.load(m0.id)
    ok9("timeline lost in both copies -> opens without it, clear warning", l3.timeline == null && l3.warnings.any { "re-run" in it })
    File(st.dir(m0.id), "manifest.json").writeText("nope"); File(st.dir(m0.id), "manifest.json.bak").delete()
    ok9("manifest lost -> meaningful error", try { st.load(m0.id); false } catch (e: PipelineError) { "cannot be opened" in (e.message ?: "") })
    val m2 = st.create("second"); st.save(m2, t)
    ok9("list skips damaged project, newest first", st.list().map { it.id } == listOf(m2.id))
    File(File(st.dir(m2.id), "work"), "x.tmp").writeText("12345")
    ok9("cleanTemp removes work files only", st.cleanTemp(m2.id) == 5L && st.load(m2.id).timeline == t && !File(File(st.dir(m2.id), "work"), "x.tmp").exists())
    ok9("bad ids rejected", try { st.dir("../x"); false } catch (e: IllegalArgumentException) { true })
    st.delete(m2.id); ok9("delete", !st.dir(m2.id).exists())
    if (fails9 > 0) { println("$fails9 FAILED"); System.exit(1) }
}
