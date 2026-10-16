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

enum class Screen { Loading, Menu, Range, Training, Results }
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
            Screen.Range, Screen.Results -> toMenu()
            else -> Unit
        }
    }

    fun toMenu() {
        advanceJob?.cancel()
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

    fun resetSelectedCategory() {
        engine.reset(selected.id)
        refreshMenu()
    }

    // ---------- session ----------
    fun startSession() {
        advanceJob?.cancel()
        val c = selected
        val facts = c.generate(rangeMin, rangeMax)
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
        nextQuestion()
    }

    private fun nextQuestion() {
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
        val out = engine.record(u.categoryId, f, verdict, ms.toDouble())
        engine.introduceIfReady(u.categoryId, rt)
        store.bumpAnswers()

        if (out.improved) rt.improved++
        if (out.requeue) rt.queue.add(f to (u.answered + 1 + (3..6).random()))  // reintroduce soon
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
        engine.flush()
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
