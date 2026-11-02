import com.auto.core.*
import kotlin.math.PI
import kotlin.math.sin
var fails17 = 0
fun ok17(name: String, cond: Boolean) { println((if (cond) "PASS " else "FAIL ") + name); if (!cond) fails17++ }
fun n17(a: Float, b: Float, e: Float = 2e-3f) = Math.abs(a - b) <= e
fun tl(): Timeline { val sc = listOf(Scene("a", 0.0, 4.0), Scene("b", 4.0, 8.0)).onEachIndexed { k, s -> s.num = k + 1; s.image = "/p/${k + 1}.png" }; return Timeline.fromScenes(sc, 8.0) }

/** Step 15: audio gain / fades / waveform / mix persistence. Step 16: caption import, export, edit. */
fun main() {
    // ---- gain
    val s = AudioSettings(volume = 1f, fadeInMs = 1000, fadeOutMs = 1000)
    ok17("fade-in ramp 0 -> .5 -> 1", AudioMath.gain(s, 0, 10_000) == 0f && n17(AudioMath.gain(s, 500, 10_000), 0.5f) && AudioMath.gain(s, 1000, 10_000) == 1f)
    ok17("fade-out ramp 1 -> .5 -> 0", AudioMath.gain(s, 9000, 10_000) == 1f && n17(AudioMath.gain(s, 9500, 10_000), 0.5f) && AudioMath.gain(s, 10_000, 10_000) == 0f)
    ok17("volume multiplies, up to 2", n17(AudioMath.gain(AudioSettings(volume = 0.5f), 100, 1000), 0.5f) && AudioMath.gain(AudioSettings(volume = 9f).clamped(), 100, 1000) == 2f)
    ok17("mute = silence", AudioMath.gain(AudioSettings(muted = true), 100, 1000) == 0f)
    ok17("dB: gain 2 = +6 dB, 0.5 = -6 dB", n17(AudioMath.db(2f), 6.02f, 0.05f) && n17(AudioMath.db(0.5f), -6.02f, 0.05f))
    ok17("fades longer than the clip never exceed volume", (0..1000 step 50).all { AudioMath.gain(AudioSettings(fadeInMs = 5000, fadeOutMs = 5000), it.toLong(), 1000) <= 1f })
    val pcm = ShortArray(2000) { 10000 }   // 1000 stereo frames at 1000 Hz = 1 s
    AudioMath.applyGain(pcm, pcm.size, 2, 1000, 0, 1000, AudioSettings(fadeInMs = 500))
    ok17("applyGain: first frame silent, frame 250 about half, frame 600 untouched, L and R equal", pcm[0] == 0.toShort() && Math.abs(pcm[500] - 5000) < 60 && pcm[1200] == 10000.toShort() && (0 until 1000).all { pcm[it * 2] == pcm[it * 2 + 1] })
    val p2 = ShortArray(4) { 30000 }; AudioMath.applyGain(p2, 4, 1, 1000, 0, 4, AudioSettings(volume = 2f))
    ok17("applyGain clips instead of wrapping around", p2.all { it == 32767.toShort() })
    val half = ShortArray(1000) { 10000 }; AudioMath.applyGain(half, 1000, 1, 1000, 500, 1000, AudioSettings(fadeOutMs = 500))   // 2nd half of a 1 s item
    ok17("applyGain: a buffer from the middle of an item continues the fade (frame 500 = full, last = ~0)", half[0] > 9900 && half[999] < 100)
    // ---- waveform
    val x = FloatArray(16000) { if (it < 8000) 0f else (0.2 * sin(2 * PI * 220 * it / 16000.0)).toFloat() }
    val pk = Waveform.peaks(x, 10)
    ok17("peaks: 10 buckets, quiet first half = 0, loud second half ~1, max exactly 1", pk.size == 10 && pk.take(5).all { it == 0f } && pk.drop(5).all { it > 0.9f } && pk.max() == 1f)
    ok17("peaks: empty or silent audio -> zeros, no division by zero", Waveform.peaks(FloatArray(0), 5).all { it == 0f } && Waveform.peaks(FloatArray(100), 5).all { it == 0f })
    ok17("resample keeps the loudest value when zooming out", Waveform.resample(floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f), 2).toList() == listOf(0.9f, 0.3f))
    ok17("bucketsFor", Waveform.bucketsFor(900) == 300 && Waveform.bucketsFor(0) == 1)
    // ---- mix persistence
    val mix = AudioMix(AudioSettings(0.8f, 300, 600, false), MusicTrack("/m/a.mp3", "a.mp3", AudioSettings(0.2f, 1000, 2000, false, 5000), false, 90_000))
    val t = tl().setMix(mix)
    ok17("mix JSON round trip", Timeline.Json.parse(t.toJson()) == t)
    ok17("setMix: narration is never trimmed", tl().setMix(AudioMix(AudioSettings(trimStartMs = 4000))).mix.narration.trimStartMs == 0L)
    ok17("default mix writes nothing (old files unchanged)", !tl().toJson().contains("\"mix\"") && Timeline.Json.parse(tl().toJson()).mix.isDefault)
    ok17("mix travels in the export plan", ExportPlan.fromTimeline(t).mix == t.mix)
    ok17("music defaults: quiet (0.35), loops", MusicTrack("/x", "x").let { it.settings.volume == 0.35f && it.loop })
    // ---- captions: parse / write
    val srt = "1\n00:00:01,000 --> 00:00:03,500\nHello world.\n\n2\n00:00:04,000 --> 00:00:06,000\nSecond line\nwraps here\n\n3\n00:01:02,250 --> 00:01:03,000\nLast.\n"
    val c = CaptionTools.parse(srt, "a.srt")
    ok17("SRT: 3 cues, ms exact, two-line cue joined", c.size == 3 && c[0].startMs == 1000L && c[0].endMs == 3500L && c[1].text == "Second line wraps here" && c[2].startMs == 62_250L)
    ok17("toSrt -> parse gives the same captions", CaptionTools.parse(CaptionTools.toSrt(c), "x.srt") == c)
    ok17("toSrt format", CaptionTools.toSrt(c).startsWith("1\n00:00:01,000 --> 00:00:03,500\nHello world.\n\n2\n"))
    val vtt = CaptionTools.toVtt(c)
    ok17("toVtt: header, dots not commas, parses back", vtt.startsWith("WEBVTT\n\n00:00:01.000 --> 00:00:03.500") && CaptionTools.parse(vtt, "x.vtt") == c)
    ok17("hours stamp", CaptionTools.stamp(3_723_456, true) == "01:02:03,456")
    ok17("no captions in file -> clear error", try { CaptionTools.parse("just words", "a.srt"); false } catch (e: PipelineError) { e.message!!.contains("No captions") || e.message!!.contains("No timestamps") })
    ok17("Whisper JSON words become captions", CaptionTools.parse("{\"segments\":[{\"start\":0,\"end\":2,\"text\":\"Hi there. Bye now.\"}]}", "a.json").isNotEmpty())
    // ---- caption edits
    var t2 = tl().withCaptions(c)
    t2 = t2.splitCaption(1, 2250)
    ok17("split: words shared by time, no word cut, contiguous", t2.captions.size == 4 && t2.captions[0].text == "Hello" && t2.captions[1].text == "world." && t2.captions[0].endMs == 2250L && t2.captions[1].startMs == 2250L && t2.captions[1].endMs == 3500L)
    ok17("split of a one-word caption or near the edge = unchanged", t2.splitCaption(t2.captions[0].id, 1500) == t2 && t2.splitCaption(t2.captions[0].id, 1010) == t2)
    val merged = t2.mergeCaptionWithNext(t2.captions[0].id)
    ok17("merge: text joined, spans both, one caption fewer", merged.captions.size == 3 && merged.captions[0].text == "Hello world." && merged.captions[0].endMs == 3500L)
    ok17("merge on the last caption = unchanged", merged.mergeCaptionWithNext(merged.captions.last().id) == merged)
    ok17("shift +500 moves all", tl().withCaptions(c).shiftCaptions(500).captions.map { it.startMs } == listOf(1500L, 4500L, 62_750L))
    ok17("shift -5000 stops at 0 for the first caption (keeps spacing)", tl().withCaptions(c).shiftCaptions(-5000).captions.map { it.startMs } == listOf(0L, 3000L, 61_250L))
    ok17("add / delete / edit", tl().addCaption(500, 1500, "new").captions.single().text == "new" && tl().withCaptions(c).deleteCaption(2).captions.size == 2 && tl().withCaptions(c).editCaption(1, text = "Changed", startMs = 1200).captions[0].let { it.text == "Changed" && it.startMs == 1200L })
    ok17("delete unknown id -> error", try { tl().deleteCaption(9); false } catch (e: IllegalArgumentException) { true })
    ok17("check(): overlap, empty, too fast", CaptionTools.check(listOf(CaptionClip(1, 0, 1000, "ok"), CaptionClip(2, 900, 1900, ""), CaptionClip(3, 5000, 5500, "x".repeat(40)))).let { r -> r.size == 3 && r.any { "overlap" in it } && r.any { "empty" in it } && r.any { "too fast" in it } })
    ok17("clean captions give no warnings", CaptionTools.check(c).isEmpty())
    ok17("caption edits are undoable", TimelineEditor(tl().withCaptions(c)).let { e -> e.edit { it.splitCaption(1, 2250) }; e.undo() == tl().withCaptions(c) })
    println(if (fails17 == 0) "ALL PASS" else "FAILED $fails17"); if (fails17 > 0) kotlin.system.exitProcess(1)
}
