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

// FIX #8: perf — lightweight per-session aggregate for the dashboard trend chart.
// Persisted as a compact "acc1,ms1;acc2,ms2;..." string per category (no JSON overhead).
data class SessionPoint(val accuracyPct: Int, val avgMs: Int)

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
    // FIX #8: perf — accumulate per-session response times so finish() can persist
    // the aggregate without re-reading per-question stats off the main thread.
    var totalMs: Long = 0L
    var answerCount: Int = 0
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
            // FIX #16: drop corrupt payload instead of retrying (and failing) forever.
            prefs.edit().remove("stats_$cat").apply()
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
        prefs.edit().remove("stats_$cat").remove("win_$cat").remove("hist_$cat").apply()
    }

    // FIX #8: perf — one-time migration of the old "powers" key. Powers of 2 and 3
    // used to share a single stats bucket; after splitting them into two categories,
    // the old bucket is renamed to "powers2" so existing progress is preserved.
    // Runs once; a marker flag prevents re-migration on subsequent launches.
    fun migratePowersSplit() {
        if (prefs.getBoolean("migrated_powers_split", false)) return
        val hasOld = prefs.contains("stats_powers") ||
                     prefs.contains("win_powers") ||
                     prefs.contains("hist_powers")
        if (hasOld) {
            // Copy old keys to their "powers2" slots (do not overwrite if new exists).
            if (!prefs.contains("stats_powers2")) {
                prefs.getString("stats_powers", null)?.let {
                    prefs.edit().putString("stats_powers2", it).apply()
                }
            }
            if (!prefs.contains("win_powers2")) {
                prefs.getString("win_powers", null)?.let {
                    prefs.edit().putString("win_powers2", it).apply()
                }
            }
            if (!prefs.contains("hist_powers2")) {
                prefs.getString("hist_powers", null)?.let {
                    prefs.edit().putString("hist_powers2", it).apply()
                }
            }
            prefs.edit()
                .remove("stats_powers")
                .remove("win_powers")
                .remove("hist_powers")
                .remove("set_powers")
                .putBoolean("migrated_powers_split", true)
                .apply()
        } else {
            prefs.edit().putBoolean("migrated_powers_split", true).apply()
        }
    }

        // FIX #8: perf — history is capped at the last 30 sessions to keep reads tiny.
    fun loadHistory(cat: String): MutableList<SessionPoint> {
        val raw = prefs.getString("hist_$cat", null) ?: return mutableListOf()
        return try {
            raw.split(';').mapNotNull { seg ->
                val p = seg.split(',')
                if (p.size != 2) null
                else SessionPoint(p[0].toIntOrNull() ?: return@mapNotNull null,
                                  p[1].toIntOrNull() ?: return@mapNotNull null)
            }.toMutableList()
        } catch (e: Exception) {
            prefs.edit().remove("hist_$cat").apply()
            mutableListOf()
        }
    }

    fun saveHistory(cat: String, h: List<SessionPoint>) {
        val s = h.takeLast(30).joinToString(";") { "${it.accuracyPct},${it.avgMs}" }
        prefs.edit().putString("hist_$cat", s).apply()
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

    // FIX #8: in-memory cache for the three global counters. SharedPreferences is only
    // touched on first read and on flush(), so screen transitions no longer hit disk.
    private var cachedTotals: Triple<Int, Int, Int>? = null
    private var totalsDirty = false

    private fun loadTotals(): Triple<Int, Int, Int> = cachedTotals ?: Triple(
        prefs.getInt("total_answers", 0),
        prefs.getInt("sessions", 0),
        prefs.getInt("best_streak", 0)
    ).also { cachedTotals = it }

    fun totalAnswers(): Int = loadTotals().first
    fun sessions(): Int = loadTotals().second
    fun bestStreak(): Int = loadTotals().third

    fun bumpAnswers() {
        val (a, s, b) = loadTotals()
        cachedTotals = Triple(a + 1, s, b)
        totalsDirty = true
    }

    fun bumpSessions() {
        val (a, s, b) = loadTotals()
        cachedTotals = Triple(a, s + 1, b)
        totalsDirty = true
    }

    fun updateBest(streak: Int) {
        val (a, s, b) = loadTotals()
        if (streak > b) {
            cachedTotals = Triple(a, s, streak)
            totalsDirty = true
        }
    }

    /** FIX #8: writes the cached counters back to disk exactly once. */
    fun flushTotals() {
        if (!totalsDirty) return
        val (a, s, b) = loadTotals()
        prefs.edit()
            .putInt("total_answers", a)
            .putInt("sessions", s)
            .putInt("best_streak", b)
            .apply()
        totalsDirty = false
    }

    // FIX #8: perf — wipe the in-memory counter cache AND the persisted values.
    // Called by "Reset All Progress". The cache becomes an all-zeros triple so
    // subsequent reads see the reset immediately, without waiting for a process
    // restart or a new `loadTotals()` from disk.
    fun resetTotals() {
        cachedTotals = Triple(0, 0, 0)
        totalsDirty = true
        prefs.edit()
            .putInt("total_answers", 0)
            .putInt("sessions", 0)
            .putInt("best_streak", 0)
            .apply()
    }

    // FIX #8: perf — overwrite the cached totals with externally-computed values.
    // Used by AdaptiveEngine.recomputeTotalsFromStats() after a per-category reset
    // so the cache reflects "answers still stored" rather than "answers ever made."
    fun setTotals(answers: Int, sessions: Int, best: Int) {
        cachedTotals = Triple(answers, sessions, best)
        totalsDirty = true
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
    private val histories = HashMap<String, MutableList<SessionPoint>>()   // FIX #8: perf

    private fun stats(cat: String) = stats.getOrPut(cat) { store.loadStats(cat) }
    private fun window(cat: String) = windows.getOrPut(cat) { store.loadWindow(cat) }

    // FIX #8: perf — read APIs for the Progress Dashboard. Sorted in-memory;
    // callers should invoke these off the main thread.
    data class FactDetail(
        val key: String, val prompt: String, val answer: String,
        val correct: Int, val incorrect: Int, val avgMs: Double,
        val lastSeen: Long, val difficulty: Double
    )

    @Synchronized
    fun details(cat: String): List<FactDetail> =
        stats(cat).map { (k, s) ->
            FactDetail(k, k, "", s.correct, s.incorrect, s.avgMs, s.lastSeen, s.difficulty)
        }
    @Synchronized
    fun mastery(cat: String): Triple<Int, Int, Int> {   // (mastered%, learning%, struggling%)
        val all = stats(cat).values.filter { it.attempts > 0 }
        if (all.isEmpty()) return Triple(0, 0, 0)
        val mastered = all.count { it.difficulty <= 20.0 && it.streak >= 3 }
        val struggling = all.count { it.difficulty >= 60.0 || (it.incorrect > it.correct) }
        val learning = all.size - mastered - struggling
        val t = all.size
        return Triple(mastered * 100 / t, learning * 100 / t, struggling * 100 / t)
    }
    @Synchronized
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
    @Synchronized
    fun reset(cat: String) {
        stats[cat] = HashMap()
        windows[cat] = mutableListOf()
        histories[cat] = mutableListOf()
        dirty.remove(cat)
        store.clearCategory(cat)
    }

    // FIX #8: perf — wipe the cached totals too. Without this, the header keeps
    // showing the pre-reset counters because Store caches them in memory and
    // never re-reads from prefs until the process restarts.
    fun resetTotals() {
        store.resetTotals()
    }

    // FIX #8: perf — recompute the global counters from surviving per-category stats.
    // Called after a per-category reset so the header no longer reflects answers that
    // were just erased. `sessions` is NOT derived — we don't store sessions per
    // category — so it stays as-is (it's a "how many sessions have you ever run?"
    // counter, not a "how much data is stored?" counter). `best_streak` is recomputed
    // from the max streak still present in any category.
    @Synchronized
    fun recomputeTotalsFromStats() {
        var totalCorrect = 0
        var totalIncorrect = 0
        var bestStreak = 0
        for (cat in Categories.all) {
            val map = stats(cat.id)
            for (s in map.values) {
                totalCorrect += s.correct
                totalIncorrect += s.incorrect
                if (s.streak > bestStreak) bestStreak = s.streak
            }
        }
        store.setTotals(totalCorrect + totalIncorrect, store.sessions(), bestStreak)
    }
    // FIX #8: perf — snapshot per-session aggregate for the dashboard trend chart.
    @Synchronized
    fun recordSession(cat: String, score: Int, total: Int, avgMs: Int) {
        if (total <= 0) return
        val h = histories.getOrPut(cat) { store.loadHistory(cat) }
        h.add(SessionPoint(score * 100 / total, avgMs))
        while (h.size > 30) h.removeAt(0)
        dirty.add(cat)
    }

    @Synchronized
    fun history(cat: String): List<SessionPoint> =
        histories.getOrPut(cat) { store.loadHistory(cat) }.toList()

    /** Pool = already-practiced questions in range; unseen ones wait to be introduced. */
    @Synchronized
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

    @Synchronized
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

    @Synchronized
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
    @Synchronized
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

    @Synchronized
    fun flush() {
        for (cat in dirty.toList()) {
            store.saveStats(cat, stats(cat))
            store.saveWindow(cat, window(cat))
            store.saveHistory(cat, histories.getOrPut(cat) { store.loadHistory(cat) })   // FIX #8
        }
        dirty.clear()
        store.flushTotals()   // FIX #8: batch-persist global counters
    }
}
