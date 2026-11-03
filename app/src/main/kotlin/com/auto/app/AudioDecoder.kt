package com.auto.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.auto.core.AudioPauses
import com.auto.core.AudioStream
import com.auto.core.PipelineError
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes mp3/m4a/wav/... to 16 kHz mono float samples (what core/AudioPauses and Align expect).
 * Phase 1 (Section 13):
 * - Closes MediaMetadataRetriever, MediaCodec, and MediaExtractor in finally blocks on all paths.
 * - Uses PagedSampleBuffer to avoid growing FloatArray doubling copies.
 * - Provides streamPeaks() to generate 20 peaks/sec in O(1) memory without full-file decode allocation.
 */
object AudioDecoder {
    const val TARGET = AudioPauses.SR

    fun durationSec(f: File): Double? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ms?.let { it / 1000.0 }
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /**
     * Streams 16 kHz resampled mono samples to `onSample` with guaranteed MediaCodec / MediaExtractor
     * cleanup in `finally` blocks on every normal or exceptional path. Returns duration in seconds.
     */
    fun streamMono16k(
        file: File,
        cancelled: () -> Boolean = { false },
        onSample: (Float) -> Unit
    ): Double {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            ex.setDataSource(file.path)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) {
                throw PipelineError("No audio track found in '${file.name}'.")
            }
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw PipelineError("Missing audio MIME type in '${file.name}'.")
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            started = true

            var sampleRate = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var ratio = sampleRate.toDouble() / TARGET
            var nextEdge = ratio
            var idx = 0L
            var acc = 0.0
            var cnt = 0
            var emitted = 0L

            while (!outputDone) {
                if (cancelled()) break
                if (!inputDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val nf = c.outputFormat
                        if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        ratio = sampleRate.toDouble() / TARGET
                        nextEdge = idx + ratio
                    }
                    oi >= 0 -> {
                        try {
                            val buf = c.getOutputBuffer(oi)
                            if (buf != null && info.size > 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val chCount = channels.coerceAtLeast(1)
                                val frames = sb.remaining() / chCount
                                for (f in 0 until frames) {
                                    var s = 0.0
                                    for (ch in 0 until chCount) s += sb.get()
                                    acc += s / chCount / 32768.0
                                    cnt++
                                    idx++
                                    if (idx >= nextEdge) {
                                        onSample((acc / cnt).toFloat())
                                        emitted++
                                        acc = 0.0
                                        cnt = 0
                                        nextEdge += ratio
                                    }
                                }
                            }
                        } finally {
                            c.releaseOutputBuffer(oi, false)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return emitted / TARGET.toDouble()
        } finally {
            if (started) {
                try { codec?.stop() } catch (_: Exception) {}
            }
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    /**
     * Generates waveform peaks (default 20/sec) in a single streaming pass without decoding the whole file into a FloatArray.
     */
    fun streamPeaks(
        file: File,
        peaksPerSec: Int = AudioStream.DEFAULT_PEAKS_PER_SEC,
        expectedDurationMs: Long? = null,
        cancelled: () -> Boolean = { false }
    ): FloatArray {
        val streamer = AudioStream.PeakStreamer(TARGET, peaksPerSec)
        streamMono16k(file, cancelled) { sample -> streamer.accept(sample) }
        val expectedBuckets = expectedDurationMs?.takeIf { it > 0L }?.let { ((it / 1000.0) * peaksPerSec).toInt().coerceAtLeast(1) }
        return streamer.finish(expectedBuckets)
    }

    /** Returns samples at 16 kHz mono (collected via bounded PagedSampleBuffer) and their length in seconds. */
    fun decode(file: File): Pair<FloatArray, Double> {
        val paged = AudioStream.PagedSampleBuffer()
        val dur = streamMono16k(file) { sample -> paged.add(sample) }
        val arr = paged.toFloatArray()
        paged.clear()
        return Pair(arr, if (dur > 0.0) dur else arr.size / TARGET.toDouble())
    }
}
