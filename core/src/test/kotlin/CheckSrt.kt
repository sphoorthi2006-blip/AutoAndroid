import com.auto.core.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Headless checks of the SRT-first Analyse (port of the desktop tests/test_analyse.py + the Android-specific ZIP / many-cue cases). Run: java -cp ... CheckSrtKt */
var failsS = 0
fun okS(name: String, c: Boolean) { println((if (c) "PASS " else "FAIL ") + name); if (!c) failsS++ }
fun ts(t: Double): String = String.format(Locale.ROOT, "00:%02d:%02d,%03d", (t / 60).toInt(), (t % 60).toInt(), Math.round((t % 1) * 1000).toInt())
fun makeSrt(n: Int, longAt: Int = -1, step: Double = 1.8, gap: Double = 0.2): Pair<String, Double> {
    val sb = StringBuilder(); var t = 0.5
    for (k in 0 until n) { val d = if (k == longAt) 12.0 else step; sb.append("${k + 1}\n${ts(t)} --> ${ts(t + d)}\nScene line number ${k + 1} spoken here\n\n"); t += d + gap }
    return Pair(sb.toString(), t)
}
fun pic(f: File, seed: Int, jpg: Boolean = f.extension != "png") {
    val im = BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB); val r = java.util.Random(seed.toLong())
    for (y in 0 until 36) for (x in 0 until 64) im.setRGB(x, y, r.nextInt(0xFFFFFF))
    f.parentFile.mkdirs(); ImageIO.write(im, if (jpg) "jpg" else "png", f)
}
fun pics(dir: File, nums: List<Int>) { for (k in nums) pic(File(dir, "$k.jpg"), k) }
fun kinds(a: Analysis) = a.issues.filter { it.level == "warn" }.map { it.kind }.toSet()
fun nm(p: String?) = p?.let { Pics.nameOf(it) }

fun main() {
    val tmp = Files.createTempDirectory("srt").toFile()
    val (srt, end) = makeSrt(20, longAt = 5)

    // nothing at all is the only hard stop
    okS("empty analyse raises PipelineError", try { Analyser.analyse(); false } catch (e: PipelineError) { true })

    // timestamps alone: cuts are exactly the cue starts, black screens with text, long scene flagged
    var a = Analyser.analyse(timestampsText = srt, timestampsName = "a.srt")
    okS("timestamps alone: 20 scenes, scenes+timing from timestamps", a.scenes.size == 20 && a.sceneSource == "timestamps" && a.timingSource == "timestamps")
    okS("cut 2 is cue start minus 0.06 s (exact, nothing snapped)", Math.abs(a.scenes[1].start - (0.5 + 1.8 + 0.2 - 0.06)) < 0.02)
    okS("no pictures: black screens with their text, flagged", a.scenes.all { it.image == null && it.text.startsWith("Scene line") } && "missing_pic" in kinds(a))
    okS("12 s cue flagged as long scene 6", a.issues.any { it.scene == 6 && it.kind == "long_scene" })
    okS("scenes are contiguous from 0 and outStart is cumulative", a.scenes[0].start == 0.0 && a.scenes.zipWithNext().all { (x, y) -> x.end == y.start } && Math.abs(a.scenes[3].outStart - a.scenes[3].start) < 1e-9)

    // cues > pictures (number 4 missing), pictures > cues (21, 22 extra), unnumbered + duplicate numbers
    val p = File(tmp, "p"); pics(p, (1..22).filter { it != 4 })
    File(p, "1.jpg").copyTo(File(p, "cover.jpg")); File(p, "2.jpg").copyTo(File(p, "2 (1).jpg"))
    a = Analyser.analyse(timestampsText = srt, timestampsName = "a.srt", pictures = p.listFiles()!!.map { it.path }, audioSeconds = end)
    okS("number 4 missing -> black screen, 3 and 5 on their own scenes", a.scenes[3].image == null && nm(a.scenes[2].image) == "3.jpg" && nm(a.scenes[4].image) == "5.jpg")
    okS("extras left out: 21, 22, cover.jpg, '2 (1).jpg'", a.extras.map { nm(it) }.toSet().containsAll(setOf("21.jpg", "22.jpg", "cover.jpg", "2 (1).jpg")))
    okS("warnings: missing_pic, extra_pic, unnumbered_pic, dup_pic", kinds(a).containsAll(setOf("missing_pic", "extra_pic", "unnumbered_pic", "dup_pic")))
    okS("the original '2.jpg' wins over its copy", nm(a.scenes[1].image) == "2.jpg")

    // fewer pictures than cues, unnumbered random names -> file-name order, black at the end
    val q = File(tmp, "q"); for (k in 0 until 5) pic(File(q, "gen_${'a' + k}_xyz.png"), k)
    a = Analyser.analyse(timestampsText = srt, pictures = q.listFiles()!!.map { it.path })
    okS("unnumbered names: first 5 scenes get pictures in name order, rest black", a.scenes.count { it.image != null } == 5 && a.scenes.take(5).all { it.image != null } && nm(a.scenes[0].image) == "gen_a_xyz.png")
    okS("unnumbered names: order_mode + missing_pic warnings", "order_mode" in kinds(a) && "missing_pic" in kinds(a))

    // audio shorter than the timestamps
    a = Analyser.analyse(timestampsText = srt, audioSeconds = end / 2)
    okS("audio shorter than timestamps -> audio_len warning", "audio_len" in kinds(a))
    a = Analyser.analyse(timestampsText = srt, audioSeconds = end + 5)
    okS("audio 5 s longer: last picture held to the end, duration = audio", Math.abs(a.duration - (end + 5)) < 1e-9 && Math.abs(a.scenes.last().end - (end + 5)) < 1e-9)

    // pictures only (+ audio): one scene per picture, in number order
    val r = File(tmp, "r"); pics(r, listOf(3, 1, 2))
    a = Analyser.analyse(pictures = r.listFiles()!!.map { it.path })
    okS("pictures only: number order, 3 s each", a.scenes.map { nm(it.image) } == listOf("1.jpg", "2.jpg", "3.jpg") && Math.abs(a.duration - 9.0) < 1e-9 && a.timingSource == "estimate")
    a = Analyser.analyse(pictures = r.listFiles()!!.map { it.path }, audioSeconds = 6.0)
    okS("pictures + audio: 3 scenes, timing from audio/even, length 6 s", a.scenes.size == 3 && a.timingSource in listOf("audio", "even") && Math.abs(a.duration - 6.0) < 1e-9)
    a = Analyser.analyse(audioSeconds = 12.0)
    okS("audio only: one black scene as long as the audio", a.scenes.size == 1 && a.sceneSource == "audio" && Math.abs(a.scenes[0].end - 12.0) < 1e-9)

    // script only: estimated timing is flagged; script + timestamps that disagree is flagged
    val scr = "First line of the script.\nSecond line goes here.\n"
    a = Analyser.analyse(scriptText = scr)
    okS("script only: 2 scenes, estimated, 'timing' warning", a.scenes.size == 2 && a.timingSource == "estimate" && "timing" in kinds(a))
    a = Analyser.analyse(timestampsText = srt, scriptText = scr)
    okS("script that does not match the timestamps -> 'script' warning", "script" in kinds(a))
    a = Analyser.analyse(timestampsText = srt, scriptText = Regex("Scene line number (\\d+) spoken here").findAll(srt).joinToString("\n") { it.value })
    okS("matching script -> no script warning", "script" !in kinds(a) && "script_line" !in kinds(a))

    // damaged picture -> black screen + warning, not a crash
    val bad = File(tmp, "bad"); pics(bad, listOf(1, 2)); File(bad, "3.jpg").writeText("this is not a picture")
    a = Analyser.analyse(timestampsText = srt, pictures = bad.listFiles()!!.map { it.path })
    okS("damaged picture 3: black screen, bad_pic warning, listed as left out", a.scenes[2].image == null && "bad_pic" in kinds(a) && a.extras.any { nm(it) == "3.jpg" })

    // numbers starting at 0
    val z0 = File(tmp, "z0"); pics(z0, (0..19).toList())
    a = Analyser.analyse(timestampsText = srt, pictures = z0.listFiles()!!.map { it.path })
    okS("pictures numbered 0..19: picture 0 on scene 1, no black screens", nm(a.scenes[0].image) == "0.jpg" && nm(a.scenes[19].image) == "19.jpg" && a.scenes.all { it.image != null })

    // wrong files are skipped, never fatal
    a = Analyser.analyse(timestampsText = "this is not a timestamps file", pictures = r.listFiles()!!.map { it.path })
    okS("a timestamps file with no cues is skipped with a warning; pictures still make scenes", a.scenes.size == 3 && "file" in kinds(a))
    a = Analyser.analyse(timestampsText = "1\n00:00:05,000 --> 00:00:06,000\nlater\n\n2\n00:00:01,000 --> 00:00:02,000\nearlier\n")
    okS("unsorted timestamps are sorted, with a warning", a.scenes[0].text == "earlier" && "srt" in kinds(a))
    a = Analyser.analyse(timestampsText = "1\n00:00:01,000 --> 00:00:05,000\nfirst\n\n2\n00:00:03,000 --> 00:00:06,000\nsecond\n")
    okS("overlapping timestamps warned", "overlap" in kinds(a))

    // YOUR case: 218 cues, 218 numbered pictures inside a ZIP -> picture N on cue N, every cut at its cue start (no early pictures)
    val (srt218, end218) = makeSrt(218, step = 2.3, gap = 0.31)
    val zipF = File(tmp, "pics.zip")
    ZipOutputStream(zipF.outputStream()).use { z -> for (k in 1..218) { val f = File(tmp, "tmp_$k.jpg"); pic(f, k); z.putNextEntry(ZipEntry("Flow images/$k.jpg")); z.write(f.readBytes()); z.closeEntry(); f.delete() } }
    val rows = Captions.parseRows(srt218, "a.srt")!!
    val t0 = System.nanoTime()
    a = Pipeline.analyse(srt218, "a.srt", null, listOf(zipF), File(tmp, "work"), null, end218)
    val ms = (System.nanoTime() - t0) / 1_000_000
    okS("218 cues + ZIP of 218 pictures: 218 scenes, picture N on scene N", a.scenes.size == 218 && a.scenes.withIndex().all { (i, s) -> nm(s.image) == "${i + 1}.jpg" })
    okS("no warnings at all for a perfect set (only the info summary)", a.warnings.isEmpty() && a.issues.single().kind == "summary")
    okS("every cut is exactly 0.06 s before its cue (never early by more, never late)", (1 until 218).all { Math.abs(rows[it].start - a.scenes[it].start - 0.06) < 1e-9 })
    okS("last scene runs to the audio end; total length = audio", Math.abs(a.duration - maxOf(end218, rows.last().end)) < 1e-9)
    println("INFO  218-cue analysis incl. unzipping took $ms ms")
    val tl = Timeline.fromScenes(a.scenes, a.duration)
    okS("timeline from it: valid, contiguous, covers the whole length", tl.validate().none { it.kind == IssueKind.GAP || it.kind == IssueKind.OVERLAP } && tl.clips.size == 218 && tl.endMs >= tl.audioMs - 1)
    val plan = ExportPlan.fromTimeline(tl)
    okS("export plan: 218 segments, total == audio length exactly", plan.segments.size == 218 && plan.totalMs == tl.audioMs && plan.segments.sumOf { it.durMs } == plan.totalMs)

    // folder source + warning cap
    val (srtBig, _) = makeSrt(300, step = 0.3, gap = 0.01)
    a = Analyser.analyse(timestampsText = srtBig)
    okS("warnings of one kind are capped (40 listed + one summary line)", a.issues.count { it.kind == "short_scene" } == Analyser.MAX_LISTED + 1 && a.issues.any { "more warnings of the same kind" in it.text })
    val fo = Pipeline.picturesFromFolder(r)
    okS("folder source lists pictures", fo.size == 3)

    // names
    okS("image numbers: 7.jpg, 007.png, Image 7.jpg, scene_7_final.png, '7 (1).jpg' -> 7", listOf("a/7.jpg", "a/007.png", "a/Image 7.jpg", "a/scene_7_final.png", "a/7 (1).jpg").all { Pics.imageNumber(it) == 7 })
    okS("image numbers: random names / dates / several numbers -> none", listOf("a/gen_xyz.png", "a/20260101.png", "a/img_3_v2.png", "a/ab12cd34.png").map { Pics.imageNumber(it) } == listOf(null, null, 3, null))
    println(if (failsS == 0) "ALL PASSED" else "$failsS FAILED"); if (failsS > 0) System.exit(1)
}
