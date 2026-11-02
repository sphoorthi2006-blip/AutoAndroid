package com.auto.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.auto.core.AudioMath
import com.auto.core.AudioSettings
import java.nio.ByteBuffer

/**
 * Applies AudioSettings (volume, fade in, fade out, mute) to 16-bit PCM using core AudioMath.applyGain.
 * playMs = how long this item plays in the video (narration = whole video; music = whole video too), used for the fade-out.
 * ASSUMPTION (not run on a device): the frame counter restarts at onFlush, so a looping music item fades per loop, not once at the end.
 */
@UnstableApi
class AudioGainProcessor(private val settings: AudioSettings, private val playMs: Long) : BaseAudioProcessor() {
    private var frame = 0L
    private var durFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        durFrames = playMs * inputAudioFormat.sampleRate / 1000L
        return if (settings.isIdentity) AudioProcessor.AudioFormat.NOT_SET else inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining() / 2
        if (n == 0) return
        val pcm = ShortArray(n)
        inputBuffer.asShortBuffer().get(pcm)
        inputBuffer.position(inputBuffer.limit())
        val ch = inputAudioFormat.channelCount
        AudioMath.applyGain(pcm, n, ch, inputAudioFormat.sampleRate, frame, durFrames, settings)
        frame += n / ch
        val out = replaceOutputBuffer(n * 2)
        out.asShortBuffer().put(pcm)
        out.flip()
    }

    override fun onFlush() { frame = 0L }
    override fun onReset() { frame = 0L }
}
