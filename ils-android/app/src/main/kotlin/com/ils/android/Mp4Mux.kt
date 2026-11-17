package com.ils.android

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

object Mp4Mux {
    /**
     * Copies the video track of videoPath and the first audio track of audioEx into one MP4 (no re-encoding).
     * Audio after maxAudioUs is dropped, so the file ends with the planned video length.
     * audioEx must already have its data source set; it is not released here.
     */
    fun combine(videoPath: String, audioEx: MediaExtractor, outPath: String, maxAudioUs: Long, cancelled: () -> Boolean) {
        val vEx = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(videoPath)
            var vi = -1
            for (i in 0 until vEx.trackCount) if (vEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vi = i; break }
            require(vi >= 0) { "The encoded video has no video track." }
            val ai = AudioTranscoder.firstAudioTrack(audioEx)
            vEx.selectTrack(vi)
            audioEx.selectTrack(ai)

            mux = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vt = mux.addTrack(vEx.getTrackFormat(vi))
            val at = mux.addTrack(audioEx.getTrackFormat(ai))
            mux.start()
            started = true

            val buf = ByteBuffer.allocate(2 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (cancelled()) throw CancellationException("Cancelled")
                val vT = vEx.sampleTime
                var aT = audioEx.sampleTime
                if (aT > maxAudioUs) aT = -1
                if (vT < 0 && aT < 0) break
                val useVideo = aT < 0 || (vT in 0..aT)
                val ex = if (useVideo) vEx else audioEx
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                run {
                    val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, ex.sampleTime, flags)
                    mux.writeSampleData(if (useVideo) vt else at, buf, info)
                }
                ex.advance()
            }
        } finally {
            vEx.release()
            try { if (started) mux?.stop() } catch (_: Throwable) {}
            try { mux?.release() } catch (_: Throwable) {}
        }
    }
}
