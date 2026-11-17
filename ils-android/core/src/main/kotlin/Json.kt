package ils.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Tiny dependency-free JSON reader/writer, so the core module runs the same on a PC and on Android.
 * Values: null, Boolean, Double, String, List<Any?>, Map<String, Any?>.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) p.fail("Extra text after the JSON value")
        return v
    }

    fun stringify(v: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, v, if (pretty) 0 else -1)
        return sb.toString()
    }

    private fun num(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return BigDecimal(d).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private fun nl(sb: StringBuilder, ind: Int) {
        if (ind < 0) return
        sb.append('\n')
        repeat(ind) { sb.append("  ") }
    }

    private fun write(sb: StringBuilder, v: Any?, ind: Int) {
        val next = if (ind < 0) -1 else ind + 1
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Int -> sb.append(v.toString())
            is Long -> sb.append(v.toString())
            is Number -> sb.append(num(v.toDouble()))
            is String -> str(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(sb, next)
                    str(sb, k.toString()); sb.append(if (ind < 0) ":" else ": ")
                    write(sb, x, next)
                }
                nl(sb, ind)
                sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    nl(sb, next)
                    write(sb, x, next)
                }
                nl(sb, ind)
                sb.append(']')
            }
            else -> str(sb, v.toString())
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON error at character $i: $msg")
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) fail("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("Unexpected '$c'")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("Expected $w")
            i += w.length
            return v
        }
        fun number(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: fail("Bad number")
        }
        fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value()); ws()
                if (i >= s.length) fail("Unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                fail("Expected , or ]")
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("Expected a key")
                val k = string(); ws()
                if (i >= s.length || s[i] != ':') fail("Expected :")
                i++
                out[k] = value(); ws()
                if (i >= s.length) fail("Unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                fail("Expected , or }")
            }
        }
    }
}
