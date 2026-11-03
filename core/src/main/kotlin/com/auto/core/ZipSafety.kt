package com.auto.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * Phase 1 (Section 13): ZIP import safety limits (zip-slip defense + entry-count, single-entry-size,
 * and total-expanded-size limits with clear messages) and Autosave/Cancellation concurrency guards.
 */
data class ZipLimits(
    val maxEntries: Int = 5_000,
    val maxSingleEntryBytes: Long = 100L * 1024L * 1024L,       // 100 MB per file
    val maxTotalExpandedBytes: Long = 2_048L * 1024L * 1024L    // 2 GB total extracted
)

object ZipSafety {
    val DEFAULT_LIMITS = ZipLimits()

    /** Verifies that `dest` resolves strictly inside `rootDir` (zip-slip traversal protection). */
    fun ensureInsideRoot(rootDir: File, entryName: String, dest: File): File {
        val canonicalRoot = rootDir.canonicalFile
        val canonicalDest = dest.canonicalFile
        val rootPrefix = canonicalRoot.path + File.separator
        if (!canonicalDest.path.startsWith(rootPrefix)) {
            throw PipelineError("ZIP security error: entry '$entryName' attempts path traversal outside target folder.")
        }
        return canonicalDest
    }

    /** Copies `input` to `output` while enforcing both single-entry and cumulative total expanded byte limits. */
    fun copyBounded(
        entryName: String,
        input: InputStream,
        output: OutputStream,
        limits: ZipLimits,
        totalAccumulated: Long
    ): Long {
        val buf = ByteArray(65_536)
        var entryBytes = 0L
        var runningTotal = totalAccumulated
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            entryBytes += n
            runningTotal += n
            if (entryBytes > limits.maxSingleEntryBytes) {
                throw PipelineError(
                    "ZIP limit exceeded: '$entryName' is larger than the single-file limit (${MediaLibrary.humanSize(limits.maxSingleEntryBytes)})."
                )
            }
            if (runningTotal > limits.maxTotalExpandedBytes) {
                throw PipelineError(
                    "ZIP limit exceeded: total extracted size exceeds ${MediaLibrary.humanSize(limits.maxTotalExpandedBytes)}."
                )
            }
            output.write(buf, 0, n)
        }
        return runningTotal
    }

    /** Safe picture extraction from ZIP enforcing entry-count, single-entry, total-size, and zip-slip rules. */
    fun extractPicturesFromZip(
        zip: File,
        outDir: File,
        imageExt: Set<String> = Pipeline.IMAGE_EXT,
        limits: ZipLimits = DEFAULT_LIMITS
    ): List<PicRef> {
        val out = ArrayList<PicRef>()
        val root = outDir.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        ZipFile(zip).use { z ->
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                entryCount++
                if (entryCount > limits.maxEntries) {
                    throw PipelineError("ZIP limit exceeded: archive has more than ${limits.maxEntries} entries.")
                }
                if (e.isDirectory) continue
                val rawParts = e.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                if (rawParts.isEmpty()) continue
                if (rawParts.any { it == ".." }) {
                    throw PipelineError("ZIP security error: entry '${e.name}' contains parent path traversal ('..').")
                }
                if (rawParts.any { it.startsWith(".") || it == "__MACOSX" }) continue
                val ext = rawParts.last().substringAfterLast('.', "").lowercase()
                if (ext !in imageExt) continue
                if (e.size > limits.maxSingleEntryBytes) {
                    throw PipelineError(
                        "ZIP limit exceeded: '${rawParts.last()}' (${MediaLibrary.humanSize(e.size)}) exceeds single-file limit (${MediaLibrary.humanSize(limits.maxSingleEntryBytes)})."
                    )
                }
                val dest = ensureInsideRoot(root, e.name, File(root, rawParts.joinToString("/")))
                dest.parentFile.mkdirs()
                try {
                    z.getInputStream(e).use { input ->
                        dest.outputStream().use { output ->
                            totalBytes = copyBounded(rawParts.last(), input, output, limits, totalBytes)
                        }
                    }
                } catch (ex: Throwable) {
                    dest.delete()
                    throw ex
                }
                out.add(PicRef(zip.path, rawParts, dest.path))
            }
        }
        return out
    }
}

/**
 * Prevents older out-of-order autosaves from overwriting a newer timeline/manifest state.
 * Monotonically tracks the latest issued sequence number and the latest committed sequence number.
 */
class AutosaveCoordinator {
    private val issuedSeq = AtomicLong(0L)
    private var committedSeq = 0L

    /** Issues a new monotonically increasing sequence token for a pending save. */
    fun nextSequence(): Long = issuedSeq.incrementAndGet()

    val latestIssued: Long get() = issuedSeq.get()

    @Synchronized
    fun latestCommitted(): Long = committedSeq

    /**
     * Executes `saveAction` only if `seq` is strictly newer than any already-committed save
     * AND equals the latest issued sequence (skipping superseded intermediate saves).
     * Returns the result of `saveAction`, or `null` if superseded by a newer sequence.
     */
    @Synchronized
    fun <T> runIfNewest(seq: Long, saveAction: () -> T): T? {
        if (seq < issuedSeq.get() || seq <= committedSeq) return null
        val res = saveAction()
        committedSeq = seq
        return res
    }
}

/**
 * Token for a cancellable background task. Once cancelled, `runIfActive` never mutates state.
 */
class TaskToken {
    private val cancelledFlag = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelledFlag.get()

    fun cancel() {
        cancelledFlag.set(true)
    }

    inline fun runIfActive(block: () -> Unit): Boolean {
        if (isCancelled) return false
        block()
        return true
    }
}
