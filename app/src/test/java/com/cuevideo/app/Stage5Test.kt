package com.cuevideo.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Stage5Test {
    private fun cue(i: Int, text: String) = Cue(i * 2.0, i * 2.0 + 2, text)

    private val cues = listOf(
        cue(0, "Welcome to the show"),
        cue(1, "1. The first thing"),
        cue(2, "Some normal words"),
        cue(3, "More words here"),
        cue(4, "Please subscribe now"),
        cue(5, "The end"),
    )
    private val bf = intArrayOf(0, 60, 120, 180, 240, 300, 360)

    private fun imgs(vararg n: Int) = n.map { ImageEntry("$it.jpg", "file:///$it.jpg") }

    @Test fun planKindsAndFrames() {
        val chk = Checker.check(cues, imgs(1, 2, 3, 4, 5, 6), emptyList())
        assertTrue(chk.canExport)
        val plan = ExportPlanner.build(cues, bf, chk)
        assertEquals(360, plan.totalFrames)
        assertEquals(
            listOf(SegKind.STILL, SegKind.HEADING, SegKind.STILL, SegKind.STILL, SegKind.BLACK, SegKind.STILL),
            plan.segments.map { it.kind },
        )
        assertEquals(60, plan.segments[1].startFrame)
        assertEquals(120, plan.segments[1].endFrame)
        assertEquals("1. The first thing", plan.segments[1].text)
        // frames are continuous, no gaps
        for (k in 1 until plan.segments.size) assertEquals(plan.segments[k - 1].endFrame, plan.segments[k].startFrame)
    }

    @Test fun sceneClipReplacesCues() {
        val clips = listOf(ClipEntry("3-4.mp4", "file:///3-4.mp4"))
        val chk = Checker.check(cues, imgs(1, 2, 3, 6), clips)   // images 4, 5 not needed: 3-4 covered, 5 is subscribe but still needs one
        assertEquals(listOf(5), chk.missing)
        val chk2 = Checker.check(cues, imgs(1, 2, 5, 6), clips)
        assertTrue(chk2.canExport)
        val plan = ExportPlanner.build(cues, bf, chk2)
        val sc = plan.segments.first { it.kind == SegKind.SCENE }
        assertEquals(3, sc.firstCue); assertEquals(4, sc.lastCue)
        assertEquals(120, sc.startFrame); assertEquals(240, sc.endFrame)
        assertEquals(5, plan.segments.size)                     // still, heading, scene, black, still
    }

    @Test fun reportCsv() {
        val chk = Checker.check(cues, imgs(1, 2, 3, 4, 5, 6), emptyList())
        val plan = ExportPlanner.build(cues, bf, chk)
        val csv = ReportCsv.build(cues, bf, plan, chk, 30)
        val lines = csv.trim().split("\r\n")
        assertEquals("cue,start,end,source,replaced_image,text", lines[0])
        assertEquals("1,0.00,2.00,1.jpg,,Welcome to the show", lines[1])
        assertEquals("2,2.00,4.00,\"TEXT SLIDE (heading, fade-in + zoom)\",2.jpg,1. The first thing", lines[2])
        assertEquals("5,8.00,10.00,TEXT SLIDE (subscribe),5.jpg,Please subscribe now", lines[5])
        val (used, unused) = ReportCsv.usage(plan, imgs(1, 2, 3, 4, 5, 6, 7))
        assertEquals(4, used)
        assertEquals(listOf("2.jpg", "5.jpg", "7.jpg"), unused)
    }

    @Test fun csvQuoting() {
        val c = listOf(Cue(0.0, 1.0, "Hello, \"world\""))
        val chk = Checker.check(c, imgs(1), emptyList())
        val plan = ExportPlanner.build(c, intArrayOf(0, 30), chk)
        val line = ReportCsv.build(c, intArrayOf(0, 30), plan, chk, 30).trim().split("\r\n")[1]
        assertEquals("1,0.00,1.00,1.jpg,,\"Hello, \"\"world\"\"\"", line)
    }

    @Test fun textureMatrices() {
        // full picture: v' = 1 - v, u' = u
        val m = GlMath.FULL
        assertEquals(1f, m[0], 1e-6f); assertEquals(-1f, m[5], 1e-6f); assertEquals(1f, m[13], 1e-6f); assertEquals(0f, m[12], 1e-6f)
        // zoom 1.25: crop box is the middle 80 %
        val c = GlMath.crop(0.1, 0.1, 0.9, 0.9)
        assertEquals(0.8f, c[0], 1e-6f); assertEquals(-0.8f, c[5], 1e-6f)
        assertEquals(0.1f, c[12], 1e-6f); assertEquals(0.9f, c[13], 1e-6f)
        // bottom-left of the screen (0,0) -> bottom-left of the crop (0.1, 0.9); top-right (1,1) -> (0.9, 0.1)
        assertEquals(0.1f, c[12], 1e-6f)
        assertEquals(0.9f, c[0] + c[12], 1e-6f)
        assertEquals(0.1f, c[13] + c[5], 1e-6f)
    }

    @Test fun letterbox() {
        val a = GlMath.letterbox(1920, 1080, 1920, 1080)
        assertEquals(1f, a.first, 1e-6f); assertEquals(1f, a.second, 1e-6f)
        val b = GlMath.letterbox(1080, 1920, 1920, 1080)        // portrait: bars left and right
        assertEquals(0.31640625f, b.first, 1e-4f); assertEquals(1f, b.second, 1e-6f)
        val c = GlMath.letterbox(1280, 960, 1920, 1080)         // 4:3: bars left and right
        assertEquals(0.75f, c.first, 1e-4f); assertEquals(1f, c.second, 1e-6f)
        val d = GlMath.letterbox(2000, 500, 1920, 1080)         // very wide: bars top and bottom
        assertEquals(1f, d.first, 1e-6f); assertEquals(0.4444f, d.second, 1e-3f)
    }

    // ---- faststart ----

    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray): ByteArray = be32(8L + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private fun makeMp4(offsetsIn: List<Long>): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() + be32(0) + "isom".toByteArray() + "mp41".toByteArray())      // 24 bytes
        val mdatPayload = ByteArray(40) { it.toByte() }
        val mdat = box("mdat", mdatPayload)
        val stco = box("stco", be32(0) + be32(offsetsIn.size.toLong()) + offsetsIn.fold(ByteArray(0)) { a, o -> a + be32(o) })
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stco)))))
        return ftyp + mdat + moov
    }

    private fun readStco(file: ByteArray): List<Long> {
        val i = String(file, Charsets.ISO_8859_1).indexOf("stco")
        val cnt = ((file[i + 8].toInt() and 0xFF) shl 24) or ((file[i + 9].toInt() and 0xFF) shl 16) or
            ((file[i + 10].toInt() and 0xFF) shl 8) or (file[i + 11].toInt() and 0xFF)
        return (0 until cnt).map { k ->
            val p = i + 12 + 4 * k
            ((file[p].toLong() and 0xFF) shl 24) or ((file[p + 1].toLong() and 0xFF) shl 16) or
                ((file[p + 2].toLong() and 0xFF) shl 8) or (file[p + 3].toLong() and 0xFF)
        }
    }

    @Test fun faststartMovesMoovAndFixesOffsets() {
        val src = makeMp4(listOf(32L, 48L))                        // ftyp 24 + mdat header 8 -> payload at 32
        val f = File.createTempFile("faststart1", ".mp4")
        try {
            f.writeBytes(src)
            val out = ByteArrayOutputStream()
            Mp4FastStart.rewrite(f, out)
            val res = out.toByteArray()
            assertEquals(src.size, res.size)
            val s = String(res, Charsets.ISO_8859_1)
            assertTrue(s.indexOf("moov") < s.indexOf("mdat"))
            val moovSize = src.size - 24 - 48                      // moov is the last box
            val offs = readStco(res)
            assertEquals(listOf(32L + moovSize, 48L + moovSize), offs)
            // the media bytes are untouched and still where the new offsets point
            val mdatAt = s.indexOf("mdat") - 4
            assertEquals(mdatAt + 8, offs[0].toInt() - 0)
            assertEquals(0, res[offs[0].toInt()].toInt())
            assertEquals(16, res[offs[1].toInt()].toInt())
        } finally { f.delete() }
    }

    @Test fun faststartLeavesReadyFilesAlone() {
        val ftyp = box("ftyp", "isom".toByteArray() + be32(0) + "isom".toByteArray())
        val moov = box("moov", box("trak", ByteArray(4)))
        val mdat = box("mdat", ByteArray(10))
        val src = ftyp + moov + mdat
        val f = File.createTempFile("faststart2", ".mp4")
        try {
            f.writeBytes(src)
            val out = ByteArrayOutputStream()
            Mp4FastStart.rewrite(f, out)
            assertArrayEquals(src, out.toByteArray())
        } finally { f.delete() }
    }
}
