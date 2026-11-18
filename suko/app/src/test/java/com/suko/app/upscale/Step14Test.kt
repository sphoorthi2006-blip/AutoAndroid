package com.suko.app.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Step14Test {
    private val bytes = mapOf("u/1" to "one", "u/2" to "two", "u/3" to "two", "u/4" to "four")
    private val opener: (String) -> java.io.InputStream = { ByteArrayInputStream(bytes.getValue(it).toByteArray()) }
    private fun pics(vararg n: Int) = n.map { ExportUpscale.Pic("$it.png", "u/$it") }

    @Test
    fun theSameContentFromTwoPlacesIsUpscaledOnceButBothGetTheResult() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val plan = ExportUpscale.prepare(pics(1, 2, 3), cache, UpscaleSettings(true), opener)
        assertEquals(3, plan.total)
        assertEquals(2, plan.todo.size)                       // pictures 2 and 3 have identical bytes
        var calls = 0
        UpscaleRunner(cache, UpscaleEngine { _, d, _ -> calls++; d.writeBytes(ByteArray(9)) }).run(plan)
        assertEquals(2, calls)
        val swap = ExportUpscale.swapMap(plan, cache)
        assertEquals(setOf("u/1", "u/2", "u/3"), swap.keys)
        assertEquals(swap["u/2"], swap["u/3"])
        assertTrue(swap.values.all { it.startsWith("file://") && it.endsWith(".png") })
    }

    @Test
    fun aFailedPictureIsNotInTheSwapSoTheRenderUsesTheOriginal() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val plan = ExportUpscale.prepare(pics(1, 4), cache, UpscaleSettings(true), opener)
        val flaky = UpscaleEngine { j, d, _ -> if (ExportUpscale.sourceUri(j) == "u/4") throw IllegalStateException("bad picture") else d.writeBytes(ByteArray(9)) }
        val batch = UpscaleRunner(cache, flaky).run(plan)
        val swap = ExportUpscale.swapMap(plan, cache)
        assertEquals(setOf("u/1"), swap.keys)
        assertEquals("1 picture(s) could not be upscaled and were used as they are.", ExportUpscale.note(batch))
    }

    @Test
    fun cancelThenExportAgainOnlyDoesTheRest() {
        val cache = UpscaleCache(RunnerKit.tmp())
        var calls = 0
        val engine = UpscaleEngine { _, d, _ -> calls++; d.writeBytes(ByteArray(9)) }
        val first = ExportUpscale.prepare(pics(1, 2, 4), cache, UpscaleSettings(true), opener)
        var done = 0
        val b1 = UpscaleRunner(cache, engine).run(first, isCancelled = { done >= 1 }, onPicture = { _, _ -> done++ })
        assertTrue(b1.cancelled)
        assertEquals(1, calls)
        val again = ExportUpscale.prepare(pics(1, 2, 4), cache, UpscaleSettings(true), opener)
        assertEquals(2, again.todo.size)                      // the finished one is reused
        UpscaleRunner(cache, engine).run(again)
        assertEquals(3, calls)
        assertEquals(3, ExportUpscale.swapMap(again, cache).size)
    }

    @Test
    fun changingTheSettingsDoesNotReuseOldResults() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val a = ExportUpscale.prepare(pics(1), cache, UpscaleSettings(true), opener)
        UpscaleRunner(cache, RunnerKit.good).run(a)
        val b = ExportUpscale.prepare(pics(1), cache, UpscaleSettings(false), opener)
        assertEquals(1, b.todo.size)
        assertTrue(ExportUpscale.swapMap(b, cache).isEmpty())
    }

    @Test
    fun theSummaryNoteNamesFallbacksAndStops() {
        val cache = UpscaleCache(RunnerKit.tmp())
        val ok = UpscaleRunner(cache, RunnerKit.good).run(ExportUpscale.prepare(pics(1), cache, UpscaleSettings(true), opener))
        assertNull(ExportUpscale.note(ok))
        val c2 = UpscaleCache(RunnerKit.tmp())
        val bad = UpscaleEngine { _, _, _ -> throw IllegalStateException("AI step failed") }
        val deg = UpscaleRunner(c2, bad, RunnerKit.good).run(ExportUpscale.prepare(pics(1), c2, UpscaleSettings(true), opener))
        assertEquals("1 picture(s) used the classic upscale method.", ExportUpscale.note(deg))
        val c3 = UpscaleCache(RunnerKit.tmp())
        val full = UpscaleEngine { _, _, _ -> throw java.io.IOException("No space left on device") }
        val st = UpscaleRunner(c3, full).run(ExportUpscale.prepare(pics(1, 4), c3, UpscaleSettings(true), opener))
        assertTrue(ExportUpscale.note(st)!!.contains("upscaling stopped: storage is full"))
        assertFalse(ExportUpscale.swapMap(ExportUpscale.prepare(pics(1), c3, UpscaleSettings(true), opener), c3).isNotEmpty())
    }
}
