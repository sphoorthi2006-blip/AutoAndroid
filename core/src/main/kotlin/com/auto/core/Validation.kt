package com.auto.core

import java.io.File
import java.security.MessageDigest

/** What the review screen shows before/after analysis. errors = will give a wrong or incomplete video; warnings = please look. */
class ValidationReport(
    val errors: List<String>, val warnings: List<String>,
    val duplicateImages: List<List<String>>, val missingNumbers: List<Int>,
    val unusedImages: List<String>, val scenesWithoutPicture: List<Int>, val unreadableImages: List<String>,
) { val ok get() = errors.isEmpty() }

object Validation {
    fun sha256(path: String): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 16)
        File(path).inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Groups of different files with identical bytes. Files are only hashed when another file has the same size. */
    fun duplicates(paths: List<String>, hash: (String) -> String = ::sha256, size: (String) -> Long = { File(it).length() }): List<List<String>> {
        val bySize = paths.groupBy(size).values.filter { it.size > 1 }
        return bySize.flatMap { g -> g.groupBy(hash).values.filter { it.size > 1 } }.map { it.sortedWith { a, b -> Matching.naturalCompare(a, b) } }
    }

    /** Numbers absent from the run of picture numbers (e.g. 001,002,004 -> [3]). Pictures without a number are ignored. */
    fun missingNumbers(paths: List<String>): List<Int> {
        val nums = paths.mapNotNull { Matching.imageNumber(it) }.toSortedSet()
        if (nums.size < 2) return emptyList()
        return (nums.first()..nums.last()).filter { it !in nums }
    }

    /** Pictures that cannot be decoded as images at all (checked by magic bytes: PNG, JPEG, WebP, BMP). */
    fun unreadable(paths: List<String>): List<String> = paths.filter { p ->
        val b = try { File(p).inputStream().use { i -> ByteArray(12).also { i.read(it) } } } catch (e: Exception) { return@filter true }
        val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
        val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
        val webp = String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        val bmp = b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()
        !(png || jpg || webp || bmp)
    }

    fun check(a: Pipeline.Analysis, hash: (String) -> String = ::sha256): ValidationReport {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        val all = a.match.groups.flatMap { it.images }
        val dup = duplicates(all, hash)
        if (dup.isNotEmpty()) warnings.add("Duplicate pictures (identical files): " + dup.take(8).joinToString("; ") { g -> g.joinToString(" = ") { Matching.nameOf(it) } })
        val miss = a.match.used.flatMap { g -> missingNumbers(g.images).map { it } }.distinct().sorted()
        if (miss.isNotEmpty()) warnings.add("Picture numbers missing from the sequence: " + miss.take(30).joinToString(", ") + if (miss.size > 30) " ..." else "")
        val bad = unreadable(all)
        if (bad.isNotEmpty()) errors.add("Not valid image files: " + bad.take(8).joinToString(", ") { Matching.nameOf(it) })
        val unused = a.match.used.flatMap { it.extras } + a.match.groups.filter { it.skipped.isNotEmpty() }.flatMap { it.images }
        if (unused.isNotEmpty()) warnings.add("${unused.size} picture(s) are not assigned to any scene: " + unused.take(8).joinToString(", ") { Matching.nameOf(it) })
        val noPic = a.scenes.filter { it.image == null && it.item?.heading != true }.map { it.num }
        if (noPic.isNotEmpty()) errors.add("Scenes without a picture: " + noPic.take(30).joinToString(", ") + if (noPic.size > 30) " ..." else "")
        val unsure = a.scenes.filter { it.conf == "?" }.map { it.num }
        if (unsure.isNotEmpty()) warnings.add("Picture match not certain for scene(s): " + unsure.take(30).joinToString(", "))
        if (a.match.sections.isNotEmpty() && !a.match.full) warnings.add("The pictures do not cover the whole script; the rest of the narration is not in the video.")
        return ValidationReport(errors, warnings, dup, miss, unused, noPic, bad)
    }
}
