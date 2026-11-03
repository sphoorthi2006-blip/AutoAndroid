import com.auto.core.*
import java.io.File

fun main() {
    fun f(x: Double) = String.format(java.util.Locale.ROOT, "%.3f", x)
    for (n in listOf("a.srt", "b.vtt", "c.txt", "d.txt", "e.txt", "f.json", "g.json")) {
        val file = File("/tmp/tt/$n")
        val w = Captions.parse(file.readText(), n)
        println("## $n ${w.size}")
        w.forEach { println("${it.text}|${f(it.start)}|${f(it.end)}") }
        println("##cues $n")
        Captions.parseCues(w).forEach { println("${f(it.start)}|${f(it.end)}|${it.text}") }
    }
    val items = Details.parseSceneFile(File("/tmp/tt/details.txt").readText())
    println("##details ${items.size}")
    items.forEach { println(listOf(it.num?.toString() ?: "None", it.spoken, it.visual, it.labels, it.emotion, it.section).joinToString("|")) }
    println("##script ${Details.parseSceneFile(File("/tmp/tt/script.txt").readText()).size}")
    for (n in listOf("details.txt", "script.txt", "a.srt", "c.txt", "d.txt", "e.txt")) {
        val e = Entry(n, File("/tmp/tt/$n").length()) { File("/tmp/tt/$n").readText() }
        println("##role $n ${Bundle.textRole(e)}")
    }
}
