package com.cuevideo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The audio mixer (background music): loop, levelling, ducking, any voice. */
class BgmMixTest {
    /** 16-bit stereo 44100 Hz test sound: a sine whose loudness is amp(t). */
    private fun pcm(seconds: Double, freq: Double = 220.0, amp: (Double) -> Double): File {
        val f = File.createTempFile("bgmtest", ".pcm")
        f.deleteOnExit()
        val n = (seconds * MIX_RATE).toInt()
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val t = i.toDouble() / MIX_RATE
            val v = (amp(t) * sin(2 * PI * freq * t) * 32767).toInt().toShort()
            bb.putShort(v); bb.putShort(v)
        }
        f.writeBytes(bb.array())
        return f
    }

    @Test fun loopHasNoJumpAtTheSeam() {
        val c = BgmPrep.build(pcm(20.0) { 0.2 })
        assertEquals(17.0, c.seconds, 0.2)                        // 20 s minus a 3 s cross-fade
        assertTrue(abs(c.l[0] - c.l[c.frames - 1]) < 1000)        // a sine never steps further than this
    }

    @Test fun aLoudSwellIsTurnedDown() {
        // quiet 70 s track with a loud swell from 30 s to 50 s
        val c = BgmPrep.build(pcm(70.0) { if (it in 30.0..50.0) 0.35 else 0.05 })
        val quiet = c.levelLin[100]            // 10 s
        val swell = c.levelLin[400]            // 40 s
        assertTrue("swell should be cut at least 8 dB below the quiet part", swell < quiet * 0.4)
    }

    @Test fun silenceAndFadeOutAtTheEndAreTrimmed() {
        val c = BgmPrep.build(pcm(40.0) { if (it > 34.0) 0.2 * (40.0 - it) / 6.0 else 0.2 })
        assertTrue(c.trimmedEnd > 0.8)               // the near-silent end of the fade is cut; the musical decay is kept
    }

    @Test fun musicComesDownForSpeechAndUpInARealPause() {
        // speech 0-2 s, silence 2-4 s, repeated for 20 s
        val v = VoiceAnalysis.analyse(pcm(20.0) { if (it % 4.0 < 2.0) 0.2 else 0.0 })
        assertEquals(1.0, v.riseLin[20].toDouble(), 0.05)         // 1 s: speaking
        assertTrue("pause should let the music rise", v.riseLin[70] > 1.3)   // 3.5 s: 1 s into a pause
        assertEquals(1.0, v.levelLin[20].toDouble(), 0.05)        // a steady voice is left alone
    }

    @Test fun aShortGapDoesNotPump() {
        // 0.4 s gaps between words
        val v = VoiceAnalysis.analyse(pcm(20.0) { if (it % 2.0 < 1.6) 0.2 else 0.0 })
        assertTrue(v.riseLin.all { it < 1.15 })
    }

    @Test fun quietAndLoudVoicesAreDetectedAlike() {
        val loud = VoiceAnalysis.analyse(pcm(20.0) { if (it % 4.0 < 2.0) 0.5 else 0.0 })
        val quiet = VoiceAnalysis.analyse(pcm(20.0) { if (it % 4.0 < 2.0) 0.02 else 0.0 })
        assertEquals(loud.speechBlocks, quiet.speechBlocks)
        assertTrue(loud.speechBlocks > 100)
    }

    @Test fun musicFadesInAndOut() {
        val c = BgmPrep.build(pcm(20.0) { 0.2 })
        val layer = BgmLayer(c, 1.0)
        val total = 10L * MIX_RATE
        val l = FloatArray(total.toInt())
        val r = FloatArray(total.toInt())
        layer.mixInto(l, r, total.toInt(), 0L, total, VoiceGains(FloatArray(0), FloatArray(0), 0))
        assertTrue(abs(l[0]) < 0.001f)
        assertTrue((l.copyOfRange(MIX_RATE * 4, MIX_RATE * 5).maxOf { abs(it) }) > 0.05f)
        assertTrue(abs(l[l.size - 1]) < 0.001f)
    }

    @Test fun voiceIsLeftAloneWithoutMusicDip() {
        val c = BgmPrep.build(pcm(20.0) { 0.2 })
        val layer = BgmLayer(c, 0.0)                              // music silent: only the narration is processed
        val l = FloatArray(MIX_RATE) { 0.1f }
        val r = FloatArray(MIX_RATE) { 0.1f }
        layer.mixInto(l, r, MIX_RATE, 5L * MIX_RATE, 20L * MIX_RATE, VoiceGains(FloatArray(0), FloatArray(0), 0))
        assertEquals(0.1f, l[100], 0.005f)
    }
}
