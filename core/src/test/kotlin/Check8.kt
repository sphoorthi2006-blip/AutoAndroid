import com.auto.core.*
import java.io.File

var fails8 = 0
fun ok8(name: String, c: Boolean) { println((if (c) "PASS " else "FAIL ") + name); if (!c) fails8++ }

fun main(args: Array<String>) {
    val dir = File(args[0])   // contains test.docx, empty.docx, notdocx.docx, p/*.png|jpg|webp, junk.png
    // --- DOCX
    val t = TextFiles.read(File(dir, "test.docx")).split("\n")
    ok8("docx: paragraphs in order", t[0] == "Image 1 - Script line: \"Tom & Jerry <meet>\"" && t[1] == "Subject: a forest")
    ok8("docx: split runs joined, tab + line break kept", t[2] == "Split run here\tafter tab" && t[3] == "line1" && t[4] == "line2")
    ok8("docx: table cells become lines, numeric entity decoded", t.contains("Cell A") && t.contains("Cell B é"))
    ok8("docx: parsed by Details like a txt file", Details.parseSceneFile(TextFiles.read(File(dir, "test.docx")) + "\nImage 2 - Script line: \"Second\"\nSubject: x").size == 2)
    ok8("docx: empty doc -> clear error", try { TextFiles.read(File(dir, "empty.docx")); false } catch (e: PipelineError) { "no text" in e.message!! })
    ok8("docx: non-docx zip -> clear error", try { TextFiles.read(File(dir, "notdocx.docx")); false } catch (e: PipelineError) { "not a Word document" in e.message!! })
    ok8("txt still read as text", TextFiles.read(File(dir, "plain.txt")) == "hello\nworld")
    val det = "Image 1 - Script line: \"One\"\nSubject: a\nImage 2 - Script line: \"Two\"\nSubject: b\nImage 3 - Script line: \"Three\"\nSubject: c"
    val b = Bundle.classify(listOf(Entry("story.docx", 10) { TextFiles.read(File(dir, "story.docx")) }, Entry("img.txt", 5) { det }), 3)
    ok8("Bundle: .docx script + txt details classified correctly", b.script?.name == "story.docx" && b.details?.name == "img.txt")
    val b2 = Bundle.classify(listOf(Entry("details.docx", 10) { det }, Entry("story.txt", 5) { "plain story text here" }), 3)
    ok8("Bundle: .docx can also be the details file", b2.details?.name == "details.docx" && b2.script?.name == "story.txt")

    // --- Validation
    val pics = File(dir, "p").listFiles()!!.map { it.path }.sorted()
    val dups = Validation.duplicates(pics)
    ok8("duplicates: identical bytes found (a001 = a003), others not", dups.size == 1 && dups[0].map { Matching.nameOf(it) } == listOf("a001.png", "a003.png"))
    ok8("duplicates: same size but different bytes NOT reported", dups.flatten().none { it.endsWith("a002.png") })
    ok8("missingNumbers: 1,2,3,5,8 -> 4,6,7", Validation.missingNumbers(listOf("a/1.png", "a/2.png", "a/3.png", "a/5.png", "a/8.png")) == listOf(4, 6, 7))
    ok8("missingNumbers: no numbers -> none", Validation.missingNumbers(listOf("cat.png", "dog.png")).isEmpty())
    val bad = Validation.unreadable(pics + File(dir, "junk.png").path)
    ok8("unreadable: png/jpg/webp pass, junk flagged", bad == listOf(File(dir, "junk.png").path))

    // --- ExportPlan
    val sc = (0 until 600).map { Scene("s", it * 1.0003, (it + 1) * 1.0003).also { s -> s.num = it + 1; s.image = "/p/$it.png" } }
    val tl = Timeline.fromScenes(sc, 600 * 1.0003)
    val plan = ExportPlan.fromTimeline(tl)
    ok8("plan: 600 segments, total == audio length EXACTLY", plan.segments.size == 600 && plan.totalMs == tl.audioMs && plan.segments.sumOf { it.durMs } == tl.audioMs)
    ok8("plan: contiguous", plan.segments.zipWithNext().all { (a, b) -> a.endMs == b.startMs } && plan.segments.first().startMs == 0L)
    // the OLD arithmetic (truncate each scene length to ms) for comparison
    val oldTotal = sc.sumOf { ((it.end - it.start).coerceAtLeast(0.04) * 1000).toLong() }
    println("info: old per-scene truncation would end at ${oldTotal} ms vs audio ${tl.audioMs} ms (drift ${tl.audioMs - oldTotal} ms)")
    ok8("old arithmetic really drifted (justifies the fix)", tl.audioMs - oldTotal > 0)
    val g = Timeline(10000, listOf(ImageClip(1, 1, "/a", 1000, 4000), ImageClip(2, 2, "/b", 3500, 6000), ImageClip(3, 3, "/c", 7000, 8000)))
    val gp = ExportPlan.fromTimeline(g)
    ok8("plan: leading gap -> blank, overlap cut, gap -> blank, last held to audio end",
        gp.segments.map { Triple(it.image, it.startMs, it.endMs) } == listOf(Triple(null, 0L, 1000L), Triple("/a", 1000L, 4000L), Triple("/b", 4000L, 6000L), Triple(null, 6000L, 7000L), Triple("/c", 7000L, 10000L)))
    ok8("plan: empty timeline -> one blank segment", ExportPlan.fromTimeline(Timeline(5000, emptyList())).segments.single().image == null)
    ok8("settings: odd size / bad fps rejected", try { ExportSettings(1281, 720); false } catch (e: IllegalArgumentException) { true } && try { ExportSettings(1280, 720, 25); false } catch (e: IllegalArgumentException) { true })
    val hd = Timeline(1000, listOf(ImageClip(1, 1, null, 0, 1000, "Intro", "", true)))
    ok8("heading flag survives timeline JSON + plan", ExportPlan.fromTimeline(Timeline.Json.parse(hd.toJson())).segments[0].heading)
    println(if (fails8 == 0) "ALL PASSED" else "$fails8 FAILED"); if (fails8 > 0) System.exit(1)
}
