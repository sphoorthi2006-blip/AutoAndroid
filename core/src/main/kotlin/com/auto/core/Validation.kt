package com.auto.core

import java.io.File
import java.security.MessageDigest

/** Extra file-level checks on the pictures. Nothing here stops a run: everything is a warning (the analysis already lists missing / extra / unnumbered / damaged pictures). */
class ValidationReport(val warnings: List<String>, val duplicateImages: List<List<String>>)

object Validation {
    fun sha256(path: String): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 16)
        File(path).inputStream().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Groups of different files with identical bytes. Files are only hashed when another file has the same size. */
    fun duplicates(paths: List<String>, hash: (String) -> String = ::sha256, size: (String) -> Long = { File(it).length() }): List<List<String>> {
        val bySize = paths.groupBy(size).values.filter { it.size > 1 }
        return bySize.flatMap { g -> g.groupBy(hash).values.filter { it.size > 1 } }.map { it.sortedWith { a, b -> Pics.naturalCompare(a, b) } }
    }

    /** Numbers absent from the run of picture numbers (e.g. 001,002,004 -> [3]). Pictures without a number are ignored. */
    fun missingNumbers(paths: List<String>): List<Int> {
        val nums = paths.mapNotNull { Pics.imageNumber(it) }.toSortedSet()
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

    /** Pictures used on two scenes that are really the same file (the generator made a copy): a warning only. */
    fun check(a: Analysis, hash: (String) -> String = ::sha256): ValidationReport {
        val dup = try { duplicates(a.scenes.mapNotNull { it.image }, hash) } catch (e: Exception) { emptyList() }
        val w = if (dup.isEmpty()) emptyList() else listOf("Duplicate pictures (identical files): " + dup.take(8).joinToString("; ") { g -> g.joinToString(" = ") { Pics.nameOf(it) } })
        return ValidationReport(w, dup)
    }
}
