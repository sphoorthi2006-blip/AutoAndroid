package com.auto.core

/** Tiny dependency-free JSON reader (objects -> Map, arrays -> List, numbers -> Double), so the core runs on plain JVM and Android. */
class MiniJson(private val s: String) {
    private var i = 0
    fun parse(): Any? { ws(); val v = value(); ws(); if (i != s.length) fail("extra text"); return v }
    private fun fail(m: String): Nothing = throw IllegalArgumentException("JSON: $m at $i")
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun value(): Any? {
        if (i >= s.length) fail("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj(); '[' -> arr(); '"' -> str()
            't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
        }
    }
    private fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) fail("bad literal"); i += w.length; return v }
    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(st, i).toDoubleOrNull() ?: fail("bad number")
    }
    private fun str(): String {
        i++; val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (val e = s[i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            } else sb.append(s[i])
            i++
        }
        if (i >= s.length) fail("unterminated string")
        i++; return sb.toString()
    }
    private fun arr(): List<Any?> {
        i++; val out = ArrayList<Any?>(); ws()
        if (s.getOrNull(i) == ']') { i++; return out }
        while (true) { ws(); out.add(value()); ws(); when (s.getOrNull(i)) { ',' -> i++; ']' -> { i++; return out }; else -> fail("expected , or ]") } }
    }
    private fun obj(): Map<String, Any?> {
        i++; val out = LinkedHashMap<String, Any?>(); ws()
        if (s.getOrNull(i) == '}') { i++; return out }
        while (true) {
            ws(); if (s.getOrNull(i) != '"') fail("expected key"); val k = str(); ws()
            if (s.getOrNull(i) != ':') fail("expected :"); i++; ws(); out[k] = value(); ws()
            when (s.getOrNull(i)) { ',' -> i++; '}' -> { i++; return out }; else -> fail("expected , or }") }
        }
    }
}
