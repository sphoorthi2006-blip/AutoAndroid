package com.auto.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Volume / fades / mute for one audio item. volume 0..2 (1 = unchanged, up to +6 dB), fades are linear ramps in ms.
 * trimStartMs is only used for MUSIC (the narration is never cut: the video is exactly as long as the narration).
 */
data class AudioSettings(val volume: Float = 1f, val fadeInMs: Int = 0, val fadeOutMs: Int = 0, val muted: Boolean = false, val trimStartMs: Long = 0L) {
    fun clamped() = copy(volume = volume.coerceIn(0f, 2f), fadeInMs = fadeInMs.coerceIn(0, 10_000), fadeOutMs = fadeOutMs.coerceIn(0, 10_000), trimStartMs = max(0L, trimStartMs))
    val isIdentity get() = volume == 1f && fadeInMs == 0 && fadeOutMs == 0 && !muted && trimStartMs == 0L
    fun toJson() = "{\"v\":$volume,\"fi\":$fadeInMs,\"fo\":$fadeOutMs,\"m\":$muted,\"ts\":$trimStartMs}"
    companion object { fun fromMap(m: Map<*, *>?): AudioSettings = if (m == null) AudioSettings() else AudioSettings((m["v"] as? Double)?.toFloat() ?: 1f, (m["fi"] as? Double)?.toInt() ?: 0, (m["fo"] as? Double)?.toInt() ?: 0, m["m"] as? Boolean ?: false, (m["ts"] as? Double)?.toLong() ?: 0L).clamped() }
}

/** A music bed under the narration: starts at 0 of the video (a start offset is NOT offered: Media3 1.3.1 cannot place an item later in an audio sequence), loops or stops at its end. */
data class MusicTrack(val path: String, val name: String, val settings: AudioSettings = AudioSettings(volume = 0.35f), val loop: Boolean = true, val durationMs: Long = 0L) {
    fun toJson() = "{\"path\":${jsonQuote(path)},\"name\":${jsonQuote(name)},\"s\":${settings.toJson()},\"loop\":$loop,\"dur\":$durationMs}"
    companion object { fun fromMap(m: Map<*, *>?): MusicTrack? { val p = m?.get("path") as? String ?: return null; return MusicTrack(p, m["name"] as? String ?: p.substringAfterLast('/'), AudioSettings.fromMap(m["s"] as? Map<*, *>), m["loop"] as? Boolean ?: true, (m["dur"] as? Double)?.toLong() ?: 0L) } }
}
data class AudioMix(val narration: AudioSettings = AudioSettings(), val music: MusicTrack? = null) {
    val isDefault get() = narration.isIdentity && music == null
    fun toJson() = "{\"narration\":${narration.toJson()},\"music\":${music?.toJson() ?: "null"}}"
    companion object { fun fromMap(m: Map<*, *>?): AudioMix = if (m == null) AudioMix() else AudioMix(AudioSettings.fromMap(m["narration"] as? Map<*, *>), MusicTrack.fromMap(m["music"] as? Map<*, *>)) }
}

object AudioMath {
    /** Gain (0..2) at tMs of an item that plays for durMs. */
    fun gain(s: AudioSettings, tMs: Long, durMs: Long): Float {
        if (s.muted || tMs < 0 || tMs > durMs) return 0f
        var g = s.volume
        if (s.fadeInMs > 0 && tMs < s.fadeInMs) g *= tMs.toFloat() / s.fadeInMs
        val left = durMs - tMs
        if (s.fadeOutMs > 0 && left < s.fadeOutMs) g *= left.toFloat() / s.fadeOutMs
        return g.coerceAtLeast(0f)
    }

    /** In-place gain on interleaved 16-bit PCM. startFrame = index of the first frame of this buffer inside the item; durFrames = length of the item. Used by the exporter's audio processor. */
    fun applyGain(pcm: ShortArray, count: Int, channels: Int, sampleRate: Int, startFrame: Long, durFrames: Long, s: AudioSettings) {
        if (s.isIdentity || count <= 0) return
        val durMs = durFrames * 1000.0 / sampleRate
        var i = 0; var frame = startFrame
        while (i + channels <= count) {
            val g = gain(s, 0, 0).let { gain(s, Math.round(frame * 1000.0 / sampleRate), Math.round(durMs)) }
            for (c in 0 until channels) { val v = (pcm[i + c] * g).toInt(); pcm[i + c] = v.coerceIn(-32768, 32767).toShort() }
            i += channels; frame++
        }
    }
    fun db(gain: Float): Float = if (gain <= 0f) Float.NEGATIVE_INFINITY else (20.0 * Math.log10(gain.toDouble())).toFloat()
}

/** Peak values for drawing a waveform. */
object Waveform {
    /** One value 0..1 per bucket = the loudest |sample| in that bucket, scaled so the loudest bucket of the whole file is 1 (quiet files are still readable). */
    fun peaks(samples: FloatArray, buckets: Int): FloatArray {
        if (samples.isEmpty() || buckets <= 0) return FloatArray(max(0, buckets))
        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val a = (b.toLong() * samples.size / buckets).toInt(); val z = max(a + 1, ((b + 1).toLong() * samples.size / buckets).toInt()).coerceAtMost(samples.size)
            var m = 0f; for (i in a until z) { val v = abs(samples[i]); if (v > m) m = v }; out[b] = m
        }
        val top = out.max(); if (top > 0f) for (i in out.indices) out[i] /= top
        return out
    }
    /** Re-sample a finished peak list to n buckets by max-pooling, so zooming the timeline never needs the audio again. */
    fun resample(peaks: FloatArray, n: Int): FloatArray {
        if (peaks.isEmpty() || n <= 0) return FloatArray(max(0, n))
        return FloatArray(n) { b -> val a = (b.toLong() * peaks.size / n).toInt(); val z = max(a + 1, ((b + 1).toLong() * peaks.size / n).toInt()).coerceAtMost(peaks.size); var m = 0f; for (i in a until z) m = max(m, peaks[i]); m }
    }
    /** Buckets for a given timeline width: about one per `pxPerBucket` pixels. */
    fun bucketsFor(widthPx: Int, pxPerBucket: Int = 3) = max(1, widthPx / max(1, pxPerBucket))
}
