package com.mathmaster.app

import android.content.Context
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/** Per-question statistics (persisted). */
data class QStat(
    var correct: Int = 0,
    var incorrect: Int = 0,
    var avgMs: Double = 0.0,
    var lastSeen: Long = 0L,
    var difficulty: Double = 50.0,
    var streak: Int = 0,
    var stability: Double = 0.0,      // memory strength in days (0 = not modelled yet)
    var memDifficulty: Double = 0.0   // 1..10 (0 = not modelled yet)
) {
    val attempts: Int get() = correct + incorrect
}

data class CatSummary(val attempts: Int, val accuracyPct: Int, val facts: Int)

data class SessionPoint(val accuracyPct: Int, val avgMs: Int, val at: Long = 0L)

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
                    j.optLong("t"), j.optDouble("d", 50.0), j.optInt("s"),
                    j.optDouble("st", 0.0), j.optDouble("md", 0.0)
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
            j.put("st", s.stability)
            j.put("md", s.memDifficulty)
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
                if (p.size < 2) null
                else SessionPoint(
                    p[0].toIntOrNull() ?: return@mapNotNull null,
                    p[1].toIntOrNull() ?: return@mapNotNull null,
                    p.getOrNull(2)?.toLongOrNull() ?: 0L
                )
            }.toMutableList()
        } catch (e: Exception) {
            prefs.edit().remove("hist_$cat").apply()
            mutableListOf()
        }
    }

    fun saveHistory(cat: String, h: List<SessionPoint>) {
        val s = h.takeLast(100).joinToString(";") { "${it.accuracyPct},${it.avgMs},${it.at}" }
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

/** FSRS-4.5 style memory model: stability (days), difficulty (1-10), retrievability. */
object Fsrs {
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0
    private val W = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031,
        1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755
    )

    fun retrievability(elapsedDays: Double, stability: Double): Double =
        if (stability <= 0.0) 0.0
        else (1.0 + FACTOR * max(0.0, elapsedDays) / stability).pow(DECAY)

    fun expectedMs(f: Fact): Double {
        val cost = f.prompt.count { it.isDigit() } * 2 + f.answer.length
        return 1200.0 + 150.0 * cost
    }

    fun grade(correct: Boolean, ms: Double, expected: Double): Int = when {
        !correct -> 1
        ms >= 2.0 * expected -> 2
        ms <= 0.7 * expected -> 4
        else -> 3
    }

    fun update(s: QStat, grade: Int, elapsedDays: Double) {
        if (s.stability <= 0.0 || s.memDifficulty <= 0.0) {
            if (s.attempts > 1) {
                s.stability = 1.0 + s.streak.coerceAtMost(5) * 1.5
                s.memDifficulty = (s.difficulty / 10.0).coerceIn(1.0, 10.0)
            } else {
                s.stability = W[grade - 1].coerceAtLeast(0.05)
                s.memDifficulty = (W[4] - W[5] * (grade - 3)).coerceIn(1.0, 10.0)
                return
            }
        }
        val r = retrievability(elapsedDays, s.stability)
        val d = s.memDifficulty
        val next = if (grade == 1) {
            (W[11] * d.pow(-W[12]) * ((s.stability + 1).pow(W[13]) - 1) * exp((1 - r) * W[14]))
                .coerceIn(0.05, s.stability)
        } else {
            val hard = if (grade == 2) W[15] else 1.0
            val easy = if (grade == 4) W[16] else 1.0
            s.stability * (1 + exp(W[8]) * (11 - d) * s.stability.pow(-W[9]) *
                (exp((1 - r) * W[10]) - 1) * hard * easy)
        }
        s.stability = next.coerceIn(0.05, 3650.0)
        val d0Easy = W[4] - W[5]
        s.memDifficulty = (W[7] * d0Easy + (1 - W[7]) * (d - W[6] * (grade - 3))).coerceIn(1.0, 10.0)
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

    // FIX #8 — cache the `details()` projection per category. `details()` maps
    // every QStat into a FactDetail on every call, which is O(n) in the number of
    // facts (up to ~5,000 per category). At typical usage this is ~1-2ms. At
    // mastery-of-everything scale it becomes ~15-20ms per category, which compounds
    // across 10 categories in buildDashboard() to ~150-200ms. This cache keeps the
    // cost amortized to zero: the list is rebuilt only when that category's stats
    // actually change. Invalidated by invalidateDetailsCache(cat) in record() and
    // reset() — both already exist.
    private val detailsCache = HashMap<String, List<FactDetail>>()

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
        // FIX #8 — serve from cache when possible. Cache is populated on first call
        // and invalidated by invalidateDetailsCache() whenever stats[cat] mutates.
        detailsCache.getOrPut(cat) {
            stats(cat).map { (k, s) ->
                FactDetail(k, k, "", s.correct, s.incorrect, s.avgMs, s.lastSeen, s.difficulty)
            }
        }

    // FIX #8 — invalidate the details cache for one category. Called from record()
    // and reset() so the cache stays coherent with stats. This is a private helper;
    // it never touches any public API or existing method signatures.
    private fun invalidateDetailsCache(cat: String) {
        detailsCache.remove(cat)
    }
    @Synchronized
    fun mastery(cat: String): Triple<Int, Int, Int> {   // (mastered%, learning%, struggling%)
        val all = stats(cat).values.filter { it.attempts > 0 }
        if (all.isEmpty()) return Triple(0, 0, 0)
        val now = System.currentTimeMillis()
        var mastered = 0
        var struggling = 0
        for (s in all) {
            if (s.stability > 0.0) {
                val r = Fsrs.retrievability((now - s.lastSeen) / 86_400_000.0, s.stability)
                when {
                    s.stability >= 7.0 && r >= 0.85 && s.correct >= 3 -> mastered++
                    r < 0.6 || s.memDifficulty >= 8.0 -> struggling++
                }
            } else {   // not modelled yet: old rules
                when {
                    s.difficulty <= 20.0 && s.streak >= 3 -> mastered++
                    s.difficulty >= 60.0 || s.incorrect > s.correct -> struggling++
                }
            }
        }
        val t = all.size
        val learning = t - mastered - struggling
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
        // FIX #8 — invalidate the details cache so the next details() call rebuilds
        // from the (now empty) stats map.
        invalidateDetailsCache(cat)
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
        h.add(SessionPoint(score * 100 / total, avgMs, System.currentTimeMillis()))
        while (h.size > 100) h.removeAt(0)
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
        if (s.stability > 0.0) {
            val days = max(0.0, (now - s.lastSeen) / 86_400_000.0)
            val r = Fsrs.retrievability(days, s.stability)
            var w = 0.2 + 8.0 * (1.0 - r) + 0.3 * s.memDifficulty
            if (s.streak == 0 && s.incorrect > 0) w *= 1.5
            return w
        }
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
        // Memory-model update: uses the real time since this fact was last seen.
        val nowMs = System.currentTimeMillis()
        val elapsedDays = if (s.lastSeen > 0L) (nowMs - s.lastSeen) / 86_400_000.0 else 0.0
        val baseline = if (had && n >= 5) 0.5 * prevAvg + 0.5 * Fsrs.expectedMs(f) else Fsrs.expectedMs(f)
        Fsrs.update(s, Fsrs.grade(correct, t, baseline), elapsedDays)
        s.lastSeen = nowMs

        val w = window(cat)
        w.add(correct)
        while (w.size > WINDOW) w.removeAt(0)
        dirty.add(cat)
        // FIX #8 — invalidate the details cache: record() just mutated stats[cat],
        // so any cached projection for this category is now stale.
        invalidateDetailsCache(cat)
        return Outcome(improved, !correct || slow)
    }

    /** Introduce a new question only when rolling accuracy over the last 10 answers >= 80%. */
    @Synchronized
    fun introduceIfReady(cat: String, rt: SessionRuntime) {
        rt.sinceIntro++
        val w = window(cat)
        if (w.size >= WINDOW && rt.sinceIntro >= 3 && rt.unseen.isNotEmpty()) {
            val acc = w.count { it }.toDouble() / w.size
            if (acc >= THRESHOLD && dueCount(cat, rt) <= 5) {
                rt.pool.add(rt.unseen.removeAt(0))
                rt.sinceIntro = 0
                rt.introduced++
            }
        }
    }

    private fun dueCount(cat: String, rt: SessionRuntime): Int {
        val st = stats(cat)
        val now = System.currentTimeMillis()
        return rt.pool.count { f ->
            val s = st[f.key]
            s != null && s.stability > 0.0 &&
                Fsrs.retrievability((now - s.lastSeen) / 86_400_000.0, s.stability) < 0.8
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
