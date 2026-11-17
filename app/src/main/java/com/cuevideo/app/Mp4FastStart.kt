package com.cuevideo.app

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Android's MediaMuxer writes the "moov" box at the END of the file. Section 2 wants it at the start
 * (faststart). This copies the file and moves moov to just after ftyp, adding its size to every
 * chunk offset (stco / co64).
 */
object Mp4FastStart {
    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    private fun put32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte()
    }

    private fun put64(b: ByteArray, p: Int, v: Long) {
        put32(b, p, v ushr 32); put32(b, p + 4, v and 0xFFFFFFFFL)
    }

    private fun topBoxes(f: RandomAccessFile): List<Box>? {
        val out = ArrayList<Box>()
        val len = f.length()
        var pos = 0L
        val h = ByteArray(16)
        while (pos + 8 <= len) {
            f.seek(pos)
            f.readFully(h, 0, 8)
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) {
                if (pos + 16 > len) return null
                f.readFully(h, 8, 8)
                size = u64(h, 8)
                hdr = 16
            } else if (size == 0L) {
                size = len - pos
            }
            if (size < hdr || pos + size > len) return null
            out.add(Box(type, pos, size, hdr))
            pos += size
        }
        return out
    }

    /** Adds [shift] to all chunk offsets found in the moov box bytes [from, to). Returns false on overflow. */
    fun patch(b: ByteArray, from: Int, to: Int, shift: Long): Boolean {
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) { size = u64(b, p + 8); hdr = 16 } else if (size == 0L) size = (to - p).toLong()
            if (size < hdr || p + size > to) return true
            val end = (p + size).toInt()
            when (type) {
                "trak", "mdia", "minf", "stbl" -> if (!patch(b, p + hdr, end, shift)) return false
                "stco" -> {
                    val cnt = u32(b, p + hdr + 4).toInt()
                    var q = p + hdr + 8
                    repeat(cnt) {
                        val v = u32(b, q) + shift
                        if (v > 0xFFFFFFFFL) return false
                        put32(b, q, v)
                        q += 4
                    }
                }
                "co64" -> {
                    val cnt = u32(b, p + hdr + 4).toInt()
                    var q = p + hdr + 8
                    repeat(cnt) {
                        put64(b, q, u64(b, q) + shift)
                        q += 8
                    }
                }
            }
            p = end
        }
        return true
    }

    private fun copyRange(f: RandomAccessFile, from: Long, len: Long, out: OutputStream, buf: ByteArray) {
        f.seek(from)
        var left = len
        while (left > 0) {
            val k = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (k <= 0) throw java.io.IOException("unexpected end of the temporary video file")
            out.write(buf, 0, k)
            left -= k
        }
    }

    /** Writes [src] to [out] with moov first. If that is not possible the file is copied unchanged. */
    fun rewrite(src: File, out: OutputStream) {
        RandomAccessFile(src, "r").use { f ->
            val buf = ByteArray(1 shl 20)
            val boxes = topBoxes(f)
            val moov = boxes?.firstOrNull { it.type == "moov" }
            val mdat = boxes?.firstOrNull { it.type == "mdat" }
            if (boxes == null || moov == null || mdat == null || moov.start < mdat.start || moov.size > (64 shl 20)) {
                copyRange(f, 0, f.length(), out, buf)
                return
            }
            val mb = ByteArray(moov.size.toInt())
            f.seek(moov.start)
            f.readFully(mb)
            if (!patch(mb, moov.header, mb.size, moov.size)) {
                copyRange(f, 0, f.length(), out, buf)
                return
            }
            for (b in boxes) if (b.start < mdat.start) copyRange(f, b.start, b.size, out, buf)
            out.write(mb)
            for (b in boxes) if (b.start >= mdat.start && b !== moov) copyRange(f, b.start, b.size, out, buf)
        }
    }
}
