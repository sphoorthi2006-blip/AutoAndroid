package com.example.upscaler

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure Kotlin image pipeline: no AI, no external libraries. Pixels are ARGB IntArrays. */
object ImageProcessor {

    class Img(val w: Int, val h: Int, val px: IntArray)

    // ---------- 1. Mark removal (done first, on the original size) ----------
    // The sparkle sits at the same spot in every image: centre (1279, 669.5) on a 1376-wide
    // image, about 30 px "radius", drawn as a 4-point star (astroid). Positions scale with width.
    // The hole is filled by copying TEXTURE from the surrounding picture (patch-based inpainting), working
    // inwards from the edge of the hole, so grain and patterns continue through it instead of leaving a blurry patch.
    fun removeMark(src: Img): Img {
        val px = src.px.copyOf()
        val w = src.w
        val h = src.h
        val s = w / 1376.0
        val cx = 1279.0 * s
        val cy = 669.5 * (h / 768.0)
        val r = 30.0 * s
        val x0 = max(1, (cx - r - 4).toInt())
        val x1 = min(w - 2, (cx + r + 4).toInt())
        val y0 = max(1, (cy - r - 4).toInt())
        val y1 = min(h - 2, (cy + r + 4).toInt())
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        if (bw < 3 || bh < 3) return Img(w, h, px)

        // star mask, then grow it a little to cover the soft glow around the star
        var mask = BooleanArray(bw * bh)
        for (y in 0 until bh) for (x in 0 until bw) {
            val dx = abs(x + x0 - cx) / r
            val dy = abs(y + y0 - cy) / r
            if (dx.pow(2.0 / 3.0) + dy.pow(2.0 / 3.0) <= 1.0) mask[y * bw + x] = true
        }
        repeat(max(3, (3 * s).toInt())) { mask = dilate(mask, bw, bh) }

        // work window = hole box plus a margin of picture to take texture from
        val margin = max(30, (60 * s).toInt())
        val wx0 = max(0, x0 - margin)
        val wy0 = max(0, y0 - margin)
        val wx1 = min(w - 1, x1 + margin)
        val wy1 = min(h - 1, y1 + margin)
        val ww = wx1 - wx0 + 1
        val wh = wy1 - wy0 + 1
        val c0 = FloatArray(ww * wh)
        val c1 = FloatArray(ww * wh)
        val c2 = FloatArray(ww * wh)
        for (y in 0 until wh) for (x in 0 until ww) {
            val p = px[(y + wy0) * w + (x + wx0)]
            val i = y * ww + x
            c0[i] = ((p shr 16) and 255).toFloat()
            c1[i] = ((p shr 8) and 255).toFloat()
            c2[i] = (p and 255).toFloat()
        }
        val hole = BooleanArray(ww * wh)
        val holeBuf = ArrayList<Int>()
        for (y in 0 until bh) for (x in 0 until bw) {
            if (!mask[y * bw + x]) continue
            val i = (y + y0 - wy0) * ww + (x + x0 - wx0)
            hole[i] = true
            holeBuf.add(i)
        }
        val holeList = holeBuf.toIntArray()
        val known = BooleanArray(ww * wh) { !hole[it] }

        // candidate patch centres: a grid of spots whose whole patch is original picture (no hole pixels)
        val rad = 2                                       // patch is (2*rad+1) x (2*rad+1)
        val stride = max(2, Math.round(2 * s).toInt())
        val candBuf = ArrayList<Int>()
        var cy2 = rad
        while (cy2 < wh - rad) {
            var cx2 = rad
            while (cx2 < ww - rad) {
                var ok = true
                for (dy in -rad..rad) for (dx in -rad..rad) if (hole[(cy2 + dy) * ww + cx2 + dx]) ok = false
                if (ok) candBuf.add(cy2 * ww + cx2)
                cx2 += stride
            }
            cy2 += stride
        }
        if (candBuf.isEmpty()) return Img(w, h, px)
        // Nearest candidates first: a good match is found early, so the early exit in the cost loop
        // rejects most of the later candidates after a few pixels (same result, far less work).
        val hcx = cx - wx0
        val hcy = cy - wy0
        val cands = candBuf.sortedBy {
            val ddx = (it % ww) - hcx
            val ddy = (it / ww) - hcy
            ddx * ddx + ddy * ddy
        }.toIntArray()
        val offs = IntArray((2 * rad + 1) * (2 * rad + 1))
        var oi = 0
        for (dy in -rad..rad) for (dx in -rad..rad) offs[oi++] = dy * ww + dx

        // Best matching candidate for hole pixel i, looking only at the already-known pixels of its patch.
        fun bestFor(i: Int): Int {
            val vo = IntArray(offs.size)
            val t0 = FloatArray(offs.size)
            val t1 = FloatArray(offs.size)
            val t2 = FloatArray(offs.size)
            var n = 0
            for (k in offs.indices) {
                val q = i + offs[k]
                if (q >= 0 && q < known.size && known[q]) {
                    vo[n] = offs[k]; t0[n] = c0[q]; t1[n] = c1[q]; t2[n] = c2[q]; n++
                }
            }
            var best = Float.MAX_VALUE
            var bestC = cands[0]
            for (c in cands) {
                var cost = 0f
                var k = 0
                while (k < n && cost < best) {
                    val cq = c + vo[k]
                    val d0 = t0[k] - c0[cq]
                    val d1 = t1[k] - c1[cq]
                    val d2 = t2[k] - c2[cq]
                    cost += d0 * d0 + d1 * d1 + d2 * d2
                    k++
                }
                if (cost < best) { best = cost; bestC = c }
            }
            return bestC
        }

        // fill from the edge inwards, one layer at a time; the pixels of one layer are independent,
        // so they are matched on several threads
        val threads = max(1, min(4, Runtime.getRuntime().availableProcessors()))
        val pool = if (threads > 1) java.util.concurrent.Executors.newFixedThreadPool(threads) else null
        try {
            var left = holeList.size
            var guard = 0
            while (left > 0 && guard++ < 400) {
                val layerBuf = ArrayList<Int>()
                for (i in holeList) {
                    if (known[i]) continue
                    val x = i % ww
                    val y = i / ww
                    var touches = false
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val xx = x + dx
                        val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= ww || yy >= wh) continue
                        if (known[yy * ww + xx]) touches = true
                    }
                    if (touches) layerBuf.add(i)
                }
                if (layerBuf.isEmpty()) break
                val layer = layerBuf.toIntArray()
                val chosen = IntArray(layer.size)
                if (pool == null || layer.size < 48) {
                    for (li in layer.indices) chosen[li] = bestFor(layer[li])
                } else {
                    val chunk = (layer.size + threads - 1) / threads
                    val jobs = ArrayList<java.util.concurrent.Callable<Unit>>()
                    var from = 0
                    while (from < layer.size) {
                        val a = from
                        val b = min(layer.size, from + chunk)
                        jobs.add(java.util.concurrent.Callable { for (li in a until b) chosen[li] = bestFor(layer[li]) })
                        from = b
                    }
                    for (f in pool.invokeAll(jobs)) f.get()
                }
                for (li in layer.indices) {
                    val i = layer[li]
                    val c = chosen[li]
                    c0[i] = c0[c]; c1[i] = c1[c]; c2[i] = c2[c]
                }
                for (i in layer) known[i] = true
                left -= layer.size
            }
        } finally {
            pool?.shutdown()
        }

        for (i in holeList) {
            val x = i % ww
            val y = i / ww
            val r8 = c0[i].toInt().coerceIn(0, 255)
            val g8 = c1[i].toInt().coerceIn(0, 255)
            val b8 = c2[i].toInt().coerceIn(0, 255)
            px[(y + wy0) * w + (x + wx0)] = (255 shl 24) or (r8 shl 16) or (g8 shl 8) or b8
        }
        return Img(w, h, px)
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = m.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) o[yy * w + xx] = true
            }
        }
        return o
    }

    // ---------- 2. Light denoise (edge-preserving, so thin lines survive) ----------
    fun denoise(src: Img, sigmaColor: Float = 14f): Img {
        val w = src.w
        val h = src.h
        val out = src.px.copyOf()
        val inv = 1f / (2f * sigmaColor * sigmaColor)
        val wt = floatArrayOf(1f, 2f, 1f, 2f, 4f, 2f, 1f, 2f, 1f)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = src.px[y * w + x]
            val cr = (c shr 16) and 255; val cg = (c shr 8) and 255; val cb = c and 255
            var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
            var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val p = src.px[(y + dy) * w + (x + dx)]
                val pr = (p shr 16) and 255; val pg = (p shr 8) and 255; val pb = p and 255
                val d = ((pr - cr) * (pr - cr) + (pg - cg) * (pg - cg) + (pb - cb) * (pb - cb)) / 3f
                val f = wt[k++] * kotlin.math.exp(-d * inv)
                sr += pr * f; sg += pg * f; sb += pb * f; sw += f
            }
            out[y * w + x] = (255 shl 24) or ((sr / sw).toInt().coerceIn(0, 255) shl 16) or
                ((sg / sw).toInt().coerceIn(0, 255) shl 8) or (sb / sw).toInt().coerceIn(0, 255)
        }
        return Img(w, h, out)
    }

    // ---------- 3. Lanczos-3 upscale ----------
    private fun lanczos(x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= 3.0) return 0.0
        val px = PI * x
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    private class Taps(val first: IntArray, val weights: Array<FloatArray>)

    private fun makeTaps(srcLen: Int, dstLen: Int): Taps {
        val scale = dstLen.toDouble() / srcLen
        val first = IntArray(dstLen)
        val weights = Array(dstLen) { FloatArray(6) }
        for (o in 0 until dstLen) {
            val c = (o + 0.5) / scale - 0.5
            val f = floor(c).toInt() - 2
            first[o] = f
            var sum = 0.0
            val ws = DoubleArray(6)
            for (t in 0 until 6) { ws[t] = lanczos(c - (f + t)); sum += ws[t] }
            for (t in 0 until 6) weights[o][t] = (ws[t] / sum).toFloat()
        }
        return Taps(first, weights)
    }

    fun upscale(src: Img, factor: Int = 2): Img {
        val w = src.w; val h = src.h
        val w2 = w * factor; val h2 = h * factor
        val tx = makeTaps(w, w2)
        val ty = makeTaps(h, h2)
        // horizontal pass -> float buffer (h rows, w2 columns, 3 channels)
        val tmp = FloatArray(h * w2 * 3)
        for (y in 0 until h) {
            val row = y * w
            for (ox in 0 until w2) {
                var r = 0f; var g = 0f; var b = 0f
                val f = tx.first[ox]; val ws = tx.weights[ox]
                for (t in 0 until 6) {
                    val sx = (f + t).coerceIn(0, w - 1)
                    val p = src.px[row + sx]
                    val wv = ws[t]
                    r += ((p shr 16) and 255) * wv
                    g += ((p shr 8) and 255) * wv
                    b += (p and 255) * wv
                }
                val i = (y * w2 + ox) * 3
                tmp[i] = r; tmp[i + 1] = g; tmp[i + 2] = b
            }
        }
        // vertical pass
        val out = IntArray(w2 * h2)
        for (oy in 0 until h2) {
            val f = ty.first[oy]; val ws = ty.weights[oy]
            for (ox in 0 until w2) {
                var r = 0f; var g = 0f; var b = 0f
                for (t in 0 until 6) {
                    val sy = (f + t).coerceIn(0, h - 1)
                    val i = (sy * w2 + ox) * 3
                    val wv = ws[t]
                    r += tmp[i] * wv; g += tmp[i + 1] * wv; b += tmp[i + 2] * wv
                }
                out[oy * w2 + ox] = (255 shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
            }
        }
        return Img(w2, h2, out)
    }

    // ---------- 4. Gentle unsharp mask: original*1.4 - blurred*0.4 ----------
    fun sharpen(src: Img, amount: Float = 0.4f): Img {
        val w = src.w; val h = src.h
        val k = floatArrayOf(1f, 4f, 6f, 4f, 1f)
        val n = w * h
        val ch = Array(3) { FloatArray(n) }
        for (i in 0 until n) {
            val p = src.px[i]
            ch[0][i] = ((p shr 16) and 255).toFloat()
            ch[1][i] = ((p shr 8) and 255).toFloat()
            ch[2][i] = (p and 255).toFloat()
        }
        val blur = Array(3) { FloatArray(n) }
        val tmp = FloatArray(n)
        for (c in 0 until 3) {
            val f = ch[c]
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (t in -2..2) s += f[y * w + (x + t).coerceIn(0, w - 1)] * k[t + 2]
                tmp[y * w + x] = s / 16f
            }
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f
                for (t in -2..2) s += tmp[(y + t).coerceIn(0, h - 1) * w + x] * k[t + 2]
                blur[c][y * w + x] = s / 16f
            }
        }
        val out = IntArray(n)
        val a = 1f + amount
        for (i in 0 until n) {
            val r = (ch[0][i] * a - blur[0][i] * amount).toInt().coerceIn(0, 255)
            val g = (ch[1][i] * a - blur[1][i] * amount).toInt().coerceIn(0, 255)
            val b = (ch[2][i] * a - blur[2][i] * amount).toInt().coerceIn(0, 255)
            out[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Img(w, h, out)
    }

    // ---------- optional: crop the black strip some generators leave at the top ----------
    fun cropTopBlack(src: Img): Img {
        var rows = 0
        val limit = min(src.h / 8, 60)
        while (rows < limit) {
            var sum = 0L
            for (x in 0 until src.w) {
                val p = src.px[rows * src.w + x]
                sum += ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)
            }
            if (sum / (src.w * 3.0) > 12.0) break
            rows++
        }
        if (rows == 0) return src
        return Img(src.w, src.h - rows, src.px.copyOfRange(rows * src.w, src.px.size))
    }

    // ---------- AI upscale (Real-ESRGAN via ncnn), done in overlapping tiles ----------
    private const val TILE_CPU = 192      // source pixels per tile on the CPU
    private const val TILE_GPU = 256      // bigger tiles on the GPU: less overhead and less thrown-away overlap
    private const val TILE_PAD = 10       // extra context around each tile, cut off again afterwards (same as Real-ESRGAN-ncnn)
    private const val AI_MAX_OUTPUT_PIXELS = 30_000_000L

    /** Set by process(): why the AI path was skipped (null when it worked or was not asked for). */
    @Volatile var aiNote: String? = null

    /** Set by process(): how long the last picture spent in each stage (milliseconds). */
    @Volatile var lastMarkMs = 0L
    @Volatile var lastScaleMs = 0L
    /** "AI" or "classic": which method produced the last picture. */
    @Volatile var lastMethod = ""

    private fun msSince(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000L

    fun aiUpscale(
        src: Img,
        scale: Int,
        assets: android.content.res.AssetManager,
        useGpu: Boolean,
        onTile: ((done: Int, total: Int) -> Unit)? = null
    ): Img {
        if (!NativeUpscaler.ensureLoaded(assets, scale, useGpu)) {
            throw IllegalStateException("AI model could not be loaded")
        }
        val w = src.w
        val h = src.h
        val ow = w * scale
        val oh = h * scale
        val out = IntArray(ow * oh)
        val tile = if (NativeUpscaler.usingGpu()) TILE_GPU else TILE_CPU
        val tilesX = (w + tile - 1) / tile
        val tilesY = (h + tile - 1) / tile
        val total = tilesX * tilesY
        var done = 0
        var ty = 0
        while (ty < h) {
            val th = min(tile, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = min(tile, w - tx)
                val x0 = max(0, tx - TILE_PAD)
                val y0 = max(0, ty - TILE_PAD)
                val x1 = min(w, tx + tw + TILE_PAD)
                val y1 = min(h, ty + th + TILE_PAD)
                val iw = x1 - x0
                val ih = y1 - y0
                val tile = IntArray(iw * ih)
                for (y in 0 until ih) System.arraycopy(src.px, (y0 + y) * w + x0, tile, y * iw, iw)
                val res = NativeUpscaler.run(tile, iw, ih) ?: throw IllegalStateException("AI step failed on a tile")
                val rw = iw * scale
                val cropX = (tx - x0) * scale
                val cropY = (ty - y0) * scale
                for (y in 0 until th * scale) {
                    System.arraycopy(res, (cropY + y) * rw + cropX, out, (ty * scale + y) * ow + tx * scale, tw * scale)
                }
                done++
                onTile?.invoke(done, total)
                tx += tw
            }
            ty += th
        }
        return Img(ow, oh, out)
    }

    /**
     * aiScale 2..4 uses the AI model (falls back to the classic pipeline if it cannot run);
     * anything else uses the classic pipeline (denoise, Lanczos 2x, sharpen).
     */
    fun process(
        src: Img,
        removeMark: Boolean,
        cropStrip: Boolean,
        aiScale: Int = 0,
        assets: android.content.res.AssetManager? = null,
        useGpu: Boolean = true,
        onTile: ((done: Int, total: Int) -> Unit)? = null
    ): Img {
        var img = src
        aiNote = null
        lastMarkMs = 0L
        lastScaleMs = 0L
        lastMethod = ""
        if (removeMark) {                          // mark position is defined on the uncropped image
            val t0 = System.nanoTime()
            img = removeMark(img)
            lastMarkMs = msSince(t0)
        }
        if (cropStrip) img = cropTopBlack(img)
        if (aiScale in 2..4 && assets != null) {
            var s = aiScale
            while (s >= 2 && img.w.toLong() * img.h * s * s > AI_MAX_OUTPUT_PIXELS) s--
            if (s < 2) {
                aiNote = "picture too large for the AI step, used the classic method"
            } else {
                val t1 = System.nanoTime()
                try {
                    if (s != aiScale) aiNote = "picture is big, AI scale reduced to ${s}x"
                    val res = aiUpscale(img, s, assets, useGpu, onTile)
                    lastScaleMs = msSince(t1)
                    lastMethod = "AI"
                    return res
                } catch (t: Throwable) {
                    aiNote = "AI step failed (${t.message}), used the classic method"
                }
            }
        }
        val t2 = System.nanoTime()
        img = denoise(img)
        img = upscale(img, if (img.w.toLong() * img.h <= 2_200_000L) 2 else 1)   // very large pictures are not doubled (memory)
        img = sharpen(img)
        lastScaleMs = msSince(t2)
        lastMethod = "classic"
        return img
    }
}
