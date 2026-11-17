package com.ils.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.min

/** Any audio file the phone can decode -> AAC in an .m4a file (needed because MP4 cannot hold mp3 or wav). */
object AudioTranscoder {

    fun firstAudioTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        throw IllegalArgumentException("The audio file has no audio track.")
    }

    /** True when the file already holds AAC, which can be copied into the MP4 without re-encoding. */
    fun isAac(ctx: Context, uri: Uri): Boolean {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            return ex.getTrackFormat(firstAudioTrack(ex)).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
        } finally { ex.release() }
    }

    fun toAac(ctx: Context, uri: Uri, outPath: String, cancelled: () -> Boolean, progress: (Double) -> Unit) {
        Job(ctx, uri, outPath, cancelled, progress).run()
    }

    private class Job(
        val ctx: Context, val uri: Uri, val outPath: String,
        val cancelled: () -> Boolean, val progress: (Double) -> Unit,
    ) {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var track = -1
        var muxStarted = false
        var sampleRate = 0
        var channels = 0
        var pcmFrames = 0L
        var encDone = false
        val encInfo = MediaCodec.BufferInfo()

        fun run() {
            try {
                ex.setDataSource(ctx, uri, null)
                val ti = firstAudioTrack(ex)
                ex.selectTrack(ti)
                val inFmt = ex.getTrackFormat(ti)
                val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION) else 0L
                val d = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
                dec = d
                d.configure(inFmt, null, null, 0)
                d.start()
                muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val info = MediaCodec.BufferInfo()
                var inDone = false
                var decDone = false
                while (!encDone) {
                    if (cancelled()) throw CancellationException("Cancelled")
                    if (!inDone) {
                        val ii = d.dequeueInputBuffer(5_000)
                        if (ii >= 0) {
                            val b = d.getInputBuffer(ii)!!
                            val n = ex.readSampleData(b, 0)
                            if (n < 0) {
                                d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                d.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    if (!decDone) {
                        val oi = d.dequeueOutputBuffer(info, 5_000)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = d.outputFormat
                            startEncoder(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                        } else if (oi >= 0) {
                            if (enc == null) startEncoder(inFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), inFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                            val out = d.getOutputBuffer(oi)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            feed(out)
                            d.releaseOutputBuffer(oi, false)
                            if (durUs > 0) progress((info.presentationTimeUs.toDouble() / durUs).coerceIn(0.0, 1.0))
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decDone = true
                                sendEos()
                            }
                        }
                    }
                    drain()
                }
                try { muxer?.stop() } catch (_: Throwable) {}
            } finally {
                try { dec?.stop() } catch (_: Throwable) {}
                try { dec?.release() } catch (_: Throwable) {}
                try { enc?.stop() } catch (_: Throwable) {}
                try { enc?.release() } catch (_: Throwable) {}
                try { muxer?.release() } catch (_: Throwable) {}
                ex.release()
            }
        }

        fun startEncoder(rate: Int, ch: Int) {
            if (enc != null) return
            require(ch in 1..2) { "The audio has $ch channels. Use a mono or stereo file." }
            sampleRate = rate
            channels = ch
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            e.start()
            enc = e
        }

        fun feed(src: ByteBuffer) {
            val e = enc!!
            val frameBytes = 2 * channels
            while (src.remaining() >= frameBytes) {
                val ei = e.dequeueInputBuffer(5_000)
                if (ei < 0) { drain(); continue }
                val eb = e.getInputBuffer(ei)!!
                eb.clear()
                var len = min(src.remaining(), eb.remaining())
                len -= len % frameBytes
                val lim = src.limit()
                src.limit(src.position() + len)
                eb.put(src)
                src.limit(lim)
                e.queueInputBuffer(ei, 0, len, pcmFrames * 1_000_000L / sampleRate, 0)
                pcmFrames += len / frameBytes
            }
        }

        fun sendEos() {
            val e = enc ?: return
            while (true) {
                val ei = e.dequeueInputBuffer(5_000)
                if (ei >= 0) {
                    e.queueInputBuffer(ei, 0, 0, pcmFrames * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    return
                }
                drain()
            }
        }

        fun drain() {
            val e = enc ?: return
            while (true) {
                val oi = e.dequeueOutputBuffer(encInfo, 0)
                when {
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer!!.addTrack(e.outputFormat)
                        muxer!!.start()
                        muxStarted = true
                    }
                    oi >= 0 -> {
                        val buf = e.getOutputBuffer(oi)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxStarted) {
                            buf.position(encInfo.offset)
                            buf.limit(encInfo.offset + encInfo.size)
                            muxer!!.writeSampleData(track, buf, encInfo)
                        }
                        e.releaseOutputBuffer(oi, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encDone = true; return }
                    }
                }
            }
        }
    }
}
