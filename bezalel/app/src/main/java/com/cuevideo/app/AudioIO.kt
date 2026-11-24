package com.cuevideo.app

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Pure file and stream helpers for the audio of section 13. No Android code. */

/** A block of decoded sound: one FloatArray per source channel, [n] samples each, values -1..1. */
typealias PcmSink = (ch: Array<FloatArray>, n: Int, rate: Int) -> Unit

class UnsupportedAudio(msg: String) : Exception(msg)

// ------------------------------------------------------------------ WAV reader (no codec needed)

object WavStream {
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)

    private fun readFully(ins: InputStream, b: ByteArray, len: Int): Boolean {
        var got = 0
        while (got < len) {
            val r = ins.read(b, got, len - got)
            if (r < 0) return false
            got += r
        }
        return true
    }

    /** PCM 8/16/24/32-bit or float WAV. Throws UnsupportedAudio for anything else. */
    fun read(ins: InputStream, sink: PcmSink) {
        val hdr = ByteArray(12)
        if (!readFully(ins, hdr, 12) || String(hdr, 0, 4) != "RIFF" || String(hdr, 8, 4) != "WAVE")
            throw UnsupportedAudio("not a WAV file")
        var channels = 0
        var rate = 0
        var bits = 0
        var float = false
        var haveFmt = false
        val ch8 = ByteArray(8)
        while (true) {
            if (!readFully(ins, ch8, 8)) throw UnsupportedAudio("WAV has no data")
            val id = String(ch8, 0, 4)
            var size = le32(ch8, 4)
            if (id == "fmt ") {
                val f = ByteArray(size.toInt())
                if (!readFully(ins, f, f.size)) throw UnsupportedAudio("bad WAV header")
                var tag = le16(f, 0)
                channels = le16(f, 2); rate = le32(f, 4).toInt(); bits = le16(f, 14)
                if (tag == 0xFFFE && f.size >= 26) tag = le16(f, 24)
                if (tag != 1 && tag != 3) throw UnsupportedAudio("WAV format $tag")
                float = tag == 3
                if (float && bits != 32) throw UnsupportedAudio("WAV float $bits")
                if (!float && bits !in listOf(8, 16, 24, 32)) throw UnsupportedAudio("WAV $bits-bit")
                if (channels < 1 || rate < 1000) throw UnsupportedAudio("bad WAV header")
                haveFmt = true
                if (size % 2 == 1L) ins.skip(1)
            } else if (id == "data") {
                if (!haveFmt) throw UnsupportedAudio("WAV data before format")
                val bps = bits / 8
                val frameBytes = bps * channels
                var remaining = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                val framesPerChunk = 16384
                val raw = ByteArray(framesPerChunk * frameBytes)
                val ch = Array(channels) { FloatArray(framesPerChunk) }
                while (remaining > 0) {
                    val want = min(raw.size.toLong(), remaining).toInt() / frameBytes * frameBytes
                    if (want == 0) break
                    var got = 0
                    while (got < want) {
                        val r = ins.read(raw, got, want - got)
                        if (r < 0) break
                        got += r
                    }
                    val frames = got / frameBytes
                    if (frames == 0) break
                    for (f in 0 until frames) for (c in 0 until channels) {
                        val o = (f * channels + c) * bps
                        ch[c][f] = when {
                            float -> java.lang.Float.intBitsToFloat(le32(raw, o).toInt())
                            bits == 8 -> ((raw[o].toInt() and 0xFF) - 128) / 128f
                            bits == 16 -> (le16(raw, o).toShort().toInt()) / 32768f
                            bits == 24 -> (((raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() shl 16))) / 8388608f
                            else -> le32(raw, o).toInt() / 2147483648f
                        }
                    }
                    sink(ch, frames, rate)
                    remaining -= got
                    if (got < want) break
                }
                return
            } else {
                var left = size + (size and 1L)
                while (left > 0) {
                    val s = ins.skip(left)
                    if (s <= 0) throw UnsupportedAudio("WAV has no data")
                    left -= s
                }
            }
        }
    }
}

// ------------------------------------------------------------------ voice-over -> stereo 44.1 kHz file

class NormInfo(val frames: Long, val sourceChannels: Int, val lufs: Double?)

/**
 * Takes decoded sound of any rate / channel count and writes 16-bit stereo 44100 Hz little-endian
 * PCM to [outFile], measuring the integrated loudness on the way. A mono source is measured as mono
 * (like ffmpeg ebur128) and then written to both channels.
 */
class StereoNormalizer(private val outFile: File) {
    private var out: BufferedOutputStream? = null
    private var rsL: Resampler? = null
    private var rsR: Resampler? = null
    private var meter: LoudnessMeter? = null
    private var srcCh = 0
    private var frames = 0L

    fun accept(ch: Array<FloatArray>, n: Int, rate: Int) {
        if (out == null) {
            srcCh = ch.size
            out = BufferedOutputStream(FileOutputStream(outFile), 1 shl 16)
            rsL = Resampler(rate, MIX_RATE)
            rsR = if (srcCh >= 2) Resampler(rate, MIX_RATE) else null
            meter = LoudnessMeter(MIX_RATE, if (srcCh >= 2) 2 else 1)
        }
        val l = rsL!!.push(ch[0], n)
        val r = if (srcCh >= 2) rsR!!.push(ch[1], n) else l
        write(l, r)
    }

    private fun write(l: FloatArray, r: FloatArray) {
        val n = min(l.size, r.size)
        if (n == 0) return
        meter!!.push(if (srcCh >= 2) arrayOf(l, r) else arrayOf(l), n)
        val bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) { bb.putShort(toShort(l[i])); bb.putShort(toShort(r[i])) }
        out!!.write(bb.array(), 0, n * 4)
        frames += n
    }

    fun finish(): NormInfo {
        if (out == null) { outFile.writeBytes(ByteArray(0)); return NormInfo(0, 0, null) }
        val empty = FloatArray(0)
        val l = rsL!!.push(empty, 0, flush = true)
        val r = if (srcCh >= 2) rsR!!.push(empty, 0, flush = true) else l
        write(l, r)
        out!!.close()
        return NormInfo(frames, srcCh, meter!!.result())
    }

    fun abort() { try { out?.close() } catch (e: Exception) { } }
}

/** Collects a (short) sound as mono 44100 Hz floats: the click. Two channels are averaged. */
class MonoCollector {
    private var rs: Resampler? = null
    private val parts = ArrayList<FloatArray>()
    private var total = 0

    fun accept(ch: Array<FloatArray>, n: Int, rate: Int) {
        if (rs == null) rs = Resampler(rate, MIX_RATE)
        val m = FloatArray(n)
        val k = min(ch.size, 2)
        for (i in 0 until n) { var s = 0f; for (c in 0 until k) s += ch[c][i]; m[i] = s / k }
        val o = rs!!.push(m, n)
        parts.add(o); total += o.size
    }

    fun result(): FloatArray {
        rs?.let { val o = it.push(FloatArray(0), 0, flush = true); parts.add(o); total += o.size }
        val all = FloatArray(total)
        var p = 0
        for (a in parts) { System.arraycopy(a, 0, all, p, a.size); p += a.size }
        return all
    }
}

fun toShort(x: Float): Short {
    val v = Math.rint(x * 32767.0)
    return (if (v > 32767.0) 32767.0 else if (v < -32768.0) -32768.0 else v).toInt().toShort()
}

// ------------------------------------------------------------------ the mix

object AudioMix {
    /**
     * Voice-over file (16-bit stereo 44100 Hz) + click track -> limited 16-bit stereo blocks.
     * Output length is exactly round(totalSeconds * 44100) samples; a shorter voice-over is
     * padded with silence. [sink] gets interleaved L R L R ... and the number of frames.
     * Returns false when cancelled.
     */
    fun mix(
        voice: File?, click: FloatArray, gain: Double, clickTimes: List<Double>, totalSeconds: Double,
        sink: (ShortArray, Int) -> Unit, progress: (Double) -> Unit = {}, cancelled: () -> Boolean = { false },
        bgm: BgmLayer? = null, voiceGains: VoiceGains? = null, master: Double = 1.0,
        tap: ((FloatArray, FloatArray, Int) -> Unit)? = null,
    ): Boolean {
        val totalSamples = Math.rint(totalSeconds * MIX_RATE).toLong()
        val pos = LongArray(clickTimes.size) { Math.rint(clickTimes[it] * MIX_RATE).toLong() }
        val chunk = MIX_RATE
        val limiter = Limiter(0.95, MIX_RATE)
        val ins = if (voice != null && voice.exists()) FileInputStream(voice).buffered(1 shl 16) else null
        val raw = ByteArray(chunk * 4)
        val l = FloatArray(chunk)
        val r = FloatArray(chunk)
        val outS = ShortArray(chunk * 2)
        var first = 0                       // first click that may still be sounding
        var done = 0L
        try {
            while (done < totalSamples) {
                if (cancelled()) return false
                val n = min(chunk.toLong(), totalSamples - done).toInt()
                var got = 0
                if (ins != null) {
                    var bytes = 0
                    while (bytes < n * 4) {
                        val k = ins.read(raw, bytes, n * 4 - bytes)
                        if (k < 0) break
                        bytes += k
                    }
                    got = bytes / 4
                }
                for (i in 0 until n) {
                    if (i < got) {
                        val o = i * 4
                        l[i] = ((raw[o].toInt() and 0xFF) or (raw[o + 1].toInt() shl 8)).toShort() / 32768f
                        r[i] = ((raw[o + 2].toInt() and 0xFF) or (raw[o + 3].toInt() shl 8)).toShort() / 32768f
                    } else { l[i] = 0f; r[i] = 0f }
                }
                // background music (loop, levelled, ducked) and the narration levelling
                if (bgm != null) bgm.mixInto(l, r, n, done, totalSamples, voiceGains ?: VoiceGains(FloatArray(0), FloatArray(0), 0))
                // click track part of this block (click track is clipped to -1..1 before the mix)
                while (first < pos.size && pos[first] + click.size <= done) first++
                var c = first
                if (c < pos.size && pos[c] < done + n) {
                    val ck = FloatArray(n)
                    while (c < pos.size && pos[c] < done + n) {
                        val s = pos[c]
                        val from = max(s, done)
                        val to = min(s + click.size, done + n)
                        for (p in from until to) ck[(p - done).toInt()] += (click[(p - s).toInt()] * gain).toFloat()
                        c++
                    }
                    for (i in 0 until n) {
                        val v = max(-1f, min(1f, ck[i]))
                        l[i] += v; r[i] += v
                    }
                }
                tap?.invoke(l, r, n)
                if (master != 1.0) { val mg = master.toFloat(); for (i in 0 until n) { l[i] *= mg; r[i] *= mg } }
                emit(limiter.process(l, r, n), outS, sink)
                done += n
                progress(done.toDouble() / totalSamples)
            }
            emit(limiter.finish(), outS, sink)
        } finally {
            ins?.close()
        }
        return true
    }

    private fun emit(p: Pair<FloatArray, FloatArray>, buf: ShortArray, sink: (ShortArray, Int) -> Unit) {
        val n = p.first.size
        if (n == 0) return
        var out = buf
        if (out.size < n * 2) out = ShortArray(n * 2)
        for (i in 0 until n) { out[2 * i] = toShort(p.first[i]); out[2 * i + 1] = toShort(p.second[i]) }
        sink(out, n)
    }
}
