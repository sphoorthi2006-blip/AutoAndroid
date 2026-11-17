package ils.core

/** PC-only check of the element stage on a synthetic image. Run: java -cp <classes> ils.core.ElementsTest */
object ElementsTest {
    private const val W = 1280
    private const val H = 720
    private val BG = 0xFFFAF5E6.toInt()

    private fun rect(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        for (y in y0..y1) for (x in x0..x1) px[y * W + x] = c
    }

    private fun disc(px: IntArray, cx: Int, cy: Int, r: Int, c: Int) {
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * W + x] = c
    }

    /** Letter-like blocks (an L shape each) so the pen has something to follow. */
    private fun letters(px: IntArray, x0: Int, y0: Int, n: Int) {
        for (i in 0 until n) {
            val x = x0 + i * 26
            rect(px, x, y0, x + 4, y0 + 22, 0xFF222222.toInt())
            rect(px, x, y0 + 18, x + 14, y0 + 22, 0xFF222222.toInt())
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val px = IntArray(W * H) { BG }
        disc(px, 220, 200, 60, 0xFFD2691E.toInt())      // icon 1 (row 1, col 1)
        letters(px, 170, 290, 4)                         // its label, below
        rect(px, 700, 150, 800, 250, 0xFF2E8B57.toInt()) // icon 2 (row 1, col 2)
        letters(px, 300, 500, 8)                         // text element (row 2, col 1)

        val det = Elements.detect(px, W, H)
        println("bg=%08X elements=%d".format(det.bg, det.elements.size))
        for (e in det.elements) println("  box ${e.x0},${e.y0} ${e.w}x${e.h} row ${e.row} col ${e.col} fine=${e.fine.size}")
        check(det.elements.size == 3) { "expected 3 elements" }
        check(det.elements.map { it.row to it.col }.toSet() == setOf(1 to 1, 1 to 2, 2 to 1)) { "slots" }

        val e1 = det.elements.first { it.row == 1 && it.col == 1 }
        val sp = Elements.split(e1, false)
        println("icon1 split: body=${sp.body != null} label=${sp.label != null} letters=${sp.letters}")
        check(sp.body != null && sp.label != null && sp.letters == 4) { "icon + label split" }

        val script = "Baking soda helps your skin feel soft. It also cleans teeth and freshens breath in a simple way."
        val srt = "1\n00:00:00,000 --> 00:00:04,000\nBaking soda helps your skin feel soft.\n\n2\n00:00:04,000 --> 00:00:09,000\nIt also cleans teeth and freshens breath in a simple way.\n"
        val r = SlideMap.build(script, srt)
        val slide = r.slides.first()
        println("slide ${slide.image} ${slide.start}-${slide.end} words=${slide.sentences.sumOf { it.words.size }}")
        check(slide.sentences.all { it.words.isNotEmpty() }) { "words on sentences" }

        val specs = ElementList.parse("""
            {"slides":[{"image":1,"elements":[
              {"label":"jar","type":"icon","sentence":0,"trigger":"baking soda","row":1,"col":1},
              {"label":"leaf","type":"icon","sentence":0,"trigger":"skin","slot":{"row":1,"col":2}},
              {"label":"Soft","type":"text","sentence":1,"trigger":"teeth","row":2,"col":1}
            ]}]}""")[1]!!
        check(specs.size == 3 && specs[1].col == 2 && specs[2].isText) { "element list parse" }

        val plan = Choreography.plan(slide, px, W, H, specs)
        plan.warnings.forEach { println("  warning: $it") }
        check(plan.placed.size == 3) { "3 placed" }
        for (p in plan.placed) println("  ${p.spec?.label}: start %.2f end %.2f body %.2f-%.2f label %.2f-%.2f".format(p.start, p.end, p.bodyStart, p.bodyEnd, p.labelStart, p.labelEnd))
        for (i in 0 until plan.placed.size - 1) check(plan.placed[i].end <= plan.placed[i + 1].start) { "ends before next starts" }
        check(plan.placed.all { it.start >= slide.start && it.end <= slide.end }) { "inside slide" }
        // the third element starts at "teeth" (sentence 1), after the slide's first sentence
        check(plan.placed[2].start > plan.placed[1].start) { "narration order" }

        // reveal states
        val s0 = plan.state(slide.start); val sEnd = plan.state(slide.end - 0.01)
        check(s0.all { it == 0 }) { "nothing drawn at the start" }
        check(sEnd.withIndex().all { (i, v) -> v == 1000 || plan.placed[i / 2].let { p -> (i % 2 == 0 && !p.hasBody) || (i % 2 == 1 && !p.hasLabel) } }) { "everything drawn at the end" }

        // pen plan of the label
        val wp = plan.placed[2].writePlan!!
        val mid = wp.frame(0.5)
        println("  text pen strokes=${wp.strokes.size}, at 50%: ${mid.lines.size} lines")
        check(wp.strokes.isNotEmpty() && mid.lines.isNotEmpty()) { "pen plan" }

        // heading image without list: written as handwriting
        val hs = slide.copy(kind = Kind.HEADING)
        val hp = Choreography.plan(hs, px, W, H, emptyList())
        check(hp.placed.size == 1 && hp.placed[0].hasLabel && !hp.placed[0].hasBody) { "heading plan" }
        // body image without list: static
        check(Choreography.plan(slide, px, W, H, emptyList()).placed.isEmpty()) { "static body image" }

        // wrong slot falls back to nearest
        val bad = specs.map { it.copy(row = 5, col = 5) }
        val bp = Choreography.plan(slide, px, W, H, bad)
        check(bp.placed.size == 3 && bp.warnings.isNotEmpty()) { "slot fallback" }
        println("ALL OK")
    }
}
