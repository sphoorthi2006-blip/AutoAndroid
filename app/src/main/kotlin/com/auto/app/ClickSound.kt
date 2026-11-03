package com.auto.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.auto.core.ClickTrack
import com.auto.core.Wav
import com.auto.core.WavFile
import java.nio.ByteBuffer

/** The app's built-in click (res/raw/click.wav: 405 ms, the owner's trimmed click; the hit is ~12 ms into the file). Used for the preview and the export. */
object ClickSound {
    private var cached: Wav? = null
    fun wav(ctx: Context): Wav = cached ?: ctx.applicationContext.resources.openRawResource(R.raw.click).use { WavFile.parse(it.readBytes()) }.also { cached = it }
}

/** Preview: plays the click with SoundPool (low latency). SoundPool volume is 0..1, which is why the click volume setting stops at 100%. NOT run on a device yet. */
class ClickPlayer(ctx: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    @Volatile private var ready = false
    private val id: Int
    init {
        pool.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        id = pool.load(ctx.applicationContext, R.raw.click, 1)
    }
    fun play(volume: Float) { if (!ready) return; val v = volume.coerceIn(0f, 1f); try { pool.play(id, v, v, 1, 0, 1f) } catch (_: Exception) {} }
    fun release() { try { pool.release() } catch (_: Exception) {} }
}

/**
 * Export: adds the click into the narration PCM at the exact sample of every picture start (ClickTrack.mixInto, tested in core Check20).
 * Put it AFTER AudioGainProcessor so narration volume / mute / fades do not change the click. The click is converted to the narration's sample rate and channel count.
 * ASSUMPTION (not run on a device): the narration is one item and is never seeked, so the frame counter that restarts at flush stays correct.
 */
@UnstableApi
class ClickMixProcessor(private val click: Wav, private val startsMs: LongArray, private val volume: Float) : BaseAudioProcessor() {
    private var frame = 0L
    private var clickPcm = ShortArray(0)
    private var startFrames = LongArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        clickPcm = ClickTrack.prepare(click, inputAudioFormat.sampleRate, inputAudioFormat.channelCount, volume)
        startFrames = LongArray(startsMs.size) { startsMs[it] * inputAudioFormat.sampleRate / 1000L }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining() / 2
        if (n == 0) return
        val pcm = ShortArray(n)
        inputBuffer.asShortBuffer().get(pcm)
        inputBuffer.position(inputBuffer.limit())
        val ch = inputAudioFormat.channelCount
        ClickTrack.mixInto(pcm, n, ch, frame, clickPcm, startFrames, inputAudioFormat.sampleRate)
        frame += n / ch
        val out = replaceOutputBuffer(n * 2)
        out.asShortBuffer().put(pcm)
        out.position(n * 2)   // the short view above does not move the byte buffer's position; without this flip() would leave an EMPTY buffer
        out.flip()
    }

    override fun onFlush() { frame = 0L }
    override fun onReset() { frame = 0L }
}
