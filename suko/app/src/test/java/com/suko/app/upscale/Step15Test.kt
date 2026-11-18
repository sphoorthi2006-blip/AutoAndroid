package com.suko.app.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class Step15Test {
    @Test
    fun lowMemoryRetriesOnceThenSucceeds() {
        val cache = UpscaleCache(RunnerKit.tmp())
        var tries = 0
        var freed = 0
        val engine = UpscaleEngine { _, dest, _ -> if (tries++ == 0) throw OutOfMemoryError() else dest.writeBytes(ByteArray(4)) }
        val r = UpscaleRunner(cache, engine, onLowMemory = { freed++ }).run(RunnerKit.plan(cache, "1.png"))
        assertEquals(Outcome.OK, r.results[0].outcome)
        assertEquals("retried after low memory", r.results[0].note)
        assertEquals(1, freed)
        assertEquals(2, tries)
    }

    @Test
    fun stillFailingFallsBackToTheClassicMethodAndSaysSo() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val bad = UpscaleEngine { _, _, _ -> throw OutOfMemoryError() }
        val classic = UpscaleEngine { _, dest, _ -> dest.writeBytes(ByteArray(8)) }
        val plan = RunnerKit.plan(cache, "1.png")
        val r = UpscaleRunner(cache, bad, classic).run(plan)
        assertEquals(Outcome.DEGRADED, r.results[0].outcome)
        assertTrue(r.results[0].note!!.contains("classic") && r.results[0].note!!.contains("not enough memory"))
        assertTrue(cache.has(plan.jobs[0].key))
        assertEquals(1, r.degraded.size)
    }

    @Test
    fun fallbackFailingToo_orNoFallback_meansFailedAndTheBatchGoesOn() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val boom = UpscaleEngine { _, _, _ -> throw IllegalStateException("gpu lost") }
        val plan = RunnerKit.plan(cache, "1.png", "2.png")
        val r = UpscaleRunner(cache, boom, boom).run(plan)
        assertEquals(listOf(Outcome.FAILED, Outcome.FAILED), r.results.map { it.outcome })
        assertEquals(2, r.failed.size)
        val empty = UpscaleEngine { _, dest, _ -> dest.writeBytes(ByteArray(0)) }
        val r2 = UpscaleRunner(UpscaleCache(RunnerKit.tmp()), empty).run(RunnerKit.plan(UpscaleCache(RunnerKit.tmp()), "x.png"))
        assertEquals(Outcome.FAILED, r2.results[0].outcome)
        assertTrue(r2.results[0].note!!.contains("no picture"))
    }

    @Test
    fun aFullDiskStopsTheBatchWithAReason() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val engine = UpscaleEngine { job, dest, _ ->
            if (job.entry.name == "2.png") throw IOException("No space left on device")
            dest.writeBytes(ByteArray(6))
        }
        val r = UpscaleRunner(cache, engine, fallback = RunnerKit.good).run(RunnerKit.plan(cache, "1.png", "2.png", "3.png", "4.png"))
        assertEquals("storage is full", r.stoppedReason)
        assertEquals(2, r.results.size)
        assertEquals(2, r.notStarted)
        assertEquals(Outcome.FAILED, r.results[1].outcome)
    }

    @Test
    fun resumeAfterAnInterruptionOnlyDoesTheRest() {
        val dir = RunnerKit.tmp()
        val cache = UpscaleCache(dir)
        val names = arrayOf("1.png", "2.png", "3.png", "4.png", "5.png")
        var done = 0
        UpscaleRunner(cache, RunnerKit.good).run(RunnerKit.plan(cache, *names), isCancelled = { done >= 2 }, onPicture = { _, _ -> done++ })
        // simulate a crash that left a half-written file for picture 3
        val third = RunnerKit.plan(cache, *names).jobs[2]
        cache.tempFor(third.key).writeBytes(ByteArray(5))
        val reopened = UpscaleCache(dir)
        val calls = ArrayList<String>()
        val engine = UpscaleEngine { job, dest, _ -> calls += job.entry.name; dest.writeBytes(ByteArray(6)) }
        val r = UpscaleRunner(reopened, engine).run(RunnerKit.plan(reopened, *names))
        assertEquals(listOf("3.png", "4.png", "5.png"), calls)
        assertEquals(2, r.alreadyCached)
        assertFalse(reopened.tempFor(third.key).exists().and(reopened.fileFor(third.key).length() == 0L))
        assertTrue(RunnerKit.plan(reopened, *names).jobs.all { it.cached })
    }
}
