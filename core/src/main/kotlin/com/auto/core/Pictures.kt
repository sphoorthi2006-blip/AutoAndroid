package com.auto.core

import kotlin.math.min

/** A picture found under a chosen root (folder or ZIP): rel = path parts below the root. */
class PicRef(val root: String, val rel: List<String>, val path: String)

/** Which picture goes on which scene: by the number in the file name (7.jpg = scene 7), else by file-name order. Port of engine.py _assign_pictures. */
object Pics {
    fun stemOf(path: String): String { val f = path.substringAfterLast('/'); return if ('.' in f && f.lastIndexOf('.') > 0) f.substringBeforeLast('.') else f }
    fun nameOf(path: String) = path.substringAfterLast('/')

    /** Natural sort: digit runs compared as numbers, path parts one by one ('2.jpg' before '10.jpg'). */
    fun naturalCompare(a: String, b: String): Int {
        fun key(s: String) = s.split('/').filter { it.isNotEmpty() }.map { part -> Regex("(\\d+)").let { r ->
            val out = ArrayList<Any>(); var last = 0
            for (m in r.findAll(part.lowercase())) { out.add(part.lowercase().substring(last, m.range.first)); out.add(m.value.toBigInteger()); last = m.range.last + 1 }
            out.add(part.lowercase().substring(last)); out } }
        val ka = key(a); val kb = key(b)
        for (i in 0 until min(ka.size, kb.size)) {
            val x = ka[i]; val y = kb[i]
            for (t in 0 until min(x.size, y.size)) {
                val c = if (x[t] is String) (x[t] as String).compareTo(y[t] as String) else (x[t] as java.math.BigInteger).compareTo(y[t] as java.math.BigInteger)
                if (c != 0) return c
            }
            if (x.size != y.size) return x.size.compareTo(y.size)
        }
        return ka.size.compareTo(kb.size)
    }
    val NATURAL = Comparator<String> { a, b -> naturalCompare(a, b) }

    private val NUM_IN_NAME = Regex("(?:image|img|scene|shot|frame|pic|picture|slide|prompt|cue)[\\s_\\-#.]*0*(\\d+)", RegexOption.IGNORE_CASE)
    private val COPY_SUFFIX = Regex("\\s*\\(\\d+\\)$")

    /** The scene number a file name carries: '7.jpg', '007.png', 'Image 7.jpg', 'scene_7_final.png'. null when the name has no number, or
     *  several that cannot be told apart (random generator names), or a date / long id (100000 or more). A Windows copy '7 (1).jpg' counts as 7. */
    fun imageNumber(path: String): Int? {
        val stem = COPY_SUFFIX.replace(stemOf(path), "")
        val m = NUM_IN_NAME.find(stem)
        val runs = if (m != null) listOf(m.groupValues[1]) else Regex("\\d+").findAll(stem).map { it.value }.toList()
        if (runs.size != 1) return null
        val n = runs[0].toLongOrNull() ?: return null
        return if (n < 100000) n.toInt() else null
    }

    class Assignment(val slots: List<String?>, val extras: List<String>, val mode: String)

    fun assign(pics0: List<String>, n: Int, iss: Issues): Assignment {
        val pics = pics0.sortedWith(NATURAL)
        val nums = pics.associateWith { imageNumber(it) }
        val numbered = pics.filter { nums[it] != null }
        if (numbered.isNotEmpty() && numbered.size >= maxOf(1, Math.ceil(0.6 * pics.size).toInt())) {
            val shift = if (numbered.minOf { nums[it]!! } == 0 && numbered.none { nums[it] == n }) 1 else 0
            if (shift == 1) iss.info("The picture numbers start at 0: picture 0 was used for scene 1, picture 1 for scene 2, and so on.", null, "pic_num")
            val byNum = java.util.TreeMap<Int, MutableList<String>>()
            // '7.jpg' comes before its copy '7 (1).jpg'
            for (p in numbered.sortedWith(compareBy<String> { COPY_SUFFIX.containsMatchIn(stemOf(it)) }.thenComparator { a, b -> naturalCompare(a, b) }))
                byNum.getOrPut(nums[p]!! + shift) { ArrayList() }.add(p)
            val slots = (0 until n).map { k -> byNum[k + 1]?.get(0) }
            val extras = ArrayList<String>()
            for ((num, lst) in byNum) if (lst.size > 1) {
                iss.warn("Picture number $num exists ${lst.size} times (${lst.take(4).joinToString(", ") { nameOf(it) }}). Used '${nameOf(lst[0])}', left out the others.",
                    if (num <= n) num else null, "dup_pic")
                extras += lst.drop(1)
            }
            val beyond = byNum.keys.filter { it > n }
            if (beyond.isNotEmpty()) {
                extras += beyond.map { byNum[it]!![0] }
                iss.warn("${beyond.size} picture(s) are numbered beyond the last scene ($n) and were left out: " +
                    beyond.take(8).joinToString(", ") { nameOf(byNum[it]!![0]) } + (if (beyond.size > 8) " ..." else ""), null, "extra_pic")
            }
            val loose = pics.filter { nums[it] == null }
            if (loose.isNotEmpty()) {
                extras += loose
                iss.warn("${loose.size} picture(s) have no number in their file name and were not placed: " +
                    loose.take(8).joinToString(", ") { nameOf(it) } + (if (loose.size > 8) " ..." else ""), null, "unnumbered_pic")
            }
            return Assignment(slots, extras, "number")
        }
        val slots = (0 until n).map { k -> pics.getOrNull(k) }
        val extras = if (pics.size > n) pics.drop(n) else emptyList()
        if (pics.isNotEmpty()) iss.warn("The picture file names carry no scene numbers, so the pictures were placed in file-name order. " +
            "Name them 1, 2, 3 ... (or Image 1, Image 2 ...) to be sure each one lands on its own scene.", null, "order_mode")
        if (extras.isNotEmpty()) iss.warn("${extras.size} extra picture(s) beyond the last scene ($n) were left out.", null, "extra_pic")
        return Assignment(slots, extras, "order")
    }
}
