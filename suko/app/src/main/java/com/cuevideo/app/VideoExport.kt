package com.cuevideo.app

import android.content.Context
import android.opengl.GLES20
import java.io.File

class ExportCancelled : Exception()

class ExportOutcome(
    val videoFile: File,
    val frames: Int,
    val seconds: Double,
    val audio: AudioResult?,
)

/**
 * Stage 5: audio mix first, then every frame is drawn with OpenGL into the H.264 encoder
 * (sections 8, 9, 10, 11, 12, 15). Stills are drawn again for every frame, headings are drawn per frame
 * from one 2x text bitmap, scene clips are decoded to a texture and drawn with black bars.
 */
object VideoExport {
    private const val AUDIO_SHARE = 0.15      // share of the one progress bar used by the audio mix

    fun run(
        ctx: Context,
        s: UiState,
        plan: ExportPlan,
        clickTimes: List<Double>,
        workDir: File,
        outVideo: File,
        progressOverall: (String, Double) -> Unit,
        cancelled: () -> Boolean,
    ): ExportOutcome? {
        val voice = s.audio
        val audioEnabled = voice != null || s.clicksEnabled
        val total = s.timing?.totalSeconds ?: throw IllegalStateException("No timing yet.")
        val fps = s.fps
        val W = Defaults.W
        val H = Defaults.H

        // One phase only: the progress bar covers audio mix + drawing the video (pictures are used as they are).
        val audioShare = if (audioEnabled) AUDIO_SHARE else 0.0
        val progress: (String, Double) -> Unit = { label, p -> progressOverall(label, p) }

        // 1. Optional audio mix. With no voice-over and clicks off, the MP4 has no audio track.
        val mix = File(workDir, "mix.m4a")
        val audio = if (audioEnabled) AudioJob.run(
            ctx, voice, s.click, s.clicksEnabled, clickTimes, total, s.audioSeconds, mix,
            { label, p -> progress(label, p * audioShare) },
            cancelled,
        ) ?: return null else null

        // 2. video
        outVideo.delete()
        var sink: VideoSink? = null
        var egl: EglCore? = null
        var draw: GlDraw? = null
        val started = System.currentTimeMillis()
        try {
            val sk = VideoSink(outVideo, W, H, fps, if (audio != null) mix else null)
            sink = sk
            val eg = EglCore(sk.inputSurface)
            egl = eg
            eg.makeCurrent()
            val gd = GlDraw(W, H)
            draw = gd
            val renderer = SlideRenderer(ctx)
            val fadeFrames = SlideMath.fadeFrames(fps)

            var frameIdx = 0L
            val totalFrames = plan.totalFrames
            var lastPct = -1
            fun present() {
                if (cancelled()) throw ExportCancelled()
                eg.setPresentationTime(frameIdx * 1_000_000_000L / fps)
                eg.swap()
                frameIdx++
                sk.drain(false)
                val pct = (frameIdx * 100 / totalFrames).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    progress("Drawing video: frame $frameIdx of $totalFrames", audioShare + (1 - audioShare) * 0.97 * frameIdx / totalFrames)
                }
            }

            for (seg in plan.segments) {
                if (cancelled()) throw ExportCancelled()
                when (seg.kind) {
                    SegKind.BLACK -> {
                        for (f in 0 until seg.frames) { gd.clear(); present() }
                    }
                    SegKind.STILL -> {
                        val img = seg.image!!
                        val loaded = try {
                            renderer.loadPicture(img.uri)
                        } catch (e: OutOfMemoryError) {
                            throw RuntimeException("Not enough memory for the picture ${img.name}.")
                        }
                        val bmp = loaded ?: throw RuntimeException("Could not read the picture ${img.name}.")
                        val tex = gd.newTexture()
                        gd.upload(tex, bmp)
                        bmp.recycle()
                        try {
                            val m = GlMath.FULL
                            for (f in 0 until seg.frames) {
                                gd.clear()
                                gd.draw(false, tex, m, 1f, 1f, 1f)
                                present()
                            }
                        } finally { gd.deleteTexture(tex) }
                    }
                    SegKind.HEADING -> {
                        val layer = renderer.headingLayer(seg.text)
                        val lw = layer.width
                        val lh = layer.height
                        val tex = gd.newTexture()
                        gd.upload(tex, layer)
                        layer.recycle()
                        try {
                            val n = seg.frames
                            for (f in 0 until n) {
                                val z = SlideMath.zoomFactor(f, n)
                                val q = SlideMath.fadeBrightness(f, fadeFrames)
                                val b = SlideMath.cropBox(lw, lh, z)
                                val m = GlMath.crop(b.left / lw, b.top / lh, b.right / lw, b.bottom / lh)
                                gd.clear()
                                gd.draw(false, tex, m, 1f, 1f, q.toFloat())
                                present()
                            }
                        } finally { gd.deleteTexture(tex) }
                    }
                    SegKind.SCENE -> {
                        val clip = seg.clip!!
                        val tex = gd.newOesTexture()
                        var dec: ClipDecoder? = null
                        try {
                            dec = ClipDecoder(ctx, clip.uri, clip.name, tex, fps)
                            val lb = GlMath.letterbox(dec.displayW, dec.displayH, W, H)
                            for (f in 0 until seg.frames) {
                                val has = dec.advanceTo(f.toDouble() / fps)
                                gd.clear()
                                if (has) gd.draw(true, tex, dec.texMatrix(), lb.first, lb.second, 1f)
                                present()
                            }
                        } finally {
                            dec?.close()
                            gd.deleteTexture(tex)
                        }
                    }
                }
            }
            if (frameIdx != totalFrames.toLong()) throw RuntimeException("Frame count mismatch ($frameIdx of $totalFrames).")
            progress("Finishing the video file...", audioShare + (1 - audioShare) * 0.97)
            sk.finish()
            sink = null
            return ExportOutcome(outVideo, totalFrames, (System.currentTimeMillis() - started) / 1000.0, audio)
        } catch (e: ExportCancelled) {
            outVideo.delete()
            return null
        } catch (e: Exception) {
            outVideo.delete()
            throw e
        } finally {
            try { draw?.release() } catch (e: Exception) { }
            try { egl?.release() } catch (e: Exception) { }
            try { sink?.release() } catch (e: Exception) { }
            if (audio != null) mix.delete()
        }
    }
}
