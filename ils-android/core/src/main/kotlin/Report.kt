package ils.core

/** Human-readable report: the check lines, then one line per slide. Used by the command-line runner and the Android app. */
fun SlideMap.toReport(r: SlideMapResult): String {
    val sb = StringBuilder()
    r.report.forEach { sb.appendLine(it) }
    sb.appendLine()
    for (s in r.slides) sb.appendLine(
        "%2d  %s -> %s  %5.1fs  %-10s %s".format(
            s.image, Srt.time(s.start), Srt.time(s.end), s.duration,
            s.kind.label + (if (s.parts > 1) " ${s.part}/${s.parts}" else ""),
            (s.heading ?: s.sentences.first().text).take(60)
        )
    )
    return sb.toString()
}
