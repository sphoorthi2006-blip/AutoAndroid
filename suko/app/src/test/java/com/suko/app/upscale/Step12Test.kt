package com.suko.app.upscale

import com.suko.app.intake.FileKind
import com.suko.app.intake.IntakeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Shared helpers for the runner tests (steps 12 and 15). */
object RunnerKit {
    fun tmp(): File = Files.createTempDirectory("suko-run").toFile()
    fun entry(name: String) = IntakeEntry(name, FileKind.IMAGE, 10, null, File(name))
    fun plan(cache: UpscaleCache, vararg names: String, same: Boolean = false): UpscalePlan =
        UpscalePlan.build(names.map { entry(it) }, cache, UpscaleSettings(true)) { e -> if (same) "same" else e.name }
    val good = UpscaleEngine { _, dest, onTile -> onTile(1, 2); onTile(2, 2); dest.writeBytes(ByteArray(20)) }
}

class Step12Test {
    @Test
    fun upscalesEveryPictureInOrderIntoTheCacheAndReportsProgress() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val plan = RunnerKit.plan(cache, "1.png", "2.png", "3.png")
        val seen = ArrayList<String>()
        val res = UpscaleRunner(cache, RunnerKit.good).run(plan, onProgress = { seen += "${it.index}/${it.total}:${it.tileDone}/${it.tileTotal}:${it.picturesDone}" })
        assertEquals(3, res.results.size)
        assertTrue(res.results.all { it.outcome == Outcome.OK })
        assertTrue(plan.jobs.all { cache.has(it.key) })
        assertEquals(listOf("1/3:0/0:0", "1/3:1/2:0", "1/3:2/2:0", "2/3:0/0:1"), seen.take(4))
        assertEquals("3/3:2/2:2", seen.last())
    }

    @Test
    fun cachedAndIdenticalPicturesAreNotDoneAgain() {
        val cache = UpscaleCache(RunnerKit.tmp())
        var calls = 0
        val counting = UpscaleEngine { _, dest, _ -> calls++; dest.writeBytes(ByteArray(5)) }
        val same = RunnerKit.plan(cache, "a.png", "b.png", "c.png", same = true)
        val r1 = UpscaleRunner(cache, counting).run(same)
        assertEquals(1, calls)
        assertEquals(1, r1.results.size)
        val r2 = UpscaleRunner(cache, counting).run(RunnerKit.plan(cache, "a.png", "b.png", "c.png", same = true))
        assertEquals(1, calls)
        assertEquals(0, r2.results.size)
        assertEquals(3, r2.alreadyCached)
    }

    @Test
    fun cancelBetweenPicturesAndInsideAPictureStopsCleanly() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val plan = RunnerKit.plan(cache, "1.png", "2.png", "3.png")
        var done = 0
        val r = UpscaleRunner(cache, RunnerKit.good).run(plan, isCancelled = { done >= 1 }, onPicture = { _, _ -> done++ })
        assertTrue(r.cancelled)
        assertEquals(1, r.results.size)
        assertEquals(2, r.notStarted)
        assertTrue(cache.has(plan.jobs[0].key))
        // cancel while a picture is being worked on: no half file may stay
        val cache2 = UpscaleCache(RunnerKit.tmp())
        val plan2 = RunnerKit.plan(cache2, "1.png")
        var flag = false
        val slow = UpscaleEngine { _, dest, onTile -> dest.writeBytes(ByteArray(3)); flag = true; onTile(1, 4) }
        val r2 = UpscaleRunner(cache2, slow).run(plan2, isCancelled = { flag })
        assertTrue(r2.cancelled)
        assertFalse(cache2.has(plan2.jobs[0].key))
        assertFalse(cache2.tempFor(plan2.jobs[0].key).exists())
    }

    @Test
    fun oneFailureIsRecordedAndTheBatchContinues() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val plan = RunnerKit.plan(cache, "1.png", "bad.png", "3.png")
        val engine = UpscaleEngine { job, dest, _ ->
            if (job.entry.name == "bad.png") throw IllegalArgumentException("cannot decode")
            dest.writeBytes(ByteArray(9))
        }
        val r = UpscaleRunner(cache, engine).run(plan)
        assertEquals(listOf(Outcome.OK, Outcome.FAILED, Outcome.OK), r.results.map { it.outcome })
        assertEquals("cannot decode", r.failed[0].note)
        assertEquals("bad.png", r.failed[0].job.entry.name)
        assertFalse(cache.has(r.failed[0].job.key))
    }
}
