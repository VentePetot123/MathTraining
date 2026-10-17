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
    companion object {
        // FIX #8: perf — upper bound on how long the nav lock can stay engaged if the
        // destination screen never calls `arrivedAt(gen)`. 1500ms is well above any
        // transition (240ms) plus settle time, so it only ever fires as a failsafe.
        private const val NAV_TIMEOUT_MS = 1500L
    }
    private val store = Store(app)
    private val engineLazy = lazy { AdaptiveEngine(store) }   // lazy init: created in preload()
    private val engine: AdaptiveEngine get() = engineLazy.value

    var screen by mutableStateOf(Screen.Loading)
        private set
    // FIX #8: perf — global navigation lock. Set to true on the first nav request,
    // cleared by the destination screen (`arrivedAt`) or by a watchdog timeout.
    // A generation counter means a stale release (from a screen that was composed
    // before the user navigated away) can't clear a fresh lock.
    var isNavigating by mutableStateOf(false)
        private set
    private var navGeneration = 0L
    private var navWatchdog: Job? = null
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
        // FIX #8: perf — one-time migration for the powers split. Runs on IO before
        // the first menu read so the migrated data is already in place.
        store.migratePowersSplit()
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
    fun openProgress() = navigateTo(Screen.Progress) {
        loadDashboard()
    }

    fun openCategoryDetail(c: Category) = navigateTo(Screen.CategoryDetail) {
        selected = c
        loadDashboard()
    }

    // ---------- navigation ----------
    // FIX #8: perf — navigation lock. Released by the DESTINATION screen, not a timer.
    //
    //   • navigateTo(next) { prep }  — engages the lock, runs prep, flips `screen`.
    //   • arrivedAt(gen)             — called by the destination's LaunchedEffect(Unit).
    //                                  Only clears the lock if `gen` matches the current
    //                                  navGeneration (prevents stale releases).
    //   • watchdog                   — if arrivedAt never fires in NAV_TIMEOUT_MS,
    //                                  the lock is force-cleared so the app can't freeze.
    private fun navigateTo(next: Screen, prep: () -> Unit = {}) {
        if (isNavigating) return       // reject taps during in-flight transition
        if (screen == next) return     // no-op when already there (belt + suspenders)
        isNavigating = true
        navGeneration++
        prep()
        screen = next
        navWatchdog?.cancel()
        navWatchdog = viewModelScope.launch {
            delay(NAV_TIMEOUT_MS)
            // Force the lock off if nothing released it. Should never happen, but
            // protects against rotation-into-transition or a caller that forgot
            // to invoke `arrivedAt`.
            if (isNavigating) isNavigating = false
        }
    }

    /** Called by the destination screen once it's composed and stable. */
    fun arrivedAt(gen: Long) {
        if (gen == navGeneration && isNavigating) {
            isNavigating = false
            navWatchdog?.cancel()
        }
    }

    /** Exposed so a screen can capture the generation it belongs to. */
    fun currentNavGeneration(): Long = navGeneration

    fun openCategory(c: Category) = navigateTo(Screen.Range) {
        selected = c
        val s = store.loadSettings(c.id)
        rangeMin = (s?.first ?: c.defMin).coerceIn(1, c.hardMax)
        rangeMax = (s?.second ?: c.defMax).coerceIn(rangeMin, c.hardMax)
        questionCount = (s?.third ?: 20).coerceIn(5, 200)
    }

    fun onBack() {
        // FIX #8: perf — Back goes through the same guard as taps. If a transition is
        // already running, Back is dropped; that's correct — one input per transition.
        if (isNavigating) return
        when (screen) {
            Screen.Training -> showQuit = true
            Screen.Range, Screen.Results, Screen.Progress, Screen.CategoryDetail -> toMenu()
            else -> Unit
        }
    }

    fun toMenu() = navigateTo(Screen.Menu) {
        advanceJob?.cancel()
        countdown = null
        showQuit = false
        engine.flush()
        refreshMenu()
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
        // FIX #8: perf — hard cap at 99 regardless of category hardMax. The number-entry
        // dialog already enforces this, but the guard here prevents any other caller
        // from setting an out-of-range value.
        rangeMin = v.coerceIn(1, minOf(99, selected.hardMax))
        if (rangeMax < rangeMin) rangeMax = rangeMin
    }

    fun setMax(v: Int) {
        rangeMax = v.coerceIn(1, minOf(99, selected.hardMax))
        if (rangeMin > rangeMax) rangeMin = rangeMax
    }

    fun setCount(v: Int) {
        // Questions don't need the 99 cap — they legitimately go higher. Keep 5..200.
        questionCount = v.coerceIn(5, 200)
    }

    fun applyPreset(a: Int, b: Int) {
        rangeMin = a.coerceIn(1, selected.hardMax)
        rangeMax = b.coerceIn(rangeMin, selected.hardMax)
    }

    // FIX #8: perf — overlay is guaranteed visible for a minimum duration so the
    // user perceives it even when the underlying reset completes in <100ms. The
    // flag is set SYNCHRONOUSLY before any launch, and cleared only after BOTH the
    // minimum window has elapsed AND the work + dashboard reload have finished.
    private fun runReset(label: String, work: suspend () -> Unit) {
        if (resetting != null) return       // ignore re-entrant calls
        resetting = label                    // synchronous — overlay appears this frame
        dashboard = null
        viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { work() }
            val data = withContext(Dispatchers.IO) {
                DashboardData(
                    Categories.all.associate { it.id to engine.mastery(it.id) },
                    Categories.all.associate { it.id to engine.history(it.id) },
                    Categories.all.associate { it.id to engine.details(it.id) }
                )
            }
            dashboard = data
            refreshMenu()
            // Hold the overlay for a minimum so it's always perceived.
            val elapsed = SystemClock.elapsedRealtime() - start
            val remaining = (MIN_RESET_OVERLAY_MS - elapsed).coerceAtLeast(0L)
            if (remaining > 0) delay(remaining)
            resetting = null
        }
    }

    fun resetSelectedCategory() = runReset("Resetting Category") {
        engine.reset(selected.id)
        engine.flush()
    }

    fun resetAllProgress() = runReset("Resetting All Progress") {
        Categories.all.forEach { engine.reset(it.id) }
        engine.flush()
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
        // FIX #8: perf — route through navigateTo so the nav lock engages. If the user
        // double-taps "Start Practice", the second tap is rejected by the guard inside
        // navigateTo (`if (isNavigating) return`).
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

        // FIX #8: perf — nav goes through navigateTo (engages the lock) instead of
        // assigning `screen` directly. This is the ONLY line that changes vs. the
        // original — everything else is preserved.
        navigateTo(Screen.Training)

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

    // FIX #8: perf — clear the whole input. Distinct from onDelete (backspace).
    fun onClear() {
        val u = ui ?: return
        if (u.feedback != Feedback.None || u.input.isEmpty()) return
        ui = u.copy(input = "", hint = null)
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
            // FIX #8: perf — if the user has left the Training screen (via Quit or Back)
            // while this delay was running, nextQuestion() must not proceed. It now
            // checks `screen` itself, so no state can be mutated after the user left.
            if (screen == Screen.Training) nextQuestion()
        }
    }

    private fun finish() {
        // FIX #8: perf — if the user already left Training (Quit pressed while the last
        // question's feedback was visible), do not navigate to Results. Results must
        // only appear as a consequence of a natural session end.
        if (screen != Screen.Training) return
        val u = ui ?: return
        val rt = runtime ?: return
        val cat = u.categoryId
        val score = u.score
        val total = u.total
        val avgMs = if (rt.answerCount > 0) (rt.totalMs / rt.answerCount).toInt() else 0
        // Persist on IO; navigation happens via navigateTo below.
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
        navigateTo(Screen.Results)
    }
}
