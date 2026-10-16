package com.mathmaster.app

import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

enum class Kind { INT, PCT, DEC, FRAC }

data class Fact(
    val key: String,       // stable id used for statistics
    val prompt: String,    // shown on screen
    val answer: String,    // canonical answer
    val solution: String,  // full worked fact, e.g. "6 × 7 = 42"
    val kind: Kind
)

class Category(
    val id: String,
    val name: String,
    val symbol: String,
    val blurb: String,
    val colorA: Color,
    val colorB: Color,
    val defMin: Int,
    val defMax: Int,
    val hardMax: Int,
    val usesFractionKeys: Boolean = false,
    val generate: (Int, Int) -> List<Fact>
) {
    fun presets(): List<Pair<Int, Int>> =
        listOf(1 to 10, 1 to 20, 1 to 30).map { it.first to min(it.second, hardMax) }.distinct()
}

private fun intFact(key: String, prompt: String, ans: Long, sol: String) =
    Fact(key, prompt, ans.toString(), sol, Kind.INT)

private fun pow(base: Long, exp: Int): Long {
    var r = 1L
    repeat(exp) { r *= base }
    return r
}

private fun factorial(n: Int): Long {
    var r = 1L
    for (i in 2..n) r *= i
    return r
}

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

// FIX #12: faster than String.format for the small number of decimal places we use.
// Behaviourally identical to "%.Nf" + trimEnd('0') + trimEnd('.').
private fun fmt(v: Double, places: Int): String {
    val scale = when (places) {
        2 -> 100.0
        4 -> 10_000.0
        else -> Math.pow(10.0, places.toDouble())
    }
    val rounded = Math.round(v * scale).toLong()
    val intPart = rounded / scale.toLong()
    val fracPart = rounded % scale.toLong()
    if (fracPart == 0L) return intPart.toString()
    val fracStr = fracPart.toString().padStart(places, '0').trimEnd('0')
    return "$intPart.$fracStr"
}

private fun genAdd(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (i in lo..hi) for (j in i..hi) {
        out.add(intFact("$i + $j", "$i + $j = ?", (i + j).toLong(), "$i + $j = ${i + j}"))
    }
    return out
}

private fun genSub(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (a in lo..hi) for (b in lo until a) {
        out.add(intFact("$a - $b", "$a − $b = ?", (a - b).toLong(), "$a − $b = ${a - b}"))
    }
    return out
}

private fun genMul(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (i in lo..hi) for (j in i..hi) {
        out.add(intFact("$i × $j", "$i × $j = ?", (i * j).toLong(), "$i × $j = ${i * j}"))
    }
    return out
}

private fun genDiv(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (i in lo..hi) for (j in lo..hi) {
        val dividend = i * j
        out.add(intFact("$dividend ÷ $i", "$dividend ÷ $i = ?", j.toLong(), "$dividend ÷ $i = $j"))
    }
    return out
}

private fun genSquares(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (i in lo..hi) {
        val sq = i * i
        out.add(intFact("${i}²", "${i}² = ?", sq.toLong(), "${i}² = $sq"))
        out.add(intFact("√$sq", "√$sq = ?", i.toLong(), "√$sq = $i"))
    }
    return out
}

private fun genCubes(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (i in lo..hi) {
        val cu = i * i * i
        out.add(intFact("${i}³", "${i}³ = ?", cu.toLong(), "${i}³ = $cu"))
        out.add(intFact("∛$cu", "∛$cu = ?", i.toLong(), "∛$cu = $i"))
    }
    return out
}

// FIX #8: perf — Powers split into two categories. Each generator emits only its base,
// so the "Powers of 2" card can't accidentally surface a 3^n fact (and vice versa).
private fun genPowers2(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (e in lo..hi) {
        out.add(intFact("2^$e", "2^$e = ?", pow(2, e), "2^$e = ${pow(2, e)}"))
    }
    return out
}

private fun genPowers3(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (e in lo..hi) {
        out.add(intFact("3^$e", "3^$e = ?", pow(3, e), "3^$e = ${pow(3, e)}"))
    }
    return out
}

private fun genFactorials(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (n in lo..hi) {
        val v = factorial(n)
        out.add(intFact("${n}!", "${n}! = ?", v, "${n}! = $v"))
        if (n >= 3) {
            out.add(intFact("n! = $v", "n! = $v  →  n = ?", n.toLong(), "${n}! = $v"))
        }
    }
    return out
}

private fun genFdp(lo0: Int, hi0: Int): List<Fact> {
    val lo = maxOf(2, lo0)
    val hi = maxOf(lo, hi0)
    val map = LinkedHashMap<String, Fact>()
    for (d in lo..hi) for (n in 1 until d) {
        if (gcd(n, d) != 1) continue
        val ratio = n.toDouble() / d
        val frac = "$n/$d"
        val pct = fmt(ratio * 100, 2) + "%"
        val dec = fmt(ratio, 4)
        map["$frac → %"] = Fact("$frac → %", "$frac = ? %", pct, "$frac = $pct", Kind.PCT)
        map["$frac → dec"] = Fact("$frac → dec", "$frac = ? (decimal)", dec, "$frac = $dec", Kind.DEC)
        if (!map.containsKey("$pct → frac")) {
            map["$pct → frac"] = Fact("$pct → frac", "$pct = ? (fraction)", frac, "$pct = $frac", Kind.FRAC)
        }
    }
    return map.values.toList()
}

object Categories {
    val all: List<Category> = listOf(
        Category("addition", "Addition", "＋", "Master addition facts",
            Color(0xFF00B4DB), Color(0xFF0083B0), 1, 10, 99, false, ::genAdd),
        Category("subtraction", "Subtraction", "−", "Master subtraction facts",
            Color(0xFF7F7FD5), Color(0xFF86A8E7), 1, 10, 99, false, ::genSub),
        Category("multiplication", "Multiplication", "×", "Master multiplication tables",
            Color(0xFF4776E6), Color(0xFF8E54E9), 1, 10, 99, false, ::genMul),
        Category("division", "Division", "÷", "Master division facts",
            Color(0xFF11998E), Color(0xFF38C172), 1, 10, 99, false, ::genDiv),
        Category("squares", "Squares & Roots", "x²", "Perfect squares and square roots",
            Color(0xFF8E2DE2), Color(0xFF4A00E0), 1, 20, 99, false, ::genSquares),
        Category("cubes", "Cubes & Roots", "x³", "Perfect cubes and cube roots",
            Color(0xFFF2994A), Color(0xFFE5A100), 1, 10, 99, false, ::genCubes),
        Category("fdp", "Fractions, Decimals,\nPercentages", "%", "Fraction, decimal and percentage equivalents (range = denominators)",
            Color(0xFF56AB2F), Color(0xFF2E8B57), 1, 8, 30, true, ::genFdp),
        Category("powers2", "Powers of 2", "2ⁿ", "Powers of 2",
            Color(0xFFFF416C), Color(0xFFFF4B2B), 1, 10, 30, false, ::genPowers2),
        Category("powers3", "Powers of 3", "3ⁿ", "Powers of 3",
            Color(0xFFEB5757), Color(0xFFC0392B), 1, 10, 30, false, ::genPowers3),
        Category("factorials", "Factorials", "n!", "Factorial values and finding n from n!",
            Color(0xFFFF8008), Color(0xFFE5532D), 1, 10, 20, false, ::genFactorials)
    )

    fun byId(id: String): Category = all.first { it.id == id }
}

object Grading {
    /** True/False when graded, null when the input cannot be parsed. */
    fun grade(f: Fact, raw: String): Boolean? {
        val t = raw.trim().replace(",", "").replace(" ", "")
        if (t.isEmpty()) return null
        return when (f.kind) {
            Kind.INT -> t.toLongOrNull()?.let { it == f.answer.toLong() }
            Kind.PCT -> t.trimEnd('%').toDoubleOrNull()
                ?.let { abs(it - f.answer.trimEnd('%').toDouble()) <= 0.05 }
            Kind.DEC -> t.toDoubleOrNull()?.let { abs(it - f.answer.toDouble()) <= 0.005 }
            Kind.FRAC -> {
                val p = t.split("/")
                val n = if (p.size == 2) p[0].toLongOrNull() else null
                val d = if (p.size == 2) p[1].toLongOrNull() else null
                if (n == null || d == null || d == 0L) {
                    null
                } else {
                    // FIX #11: guard against a malformed canonical answer (defensive;
                    // current generators always emit "n/d", but a future category might not).
                    val e = f.answer.split("/")
                    if (e.size != 2) return null
                    val en = e[0].toLongOrNull()
                    val ed = e[1].toLongOrNull()
                    if (en == null || ed == null || ed == 0L) return null
                    n * ed == d * en
                }
            }
        }
    }
}
