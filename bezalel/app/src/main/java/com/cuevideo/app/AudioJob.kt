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
    /** Title of the background music, null when there is none. */
    val bgmTitle: String? = null,
    /** How many times the music track was repeated to cover the video. */
    val bgmLoops: Double = 0.0,
    /** Loudness of the finished mix (set to the target when music is on). */
    val mixLufs: Double? = null,
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
        bgm: BgmTrack? = null,
        bgmLevelDb: Double = BgmDefaults.LEVEL_BELOW_VOICE,
        progress: (String, Double) -> Unit,
        cancelled: () -> Boolean,
    ): AudioResult? {
        require(voice != null || clicksEnabled || bgm != null) { "There is no audio track to mix." }
        val tmpDir = File(ctx.cacheDir, "audio").apply { mkdirs() }
        val pcm = File(tmpDir, "voice_44100_stereo.pcm")
        val bgmPcm = File(tmpDir, "bgm_44100_stereo.pcm")
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

            // ---- background music: one bundled track, looped, levelled and set against this voice
            var layer: BgmLayer? = null
            var voiceGains: VoiceGains? = null
            var master = 1.0
            var mixLufs: Double? = null
            var loops = 0.0
            if (bgm != null) {
                progress("Preparing the music...", 0.34)
                val norm = StereoNormalizer(bgmPcm)
                try {
                    AudioDecoder.decodeAsset(ctx, bgm.asset, { ch, n, rate -> norm.accept(ch, n, rate) }, cancelled)
                } catch (e: Exception) {
                    norm.abort()
                    throw e
                }
                norm.finish()
                if (cancelled()) return null
                val cycle = BgmPrep.build(bgmPcm)
                bgmPcm.delete()
                progress("Preparing the music...", 0.40)
                voiceGains = VoiceAnalysis.analyse(if (voice != null) pcm else null)
                // with a voice-over the music sits bgmLevelDb under it; music alone just plays at the target level
                val vl = voiceLufs
                val musicTarget = if (vl != null) vl - bgmLevelDb else BgmDefaults.TARGET_LUFS - BgmDefaults.PAUSE_RISE_DB
                val baseDb = musicTarget - cycle.lufs
                layer = BgmLayer(cycle, 10.0.pow(baseDb / 20.0))
                loops = totalSeconds / cycle.seconds
                // first pass: measure the mix, so the finished video is at one steady loudness
                progress("Balancing the mix...", 0.42)
                val meter = LoudnessMeter(MIX_RATE, 2)
                val measured = AudioMix.mix(
                    if (voice != null) pcm else null, click.samples, gain, activeTimes, totalSeconds,
                    { _, _ -> },
                    { p -> progress("Balancing the mix...", 0.42 + 0.18 * p) },
                    cancelled, layer, voiceGains, 1.0,
                    { l, r, n -> meter.push(arrayOf(l, r), n) },
                )
                if (!measured) return null
                val m = meter.result()
                if (m != null) {
                    val masterDb = (BgmDefaults.TARGET_LUFS - m).coerceIn(-12.0, 12.0)
                    master = 10.0.pow(masterDb / 20.0)
                    mixLufs = m + masterDb
                }
            }
            val encodeFrom = if (bgm != null) 0.60 else 0.35
            progress("Mixing and encoding...", encodeFrom)
            out.delete()
            val writer = AacWriter(out)
            var ok = false
            try {
                ok = AudioMix.mix(
                    if (voice != null) pcm else null, click.samples, gain, activeTimes, totalSeconds,
                    { buf, n -> writer.write(buf, n) },
                    { p -> progress("Mixing and encoding...", encodeFrom + (1.0 - encodeFrom) * p) },
                    cancelled, layer, voiceGains, master,
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
                bgm?.title, loops, mixLufs,
            )
        } finally {
            pcm.delete()
            bgmPcm.delete()
        }
    }
}
