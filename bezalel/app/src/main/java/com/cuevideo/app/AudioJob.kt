package com.cuevideo.app

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

data class AudioResult(
    val file: File,
    val lufs: Double?,
    val lufsMeasured: Boolean,
    val clickRmsDb: Double,
    val clickPeak: Double,
    val gainDb: Double,
    val clicks: Int,
    val seconds: Double,
)

fun clickTimesFor(s: UiState): List<Double>? {
    val t = s.timing ?: return null
    val chk = s.check ?: return null
    val clips = chk.clipRows.mapNotNull { r ->
        val f = r.first
        val l = r.last
        if (f != null && l != null && f >= 1 && l >= f && l <= chk.cueCount) Pair(f, l) else null
    }
    // a placed text acts like a scene clip for clicks: one click at its start, none inside it
    val texts = chk.texts.map { Pair(it.first, it.last) }
    return ClickPlan.times(t.frames, clips + texts, s.fps)
}

/** Voice-over and/or click track -> limited AAC. No voice-over is a valid click-only export. */
object AudioJob {
    private const val FALLBACK_LUFS = -20.0

    fun run(
        ctx: Context,
        voice: FileInfo?,
        clickFile: FileInfo?,
        clicksEnabled: Boolean,
        clickTimes: List<Double>,
        totalSeconds: Double,
        voiceSeconds: Double?,
        out: File,
        progress: (String, Double) -> Unit,
        cancelled: () -> Boolean,
    ): AudioResult? {
        require(voice != null || clicksEnabled) { "There is no audio track to mix." }
        val tmpDir = File(ctx.cacheDir, "audio").apply { mkdirs() }
        val pcm = File(tmpDir, "voice_44100_stereo.pcm")
        var voiceLufs: Double? = null
        var lufsMeasured = false
        try {
            if (voice != null) {
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
                            if (pct != lastShown) {
                                lastShown = pct
                                progress("Reading the voice-over...", pct / 100.0 * 0.3)
                            }
                        }
                    }, cancelled)
                } catch (e: Exception) {
                    norm.abort()
                    throw e
                }
                val info = norm.finish()
                if (cancelled()) return null
                if (info.frames == 0L) throw UnsupportedAudio("the voice-over has no sound")
                voiceLufs = info.lufs
                lufsMeasured = info.lufs != null
            }
            val click = if (clicksEnabled) {
                progress("Preparing the click...", 0.32)
                val mc = MonoCollector()
                if (clickFile != null) {
                    AudioDecoder.decodeUri(ctx, Uri.parse(clickFile.uri), clickFile.name, { ch, n, r -> mc.accept(ch, n, r) }, cancelled)
                } else {
                    AudioDecoder.decodeAssetWav(ctx, "click.wav") { ch, n, r -> mc.accept(ch, n, r) }
                }
                ClickPrep.prepare(mc.result())
            } else ClickSound(FloatArray(0), 0.0, 0.0)
            if (cancelled()) return null
            val gainDb = if (clicksEnabled) ClickPrep.gainDb(click, voiceLufs ?: FALLBACK_LUFS) else 0.0
            val gain = if (clicksEnabled) 10.0.pow(gainDb / 20.0) else 1.0
            val activeTimes = if (clicksEnabled) clickTimes else emptyList()
            progress("Mixing and encoding...", 0.35)
            out.delete()
            val writer = AacWriter(out)
            var ok = false
            try {
                ok = AudioMix.mix(
                    if (voice != null) pcm else null, click.samples, gain, activeTimes, totalSeconds,
                    { buf, n -> writer.write(buf, n) },
                    { p -> progress("Mixing and encoding...", 0.35 + 0.65 * p) },
                    cancelled,
                )
                if (ok) writer.finish()
            } finally {
                if (!ok) writer.release()
            }
            if (!ok) { out.delete(); return null }
            return AudioResult(
                out, voiceLufs, lufsMeasured,
                if (clicksEnabled) 20 * log10(maxOf(click.rms, 1e-6)) else 0.0,
                if (clicksEnabled) click.peak else 0.0,
                gainDb, activeTimes.size, totalSeconds,
            )
        } finally {
            pcm.delete()
        }
    }
}
