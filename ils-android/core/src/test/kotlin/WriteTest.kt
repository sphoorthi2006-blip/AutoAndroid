package ils.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** JVM-only visual test of the handwriting reveal:  WriteTest <in.png> <out.png> [frames]  (dark text on a light background). */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val src = ImageIO.read(File(args[0]))
    val w = src.width; val h = src.height
    val bits = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val c = src.getRGB(x, y); val g = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
        if (g < 140) bits[y * w + x] = 1
    }
    val t0 = System.nanoTime()
    val plan = Handwriting.plan(bits, w, h)
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("${args[0]}: ${w}x$h, ${plan.strokes.size} strokes, planned in $ms ms")

    // order check: stroke starts should move left to right within a line
    val starts = plan.strokes.map { it.pts.first() }
    var backJumps = 0
    for (i in 1 until starts.size) if (starts[i].x < starts[i - 1].x - 0.6 * plan.strokes[i].pen && kotlin.math.abs(starts[i].y - starts[i - 1].y) < 40) backJumps++
    println("  right-to-left jumps inside one line: $backJumps")

    fun mask(frame: WriteFrame): BufferedImage {
        val m = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = m.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        for ((pts, pen) in frame.lines) {
            g.stroke = BasicStroke(pen.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            if (pts.size == 1) { g.fillOval((pts[0].x - pen / 2).toInt(), (pts[0].y - pen / 2).toInt(), pen.toInt(), pen.toInt()); continue }
            val p = Path2D.Double(); p.moveTo(pts[0].x, pts[0].y); for (q in pts.drop(1)) p.lineTo(q.x, q.y); g.draw(p)
        }
        g.dispose()
        return m
    }

    val inkTotal = bits.count { it.toInt() != 0 }
    val atEnd = mask(plan.frame(plan.settings.penShare))
    var covered = 0
    for (y in 0 until h) for (x in 0 until w) if (bits[y * w + x].toInt() != 0 && (atEnd.getRGB(x, y) and 255) > 128) covered++
    println("  ink reached by the pen: %.1f%% (the rest fades in during the last %.0f%%)".format(100.0 * covered / inkTotal, 100 * (1 - plan.settings.penShare)))

    val n = if (args.size > 2) args[2].toInt() else 6
    val us = (1..n).map { it.toDouble() / n }
    val sheet = BufferedImage(w, h * n, BufferedImage.TYPE_INT_RGB)
    val sg = sheet.createGraphics(); sg.color = Color.WHITE; sg.fillRect(0, 0, w, h * n)
    for ((k, u) in us.withIndex()) {
        val f = plan.frame(u); val m = mask(f)
        for (y in 0 until h) for (x in 0 until w) {
            val c0 = src.getRGB(x, y); val g0 = ((c0 shr 16 and 255) + (c0 shr 8 and 255) + (c0 and 255)) / 3
            if (g0 >= 250) { sheet.setRGB(x, k * h + y, src.getRGB(x, y)); continue }   // pure background; every other pixel, soft edges included, belongs to the text and is hidden until written
            val a = if ((m.getRGB(x, y) and 255) > 128) 1.0 else f.finish
            val c = src.getRGB(x, y)
            val r = (255 + ((c shr 16 and 255) - 255) * a).toInt(); val gg = (255 + ((c shr 8 and 255) - 255) * a).toInt(); val b = (255 + ((c and 255) - 255) * a).toInt()
            sheet.setRGB(x, k * h + y, (r shl 16) or (gg shl 8) or b)
        }
        sg.color = Color(200, 30, 30); sg.drawString("t = %.0f%%".format(u * 100), 4, k * h + 12)
        sg.color = Color(230, 230, 230); sg.drawLine(0, (k + 1) * h - 1, w, (k + 1) * h - 1)
    }
    sg.dispose()
    ImageIO.write(sheet, "png", File(args[1]))
}
