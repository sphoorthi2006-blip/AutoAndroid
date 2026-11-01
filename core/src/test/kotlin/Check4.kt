import com.auto.core.*
import java.io.File

fun main() {
    val det = File("/tmp/tt2/details.txt").readText(); val scr = File("/tmp/tt2/script.txt").readText()
    val rows = File("/tmp/tt3/scen.txt").readLines().filter { it.isNotBlank() }.map { it.split("\t") }
    val byScen = LinkedHashMap<String, MutableList<List<String>>>()
    for (r in rows) byScen.getOrPut(r[0]) { mutableListOf() }.add(r)
    for ((sc, rs) in byScen) {
        val match = rs[0][1]
        val items = Details.parseSceneFile(det); val lines = items.map { it.spoken }
        val scenes = Align.estimate(lines, scr)
        scenes.forEachIndexed { k, s -> s.item = items[k]; s.num = k + 1 }
        val refs = rs.map { PicRef(it[2], it[4].split("/"), it[2] + "/" + it[4]) }
        val rootNames = rs.associate { it[2] to it[3] }
        val groups = Matching.imageGroups(refs, rootNames)
        val sections = Matching.findSections(items, scr)
        println("## $sc $match sections ${sections.joinToString(", ", "[", "]") { "(${it.first}, '${it.second}')" }}")
        try {
            val r = Matching.matchPictures(items, scenes, groups, scr, match)
            val skip = listOf(Regex("^\\d+ image folder"), Regex("^Folder '.*' is not used"), Regex("^Not completely sure"), Regex("^\\d+ extra image"), Regex("^Very short"), Regex("^Ignored"))
            r.warnings.filter { w -> skip.none { it.containsMatchIn(w) } }.forEach { println("W $it") }
        } catch (e: PipelineError) { println("ERR ${e.message}") }
        for (g in groups) println("G ${g.name}|${g.window.first}-${g.window.second}|${g.sure.toString().replaceFirstChar { it.uppercase() }}|${g.skipped}|${g.extras.size}|${g.images.take(2).map { Matching.nameOf(it) }.joinToString(", ", "[", "]") { "'$it'" }}")
        for (s in scenes) println("S ${s.num}|${s.image?.let { Matching.nameOf(it) }}|${s.conf}|${s.missing.toString().replaceFirstChar { it.uppercase() }}")
    }
}
