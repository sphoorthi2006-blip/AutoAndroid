package com.auto.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tanh

/** A 16-bit PCM sound: samples are interleaved (L R L R ...). */
class Wav(val sampleRate: Int, val channels: Int, val samples: ShortArray) {
    val frames get() = samples.size / channels
    val durationMs get() = frames * 1000L / sampleRate
}

object WavFile {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    /** Reads an uncompressed 16-bit PCM WAV (any chunk order, extra chunks skipped). */
    fun parse(b: ByteArray): Wav {
        require(b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a WAV file." }
        var o = 12; var rate = 0; var ch = 0; var bits = 0; var fmtTag = 0
        while (o + 8 <= b.size) {
            val id = String(b, o, 4, Charsets.US_ASCII); val size = u32(b, o + 4).coerceAtMost((b.size - o - 8).toLong()).toInt()
            if (id == "fmt ") { fmtTag = u16(b, o + 8); ch = u16(b, o + 10); rate = u32(b, o + 12).toInt(); bits = u16(b, o + 22) }
            if (id == "data") {
                require(fmtTag == 1 && bits == 16 && ch in 1..8 && rate > 0) { "Only 16-bit PCM WAV is supported." }
                val n = size / 2 / ch * ch
                return Wav(rate, ch, ShortArray(n) { (u16(b, o + 8 + it * 2)).toShort() })
            }
            o += 8 + size + (size and 1)
        }
        throw IllegalArgumentException("WAV has no sound data.")
    }

    /** The 44-byte header of a 16-bit PCM WAV with `frames` frames (the sound data follows). Used for the silent base track when a project has no narration. */
    fun header(sampleRate: Int, channels: Int, frames: Long): ByteArray {
        val dataBytes = frames * channels * 2
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(sampleRate); h.putInt(sampleRate * channels * 2); h.putShort((channels * 2).toShort()); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return h.array()
    }
}

/**
 * Click sound at every picture change.
 * - startsMs: WHEN (the start of every picture / text screen, so 0 ms for the first one; blank gaps are not picture changes).
 * - prepare: the click converted to the narration's sample rate / channel count and volume.
 * - mixInto: adds the click into narration PCM at exact sample positions (used by the exporter's audio processor, which sees the narration buffer by buffer).
 * RULES (agreed with the owner): the click never runs into the next click (cut at the next start with a 10 ms fade), and the sum never wraps around (soft limiter above 90% of full scale).
 */
object ClickTrack {
    const val FADE_MS = 10
    private const val KNEE = 29491            // 90% of 32767
    private const val HEADROOM = 32767 - KNEE

    fun startsMs(plan: ExportPlan.Plan): LongArray =
        plan.segments.filter { it.sceneNum != null && it.startMs in 0 until plan.totalMs }.map { it.startMs }.distinct().sorted().toLongArray()

    /** Index of the first start after `ms` (inclusive = also a start exactly at `ms`). Used by the preview so scrubbing never clicks. */
    fun firstIndex(starts: LongArray, ms: Long, inclusive: Boolean): Int {
        var i = 0
        while (i < starts.size && (if (inclusive) starts[i] < ms else starts[i] <= ms)) i++
        return i
    }

    /** Click as interleaved 16-bit at outRate / outChannels, multiplied by volume. Linear resampling; channels: same count = copy, to mono = average, mono/other = repeat. */
    fun prepare(w: Wav, outRate: Int, outChannels: Int, volume: Float): ShortArray {
        require(outRate > 0 && outChannels > 0)
        val srcN = w.frames
        val outN = (srcN.toLong() * outRate / w.sampleRate).toInt()
        val out = ShortArray(outN * outChannels)
        val step = w.sampleRate.toDouble() / outRate
        fun at(i: Int, c: Int): Double {
            val k = i.coerceIn(0, srcN - 1)
            return if (outChannels == 1 && w.channels > 1) (0 until w.channels).sumOf { w.samples[k * w.channels + it].toDouble() } / w.channels
            else w.samples[k * w.channels + (c % w.channels)].toDouble()
        }
        for (j in 0 until outN) {
            val pos = j * step; val i0 = floor(pos).toInt(); val fr = pos - i0
            for (c in 0 until outChannels) {
                val v = (at(i0, c) * (1 - fr) + at(i0 + 1, c) * fr) * volume
                out[j * outChannels + c] = v.roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    private fun limit(v: Int): Short {
        val a = abs(v)
        if (a <= KNEE) return v.toShort()
        val o = KNEE + (HEADROOM * tanh((a - KNEE).toDouble() / HEADROOM)).toInt()
        return (if (v < 0) -o else o).coerceIn(-32767, 32767).toShort()
    }

    /**
     * Adds the click at every start (in frames) into pcm[0 until count] (interleaved, `channels`), where pcm[0] is frame `bufStartFrame` of the narration.
     * Works for any buffer split: calling it buffer by buffer gives exactly the same result as one big buffer.
     */
    fun mixInto(pcm: ShortArray, count: Int, channels: Int, bufStartFrame: Long, click: ShortArray, startFrames: LongArray, sampleRate: Int) {
        val clickFrames = click.size / channels
        if (clickFrames == 0 || count < channels) return
        val nFrames = count / channels; val bufEnd = bufStartFrame + nFrames
        val fade = (sampleRate.toLong() * FADE_MS / 1000).toInt().coerceAtLeast(1)
        for (k in startFrames.indices) {
            val s = startFrames[k]
            if (s >= bufEnd) break
            val next = if (k + 1 < startFrames.size) startFrames[k + 1] else Long.MAX_VALUE
            val truncated = next - s < clickFrames
            val len = if (truncated) (next - s).toInt() else clickFrames
            if (len <= 0 || s + len <= bufStartFrame) continue
            val from = maxOf(s, bufStartFrame); val to = minOf(s + len, bufEnd)
            for (f in from until to) {
                val j = (f - s).toInt()
                val g = if (truncated && j >= len - fade) (len - j).toDouble() / min(fade, len) else 1.0
                val base = (f - bufStartFrame).toInt() * channels
                for (c in 0 until channels) { val cv = (click[j * channels + c] * g).toInt(); if (cv != 0) pcm[base + c] = limit(pcm[base + c] + cv) }   // narration is left untouched where the click is exactly 0
            }
        }
    }
}
