package com.mathmaster.app

import android.content.Context
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Per-question statistics (persisted). */
data class QStat(
    var correct: Int = 0,
    var incorrect: Int = 0,
    var avgMs: Double = 0.0,        // average response time
    var lastSeen: Long = 0L,        // epoch millis
    var difficulty: Double = 50.0,  // 5 (easy) .. 100 (hard)
    var streak: Int = 0             // consecutive correct answers
) {
    val attempts: Int get() = correct + incorrect
}

data class CatSummary(val attempts: Int, val accuracyPct: Int, val facts: Int)

class Outcome(val improved: Boolean, val requeue: Boolean)

/** Non-persistent state of one practice session. */
class SessionRuntime {
    val pool = mutableListOf<Fact>()                 // questions currently in rotation
    val unseen = mutableListOf<Fact>()               // not yet introduced
    val queue = mutableListOf<Pair<Fact, Int>>()     // reintroduced questions (fact, due answer index)
    val recent = ArrayDeque<String>()
    val missed = LinkedHashMap<String, Fact>()
    var sinceIntro = 0
    var improved = 0
    var introduced = 0
    var startedAt = 0L
}

/** SharedPreferences + JSON persistence. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("mathmaster", Context.MODE_PRIVATE)

    fun loadStats(cat: String): MutableMap<String, QStat> {
        val raw = prefs.getString("stats_$cat", null) ?: return HashMap()
        return try {
            val o = JSONObject(raw)
            val m = HashMap<String, QStat>()
            o.keys().forEach { k ->
                val j = o.getJSONObject(k)
                m[k] = QStat(
                    j.optInt("c"), j.optInt("i"), j.optDouble("a", 0.0),
                    j.optLong("t"), j.optDouble("d", 50.0), j.optInt("s")
                )
            }
            m
        } catch (e: Exception) {
            HashMap()
        }
    }

    fun saveStats(cat: String, stats: Map<String, QStat>) {
        val o = JSONObject()
        for ((k, s) in stats) {
            val j = JSONObject()
            j.put("c", s.correct)
            j.put("i", s.incorrect)
            j.put("a", s.avgMs)
            j.put("t", s.lastSeen)
            j.put("d", s.difficulty)
            j.put("s", s.streak)
            o.put(k, j)
        }
        prefs.edit().putString("stats_$cat", o.toString()).apply()
    }

    fun loadWindow(cat: String): MutableList<Boolean> =
        (prefs.getString("win_$cat", "") ?: "").map { it == '1' }.toMutableList()

    fun saveWindow(cat: String, w: List<Boolean>) {
        prefs.edit().putString("win_$cat", w.joinToString("") { if (it) "1" else "0" }).apply()
    }

    fun clearCategory(cat: String) {
        prefs.edit().remove("stats_$cat").remove("win_$cat").apply()
    }

    fun loadSettings(cat: String): Triple<Int, Int, Int>? {
        val p = (prefs.getString("set_$cat", null) ?: return null).split(",")
        if (p.size != 3) return null
        val a = p[0].toIntOrNull()
        val b = p[1].toIntOrNull()
        val q = p[2].toIntOrNull()
        return if (a == null || b == null || q == null) null else Triple(a, b, q)
    }

    fun saveSettings(cat: String, lo: Int, hi: Int, questions: Int) {
        prefs.edit().putString("set_$cat", "$lo,$hi,$questions").apply()
    }

    fun totalAnswers(): Int = prefs.getInt("total_answers", 0)
    fun sessions(): Int = prefs.getInt("sessions", 0)
    fun bestStreak(): Int = prefs.getInt("best_streak", 0)
    fun bumpAnswers() { prefs.edit().putInt("total_answers", totalAnswers() + 1).apply() }
    fun bumpSessions() { prefs.edit().putInt("sessions", sessions() + 1).apply() }
    fun updateBest(streak: Int) {
        if (streak > bestStreak()) prefs.edit().putInt("best_streak", streak).apply()
    }
}

/**
 * Adaptive spaced-repetition-style engine.
 *  - Weighted selection: hard / frequently-missed questions appear more, mastered ones less.
 *  - New questions are introduced only when rolling accuracy over the last 10 answers >= 80%.
 *  - Improvement (a previously hard question answered correctly AND faster than its
 *    historical average) sharply lowers its difficulty, so it appears less often.
 *  - Wrong answers and abnormally slow answers are re-queued to reappear within the session.
 */
class AdaptiveEngine(private val store: Store) {
    companion object {
        const val MIN_POOL = 8
        const val WINDOW = 10
        const val THRESHOLD = 0.8
    }

    private val stats = HashMap<String, MutableMap<String, QStat>>()
    private val windows = HashMap<String, MutableList<Boolean>>()
    private val dirty = HashSet<String>()

    private fun stats(cat: String) = stats.getOrPut(cat) { store.loadStats(cat) }
    private fun window(cat: String) = windows.getOrPut(cat) { store.loadWindow(cat) }

    fun summary(cat: String): CatSummary {
        val all = stats(cat).values
        val attempts = all.sumOf { it.attempts }
        val correct = all.sumOf { it.correct }
        return CatSummary(
            attempts,
            if (attempts > 0) correct * 100 / attempts else 0,
            all.count { it.attempts > 0 }
        )
    }

    fun reset(cat: String) {
        stats[cat] = HashMap()
        windows[cat] = mutableListOf()
        dirty.remove(cat)
        store.clearCategory(cat)
    }

    /** Pool = already-practiced questions in range; unseen ones wait to be introduced. */
    fun seed(cat: String, facts: List<Fact>, rt: SessionRuntime) {
        val st = stats(cat)
        val (seen, fresh) = facts.partition { (st[it.key]?.attempts ?: 0) > 0 }
        rt.pool.addAll(seen)
        rt.unseen.addAll(fresh.shuffled())
        while (rt.pool.size < MIN_POOL && rt.unseen.isNotEmpty()) {
            rt.pool.add(rt.unseen.removeAt(0))
        }
    }

    private fun weight(s: QStat?, now: Long): Double {
        if (s == null) return 3.0
        var w = 1.0 + s.difficulty / 20.0                       // 1.25 .. 6
        if (s.streak >= 3 && s.difficulty <= 20.0) {            // mastered: rare, but spaced-repetition
            val days = max(0.0, (now - s.lastSeen) / 86_400_000.0)
            w = 0.35 * (1.0 + min(days, 14.0) / 4.0)            // creeps back up over time
        }
        if (s.streak == 0 && s.incorrect > 0) w *= 1.5          // recently missed
        return w
    }

    fun pick(cat: String, rt: SessionRuntime, answered: Int): Fact {
        val last = rt.recent.lastOrNull()
        val dueIdx = rt.queue.indexOfFirst { it.second <= answered && it.first.key != last }
        if (dueIdx >= 0) {
            val f = rt.queue.removeAt(dueIdx).first
            remember(rt, f)
            return f
        }
        val avoid = when {
            rt.pool.size > 3 -> 2
            rt.pool.size > 1 -> 1
            else -> 0
        }
        val blocked = rt.recent.takeLast(avoid).toSet()
        val candidates = rt.pool.filter { it.key !in blocked }.ifEmpty { rt.pool }
        val st = stats(cat)
        val now = System.currentTimeMillis()
        val weights = candidates.map { weight(st[it.key], now) }
        var r = Random.nextDouble() * weights.sum()
        var chosen = candidates.last()
        for (i in candidates.indices) {
            r -= weights[i]
            if (r <= 0.0) {
                chosen = candidates[i]
                break
            }
        }
        remember(rt, chosen)
        return chosen
    }

    private fun remember(rt: SessionRuntime, f: Fact) {
        rt.recent.addLast(f.key)
        while (rt.recent.size > 4) rt.recent.removeFirst()
    }

    fun record(cat: String, f: Fact, correct: Boolean, ms: Double): Outcome {
        val s = stats(cat).getOrPut(f.key) { QStat() }
        val n = s.attempts
        val had = n > 0
        val prevAvg = s.avgMs
        val t = min(ms, 30_000.0)
        val wasHard = s.difficulty >= 50.0
        var improved = false
        var slow = false

        if (correct) {
            s.correct++
            s.streak++
            val speed = ((8000.0 - t) / 6000.0).coerceIn(0.0, 1.0)
            s.difficulty -= 8.0 + 6.0 * speed
            if (had && wasHard && t < prevAvg) {      // improvement detected
                s.difficulty -= 20.0
                improved = true
            }
            slow = had && n >= 2 && t > max(2.0 * prevAvg, 6000.0)
            if (slow) {                                // abnormally long: bring it back
                s.difficulty += 12.0
                s.streak = 0
            }
        } else {
            s.incorrect++
            s.streak = 0
            s.difficulty += 15.0 + 10.0 * (t / 15_000.0).coerceIn(0.0, 1.0)
        }
        s.difficulty = s.difficulty.coerceIn(5.0, 100.0)
        s.avgMs = if (had) (prevAvg * n + t) / (n + 1) else t
        s.lastSeen = System.currentTimeMillis()

        val w = window(cat)
        w.add(correct)
        while (w.size > WINDOW) w.removeAt(0)
        dirty.add(cat)
        return Outcome(improved, !correct || slow)
    }

    /** Introduce a new question only when rolling accuracy over the last 10 answers >= 80%. */
    fun introduceIfReady(cat: String, rt: SessionRuntime) {
        rt.sinceIntro++
        val w = window(cat)
        if (w.size >= WINDOW && rt.sinceIntro >= 3 && rt.unseen.isNotEmpty()) {
            val acc = w.count { it }.toDouble() / w.size
            if (acc >= THRESHOLD) {
                rt.pool.add(rt.unseen.removeAt(0))
                rt.sinceIntro = 0
                rt.introduced++
            }
        }
    }

    fun flush() {
        for (cat in dirty.toList()) {
            store.saveStats(cat, stats(cat))
            store.saveWindow(cat, window(cat))
        }
        dirty.clear()
    }
}
