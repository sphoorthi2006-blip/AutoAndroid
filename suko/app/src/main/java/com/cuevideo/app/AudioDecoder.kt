package com.cuevideo.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes any audio file the phone can play into float blocks (see PcmSink). */
object AudioDecoder {
    /** WAV files are read directly (no codec); everything else, and odd WAVs, go through MediaCodec. */
    fun decodeUri(ctx: Context, uri: Uri, name: String, sink: PcmSink, cancelled: () -> Boolean) {
        if (extOf(name) == "wav") {
            var delivered = false
            try {
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    WavStream.read(ins) { ch, n, r -> delivered = true; sink(ch, n, r) }
                }
                return
            } catch (e: UnsupportedAudio) {
                if (delivered) throw e
            }
        }
        decodeWithCodec(ctx, uri, sink, cancelled)
    }

    /** The bundled click is a plain 16-bit WAV. */
    fun decodeAssetWav(ctx: Context, asset: String, sink: PcmSink) {
        ctx.assets.open(asset).use { WavStream.read(it, sink) }
    }

    private fun decodeWithCodec(ctx: Context, uri: Uri, sink: PcmSink, cancelled: () -> Boolean) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) { track = i; break }
            }
            if (track < 0) throw UnsupportedAudio("no sound found in this file")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = 2                                   // ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (cancelled()) return
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi >= 0 -> {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.LITTLE_ENDIAN)
                            val bps = if (encoding == 4) 4 else 2
                            val frames = info.size / (bps * channels)
                            if (frames > 0) {
                                val ch = Array(channels) { FloatArray(frames) }
                                if (encoding == 4) {
                                    val fb = ob.asFloatBuffer()
                                    for (f in 0 until frames) for (c in 0 until channels) ch[c][f] = fb.get()
                                } else {
                                    val sb = ob.asShortBuffer()
                                    for (f in 0 until frames) for (c in 0 until channels) ch[c][f] = sb.get() / 32768f
                                }
                                sink(ch, frames, rate)
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { ex.release() } catch (e: Exception) { }
        }
    }
}
