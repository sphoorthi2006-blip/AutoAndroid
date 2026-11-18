package com.suko.app.upscale

import com.suko.app.intake.FileKind
import com.suko.app.intake.IntakeEntry
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** What makes two upscale results interchangeable. Change [PIPELINE] whenever the engine or models change. */
class UpscaleSettings(val removeMark: Boolean, val scale: Int = 2, val pipeline: String = PIPELINE) {
    companion object {
        const val PIPELINE = "sukko-realesr-animevideov3-v1"
    }
}

object UpscaleKeys {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** SHA-256 of the picture's bytes. Content based, so the same picture is recognised from any ZIP or folder. */
    fun contentHash(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun contentHashOf(e: IntakeEntry): String =
        (e.file?.inputStream() ?: e.node!!.open()).use { contentHash(it) }

    /** Cache key = picture content + every setting that changes the result. 32 hex characters. */
    fun key(contentHash: String, s: UpscaleSettings): String {
        val text = "$contentHash|mark=${s.removeMark}|x${s.scale}|${s.pipeline}"
        return hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray())).take(32)
    }
}

/** Folder of finished upscaled pictures (PNG, one per key). A result is only visible once fully written. */
class UpscaleCache(val dir: File) {
    init { dir.mkdirs() }

    fun fileFor(key: String) = File(dir, "$key.png")
    fun tempFor(key: String) = File(dir, "$key.part")

    fun has(key: String): Boolean = fileFor(key).let { it.isFile && it.length() > 0 }

    /** Call after the temp file is completely written. Moves it into place; false if there was nothing to move. */
    fun commit(key: String): Boolean {
        val t = tempFor(key)
        if (!t.isFile || t.length() == 0L) { t.delete(); return false }
        val f = fileFor(key)
        f.delete()
        return t.renameTo(f)
    }

    fun discard(key: String) { tempFor(key).delete() }

    /** Removes half-written files left by a crash or cancel. Returns how many. */
    fun cleanLeftovers(): Int = (dir.listFiles { x -> x.name.endsWith(".part") } ?: emptyArray()).count { it.delete() }

    fun sizeBytes(): Long = (dir.listFiles { x -> x.name.endsWith(".png") } ?: emptyArray()).sumOf { it.length() }

    /** Deletes the oldest results until the cache fits maxBytes. Keys in keep (the current project) are never deleted. */
    fun trimTo(maxBytes: Long, keep: Set<String> = emptySet()): Int {
        var total = sizeBytes()
        var removed = 0
        val old = (dir.listFiles { x -> x.name.endsWith(".png") } ?: emptyArray()).sortedBy { it.lastModified() }
        for (f in old) {
            if (total <= maxBytes) break
            if (f.name.removeSuffix(".png") in keep) continue
            val n = f.length()
            if (f.delete()) { total -= n; removed++ }
        }
        return removed
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

/** One picture to upscale. cached = a finished result already exists, so the work is skipped, not the picture. */
class UpscaleJob(val entry: IntakeEntry, val key: String, val output: File, val cached: Boolean)

/**
 * Step 11 rule (owner): EVERY picture is upscaled, whatever its size. The only thing never redone is a picture
 * whose identical result is already in the cache. The engine keeps its own memory protection for huge pictures.
 */
class UpscalePlan(val jobs: List<UpscaleJob>) {
    val total: Int get() = jobs.size
    val cachedCount: Int get() = jobs.count { it.cached }
    /** Work still to do: not cached, and one job per distinct picture content. */
    val todo: List<UpscaleJob> get() = jobs.filter { !it.cached }.distinctBy { it.key }

    companion object {
        fun build(
            entries: List<IntakeEntry>,
            cache: UpscaleCache,
            settings: UpscaleSettings,
            hash: (IntakeEntry) -> String = { UpscaleKeys.contentHashOf(it) },
        ): UpscalePlan = UpscalePlan(
            entries.filter { it.kind == FileKind.IMAGE }.map { e ->
                val key = UpscaleKeys.key(hash(e), settings)
                UpscaleJob(e, key, cache.fileFor(key), cache.has(key))
            },
        )
    }
}
