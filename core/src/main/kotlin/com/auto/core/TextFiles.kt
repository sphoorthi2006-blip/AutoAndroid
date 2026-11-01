package com.auto.core

import java.io.File
import java.util.zip.ZipFile

/** Reads script / details / caption files. .docx is unpacked with java.util.zip only (no library), everything else is plain text. */
object TextFiles {
    private val PARA = Regex("<w:p[ >/][\\s\\S]*?(?:</w:p>|(?=<w:p[ >/]))")
    private val TOKEN = Regex("<w:t(?:\\s[^>]*)?>([\\s\\S]*?)</w:t>|<w:tab\\s*/>|<w:br\\s*/>|<w:cr\\s*/>")
    private val MAX_XML = 64L * 1024 * 1024

    fun isDocx(name: String) = name.lowercase().endsWith(".docx")

    fun read(f: File): String = if (isDocx(f.name)) docxText(f) else f.readText()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }.replace("&amp;", "&")

    /** One line per paragraph, in document order. Tables are read cell by cell (each cell paragraph = one line). */
    fun docxText(f: File): String {
        val xml = try {
            ZipFile(f).use { z ->
                val e = z.getEntry("word/document.xml") ?: throw PipelineError("'${f.name}' is not a Word document (no word/document.xml).")
                if (e.size > MAX_XML) throw PipelineError("'${f.name}' is too large to read as text.")
                z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: PipelineError) { throw e } catch (e: Exception) { throw PipelineError("Could not open '${f.name}' as a Word document: ${e.message}") }
        val lines = PARA.findAll(xml).map { p ->
            val sb = StringBuilder()
            for (m in TOKEN.findAll(p.value)) when {
                m.value.startsWith("<w:tab") -> sb.append('\t')
                m.value.startsWith("<w:br") || m.value.startsWith("<w:cr") -> sb.append('\n')
                else -> sb.append(unescape(m.groupValues[1]))
            }
            sb.toString()
        }.toList()
        if (lines.none { it.isNotBlank() }) throw PipelineError("'${f.name}' contains no text.")
        return lines.joinToString("\n")
    }
}
