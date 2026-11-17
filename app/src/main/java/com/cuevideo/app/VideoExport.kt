package com.cuevideo.app

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ExportCancelled : Exception()

class ExportOutcome(
    val videoFile: File,
    val frames: Int,
    val seconds: Double,
    val audio: AudioResult,
    val breakdown: String = "",
)

/** Combines the progress of the sound (own thread) and the picture (this thread) into one bar. */
private class Prog(private val out: (String, Double) -> Unit) {
    private var a = 0.0
    private var v = 0.0
    private var aLabel = "Reading the voice-over..."
    private var vLabel = "Preparing the pictures..."

    @Synchronized fun audio(label: String, p: Double) { aLabel = label; a = p.coerceIn(0.0, 1.0); push() }
    @Synchronized fun audioDone() { a = 1.0; push() }
    @Synchronized fun video(label: String, p: Double) { vLabel = label; v = p.coerceIn(0.0, 1.0); push() }
    @Synchronized fun videoDone() { v = 1.0; push() }
    @Synchronized fun joining() { a = 1.0; v = 1.0; out("Joining picture and sound...", 0.96) }

    private fun push() {
        val label = when {
            v < 1.0 && a < 1.0 -> "$vLabel (sound ${(a * 100).toInt()}%)"
            v < 1.0 -> vLabel
            else -> aLabel
        }
        out(label, 0.95 * (a + v) / 2)
    }
}

/**
 * Decodes the pictures of the still slides on a few background threads, a little ahead of the drawing,
 * so the encoder never waits for a JPEG. At most [ahead] pictures are held in memory at once.
 */
private class PicturePrefetch(ctx: Context, private val segs: List<Segment>, threads: Int, private val ahead: Int) {
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "cue-picture").apply { isDaemon = true }
    }
    private val local = ThreadLocal.withInitial<SlideRenderer> { SlideRenderer(ctx) }
    private val stills: List<Int> = segs.indices.filter { segs[it].kind == SegKind.STILL }
    private val position = HashMap<Int, Int>().also { m -> stills.forEachIndexed { k, i -> m[i] = k } }
    private val pending = HashMap<Int, Future<Bitmap?>>()
    private var submitted = 0

    /** The finished picture of segment [segIndex] (null if the file could not be read). */
    fun take(segIndex: Int): Bitmap? {
        val pos = position[segIndex] ?: throw IllegalStateException("not a still slide")
        while (submitted < stills.size && submitted <= pos + ahead) {
            val i = stills[submitted]
            val uri = segs[i].image!!.uri
            pending[i] = pool.submit(Callable<Bitmap?> { local.get()!!.loadPicture(uri) })
            submitted++
        }
        val f = pending.remove(segIndex) ?: throw IllegalStateException("picture was not requested")
        try {
            return f.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun close() {
        pool.shutdownNow()
        pending.clear()
    }
}

/**
 * Stage 5: sound and picture are made at the same time, then joined (no re-encoding).
 *
 * Picture: every frame is drawn with OpenGL into the H.264 encoder. In fast mode, still slides and black
 * slides send only a few frames to the encoder (the first ones, then one per second, and the last two of
 * the whole video); the time stamp of every sent frame is still frame / fps, so cuts stay frame exact and the
 * picture simply stays until the next frame arrives (variable frame rate). Headings and scene clips always
 * send every frame. Still pictures are decoded ahead on background threads.
 */
object VideoExport {
    private const val REFINE_FRAMES = 4       // fast mode: frames sent at the start of a still slide
    private const val PIC_THREADS = 3
    private const val PIC_AHEAD = 4

    fun run(
        ctx: Context,
        s: UiState,
        plan: ExportPlan,
        clickTimes: List<Double>,
        workDir: File,
        outVideo: File,
        progress: (String, Double) -> Unit,
        cancelled: () -> Boolean,
    ): ExportOutcome? {
        val voice = s.audio ?: throw IllegalStateException("No voice-over chosen.")
        val total = s.timing?.totalSeconds ?: throw IllegalStateException("No timing yet.")
        val fps = s.fps
        val fast = true            // still slides always send only a few frames (see above)
        val W = Defaults.W
        val H = Defaults.H

        val t0 = System.nanoTime()
        fun since(t: Long): Double = (System.nanoTime() - t) / 1e9

        val mix = File(workDir, "mix.m4a")
        val vidOnly = File(workDir, "video_only.mp4")
        outVideo.delete()
        vidOnly.delete()

        val prog = Prog(progress)
        val stop = AtomicBoolean(false)
        val stopOrCancelled = { stop.get() || cancelled() }

        // 1. sound on its own thread (section 13)
        val audioRes = AtomicReference<AudioResult?>(null)
        val audioErr = AtomicReference<Throwable?>(null)
        val audioSecs = AtomicReference(0.0)
        val audioThread = Thread({
            val ta = System.nanoTime()
            try {
                val r = AudioJob.run(
                    ctx, voice, s.click, clickTimes, total, s.audioSeconds, mix,
                    { label, p -> prog.audio(label, p) },
                    stopOrCancelled,
                )
                audioRes.set(r)
                if (r != null) prog.audioDone()
            } catch (t: Throwable) {
                audioErr.set(t)
            } finally {
                audioSecs.set(since(ta))
            }
        }, "cue-audio")

        // 2. picture (this thread owns OpenGL and the encoder)
        var sink: VideoSink? = null
        var egl: EglCore? = null
        var draw: GlDraw? = null
        var pics: PicturePrefetch? = null
        var picWaitNs = 0L
        val tv = System.nanoTime()
        try {
            audioThread.start()
            val sk = VideoSink(vidOnly, W, H, fps, null, fast)
            sink = sk
            val eg = EglCore(sk.inputSurface)
            egl = eg
            eg.makeCurrent()
            val gd = GlDraw(W, H)
            draw = gd
            val renderer = SlideRenderer(ctx)
            val pre = PicturePrefetch(ctx, plan.segments, PIC_THREADS, PIC_AHEAD)
            pics = pre
            val fadeFrames = SlideMath.fadeFrames(fps)

            var frameIdx = 0L
            val totalFrames = plan.totalFrames
            var lastPct = -1
            fun tick() {
                if (cancelled()) throw ExportCancelled()
                frameIdx++
                val pct = (frameIdx * 100 / totalFrames).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    prog.video("Drawing video: frame $frameIdx of $totalFrames", 0.97 * frameIdx / totalFrames)
                }
            }
            fun present() {
                eg.setPresentationTime(frameIdx * 1_000_000_000L / fps)
                eg.swap()
                sk.drain(false)
                tick()
            }
            /** Fast mode: does frame [f] of a still slide that starts at [startFrame] go to the encoder? */
            fun due(f: Int, startFrame: Int): Boolean =
                !fast || f < REFINE_FRAMES || f % fps == 0 || startFrame + f >= totalFrames - 2

            for ((segIndex, seg) in plan.segments.withIndex()) {
                if (cancelled()) throw ExportCancelled()
                if (fast) sk.requestSync()          // every cut starts on a full frame
                when (seg.kind) {
                    SegKind.BLACK -> {
                        for (f in 0 until seg.frames) {
                            if (due(f, seg.startFrame)) {
                                if (fast && f > 0 && f % (2 * fps) == 0) sk.requestSync()
                                gd.clear(); present()
                            } else tick()
                        }
                    }
                    SegKind.STILL -> {
                        val img = seg.image!!
                        val tw = System.nanoTime()
                        val loaded = try {
                            pre.take(segIndex)
                        } catch (e: OutOfMemoryError) {
                            throw RuntimeException("Not enough memory for the picture ${img.name}.")
                        }
                        picWaitNs += System.nanoTime() - tw
                        val bmp = loaded ?: throw RuntimeException("Could not read the picture ${img.name}.")
                        val tex = gd.newTexture()
                        gd.upload(tex, bmp)
                        bmp.recycle()
                        try {
                            val m = GlMath.FULL
                            for (f in 0 until seg.frames) {
                                if (due(f, seg.startFrame)) {
                                    if (fast && f > 0 && f % (2 * fps) == 0) sk.requestSync()
                                    gd.clear()
                                    gd.draw(false, tex, m, 1f, 1f, 1f)
                                    present()
                                } else tick()
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
            prog.video("Finishing the video file...", 0.99)
            sk.finish()
            sink = null
            prog.videoDone()
            val videoSecs = since(tv)

            // 3. wait for the sound, then join the two files
            audioThread.join()
            audioErr.get()?.let { throw it }
            val audio = audioRes.get() ?: return null          // cancelled
            if (cancelled()) return null
            prog.joining()
            val tj = System.nanoTime()
            if (!Mp4Mux.combine(vidOnly, mix, outVideo, cancelled)) return null
            val joinSecs = since(tj)

            val mode = if (fast) "fast mode" else "every frame"
            val breakdown = "Picture ${"%.0f".format(videoSecs)} s ($mode, waited ${"%.0f".format(picWaitNs / 1e9)} s for pictures), " +
                "sound ${"%.0f".format(audioSecs.get())} s (at the same time), joining ${"%.0f".format(joinSecs)} s."
            return ExportOutcome(outVideo, totalFrames, since(t0), audio, breakdown)
        } catch (e: ExportCancelled) {
            outVideo.delete()
            return null
        } catch (e: Exception) {
            outVideo.delete()
            throw e
        } finally {
            stop.set(true)
            try { audioThread.join() } catch (e: InterruptedException) { }
            try { pics?.close() } catch (e: Exception) { }
            try { draw?.release() } catch (e: Exception) { }
            try { egl?.release() } catch (e: Exception) { }
            try { sink?.release() } catch (e: Exception) { }
            mix.delete()
            vidOnly.delete()
        }
    }
}
