import com.auto.core.*
import java.io.File

var fails10 = 0
fun ok10(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails10++ }
fun err10(f: () -> Unit): String? = try { f(); null } catch (e: PipelineError) { e.message }

/** args: port audioFile timingDir(with details.txt script.txt caps.srt) */
fun main(args: Array<String>) {
    val base = "http://127.0.0.1:${args[0]}"; val audio = File(args[1]); val dir = args[2]

    // ---- config validation
    ok10("http to a public host is refused", err10 { BackendConfig("http://example.com", "t").validate() }?.contains("https") == true)
    ok10("http to a LAN address is allowed", err10 { BackendConfig("http://192.168.1.20:8000", "t").validate() } == null)
    ok10("empty token refused", err10 { BackendConfig("https://x.example", " ").validate() }?.contains("token") == true)
    ok10("address without scheme refused", err10 { BackendConfig("x.example", "t").validate() } != null)

    // ---- result parsing
    val pr = BackendClient.parseResult(MiniJson("{\"segments\":[{\"words\":[{\"word\":\" Hi\",\"start\":0.5,\"end\":0.7,\"score\":0.9},{\"word\":\"there\",\"start\":0.8,\"end\":1.0}]}],\"speech\":[[0.4,1.1]],\"silences\":[],\"warnings\":[\"w\"],\"alignment\":\"whisperx\",\"caption_diff\":{\"ratio\":0.97},\"duration\":2.0}").parse() as Map<*, *>)
    ok10("parseResult: words trimmed + timed, speech, warnings, caption match", pr.words == listOf(TimedWord("Hi", 0.5, 0.7), TimedWord("there", 0.8, 1.0)) && pr.speech == listOf(Pair(0.4, 1.1)) && pr.warnings == listOf("w") && pr.captionMatch == 0.97 && pr.alignment == "whisperx")
    ok10("parseResult: no words -> clear error", err10 { BackendClient.parseResult(MiniJson("{\"segments\":[]}").parse() as Map<*, *>) }?.contains("no spoken words") == true)

    // ---- live server (real FastAPI app, fake speech models)
    val c = BackendClient(BackendConfig(base, "test-token"), retries = 1, readMs = 20000)
    val seen = ArrayList<String>()
    val r1 = c.analyze(audio, "a young boy enters a dark forest", "a young boy enters a dark forest", pollMs = 100) { s, _ -> seen.add(s) }
    ok10("live: 7 spoken words with real times", r1.words.size == 7 && r1.words[0].text == "a" && Math.abs(r1.words[0].start - 0.5) < 1e-6 && r1.words.zipWithNext().all { (a, b) -> a.end <= b.start + 1e-9 })
    ok10("live: first run uploads, status callbacks fired", r1.uploaded && seen.contains("uploading audio") && r1.duration > 1.0 && r1.captionMatch == 1.0)
    val r2 = c.analyze(audio, "a young boy enters a dark forest", "a young boy enters a dark forest", pollMs = 100)
    ok10("live: same audio is NOT uploaded again and result is reused", !r2.uploaded && r2.words == r1.words)
    ok10("live: wrong token -> message names the token", err10 { BackendClient(BackendConfig(base, "nope"), retries = 1).analyze(audio, pollMs = 100) }?.contains("token") == true)
    ok10("live: server-side failure surfaces as a readable error", err10 { c.analyze(audio, "FAIL", "", pollMs = 100) }?.contains("no words") == true)
    ok10("live: server not running -> 'Cannot reach'", err10 { BackendClient(BackendConfig("http://127.0.0.1:1", "t"), connectMs = 1000, retries = 1).analyze(audio) }?.contains("Cannot reach") == true)

    // ---- the timing fix: verified word times place every picture change at its first spoken word
    val scr = File("$dir/script.txt").readText()
    val items = scopedItems(Details.parseSceneFile(File("$dir/details.txt").readText()), scr, mutableListOf())
    val approx = Captions.parse(File("$dir/caps.srt").readText(), "caps.srt")
    // pretend the real speaker slows down after word 40 (the evenly-spread caption words cannot know this) and pauses inside later cues
    val truth = approx.mapIndexed { i, w -> val d = if (i >= 40) 0.9 else 0.0; TimedWord(w.text, w.start + d, w.end + d) }
    val dur = truth.last().end + 1.0
    // audio whose silences are in the WRONG places (every 2.1 s) - exactly what makes energy-based snapping move cuts
    val x = FloatArray((dur * 16000).toInt()) { k -> if ((k / 16000.0) % 2.1 < 1.7) (0.3 * Math.sin(k * 0.1)).toFloat() else 0.0f }
    val tv = timeScenes(items, scr, truth, x, dur, verified = true)
    val early = tv.scenes.drop(1).count { s -> truth.none { w -> w.start - s.start in -1e-6..0.0601 } }
    ok10("verified: every cut is at a spoken word, at most 0.06 s early (${tv.scenes.size - 1} cuts)", early == 0 && tv.scenes.size > 3)
    ok10("verified: no pause-snapping/anchoring applied or reported", tv.report?.anchored == false && tv.warnings.any { "exact spoken-word times" in it })
    val tu = timeScenes(items, scr, truth, x, dur, verified = false)
    val badU = tu.scenes.drop(1).count { s -> truth.none { w -> w.start - s.start in -1e-6..0.0601 } }
    println("INFO  old energy-snapping path on the same input: $badU of ${tu.scenes.size - 1} cuts NOT at a spoken word")
    ok10("verified: timeline built from it is valid (no gap/overlap)", Timeline.fromScenes(tv.scenes.onEachIndexed { k, s -> s.image = "/p/$k.png" }, dur).validate().none { it.kind == IssueKind.GAP || it.kind == IssueKind.OVERLAP })
    if (fails10 > 0) { println("$fails10 FAILED"); System.exit(1) }
}
