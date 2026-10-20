package com.mathmaster.app

import androidx.compose.ui.graphics.Color
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs

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

// FIX #8 — Integers category. Generates mixed operation questions (+,-,*,/)
// using negative numbers. The range parameter (lo..hi) determines the magnitude
// of the numbers involved.
private fun genIntegers(lo: Int, hi: Int): List<Fact> {
    val out = ArrayList<Fact>()
    // Use the positive range requested (e.g., 1..10), but generate negatives too
    val h = maxOf(1, hi)
    val l = maxOf(1, minOf(lo, h))
    
    // Define how many positive numbers per range to use for multiplying/dividing
    // to keep the questions reasonable (e.g., multiplication stays under ~50)
    val mulMax = minOf(h, 10)
    
    for (a in l..h) for (b in l..h) {
        // Addition
        out.add(intFact("$a + $b", "$a + $b = ?", (a + b).toLong(), "$a + $b = ${a + b}"))
        out.add(intFact("$a + (-$b)", "$a + (−$b) = ?", (a - b).toLong(), "$a + (−$b) = ${a - b}"))
        out.add(intFact("(-$a) + $b", "(−$a) + $b = ?", (b - a).toLong(), "(−$a) + $b = ${b - a}"))
        out.add(intFact("(-$a) + (-$b)", "(−$a) + (−$b) = ?", (-a - b).toLong(), "(−$a) + (−$b) = ${-a - b}"))

        // Subtraction
        out.add(intFact("$a - $b", "$a − $b = ?", (a - b).toLong(), "$a − $b = ${a - b}"))
        out.add(intFact("$a - (-$b)", "$a − (−$b) = ?", (a + b).toLong(), "$a − (−$b) = ${a + b}"))
        out.add(intFact("(-$a) - $b", "(−$a) − $b = ?", (-a - b).toLong(), "(−$a) − $b = ${-a - b}"))
        out.add(intFact("(-$a) - (-$b)", "(−$a) − (−$b) = ?", (b - a).toLong(), "(−$a) − (−$b) = ${b - a}"))
    }

    // Multiplication / Division use a tighter range so numbers don't explode
    for (a in l..mulMax) for (b in l..mulMax) {
        // Multiplication
        out.add(intFact("$a × $b", "$a × $b = ?", (a * b).toLong(), "$a × $b = ${a * b}"))
        out.add(intFact("(-$a) × $b", "(−$a) × $b = ?", (-a * b).toLong(), "(−$a) × $b = ${-a * b}"))
        out.add(intFact("$a × (-$b)", "$a × (−$b) = ?", (-a * b).toLong(), "$a × (−$b) = ${-a * b}"))
        out.add(intFact("(-$a) × (-$b)", "(−$a) × (−$b) = ?", (a * b).toLong(), "(−$a) × (−$b) = ${a * b}"))

        // Division (exact only)
        val ab = a * b
        out.add(intFact("$ab ÷ $b", "$ab ÷ $b = ?", a.toLong(), "$ab ÷ $b = $a"))
        out.add(intFact("(-$ab) ÷ $b", "(−$ab) ÷ $b = ?", (-a).toLong(), "(−$ab) ÷ $b = ${-a}"))
        out.add(intFact("$ab ÷ (-$b)", "$ab ÷ (−$b) = ?", (-a).toLong(), "$ab ÷ (−$b) = ${-a}"))
        out.add(intFact("(-$ab) ÷ (-$b)", "(−$ab) ÷ (−$b) = ?", a.toLong(), "(−$ab) ÷ (−$b) = $a"))
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
        val frac = "$n/$d"
        val bn = n.toBigDecimal()
        val bp = (n * 100).toBigDecimal()
        val bd = d.toBigDecimal()

        val isInfinite = repeats(d)

        val dec: String
        val pct: String
        if (isInfinite) {
            dec = bn.divide(bd, 4, RoundingMode.HALF_UP).toPlainString()
            pct = bp.divide(bd, 2, RoundingMode.HALF_UP).toPlainString() + "%"
        } else {
            dec = bn.divide(bd).stripTrailingZeros().toPlainString()
            pct = bp.divide(bd).stripTrailingZeros().toPlainString() + "%"
        }

        val eq = if (isInfinite) "≈" else "="
        val decNote = if (isInfinite) "Never ends: round to 4 decimal places" else null
        val pctNote = if (isInfinite) "Never ends: round to 2 decimal places" else null

        map["$frac → %"] = Fact(
            "$frac → %", "$frac = ?", pct, "$frac $eq $pct",
            Kind.PCT, pctNote, isInfinite
        )
        map["$frac → dec"] = Fact(
            "$frac → dec", "$frac = ?", dec, "$frac $eq $dec",
            Kind.DEC, decNote, isInfinite
        )
        if (!map.containsKey("$pct → frac")) {
            map["$pct → frac"] = Fact(
                "$pct → frac", "$pct = ?", frac, "$pct $eq $frac",
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
        Category("integers", "Integers", "ℤ", "Add, subtract, multiply and divide with negatives",
            Color(0xFF00D2FF), Color(0xFF3A7BD5), 1, 10, 100, false, ::genIntegers),
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
    private fun placesAfterPoint(s: String): Int =
        if (s.contains('.')) s.substringAfter('.').length else 0

    /** Yellow hint when the input is incomplete or in the wrong format; null when it can be graded. */
    fun check(f: Fact, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        when (f.kind) {
            Kind.INT -> {
                if (t == "-") return "Invalid Format"
            }
            Kind.DEC -> {
                if (!t.contains('.')) return "Add a decimal point (.)"
                if (t.endsWith(".")) return "Invalid Format"
                if (f.infinite && placesAfterPoint(t) != 4) return "Round to exactly 4 decimal places"
            }
            Kind.PCT -> {
                if (!t.contains('%')) return "Add a % sign at the end"
                val body = t.dropLast(1)
                if (body.isEmpty()) return "Invalid Format"
                if (body.endsWith(".")) return "Invalid Format"
                if (f.infinite && placesAfterPoint(body) != 2) return "Round to exactly 2 decimal places"
            }
            Kind.FRAC -> {
                if (!t.contains('/')) return "Use a fraction format (e.g., 1/2)"
                if (t.startsWith("/") || t.endsWith("/")) return "Invalid Format"
                if (t.substringAfter('/').toBigDecimalOrNull()?.signum() == 0) return "Invalid Format"
            }
        }
        return null
    }

    fun grade(f: Fact, raw: String): Boolean? {
        val t = raw.trim().replace(",", "").replace(" ", "")
        if (t.isEmpty()) return null
        return when (f.kind) {
            Kind.INT -> t.toBigIntegerOrNull()?.toString() == f.answer
            Kind.DEC -> t.toBigDecimalOrNull()?.let { it.compareTo(f.answer.toBigDecimal()) == 0 }
            Kind.PCT -> t.removeSuffix("%").toBigDecimalOrNull()
                ?.let { it.compareTo(f.answer.removeSuffix("%").toBigDecimal()) == 0 }
            Kind.FRAC -> {
                val p = t.split("/")
                val n = if (p.size == 2) p[0].toLongOrNull() else null
                val d = if (p.size == 2) p[1].toLongOrNull() else null
                if (n == null || d == null || d == 0L) {
                    null
                } else {
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
