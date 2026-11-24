package com.cuevideo.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * H.264 encoder fed through an input Surface, plus the MP4 muxer. The finished audio (an .m4a made by
 * AudioJob) is copied into the same muxer in step with the video, so no second pass is needed.
 */
class VideoSink(file: File, private val W: Int, private val H: Int, private val fps: Int, audio: File?) {
    private val codec: MediaCodec
    val inputSurface: Surface
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var released = false

    private val aex: MediaExtractor?
    private var audioFormat: MediaFormat? = null
    private var aEnd = false
    private val abuf: ByteBuffer = ByteBuffer.allocate(1 shl 16)
    private val ainfo = MediaCodec.BufferInfo()

    private fun create(withProfile: Boolean): MediaCodec {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        f.setInteger(MediaFormat.KEY_BIT_RATE, if (fps > 30) 16_000_000 else 12_000_000)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        if (withProfile) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            f.setInteger(
                MediaFormat.KEY_LEVEL,
                if (fps > 30) MediaCodecInfo.CodecProfileLevel.AVCLevel42 else MediaCodecInfo.CodecProfileLevel.AVCLevel41,
            )
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            c.release()
            throw e
        }
        return c
    }

    init {
        codec = try { create(true) } catch (e: Exception) { create(false) }
        inputSurface = codec.createInputSurface()
        codec.start()
        if (audio != null) {
            val ex = MediaExtractor()
            ex.setDataSource(audio.path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    ex.selectTrack(i)
                    audioFormat = f
                    break
                }
            }
            aex = ex
        } else {
            aex = null
        }
    }

    private fun pumpAudio(upToUs: Long) {
        val ex = aex ?: return
        if (!started || audioTrack < 0) return
        while (!aEnd) {
            val t = ex.sampleTime
            if (t < 0) { aEnd = true; break }
            if (t > upToUs) break
            abuf.clear()
            val n = ex.readSampleData(abuf, 0)
            if (n < 0) { aEnd = true; break }
            abuf.position(0)
            abuf.limit(n)
            ainfo.set(0, n, t, ex.sampleFlags)
            muxer.writeSampleData(audioTrack, abuf, ainfo)
            ex.advance()
        }
    }

    /** Takes finished video from the encoder. [untilEnd] waits for the end of the stream. */
    fun drain(untilEnd: Boolean) {
        var waits = 0
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEnd) return
                    if (++waits > 3000) throw RuntimeException("The video encoder did not finish.")
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    val af = audioFormat
                    if (af != null) audioTrack = muxer.addTrack(af)
                    muxer.start()
                    started = true
                }
                oi >= 0 -> {
                    waits = 0
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, ob, info)
                        pumpAudio(info.presentationTimeUs)
                    }
                    val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(oi, false)
                    if (end) return
                }
            }
        }
    }

    fun finish() {
        try {
            codec.signalEndOfInputStream()
            drain(true)
            pumpAudio(Long.MAX_VALUE)
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
        try { inputSurface.release() } catch (e: Exception) { }
        try { muxer.release() } catch (e: Exception) { }
        try { aex?.release() } catch (e: Exception) { }
    }
}
