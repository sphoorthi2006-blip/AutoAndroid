package com.suko.app.upscale

import com.suko.app.intake.FileKind
import com.suko.app.intake.FolderNode
import com.suko.app.intake.IntakeEntry
import java.io.InputStream

/**
 * Step 14, pure part: connects the Auto Editor's pictures (name + uri) to the upscale plan from step 11,
 * and turns finished results into "use this file instead of the original" for the render phase.
 * No Android classes here, so it is tested on the JVM.
 */
object ExportUpscale {
    class Pic(val name: String, val uri: String)

    /** A picture that stays where the user has it; its bytes are read through [opener] only when hashed or upscaled. */
    private class UriNode(override val name: String, private val uri: String, private val opener: (String) -> InputStream) : FolderNode {
        override val isDirectory: Boolean get() = false
        override val size: Long get() = 0L
        override val location: String get() = uri
        override fun children(): List<FolderNode> = emptyList()
        override fun open(): InputStream = opener(uri)
    }

    fun prepare(pics: List<Pic>, cache: UpscaleCache, settings: UpscaleSettings, opener: (String) -> InputStream): UpscalePlan {
        val entries = pics.map { IntakeEntry(it.name, FileKind.IMAGE, 0L, UriNode(it.name, it.uri, opener), null) }
        return UpscalePlan.build(entries, cache, settings)
    }

    /** Where the original picture is read from (the uri it was given with). */
    fun sourceUri(job: UpscaleJob): String = job.entry.node!!.location

    /**
     * original uri -> uri of the finished upscaled file. Only pictures with a finished result are in the map,
     * so a failed picture is simply not found there and the render uses the original (step 15 rule).
     */
    fun swapMap(plan: UpscalePlan, cache: UpscaleCache): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (j in plan.jobs) if (cache.has(j.key)) m[sourceUri(j)] = "file://" + cache.fileFor(j.key).absolutePath
        return m
    }

    /** One plain sentence for the export summary, or null when everything went normally. */
    fun note(b: BatchResult): String? {
        val parts = ArrayList<String>()
        if (b.failed.isNotEmpty()) parts += "${b.failed.size} picture(s) could not be upscaled and were used as they are"
        if (b.degraded.isNotEmpty()) parts += "${b.degraded.size} picture(s) used the classic upscale method"
        b.stoppedReason?.let { parts += "upscaling stopped: $it" }
        return if (parts.isEmpty()) null else parts.joinToString("; ") + "."
    }
}
