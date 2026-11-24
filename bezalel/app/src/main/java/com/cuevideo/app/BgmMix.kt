package com.cuevideo.app

import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Background music ("audio mixer"). Pure maths, no Android code, so it is tested on the build server.
 *
 * One bundled track is repeated to cover the whole video. It is levelled (its own loud swells are
 * turned down, its quiet parts lifted a little), set a fixed distance below the voice-over and
 * ducked while the voice speaks. The narration is gently levelled too. Nothing is tied to one voice:
 * every level is measured from the voice-over that is being mixed.
 */

// ------------------------------------------------------------------ the list the user picks from

data class BgmTrack(val id: String, val title: String, val note: String, val asset: String)

object BgmCatalog {
    val tracks = listOf(
        BgmTrack("steady", "Steady Finance Explainer", "Even and steady. Best all-round choice.", "bgm/steady_finance_explainer.mp3"),
        BgmTrack("soft", "Soft Piano", "Gentle piano with two louder swells.", "bgm/soft_piano.mp3"),
        BgmTrack("stillness", "Stillness Piano", "Slow piano with one big swell in the middle.", "bgm/stillness_piano.mp3"),
        BgmTrack("sacred", "Sacred Silence", "Calm and spacious, with several swells.", "bgm/sacred_silence.mp3"),
        BgmTrack("impact", "Impact", "Very quiet, steady background.", "bgm/impact.m4a"),
    )

    fun byId(id: String?): BgmTrack? = tracks.firstOrNull { it.id == id }
}

object BgmDefaults {
    /** Music sits this many LU under the voice-over while someone is speaking. */
    const val LEVEL_BELOW_VOICE = 20.0
    const val MIN_LEVEL = 12.0
    const val MAX_LEVEL = 32.0
    /** In a real pause the music comes up by this much. */
    const val PAUSE_RISE_DB = 4.0
    /** Loudness of the finished mix whenever music is on. */
    const val TARGET_LUFS = -16.0
    const val FADE_IN_SEC = 1.5
    const val FADE_OUT_SEC = 3.0
    const val CROSSFADE_SEC = 3.0
}

fun dbToLin(db: Double): Double = 10.0.pow(db / 20.0)

private fun db10(x: Double): Double = 10.0 * log10(max(x, 1e-12))

/** Moving average, window [width] centred on each point, edges use the nearest value. */
internal fun movingAvg(x: DoubleArray, width: Int): DoubleArray {
    val n = x.size
    if (n == 0 || width <= 1) return x.copyOf()
    val pre = DoubleArray(n + 1)
    for (i in 0 until n) pre[i + 1] = pre[i] + x[i]
    val half = width / 2
    return DoubleArray(n) { k ->
        val a = k - half
        val b = a + width                   // exclusive
        // clamp by repeating the edge value
        var s = 0.0
        val lo = max(a, 0)
        val hi = min(b, n)
        s += pre[hi] - pre[lo]
        if (a < 0) s += -a * x[0]
        if (b > n) s += (b - n) * x[n - 1]
        s / width
    }
}

/** Moving average that wraps round (for a looped sound). */
internal fun movingAvgCircular(x: DoubleArray, width: Int): DoubleArray {
    val n = x.size
    if (n == 0 || width <= 1) return x.copyOf()
    val w = min(width, n)
    val half = w / 2
    val out = DoubleArray(n)
    var s = 0.0
    for (j in -half until -half + w) s += x[Math.floorMod(j, n)]
    out[0] = s / w
    for (k in 1 until n) {
        s += x[Math.floorMod(k - half + w - 1, n)] - x[Math.floorMod(k - half - 1, n)]
        out[k] = s / w
    }
    return out
}

internal fun median(v: List<Double>): Double {
    if (v.isEmpty()) return 0.0
    val s = v.sorted()
    return s[s.size / 2]
}

private fun lerpAt(a: FloatArray, pos: Long, step: Int, outside: Double): Double {
    val k = (pos / step).toInt()
    if (k >= a.size) return outside
    val f = (pos % step).toDouble() / step
    val v0 = a[k].toDouble()
    val v1 = if (k + 1 < a.size) a[k + 1].toDouble() else outside
    return v0 + (v1 - v0) * f
}

// ------------------------------------------------------------------ the voice-over

/**
 * What the voice-over asks of the mix, in 50 ms blocks:
 * [levelLin] gentle levelling of the narration, [riseLin] how far the music may come up (1.0 while
 * someone is speaking, up to +4 dB in a real pause).
 */
class VoiceGains(val levelLin: FloatArray, val riseLin: FloatArray, val speechBlocks: Int) {
    fun level(p: Long): Double = lerpAt(levelLin, p, VoiceAnalysis.BLOCK, 1.0)
    fun rise(p: Long): Double = lerpAt(riseLin, p, VoiceAnalysis.BLOCK, dbToLin(BgmDefaults.PAUSE_RISE_DB))
}

object VoiceAnalysis {
    const val BLOCK = MIX_RATE / 20                  // 50 ms
    private const val LOOKAHEAD = 3                  // music goes down 150 ms before a word
    private const val HOLD = 10                      // and stays down 500 ms after it: short pauses do not pump
    private const val ATTACK_SEC = 0.15
    private const val RELEASE_SEC = 0.7
    private const val SPEECH_BELOW_PEAK_DB = 22.0    // speech = within 22 dB of this voice's own loud level

    /** [pcm] is the voice-over as 16-bit stereo 44100 Hz; null means no voice-over. */
    fun analyse(pcm: File?): VoiceGains {
        if (pcm == null || !pcm.exists() || pcm.length() < 4) {
            return VoiceGains(FloatArray(0), FloatArray(0), 0)
        }
        val n = ((pcm.length() / 4 + BLOCK - 1) / BLOCK).toInt()
        val ms = DoubleArray(n)
        val raw = ByteArray(BLOCK * 4)
        FileInputStream(pcm).buffered(1 shl 16).use { ins ->
            for (b in 0 until n) {
                var got = 0
                while (got < raw.size) {
                    val k = ins.read(raw, got, raw.size - got)
                    if (k < 0) break
                    got += k
                }
                val frames = got / 4
                var sum = 0.0
                for (i in 0 until frames) {
                    val o = i * 4
                    val l = ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8)).toShort() / 32768.0
                    val r = ((raw[o + 2].toInt() and 0xFF) or (raw[o + 3].toInt() shl 8)).toShort() / 32768.0
                    sum += l * l + r * r
                }
                ms[b] = if (frames > 0) sum / (2.0 * frames) else 0.0
            }
        }
        val db = DoubleArray(n) { db10(ms[it]) }

        // speech = louder than (this voice's own p95 level - 22 dB); works for quiet and loud voices alike
        val live = db.filter { it > -80.0 }.sorted()
        val thr = if (live.isEmpty()) Double.POSITIVE_INFINITY else max(live[(live.size * 0.95).toInt().coerceAtMost(live.size - 1)] - SPEECH_BELOW_PEAK_DB, -70.0)
        val speaking = BooleanArray(n) { db[it] > thr }
        val speechBlocks = speaking.count { it }

        // ---- ducking: dilate speech, then smooth with a fast attack and a slow release
        val held = BooleanArray(n)
        var last = -1_000_000
        for (b in 0 until n) { if (speaking[b]) last = b; if (b - last <= HOLD) held[b] = true }
        var next = 1_000_000_000
        for (b in n - 1 downTo 0) { if (speaking[b]) next = b; if (next - b <= LOOKAHEAD) held[b] = true }
        val step = BLOCK.toDouble() / MIX_RATE
        val aAtt = exp(-step / ATTACK_SEC)
        val aRel = exp(-step / RELEASE_SEC)
        val rise = FloatArray(n)
        var st = 0.0
        for (b in 0 until n) {
            val target = if (held[b]) 0.0 else BgmDefaults.PAUSE_RISE_DB
            val a = if (target < st) aAtt else aRel
            st = a * st + (1 - a) * target
            rise[b] = dbToLin(st).toFloat()
        }

        // ---- gentle narration levelling: short-term level of the speaking blocks only, 3 s window
        val en = DoubleArray(n) { if (speaking[it]) 10.0.pow(db[it] / 10.0) else 0.0 }
        val cnt = DoubleArray(n) { if (speaking[it]) 1.0 else 0.0 }
        val sumE = movingAvg(en, 61)
        val sumC = movingAvg(cnt, 61)
        val lvl = DoubleArray(n) { if (sumC[it] > 0.02) db10(sumE[it] / sumC[it]) else Double.NaN }
        val typical = median(lvl.filter { !it.isNaN() })
        val g = DoubleArray(n)
        var prev = 0.0
        for (b in 0 until n) {
            val v = lvl[b]
            val want = if (v.isNaN()) prev else (0.5 * (typical - v)).coerceIn(-3.0, 3.0)
            g[b] = want
            prev = want
        }
        val gs = movingAvg(movingAvg(g, 21), 21)
        val level = FloatArray(n) { dbToLin(gs[it]).toFloat() }
        return VoiceGains(level, rise, speechBlocks)
    }
}

// ------------------------------------------------------------------ the music

/** One seamless loop of the music, with the gain curves that go with it (100 ms steps, wrapping). */
class BgmCycle(
    val l: ShortArray,
    val r: ShortArray,
    val levelLin: FloatArray,
    val dipLin: FloatArray,
    /** loudness of the loop with the levelling applied, before it is set against the voice */
    val lufs: Double,
    val trimmedStart: Double,
    val trimmedEnd: Double,
    val sourceSeconds: Double,
) {
    val frames: Int get() = l.size
    val seconds: Double get() = frames.toDouble() / MIX_RATE

    private fun circ(a: FloatArray, m: Int): Double {
        val k = m / BgmPrep.HOP
        val f = (m % BgmPrep.HOP).toDouble() / BgmPrep.HOP
        val v0 = a[k].toDouble()
        val v1 = a[(k + 1) % a.size].toDouble()
        return v0 + (v1 - v0) * f
    }

    fun levelAt(m: Int): Double = circ(levelLin, m)
    fun dipAt(m: Int): Double = circ(dipLin, m)
}

object BgmPrep {
    const val HOP = MIX_RATE / 10                    // 100 ms
    private const val WINDOW_HOPS = 30               // 3 s short-term level
    private const val STRENGTH = 0.85
    private const val MAX_CUT_DB = 12.0
    private const val MAX_LIFT_DB = 6.0

    private fun meanSq(l: ShortArray, r: ShortArray, from: Int, count: Int): Double {
        if (count <= 0) return 0.0
        var s = 0.0
        for (i in from until from + count) {
            val a = l[i] / 32768.0
            val b = r[i] / 32768.0
            s += a * a + b * b
        }
        return s / (2.0 * count)
    }

    /** [pcm]: the track as 16-bit stereo 44100 Hz (see StereoNormalizer). */
    fun build(pcm: File): BgmCycle {
        val frames = (pcm.length() / 4).toInt()
        if (frames < MIX_RATE / 2) throw UnsupportedAudio("the music file is too short")
        val xl = ShortArray(frames)
        val xr = ShortArray(frames)
        val raw = ByteArray(MIX_RATE * 4)
        FileInputStream(pcm).buffered(1 shl 16).use { ins ->
            var done = 0
            while (done < frames) {
                val want = min(MIX_RATE, frames - done) * 4
                var got = 0
                while (got < want) {
                    val k = ins.read(raw, got, want - got)
                    if (k < 0) break
                    got += k
                }
                val nf = got / 4
                if (nf == 0) break
                for (i in 0 until nf) {
                    val o = i * 4
                    xl[done + i] = ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8)).toShort()
                    xr[done + i] = ((raw[o + 2].toInt() and 0xFF) or (raw[o + 3].toInt() shl 8)).toShort()
                }
                done += nf
            }
        }

        // ---- trim silence at the start and the fade-out at the end, so the loop has no dip
        val nh = frames / HOP
        val e = DoubleArray(nh) { meanSq(xl, xr, it * HOP, HOP) }
        val s3 = movingAvg(e, WINDOW_HOPS)
        val typicalSrc = median((0 until nh).map { db10(s3[it]) }.filter { it > -70.0 })
        if (typicalSrc == 0.0 || (0 until nh).none { db10(s3[it]) > -70.0 }) throw UnsupportedAudio("the music has no sound")
        var a = 0
        while (a < nh - 1 && db10(e[a]) < typicalSrc - 35.0) a++
        val s1 = movingAvg(e, 10)
        var j = nh - 1
        while (j > a && db10(s1[j]) < typicalSrc - 14.0) j--
        val start = a * HOP
        val end = min(frames, (j + 1) * HOP)
        if (end - start < MIX_RATE) throw UnsupportedAudio("the music has no sound")

        // ---- one seamless loop: the tail is cross-faded (equal power) into the head
        val cf = min((BgmDefaults.CROSSFADE_SEC * MIX_RATE).toInt(), (end - start) / 4)
        val len = end - start - cf
        val cl = ShortArray(len)
        val cr = ShortArray(len)
        for (i in 0 until len) {
            if (i < cf) {
                val x = i.toDouble() / cf
                val gi = sin(PI / 2 * x)
                val go = cos(PI / 2 * x)
                cl[i] = toShort((xl[start + i] * gi + xl[start + len + i] * go).toFloat() / 32768f)
                cr[i] = toShort((xr[start + i] * gi + xr[start + len + i] * go).toFloat() / 32768f)
            } else {
                cl[i] = xl[start + i]
                cr[i] = xr[start + i]
            }
        }

        // ---- level the loop: turn the loud swells down and the quiet parts up a little (wrapping round)
        val k = (len + HOP - 1) / HOP
        val e2 = DoubleArray(k) { meanSq(cl, cr, it * HOP, min(HOP, len - it * HOP)) }
        val s = movingAvgCircular(e2, WINDOW_HOPS)
        val sdb = DoubleArray(k) { db10(s[it]) }
        val typical = median(sdb.filter { it > -70.0 }.ifEmpty { sdb.toList() })
        val raw2 = DoubleArray(k) { (STRENGTH * (typical - sdb[it])).coerceIn(-MAX_CUT_DB, MAX_LIFT_DB) }
        val gdb = movingAvgCircular(movingAvgCircular(raw2, 15), 15)
        val levelLin = FloatArray(k) { dbToLin(gdb[it]).toFloat() }
        // a swell that is still too loud after the cut makes the narration give way a little (never more than 2 dB)
        val dipLin = FloatArray(k) {
            val resid = max(0.0, sdb[it] + gdb[it] - typical - 1.5)
            dbToLin(-min(2.0, 0.5 * resid)).toFloat()
        }

        // ---- loudness of the levelled loop
        val meter = LoudnessMeter(MIX_RATE, 2)
        val bl = FloatArray(MIX_RATE)
        val br = FloatArray(MIX_RATE)
        var pos = 0
        while (pos < len) {
            val n = min(MIX_RATE, len - pos)
            for (i in 0 until n) {
                val p = pos + i
                val kk = p / HOP
                val f = (p % HOP).toDouble() / HOP
                val gain = levelLin[kk] + (levelLin[(kk + 1) % k] - levelLin[kk]) * f
                bl[i] = (cl[p] / 32768.0 * gain).toFloat()
                br[i] = (cr[p] / 32768.0 * gain).toFloat()
            }
            meter.push(arrayOf(bl, br), n)
            pos += n
        }
        val lufs = meter.result() ?: -30.0
        return BgmCycle(
            cl, cr, levelLin, dipLin, lufs,
            start.toDouble() / MIX_RATE, (frames - end).toDouble() / MIX_RATE, frames.toDouble() / MIX_RATE,
        )
    }
}

/** The music as it enters the mix: the loop, its base gain (from the voice level) and the fades. */
class BgmLayer(val cycle: BgmCycle, val baseLin: Double) {
    /**
     * Adds the music to [l]/[r] (which hold the voice-over) for samples start until start+n of a
     * [total]-sample mix, and applies the narration levelling and dip to the voice already in them.
     */
    fun mixInto(l: FloatArray, r: FloatArray, n: Int, start: Long, total: Long, vg: VoiceGains) {
        val len = cycle.frames
        var m = (start % len).toInt()
        val fin = BgmDefaults.FADE_IN_SEC * MIX_RATE
        val fout = BgmDefaults.FADE_OUT_SEC * MIX_RATE
        for (i in 0 until n) {
            val p = start + i
            val vGain = vg.level(p) * cycle.dipAt(m)
            var fade = 1.0
            if (p < fin) fade = p / fin
            val left = total - p
            if (left < fout) fade = min(fade, left / fout)
            val mg = baseLin * cycle.levelAt(m) * vg.rise(p) * fade
            l[i] = (l[i] * vGain + cycle.l[m] / 32768.0 * mg).toFloat()
            r[i] = (r[i] * vGain + cycle.r[m] / 32768.0 * mg).toFloat()
            if (++m == len) m = 0
        }
    }
}
