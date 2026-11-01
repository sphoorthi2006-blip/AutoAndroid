import com.auto.core.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)
fun main() {
    val bytes = File("/tmp/tt2/audio.f32").readBytes()
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); val x = FloatArray(fb.remaining()); fb.get(x)
    val words = Captions.parse(File("/tmp/tt2/caps.srt").readText(), "caps.srt")
    val scr = File("/tmp/tt2/script.txt").readText()
    val warn = mutableListOf<String>()
    val items = scopedItems(Details.parseSceneFile(File("/tmp/tt2/details.txt").readText()), scr, warn)
    val lines = items.map { it.spoken }
    val heads = items.withIndex().filter { (k, it) -> k > 0 && it.section.isNotEmpty() && it.section != items[k - 1].section }.associate { it.index to it.value.section }
    fun show(tag: String, sc: List<Scene>) { println("## $tag"); sc.forEach { println("${f3(it.start)}|${f3(it.end)}") } }
    val (sc, rep) = Align.align(words, lines, scr, x, heads); show("with-audio", sc)
    println("rep match=${f3(rep.match)} words=${rep.words} moved=${rep.cutsMoved} unchecked=${rep.cutsUnchecked} flagged=${rep.flagged}")
    val res = AudioPauses.anchorCuts(x, sc.map { it.text }, sc.map { it.start })
    println("##anchor " + (res?.let { r -> r.first.joinToString(", ") { f3(it) } + " | " + r.second } ?: "None"))
    val (sc2, _) = Align.align(words, lines, scr, null, heads); show("captions-only", sc2)
    println("##estimate"); Align.estimate(lines, scr).forEach { println("${f3(it.start)}|${f3(it.end)}") }
    println("##heads $heads warn $warn")
    // full pipeline
    val t = timeScenes(items, scr, words, x, x.size / 16000.0)
    println("##pipeline dur=${f3(t.duration)} anchored=${t.report?.anchored} warnings=${t.warnings}")
    t.scenes.forEach { println("${it.num} ${f3(it.start)}-${f3(it.end)} ${if (it.cutCheck) "!" else ""} ${it.text.take(30)}") }
}
