package ils.core

/** Word splitting and spelling-insensitive comparison, so "odour"/"odor", "pH five"/"pH 5" and "check ups"/"check-ups" still match. */
object Words {
    private val re = Regex("[A-Za-z0-9À-ɏ]+(?:['’.,][A-Za-z0-9À-ɏ]+)*")
    private val nums = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19", "twenty" to "20",
        "thirty" to "30", "forty" to "40", "fifty" to "50",
    )
    private val syn = mapOf(
        "milligrams" to "mg", "milligram" to "mg", "centre" to "center", "centres" to "centers",
        "percent" to "percent", "okay" to "ok",
    )
    private val iseRe = Regex("(?<=[a-z])is(e|es|ed|ing)$")
    private val ourRe = Regex("(?<=[a-z])our(s|ed|ing)?$")

    fun split(text: String): List<String> = re.findAll(text).map { it.value }.toList()

    /** Same normal form for both sides of the comparison (the exact form does not matter, only that it is the same). */
    fun norm(raw: String): String {
        var w = raw.lowercase().replace("’", "'")
        w = w.replace("'", "").replace(".", "").replace(",", "")
        nums[w]?.let { return it }
        w = syn[w] ?: w
        w = iseRe.replace(w, "iz\$1")
        w = ourRe.replace(w, "or\$1")
        return w
    }

    private fun lev1(a: String, b: String): Boolean {
        // true when the edit distance is at most 1
        if (Math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        if (i == a.length && i == b.length) return true
        return when {
            a.length == b.length -> a.substring(i + 1) == b.substring(i + 1)
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            else -> a.substring(i) == b.substring(i + 1)
        }
    }

    fun same(a: String, b: String): Boolean =
        a == b || (a.length >= 5 && b.length >= 5 && a[0] == b[0] && lev1(a, b))
}
