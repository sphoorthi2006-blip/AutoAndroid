package com.suko.app.upscale

import java.io.File

/** Does the real work for ONE picture: must write the finished PNG to dest, or throw. */
fun interface UpscaleEngine {
    fun upscale(job: UpscaleJob, dest: File, onTile: (done: Int, total: Int) -> Unit)
}

enum class Outcome { OK, DEGRADED, FAILED }

/** OK = AI result. DEGRADED = the fallback (classic) method was used. FAILED = no result: the export must use the original picture. */
class JobResult(val job: UpscaleJob, val outcome: Outcome, val note: String? = null)

/** index is 1-based among the pictures still to do; tileTotal is 0 until the engine reports tiles. */
class RunProgress(val index: Int, val total: Int, val tileDone: Int, val tileTotal: Int, val picturesDone: Int)

class BatchResult(
    val results: List<JobResult>,
    val cancelled: Boolean,
    val stoppedReason: String?,
    val alreadyCached: Int,
    val notStarted: Int,
) {
    val failed: List<JobResult> get() = results.filter { it.outcome == Outcome.FAILED }
    val degraded: List<JobResult> get() = results.filter { it.outcome == Outcome.DEGRADED }
}

/**
 * Step 12 + 15: upscales the pictures of an [UpscalePlan] one at a time into the cache.
 * - Finished results are reused, so pressing Export again after a cancel or crash resumes where it stopped.
 * - Low memory: one retry after onLowMemory(), then the fallback engine (classic method) if there is one.
 * - One bad picture never stops the batch; a full disk does (nothing more can be saved).
 */
class UpscaleRunner(
    private val cache: UpscaleCache,
    private val primary: UpscaleEngine,
    private val fallback: UpscaleEngine? = null,
    private val onLowMemory: () -> Unit = {},
) {
    private class Cancelled : RuntimeException()

    fun run(
        plan: UpscalePlan,
        isCancelled: () -> Boolean = { false },
        onProgress: (RunProgress) -> Unit = {},
        onPicture: (JobResult, seconds: Double) -> Unit = { _, _ -> },
    ): BatchResult {
        cache.cleanLeftovers()
        val todo = plan.todo
        val results = ArrayList<JobResult>()
        var stopped: String? = null
        for ((i, job) in todo.withIndex()) {
            if (isCancelled()) return BatchResult(results, true, null, plan.cachedCount, todo.size - results.size)
            onProgress(RunProgress(i + 1, todo.size, 0, 0, i))
            val t0 = System.nanoTime()
            val r = try {
                runOne(job) { d, t ->
                    if (isCancelled()) throw Cancelled()
                    onProgress(RunProgress(i + 1, todo.size, d, t, i))
                }
            } catch (e: Cancelled) {
                cache.discard(job.key)
                return BatchResult(results, true, null, plan.cachedCount, todo.size - results.size)
            }
            results += r
            onPicture(r, (System.nanoTime() - t0) / 1e9)
            if (r.outcome == Outcome.FAILED && isStorageFull(r.note)) { stopped = "storage is full"; break }
        }
        return BatchResult(results, false, stopped, plan.cachedCount, todo.size - results.size)
    }

    private fun runOne(job: UpscaleJob, onTile: (Int, Int) -> Unit): JobResult {
        var err: Throwable = attempt(primary, job, onTile) ?: return JobResult(job, Outcome.OK)
        if (err is OutOfMemoryError) {
            onLowMemory()
            err = attempt(primary, job, onTile) ?: return JobResult(job, Outcome.OK, "retried after low memory")
        }
        val why = describe(err)
        val fb = fallback
        if (isStorageFull(why) || fb == null) return JobResult(job, Outcome.FAILED, why)
        val err2 = attempt(fb, job, onTile) ?: return JobResult(job, Outcome.DEGRADED, "used the classic method ($why)")
        return JobResult(job, Outcome.FAILED, describe(err2))
    }

    /** Null = a finished result is now in the cache. */
    private fun attempt(engine: UpscaleEngine, job: UpscaleJob, onTile: (Int, Int) -> Unit): Throwable? {
        cache.discard(job.key)
        return try {
            engine.upscale(job, cache.tempFor(job.key), onTile)
            if (cache.commit(job.key)) null else IllegalStateException("the upscaler produced no picture")
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            cache.discard(job.key)
            e
        } catch (e: OutOfMemoryError) {
            cache.discard(job.key)
            e
        }
    }

    private fun describe(e: Throwable): String =
        if (e is OutOfMemoryError) "not enough memory" else (e.message ?: e.javaClass.simpleName)

    private fun isStorageFull(msg: String?): Boolean =
        msg != null && (msg.contains("No space left", ignoreCase = true) || msg.contains("ENOSPC"))
}
