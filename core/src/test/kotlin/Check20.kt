import com.auto.core.*
import java.io.File
import kotlin.math.abs

var fails20 = 0
fun ok20(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails20++ }

fun scenes20(vararg ms: Long): Timeline {
    val sc = ms.toList().zipWithNext().mapIndexed { k, (a, b) -> Scene("s$k", a / 1000.0, b / 1000.0).also { it.num = k + 1; it.image = "/p/${k + 1}.png" } }
    return Timeline.fromScenes(sc, ms.last() / 1000.0)
}

/** Step 42: click sound at every picture change. args: [click.wav] [master.srt] */
fun main(args: Array<String>) {
    val wavPath = args.getOrNull(0) ?: "app/src/main/res/raw/click.wav"
    val srtPath = args.getOrNull(1) ?: "testdata/click/master.srt"
    val wav = WavFile.parse(File(wavPath).readBytes())

    // ---- the real click file
    ok20("click.wav: 44.1 kHz stereo, 405 ms (the light trim the owner chose)", wav.sampleRate == 44100 && wav.channels == 2 && wav.durationMs in 404..406)
    val peak = wav.samples.maxOf { abs(it.toInt()) }
    ok20("click.wav: peak about -2 dBFS", peak in 25000..27000)
    val firstHit = wav.samples.indices.first { abs(wav.samples[it].toInt()) > peak / 100 } / 2 * 1000.0 / wav.sampleRate
    ok20("click.wav: the hit starts at ~12 ms (almost no dead time before it)", firstHit in 8.0..16.0)
    ok20("WAV reader rejects garbage", runCatching { WavFile.parse(ByteArray(50)) }.isFailure)
    val hdr = WavFile.header(44100, 1, 44100)
    ok20("silent-track header: valid, parses back to 1 s mono", WavFile.parse(hdr + ByteArray(88200)).let { it.channels == 1 && it.frames == 44100 })

    // ---- when: the owner's real master.srt (one picture per cue)
    val cues = Captions.parseRows(File(srtPath).readText(), "master.srt")!!
    val tl = scenes20(*(cues.map { Math.round(it.start * 1000) } + Math.round(cues.last().end * 1000)).toLongArray().toTypedArray().toLongArray())
    val plan = ExportPlan.fromTimeline(tl)
    val starts = ClickTrack.startsMs(plan)
    ok20("master.srt: ${cues.size} cues -> ${cues.size} clicks", starts.size == cues.size && cues.size == 51)
    ok20("first click is at 00:00:00,000 (start of the audio)", starts[0] == 0L)
    ok20("every click is exactly on its cue start", cues.indices.all { starts[it] == Math.round(cues[it].start * 1000) })
    val minGap = starts.toList().zipWithNext { a, b -> b - a }.min()
    ok20("shortest cue ($minGap ms, 'A Pharisee.') is longer than the click (${wav.durationMs} ms): no click can reach the next one", minGap > wav.durationMs)

    // ---- when: edge cases
    val t2 = scenes20(0, 4000, 8000)
    ok20("two scenes -> clicks at 0 and 4000", ClickTrack.startsMs(ExportPlan.fromTimeline(t2)).toList() == listOf(0L, 4000L))
    val gapTl = t2.setLength(2, 2000).move(2, 5000)     // picture 2 now 5000..7000, blank gap 4000..5000
    val gapPlan = ExportPlan.fromTimeline(gapTl)
    ok20("moving a scene moves its click (nothing is baked)", ClickTrack.startsMs(gapPlan).toList() == listOf(0L, 5000L))
    ok20("a blank gap (no picture) gets no click of its own", gapPlan.segments.any { it.sceneNum == null } && ClickTrack.startsMs(gapPlan).size == 2)
    ok20("a scene that starts after the narration ends is not exported and has no click", ClickTrack.startsMs(ExportPlan.fromTimeline(scenes20(0, 3000, 6000, 9000).copy(audioMs = 4000))).toList() == listOf(0L, 3000L))
    ok20("preview index: playing from 0 includes the click at 0; scrubbing/seeking to a start does not repeat it", ClickTrack.firstIndex(starts, 0, true) == 0 && ClickTrack.firstIndex(starts, 0, false) == 1)
    ok20("preview index: mid-scene = next start", ClickTrack.firstIndex(longArrayOf(0, 4000, 8000), 4001, true) == 2 && ClickTrack.firstIndex(longArrayOf(0, 4000, 8000), 4000, true) == 1)

    // ---- prepare (rate / channels / volume)
    val full = ClickTrack.prepare(wav, 44100, 2, 1f)
    ok20("prepare at the same rate/channels/volume 1 = the file itself", full.contentEquals(wav.samples))
    val half = ClickTrack.prepare(wav, 44100, 2, 0.5f)
    ok20("prepare volume 0.5 halves the peak", abs(half.maxOf { abs(it.toInt()) } - peak / 2) <= 1)
    val mono24 = ClickTrack.prepare(wav, 24000, 1, 1f)
    ok20("prepare to 24 kHz mono keeps the length (405 ms) and the sound", abs(mono24.size - 405 * 24) <= 30 && mono24.maxOf { abs(it.toInt()) } > peak / 2)
    ok20("prepare to mono: the hit stays at the same time", mono24.indices.first { abs(mono24[it].toInt()) > peak / 100 } * 1000.0 / 24000 in 8.0..16.0)

    // ---- mixInto: exact positions on a silent narration
    val rate = 44100; val ch = 2; val total = rate * 4
    val nar = ShortArray(total * ch)
    val cl = ClickTrack.prepare(wav, rate, ch, 0.5f)
    val st = longArrayOf(0, rate.toLong(), 2L * rate)
    ClickTrack.mixInto(nar, nar.size, ch, 0, cl, st, rate)
    ok20("click at frame 0 is exactly the prepared click", (cl.indices).all { nar[it] == cl[it] })
    ok20("click at 1.000 s starts exactly at frame 44100", (cl.indices).all { nar[rate * ch + it] == cl[it] })
    ok20("silence stays silent between clicks (1.5 s .. 2.0 s)", (rate * 3 / 2 * ch until 2 * rate * ch).all { nar[it] == 0.toShort() })
    // any buffer split gives the same result as one buffer (the Transformer hands over odd-sized buffers)
    val nar2 = ShortArray(total * ch); var pos = 0
    for (sz in generateSequence(997) { (it * 7 + 13) % 6000 + 500 }.take(2000)) { if (pos >= total) break; val n = minOf(sz, total - pos); val buf = ShortArray(n * ch); ClickTrack.mixInto(buf, buf.size, ch, pos.toLong(), cl, st, rate); System.arraycopy(buf, 0, nar2, pos * ch, buf.size); pos += n }
    ok20("buffer by buffer (odd sizes, clicks straddling buffer edges) == one big buffer", nar.contentEquals(nar2))

    // ---- never runs into the next click
    val tight = longArrayOf(0, 4410)   // second click 100 ms later; the click is 405 ms
    val a = ShortArray(rate * ch); ClickTrack.mixInto(a, a.size, ch, 0, cl, tight, rate)
    val b = ShortArray(rate * ch); ClickTrack.mixInto(b, b.size, ch, 0, cl, longArrayOf(4410), rate)
    ok20("a click that would run into the next one is cut at the next start", (4410 * ch until 4410 * ch + cl.size).all { a[it] == b[it] })
    ok20("... with a short fade: the last frame before the next click is (almost) silent", abs(a[(4410 - 1) * ch].toInt()) < 400 && abs(a[(4410 - 1) * ch + 1].toInt()) < 400)

    // ---- no wrap-around
    val loud = ShortArray(rate * ch) { 32000 }; ClickTrack.mixInto(loud, loud.size, ch, 0, ClickTrack.prepare(wav, rate, ch, 1f), longArrayOf(0), rate)
    ok20("loud narration (32000) + full-volume click never wraps around to negative", loud.all { it > 0 })
    ok20("... and the loudest sample the click adds stays within full scale", loud.maxOf { it.toInt() } <= 32767)
    val quiet = ShortArray(rate * ch) { 1000 }; ClickTrack.mixInto(quiet, quiet.size, ch, 0, cl, longArrayOf(0), rate)
    ok20("quiet narration + click is the plain sum (limiter does not touch normal levels)", (cl.indices).all { quiet[it].toInt() == 1000 + cl[it] })

    // ---- settings / project file / plan
    ok20("click is ON by default at volume 50%", ClickSettings().let { it.enabled && it.volume == 0.5f })
    ok20("default writes nothing; an old project file loads with the click ON", !tl.toJson().contains("click") && Timeline.Json.parse(tl.toJson()).mix.click.enabled)
    val off = tl.setMix(tl.mix.copy(click = ClickSettings(enabled = false, volume = 0.3f)))
    ok20("click OFF + volume survive save and load", Timeline.Json.parse(off.toJson()).mix.click == ClickSettings(false, 0.3f))
    ok20("setMix keeps the click setting and clamps the volume to 0..1", tl.setMix(AudioMix(click = ClickSettings(true, 5f))).mix.click.volume == 1f)
    ok20("other mix edits keep the click (narration volume change)", off.setMix(off.mix.copy(narration = AudioSettings(volume = 0.8f))).mix.click == ClickSettings(false, 0.3f))
    ok20("click setting travels in the export plan", ExportPlan.fromTimeline(off).mix.click == ClickSettings(false, 0.3f))
    ok20("RenderCaps: click is offered (Beta, not yet seen on a phone), in the exporter's list", RenderCaps.available("click") && RenderCaps.isBeta("click") && "click" in RenderCaps.exporterImplements)
    ok20("RenderCaps.used lists the click when ON and not when OFF", "click" in RenderCaps.used(plan, true) && "click" !in RenderCaps.used(ExportPlan.fromTimeline(off), true))
    println(if (fails20 == 0) "ALL PASS" else "FAILED $fails20")
    if (fails20 != 0) System.exit(1)
}
