package com.auto.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ByteOrder

/** Decodes mp3/m4a/wav/... to 16 kHz mono float samples (what core/AudioPauses and Align expect). Streams, so long files do not blow the memory. */
object AudioDecoder {
    const val TARGET = 16000

    fun durationSec(f: File): Double? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.path)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        r.release(); ms?.let { it / 1000.0 }
    } catch (e: Exception) { null }

    private class FloatBuf {
        var a = FloatArray(1 shl 20); var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun result() = a.copyOf(n)
    }

    /** Returns samples at 16 kHz mono and their length in seconds. */
    fun decode(file: File): Pair<FloatArray, Double> {
        val ex = MediaExtractor(); ex.setDataSource(file.path)
        var track = -1; var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) { ex.release(); throw com.auto.core.PipelineError("No audio track found in '${file.name}'.") }
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val out = FloatBuf()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        // box-filter resampler: average all native samples that fall into one 16 kHz output sample
        var ratio = sampleRate.toDouble() / TARGET; var nextEdge = ratio; var idx = 0L; var acc = 0.0; var cnt = 0
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val nf = codec.outputFormat
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    ratio = sampleRate.toDouble() / TARGET; nextEdge = idx + ratio
                }
                oi >= 0 -> {
                    val buf = codec.getOutputBuffer(oi)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = sb.remaining() / channels
                    for (f in 0 until frames) {
                        var s = 0.0
                        for (c in 0 until channels) s += sb.get()
                        acc += s / channels / 32768.0; cnt++; idx++
                        if (idx >= nextEdge) { out.add((acc / cnt).toFloat()); acc = 0.0; cnt = 0; nextEdge += ratio }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        val x = out.result()
        return Pair(x, x.size / TARGET.toDouble())
    }
}
