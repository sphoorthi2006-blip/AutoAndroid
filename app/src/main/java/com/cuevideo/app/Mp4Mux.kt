package com.cuevideo.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins a video-only MP4 and the finished audio (.m4a) into one MP4 without re-encoding anything.
 * This lets the audio and the video be made at the same time.
 */
object Mp4Mux {
    private fun firstTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith(prefix)) return i
        }
        throw RuntimeException("No $prefix track found while joining picture and sound.")
    }

    /** Returns false when [cancelled] became true (the half-written [out] is deleted). */
    fun combine(video: File, audio: File, out: File, cancelled: () -> Boolean): Boolean {
        val vx = MediaExtractor()
        val ax = MediaExtractor()
        var mux: MediaMuxer? = null
        var started = false
        var done = false
        try {
            vx.setDataSource(video.path)
            ax.setDataSource(audio.path)
            val vt = firstTrack(vx, "video/")
            val at = firstTrack(ax, "audio/")
            vx.selectTrack(vt)
            ax.selectTrack(at)
            out.delete()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val vOut = m.addTrack(vx.getTrackFormat(vt))
            val aOut = m.addTrack(ax.getTrackFormat(at))
            m.start()
            started = true

            val buf = ByteBuffer.allocateDirect(8 shl 20)
            val info = MediaCodec.BufferInfo()
            var n = 0
            while (true) {
                val tv = vx.sampleTime
                val ta = ax.sampleTime
                if (tv < 0 && ta < 0) break
                val useVideo = ta < 0 || (tv in 0L..ta)
                val ex = if (useVideo) vx else ax
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, ex.sampleTime, ex.sampleFlags)
                m.writeSampleData(if (useVideo) vOut else aOut, buf, info)
                ex.advance()
                if (++n % 512 == 0 && cancelled()) return false
            }
            m.stop()
            started = false
            done = true
            return true
        } finally {
            try { if (started) mux?.stop() } catch (e: Exception) { }
            try { mux?.release() } catch (e: Exception) { }
            try { vx.release() } catch (e: Exception) { }
            try { ax.release() } catch (e: Exception) { }
            if (!done) out.delete()
        }
    }
}
