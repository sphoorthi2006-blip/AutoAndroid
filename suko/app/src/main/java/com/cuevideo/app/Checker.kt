package com.cuevideo.app

data class ImageEntry(val name: String, val uri: String)
data class ClipEntry(val name: String, val uri: String)

/** One line of the "53.mp4 -> cue 53" confirmation list. */
data class ClipRow(
    val clip: ClipEntry,
    val first: Int?,
    val last: Int?,
    val corrected: Boolean,
) {
    val label: String
        get() = when {
            first == null || last == null -> "${clip.name} -> no number found"
            first == last -> "${clip.name} -> cue $first"
            else -> "${clip.name} -> cues $first to $last"
        }
}

data class CheckResult(
    val cueCount: Int,
    val clipRows: List<ClipRow>,
    val errors: List<String>,
    val warnings: List<String>,
    val covered: Set<Int>,                 // 1-based cue numbers shown from a clip
    val missing: List<Int>,                // cues that need an image but have none
    val imageForCue: Map<Int, ImageEntry>, // images that will be used (or ignored, see below)
    val ignoredUnderClips: List<Int>,
    val extraImages: List<ImageEntry>,
    val headingCues: Int,
    val subscribeCues: Int,
    val texts: List<TextPlacement> = emptyList(),   // valid text placements, sorted by first cue
    val textCovered: Set<Int> = emptySet(),         // cues shown as placed text
) {
    val canExport: Boolean get() = errors.isEmpty() && missing.isEmpty()
    val missingText: String get() = "Missing images: " + missing.joinToString(", ")
}

object Checker {
    /** Images sorted like desktop natural_key: by number, then by name. */
    private fun sortedImages(images: List<ImageEntry>): List<ImageEntry> =
        images.sortedWith(
            compareBy<ImageEntry>({ CueNames.imageCue(it.name) ?: Int.MAX_VALUE }, { it.name.lowercase() })
        )

    fun clipRows(clips: List<ClipEntry>, corrections: Map<String, Pair<Int, Int>>): List<ClipRow> =
        clips.map { c ->
            val fix = corrections[c.uri]
            if (fix != null) ClipRow(c, fix.first, fix.second, true)
            else {
                val r = CueNames.clipRange(c.name)
                ClipRow(c, r?.first, r?.second, false)
            }
        }.sortedWith(compareBy({ it.first ?: Int.MAX_VALUE }, { it.last ?: Int.MAX_VALUE }))

    fun check(
        cues: List<Cue>,
        images: List<ImageEntry>,
        clips: List<ClipEntry>,
        corrections: Map<String, Pair<Int, Int>> = emptyMap(),
        texts: List<TextPlacement> = emptyList(),
    ): CheckResult {
        val n = cues.size
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val rows = clipRows(clips, corrections)

        // ---- scene clip checks (section 4) ----
        val valid = ArrayList<ClipRow>()
        for (r in rows) {
            val f = r.first
            val l = r.last
            if (f == null || l == null) {
                errors.add("${r.clip.name}: no cue number found in the name. Set it by hand.")
            } else if (f < 1 || l > n) {
                errors.add("${r.clip.name} points to cues $f-$l, but the master SRT has cues 1 to $n only.")
            } else if (l < f) {
                errors.add("${r.clip.name}: the second number must not be smaller than the first one.")
            } else {
                valid.add(r)
            }
        }
        var prev: ClipRow? = null
        val covered = HashSet<Int>()
        for (r in valid) {
            val p = prev
            if (p != null && r.first!! <= p.last!!) {
                errors.add(
                    "${p.clip.name} and ${r.clip.name} cover the same cue. " +
                        "Each cue can have only one scene clip."
                )
            }
            for (c in r.first!!..r.last!!) covered.add(c)
            prev = r
        }

        // ---- text placements: a range of cues written by hand instead of their images ----
        val validTexts = ArrayList<TextPlacement>()
        val textCovered = HashSet<Int>()
        for (t in texts.sortedWith(compareBy({ it.first }, { it.last }))) {
            val what = "Text for ${t.label}"
            if (t.first < 1 || t.last < t.first || t.last > n) {
                errors.add("$what: the range must be inside cues 1 to $n, with the first number not larger than the second.")
            } else if (t.text.isBlank()) {
                errors.add("$what: the text is empty.")
            } else {
                val clash = (t.first..t.last).firstOrNull { it in covered || it in textCovered }
                if (clash != null) {
                    val other = if (clash in covered) "a scene clip" else "another text"
                    errors.add("$what overlaps $other at cue $clash. Each cue can have only one of: scene clip, text.")
                } else {
                    validTexts.add(t)
                }
                // a bad overlap still reserves its cues, so the same cues are not also reported missing
                for (c in t.first..t.last) textCovered.add(c)
            }
        }

        // ---- images by cue number (section 3, 5) ----
        val byCue = LinkedHashMap<Int, ImageEntry>()
        val extra = ArrayList<ImageEntry>()
        val dupes = sortedSetOf<Int>()
        for (img in sortedImages(images)) {
            val num = CueNames.imageCue(img.name)
            if (num == null || num < 1 || num > n) {
                extra.add(img)
            } else if (byCue.containsKey(num)) {
                dupes.add(num)
            } else {
                byCue[num] = img
            }
        }
        val missing = (1..n).filter { it !in covered && it !in textCovered && !byCue.containsKey(it) }
        val ignored = byCue.keys.filter { it in covered }.sorted()

        if (dupes.isNotEmpty())
            warnings.add("More than one image for cue " + dupes.joinToString(", ") +
                ". Only the first (by name) is used.")
        if (ignored.isNotEmpty())
            warnings.add("Images for cues under a scene clip are ignored: " +
                ignored.joinToString(", ") + ".")
        val ignoredText = byCue.keys.filter { it in textCovered && it !in covered }.sorted()
        if (ignoredText.isNotEmpty())
            warnings.add("Images for cues under a text are ignored: " + ignoredText.joinToString(", ") + ".")
        if (extra.isNotEmpty())
            warnings.add("${extra.size} image(s) match no cue and will not be used: " +
                extra.take(8).joinToString(", ") { it.name } +
                if (extra.size > 8) ", ..." else "")

        val kinds = cues.map { CueClassifier.classify(it.text) }
        return CheckResult(
            cueCount = n,
            clipRows = rows,
            errors = errors,
            warnings = warnings,
            covered = covered,
            missing = missing,
            imageForCue = byCue,
            ignoredUnderClips = ignored,
            extraImages = extra,
            headingCues = kinds.withIndex().count { (i, k) -> k == CueKind.HEADING && (i + 1) !in covered && (i + 1) !in textCovered },
            subscribeCues = kinds.withIndex().count { (i, k) -> k == CueKind.SUBSCRIBE && (i + 1) !in covered && (i + 1) !in textCovered },
            texts = validTexts,
            textCovered = textCovered,
        )
    }
}
