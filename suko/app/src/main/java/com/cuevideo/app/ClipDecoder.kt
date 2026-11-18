package com.cuevideo.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.Matrix
import android.view.Surface

class UnsupportedClip(msg: String) : Exception(msg)

/**
 * Decodes one scene clip into an external texture (section 11). The caller asks for output frame times
 * (k / fps, counted from the start of the clip's slot) and this class shows the right source frame:
 * the one whose time is nearest, like ffmpeg's fps filter. After the last frame the picture is held.
 * Use on the GL thread (the one with the EGL context current).
 */
class ClipDecoder(ctx: Context, uri: String, private val name: String, private val oesTex: Int, private val fps: Int) {
    private val st = SurfaceTexture(oesTex)
    private val surface = Surface(st)
    private val extractor = MediaExtractor()
    private var dec: MediaCodec? = null
    private val info = MediaCodec.BufferInfo()
    private val lock = Object()
    private var frameReady = false
    private var inputDone = false
    private var outEos = false
    private var pendingIdx = -1
    private var pendingPts = 0L
    private var base = Long.MIN_VALUE
    private var shown = false
    private val stMat = FloatArray(16)
    private val rotMat = FloatArray(16)
    private val outMat = FloatArray(16)

    /** Size of the picture as it is meant to be seen (after rotation). */
    var displayW = 0
        private set
    var displayH = 0
        private set
    private var rotation = 0

    init {
        try {
            extractor.setDataSource(ctx, Uri.parse(uri), null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw UnsupportedClip("$name has no video picture.")
            extractor.selectTrack(track)
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) ((fmt.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360 else 0
            val swap = rotation == 90 || rotation == 270
            displayW = if (swap) h else w
            displayH = if (swap) w else h
            Matrix.setIdentityM(rotMat, 0)
            Matrix.translateM(rotMat, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(rotMat, 0, rotation.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(rotMat, 0, -0.5f, -0.5f, 0f)
            st.setOnFrameAvailableListener {
                synchronized(lock) { frameReady = true; lock.notifyAll() }
            }
            val d = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(fmt, surface, null, 0)
            d.start()
        } catch (e: UnsupportedClip) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw UnsupportedClip("$name could not be opened (${e.message}).")
        }
    }

    private fun feed() {
        if (inputDone) return
        val d = dec ?: return
        val ii = d.dequeueInputBuffer(0)
        if (ii < 0) return
        val buf = d.getInputBuffer(ii)!!
        val n = extractor.readSampleData(buf, 0)
        if (n < 0) {
            d.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            d.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** Makes the next decoded frame the "pending" one. False when the clip has no more frames. */
    private fun fetch(): Boolean {
        if (pendingIdx >= 0) return true
        if (outEos) return false
        val d = dec ?: return false
        var spins = 0
        while (spins < 3000) {
            feed()
            val oi = d.dequeueOutputBuffer(info, 2000)
            if (oi >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                if (info.size == 0) {
                    d.releaseOutputBuffer(oi, false)
                    if (outEos) return false
                    spins++
                    continue
                }
                pendingIdx = oi
                pendingPts = info.presentationTimeUs
                return true
            }
            spins++
        }
        outEos = true
        return false
    }

    private fun showPending() {
        val d = dec ?: return
        synchronized(lock) { frameReady = false }
        d.releaseOutputBuffer(pendingIdx, true)
        pendingIdx = -1
        var ok = false
        synchronized(lock) {
            val end = System.currentTimeMillis() + 3000
            while (!frameReady) {
                val rem = end - System.currentTimeMillis()
                if (rem <= 0) break
                lock.wait(rem)
            }
            ok = frameReady
            frameReady = false
        }
        if (ok) {
            st.updateTexImage()
            st.getTransformMatrix(stMat)
            Matrix.multiplyMM(outMat, 0, stMat, 0, rotMat, 0)
            shown = true
        }
    }

    /** Brings the texture up to output time [t] seconds. Returns true once a picture exists. */
    fun advanceTo(t: Double): Boolean {
        val limit = t + 0.5 / fps
        while (fetch()) {
            if (base == Long.MIN_VALUE) base = pendingPts
            val ts = (pendingPts - base) / 1_000_000.0
            if (ts >= limit) break
            showPending()
        }
        return shown
    }

    /** Texture matrix for the external texture (valid after advanceTo returned true). */
    fun texMatrix(): FloatArray = outMat

    fun close() {
        try { dec?.stop() } catch (e: Exception) { }
        try { dec?.release() } catch (e: Exception) { }
        dec = null
        try { extractor.release() } catch (e: Exception) { }
        try { surface.release() } catch (e: Exception) { }
        try { st.release() } catch (e: Exception) { }
    }
}
