package com.ils.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Writes an H.264 video-only MP4 from ARGB frames. Frames go into the encoder as bytes with exact timestamps
 * (frame number / fps), so the video is exactly as long as planned whatever the phone's speed.
 * write(null) repeats the previous frame without converting it again.
 */
class VideoWriter(path: String, private val w: Int, private val h: Int, private val fps: Int) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var frame = 0L
    private var released = false

    private val yPlane = ByteArray(w * h)
    private val uPlane = ByteArray(w / 2 * (h / 2))
    private val vPlane = ByteArray(w / 2 * (h / 2))

    init {
        require(w % 2 == 0 && h % 2 == 0) { "Video size must be even." }
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        f.setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * fps * 0.15).toInt().coerceIn(1_000_000, 20_000_000))
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(argb: IntArray?) {
        if (argb != null) toI420(argb)
        var idx: Int
        while (true) {
            idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) break
            drain(false)
        }
        val img = codec.getInputImage(idx) ?: throw IllegalStateException("The video encoder gave no input image.")
        copyPlane(img.planes[0], yPlane, w, h)
        copyPlane(img.planes[1], uPlane, w / 2, h / 2)
        copyPlane(img.planes[2], vPlane, w / 2, h / 2)
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, frame * 1_000_000L / fps, 0)
        frame++
        drain(false)
    }

    private fun copyPlane(p: android.media.Image.Plane, src: ByteArray, pw: Int, ph: Int) {
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        if (ps == 1) {
            for (r in 0 until ph) { buf.position(r * rs); buf.put(src, r * pw, pw) }
        } else {
            for (r in 0 until ph) {
                val b = r * rs
                for (c in 0 until pw) buf.put(b + c * ps, src[r * pw + c])
            }
        }
    }

    /** ARGB -> I420 (BT.601, limited range). Chroma is the average of each 2 x 2 block. */
    private fun toI420(px: IntArray) {
        val cw = w / 2
        for (by in 0 until h / 2) {
            for (bx in 0 until cw) {
                var rs = 0; var gs = 0; var bs = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val x = bx * 2 + dx; val y = by * 2 + dy
                    val c = px[y * w + x]
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                    yPlane[y * w + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                    rs += r; gs += g; bs += b
                }
                val r = rs shr 2; val g = gs shr 2; val b = bs shr 2
                uPlane[by * cw + bx] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                vPlane[by * cw + bx] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            }
        }
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (++waits > 3000) throw IllegalStateException("The video encoder did not finish.")
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                oi >= 0 -> {
                    val buf: ByteBuffer = codec.getOutputBuffer(oi) ?: throw IllegalStateException("No encoder output buffer.")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Ends the video and closes the file. */
    fun finish() {
        var idx: Int
        while (true) {
            idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) break
            drain(false)
        }
        codec.queueInputBuffer(idx, 0, 0, frame * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        release(true)
    }

    /** Frees the encoder and the muxer. Safe to call after an error. */
    fun release(clean: Boolean = false) {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        try { if (muxerStarted && clean) muxer.stop() } catch (_: Throwable) {}
        try { muxer.release() } catch (_: Throwable) {}
    }
}
