import com.auto.core.*
import java.io.File

/** v19 parity: headings as scenes, Prompt blocks, black screens, folder-name variants. Usage: Check6Kt <testdata/v19 dir> */
fun main(args: Array<String>) {
    for (sd in File(args[0]).listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
        val det = File(sd, "details.txt").readText(); val scr = File(sd, "script.txt").readText(); val match = File(sd, "match.txt").readText().trim()
        val rows = File(sd, "pics.tsv").readLines().filter { it.isNotBlank() }.map { it.split("\t") }
        val refs = rows.map { PicRef(it[0], it[2].split("/"), it[0] + "/" + it[2]) }
        val groups = Matching.imageGroups(refs, rows.associate { it[0] to it[1] })
        val warns = ArrayList<String>()
        var items = scopedItems(Details.parseSceneFile(det), scr, warns)
        val sp = File(sd, "spoken.txt")
        val wt = if (sp.exists()) sp.readText().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { Details.tokens(it).joinToString("") }.filter { it.isNotEmpty() } else null
        items = Matching.addHeadingItems(items, scr, groups, warns, wt)
        val scenes = Align.estimate(items.map { it.spoken }, scr)
        scenes.forEachIndexed { k, s -> s.item = items[k]; s.num = k + 1 }
        val sections = Matching.findSections(items, scr)
        println("## ${sd.name} $match sections ${sections.joinToString(", ", "[", "]") { "(${it.first}, '${it.second}')" }}")
        try {
            val r = Matching.matchPictures(items, scenes, groups, scr, match, warnings0 = warns)
            val skip = listOf(Regex("^\\d+ image folder"), Regex("^Folder '.*' is not used"), Regex("^Not completely sure"), Regex("^\\d+ extra image"), Regex("^Very short"), Regex("^Ignored"))
            r.warnings.filter { w -> skip.none { it.containsMatchIn(w) } }.forEach { println("W $it") }
        } catch (e: PipelineError) { println("ERR ${e.message}") }
        for (g in groups) println("G ${g.name}|${g.window.first}-${g.window.second}|${g.sure.toString().replaceFirstChar { it.uppercase() }}|${g.skipped}|${g.extras.size}")
        for (s in scenes) println("S ${s.num}|${s.item?.num}|${s.text.take(30)}|${s.image?.let { Matching.nameOf(it) }}|${s.conf}|${s.missing.toString().replaceFirstChar { it.uppercase() }}|${(s.item?.heading == true).toString().replaceFirstChar { it.uppercase() }}")
    }
}
