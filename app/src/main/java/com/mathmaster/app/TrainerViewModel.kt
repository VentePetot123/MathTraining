package com.mathmaster.app

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Loading, Menu, Range, Training, Results, Progress, CategoryDetail }
enum class ThemeMode { System, Light, Dark }
enum class Feedback { None, Correct, Wrong }

data class UiSession(
    val id: Long,
    val categoryId: String,
    val total: Int,
    val answered: Int = 0,
    val showAnswer: Boolean = false,
    val score: Int = 0,
    val streak: Int = 0,
    val best: Int = 0,
    val fact: Fact? = null,
    val input: String = "",
    val feedback: Feedback = Feedback.None,
    val seq: Int = 0,
    val lastMs: Long = 0L,
    val hint: String? = null,
    val timedOut: Boolean = false,
    // Question number shown in the header. Only nextQuestion() changes it, so it
    // flips in the same frame the new card starts its entrance animation.
    val questionNo: Int = 1
)

data class ResultsUi(
    val categoryId: String,
    val score: Int,
    val total: Int,
    val best: Int,
    val missed: List<String>,
    val improved: Int,
    val introduced: Int,
    val avgMs: Int // <-- Added this
)

data class Totals(val answers: Int, val sessions: Int, val bestStreak: Int)

class TrainerViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        // FIX #8: perf — minimum time the reset overlay stays visible so the user
        // always perceives it even when the underlying work finishes in <100ms.
        private const val MIN_RESET_OVERLAY_MS = 650L
        // FIX #8: perf — upper bound on how long the nav lock stays engaged if the
        // destination screen never calls `arrivedAt(gen)`. 1500ms is well above any
        // transition (240ms) plus settle time, so it only fires as a failsafe.
        private const val NAV_TIMEOUT_MS = 1500L
        private const val QUESTION_LIMIT_MS = 120_000L
        // Grace period after the entrance animation ends, before the timer
        // starts counting. 1200ms covers typical "read the question" time
        // for arithmetic and factorial questions. Increase if still too tight.
        private const val SETTLE_MS = 150L
    }

    private val store = Store(app)
    private val engineLazy = lazy { AdaptiveEngine(store) }   // lazy init: created in preload()
    private val engine: AdaptiveEngine get() = engineLazy.value

    // FIX #8 — sound effects for correct/wrong feedback. Created once in the VM,
    // pre-loads the two short clips into memory (SoundPool) for zero-latency playback.
    private val soundManager = SoundManager(app)

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
    // FIX #8 — sound on/off toggle. Initial value loaded from persistent prefs so
    // the user's mute choice survives app restarts.
    var soundEnabled by mutableStateOf(store.isSoundEnabled())
        private set
    var themeMode by mutableStateOf(
        runCatching { ThemeMode.valueOf(store.getThemeMode()) }.getOrDefault(ThemeMode.System)
    )
        private set

    /** Pass the *effective* dark state so "System" mode flips to the opposite. */
    fun toggleTheme(currentlyDark: Boolean) {
        themeMode = if (currentlyDark) ThemeMode.Light else ThemeMode.Dark
        store.setThemeMode(themeMode.name)
    }

    fun toggleSound() {
        soundEnabled = !soundEnabled
        // FIX #8 — no confirmation sound on toggle. The icon change (waves vs X)
        // is visual feedback enough; playing a sound just to confirm the sound
        // setting was annoying, especially when muting in public.
        store.setSoundEnabled(soundEnabled)
    }

    // FIX #8: perf — countdown gate for a new session. While non-null, the training
    // screen renders a scrim + "Ready/Set/Solve!" and no question is shown yet.
    // Set to null when the countdown finishes so the overlay leaves NO trace.
    var countdown by mutableStateOf<String?>(null)
        private set

        // FIX #8: perf — dashboard data loaded off the main thread and cached here.
    data class DashboardData(
        val mastery: Map<String, Triple<Int, Int, Int>>,
        val history: Map<String, HistoryRef>,
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

    // FIX #8 — remembers where CategoryDetail was opened from. Controls two things:
    //  • where the back chevron goes (Results vs Progress Dashboard)
    //  • whether the action row (Start Practice / Menu / Reset Category) is shown
    //    (hidden when opened from Results — user just finished a session).
    var categoryDetailFromResults by mutableStateOf(false)
        private set

    // FIX #8 — scroll positions preserved across navigation. The menu grid and the
    // progress dashboard each get their own LazyListState. Because the state lives in
    // the ViewModel (not in a `remember` block inside the composable), it survives
    // screen destruction when the user navigates away and returns.
    //
    // We don't need `saveable` because the ViewModel is itself retained across
    // configuration changes (rotation) — this preserves scroll across rotation too.
    val menuScrollState = androidx.compose.foundation.lazy.grid.LazyGridState()
    val progressScrollState = androidx.compose.foundation.lazy.LazyListState()

    fun loadDashboard() {
        // FIX #8: perf — mark loading so the UI can render a progress bar; clear
        // the flag only after the new dashboard snapshot is committed.
        dashboardLoading = true
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                val mastery = Categories.all.associate { it.id to engine.mastery(it.id) }
                val history = Categories.all.associate { it.id to engine.historyRef(it.id) }
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
    private var timeoutJob: Job? = null
    var questionStartedAt by mutableLongStateOf(0L)
        private set
    var liveTickMs by mutableStateOf(0L)
        private set
    private var isPaused = false
    // Active-time stopwatch for the current question (frozen while the app is away).
    private var timerActive = false
    private var accumulatedMs = 0L      // active time banked before the running segment
    private var segmentStart = 0L       // elapsedRealtime when the running segment began; 0 = frozen

    private fun currentElapsedMs(): Long =
        if (segmentStart == 0L) accumulatedMs
        else (accumulatedMs + (SystemClock.elapsedRealtime() - segmentStart)).coerceAtLeast(0L)

    private fun stopTimer() {
        timerActive = false
        segmentStart = 0L
        accumulatedMs = 0L
    }

    // ---------- startup ----------
    suspend fun preload() = withContext(Dispatchers.Default) {
        // FIX #8: perf — one-time migration for the powers split. Runs on IO before
        // the first menu read so the migrated data is already in place.
        store.migratePowersSplit()
                runCatching { store.migrateHistoryToFiles() }   // can never block startup

        // FIX #8: perf — warm-up pass. Every heavy operation the app does on its
        // first real interaction is exercised here so JIT has compiled it before the
        // user taps anything. Costs ~120ms during loading (invisible to the user);
        // saves ~40-80ms on every "first tap of a new screen" forever after.
        try {
            // 1. Menu stats — needed for the very first Menu render.
            refreshMenu()

            // 2. Dashboard — computed here so tapping Progress/Math Master doesn't
            //    spend 30-50ms mapping Categories.all into DashboardData on the slide.
            dashboard = DashboardData(
                Categories.all.associate { it.id to engine.mastery(it.id) },
                Categories.all.associate { it.id to engine.historyRef(it.id) },
                Categories.all.associate { it.id to engine.details(it.id) }
            )

            // 3. Fact generators — the heaviest pure-Kotlin code. A single call
            //    through each category's generator JITs the `for` loops and `Fact`
            //    constructor. Prevents first "Start Practice" from janking.
            Categories.all.forEach { cat ->
                // Skip FDP since its range is wide; use a small range to keep it fast.
                val (lo, hi) = if (cat.usesFractionKeys) 2 to 5 else 1 to 5
                runCatching { cat.generate(lo, hi) }
            }

            // 4. Total counters — needed for MenuHeader.
            totals = Totals(store.totalAnswers(), store.sessions(), store.bestStreak())
        } catch (t: Throwable) {
            // Warming up is best-effort. A failure here must never block startup.
        }
    }

    fun finishLoading() {
        if (screen == Screen.Loading) screen = Screen.Menu
    }

    private fun refreshMenu() {
        menuStats = Categories.all.associate { it.id to engine.summary(it.id) }
        // FIX #8 — Recompute global totals from the actual saved data every time the
        // menu refreshes. This is the single source of truth for Answers, Sessions,
        // and Best Streak, so both Reset Category and Reset All Progress will
        // immediately reflect the correct decreased numbers.
        engine.recomputeTotalsFromStats()
        totals = Totals(store.totalAnswers(), store.sessions(), store.bestStreak())
    }

    private fun buildDashboard() = DashboardData(
        Categories.all.associate { it.id to engine.mastery(it.id) },
        Categories.all.associate { it.id to engine.historyRef(it.id) },
        Categories.all.associate { it.id to engine.details(it.id) }
    )

    private var dashJob: Job? = null

    // Rebuild off the main thread; publish only if something changed, so unchanged
    // cards skip recomposition. delayMs lets an open-time refresh wait until the
    // screen transition has finished.
    private fun refreshDashboard(delayMs: Long = 0L) {
        dashJob?.cancel()
        dashJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val fresh = withContext(Dispatchers.IO) { buildDashboard() }
            if (fresh != dashboard) dashboard = fresh
        }
    }

    fun openProgress() {
        if (isNavigating) return
        if (dashboard != null) {
            navigateTo(Screen.Progress)       // instant: cached snapshot
            refreshDashboard(delayMs = 450)   // quiet catch-up after the transition
        } else {
            viewModelScope.launch {           // only after a reset / first run
                dashboard = withContext(Dispatchers.IO) { buildDashboard() }
                navigateTo(Screen.Progress)
            }
        }
    }

    fun openCategoryDetail(c: Category, fromResults: Boolean = false) {
        if (isNavigating) return
        selected = c
        categoryDetailFromResults = fromResults
        if (dashboard != null) {
            navigateTo(Screen.CategoryDetail)
            refreshDashboard(delayMs = 450)
        } else {
            viewModelScope.launch {
                dashboard = withContext(Dispatchers.IO) { buildDashboard() }
                navigateTo(Screen.CategoryDetail)
            }
        }
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
    private fun navigateTo(next: Screen, prep: () -> Unit = {}): Boolean {
        // FIX #8: perf — return Boolean so callers know whether the nav actually
        // engaged. If we bail because the lock is up or we're already there, callers
        // must not proceed to mutate state (e.g., startSession's countdown).
        if (isNavigating) return false
        if (screen == next) return true       // already there — treat as success
        isNavigating = true
        navGeneration++
        prep()
        screen = next
        navWatchdog?.cancel()
        navWatchdog = viewModelScope.launch {
            delay(NAV_TIMEOUT_MS)
            if (isNavigating) isNavigating = false
        }
        return true
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
        questionCount = (s?.third ?: 20).coerceIn(1, 200)
    }

    fun onBack() {
        // FIX #8: perf — Back goes through the same guard as taps. If a transition is
        // already running, Back is dropped; that's correct — one input per transition.
        if (isNavigating) return
        when (screen) {
            Screen.Training -> showQuit = true
            // FIX #8 — CategoryDetail backs to Results if it was opened from there,
            // otherwise to Progress Dashboard. Matches the chevron's destination.
            Screen.CategoryDetail -> {
                if (categoryDetailFromResults) {
                    navigateTo(Screen.Results)
                } else {
                    openProgress()
                }
            }
            // FIX #8 — Results backs to Range (the category settings screen) rather than
            // the main Menu. This mirrors the "Back to Category" button and matches the
            // user's mental model: after finishing a session, going back means returning
            // to the setup screen for the same category.
            Screen.Results -> openCategory(selected)
            Screen.Range, Screen.Progress -> toMenu()
            else -> Unit
        }
    }

    fun toMenu() {
        // FIX #8: perf — cancel any in-flight countdown coroutine before navigating.
        // Otherwise the `nextQuestion()` inside it fires after we've left Training,
        // and nextQuestion's guard returns silently — but the user is now on Menu
        // with a dangling runtime.
        advanceJob?.cancel()
        timeoutJob?.cancel()
        countdown = null

        val navigated = navigateTo(Screen.Menu) {
            showQuit = false
            engine.flush()
            refreshMenu()
            refreshDashboard()
        }
        // If nav bailed, still clear local state — the session is dead either way.
        if (!navigated) {
            runtime = null
            ui = null
        }
    }

    fun quit() {
        // FIX #8 — Quit now returns to the Range screen (category settings) instead
        // of the main Menu. This lets the user immediately start another session of
        // the same category without re-navigating through the menu.
        advanceJob?.cancel()
        timeoutJob?.cancel()
        countdown = null
        showQuit = false
        engine.flush()
        refreshMenu()
        // Flush the dashboard refresh in the background
        refreshDashboard()

        val navigated = navigateTo(Screen.Range)
        if (!navigated) {
            runtime = null
            ui = null
        }
    }

    fun flush() {
        if (engineLazy.isInitialized()) engine.flush()
    }

    override fun onCleared() {
        flush()
        // FIX #8 — free the SoundPool. Without this, the audio buffers stay in
        // memory for the whole process lifetime after the VM is destroyed.
        soundManager.release()
        super.onCleared()
    }

    // ---------- range screen ----------
    fun setMin(v: Int) {
        // FIX #8: perf — hard cap at 99 regardless of category hardMax. The number-entry
        // dialog already enforces this, but the guard here prevents any other caller
        // from setting an out-of-range value.
        rangeMin = v.coerceIn(1, selected.hardMax)
        if (rangeMax < rangeMin) rangeMax = rangeMin
    }

    fun setMax(v: Int) {
        rangeMax = v.coerceIn(1, selected.hardMax)
        if (rangeMin > rangeMax) rangeMin = rangeMax
    }

    fun setCount(v: Int) {
        // FIX #8 — allow 1..200 for question count. Previously the min was 5, which
        // silently bumped a user typing "2" up to 5. Now the minimum is 1 and the
        // maximum is 200 (the hard upper bound the dialog enforces).
        questionCount = v.coerceIn(1, 200)
    }

    fun applyPreset(a: Int, b: Int) {
        // FIX #8 — use the category's hardMax (not hardcoded 99) so presets respect
        // the per-category limit (30 for FDP/Powers, 20 for Factorials).
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
                    Categories.all.associate { it.id to engine.historyRef(it.id) },
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
        // FIX #8: — recompute global totals from surviving stats so the header no
        // longer counts answers that were just erased. runReset() already calls
        // refreshMenu() after this block, so the header picks up the new values.
        engine.recomputeTotalsFromStats()
    }

    fun resetAllProgress() = runReset("Resetting All Progress") {
        Categories.all.forEach { engine.reset(it.id) }
        engine.resetTotals()
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
        // FIX #8: perf — check the lock BEFORE mutating any state. If a transition is
        // in flight, we must not touch ui/runtime/countdown because we might bail
        // inside navigateTo and leave dangling state behind.
        if (isNavigating) return
        if (screen != Screen.Range && screen != Screen.Results && screen != Screen.CategoryDetail) return

        advanceJob?.cancel()
        timeoutJob?.cancel()
        val c = selected
        val facts = factsFor(c, rangeMin, rangeMax)
        if (facts.isEmpty()) return
        store.saveSettings(c.id, rangeMin, rangeMax, questionCount)
        // FIX #8 — session count is incremented in finish(), not here. This way
        // abandoning a session (via Quit/Back mid-session) doesn't inflate the
        // "sessions" counter — only completed sessions count.

        val rt = SessionRuntime()
        engine.seed(c.id, facts, rt)
        runtime = rt

        // FIX #6: every session gets a brand-new UiSession (answered = 0, new id).
        // Progress is derived ONLY from this object, never stored in a view or in saved
        // instance state, so the bar always starts at 0 and shows only this session.
        ui = UiSession(id = System.nanoTime(), categoryId = c.id, total = questionCount)
        results = null
        showQuit = false

        // FIX #8: perf — if the nav call bails (lock engaged by a racing nav),
        // roll back the session state so the user doesn't get stuck with dangling
        // runtime/countdown state on the Range screen.
        val navigated = navigateTo(Screen.Training)
        if (!navigated) {
            // Rollback: no session, no countdown, no runtime.
            runtime = null
            ui = null
            countdown = null
            return
        }

        countdown = "Ready"
        viewModelScope.launch {
            delay(600)
            if (screen != Screen.Training) { countdown = null; return@launch }
            countdown = "Set"
            delay(600)
            if (screen != Screen.Training) { countdown = null; return@launch }
            countdown = "Solve!"
            delay(500)
            if (screen != Screen.Training) { countdown = null; return@launch }
            countdown = null
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
        // FIX #8 — timer starts only after the question card animation finishes.
        // The old code set `startedAt` here, which meant the user was penalised
        // for the 380ms entrance animation. Instead, we reset the timer to 0 and
        // let the UI signal back when it's ready (see `onQuestionShown`).
        rt.startedAt = 0L
        questionStartedAt = 0L
        liveTickMs = 0L
        stopTimer()
        timeoutJob?.cancel()
        ui = u.copy(
            fact = f, 
            input = "", 
            feedback = Feedback.None, 
            hint = null, 
            seq = u.seq + 1, 
            timedOut = false,
            showAnswer = false,
            questionNo = u.answered + 1
        )
    }

    // FIX #8 — called by the UI once the entrance animation completes. Starts the
    // session clock for this question, so the user is only timed for their actual
    // response time, not the card animation.
    fun onAppBackground() {
        if (isPaused) return
        isPaused = true
        if (timerActive && segmentStart != 0L) {
            val delta = SystemClock.elapsedRealtime() - segmentStart
            if (delta > 0) accumulatedMs += delta   // negative delta = still in grace; ignore
            segmentStart = 0L
        }
    }

    fun onAppForeground() {
        if (!isPaused) return
        isPaused = false
        if (timerActive && segmentStart == 0L) {
            // Restore segmentStart. If we were in the grace period when paused,
            // questionStartedAt is still in the future → use it, preserving the
            // grace. If we were counting when paused, questionStartedAt is in the
            // past → use "now" so the pause time isn't counted.
            val now = SystemClock.elapsedRealtime()
            val originalStart = questionStartedAt
            segmentStart = if (now < originalStart) originalStart else now
            // Also guarantee accumulatedMs is never negative — if a stray
            // onAppBackground during the grace window subtracted a negative
            // delta, clamp it back to 0.
            if (accumulatedMs < 0L) accumulatedMs = 0L
        }
    }

    fun onQuestionShown() {
        val rt = runtime ?: return
        if (rt.startedAt != 0L) return
        val seq = ui?.seq ?: return
        val now = SystemClock.elapsedRealtime()
        // Grace period: starts AFTER the animation, and is guaranteed by a
        // FUTURE segmentStart — currentElapsedMs() clamps to 0 until then.
        // We do NOT consult isPaused here: if the app is paused, onAppBackground
        // would have already frozen any running timer. Setting segmentStart to
        // the future startAt unconditionally, and letting onAppForeground() /
        // onAppBackground() adjust it, is strictly safer than branching on
        // isPaused in this method.
        val startAt = now + SETTLE_MS
        rt.startedAt = startAt
        questionStartedAt = startAt
        accumulatedMs = 0L
        segmentStart = startAt            // ← ALWAYS the future startAt, never 0
        timerActive = true
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            while (true) {
                val elapsed = currentElapsedMs()
                liveTickMs = elapsed
                val left = QUESTION_LIMIT_MS - elapsed
                if (left <= 0L) break
                delay(minOf(left, 33L))
            }
            timeUp(seq)
        }
    }


    // FIX #8: perf — these methods only touch `u.input`. Because `UiSession` is a
    // data class with copy(), the whole `ui` value changes; but Compose's snapshot
    // system detects unchanged fields and skips their compositions. So the header,
    // progress bar, keypad, etc. never re-render — only the QuestionCard's input
    // box does. This is inherently how Compose works; the previous version also
    // worked this way, but the `TrainingScreen` was structured such that all the
    // siblings got re-diffed. With the split into HeaderBar/ProgressBarRow/Keypad,
    // each field change only recomposes the affected sub-tree.
    fun onKey(k: String) {
        val u = ui ?: return
        if (u.feedback != Feedback.None) return
        if (u.input.length >= 160) return
        val kind = u.fact?.kind
        
        // Only the symbols that belong to this question type can be typed.
        if (k == "." && kind != Kind.DEC && kind != Kind.PCT) return
        if (k == "/" && kind != Kind.FRAC) return
        if (k == "%" && kind != Kind.PCT) return
        
        // Each of . / % at most once; extra presses are ignored.
        if ((k == "." || k == "/" || k == "%") && u.input.contains(k)) return
        
        // FIX: No characters can be typed after a % sign.
        if (u.input.endsWith("%")) return
        
        // "-" only as the very first character (Integers).
        if (k == "-" && u.input.isNotEmpty()) return
        
        ui = u.copy(input = u.input + k, hint = null)
    }

    fun onDelete() {
        val u = ui ?: return
        if (u.feedback != Feedback.None) return
        if (u.input.isEmpty()) return
        ui = u.copy(input = u.input.dropLast(1), hint = null)
    }

    fun onClear() {
        val u = ui ?: return
        if (u.feedback != Feedback.None) return
        if (u.input.isEmpty()) return
        ui = u.copy(input = "", hint = null)
    }

    private fun timeUp(seq: Int) {
        val u = ui ?: return
        val f = u.fact ?: return
        val rt = runtime ?: return
        if (screen != Screen.Training || u.seq != seq || u.feedback != Feedback.None) return
        
        timeoutJob?.cancel()
        stopTimer()
        val ms = QUESTION_LIMIT_MS
        rt.totalMs += ms
        rt.answerCount++
        val out = engine.record(u.categoryId, f, false, ms.toDouble())
        engine.introduceIfReady(u.categoryId, rt)

        if (out.improved) rt.improved++
        if (out.requeue) {
            rt.queue.add(f to (u.answered + 1 + (3..6).random()))
            if (rt.queue.size > 32) rt.queue.removeAt(0)
        }
        rt.missed[f.key] = f

        if (soundEnabled) {
            soundManager.playWrong()
        }

        ui = u.copy(
            answered = u.answered + 1,
            score = u.score,
            streak = 0,
            best = maxOf(u.best, 0),
            feedback = Feedback.Wrong,
            lastMs = ms,
            hint = null,
            timedOut = true
        )
        if ((u.answered + 1) % 5 == 0) {
            viewModelScope.launch(Dispatchers.IO) { engine.flush() }
        }

        advanceJob?.cancel()
        advanceJob = viewModelScope.launch {
            val answerLen = f.answer.length
            val delayMs = (1900L + 60L * answerLen).coerceAtMost(6000L)
            delay(delayMs)
            if (screen == Screen.Training) nextQuestion()
        }
    }

    fun submit() {
        val u = ui ?: return
        val f = u.fact ?: return
        val rt = runtime ?: return
        if (u.feedback != Feedback.None || u.input.isEmpty()) return

        val problem = Grading.check(f, u.input)
        if (problem != null) {
            ui = u.copy(input = "", hint = problem)
            return
        }

        val verdict = Grading.grade(f, u.input)
        if (verdict == null) {
            ui = u.copy(input = "", hint = "Check the answer format")
            return
        }
        // FIX #8 — guard against a zero timer (user submits before animation done).
        // If startedAt is still 0, treat elapsed as 0 (best-effort; the answer is
        // still graded, just not timed).
        val ms = if (timerActive) currentElapsedMs() else 0L
        stopTimer()
        rt.totalMs += ms
        rt.answerCount++
        // FIX #8: perf — move stats write + adaptive recalculation off the main thread.
        // record() is pure in-memory except for its final dirty flag, so it's cheap;
        // but we still dispatch the flush-heavy work at session end.
        val out = engine.record(u.categoryId, f, verdict, ms.toDouble())
        engine.introduceIfReady(u.categoryId, rt)
        // FIX #8 — Do NOT manually bump global counters here. They are now derived
        // automatically from the actual category stats whenever the menu refreshes.

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

        // Track the true best streak for this session (ignores engine slow-penalties)
        if (streak > rt.sessionBestStreak) {
            rt.sessionBestStreak = streak
        }

        // Update the global best if this is a new record
        if (streak > store.bestStreak()) {
            store.updateBest(streak)
            // CRITICAL: Flush to disk immediately so the new record
            // isn't lost if the app is closed before the next session flushes.
            store.flushTotals()
        }ak = if (verdict) u.streak + 1 else 0
        // FIX #8 — Do NOT update best streak here. It is derived automatically.

        // FIX #8 — play the appropriate sound effect (if enabled). Fired BEFORE the
        // UI update so the sound and visual feedback feel synchronized.
        if (soundEnabled) {
            if (verdict) soundManager.playCorrect() else soundManager.playWrong()
        }

        timeoutJob?.cancel()
        ui = u.copy(
            answered = u.answered + 1,
            showAnswer = true,
            score = u.score + if (verdict) 1 else 0,
            streak = streak,
            best = maxOf(u.best, streak),
            feedback = if (verdict) Feedback.Correct else Feedback.Wrong,
            lastMs = ms,
            hint = null
        )
        // FIX #8 — move the periodic flush off the main thread. Under heavy use (many
        // sessions accumulated), engine.flush() serializes every dirty category to JSON
        // and writes to SharedPreferences, which can take 100-150ms. Running it on the
        // main thread here would cause a one-frame hitch every 5th answer. Dispatching
        // to IO keeps the UI perfectly smooth while the flush happens in the background.
        if ((u.answered + 1) % 5 == 0) {
            viewModelScope.launch(Dispatchers.IO) { engine.flush() }
        }

        advanceJob?.cancel()
        timeoutJob?.cancel()
        advanceJob = viewModelScope.launch {
            // FIX #8 — dynamic delay based on answer length.
            //  • Correct: 900ms base + 40ms per character (cap at ~2.5s). Correct
            //    answers don't need much reading time, but a very long correct
            //    decimal/fraction should still be visible for a beat longer.
            //  • Wrong: 1900ms base + 60ms per character (cap at ~6s). Wrong answers
            //    need enough time to actually read the correct answer and understand
            //    the mistake — more characters = more time.
            val answerLen = f.answer.length
            val delayMs = if (verdict) {
                (900L + 40L * answerLen).coerceAtMost(2500L)
            } else {
                (1900L + 60L * answerLen).coerceAtMost(6000L)
            }
            delay(delayMs)
            // FIX #8: perf — if the user has left the Training screen (via Quit or Back)
            // while this delay was running, nextQuestion() must not proceed. It now
            // checks `screen` itself, so no state can be mutated after the user left.
            if (screen == Screen.Training) nextQuestion()
        }
    }

    private fun finish() {
        if (screen != Screen.Training) return
        val u = ui ?: return
        val rt = runtime ?: return

        // FIX #8 — play the session-complete fanfare.
        if (soundEnabled) {
            soundManager.playSessionComplete()
        }
        val cat = u.categoryId
        val score = u.score
        val total = u.total
        val avgMs = if (rt.answerCount > 0) (rt.totalMs / rt.answerCount).toInt() else 0
        // FIX #8 — The session is recorded in engine.recordSession() below. We no
        // longer manually bump the global session counter because refreshMenu() now
        // derives it from the actual category history lists. This ensures "Reset
        // Category" properly decreases the global session count.
        viewModelScope.launch(Dispatchers.IO) {
            engine.recordSession(cat, score, total, avgMs)
            engine.flush()
            val fresh = buildDashboard()
            if (fresh != dashboard) dashboard = fresh
        }
        results = ResultsUi(
            categoryId = u.categoryId,
            score = u.score,
            total = u.total,
            best = rt.sessionBestStreak, // Use the true session streak
            missed = rt.missed.values.map { it.solution },
            improved = rt.improved,
            introduced = rt.introduced,
            avgMs = avgMs // <-- Added this
        )
        refreshMenu()
        // FIX #8: perf — if the nav bails (lock engaged), don't leave `results`
        // dangling; the user is somewhere else and doesn't need a Results screen.
        val navigated = navigateTo(Screen.Results)
        if (!navigated) {
            results = null
        }
    }
}
