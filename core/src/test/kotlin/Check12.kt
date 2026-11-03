import com.auto.core.*
import java.io.File
import java.util.Base64

var fails12 = 0
fun ok12(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails12++ }
fun err12(f: () -> Unit): String? = try { f(); null } catch (e: PipelineError) { e.message }
private val PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
private fun pics(dir: File, vararg names: String): File { dir.deleteRecursively(); dir.mkdirs(); names.forEach { File(dir, it).writeBytes(PNG) }; return dir }

/** Timestamps-first analyse (desktop's new Analyse, no Details file). args = testdata/real (ps_clean.srt = the owner's real captions). */
fun main(args: Array<String>) {
    val tmp = File(System.getProperty("java.io.tmpdir"), "chk12").apply { deleteRecursively(); mkdirs() }
    val raw = File("${args[0]}/ps_clean.srt").readText()
    val rows = Captions.parseRows(raw, "ps_clean.srt")!!

    // ---- A: the real captions + audio + numbered pictures with problems, NO details file, no script
    val names = (1..184).filter { it != 5 && it != 10 }.map { "$it.png" } + listOf("7 (1).png", "cover.png", "200.png")
    val a = CaptionPipeline.analyse(rows, "", null, 680.0, listOf(pics(File(tmp, "A"), *names.toTypedArray())), File(tmp, "workA"))
    val sc = a.scenes
    ok12("A: 184 scenes, one per caption", sc.size == 184)
    ok12("A: every picture change is exactly its cue start (cues are back-to-back)", sc.indices.all { Math.abs(sc[it].start - rows[it].start) < 1e-9 })
    ok12("A: scenes contiguous, last one lasts to the end of the audio", sc.zipWithNext().all { (x, y) -> x.end == y.start } && sc.last().end >= 680.0)
    ok12("A: pictures by number: scene 7 = 7.png (not the copy), 5 and 10 are black scenes", Matching.nameOf(sc[6].image!!) == "7.png" && sc[4].image == null && sc[9].image == null && sc[0].image != null)
    ok12("A: scene keeps its caption text for the black screen", sc[4].text == rows[4].text)
    val w = a.warnings.joinToString("\n")
    ok12("A: warns about missing 5 and 10, duplicate 7, beyond-last 200, unnumbered cover", w.contains("Scene 5 has no picture") && w.contains("Scene 10 has no picture") && w.contains("Picture number 7 exists 2 times") && w.contains("numbered beyond the last scene (184)") && w.contains("cover.png"))
    ok12("A: warns about the 24 long scenes and the one short scene", (1..184).count { w.contains("Scene $it is ") && w.contains("s long") } >= 24 && w.contains("Scene 45 is only 0.58 s long"))
    ok12("A: summary says exact timestamps", a.warnings.first().contains("Timing: timestamps (exact)") && a.warnings.first().contains("184 captions"))
    val tl = Timeline.fromScenes(a.scenes, a.timing.duration)
    ok12("A: becomes a valid timeline (no gaps/overlaps) and the export path is taken (match.full)", a.match.full && tl.validate().none { it.kind == IssueKind.GAP || it.kind == IssueKind.OVERLAP })
    ok12("A: Validation.check works on it (scenes without picture reported)", Validation.check(a).scenesWithoutPicture == listOf(5, 10))

    // ---- B: back-to-back cues + a script with other counts: the cues still win
    val sc2 = CaptionPipeline.analyse(rows, rows.take(100).joinToString("\n") { it.text }, null, 0.0, emptyList(), File(tmp, "workB"))
    ok12("B: script is only a cross-check, scenes follow the cues; no pictures -> black screens, no audio -> still works", sc2.scenes.size == 184 && sc2.warnings.any { Regex("script has 10\\d lines and the timestamps 184").containsMatchIn(it) } && sc2.warnings.any { it.contains("No pictures were added") })

    // ---- C: script only, no audio -> estimated; script + verified words -> aligned
    val lines = listOf("The ancient city stood above the sea.", "Its great walls protected thousands of families.", "Merchants brought silk and spices.")
    val c = CaptionPipeline.analyse(null, lines.joinToString("\n"), null, 0.0, listOf(pics(File(tmp, "C"), "1.png", "2.png", "3.png")), File(tmp, "workC"))
    ok12("C: script only -> 3 scenes, estimated timing with a warning, pictures 1..3", c.scenes.size == 3 && c.warnings.any { it.contains("ESTIMATED") } && c.scenes.all { it.image != null })
    val tw = ArrayList<TimedWord>(); var t = 1.0
    for (l in lines) for (x in l.split(" ")) { tw.add(TimedWord(x, t, t + 0.3)); t += 0.4 }
    val c2 = CaptionPipeline.analyse(null, lines.joinToString("\n"), tw, 12.0, emptyList(), File(tmp, "workC2"), hasAudio = true)
    ok12("C: script + verified word times -> cuts at the spoken words", c2.scenes.size == 3 && c2.scenes[1].start > 3.0 && c2.scenes[1].start < 4.0 && c2.warnings.first().contains("Timing: audio"))

    // ---- D: pictures only -> ordered by number, 3 s each
    val d = CaptionPipeline.analyse(null, "", null, 0.0, listOf(pics(File(tmp, "D"), "10.png", "2.png", "1.png")), File(tmp, "workD"))
    ok12("D: pictures only -> sorted by number, 3 s each", d.scenes.map { Matching.nameOf(it.image!!) } == listOf("1.png", "2.png", "10.png") && d.scenes.all { Math.abs(it.end - it.start - 3.0) < 1e-6 })

    // ---- E: audio only; nothing at all
    val e = CaptionPipeline.analyse(null, "", null, 42.0, emptyList(), File(tmp, "workE"), hasAudio = true)
    ok12("E: audio only -> one black scene as long as the narration", e.scenes.size == 1 && e.scenes[0].end == 42.0 && e.scenes[0].image == null)
    ok12("E: nothing at all -> the only error", err12 { CaptionPipeline.analyse(null, "", null, 0.0, emptyList(), File(tmp, "workE2")) }?.contains("Nothing to analyse") == true)

    // ---- F: names without numbers -> file-name order + warning; fewer pictures than scenes
    val f = CaptionPipeline.analyse(rows.take(5), "", null, 0.0, listOf(pics(File(tmp, "F"), "alpha.png", "beta.png", "gamma.png")), File(tmp, "workF"))
    ok12("F: no numbers -> file-name order, warning, scenes 4-5 black", f.scenes.take(3).all { it.image != null } && f.scenes[3].image == null && f.warnings.any { it.contains("no scene numbers") } && f.warnings.any { it.contains("Scenes 4-5") })

    // ---- G: numbering from 0, prompt/cue names, copy suffix
    val g = CaptionPipeline.analyse(rows.take(3), "", null, 0.0, listOf(pics(File(tmp, "G"), "Prompt 0.png", "Prompt 1.png", "Prompt 2.png")), File(tmp, "workG"))
    ok12("G: pictures numbered from 0 -> picture 0 is scene 1", g.scenes.all { it.image != null } && Matching.nameOf(g.scenes[0].image!!) == "Prompt 0.png")
    ok12("G: imageNumber understands prompt / cue / copy suffix / long ids", CaptionPipeline.imageNumber("/x/Cue 12.png") == 12 && CaptionPipeline.imageNumber("/x/7 (1).jpg") == 7 && CaptionPipeline.imageNumber("/x/20240115123456.png") == null && CaptionPipeline.imageNumber("/x/a1b2.png") == null)

    // ---- H: unsorted / overlapping / empty captions are repaired with warnings, not errors
    val messy = listOf(Cue(5.0, 8.0, "second"), Cue(0.0, 5.0, "first"), Cue(8.0, 9.0, "  "), Cue(7.5, 12.0, "third"))
    val h = CaptionPipeline.analyse(messy, "", null, 0.0, emptyList(), File(tmp, "workH"))
    ok12("H: sorted, empty caption skipped, overlap reported", h.scenes.map { it.text } == listOf("first", "second", "third") && h.warnings.any { it.contains("not in time order") } && h.warnings.any { it.contains("without text were skipped") } && h.warnings.any { it.contains("overlapping") })

    println(if (fails12 == 0) "ALL PASSED" else "$fails12 FAILED")
    if (fails12 > 0) System.exit(1)
}
