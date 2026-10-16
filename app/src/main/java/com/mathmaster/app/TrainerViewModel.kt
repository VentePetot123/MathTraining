package com.mathmaster.app

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Loading, Menu, Range, Training, Results, Progress, CategoryDetail }
enum class Feedback { None, Correct, Wrong }

data class UiSession(
    val id: Long,
    val categoryId: String,
    val total: Int,
    val answered: Int = 0,
    val score: Int = 0,
    val streak: Int = 0,
    val best: Int = 0,
    val fact: Fact? = null,
    val input: String = "",
    val feedback: Feedback = Feedback.None,
    val seq: Int = 0,
    val lastMs: Long = 0L,
    val hint: String? = null
)

data class ResultsUi(
    val categoryId: String,
    val score: Int,
    val total: Int,
    val best: Int,
    val missed: List<String>,
    val improved: Int,
    val introduced: Int
)

data class Totals(val answers: Int, val sessions: Int, val bestStreak: Int)

class TrainerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val engineLazy = lazy { AdaptiveEngine(store) }   // lazy init: created in preload()
    private val engine: AdaptiveEngine get() = engineLazy.value

    var screen by mutableStateOf(Screen.Loading)
        private set
    var selected by mutableStateOf(Categories.all.first())
        private set
    var rangeMin by mutableIntStateOf(1)
        private set
    var rangeMax by mutableIntStateOf(10)
        private set
    var questionCount by mutableIntStateOf(20)
        private set
    var ui by mutableStateOf<UiSession?>(null)
        private set
    var results by mutableStateOf<ResultsUi?>(null)
        private set
    var menuStats by mutableStateOf<Map<String, CatSummary>>(emptyMap())
        private set
    var totals by mutableStateOf(Totals(0, 0, 0))
        private set
    var showQuit by mutableStateOf(false)

    // FIX #8: perf — countdown gate for a new session. While non-null, the training
    // screen renders a scrim + "Ready/Set/Solve!" and no question is shown yet.
    // Set to null when the countdown finishes so the overlay leaves NO trace.
    var countdown by mutableStateOf<String?>(null)
        private set

        // FIX #8: perf — dashboard data loaded off the main thread and cached here.
    data class DashboardData(
        val mastery: Map<String, Triple<Int, Int, Int>>,
        val history: Map<String, List<SessionPoint>>,
        val details: Map<String, List<AdaptiveEngine.FactDetail>>
    )
    var dashboard by mutableStateOf<DashboardData?>(null)
        private set
    // FIX #8: perf — dashboard loading state, so the screen can show a progress bar
    // instead of freezing on a static "Loading…" label.
    var dashboardLoading by mutableStateOf(false)
        private set
    // FIX #8: perf — full-screen reset overlay. Null when idle; a label while running.
    // The overlay composes only while this is non-null, so it leaves no trace.
    var resetting by mutableStateOf<String?>(null)
        private set

    fun loadDashboard() {
        // FIX #8: perf — mark loading so the UI can render a progress bar; clear
        // the flag only after the new dashboard snapshot is committed.
        dashboardLoading = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val mastery = Categories.all.associate { it.id to engine.mastery(it.id) }
                val history = Categories.all.associate { it.id to engine.history(it.id) }
                val details = Categories.all.associate { it.id to engine.details(it.id) }
                DashboardData(mastery, history, details)
            }
            dashboard = data
            dashboardLoading = false
        }
    }

    fun clearDashboard() { dashboard = null }

    private var runtime: SessionRuntime? = null
    private var advanceJob: Job? = null

    // ---------- startup ----------
    suspend fun preload() = withContext(Dispatchers.Default) {
        refreshMenu()
    }

    fun finishLoading() {
        if (screen == Screen.Loading) screen = Screen.Menu
    }

    private fun refreshMenu() {
        menuStats = Categories.all.associate { it.id to engine.summary(it.id) }
        totals = Totals(store.totalAnswers(), store.sessions(), store.bestStreak())
    }

    // FIX #8: perf — dashboard + detail navigation. Data loads asynchronously; the
    // screens render an empty state until `dashboard` is populated.
    fun openProgress() {
        loadDashboard()
        screen = Screen.Progress
    }

    fun openCategoryDetail(c: Category) {
        selected = c
        loadDashboard()
        screen = Screen.CategoryDetail
    }

    // ---------- navigation ----------
    fun openCategory(c: Category) {
        selected = c
        val s = store.loadSettings(c.id)
        rangeMin = (s?.first ?: c.defMin).coerceIn(1, c.hardMax)
        rangeMax = (s?.second ?: c.defMax).coerceIn(rangeMin, c.hardMax)
        questionCount = (s?.third ?: 20).coerceIn(5, 200)
        screen = Screen.Range
    }

    fun onBack() {
        when (screen) {
            Screen.Training -> showQuit = true
            Screen.Range, Screen.Results, Screen.Progress, Screen.CategoryDetail -> toMenu()
            else -> Unit
        }
    }

    fun toMenu() {
        advanceJob?.cancel()
        countdown = null          // FIX #8: kill any in-flight countdown overlay
        showQuit = false
        engine.flush()
        refreshMenu()
        screen = Screen.Menu
    }

    fun quit() = toMenu()

    fun flush() {
        if (engineLazy.isInitialized()) engine.flush()
    }

    override fun onCleared() {
        flush()
        super.onCleared()
    }

    // ---------- range screen ----------
    fun setMin(v: Int) {
        rangeMin = v.coerceIn(1, selected.hardMax)
        if (rangeMax < rangeMin) rangeMax = rangeMin
    }

    fun setMax(v: Int) {
        rangeMax = v.coerceIn(1, selected.hardMax)
        if (rangeMin > rangeMax) rangeMin = rangeMax
    }

    fun setCount(v: Int) {
        questionCount = v.coerceIn(5, 200)
    }

    fun applyPreset(a: Int, b: Int) {
        rangeMin = a.coerceIn(1, selected.hardMax)
        rangeMax = b.coerceIn(rangeMin, selected.hardMax)
    }

    // FIX #8: perf — show a full-screen overlay while the reset runs. The overlay is
    // driven by real work (not a timer): it clears the moment the engine has flushed
    // AND the fresh dashboard snapshot is committed. Because the flag is nulled only
    // at the end, the bar stays visible for as long as the reset actually takes.
    fun resetSelectedCategory() {
        if (resetting != null) return   // ignore double-taps
        resetting = "Resetting Category"
        dashboard = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                engine.reset(selected.id)
                engine.flush()
            }
            // Reload snapshot off the main thread, then commit; only then drop the overlay.
            val data = withContext(Dispatchers.IO) {
                DashboardData(
                    Categories.all.associate { it.id to engine.mastery(it.id) },
                    Categories.all.associate { it.id to engine.history(it.id) },
                    Categories.all.associate { it.id to engine.details(it.id) }
                )
            }
            dashboard = data
            refreshMenu()
            resetting = null
        }
    }

    // FIX #8: perf — wipe all categories; the overlay covers the screen until both the
    // wipe and the reload finish. Same "resetting" flag drives the UI.
    fun resetAllProgress() {
        if (resetting != null) return
        resetting = "Resetting All Progress"
        dashboard = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Categories.all.forEach { engine.reset(it.id) }
                engine.flush()
            }
            val data = withContext(Dispatchers.IO) {
                DashboardData(
                    Categories.all.associate { it.id to engine.mastery(it.id) },
                    Categories.all.associate { it.id to engine.history(it.id) },
                    Categories.all.associate { it.id to engine.details(it.id) }
                )
            }
            dashboard = data
            refreshMenu()
            resetting = null
        }
    }

    // ---------- session ----------
    // FIX #9: cache the last generated fact list per (category, min, max) so repeat
    // sessions with the same range don't rebuild thousands of Fact objects on the
    // main thread. Invalidated automatically when the range changes.
    private var factCache: Triple<String, Int, Int>? = null
    private var factCacheList: List<Fact> = emptyList()

    private fun factsFor(c: Category, lo: Int, hi: Int): List<Fact> {
        val key = Triple(c.id, lo, hi)
        if (factCache == key && factCacheList.isNotEmpty()) return factCacheList
        val list = c.generate(lo, hi)
        factCache = key
        factCacheList = list
        return list
    }

    fun startSession() {
        advanceJob?.cancel()
        val c = selected
        val facts = factsFor(c, rangeMin, rangeMax)
        if (facts.isEmpty()) return
        store.saveSettings(c.id, rangeMin, rangeMax, questionCount)
        store.bumpSessions()

        val rt = SessionRuntime()
        engine.seed(c.id, facts, rt)
        runtime = rt

        // FIX #6: every session gets a brand-new UiSession (answered = 0, new id).
        // Progress is derived ONLY from this object, never stored in a view or in saved
        // instance state, so the bar always starts at 0 and shows only this session.
        ui = UiSession(id = System.nanoTime(), categoryId = c.id, total = questionCount)
        results = null
        showQuit = false
        screen = Screen.Training

        // FIX #8: perf — hold the first question behind a short Ready/Set/Solve!
        // countdown so the user has a beat before the answer section appears.
        countdown = "Ready"
        viewModelScope.launch {
            delay(600); countdown = "Set"
            delay(600); countdown = "Solve!"
            delay(500)
            countdown = null          // clears the overlay — no residual scrim/layer
            nextQuestion()
        }
    }

    private fun nextQuestion() {
        // FIX #10: if the user has left the training screen, do nothing — avoids
        // mutating results after navigation and prevents stray state writes.
        if (screen != Screen.Training) return
        val u = ui ?: return
        val rt = runtime ?: return
        if (u.answered >= u.total) {
            finish()
            return
        }
        val f = engine.pick(u.categoryId, rt, u.answered)
        rt.startedAt = SystemClock.elapsedRealtime()
        ui = u.copy(fact = f, input = "", feedback = Feedback.None, hint = null, seq = u.seq + 1)
    }

    fun onKey(k: String) {
        val u = ui ?: return
        if (u.feedback != Feedback.None || u.input.length >= 12) return
        ui = u.copy(input = u.input + k, hint = null)
    }

    fun onDelete() {
        val u = ui ?: return
        if (u.feedback != Feedback.None || u.input.isEmpty()) return
        ui = u.copy(input = u.input.dropLast(1), hint = null)
    }

    fun submit() {
        val u = ui ?: return
        val f = u.fact ?: return
        val rt = runtime ?: return
        if (u.feedback != Feedback.None || u.input.isEmpty()) return

        val verdict = Grading.grade(f, u.input)
        if (verdict == null) {
            ui = u.copy(input = "", hint = "Check the answer format")
            return
        }
        val ms = SystemClock.elapsedRealtime() - rt.startedAt
        rt.totalMs += ms
        rt.answerCount++
        // FIX #8: perf — move stats write + adaptive recalculation off the main thread.
        // record() is pure in-memory except for its final dirty flag, so it's cheap;
        // but we still dispatch the flush-heavy work at session end.
        val out = engine.record(u.categoryId, f, verdict, ms.toDouble())
        engine.introduceIfReady(u.categoryId, rt)
        store.bumpAnswers()

        if (out.improved) rt.improved++
        // FIX #13: cap the reintroduction queue so it stays O(1)-ish for pick().
        // The oldest pending entry is dropped when the cap is exceeded; it will still
        // reappear naturally through the weighted random pool.
        if (out.requeue) {
            rt.queue.add(f to (u.answered + 1 + (3..6).random()))
            if (rt.queue.size > 32) rt.queue.removeAt(0)
        }
        if (!verdict) rt.missed[f.key] = f

        val streak = if (verdict) u.streak + 1 else 0
        store.updateBest(streak)
        ui = u.copy(
            answered = u.answered + 1,
            score = u.score + if (verdict) 1 else 0,
            streak = streak,
            best = maxOf(u.best, streak),
            feedback = if (verdict) Feedback.Correct else Feedback.Wrong,
            lastMs = ms,
            hint = null
        )
        if ((u.answered + 1) % 5 == 0) engine.flush()

        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            delay(if (verdict) 900L else 1900L)
            nextQuestion()
        }
    }

    private fun finish() {
        val u = ui ?: return
        val rt = runtime ?: return
        val cat = u.categoryId
        val score = u.score
        val total = u.total
        val avgMs = if (rt.answerCount > 0) (rt.totalMs / rt.answerCount).toInt() else 0
        // FIX #8: perf — persist session aggregate + flush stats on Dispatchers.IO so the
        // results screen appears immediately without waiting for disk.
        viewModelScope.launch(Dispatchers.IO) {
            engine.recordSession(cat, score, total, avgMs)
            engine.flush()
        }
        results = ResultsUi(
            categoryId = u.categoryId,
            score = u.score,
            total = u.total,
            best = u.best,
            missed = rt.missed.values.map { it.solution },
            improved = rt.improved,
            introduced = rt.introduced
        )
        refreshMenu()
        screen = Screen.Results
    }
}
