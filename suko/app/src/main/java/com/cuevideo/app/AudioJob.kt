package com.cuevideo.app

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

data class AudioResult(
    val file: File,
    val lufs: Double,
    val lufsMeasured: Boolean,
    val clickRmsDb: Double,
    val clickPeak: Double,
    val gainDb: Double,
    val clicks: Int,
    val seconds: Double,
)

/** Click times for the current project, or null when there is nothing to work from yet. */
fun clickTimesFor(s: UiState): List<Double>? {
    val t = s.timing ?: return null
    val chk = s.check ?: return null
    val clips = chk.clipRows.mapNotNull { r ->
        val f = r.first
        val l = r.last
        if (f != null && l != null && f >= 1 && l >= f && l <= chk.cueCount) Pair(f, l) else null
    }
    return ClickPlan.times(t.frames, clips, s.fps)
}

/** Section 13 end to end: voice-over -> loudness -> click gain -> click track -> mix -> limiter -> AAC. */
object AudioJob {
    private const val FALLBACK_LUFS = -20.0

    fun run(
        ctx: Context,
        voice: FileInfo,
        clickFile: FileInfo?,
        clickTimes: List<Double>,
        totalSeconds: Double,
        voiceSeconds: Double?,
        out: File,
        progress: (String, Double) -> Unit,
        cancelled: () -> Boolean,
    ): AudioResult? {
        val tmpDir = File(ctx.cacheDir, "audio").apply { mkdirs() }
        val pcm = File(tmpDir, "voice_44100_stereo.pcm")

        // 1. voice-over -> 16-bit stereo 44.1 kHz file + loudness
        progress("Reading the voice-over...", 0.0)
        val norm = StereoNormalizer(pcm)
        var fed = 0L
        var lastShown = -1
        try {
            AudioDecoder.decodeUri(ctx, Uri.parse(voice.uri), voice.name, { ch, n, rate ->
                norm.accept(ch, n, rate)
                fed += n
                if (voiceSeconds != null && voiceSeconds > 0) {
                    val pct = (fed.toDouble() / rate / voiceSeconds * 100).toInt().coerceIn(0, 100)
                    if (pct != lastShown) { lastShown = pct; progress("Reading the voice-over...", pct / 100.0 * 0.3) }
                }
            }, cancelled)
        } catch (e: Exception) {
            norm.abort()
            throw e
        }
        val info = norm.finish()
        if (cancelled()) return null
        if (info.frames == 0L) throw UnsupportedAudio("the voice-over has no sound")
        val lufs = info.lufs ?: FALLBACK_LUFS

        // 2. click sound -> mono 44.1 kHz, trimmed, faded
        progress("Preparing the click...", 0.32)
        val mc = MonoCollector()
        if (clickFile != null) {
            AudioDecoder.decodeUri(ctx, Uri.parse(clickFile.uri), clickFile.name, { ch, n, r -> mc.accept(ch, n, r) }, cancelled)
        } else {
            AudioDecoder.decodeAssetWav(ctx, "click.wav") { ch, n, r -> mc.accept(ch, n, r) }
        }
        val click = ClickPrep.prepare(mc.result())
        val gainDb = ClickPrep.gainDb(click, lufs)
        val gain = 10.0.pow(gainDb / 20.0)

        // 3. mix + limiter + AAC
        progress("Mixing and encoding...", 0.35)
        out.delete()
        val writer = AacWriter(out)
        var ok = false
        try {
            ok = AudioMix.mix(
                pcm, click.samples, gain, clickTimes, totalSeconds,
                { buf, n -> writer.write(buf, n) },
                { p -> progress("Mixing and encoding...", 0.35 + 0.65 * p) },
                cancelled,
            )
            if (ok) writer.finish()
        } finally {
            if (!ok) writer.release()
        }
        pcm.delete()
        if (!ok) { out.delete(); return null }
        return AudioResult(
            out, lufs, info.lufs != null, 20 * log10(maxOf(click.rms, 1e-6)), click.peak,
            gainDb, clickTimes.size, totalSeconds,
        )
    }
}
