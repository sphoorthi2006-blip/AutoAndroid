import com.auto.core.*
import java.io.File
fun e(n: String, dur: Double? = null) = Entry(n, File("/tmp/tt/$n").takeIf { it.exists() }?.length() ?: 1000L, dur) { File("/tmp/tt/$n").takeIf { it.exists() }?.readText() }
fun main() {
    val r = Bundle.classify(listOf(e("voice.mp3", 312.0), e("click.mp3", 0.9), e("details.txt"), e("script.txt"), e("a.srt"), e("c.txt"), e("f.json"), e("x.wav", 20.0)), nPictures = 42)
    println("audio=${r.audio?.name} click=${r.click?.name} details=${r.details?.name} script=${r.script?.name} ts=${r.timestamps?.name}")
    r.notes.forEach { println(" - $it") }
    val r2 = Bundle.classify(listOf(e("only.srt"), e("details.txt")), nPictures = 0)
    println("captions-only: audio=${r2.audio} ts=${r2.timestamps?.name}"); r2.notes.forEach { println(" - $it") }
}
