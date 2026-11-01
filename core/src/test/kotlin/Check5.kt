import com.auto.core.*
import java.io.File
import java.util.zip.*

fun main() {
    val tmp = File("/tmp/tt5").apply { deleteRecursively(); mkdirs() }
    val zf = File(tmp, "Pictures.zip")
    ZipOutputStream(zf.outputStream()).use { z ->
        for ((n, i) in listOf("Intro" to 1, "Intro" to 2, "Intro" to 3, "Section 2" to 4, "Section 2" to 5, "Section 2" to 6, "Section 2" to 7, "Section 3" to 8, "Section 3" to 9, "Section 3" to 10)) {
            z.putNextEntry(ZipEntry("$n/image_$i.png")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry() }
        z.putNextEntry(ZipEntry("__MACOSX/Intro/._image_1.png")); z.write(1); z.closeEntry()
        z.putNextEntry(ZipEntry("../evil.png")); z.write(1); z.closeEntry()
    }
    val a = Pipeline.analyse(File("/tmp/tt2/details.txt").readText(), File("/tmp/tt2/script.txt").readText(), null, null, 0.0, listOf(zf), File(tmp, "work"))
    println("scenes=${a.scenes.size} full=${a.match.full} evil=${File(tmp, "evil.png").exists() || File("/tmp/tt5/work/evil.png").exists()}")
    a.scenes.forEach { println("${it.num} ${"%.2f".format(it.outStart)} ${it.image?.let { p -> Matching.nameOf(p) }}") }
    a.warnings.forEach { println("W $it") }
}
