package com.cuevideo.app

import android.content.Context
import android.net.Uri
import android.opengl.GLES20
import com.suko.app.upscale.AiEngine
import com.suko.app.upscale.ClassicEngine
import com.suko.app.upscale.ExportProgress
import com.suko.app.upscale.ExportUpscale
import com.suko.app.upscale.Outcome
import com.suko.app.upscale.UpscaleCache
import com.suko.app.upscale.UpscaleRunner
import com.suko.app.upscale.UpscaleSettings
import java.io.File
import java.io.IOException

class ExportCancelled : Exception()

class ExportOutcome(
    val videoFile: File,
    val frames: Int,
    val seconds: Double,
    val audio: AudioResult?,
    /** Step 14: plain sentence about pictures that were not upscaled normally, or null. */
    val upscaleNote: String? = null,
)

/**
 * Stage 5: audio mix first, then every frame is drawn with OpenGL into the H.264 encoder
 * (sections 8, 9, 10, 11, 12, 15). Stills are drawn again for every frame, headings are drawn per frame
 * from one 2x text bitmap, scene clips are decoded to a texture and drawn with black bars.
 */
object VideoExport {
    private const val AUDIO_SHARE = 0.15      // share of phase 2 (render) used by the audio mix
    private const val RENDER_FPS_GUESS = 90.0 // assumed encode speed, only used to split the one bar between the phases
    private const val CACHE_LIMIT = 2L shl 30 // keep at most about 2 GB of upscaled pictures

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

        // 0. phase 1 (step 14): upscale every picture the video really shows, one at a time, into the cache.
        //    Finished results are reused, so a second export or a resume after a cancel only does the rest.
        val settings = UpscaleSettings(removeMark = true)
        val cache = UpscaleCache(File(ctx.cacheDir, "upscale"))
        //    The "Upscale images" switch (s.upscale) turns the whole phase off: pictures are then used as they are.
        val pics = if (!s.upscale) emptyList() else plan.segments.filter { it.kind == SegKind.STILL }.mapNotNull { it.image }
            .distinctBy { it.uri }.map { ExportUpscale.Pic(it.name, it.uri) }
        var swap: Map<String, String> = emptyMap()
        var upscaleNote: String? = null
        if (s.upscale) progressOverall("Checking pictures...", 0.0)
        val upPlan = if (!s.upscale) null else try {
            ExportUpscale.prepare(pics, cache, settings) { uri ->
                ctx.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("could not open $uri")
            }
        } catch (e: Exception) {
            upscaleNote = "pictures were not upscaled (${e.message ?: e.javaClass.simpleName})."
            null
        }
        val ep = ExportProgress(upPlan?.todo?.size ?: 0, plan.totalFrames / RENDER_FPS_GUESS)
        if (upPlan != null) {
            if (upPlan.todo.isNotEmpty()) {
                val runner = UpscaleRunner(cache, AiEngine(ctx, settings.removeMark), ClassicEngine(ctx, settings.removeMark)) { System.gc() }
                val batch = runner.run(
                    upPlan, cancelled,
                    onProgress = { r ->
                        progressOverall(
                            ep.label(ExportProgress.Phase.UPSCALE, r.picturesDone),
                            ep.overall(ExportProgress.Phase.UPSCALE, ep.upscaleFraction(r.picturesDone, r.tileDone, r.tileTotal)),
                        )
                    },
                    onPicture = { r, sec -> if (r.outcome == Outcome.OK) ep.pictureDone(sec) },
                )
                if (batch.cancelled) return null
                upscaleNote = ExportUpscale.note(batch)
            }
            swap = ExportUpscale.swapMap(upPlan, cache)
            cache.trimTo(CACHE_LIMIT, upPlan.jobs.map { it.key }.toSet())
        }
        // everything below is phase 2: its own 0..1 is mapped onto the rest of the one bar
        val audioShare = if (audioEnabled) AUDIO_SHARE else 0.0
        val progress: (String, Double) -> Unit = { label, p -> progressOverall(label, ep.overall(ExportProgress.Phase.RENDER, p)) }

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
                            renderer.loadPicture(swap[img.uri] ?: img.uri)
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
            return ExportOutcome(outVideo, totalFrames, (System.currentTimeMillis() - started) / 1000.0, audio, upscaleNote)
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
