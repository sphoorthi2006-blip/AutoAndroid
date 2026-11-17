package com.cuevideo.app

/**
 * Faithful port of Python difflib.SequenceMatcher(None, a, b, autojunk=False)
 * for lists of words (no "junk" handling, because the desktop engine uses none).
 * Longest block first (ties: earliest in a, then earliest in b), then left and right of it.
 */
class SeqMatcher(private val a: List<String>, private val b: List<String>) {
    data class Block(val a: Int, val b: Int, val size: Int)

    private val b2j = HashMap<String, MutableList<Int>>()

    init {
        for ((j, w) in b.withIndex()) b2j.getOrPut(w) { ArrayList() }.add(j)
    }

    private fun longest(alo: Int, ahi: Int, blo: Int, bhi: Int): Block {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            val js = b2j[a[i]]
            if (js != null) {
                for (j in js) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newj2len[j] = k
                    if (k > bestsize) {
                        besti = i - k + 1
                        bestj = j - k + 1
                        bestsize = k
                    }
                }
            }
            j2len = newj2len
        }
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--; bestj--; bestsize++
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi &&
            a[besti + bestsize] == b[bestj + bestsize]
        ) {
            bestsize++
        }
        return Block(besti, bestj, bestsize)
    }

    /** Like get_matching_blocks(), without the final zero-size sentinel. */
    fun matchingBlocks(): List<Block> {
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, a.size, 0, b.size))
        val found = ArrayList<Block>()
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast().let { listOf(it[0], it[1], it[2], it[3]) }
            val x = longest(alo, ahi, blo, bhi)
            if (x.size > 0) {
                found.add(x)
                if (alo < x.a && blo < x.b) queue.addLast(intArrayOf(alo, x.a, blo, x.b))
                if (x.a + x.size < ahi && x.b + x.size < bhi)
                    queue.addLast(intArrayOf(x.a + x.size, ahi, x.b + x.size, bhi))
            }
        }
        found.sortWith(compareBy({ it.a }, { it.b }, { it.size }))
        val merged = ArrayList<Block>()
        for (blk in found) {
            val last = merged.lastOrNull()
            if (last != null && last.a + last.size == blk.a && last.b + last.size == blk.b) {
                merged[merged.size - 1] = Block(last.a, last.b, last.size + blk.size)
            } else merged.add(blk)
        }
        return merged
    }
}
