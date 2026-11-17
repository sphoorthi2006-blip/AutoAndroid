package com.cuevideo.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Pure audio maths of section 13. No Android code, so it is tested on the build server. */

const val MIX_RATE = 44100

// ------------------------------------------------------------------ click times

object ClickPlan {
    /**
     * Click times in seconds, in order (section 13).
     * [bf] = frame numbers from Timing.frameBounds (n+1 entries).
     * [clips] = (first, last) cue numbers, 1-based, of every scene clip.
     * A click at every cue start except cue 1; cues under a clip get none, but the clip itself
     * gets one at its start unless it starts at cue 1. The cue after a clip gets its own click.
     */
    fun times(bf: IntArray, clips: List<Pair<Int, Int>>, fps: Int): List<Double> {
        val n = bf.size - 1
        val covered = HashSet<Int>()
        for (c in clips) for (k in c.first..c.second) covered.add(k)
        val frames = ArrayList<Int>()
        for (i in 1 until n) {                       // i = 0 is cue 1: never a click
            if ((i + 1) !in covered) frames.add(bf[i])
        }
        for (c in clips) if (c.first > 1) frames.add(bf[c.first - 1])
        frames.sort()
        return frames.filter { it > 0 }.map { it.toDouble() / fps }
    }
}

// ------------------------------------------------------------------ click sound

class ClickSound(val samples: FloatArray, val rms: Double, val peak: Double)

object ClickPrep {
    /** mono, 44100 Hz input -> trimmed, faded click and its rms / peak. */
    fun prepare(mono: FloatArray, sr: Int = MIX_RATE): ClickSound {
        val thr = 10.0.pow(-45.0 / 20.0)
        var first = -1
        var last = -1
        for (i in mono.indices) {
            if (abs(mono[i]) > thr) {
                if (first < 0) first = i
                last = i
            }
        }
        var x: FloatArray = if (first < 0) mono.copyOf()
        else {
            val end = min(mono.size, last + (0.03 * sr).toInt())
            mono.copyOfRange(first, max(first + 1, end))
        }
        // linear fade-out over the last 0.02 s (like numpy linspace(1, 0, n))
        val nf = min(x.size, (0.02 * sr).toInt())
        if (nf > 1) {
            for (k in 0 until nf) x[x.size - nf + k] = (x[x.size - nf + k] * (1.0 - k.toDouble() / (nf - 1))).toFloat()
        } else if (nf == 1) {
            x[x.size - 1] = 0f
        }
        var sq = 0.0
        var pk = 0.0
        for (v in x) { sq += v.toDouble() * v; pk = max(pk, abs(v.toDouble())) }
        val rms = if (x.isEmpty()) 0.0 else sqrt(sq / x.size)
        return ClickSound(x, rms, pk)
    }

    /** Gain in dB (section 13): LUFS + level - rms_db, then the peak limit. */
    fun gainDb(click: ClickSound, lufs: Double, levelDb: Double = -14.0): Double {
        val rmsDb = 20 * log10(max(click.rms, 1e-6))
        var g = lufs + levelDb - rmsDb
        g = min(g, -3.0 - 20 * log10(max(click.peak, 1e-6)))
        return g
    }
}

// ------------------------------------------------------------------ loudness (BS.1770-4, as ffmpeg ebur128)

/** Integrated loudness. Feed samples with push(); call result() at the end. */
class LoudnessMeter(private val sr: Int, private val channels: Int) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0
        var z2 = 0.0
        fun run(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private fun shelf(): Biquad {
        val f0 = 1681.974450955533
        val g = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / sr)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0,
        )
    }

    private fun highPass(): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / sr)
        val a0 = 1.0 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private val sh = Array(channels) { shelf() }
    private val hp = Array(channels) { highPass() }
    private val sub = sr / 10                       // 100 ms
    private var subCount = 0
    private var subSum = 0.0
    private val subEnergies = ArrayList<Double>()   // mean square (weighted sum over channels) of each 100 ms

    /** Interleaved-free: one array per channel, n samples each. */
    fun push(ch: Array<FloatArray>, n: Int) {
        for (i in 0 until n) {
            var e = 0.0
            for (c in 0 until channels) {
                val y = hp[c].run(sh[c].run(ch[c][i].toDouble()))
                e += y * y
            }
            subSum += e
            if (++subCount == sub) {
                subEnergies.add(subSum / sub)
                subSum = 0.0
                subCount = 0
            }
        }
    }

    /** Integrated loudness in LUFS, or null when there is not enough signal. */
    fun result(): Double? {
        val blocks = ArrayList<Double>()
        for (m in 0..subEnergies.size - 4) {
            blocks.add((subEnergies[m] + subEnergies[m + 1] + subEnergies[m + 2] + subEnergies[m + 3]) / 4.0)
        }
        fun lk(e: Double) = -0.691 + 10 * log10(e)
        val absGated = blocks.filter { it > 0 && lk(it) > -70.0 }
        if (absGated.isEmpty()) return null
        val rel = lk(absGated.sum() / absGated.size) - 10.0
        val relGated = absGated.filter { lk(it) > rel }
        if (relGated.isEmpty()) return null
        val v = lk(relGated.sum() / relGated.size)
        return if (v.isNaN() || v.isInfinite()) null else v
    }
}

// ------------------------------------------------------------------ resampling (any rate -> 44100)

/**
 * Streaming windowed-sinc resampler, one instance per channel.
 * push() takes input samples and returns the output samples that are ready;
 * push(..., flush = true) on the last call also returns the tail.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    private val half = 12
    private val phases = 1024
    private val step = inRate.toDouble() / outRate
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.97
    private val table: Array<FloatArray> = Array(phases + 1) { p ->
        val frac = p.toDouble() / phases
        FloatArray(2 * half) { t ->
            val x = (t - half + 1) - frac                  // distance from the output position
            val s = if (abs(x) < 1e-12) cutoff else sin(PI * cutoff * x) / (PI * x)
            val w = if (abs(x) < half) 0.5 + 0.5 * cos(PI * x / half) else 0.0   // Hann window
            (s * w).toFloat()
        }
    }
    private var buf = FloatArray(0)
    private var base = 0L           // absolute input index of buf[0]
    private var next = 0L           // next output sample index
    val identity = inRate == outRate

    fun push(x: FloatArray, n: Int, flush: Boolean = false): FloatArray {
        if (identity) return x.copyOf(n)
        val nb = FloatArray(buf.size + n)
        System.arraycopy(buf, 0, nb, 0, buf.size)
        System.arraycopy(x, 0, nb, buf.size, n)
        buf = nb
        val total = base + buf.size                         // input samples received so far
        val cap = ((total - next * step) / step).toInt() + 4
        var out = FloatArray(max(cap, 0))
        var cnt = 0
        while (true) {
            val pos = next * step
            val i0 = floor(pos).toLong()
            if (flush) { if (pos >= total) break } else { if (i0 + half > total - 1) break }
            if (cnt == out.size) out = out.copyOf(out.size * 2 + 4)
            val tab = table[(((pos - i0) * phases) + 0.5).toInt()]
            var acc = 0.0f
            val start = i0 - half + 1 - base
            for (t in 0 until 2 * half) {
                val idx = start + t
                if (idx >= 0 && idx < buf.size) acc += buf[idx.toInt()] * tab[t]
            }
            out[cnt++] = acc
            next++
        }
        val keepFrom = floor(next * step).toLong() - half
        if (keepFrom > base) {
            val drop = min((keepFrom - base).toInt(), buf.size)
            buf = buf.copyOfRange(drop, buf.size)
            base += drop
        }
        return out.copyOf(cnt)
    }
}

// ------------------------------------------------------------------ limiter

/**
 * Look-ahead peak limiter with a ceiling (0.95). The sound is delayed by 5 ms, the gain drops in
 * time before a peak and comes back in about 50 ms. process() returns the samples that are ready
 * (the first 5 ms of input produce nothing); finish() returns the tail, so that the total output
 * length equals the total input length.
 */
class Limiter(private val ceiling: Double = 0.95, sr: Int = MIX_RATE) {
    private val la = max(1, (0.005 * sr).toInt())
    private val relStep = 1.0 / (0.05 * sr)
    private val win = la + 1
    private val audL = FloatArray(la)
    private val audR = FloatArray(la)
    private val dqIdx = LongArray(win + 1)        // monotonic deque for the sliding minimum
    private val dqVal = DoubleArray(win + 1)
    private var dqHead = 0
    private var dqSize = 0
    private val avgRing = DoubleArray(win) { 1.0 }
    private var avgSum = win.toDouble()
    private var rel = 1.0
    private var count = 0L

    private var oL = FloatArray(0)
    private var oR = FloatArray(0)
    private var oN = 0

    private fun one(l: Float, r: Float) {
        val k = count
        val cap = dqVal.size
        val peak = max(abs(l.toDouble()), abs(r.toDouble()))
        val need = if (peak > ceiling) ceiling / peak else 1.0
        while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % cap] >= need) dqSize--
        val tail = (dqHead + dqSize) % cap
        dqIdx[tail] = k; dqVal[tail] = need; dqSize++
        while (dqSize > 0 && k - dqIdx[dqHead] >= win) { dqHead = (dqHead + 1) % cap; dqSize-- }
        rel = min(dqVal[dqHead], rel + relStep)
        val slot = (k % win).toInt()
        avgSum += rel - avgRing[slot]
        avgRing[slot] = rel
        val g = (avgSum / win).coerceIn(0.0, 1.0)
        val a = (k % la).toInt()                   // holds sample k - la
        val pl = audL[a]
        val pr = audR[a]
        audL[a] = l; audR[a] = r
        count++
        if (k >= la) {
            oL[oN] = (pl * g).toFloat(); oR[oN] = (pr * g).toFloat(); oN++
        }
    }

    fun process(l: FloatArray, r: FloatArray, n: Int): Pair<FloatArray, FloatArray> {
        if (oL.size < n) { oL = FloatArray(n); oR = FloatArray(n) }
        oN = 0
        for (i in 0 until n) one(l[i], r[i])
        return Pair(oL.copyOf(oN), oR.copyOf(oN))
    }

    fun finish(): Pair<FloatArray, FloatArray> {
        val z = FloatArray(la)
        return process(z, z, la)
    }
}
