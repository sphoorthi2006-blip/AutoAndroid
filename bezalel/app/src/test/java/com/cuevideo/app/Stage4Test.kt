package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Expected numbers were checked against ffmpeg (ebur128 loudness, mix) and the spec, section 13. */
class Stage4Test {
    @Test fun clickTimes() {
        val bf = intArrayOf(0, 30, 60, 90, 120, 150, 180, 200)       // 7 cues at 30 fps
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), ClickPlan.times(bf, emptyList(), 30))
        // clip on cues 3-4: no clicks inside, one at the clip start, the next cue clicks as usual
        assertEquals(listOf(1.0, 2.0, 4.0, 5.0, 6.0), ClickPlan.times(bf, listOf(Pair(3, 4)), 30))
        // clip on cues 1-2: no click at 0 and none for the clip itself
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0, 6.0), ClickPlan.times(bf, listOf(Pair(1, 2)), 30))
    }

    @Test fun loudnessOfSineMatchesFfmpeg() {
        // stereo 1 kHz sine, amplitude 0.2: ffmpeg ebur128 gives -13.98 LUFS (checked via 0.025 -> -32.04)
        val m = LoudnessMeter(44100, 2)
        val x = FloatArray(441000) { (0.2 * sin(2 * PI * 1000 * it / 44100.0)).toFloat() }
        m.push(arrayOf(x, x), x.size)
        assertEquals(-13.97, m.result()!!, 0.05)
    }

    @Test fun silenceHasNoLoudness() {
        val m = LoudnessMeter(44100, 2)
        val z = FloatArray(88200)
        m.push(arrayOf(z, z), z.size)
        assertEquals(null, m.result())
    }

    @Test fun clickTrimFadeAndGain() {
        // 0.1 s of silence, 0.05 s of tone at 0.5, 0.2 s of silence
        val x = FloatArray(44100 / 10 * 0 + 15435) { i ->
            if (i in 4410 until 6615) (0.5 * sin(2 * PI * 800 * i / 44100.0)).toFloat() else 0f
        }
        val c = ClickPrep.prepare(x)
        // starts at the first loud sample, ends 0.03 s after the last loud one
        assertTrue(c.samples.size in 2200..2205 + 1323)
        assertEquals(0f, c.samples.last(), 1e-6f)                       // faded to zero
        assertTrue(c.peak <= 0.5 + 1e-6)
        val g = ClickPrep.gainDb(c, -20.0)
        val rmsDb = 20 * kotlin.math.log10(c.rms)
        assertEquals(minOf(-20.0 - 14.0 - rmsDb, -3.0 - 20 * kotlin.math.log10(c.peak)), g, 1e-9)
    }

    @Test fun limiterKeepsCeilingAndLength() {
        val n = 44100 * 3
        val l = FloatArray(n) { (1.6 * sin(2 * PI * 300 * it / 44100.0)).toFloat() }
        val lim = Limiter(0.95)
        val a = lim.process(l, l, n)
        val b = lim.finish()
        assertEquals(n, a.first.size + b.first.size)
        var peak = 0.0
        for (v in a.first + b.first) peak = max(peak, abs(v.toDouble()))
        assertTrue(peak <= 0.95 + 1e-6)
    }

    @Test fun limiterLeavesQuietSoundAlone() {
        val q = FloatArray(10000) { (0.5 * sin(it * 0.05)).toFloat() }
        val lim = Limiter(0.95)
        val out = lim.process(q, q, q.size).first + lim.finish().first
        assertEquals(q.size, out.size)
        for (i in q.indices) assertEquals(q[i], out[i], 1e-6f)
    }

    @Test fun mixLengthAndClickPosition() {
        // voice-over file: 2 s of silence; click track puts one click at 1.0 s
        val f = File.createTempFile("voice", ".pcm")
        f.writeBytes(ByteArray(44100 * 2 * 4))
        val click = FloatArray(100) { 0.5f }
        val got = ArrayList<Short>()
        val ok = AudioMix.mix(f, click, 1.0, listOf(1.0), 3.0, { s, n -> for (i in 0 until n * 2) got.add(s[i]) })
        f.delete()
        assertTrue(ok)
        assertEquals(3 * 44100, got.size / 2)                          // total length, voice shorter -> padded
        assertEquals(0, got[2 * 44099].toInt())
        assertEquals(Math.rint(0.5 * 32767).toInt(), got[2 * 44100].toInt())
        assertEquals(got[2 * 44100], got[2 * 44100 + 1])                // both channels equal
        assertEquals(0, got[2 * (44100 + 100)].toInt())
    }

    @Test fun clickOnlyMixWorksWithoutVoiceOver() {
        val click = FloatArray(120) { 0.4f }
        val samples = ArrayList<Short>()
        val ok = AudioMix.mix(null, click, 1.0, listOf(0.5), 1.0, { pcm, n ->
            for (i in 0 until n * 2) samples.add(pcm[i])
        })
        assertTrue(ok)
        assertEquals(44100, samples.size / 2)
        assertTrue(samples.drop(2 * 22050).any { it.toInt() != 0 })
    }

    @Test fun voiceOnlyMixWorksWithoutClickTrack() {
        val f = File.createTempFile("voice-only", ".pcm")
        f.writeBytes(ByteArray(44100 * 4))
        val samples = ArrayList<Short>()
        val ok = AudioMix.mix(f, FloatArray(0), 1.0, emptyList(), 1.0, { pcm, n ->
            for (i in 0 until n * 2) samples.add(pcm[i])
        })
        f.delete()
        assertTrue(ok)
        assertEquals(44100, samples.size / 2)
        assertTrue(samples.all { it.toInt() == 0 })
    }

    @Test fun resamplerKeepsTone() {
        val rs = Resampler(48000, 44100)
        val sine = FloatArray(48000) { (0.5 * sin(2 * PI * 1000 * it / 48000.0)).toFloat() }
        val o = rs.push(sine, sine.size) + rs.push(FloatArray(0), 0, true)
        assertEquals(44100, o.size)
        var err = 0.0
        for (i in 200 until 44000) err = max(err, abs(o[i] - 0.5 * sin(2 * PI * 1000 * i / 44100.0)))
        assertTrue(err < 1e-3)
    }

    @Test fun wavReaderRoundTrip() {
        val pcm = ByteArray(8) { it.toByte() }
        val h = java.io.ByteArrayOutputStream()
        fun w32(v: Int) { for (k in 0..3) h.write((v shr (8 * k)) and 0xFF) }
        fun w16(v: Int) { h.write(v and 0xFF); h.write((v shr 8) and 0xFF) }
        h.write("RIFF".toByteArray()); w32(36 + pcm.size); h.write("WAVE".toByteArray())
        h.write("fmt ".toByteArray()); w32(16); w16(1); w16(2); w32(44100); w32(44100 * 4); w16(4); w16(16)
        h.write("data".toByteArray()); w32(pcm.size); h.write(pcm)
        var frames = 0
        var rate = 0
        WavStream.read(h.toByteArray().inputStream()) { ch, n, r -> frames += n; rate = r; assertEquals(2, ch.size) }
        assertEquals(2, frames)
        assertEquals(44100, rate)
    }
}
