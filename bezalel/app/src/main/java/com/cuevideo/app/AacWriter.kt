package com.cuevideo.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/** Encodes 16-bit stereo 44100 Hz PCM to AAC-LC 192 kbps in an .m4a file (section 13). */
class AacWriter(file: File, bitrate: Int = 192_000) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var fed = 0L
    private var released = false

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MIX_RATE, 2)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Returns true when the encoder has signalled the end of the stream. */
    private fun drain(timeoutUs: Long): Boolean {
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                oi >= 0 -> {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    /** pcm = interleaved L R L R ..., [frames] sample pairs. */
    fun write(pcm: ShortArray, frames: Int) {
        val total = frames * 2
        var off = 0
        while (off < total) {
            drain(0)
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii < 0) continue
            val b = codec.getInputBuffer(ii)!!
            b.clear()
            val cap = (b.capacity() / 4) * 2                    // shorts, whole frames only
            val k = min(cap, total - off)
            b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, off, k)
            codec.queueInputBuffer(ii, 0, k * 2, fed * 1_000_000L / MIX_RATE, 0)
            fed += k / 2
            off += k
        }
    }

    fun finish() {
        try {
            while (true) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    codec.queueInputBuffer(ii, 0, 0, fed * 1_000_000L / MIX_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(0)
            }
            while (!drain(10_000)) { /* wait for the end of the stream */ }
            if (started) muxer.stop()
        } finally {
            release()
        }
    }

    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (e: Exception) { }
        try { codec.release() } catch (e: Exception) { }
        try { muxer.release() } catch (e: Exception) { }
    }
}
