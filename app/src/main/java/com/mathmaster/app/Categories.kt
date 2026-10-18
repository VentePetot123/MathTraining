package com.mathmaster.app

import androidx.compose.ui.graphics.Color
import java.math.BigInteger
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

enum class Kind { INT, PCT, DEC, FRAC }

data class Fact(
    val key: String,       // stable id used for statistics
    val prompt: String,    // shown on screen
    val answer: String,    // canonical answer
    val solution: String,  // full worked fact, e.g. "6 × 7 = 42"
    val kind: Kind,
    val note: String? = null,  // optional hint shown under the answer box
    // FIX #8 — true only for DEC facts whose decimal expansion is infinite.
    // Grading uses this to allow ±0.0001 tolerance for infinite decimals while
    // requiring exact match for finite decimals (0.25 not 0.2500).
    val infinite: Boolean = false
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
        listOf(1 to 10, 1 to 20, 1 to 30, 1 to 50)
}

private fun intFact(key: String, prompt: String, ans: Long, sol: String) =
    Fact(key, prompt, ans.toString(), sol, Kind.INT)

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

// FIX #12: faster than String.format for the small number of decimal places we use.
// Behaviourally identical to "%.Nf" + trimEnd('0') + trimEnd('.').
private fun fmt(v: Double, places: Int): String {
    if (v.isInfinite() || v.isNaN()) return "0"
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

private fun bigFact(key: String, prompt: String, ans: BigInteger, sol: String) =
    Fact(key, prompt, ans.toString(), sol, Kind.INT)

private fun factorialBig(n: Int): BigInteger {
    var r = BigInteger.ONE
    for (i in 2..n) r = r.multiply(BigInteger.valueOf(i.toLong()))
    return r
}

private fun genPowers2(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (e in lo..hi) {
        val v = BigInteger.valueOf(2).pow(e)
        out.add(bigFact("2^$e", "2^$e = ?", v, "2^$e = $v"))
    }
    return out
}

private fun genPowers3(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (e in lo..hi) {
        val v = BigInteger.valueOf(3).pow(e)
        out.add(bigFact("3^$e", "3^$e = ?", v, "3^$e = $v"))
    }
    return out
}

// FIX #8 — Integers category. Generates absolute value and comparison questions
// that produce a non-negative integer answer (so the existing keypad can type it).
private fun genIntegers(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    // Ensure the range covers negative numbers, regardless of what the user picked
    val minVal = minOf(-hi, lo)
    val maxVal = maxOf(hi, -lo)

    // 1. Absolute Value questions (|x| = ?)
    for (i in minVal..maxVal) {
        if (i == 0) continue
        val absVal = kotlin.math.abs(i).toLong()
        out.add(
            intFact(
                key = "|$i|",
                prompt = "|$i| = ?",
                ans = absVal,
                sol = "|$i| = $absVal"
            )
        )
    }

    // 2. Subtraction across zero (-a - b = ?), but we force the answer to be negative.
    // Since the keypad lacks a minus button, we ask for the magnitude instead:
    // "What is the distance between a and b?" which is always positive.
    for (a in minVal..maxVal) {
        for (b in minVal..maxVal) {
            if (a == b || (a in 1..hi && b in 1..hi)) continue // skip positive-only pairs
            val distance = kotlin.math.abs(a - b).toLong()
            out.add(
                intFact(
                    key = "dist($a,$b)",
                    prompt = "Distance from $a to $b",
                    ans = distance,
                    sol = "Distance from $a to $b = $distance"
                )
            )
        }
    }

    return out
}

private fun genFactorials(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    for (n in lo..hi) {
        val v = factorialBig(n)
        out.add(bigFact("${n}!", "${n}! = ?", v, "${n}! = $v"))
        // "find n from n!" stays up to 20!, because a 100-digit prompt can't be read on screen.
        if (n in 3..20) {
            out.add(bigFact("n! = $v", "n! = $v  →  n = ?", BigInteger.valueOf(n.toLong()), "${n}! = $v"))
        }
    }
    return out
}

// FIX #8 — format a finite decimal using the shortest exact representation.
// 0.25 → "0.25"; 0.5 → "0.5"; 0.0 → "0". No trailing zeros, no forced rounding.
private fun trimExact(v: Double): String {
    // up to 15 significant digits; java's Double.toString gives the shortest
    // decimal that round-trips back to the same double.
    val s = v.toString()
    // Strip a trailing ".0" so integers print as integers (e.g. 1.0 → "1").
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

// True when n/d (already reduced) is a repeating decimal, i.e. d has a prime
// factor other than 2 or 5. Those are the answers that can't be typed exactly.
private fun repeats(d: Int): Boolean {
    var x = d
    while (x % 2 == 0) x /= 2
    while (x % 5 == 0) x /= 5
    return x != 1
}

private fun genFdp(lo0: Int, hi0: Int): List<Fact> {
    val lo = maxOf(2, lo0)
    val hi = maxOf(lo, hi0)
    val map = LinkedHashMap<String, Fact>()
    for (d in lo..hi) for (n in 1 until d) {
        if (gcd(n, d) != 1) continue
        val ratio = n.toDouble() / d
        val frac = "$n/$d"

        // FIX #8 — a decimal is "infinite" if the denominator has a prime factor
        // other than 2 or 5. That's when the decimal expansion repeats forever and
        // must be rounded (to 4 places here) for the user to be able to type it.
        // Finite decimals like 1/4 = 0.25 must render as the exact value, not 0.2500.
        val isInfinite = repeats(d)

        // Percent always rounds to 2 decimals (display convention) — kept as-is.
        val pct = fmt(ratio * 100, 2) + "%"

        // Decimal: use 4 places only if infinite; otherwise use the exact value with
        // trailing zeros trimmed (e.g. 0.25, not 0.2500; 0.5, not 0.50).
        val dec = if (isInfinite) fmt(ratio, 4) else trimExact(ratio)

        val decNote = if (isInfinite) "Round to 4 decimal places" else null
        val pctNote = if (isInfinite) "Round to 2 decimal places" else null

        map["$frac → %"] = Fact(
            "$frac → %", "$frac = ? %", pct, "$frac = $pct",
            Kind.PCT, pctNote
        )
        map["$frac → dec"] = Fact(
            "$frac → dec", "$frac = ? (decimal)", dec, "$frac = $dec",
            Kind.DEC, decNote, isInfinite
        )
        if (!map.containsKey("$pct → frac")) {
            map["$pct → frac"] = Fact(
                "$pct → frac", "$pct = ? (fraction)", frac, "$pct = $frac",
                Kind.FRAC
            )
        }
    }
    return map.values.toList()
}

object Categories {
    val all: List<Category> = listOf(
        Category("addition", "Addition", "＋", "Master addition facts",
            Color(0xFF00B4DB), Color(0xFF0083B0), 1, 10, 100, false, ::genAdd),
        Category("subtraction", "Subtraction", "−", "Master subtraction facts",
            Color(0xFF7F7FD5), Color(0xFF86A8E7), 1, 10, 100, false, ::genSub),
        Category("multiplication", "Multiplication", "×", "Master multiplication tables",
            Color(0xFF4776E6), Color(0xFF8E54E9), 1, 10, 100, false, ::genMul),
        Category("division", "Division", "÷", "Master division facts",
            Color(0xFF11998E), Color(0xFF38C172), 1, 10, 100, false, ::genDiv),
        Category("squares", "Squares & Roots", "x²", "Perfect squares and square roots",
            Color(0xFF8E2DE2), Color(0xFF4A00E0), 1, 10, 100, false, ::genSquares),
        Category("cubes", "Cubes & Roots", "x³", "Perfect cubes and cube roots",
            Color(0xFFF2994A), Color(0xFFE5A100), 1, 10, 100, false, ::genCubes),
        Category("fdp", "Fractions, Decimals, Percentages", "%", "Fraction, decimal and percentage equivalents (range = denominators)",
            Color(0xFF56AB2F), Color(0xFF2E8B57), 1, 10, 100, true, ::genFdp),
        Category("integers", "Integers", "ℤ", "Absolute values and distances",
            Color(0xFF00D2FF), Color(0xFF3A7BD5), 1, 10, 20, false, ::genIntegers),
        Category("powers2", "Powers of 2", "2ⁿ", "Powers of 2",
            Color(0xFFFF416C), Color(0xFFFF4B2B), 1, 10, 100, false, ::genPowers2),
        Category("powers3", "Powers of 3", "3ⁿ", "Powers of 3",
            Color(0xFFEB5757), Color(0xFFC0392B), 1, 10, 100, false, ::genPowers3),
        Category("factorials", "Factorials", "n!", "Factorial values and finding n from n!",
            Color(0xFFFF8008), Color(0xFFE5532D), 1, 10, 100, false, ::genFactorials)
    )

    fun byId(id: String): Category = all.first { it.id == id }
}

object Grading {
    /** True/False when graded, null when the input cannot be parsed. */
    fun grade(f: Fact, raw: String): Boolean? {
        val t = raw.trim().replace(",", "").replace(" ", "")
        if (t.isEmpty()) return null
        return when (f.kind) {
            Kind.INT -> (t.toBigIntegerOrNull()?.toString() == f.answer)
            Kind.PCT -> t.trimEnd('%').toDoubleOrNull()
                ?.let { abs(it - f.answer.trimEnd('%').toDouble()) <= 0.05 }
            Kind.DEC -> t.toDoubleOrNull()?.let { user ->
                val expected = f.answer.toDouble()
                if (f.infinite) {
                    // FIX #8 — infinite decimal: accept anything within 4 decimal places
                    // of the correct rounded value. Tolerance of 0.0001 covers both the
                    // user typing "0.3333" (exactly) and "0.33333" (extra digit rounded).
                    abs(user - expected) <= 0.0001
                } else {
                    // FIX #8 — finite decimal: require exact match. If canonical is
                    // "0.25", user must type 0.25 (not 0.2500 — but the parser trims
                    // trailing zeros implicitly because 0.2500.toDouble() == 0.25).
                    // Tiny tolerance to handle floating point representation.
                    abs(user - expected) <= 1e-9
                }
            }
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
