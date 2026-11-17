package ils.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Every tunable value of the element stage (detection, timing, reveal) lives here. */
data class ElementSettings(
    // detection
    /** A pixel is ink when one colour channel differs from the background by more than this (0..255). */
    val bgThreshold: Int = 40,
    /** Ink closer together than twice this (share of the image's shorter side) sideways is one element, so the words of a label stay together. */
    val mergeRadiusFrac: Double = 0.02,
    /** The same, up and down: larger, so an icon and the label under it stay together. */
    val mergeRadiusVFrac: Double = 0.035,
    /** Ink groups with less ink than this (share of the image area) are noise and stay visible all the time. */
    val minAreaFrac: Double = 0.0003,
    /** Pixels around the ink that belong to it (anti-aliasing). */
    val edgePad: Int = 2,
    /** Inside an icon element, a piece of ink outside the main picture and not taller than this share of it is its label. */
    val labelMaxHeightShare: Double = 0.6,
    // timing
    val iconDrawSec: Double = 0.9,
    val textBaseSec: Double = 0.5,
    val textSecPerLetter: Double = 0.09,
    val textMaxSec: Double = 3.0,
    val minDrawSec: Double = 0.3,
    /** Two elements never start closer together than this. */
    val minStartGap: Double = 0.12,
    /** An element is finished this long before the next one starts. */
    val gapBeforeNext: Double = 0.05,
    /** The last element is finished this long before the image ends. */
    val endMargin: Double = 0.15,
    /** For an icon with a label: share of the draw time used for the picture; the label is written in the rest. */
    val iconBodyShare: Double = 0.4,
    /** An icon grows from this scale to 1 while it fades in. */
    val popScaleFrom: Double = 0.9,
    // heading images without an element list: all ink is written as handwriting
    val headingDelaySec: Double = 0.15,
    val headingMaxWriteSec: Double = 2.5,
    val headingWriteShare: Double = 0.7,
)

/** A connected piece of ink (global image coordinates). */
class Fine(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val pixels: Int) {
    val w get() = x1 - x0 + 1
    val h get() = y1 - y0 + 1
    val cx get() = (x0 + x1) / 2.0
    val cy get() = (y0 + y1) / 2.0
}

/**
 * One element found in an image: a group of ink pieces that lie close together.
 * Masks cover the element's box (w x h), 1 = belongs to the element.
 * sprite = ink plus its edge pixels (what is drawn and what is erased), ink = ink only (what the pen follows).
 */
class Detected(
    val x0: Int, val y0: Int, val w: Int, val h: Int,
    val sprite: ByteArray, val ink: ByteArray,
    val fine: List<Fine>, val fineLabel: IntArray,
) {
    var row = 0
    var col = 0
    val cx get() = x0 + w / 2.0
    val cy get() = y0 + h / 2.0
}

class Detection(val w: Int, val h: Int, val bg: Int, val elements: List<Detected>)

/** An element split into the part that pops in (body) and the part written by the pen (label). Masks cover the element's box. */
class Split(val body: ByteArray?, val label: ByteArray?, val labelInk: ByteArray?, val letters: Int)

object Elements {
    class Labels(val lab: IntArray, val n: Int, val x0: IntArray, val y0: IntArray, val x1: IntArray, val y1: IntArray, val count: IntArray)

    /** The most common colour of the image (5 bits per channel buckets, then the average inside the winning bucket). */
    fun estimateBackground(px: IntArray): Int {
        val cnt = IntArray(32768)
        for (c in px) cnt[((c shr 19) and 31 shl 10) or ((c shr 11) and 31 shl 5) or ((c shr 3) and 31)]++
        var best = 0
        for (i in cnt.indices) if (cnt[i] > cnt[best]) best = i
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (c in px) {
            if ((((c shr 19) and 31 shl 10) or ((c shr 11) and 31 shl 5) or ((c shr 3) and 31)) != best) continue
            r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
        }
        if (n == 0L) return 0xFFFFFFFF.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    fun foreground(px: IntArray, bg: Int, thr: Int): ByteArray {
        val br = (bg shr 16) and 255; val bgG = (bg shr 8) and 255; val bb = bg and 255
        val out = ByteArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val d = max(max(kotlin.math.abs(((c shr 16) and 255) - br), kotlin.math.abs(((c shr 8) and 255) - bgG)), kotlin.math.abs((c and 255) - bb))
            if (d > thr) out[i] = 1
        }
        return out
    }

    /** Rectangular dilation by rx pixels sideways and ry up and down (two passes with running counts). */
    fun dilate(bits: ByteArray, w: Int, h: Int, rx: Int, ry: Int = rx): ByteArray {
        if (rx <= 0 && ry <= 0) return bits.copyOf()
        val tmp = ByteArray(w * h)
        val pre = IntArray(max(w, h) + 1)
        for (y in 0 until h) {
            for (x in 0 until w) pre[x + 1] = pre[x] + (if (bits[y * w + x].toInt() != 0) 1 else 0)
            for (x in 0 until w) if (pre[min(w, x + rx + 1)] - pre[max(0, x - rx)] > 0) tmp[y * w + x] = 1
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) pre[y + 1] = pre[y] + tmp[y * w + x]
            for (y in 0 until h) if (pre[min(h, y + ry + 1)] - pre[max(0, y - ry)] > 0) out[y * w + x] = 1
        }
        return out
    }

    /** 8-connected labelling. Labels are 1..n, 0 = background. */
    fun label(bits: ByteArray, w: Int, h: Int): Labels {
        val lab = IntArray(w * h)
        val stack = IntArray(w * h)
        val x0 = ArrayList<Int>().apply { add(0) }; val y0 = ArrayList<Int>().apply { add(0) }
        val x1 = ArrayList<Int>().apply { add(0) }; val y1 = ArrayList<Int>().apply { add(0) }
        val cnt = ArrayList<Int>().apply { add(0) }
        var n = 0
        for (s in 0 until w * h) {
            if (bits[s].toInt() == 0 || lab[s] != 0) continue
            n++
            var sp = 0
            stack[sp++] = s; lab[s] = n
            var ax = w; var ay = h; var bx = 0; var by = 0; var c = 0
            while (sp > 0) {
                val i = stack[--sp]; c++
                val x = i % w; val y = i / w
                if (x < ax) ax = x; if (x > bx) bx = x; if (y < ay) ay = y; if (y > by) by = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (bits[j].toInt() != 0 && lab[j] == 0) { lab[j] = n; stack[sp++] = j }
                }
            }
            x0.add(ax); y0.add(ay); x1.add(bx); y1.add(by); cnt.add(c)
        }
        return Labels(lab, n, x0.toIntArray(), y0.toIntArray(), x1.toIntArray(), y1.toIntArray(), cnt.toIntArray())
    }

    private fun make(w: Int, x0: Int, y0: Int, x1: Int, y1: Int, member: (Int) -> Boolean, fg: ByteArray, spriteAll: ByteArray, fine: Labels): Detected {
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val sprite = ByteArray(bw * bh); val ink = ByteArray(bw * bh); val fl = IntArray(bw * bh)
        val idx = HashMap<Int, Int>(); val boxes = ArrayList<Fine>()
        for (y in y0..y1) for (x in x0..x1) {
            val p = y * w + x
            if (!member(p)) continue
            val q = (y - y0) * bw + (x - x0)
            if (spriteAll[p].toInt() != 0) sprite[q] = 1
            if (fg[p].toInt() != 0) {
                ink[q] = 1
                val f = fine.lab[p]
                fl[q] = idx.getOrPut(f) { boxes.add(Fine(fine.x0[f], fine.y0[f], fine.x1[f], fine.y1[f], fine.count[f])); boxes.size }
            }
        }
        return Detected(x0, y0, bw, bh, sprite, ink, boxes, fl)
    }

    /** Finds the elements of an image (pixels = ARGB, w x h). Elements get their slot (row, col) counted from 1. */
    fun detect(px: IntArray, w: Int, h: Int, st: ElementSettings = ElementSettings()): Detection {
        val bg = estimateBackground(px)
        val fg = foreground(px, bg, st.bgThreshold)
        val rx = max((min(w, h) * st.mergeRadiusFrac).roundToInt(), st.edgePad + 1)
        val ry = max((min(w, h) * st.mergeRadiusVFrac).roundToInt(), st.edgePad + 1)
        val coarse = label(dilate(fg, w, h, rx, ry), w, h)
        val fine = label(fg, w, h)
        val spriteAll = dilate(fg, w, h, st.edgePad)
        val ink = IntArray(coarse.n + 1)
        for (p in 0 until w * h) if (fg[p].toInt() != 0) ink[coarse.lab[p]]++
        val minInk = (w.toLong() * h * st.minAreaFrac).toInt()
        val out = ArrayList<Detected>()
        for (k in 1..coarse.n) {
            if (ink[k] < max(minInk, 1)) continue
            out.add(make(w, coarse.x0[k], coarse.y0[k], coarse.x1[k], coarse.y1[k], { p -> coarse.lab[p] == k }, fg, spriteAll, fine))
        }
        assignSlots(out)
        return Detection(w, h, bg, out)
    }

    /** All ink of the image as one element (used to write a heading image that has no element list). Null when the image has no ink. */
    fun wholeInk(px: IntArray, w: Int, h: Int, st: ElementSettings = ElementSettings()): Detected? {
        val bg = estimateBackground(px)
        val fg = foreground(px, bg, st.bgThreshold)
        var ax = w; var ay = h; var bx = -1; var by = -1
        for (p in fg.indices) if (fg[p].toInt() != 0) {
            val x = p % w; val y = p / w
            if (x < ax) ax = x; if (x > bx) bx = x; if (y < ay) ay = y; if (y > by) by = y
        }
        if (bx < 0) return null
        val pad = st.edgePad
        ax = max(0, ax - pad); ay = max(0, ay - pad); bx = min(w - 1, bx + pad); by = min(h - 1, by + pad)
        val d = make(w, ax, ay, bx, by, { true }, fg, dilate(fg, w, h, pad), label(fg, w, h))
        d.row = 1; d.col = 1
        return d
    }

    /** Rows top to bottom (ink that overlaps vertically shares a row), columns left to right. */
    fun assignSlots(els: List<Detected>) {
        class Row(var y0: Int, var y1: Int, val items: ArrayList<Detected> = ArrayList())
        val rows = ArrayList<Row>()
        for (e in els.sortedBy { it.cy }) {
            val rw = rows.firstOrNull { rr ->
                val ov = min(rr.y1, e.y0 + e.h - 1) - max(rr.y0, e.y0) + 1
                ov >= 0.4 * min(rr.y1 - rr.y0 + 1, e.h)
            }
            if (rw == null) rows.add(Row(e.y0, e.y0 + e.h - 1, arrayListOf(e)))
            else { rw.items.add(e); rw.y0 = min(rw.y0, e.y0); rw.y1 = max(rw.y1, e.y0 + e.h - 1) }
        }
        rows.sortBy { it.y0 }
        for ((ri, rw) in rows.withIndex()) for ((ci, e) in rw.items.sortedBy { it.cx }.withIndex()) { e.row = ri + 1; e.col = ci + 1 }
    }

    /** Splits an element into picture and label. For a text element everything is label. */
    fun split(d: Detected, asText: Boolean, st: ElementSettings = ElementSettings()): Split {
        if (asText || d.fine.isEmpty()) return Split(null, d.sprite, d.ink, d.fine.size)
        val m = d.fine.maxByOrNull { it.w.toLong() * it.h }!!
        val isLabel = BooleanArray(d.fine.size + 1)
        var letters = 0
        for ((i, f) in d.fine.withIndex()) {
            if (f === m) continue
            val inside = f.cx >= m.x0 && f.cx <= m.x1 && f.cy >= m.y0 && f.cy <= m.y1
            if (!inside && f.h <= st.labelMaxHeightShare * m.h) { isLabel[i + 1] = true; letters++ }
        }
        if (letters == 0) return Split(d.sprite, null, null, 0)
        val labelInk = ByteArray(d.w * d.h)
        for (q in labelInk.indices) if (d.ink[q].toInt() != 0 && isLabel[d.fineLabel[q]]) labelInk[q] = 1
        val labelSprite = dilate(labelInk, d.w, d.h, st.edgePad)
        val body = ByteArray(d.w * d.h)
        for (q in body.indices) {
            if (d.sprite[q].toInt() == 0) labelSprite[q] = 0
            else if (labelSprite[q].toInt() == 0) body[q] = 1
        }
        return Split(body, labelSprite, labelInk, letters)
    }

    /** src is an image already scaled to fit; returns W x H with the image centred on the image's own background colour. */
    fun letterbox(src: IntArray, sw: Int, sh: Int, W: Int, H: Int): IntArray {
        val bg = estimateBackground(src)
        val out = IntArray(W * H) { bg }
        val ox = (W - sw) / 2; val oy = (H - sh) / 2
        for (y in 0 until sh) System.arraycopy(src, y * sw, out, (y + oy) * W + ox, sw)
        return out
    }
}
