package ils.core

import java.io.File

/** Command line test runner:  Main <script.txt> <raw.srt> <outDir> [splitAfterSec]  */
fun main(args: Array<String>) {
    if (args.size < 3) { println("Usage: <script.txt> <raw.srt> <outDir> [splitAfterSec]"); return }
    val st = if (args.size > 3) SlideMapSettings(splitAfterSec = args[3].toDouble()) else SlideMapSettings()
    val r = SlideMap.build(File(args[0]).readText(), File(args[1]).readText(), st)
    val out = File(args[2]); out.mkdirs()
    File(out, "slides.json").writeText(SlideMap.toJson(r))
    File(out, "slides.srt").writeText(SlideMap.toSrt(r))
    val report = SlideMap.toReport(r)
    File(out, "report.txt").writeText(report)
    println(report)
}
