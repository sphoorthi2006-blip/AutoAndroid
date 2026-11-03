package com.auto.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.max

/**
 * Priority 1 & Performance Directive Phase 3:
 * True bounded-memory streaming PNG encoder with multi-scanline batching and fast lossless compression (`Deflater.BEST_SPEED = 1`).
 * Writes horizontal strips (`IntArray` of `width * stripH` ARGB_8888 pixels) directly to an OutputStream
 * as standard zlib-compressed PNG `IDAT` chunks using `java.util.zip.Deflater` + `CRC32`,
 * WITHOUT ever allocating a full output `Bitmap` or full output `IntArray(width * height)`.
 *
 * Performance optimization (Phase 3):
 * - Batches multiple scanlines into a bounded `~128 KB` buffer (`batchBuf`) before calling `deflater.setInput`,
 *   reducing JNI transitions by up to 32x-64x compared to per-row `setInput`.
 * - Defaults to `compressionLevel = Deflater.BEST_SPEED` (`1`) for lossless intermediate files.
 */
class StreamingPngWriter(
    rawOut: OutputStream,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean = false,
    val compressionLevel: Int = Deflater.BEST_SPEED
) : AutoCloseable {
    init {
        require(width > 0 && height > 0) { "PNG dimensions must be positive (${width}x$height)." }
        Math.multiplyExact(width.toLong(), height.toLong())
        Math.multiplyExact(width, if (hasAlpha) 4 else 3)
    }

    private val out = if (rawOut is BufferedOutputStream) rawOut else BufferedOutputStream(rawOut, 65_536)
    private val bytesPerPixel = if (hasAlpha) 4 else 3
    private val rowStrideBytes = 1 + width * bytesPerPixel
    private val batchRowCapacity = max(1, 131_072 / rowStrideBytes)
    private val batchBuf = ByteArray(rowStrideBytes * batchRowCapacity)
    private var bufferedRows = 0
    private val deflater = Deflater(compressionLevel)
    private val deflateBuf = ByteArray(65_536)
    private val crc = CRC32()
    var rowsWritten: Int = 0
        private set
    private var finished = false
    private var closed = false

    init {
        // 1. 8-byte PNG signature
        out.write(PNG_SIGNATURE)
        // 2. 13-byte IHDR chunk: width(4), height(4), bitDepth=8(1), colorType=2(RGB)/6(RGBA)(1), comp=0(1), filter=0(1), interlace=0(1)
        val ihdr = ByteBuffer.allocate(13)
        ihdr.putInt(width)
        ihdr.putInt(height)
        ihdr.put(8.toByte())
        ihdr.put((if (hasAlpha) 6 else 2).toByte())
        ihdr.put(0.toByte())
        ihdr.put(0.toByte())
        ihdr.put(0.toByte())
        writeChunk(CHUNK_IHDR, ihdr.array(), 0, 13)
    }

    /**
     * Encodes `rows` scanlines from `stripArgb` (starting at `stripOffset` with row stride `stripStride`)
     * into batched `IDAT` chunks.
     */
    fun writeStrip(stripArgb: IntArray, rows: Int, stripOffset: Int = 0, stripStride: Int = width) {
        check(!finished && !closed) { "StreamingPngWriter is already finished or closed." }
        require(rows >= 0 && rowsWritten + rows <= height) {
            "Strip rows ($rows) exceed remaining PNG height (${height - rowsWritten})."
        }
        for (r in 0 until rows) {
            val rowBase = stripOffset + r * stripStride
            var bi = bufferedRows * rowStrideBytes
            batchBuf[bi++] = 0 // Filter type 0 (None)
            if (hasAlpha) {
                for (x in 0 until width) {
                    val c = stripArgb[rowBase + x]
                    batchBuf[bi++] = ((c shr 16) and 0xFF).toByte()
                    batchBuf[bi++] = ((c shr 8) and 0xFF).toByte()
                    batchBuf[bi++] = (c and 0xFF).toByte()
                    batchBuf[bi++] = ((c ushr 24) and 0xFF).toByte()
                }
            } else {
                for (x in 0 until width) {
                    val c = stripArgb[rowBase + x]
                    batchBuf[bi++] = ((c shr 16) and 0xFF).toByte()
                    batchBuf[bi++] = ((c shr 8) and 0xFF).toByte()
                    batchBuf[bi++] = (c and 0xFF).toByte()
                }
            }
            bufferedRows++
            rowsWritten++
            if (bufferedRows == batchRowCapacity) {
                flushBatchBuffer()
            }
        }
    }

    private fun flushBatchBuffer() {
        if (bufferedRows <= 0) return
        deflater.setInput(batchBuf, 0, bufferedRows * rowStrideBytes)
        drainDeflater(finish = false)
        bufferedRows = 0
    }

    /** Completes the zlib stream and writes the trailing `IDAT` and `IEND` chunks. */
    fun finish() {
        if (finished) return
        check(!closed) { "StreamingPngWriter is closed." }
        check(rowsWritten == height) { "Incomplete PNG: wrote $rowsWritten of $height rows." }
        flushBatchBuffer()
        deflater.finish()
        drainDeflater(finish = true)
        writeChunk(CHUNK_IEND, ByteArray(0), 0, 0)
        out.flush()
        finished = true
    }

    private fun drainDeflater(finish: Boolean) {
        while (if (finish) !deflater.finished() else !deflater.needsInput()) {
            val n = deflater.deflate(deflateBuf, 0, deflateBuf.size)
            if (n > 0) {
                writeChunk(CHUNK_IDAT, deflateBuf, 0, n)
            } else if (!finish) {
                break
            }
        }
    }

    private fun writeChunk(type: ByteArray, data: ByteArray, offset: Int, length: Int) {
        writeIntBigEndian(length)
        out.write(type, 0, 4)
        if (length > 0) {
            out.write(data, offset, length)
        }
        crc.reset()
        crc.update(type, 0, 4)
        if (length > 0) {
            crc.update(data, offset, length)
        }
        writeIntBigEndian(crc.value.toInt())
    }

    private fun writeIntBigEndian(v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            deflater.end()
        } catch (_: Exception) {}
        try {
            out.close()
        } catch (_: Exception) {}
    }

    companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val CHUNK_IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
        private val CHUNK_IDAT = "IDAT".toByteArray(Charsets.US_ASCII)
        private val CHUNK_IEND = "IEND".toByteArray(Charsets.US_ASCII)

        /** Helper to write a full file via a strip producer callback, deleting partial output on any failure. */
        fun writeFile(
            targetFile: File,
            width: Int,
            height: Int,
            hasAlpha: Boolean = false,
            compressionLevel: Int = Deflater.BEST_SPEED,
            producer: (StreamingPngWriter) -> Unit
        ) {
            targetFile.parentFile?.mkdirs()
            var ok = false
            try {
                FileOutputStream(targetFile).use { fos ->
                    StreamingPngWriter(fos, width, height, hasAlpha, compressionLevel).use { writer ->
                        producer(writer)
                        writer.finish() // flushes the buffered stream
                        fos.fd.sync()   // must run BEFORE close(): closing the writer closes `fos`, after which sync() throws
                    }
                }
                ok = true
            } finally {
                if (!ok && targetFile.exists()) {
                    targetFile.delete()
                }
            }
        }
    }
}

/**
 * Pure JVM verifier for PNG and JPEG intermediate files (`StreamingPngWriter` & `UpscaleCache`).
 * Validates PNG signature, `IHDR` dimensions/colorType, `IDAT` presence, `IEND` trailer, and CRC32 integrity of every chunk,
 * or fast header/trailer integrity before reusing cached files.
 */
object PngFormatVerifier {
    data class PngInfo(
        val valid: Boolean,
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val colorType: Int,
        val idatChunks: Int,
        val idatBytes: Long,
        val hasIend: Boolean,
        val error: String = ""
    )

    /**
     * Fast structural integrity check used by `UpscaleCache.get(...)` before reusing any cached intermediate file:
     * - For PNG: checks 8-byte PNG signature, valid positive `IHDR` width/height, and trailing 12-byte `IEND` chunk (`00 00 00 00 49 45 4E 44 AE 42 60 82`).
     * - For JPEG: checks SOI (`FF D8`) and trailing EOI (`FF D9`) markers and minimum file length.
     */
    fun isValidCachedImageFile(file: File, format: IntermediateFormat): Boolean {
        if (!file.isFile || file.length() < 45L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (format.png) {
                    val head = ByteArray(24)
                    raf.readFully(head)
                    for (i in 0 until 8) {
                        if (head[i] != StreamingPngWriter.PNG_SIGNATURE[i]) return false
                    }
                    val bb = ByteBuffer.wrap(head, 8, 16)
                    val ihdrLen = bb.int
                    val ihdrTag = IntArray(4) { bb.get().toInt() and 0xFF }
                    val w = bb.int
                    val h = bb.int
                    if (ihdrLen != 13 || ihdrTag[0] != 'I'.code || ihdrTag[1] != 'E'.code - 4 || w <= 0 || h <= 0) {
                        // Fallback to exact ASCII check for "IHDR"
                        val tagStr = String(head, 12, 4, Charsets.US_ASCII)
                        if (ihdrLen != 13 || tagStr != "IHDR" || w <= 0 || h <= 0) return false
                    }
                    raf.seek(file.length() - 12L)
                    val tail = ByteArray(12)
                    raf.readFully(tail)
                    val iendTag = String(tail, 4, 4, Charsets.US_ASCII)
                    iendTag == "IEND"
                } else {
                    val b0 = raf.read()
                    val b1 = raf.read()
                    if (b0 != 0xFF || b1 != 0xD8) return false
                    raf.seek(file.length() - 2L)
                    val e0 = raf.read()
                    val e1 = raf.read()
                    e0 == 0xFF && e1 == 0xD9
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun inspect(file: File): PngInfo {
        if (!file.isFile || file.length() < 33L) {
            return PngInfo(false, 0, 0, 0, 0, 0, 0L, false, "File missing or too short (< 33 bytes).")
        }
        FileInputStream(file).use { fis ->
            val sig = ByteArray(8)
            if (fis.read(sig) != 8 || !sig.contentEquals(StreamingPngWriter.PNG_SIGNATURE)) {
                return PngInfo(false, 0, 0, 0, 0, 0, 0L, false, "Invalid PNG signature.")
            }
            var width = 0
            var height = 0
            var bitDepth = 0
            var colorType = 0
            var idatCount = 0
            var idatBytes = 0L
            var hasIend = false
            val header = ByteArray(8)
            val crc = CRC32()

            while (true) {
                val r = fis.read(header)
                if (r < 0) break
                if (r != 8) return PngInfo(false, width, height, bitDepth, colorType, idatCount, idatBytes, false, "Truncated chunk header.")
                val len = ByteBuffer.wrap(header, 0, 4).int
                if (len < 0 || len > 100_000_000) {
                    return PngInfo(false, width, height, bitDepth, colorType, idatCount, idatBytes, false, "Invalid chunk length $len.")
                }
                val typeStr = String(header, 4, 4, Charsets.US_ASCII)
                val data = ByteArray(len)
                var read = 0
                while (read < len) {
                    val n = fis.read(data, read, len - read)
                    if (n < 0) return PngInfo(false, width, height, bitDepth, colorType, idatCount, idatBytes, false, "Truncated chunk data in $typeStr.")
                    read += n
                }
                val crcBytes = ByteArray(4)
                if (fis.read(crcBytes) != 4) {
                    return PngInfo(false, width, height, bitDepth, colorType, idatCount, idatBytes, false, "Missing CRC in $typeStr.")
                }
                val expectedCrc = ByteBuffer.wrap(crcBytes).int
                crc.reset()
                crc.update(header, 4, 4)
                crc.update(data, 0, len)
                if (crc.value.toInt() != expectedCrc) {
                    return PngInfo(false, width, height, bitDepth, colorType, idatCount, idatBytes, false, "CRC mismatch in chunk $typeStr.")
                }
                when (typeStr) {
                    "IHDR" -> {
                        if (len != 13) return PngInfo(false, 0, 0, 0, 0, 0, 0L, false, "IHDR length != 13.")
                        val bb = ByteBuffer.wrap(data)
                        width = bb.int
                        height = bb.int
                        bitDepth = bb.get().toInt() and 0xFF
                        colorType = bb.get().toInt() and 0xFF
                    }
                    "IDAT" -> {
                        idatCount++
                        idatBytes += len
                    }
                    "IEND" -> {
                        hasIend = true
                        break
                    }
                }
            }
            val ok = width > 0 && height > 0 && idatCount > 0 && idatBytes > 0L && hasIend
            return PngInfo(ok, width, height, bitDepth, colorType, idatCount, idatBytes, hasIend, if (ok) "" else "Incomplete PNG structure.")
        }
    }
}
