package com.auto.core

import kotlin.math.*

/** One picture folder (or ZIP) and the part of the script it was matched to. Pictures are plain path strings. */
class Group(val name: String, val images: List<String>) {
    var scenes: List<Scene> = emptyList()
    var window = Pair(0, 0)
    var sure = true
    var skipped = ""
    var extras: List<String> = emptyList()
}

/** A picture found under a chosen root (folder or ZIP): rel = path parts below the root. */
class PicRef(val root: String, val rel: List<String>, val path: String)

object Hungarian {
    /** Best one-to-one assignment maximising the total score (rows may be fewer or more than columns). Returns the column per row, -1 if none. */
    fun assign(score: Array<DoubleArray>): IntArray {
        val n0 = score.size; if (n0 == 0) return IntArray(0)
        val m0 = score[0].size; if (m0 == 0) return IntArray(n0) { -1 }
        val tr = n0 > m0
        val n = if (tr) m0 else n0; val m = if (tr) n0 else m0
        val a = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) for (j in 1..m) a[i][j] = -(if (tr) score[j - 1][i - 1] else score[i - 1][j - 1])
        val INF = Double.MAX_VALUE / 4
        val u = DoubleArray(n + 1); val v = DoubleArray(m + 1); val p = IntArray(m + 1); val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i; var j0 = 0; val minv = DoubleArray(m + 1) { INF }; val used = BooleanArray(m + 1)
            do {
                used[j0] = true; val i0 = p[j0]; var delta = INF; var j1 = 0
                for (j in 1..m) if (!used[j]) {
                    val cur = a[i0][j] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..m) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                j0 = j1
            } while (p[j0] != 0)
            do { val j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while (j0 != 0)
        }
        val out = IntArray(n0) { -1 }
        for (j in 1..m) if (p[j] != 0) { if (tr) out[j - 1] = p[j] - 1 else out[p[j] - 1] = j - 1 }
        return out
    }
}

/** Port of the picture-matching part of scriptvideo/engine.py (number / file-name words / folder -> section placement). */
object Matching {
    // ------------------------------------------------------------ small helpers
    fun stemOf(path: String): String { val f = path.substringAfterLast('/'); return if ('.' in f && f.lastIndexOf('.') > 0) f.substringBeforeLast('.') else f }
    fun nameOf(path: String) = path.substringAfterLast('/')

    /** natural sort key like engine.natural_key: parts of the path, digit runs compared as numbers. */
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

    private val NUM_IN_NAME = Regex("(?:image|img|scene|shot|frame|pic|picture|slide)[\\s_\\-#.]*0*(\\d+)", RegexOption.IGNORE_CASE)
    /** 'image_07.png', 'Image 7.jpg', '7.png', 'intro_scene3 (1).webp' -> 7, 7, 7, 3. */
    fun imageNumber(path: String): Int? {
        val stem = stemOf(path).replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
        NUM_IN_NAME.find(stem)?.let { return it.groupValues[1].toInt() }
        return Regex("\\d+").findAll(stem).lastOrNull()?.value?.toInt()
    }
    private fun numberMap(images: List<String>): Map<Int, String> {
        val by = LinkedHashMap<Int, MutableList<String>>()
        for (p in images) imageNumber(p)?.let { by.getOrPut(it) { mutableListOf() }.add(p) }
        return by.filter { it.value.size == 1 }.mapValues { it.value[0] }
    }
    private fun sceneKeys(items: List<SceneItem>, a: Int, b: Int) = (a until b).map { items[it].num ?: (it + 1) }

    /** Give scenes a..b-1 the picture whose file-name number equals the scene's number. Null when the names carry no usable numbers. */
    fun numberAssign(items: List<SceneItem>, images: List<String>, a: Int, b: Int): Pair<MutableList<String?>, String>? {
        val nm = numberMap(images); val n = b - a
        if (nm.size < max(2, (0.6 * min(images.size, n)).toInt())) return null
        val nop = (a until b).map { items[it].nopic }                  // a heading with no picture of its own takes no number
        fun seq(first: Int): List<Int?> { var c = first; return nop.map { h -> if (h) null else c.also { c++ } } }
        val keys = LinkedHashMap<String, List<Int?>>()
        keys["details"] = sceneKeys(items, a, b); keys["folder"] = seq(1); keys["folder from 0"] = (0 until n).toList()
        if (nop.any { it }) keys.remove("folder from 0")
        if (keys["details"]!!.toSet().size != n || nop.any { it }) keys.remove("details")   // numbering restarts in every section: cannot identify a picture
        var best = ""; var bv = -1
        for ((k, v) in keys) { val sc = v.count { it != null && it in nm }; if (sc > bv) { bv = sc; best = k } }
        val got = keys[best]!!.map { if (it == null) null else nm[it] }.toMutableList()
        val hit = got.count { it != null }
        if (hit < 0.6 * (n - nop.count { it })) return null
        return Pair(got, "Pictures matched by their number ($best numbering): $hit of $n scenes.")
    }

    private fun numberWindow(items: List<SceneItem>, images: List<String>, lo: Int, hi: Int): Pair<Int, Int>? {
        val nm = numberMap(images); val K = images.size
        if (nm.size < max(2, (0.6 * K).toInt())) return null
        val keys = sceneKeys(items, lo, hi)
        if (keys.toSet().size != keys.size) return null
        val hits = keys.withIndex().filter { it.value in nm }.map { lo + it.index }
        if (hits.size < 0.6 * K) return null
        val a = hits.min(); val b = hits.max() + 1
        return if (b - a <= K) Pair(a, b) else null
    }

    // ------------------------------------------------------------ file-name words
    private fun stem(w: String): String { for (suf in listOf("ing", "ed", "es", "s")) if (w.endsWith(suf) && w.length - suf.length >= 4) return w.dropLast(suf.length); return w }

    fun nameWords(path: String): List<String> {
        var st = stemOf(path)
        val cut = Regex("#U2026|\u2026|\\.\\.\\.").containsMatchIn(st)
        st = st.replace(Regex("(#U2026|\u2026|\\.\\.\\.)"), " ~ ")
        st = st.replace(Regex("[_\\-]?[0-9a-f]{8}$"), "")
        var ws = Regex("[a-z]+|~").findAll(st.lowercase()).map { it.value }.toList()
        if (cut && "~" in ws) { val k = ws.indexOf("~"); ws = if (k > 0 && ws[k - 1].length < 5) ws.take(max(k - 1, 0)) else ws.take(k) }
        return ws.filter { it.length > 2 && it != "~" && it !in STOP }.map { stem(it) }
    }
    private fun sceneWordSets(items: List<SceneItem>) = items.map { it ->
        Regex("[a-z]+").findAll((it.visual + " " + it.spoken).lowercase()).map { m -> stem(m.value) }.toSet() }

    fun nameScores(items: List<SceneItem>, images: List<String>, lo: Int, hi: Int): Array<DoubleArray> {
        val docs = sceneWordSets(items); val N = docs.size
        val df = HashMap<String, Int>(); for (d in docs) for (w in d) df[w] = (df[w] ?: 0) + 1
        fun idf(w: String) = ln((N + 1.0) / ((df[w] ?: 0) + 0.5))
        val S = Array(hi - lo) { DoubleArray(images.size) }
        for ((j, p) in images.withIndex()) {
            val nw = nameWords(p); val tot = nw.sumOf { idf(it) } + 1e-6
            for (i in lo until hi) { val d = docs[i]
                S[i - lo][j] = nw.filter { w -> w in d || d.any { x -> w.length >= 5 && x.length >= 5 && (x.startsWith(w) || w.startsWith(x)) } }.sumOf { idf(it) } / tot }
        }
        return S
    }
    private fun namesUsable(images: List<String>) = images.count { nameWords(it).size >= 2 } >= max(2, (0.6 * images.size).toInt())

    fun nameAssign(items: List<SceneItem>, images: List<String>, a: Int, b: Int): Triple<List<String>, List<String>, String>? {
        val n = b - a
        if (images.size < n || !namesUsable(images)) return null
        val S = nameScores(items, images, a, b); val order = Hungarian.assign(S)
        val best = order.withIndex().map { S[it.index][it.value] }
        if (best.average() < 0.25) return null
        val conf = order.withIndex().map { (i, j) ->
            val rowMax = S[i].filterIndexed { k, _ -> k != j }.maxOrNull() ?: 0.0
            if (S[i][j] >= 0.3 && S[i][j] - rowMax >= 0.12) "" else "?" }
        return Triple(order.map { images[it] }, conf, "Pictures matched by the words in their file names: $n scenes, ${conf.count { it == "?" }} to check.")
    }

    private fun nameWindow(items: List<SceneItem>, images: List<String>, lo: Int, hi: Int): Pair<Int, Int>? {
        val K = images.size
        if (K >= hi - lo || K < 2 || !namesUsable(images)) return null
        val S = nameScores(items, images, lo, hi)
        val tot = ArrayList<Double>(); val good = ArrayList<Double>()
        for (a in 0..(hi - lo - K)) {
            val sub = Array(K) { S[a + it] }; val o = Hungarian.assign(sub)
            val sc = o.withIndex().map { sub[it.index][it.value] }
            tot.add(sc.sum()); good.add(sc.count { it >= 0.3 }.toDouble() / K)
        }
        var k = 0; for (i in tot.indices) if (tot[i] > tot[k]) k = i
        val sorted = tot.sorted()
        if (good[k] < 0.7 || (tot.size > 1 && sorted[sorted.size - 1] - sorted[sorted.size - 2] < 1e-6)) return null
        return Pair(lo + k, lo + k + K)
    }
    private fun windowByNames(match: String, items: List<SceneItem>, images: List<String>, lo: Int, hi: Int): Pair<Int, Int>? {
        if (match == "number") numberWindow(items, images, lo, hi)?.let { return it }
        if (match == "number" || match == "name") return nameWindow(items, images, lo, hi)
        return null
    }

    // ------------------------------------------------------------ sections and folder names
    private val KW = mapOf("introduction" to "intro", "intro" to "intro", "prologue" to "intro", "outro" to "outro", "conclusion" to "outro", "epilogue" to "outro",
        "ending" to "outro", "closing" to "outro", "section" to "section", "sec" to "section", "part" to "section", "chapter" to "section", "segment" to "section",
        "para" to "section", "paragraph" to "section", "act" to "section", "summary" to "summary", "overview" to "overview", "background" to "background",
        "body" to "body", "aftermath" to "aftermath")
    private val NUMW = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10)

    fun sectionRef(text: String): Pair<String, Int?>? {
        var t = text.lowercase().replace(Regex("([a-z])(\\d)"), "$1 $2"); t = t.replace(Regex("(\\d)([a-z])"), "$1 $2")
        val words = Regex("[a-z]+|\\d+").findAll(t).map { it.value }.toList()
        for ((i, w) in words.withIndex()) {
            val k = KW[w] ?: continue
            if (k != "section") return Pair(k, null)
            for (w2 in words.drop(i + 1).take(2)) { if (w2.all { it.isDigit() }) return Pair("section", w2.toInt()); NUMW[w2]?.let { return Pair("section", it) } }
            if (words[0].all { it.isDigit() }) return Pair("section", words[0].toInt())
            return Pair("section", null)
        }
        if (words.size == 1 && words[0].all { it.isDigit() }) return Pair("section", words[0].toInt())
        return null
    }
    private fun normTitle(t: String) = t.lowercase().replace(Regex("[^a-z0-9 ]+"), "").trim()

    /** (section index or null, decided by the name). */
    fun matchGroupToSection(name: String, sections: List<Pair<Int, String>>): Pair<Int?, Boolean> {
        val real = sections.withIndex().filter { it.value.second != "Start" }.map { Pair(it.index, it.value.second) }
        if (sections.isNotEmpty() && sections[0].second == "Start" && sectionRef(name) == Pair("intro", null) &&
            real.none { sectionRef(it.second) == Pair("intro", null) }) return Pair(0, true)   // details whose first block has no heading: that block IS the intro
        val n = normTitle(name)
        val nSp = normTitle(name.replace(Regex("[_\\-.]+"), " "))                             // '01_Crafty_Question' -> 'crafty question'
        val names = setOf(n, nSp, nSp.replace(Regex("^\\d+\\s*"), "").trim()).filter { it.length >= 3 }.toSet()
        for ((i, t) in real) {
            val forms = (listOf(t) + Regex("\\s*[:\u2013\u2014-]\\s+|:\\s*").split(t)).map { normTitle(it) }.toSet()
            if (names.any { it in forms }) return Pair(i, true)
        }
        val ref = sectionRef(name) ?: return Pair(null, false)
        val (kw, num) = ref
        val same = real.map { (i, t) -> Pair(i, sectionRef(t)) }.filter { it.second != null && it.second!!.first == kw }
        if (same.isEmpty() || (kw == "section" && num == null)) return Pair(null, false)
        val hit = same.filter { kw != "section" || it.second!!.second == num }.map { it.first }
        return if (hit.isNotEmpty()) Pair(hit[0], true) else Pair(null, true)
    }

    private val TITLE_LINE = Regex("^\\s*(?:#+\\s*)?(?:section|part|chapter)?\\s*(\\d+)\\s*[.):\\-\u2013\u2014]\\s*(\\S.{0,60})$", RegexOption.IGNORE_CASE)
    private val GENERIC_TITLE = Regex("^(start|intro|introduction|prologue|outro|conclusion|epilogue|ending|closing|summary|overview|(?:part|section|chapter)\\s*\\d+)$", RegexOption.IGNORE_CASE)

    private fun cleanSecTitle(m: kotlin.text.MatchResult) = "Section ${m.groupValues[1].toInt()}: ${m.groupValues[2].trim()}"

    /** The heading line of the script that sits directly in front of the line that STARTS with `spoken` (any heading style), or "". */
    fun scriptHeadingBefore(scriptLines: List<String>, spoken: String): String {
        val tk = Details.tokens(spoken).take(4)
        if (tk.isEmpty() || scriptLines.isEmpty()) return ""
        for ((k, ln) in scriptLines.withIndex()) {
            if (k == 0 || Details.isHeading(ln)) continue
            val lt = Details.tokens(ln)
            if (lt.take(tk.size) == tk && Details.isHeading(scriptLines[k - 1])) return Details.cleanTitle(scriptLines[k - 1])
        }
        return ""
    }

    private fun sectionsFromNumbering(items: List<SceneItem>, scriptText: String = ""): List<Pair<Int, String>> {
        val nums = items.map { it.num }
        if (items.size < 3 || nums.any { it == null }) return emptyList()
        val starts = listOf(0) + (1 until items.size).filter { nums[it]!! <= nums[it - 1]!! }
        if (starts.size < 2) return emptyList()
        val slines = Details.linesOf(scriptText)
        var outH = HashMap<Int, String>()
        for ((k, i) in starts.withIndex()) {
            val h = scriptHeadingBefore(slines, items[i].spoken)
            if (h.isNotEmpty() && !GENERIC_TITLE.matches(h) && (k > 0 || TITLE_LINE.containsMatchIn(h))) {
                val m = TITLE_LINE.find(h)
                outH[i] = if (m != null) cleanSecTitle(m) else h
            }
        }
        if (starts.any { items[it].heading }) outH = HashMap()          // headings are already scenes: handled below
        if (outH.isNotEmpty()) return starts.withIndex().map { (k, i) -> Pair(i, outH[i] ?: (if (k == 0) "Intro" else if (k == starts.size - 1) "Outro" else "Part ${k + 1}")) }
        return starts.withIndex().map { (k, i) ->
            if (items[i].heading) {
                val h = items[i].spoken.trim(); val m = TITLE_LINE.find(h)
                return@map Pair(i, if (m != null) cleanSecTitle(m) else h)
            }
            val sp = items[i].spoken.trim(); val m = TITLE_LINE.find(sp)
            val t = if (m != null && m.groupValues[2].split(Regex("\\s+")).size <= 8 && !m.groupValues[2].trimEnd().let { it.endsWith(".") || it.endsWith(",") || it.endsWith(";") || it.endsWith("?") || it.endsWith("!") })
                cleanSecTitle(m) else if (k == 0) "Intro" else if (k == starts.size - 1) "Outro" else "Part ${k + 1}"
            Pair(i, t)
        }
    }

    /** [(first scene index, title)] from headings in the details, else from the numbering, else from headings in the script. */
    fun findSections(items: List<SceneItem>, scriptText: String = ""): List<Pair<Int, String>> {
        val secs = ArrayList<Pair<Int, String>>()
        for ((i, it) in items.withIndex()) if (it.section.isNotEmpty() && (secs.isEmpty() || secs.last().second != it.section)) secs.add(Pair(i, it.section))
        if (secs.isNotEmpty()) { if (secs[0].first > 0) secs.add(0, Pair(0, "Start")); return secs }
        val num = sectionsFromNumbering(items, scriptText); if (num.isNotEmpty()) return num
        if (scriptText.isEmpty()) return emptyList()
        val lines = Details.linesOf(scriptText); val toks = items.map { Details.tokens(it.spoken) }
        var last = 0; val out = ArrayList<Pair<Int, String>>()
        for ((k, ln) in lines.withIndex()) {
            if (!Details.isHeading(ln) || k + 1 >= lines.size || Details.isHeading(lines[k + 1])) continue
            val head = Details.tokens(lines.drop(k + 1).take(2).joinToString(" ")).take(60).toSet()
            var best = 0.0; var bi: Int? = null
            for (i in last until items.size) {
                if (toks[i].size >= 3) { val sc = toks[i].toSet().intersect(head).size.toDouble() / toks[i].toSet().size
                    if (sc > best + 1e-9) { best = sc; bi = i }; if (best > 0.9) break }
            }
            if (bi != null && best >= 0.5 && (out.isEmpty() || bi > out.last().first)) { out.add(Pair(bi, Details.cleanTitle(ln))); last = bi }
        }
        if (out.size >= 2 && out[0].first > 0) out.add(0, Pair(0, "Intro"))         // the lines before the first heading are the intro
        return if (out.size >= 2) out else emptyList()
    }


    // ------------------------------------------------------------ section headings as scenes
    private fun headingSpoken(wtoks: List<String>, head: String, first: String): Boolean {
        val H = Details.tokens(head).map { Align.tn(it) }; val F = Details.tokens(first).map { Align.tn(it) }.take(3)
        if (H.isEmpty() || F.isEmpty()) return false
        val W = wtoks.map { Align.tn(it) }; val need = max(1, (0.6 * H.size + 0.5).toInt())
        for (i in 0..(W.size - F.size)) {
            if (W.subList(i, i + F.size) == F) {
                val win = W.subList(max(0, i - H.size - 3), i); val left = H.toMutableList(); var hit = 0
                for (w in win) if (left.remove(w)) hit++
                if (hit >= need) return true
            }
        }
        return false
    }

    /** A section heading that the SCRIPT has and the narrator SAYS, but the details do not list, becomes a scene of its own
     *  (with a picture when its folder has one more picture than the section has scenes, else a black screen with the text). */
    fun addHeadingItems(items: List<SceneItem>, scriptText: String, groups: List<Group>, warnings: MutableList<String>, wtoks: List<String>? = null): List<SceneItem> {
        val secs = findSections(items, scriptText)
        if (secs.isEmpty()) return items
        val slines = Details.linesOf(scriptText)
        val npics = HashMap<Int, Int>(); var unnamed = false
        for (g in groups) { val idx = matchGroupToSection(g.name, secs).first; if (idx == null) unnamed = true else npics[idx] = (npics[idx] ?: 0) + g.images.size }
        if (unnamed && groups.isNotEmpty() && groups.size == secs.size && npics.isEmpty()) groups.forEachIndexed { i, g -> npics[i] = g.images.size }
        val out = ArrayList<SceneItem>(items.subList(0, secs[0].first)); val made = ArrayList<Pair<String, Boolean>>()
        for ((si, sec) in secs.withIndex()) {
            val a = sec.first; val b = if (si + 1 < secs.size) secs[si + 1].first else items.size
            val seg = items.subList(a, b); var outSeg: List<SceneItem> = seg
            if (seg.isNotEmpty()) {
                var head = seg[0].section.trim().ifEmpty { scriptHeadingBefore(slines, seg[0].spoken) }
                if (head.isEmpty() || GENERIC_TITLE.matches(head)) head = ""
                val T = Details.tokens(head).map { Align.tn(it) }.toSet()
                val ft = Details.tokens(seg[0].spoken).map { Align.tn(it) }
                val already = ft.isNotEmpty() && ft.size <= T.size + 1 && ft.count { it in T } >= 0.6 * ft.size
                if (head.isNotEmpty() && !already) {
                    val ok = if (!wtoks.isNullOrEmpty()) headingSpoken(wtoks, head, seg[0].spoken) else si > 0
                    if (ok) {
                        val withPic = npics[si] == seg.size + 1
                        if (withPic) outSeg = seg.map { it.copy(num = it.num?.plus(1)) }
                        out.add(SceneItem(if (withPic) 1 else -1, head, head, "", head, section = seg[0].section, type = "Heading", heading = true, nopic = !withPic))
                        made.add(Pair(head, withPic))
                    }
                }
            }
            out.addAll(outSeg)
        }
        if (made.isNotEmpty()) warnings.add("Section headings added as scenes: " + made.joinToString("; ") { it.first + (if (it.second) " (with picture)" else " (black screen + text)") } + ".")
        return out
    }

    // ------------------------------------------------------------ folders (groups)
    /** Split the pictures into groups = image folders (every sub-folder or ZIP of what the user chose is one group). */
    fun imageGroups(imgs: List<PicRef>, rootNames: Map<String, String>): List<Group> {
        val byRoot = LinkedHashMap<String, MutableList<PicRef>>()
        for (p in imgs) byRoot.getOrPut(p.root) { mutableListOf() }.add(p)
        val found = LinkedHashMap<Pair<String, String>, MutableList<PicRef>>()
        for ((r, plist) in byRoot) {
            var base = rootNames[r]?.takeIf { it.isNotEmpty() } ?: r.substringAfterLast('/').ifEmpty { "Pictures" }
            var depth = 0
            while (plist.all { it.rel.size - depth > 1 } && plist.map { it.rel[depth] }.toSet().size == 1) { base = plist[0].rel[depth]; depth++ }
            for (p in plist) { val rest = p.rel.drop(depth); found.getOrPut(Pair(r, if (rest.size > 1) rest[0] else base)) { mutableListOf() }.add(p) }
        }
        val out = ArrayList<Group>(); val used = HashMap<String, Int>()
        for ((key, lst) in found.entries.sortedWith { a, b -> naturalCompare(a.key.second, b.key.second) }) {
            val name = key.second; val n = (used[name.lowercase()] ?: 0) + 1; used[name.lowercase()] = n
            out.add(Group(if (n == 1) name else "$name ($n)", lst.sortedWith { a, b -> naturalCompare(a.rel.joinToString("/"), b.rel.joinToString("/")) }.map { it.path }))
        }
        return out
    }

    private val GENERIC = setOf("image", "images", "img", "pic", "pics", "picture", "pictures", "folder", "new", "copy", "final", "section", "part", "chapter",
        "scene", "scenes", "zip", "jpg", "jpeg", "png", "file", "files", "video", "untitled", "all")
    private fun nameWordset(text: String) = Regex("[a-z]+").findAll(text.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()).map { it.value }
        .filter { it.length > 2 && it !in STOP && it !in GENERIC }.map { stem(it) }.toSet()
    private fun titleScore(folder: String, title: String): Double {
        val a = nameWordset(folder); val b = nameWordset(title); val inter = a.intersect(b).size
        if (a.isEmpty() || b.isEmpty() || inter == 0 || inter.toDouble() / a.size < 0.6) return 0.0
        return 0.6 * inter / a.size + 0.4 * inter / b.size
    }
    private fun countScore(K: Int, L: Int) = if (K == L) 1.0 else if (K < L) 0.5 * K / L else 0.6 * L / K
    private fun filenameScore(items: List<SceneItem>, images: List<String>, lo: Int, hi: Int): Double? {
        if (!namesUsable(images)) return null
        val S = nameScores(items, images, lo, hi)
        val M = if (S[0].size >= S.size) S else Array(S[0].size) { j -> DoubleArray(S.size) { i -> S[i][j] } }
        val o = Hungarian.assign(M); val vals = o.withIndex().filter { it.value >= 0 }.map { M[it.index][it.value] }
        return if (vals.isEmpty()) 0.0 else vals.average()
    }

    private class Guess(val idx: Int, val total: Double, val sure: Boolean)

    private fun inferGroups(groups: List<Group>, items: List<SceneItem>, sections: List<Pair<Int, String>>, bounds: List<Int>, taken: Set<Int>): Map<Group, Guess> {
        val real = sections.withIndex().filter { it.value.second != "Start" }.map { it.index }
        val avail = real.filter { it !in taken }
        if (real.size < 2 || groups.isEmpty() || avail.isEmpty()) return emptyMap()
        val G = groups.size; val S = avail.size
        val ranks = groups.sortedWith { a, b -> naturalCompare(a.name, b.name) }.withIndex().associate { it.value to it.index }
        val M = Array(G) { DoubleArray(S) }
        for ((a, g) in groups.withIndex()) {
            val K = g.images.size
            for ((b, si) in avail.withIndex()) {
                val lo = bounds[si]; val hi = bounds[si + 1]; val f = filenameScore(items, g.images, lo, hi)
                val order = if (G > 1 && S > 1) 1.0 - abs(ranks[g]!!.toDouble() / (G - 1) - b.toDouble() / (S - 1)) else 0.0
                M[a][b] = 1.0 * titleScore(g.name, sections[si].second) + 0.9 * countScore(K, hi - lo) + (if (f != null) 1.2 * f else 0.0) + 0.3 * order
            }
        }
        val out = LinkedHashMap<Group, Guess>()
        for ((a, b) in Hungarian.assign(M).withIndex()) {
            if (b < 0) continue
            val tot = M[a][b]; if (tot < 0.5) continue
            val others = M[a].filterIndexed { k, _ -> k != b }; val margin = tot - (others.maxOrNull() ?: 0.0)
            out[groups[a]] = Guess(avail[b], tot, tot >= 1.1 && margin >= 0.3)
        }
        return out
    }

    private fun nNopic(items: List<SceneItem>, a: Int, b: Int) = items.subList(a, b).count { it.nopic }

    private fun guessWindow(sections: List<Pair<Int, String>>, N: Int, K: Int): Pair<Int, Int> {
        val bounds = sections.map { it.first } + N
        for (i in 0 until bounds.size - 1) if (bounds[i + 1] - bounds[i] == K) return Pair(bounds[i], bounds[i + 1])
        return Pair(0, K)
    }
    fun sectionNames(sections: List<Pair<Int, String>>, a: Int, b: Int): String {
        if (sections.isEmpty()) return ""
        val bounds = sections.map { it.first } + 1_000_000_000
        return sections.zip(bounds.drop(1)).filter { (s, nxt) -> s.first < b && nxt > a }.joinToString(", ") { it.first.second }
    }

    // ------------------------------------------------------------ choose the picture for each scene
    private fun assignImages(matchIn: String, items: List<SceneItem>, usable: List<String>, a: Int, b: Int, scenes: List<Scene>, warnings: MutableList<String>,
                             mtime: (String) -> Long): List<String?> {
        val n = b - a; var chosen: List<String?> = usable.take(n)
        var match = if (matchIn == "content") "name" else matchIn
        if (match == "number") {
            val res = numberAssign(items, usable, a, b)
            if (res == null) { warnings.add("The picture file names carry no usable scene numbers, so the words in the names were used instead."); match = "name" }
            else {
                val (got, note) = res
                val missing = got.indices.filter { got[it] == null }
                val conf = got.map { if (it != null) "" else "?" }.toMutableList()
                if (missing.isNotEmpty()) {
                    val left = usable.filter { it !in got && imageNumber(it) == null }.sortedWith(NATURAL)
                    val filled = ArrayList<Int>()
                    for ((i, p) in missing.zip(left)) { got[i] = p; filled.add(i) }
                    if (filled.isNotEmpty()) {
                        warnings.add("No picture carried the number of scene(s) " + filled.take(30).joinToString(", ") { scenes[a + it].num.toString() } +
                            ": they got leftover un-numbered pictures in sorted order - please check them.")
                        filled.forEach { conf[it] = "?" }
                    }
                }
                scenes.subList(a, b).zip(conf).forEach { (s, c) -> s.conf = c }
                warnings.add(note); return got
            }
        }
        if (match == "name") {
            val res = numberAssign(items, usable, a, b)
            if (res != null && res.first.all { it != null } && numberMap(usable).size == usable.size) {
                scenes.subList(a, b).forEach { it.conf = "" }
                warnings.add("Pictures matched by the complete number sequence in their file names: $n scenes."); return res.first
            }
            val r2 = nameAssign(items, usable, a, b)
            if (r2 == null) {
                warnings.add("The picture file names carry no usable numbers or scene words, so the pictures were taken in sorted order " +
                    "(NOT matched to the scenes). Number the files (Image 1, Image 2 ...) or name the folder like its section.")
                return chosen
            }
            val (got, conf, note) = r2
            scenes.subList(a, b).zip(conf).forEach { (s, c) -> s.conf = c }
            warnings.add(note)
            val weak = conf.withIndex().filter { it.value == "?" }.map { scenes[a + it.index].num }
            if (weak.isNotEmpty()) warnings.add("Please double-check these scenes (name words not clear): " + weak.take(30).joinToString(", "))
            return got
        }
        if (match == "time") {
            chosen = usable.sortedWith(compareBy<String> { mtime(it) }.thenComparator { x, y -> naturalCompare(x, y) }).take(n)
            warnings.add("Images were ordered by file time (the order they were created). Check the list: if the pictures do not " +
                "match their scenes, file times are not reliable for this set.")
        }
        return chosen
    }

    // ------------------------------------------------------------ which part of the script does each folder belong to
    private fun placeGroups(groups: List<Group>, items: List<SceneItem>, sections: List<Pair<Int, String>>, scenes: List<Scene>, match: String,
                            warnings: MutableList<String>, mtime: (String) -> Long): List<Group> {
        val N = scenes.size; val bounds = sections.map { it.first } + N; val one = groups.size == 1
        val decided = ArrayList<Group>()
        val named = groups.associateWith { matchGroupToSection(it.name, sections) }
        val taken = named.values.mapNotNull { it.first }.toSet()
        val inf = inferGroups(groups.filter { named[it]!!.first == null }, items, sections, bounds, taken)

        fun put(g: Group, idx: Int, sure: Boolean) {
            val K = g.images.size; val lo = bounds[idx]; val hi = bounds[idx + 1]; g.sure = sure
            if (K >= hi - lo - nNopic(items, lo, hi)) g.window = Pair(lo, hi)
            else if (numberAssign(items, g.images, lo, hi) != null) g.window = Pair(lo, hi)
            else { val w = windowByNames(match, items, g.images, lo, hi)
                if (w != null) g.window = w else {
                    g.window = Pair(lo, lo + K)
                    warnings.add("Folder '${g.name}' has $K pictures but its section has ${hi - lo} scenes: the video covers scenes ${lo + 1}-${lo + K} only.")
                } }
        }
        for (g in groups) {
            val K = g.images.size; var (idx, byName) = named[g]!!; var guess = inf[g]
            if (one && idx == null && guess != null && K > (0 until bounds.size - 1).maxOf { bounds[it + 1] - bounds[it] }) guess = null   // ONE folder bigger than any section covers the whole script
            if (idx == null && guess != null && guess.sure) idx = guess.idx
            else if (byName && idx == null) { g.skipped = "its name points to a section that is not in your script"; continue }
            if (idx != null) put(g, idx, true)
            else if (one && K >= N - nNopic(items, 0, N)) { g.window = Pair(0, N); g.sure = true }
            else if (one && numberAssign(items, g.images, 0, N) != null) { g.window = Pair(0, N); g.sure = true }
            else { val w = windowByNames(match, items, g.images, 0, N)
                if (w != null) { g.window = w; g.sure = true }
                else if (guess != null) {
                    put(g, guess.idx, false)
                    warnings.add("Folder '${g.name}' was placed by a best guess (picture count / file-name words / folder order): ${sections[guess.idx].second}. Please check it.")
                } else if (one) { g.window = guessWindow(sections, N, K); g.sure = false }
                else { g.skipped = "its picture count, file-name words and name do not point to any part of the script. " +
                    "Rename the folder with a word from its section title (e.g. 'Section 3')"; continue } }
            decided.add(g)
        }
        decided.sortWith(compareBy<Group> { it.window.first }.thenBy { it.window.second })
        val kept = ArrayList<Group>()
        for (g0 in decided) {
            var g: Group? = g0
            while (g != null && kept.isNotEmpty() && g.window.first < kept.last().window.second) { g.skipped = "it overlaps the scenes of '${kept.last().name}'"; g = null }
            if (g != null) kept.add(g)
        }
        for (g in kept) {
            val (a, b) = g.window
            val chosen: List<String?> = if (items[a].nopic && b - a > 1) listOf<String?>(null) + assignImages(match, items, g.images, a + 1, b, scenes, warnings, mtime)
                else assignImages(match, items, g.images, a, b, scenes, warnings, mtime)
            g.scenes = scenes.subList(a, b)
            g.scenes.zip(chosen).forEach { (s, img) -> s.image = img }
            g.extras = g.images.filter { it !in chosen }
            g.scenes.filter { it.image == null }.forEach { it.missing = true }
            val gone = g.scenes.filter { it.image == null && it.item?.heading != true }
            if (gone.isNotEmpty()) warnings.add("MISSING PICTURES: ${gone.size} scene(s) have no picture and show a blank screen: " + gone.take(40).joinToString(", ") { it.num.toString() } +
                (if (gone.size > 40) " ..." else "") + ". Generate them, then use Replace on those scenes.")
        }
        return kept
    }

    class MatchResult(val scenes: List<Scene>, val groups: List<Group>, val used: List<Group>, val sections: List<Pair<Int, String>>, val full: Boolean, val warnings: List<String>)

    /** The picture-matching part of analyse(): scenes (already timed) + image folders -> every scene gets its picture.
     *  match = "number" (default in the app), "name" or "order"; blank = pictures judged nearly blank by the picture layer (ignored). */
    fun matchPictures(items: List<SceneItem>, scenes: List<Scene>, groups: List<Group>, scriptText: String, match: String = "number",
                      blank: List<String> = emptyList(), warnings0: List<String> = emptyList(), mtime: (String) -> Long = { 0L }): MatchResult {
        val warnings = ArrayList(warnings0)
        val sections = findSections(items, scriptText); val N = scenes.size
        val used = placeGroups(groups, items, sections, scenes, match, warnings, mtime)
        if (used.isEmpty()) throw PipelineError("None of the image folders could be used for this script. " + groups.filter { it.skipped.isNotEmpty() }.joinToString("; ") { "'${it.name}': ${it.skipped}" })
        val sorted = used.sortedWith(compareBy<Group> { it.window.first }.thenBy { it.window.second })
        val chosen = sorted.flatMap { it.scenes }
        val full = sorted.first().window.first == 0 && sorted.last().window.second == N && sorted.zipWithNext().all { (x, y) -> x.window.second == y.window.first }
        if (full) setOutStarts(chosen) else sorted.forEach { setOutStarts(it.scenes) }
        if (groups.size > 1 || !full) {
            val parts = sorted.joinToString("; ") { g -> "'${g.name}' -> scenes ${g.window.first + 1}-${g.window.second}" + sectionNames(sections, g.window.first, g.window.second).let { if (it.isNotEmpty()) " ($it)" else "" } }
            warnings.add(0, "${sorted.size} image folder(s) used for a script of $N scenes: $parts. " +
                if (full) "Together they cover the whole script, so you get ONE full video." else (if (sorted.size > 1) "You get one video per folder." else "The rest of the narration is cut out."))
        }
        groups.filter { it.skipped.isNotEmpty() }.forEach { warnings.add("Folder '${it.name}' is not used: ${it.skipped}.") }
        if (!sorted.all { it.sure }) warnings.add("Not completely sure which part some pictures belong to - check the list" + (if (groups.size == 1) ", or use 'Other part...'." else ", or name the folders like the section (Intro, Section 3 ...)."))
        val extras = sorted.flatMap { it.extras }
        if (extras.isNotEmpty()) warnings.add("${extras.size} extra image(s) are not used.")
        if (blank.isNotEmpty()) warnings.add("Ignored ${blank.size} nearly blank image(s) (reference sheets): " + blank.take(5).joinToString(", ") { nameOf(it) })
        val short = chosen.filter { it.end - it.start < 0.6 }.map { it.num }
        if (short.isNotEmpty()) warnings.add("Very short scenes (under 0.6 s): " + short.take(15).joinToString(", "))
        return MatchResult(chosen, groups, sorted, sections, full, warnings.distinct())
    }

    private fun setOutStarts(scenes: List<Scene>) { var t = 0.0; for (s in scenes) { s.outStart = t; t += max(s.end - s.start, 0.04) } }
}
