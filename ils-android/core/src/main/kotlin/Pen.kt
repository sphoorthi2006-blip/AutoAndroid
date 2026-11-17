package ils.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Pt(val x: Double, val y: Double)

/** Pen strokes of one shape, and the pen width in pixels that covers its thickest part. */
class Skeleton(val strokes: List<List<Pt>>, val pen: Double)

/**
 * Pen paths for handwriting (port of the desktop app's skeleton.ts): finds the centre line of a bitmap and turns it into a few long strokes.
 * Works on a 0/1 bitmap with a border of empty pixels. Pure Kotlin, no Android or AWT.
 */
object Pen {
    // N NE E SE S SW W NW
    private val RX = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val RY = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    /** Zhang-Suen thinning: shrinks every shape to a line one pixel wide, in the middle of the shape. */
    fun thin(bits: ByteArray, w: Int, h: Int): ByteArray {
        val s = bits.copyOf()
        val off = IntArray(8) { RY[it] * w + RX[it] }
        val kill = ArrayList<Int>()
        val p = IntArray(8)
        var changed = true
        while (changed) {
            changed = false
            for (step in 0..1) {
                kill.clear()
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    if (s[i].toInt() == 0) continue
                    for (k in 0..7) p[k] = s[i + off[k]].toInt()
                    val b = p.sum()
                    if (b < 2 || b > 6) continue
                    var a = 0
                    for (k in 0..7) if (p[k] == 0 && p[(k + 1) % 8] != 0) a++
                    if (a != 1) continue
                    val blocked = if (step == 0) (p[0] * p[2] * p[4] != 0 || p[2] * p[4] * p[6] != 0) else (p[0] * p[2] * p[6] != 0 || p[0] * p[4] * p[6] != 0)
                    if (blocked) continue
                    kill.add(i)
                }
                if (kill.isNotEmpty()) { changed = true; for (i in kill) s[i] = 0 }
            }
        }
        return s
    }

    /** Chamfer distance to the nearest empty pixel (about 1 per pixel). */
    private fun distance(bits: ByteArray, w: Int, h: Int): FloatArray {
        val d = FloatArray(w * h)
        val inf = 1e9f; val a = 1f; val b = sqrt(2f)
        for (i in d.indices) d[i] = if (bits[i].toInt() != 0) inf else 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (d[i] != 0f) d[i] = minOf(d[i], d[i - 1] + a, d[i - w] + a, d[i - w - 1] + b, d[i - w + 1] + b)
        }
        for (y in h - 2 downTo 1) for (x in w - 2 downTo 1) {
            val i = y * w + x
            if (d[i] != 0f) d[i] = minOf(d[i], d[i + 1] + a, d[i + w] + a, d[i + w + 1] + b, d[i + w - 1] + b)
        }
        return d
    }

    /** Douglas-Peucker line simplification, with a safe split for closed loops. */
    fun simplify(pts: List<Pt>, eps: Double): List<Pt> {
        if (pts.size < 3) return pts
        var idx = 0; var dmax = 0.0
        val a = pts.first(); val b = pts.last()
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = hypot(dx, dy).let { if (it == 0.0) 1e-9 else it }
        for (i in 1 until pts.size - 1) {
            val d = abs(dy * pts[i].x - dx * pts[i].y + b.x * a.y - b.y * a.x) / len
            if (d > dmax) { dmax = d; idx = i }
        }
        if (len < 1e-6) {
            var k = 1; var far = 0.0
            for (i in 1 until pts.size - 1) { val d = hypot(pts[i].x - a.x, pts[i].y - a.y); if (d > far) { far = d; k = i } }
            if (far < eps) return listOf(a, b)
            return simplify(pts.subList(0, k + 1), eps).dropLast(1) + simplify(pts.subList(k, pts.size), eps)
        }
        if (dmax <= eps) return listOf(a, b)
        return simplify(pts.subList(0, idx + 1), eps).dropLast(1) + simplify(pts.subList(idx, pts.size), eps)
    }

    /** One round of corner cutting: keeps the ends, rounds the bends. */
    fun smooth(p: List<Pt>): List<Pt> {
        if (p.size < 3) return p
        val o = ArrayList<Pt>()
        o.add(p[0])
        for (i in 0 until p.size - 1) {
            val a = p[i]; val b = p[i + 1]
            o.add(Pt(a.x * 0.75 + b.x * 0.25, a.y * 0.75 + b.y * 0.25))
            o.add(Pt(a.x * 0.25 + b.x * 0.75, a.y * 0.25 + b.y * 0.75))
        }
        o.add(p.last())
        return o
    }

    fun polyLength(p: List<Pt>): Double {
        var l = 0.0
        for (i in 1 until p.size) l += hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y)
        return l
    }

    /**
     * Centre line of the shape as pen strokes, in the order a person would write them: largest piece first, starting at the top-left end,
     * carrying straight on at each crossing; side branches follow as their own strokes.
     */
    fun skeletonStrokes(bits: ByteArray, w: Int, h: Int): Skeleton {
        val sk = thin(bits, w, h)
        val dt = distance(bits, w, h)
        val off = IntArray(8) { RY[it] * w + RX[it] }
        val on = ArrayList<Int>()
        for (i in sk.indices) if (sk[i].toInt() != 0) on.add(i)
        if (on.isEmpty()) return Skeleton(emptyList(), 4.0)

        val th = on.map { dt[it].toDouble() }.sorted()
        val pen = max(2.0, 2 * th[floor(th.size * 0.9).toInt()] * 1.12 + 1.5)
        val spur = max(3.0, pen * 0.45)

        fun nb(i: Int): List<Int> {
            val r = ArrayList<Int>(8)
            for (k in 0..7) { val j = i + off[k]; if (j in sk.indices && sk[j].toInt() != 0) r.add(j) }
            return r
        }
        fun X(i: Int) = i % w
        fun Y(i: Int) = i / w
        fun adj8(i: Int, j: Int) = abs(X(i) - X(j)) <= 1 && abs(Y(i) - Y(j)) <= 1
        fun pt(i: Int) = Pt(X(i) + 0.5, Y(i) + 0.5)
        fun score(i: Int) = X(i) + 0.6 * Y(i)

        // pieces of the shape (an "i" has two: stem and dot), biggest first
        val comp = HashMap<Int, Int>()
        val comps = ArrayList<List<Int>>()
        for (i in on) {
            if (comp.containsKey(i)) continue
            val c = ArrayList<Int>(); val st = ArrayDeque<Int>(); st.add(i); comp[i] = comps.size
            while (st.isNotEmpty()) {
                val a = st.removeLast(); c.add(a)
                for (b in nb(a)) if (!comp.containsKey(b)) { comp[b] = comps.size; st.add(b) }
            }
            comps.add(c)
        }
        comps.sortByDescending { it.size }

        val seen = BooleanArray(sk.size)
        val out = ArrayList<List<Pt>>()

        for (c in comps) {
            if (c.size < 4) {   // a dot
                var mx = 0.0; var my = 0.0
                for (i in c) { mx += pt(i).x / c.size; my += pt(i).y / c.size }
                out.add(listOf(Pt(mx, my), Pt(mx, my + 1)))
                for (i in c) seen[i] = true
                continue
            }
            var first = true
            while (true) {
                var start = -1; var from = -1
                if (first) {
                    val ends = c.filter { !seen[it] && nb(it).size == 1 }
                    val pool = if (ends.isNotEmpty()) ends else c
                    for (i in pool) {
                        val better = if (start < 0) true
                        else if (ends.isNotEmpty()) score(i) < score(start)
                        else (Y(i) < Y(start) || (Y(i) == Y(start) && X(i) < X(start)))
                        if (better) start = i
                    }
                } else {
                    var best = Double.MAX_VALUE
                    for (i in c) if (!seen[i]) {
                        val touch = nb(i).firstOrNull { seen[it] }
                        if (touch != null && score(i) < best) { best = score(i); start = i; from = touch }
                    }
                    if (start < 0) {
                        val left = c.filter { !seen[it] }
                        if (left.isEmpty()) break
                        start = left.minByOrNull { score(it) }!!
                    }
                }
                if (start < 0) break
                first = false
                val path = ArrayList<Int>()
                if (from >= 0) { path.add(from); path.add(start) } else path.add(start)
                seen[start] = true
                var cur = start
                while (true) {
                    val k0 = max(0, path.size - 5)
                    var dx = (X(cur) - X(path[k0])).toDouble(); val dy = (Y(cur) - Y(path[k0])).toDouble()
                    if (dx == 0.0 && dy == 0.0) dx = if (path.size == 1) -1.0 else 0.0
                    val dl = hypot(dx, dy).let { if (it == 0.0) 1.0 else it }
                    var next = -1; var cost = Double.MAX_VALUE
                    for (j in nb(cur)) {
                        if (seen[j]) continue
                        val sx = (X(j) - X(cur)).toDouble(); val sy = (Y(j) - Y(cur)).toDouble(); val sl = hypot(sx, sy)
                        val turn = acos(max(-1.0, min(1.0, (sx * dx + sy * dy) / (sl * dl))))
                        val c2 = turn + (if (sx != 0.0 && sy != 0.0) 0.02 else 0.0)
                        if (c2 < cost) { cost = c2; next = j }
                    }
                    if (next < 0) break
                    for (j in nb(cur)) if (j != next && !seen[j] && adj8(j, next)) seen[j] = true   // staircase leftovers
                    seen[next] = true; path.add(next); cur = next
                }
                if (path.size > 8 && nb(cur).contains(path[0]) && from < 0) path.add(path[0])   // a loop closes
                val pts = path.map { pt(it) }
                val len = polyLength(pts)
                // short stubs off a longer line are rendering noise, not pen strokes
                if (len >= spur || from < 0) out.add(smooth(simplify(pts, 0.8)))
            }
        }
        return Skeleton(out, pen)
    }
}
