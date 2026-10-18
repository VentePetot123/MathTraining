package com.mathmaster.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// =====================================================================
// Loading screen
// =====================================================================
@Composable
fun LoadingScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    val progress = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.6f) }

    // The bar always runs ~1.8s; stats are preloaded on a background thread in parallel.
    LaunchedEffect(Unit) {
        coroutineScope {
            launch { logoScale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 200f)) }
            val preload = async { vm.preload() }
            // FIX #8: perf — 1400ms gives the app time to fully warm up (JIT
            // compilation, first-frame GPU allocation, menu stats load) BEFORE the
            // Loading → Menu transition begins. This eliminates the tiny hitch that
            // was showing up mid-slide on the very first transition after cold start.
            // `preload.await()` still gates on real work; this is just an upper bound
            // on how long we wait for the animation to look smooth.
            // FIX #8: perf — 1600ms covers the pre-warm pass (JIT compilation of
            // every generator, screen, and card) plus the settle delay. The bar
            // moves smoothly for the entire warm-up, so the user never sees a
            // "frozen loading bar" moment.
            progress.animateTo(1f, tween(1600, easing = LinearEasing))
            preload.await()
            // FIX #8: perf — 400ms settle after preload finishes. The preload JITs
            // hundreds of `associate` calls and 10 fact generators; without a settle,
            // the first Menu frame is still paying JIT tax, and the very next tap
            // (Math Master) inherits it. This delay is invisible — the loading bar
            // is already full by the time it starts.
            delay(400)
            vm.finishLoading()
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.background).padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LogoMark(120.dp, Modifier.graphicsLayer { scaleX = logoScale.value; scaleY = logoScale.value })
        Spacer(Modifier.height(24.dp))
        Txt("Math Master", 30, FontWeight.ExtraBold, c.onBackground)
        Txt("Train your mind. Adaptively.", 14, FontWeight.Normal, c.muted)
        Spacer(Modifier.height(40.dp))
        GradientProgressBar(progress.value, animate = false, height = 10.dp)

        // FIX #8: perf — off-screen warm-up mount. Every heavy child composable the
        // user will hit after loading runs here once (invisible: rendered into a
        // 1x1 transparent Box that sits behind the loading content). By the time the
        // user taps Math Master or a category, the composable JIT table is already
        // populated — the first tap animates smoothly instead of stuttering.
        //
        // The `alpha(0f)` makes it invisible; the `size(1.dp)` makes it take no
        // layout space. Compose still runs the composition pass on these, which is
        // exactly what we want for warm-up.
        Box(Modifier.size(1.dp).alpha(0f)) {
            // Warm up the heaviest child composable.
            CategoryProgressSection(
                cat = Categories.all.first(),
                mastery = Triple(0, 0, 0),
                history = emptyList(),
                details = emptyList(),
                onOpen = null,
                showHeader = false
            )
        }
    }
}

// =====================================================================
// Menu (categories) screen
// =====================================================================
@Composable
fun MenuScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(c.background),
        // FIX #8 — use the VM-held scroll state so the scroll position survives
        // navigating away and returning. Picking 3ⁿ at the bottom, backing out, and
        // coming back keeps you at the bottom — no re-scroll needed.
        state = vm.menuScrollState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { MenuHeader(vm) { vm.openProgress() } }
        itemsIndexed(Categories.all) { index, cat ->
            CategoryCard(index, cat, vm.menuStats[cat.id]) { vm.openCategory(cat) }
        }
    }
}

@Composable
fun MenuHeader(vm: TrainerViewModel, onOpenProgress: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val t = vm.totals
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .clip(shape)
            .background(AppTheme.colors.accent)
            .bounceClick(shape, 0.dp, onClick = onOpenProgress)   // FIX #8: no second shadow layer
            .clip(shape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Txt("Math Master", 28, FontWeight.ExtraBold, Color.White)
        Txt("Tap to view your progress", 14, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
        val summary = if (t.answers > 0)
            "${t.answers} answers · ${t.sessions} sessions · best streak ${t.bestStreak}"
        else "Your progress will appear here"
        Txt(summary, 12, FontWeight.Medium, Color.White.copy(alpha = 0.85f))
    }
}

@Composable
fun CategoryCard(index: Int, cat: Category, s: CatSummary?, onClick: () -> Unit) {
    // FIX #8: perf — vertical Column layout (symbol → name → stat) so the three rows
    // never collide. The name is capped at 2 lines with ellipsis so the stat row
    // ("Not practiced yet" / "% acc · N facts") is always visible beneath it, even
    // for the longest category name ("Fractions, Decimals, Percentages"). Card height
    // stays 148dp; the extra breathing room comes from constraining the name to 2 lines.
    Column(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .bounceClick(RoundedCornerShape(22.dp), 8.dp, onClick = onClick)
            .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Txt(cat.symbol, 30, FontWeight.Bold, Color.White)

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = cat.name,
                color = Color.White,
                // FIX #8 — title is now 16sp ExtraBold (was 14sp Bold) so it reads as
                // a proper header. lineHeight set to 18sp gives a clean compact lead.
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
            // FIX #8 — small spacer for breathing room between title and stat.
            Spacer(Modifier.height(2.dp))
            val stat = if (s == null || s.attempts == 0) "Not practiced yet"
            else "${s.accuracyPct}% acc · ${s.facts} facts"
            Txt(stat, 11, FontWeight.Medium, Color.White.copy(alpha = 0.85f))
        }
    }
}

// =====================================================================
// Category / range screen
// =====================================================================
@Composable
fun RangeScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    val cat = vm.selected
    val headerShape = RoundedCornerShape(22.dp)

    // FIX #8: perf — compact, no-scroll layout. Every element is intentionally
    // smaller than the shared variants so the whole screen fits on a mid-range
    // phone without vertical scroll.
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(rememberScrollState())   // no-op when content fits
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- Compact gradient header ----
        // FIX #8 — the whole header is now a tappable back target. Tapping anywhere
        // on the gradient (symbol, name, blurb, or the chevron itself) returns to the
        // Menu. This matches ProgressHeader's behavior on the Progress Dashboard,
        // giving a consistent back affordance across screens.
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(6.dp, headerShape)
                .clip(headerShape)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
                .bounceClick(headerShape, 0.dp, onClick = { vm.toMenu() })
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(cat.symbol, 26, FontWeight.Bold, Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // FIX #8 — Title: ExtraBold, with a tight lineHeight so 2-line names
                // like "Fractions, Decimals, Percentages" stack cleanly.
                Txt(
                    cat.name,
                    18,
                    FontWeight.ExtraBold,
                    Color.White,
                    lineHeight = 20 // 18sp font → 20sp line height (compact but readable)
                )
                // FIX #8 — Blurb: normal weight, tight line height. The Column's
                // spacedBy(2.dp) adds a clear but small gap between title and blurb.
                Txt(
                    cat.blurb,
                    11,
                    FontWeight.Normal,
                    Color.White.copy(alpha = 0.9f),
                    lineHeight = 13
                )
            }
            // Visual affordance only — the ripple and click are on the parent Row.
            Box(
                Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Txt("‹", 34, FontWeight.Bold, Color.White, align = TextAlign.Center)
            }
        }

        // Rounding rule — shown first so it can't be missed (Fractions/Decimals/% only)
        if (cat.id == "fdp") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.accentA.copy(alpha = 0.14f))
                    .border(1.dp, c.accentA.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Txt("ⓘ", 20, FontWeight.Bold, c.accentA)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt("Rounding rule", 13, FontWeight.Bold, c.onBackground)
                    Txt(
                        "Infinite decimals (like 1/3): type 4 decimal places, e.g. 0.3333. " +
                            "Percentages: 2 decimal places, e.g. 33.33%.",
                        12, FontWeight.Medium, c.muted,
                        lineHeight = 15
                    )
                }
            }
        }

        // ---- Number range card ----
        CompactCard {
            Txt(if (cat.usesFractionKeys) "Denominator range" else "Number range", 15, FontWeight.Bold)
            // FIX #8 — hardMax passed so the dialog's title says "Enter value (1–30)"
            // for categories with a low limit (FDP, Powers, Factorials), and "Enter
            // value (1–99)" for the arithmetic categories. No more typing 100+ for
            // a category that only supports 30.
            Stepper("From", vm.rangeMin, 1, hardMax = cat.hardMax) { vm.setMin(it) }
            Stepper("To", vm.rangeMax, 1, hardMax = cat.hardMax) { vm.setMax(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cat.presets().forEach { (a, b) ->
                    SoftButton("$a–$b", Modifier.weight(1f), size = 12) { vm.applyPreset(a, b) }
                }
            }
        }

        // ---- Questions per session card ----
        CompactCard {
            Txt("Questions per session", 15, FontWeight.Bold)
            // FIX #8 — allow 3-digit input up to 200 for question count, while the
            // From/To range steppers keep the default 2-digit / 99 cap.
            Stepper(
                label = "Questions",
                value = vm.questionCount,
                step = 5,
                onChange = { vm.setCount(it) },
                maxDigits = 3,
                maxValue = 200
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 20, 30, 50).forEach { n ->
                    SoftButton("$n", Modifier.weight(1f), size = 12) { vm.setCount(n) }
                }
            }
        }

        // ---- Compact stat line ----
        val s = vm.menuStats[cat.id]
        val statText = if (s == null || s.attempts == 0) "No practice data yet for this category"
        else "${s.attempts} attempts · ${s.accuracyPct}% accuracy"
        Txt(statText, 11, FontWeight.Medium, c.muted, Modifier.fillMaxWidth(), TextAlign.Center)

        // FIX #8: perf — single action row.

        // FIX #8: perf — single action row. The previous "Start practice" at the top
        // was a duplicate of the one in the bottom row; removed to remove ambiguity
        // and to reclaim vertical space.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Menu", Modifier.weight(1f), size = 14) { vm.toMenu() }
            GradientButton("Start Practice", Modifier.weight(1f)) { vm.startSession() }
        }
    }

}

@Composable
private fun Text2(s: String) = Txt(s, 15, FontWeight.Normal, AppTheme.colors.onBackground)

// =====================================================================
// Training screen
// =====================================================================
@Composable
fun TrainingScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    val u = vm.ui
    if (u == null) {
        Box(Modifier.fillMaxSize().background(c.background))
        return
    }
    val cat = Categories.byId(u.categoryId)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.background)) {
            // FIX #8: perf — header observes only the fields it renders. Because
            // Compose skips recomposition when the values haven't changed, typing a
            // digit (which only changes u.input) does NOT recompose the header.
            HeaderBar(
                answered = u.answered,
                total = u.total,
                score = u.score,
                streak = u.streak,
                onQuit = { vm.showQuit = true }
            )

            ProgressBarRow(
                sessionId = u.id,
                answered = u.answered,
                total = u.total
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .zIndex(1f)
                    .padding(16.dp)
            ) {
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp).verticalScroll(rememberScrollState())
                ) {
                    // FIX #8: perf — the question card re-renders only when its own
                    // fields change. The keypad's `enabled` value is passed in as a
                    // primitive so it's a stability anchor.
                    key(u.id) {
                        // FIX #8 — fire the "question is now visible" callback after
                        // the entrance animation finishes (see LaunchedEffect inside
                        // QuestionCard). This ensures the timer measures only the
                        // user's thinking time, not the animation duration.
                        QuestionCard(u) { vm.onQuestionShown() }
                    }
                }
            }

            // FIX #8: perf — the keypad now takes only `enabled` (a Boolean) and the
            // stable VM method references. It does NOT read `u.input`, so typing a
            // digit does NOT recompose the keypad itself. It only re-renders when
            // feedback starts/ends (enabled flips).
            Keypad(
                fractionKeys = cat.usesFractionKeys,
                enabled = u.feedback == Feedback.None,
                onKey = vm::onKey,
                onDelete = vm::onDelete,
                onClear = vm::onClear,
                onSubmit = vm::submit
            )
        }

        if (vm.showQuit) {
            AlertDialog(
                onDismissRequest = { vm.showQuit = false },
                containerColor = c.surface,
                title = { Text2("Leave this session?") },
                text = { Text2("Answers so far are already saved.") },
                confirmButton = {
                    TextButton(onClick = { vm.quit() }) { Txt("Leave", 15, FontWeight.Bold, c.error) }
                },
                dismissButton = {
                    TextButton(onClick = { vm.showQuit = false }) { Txt("Keep going", 15, FontWeight.Medium, c.muted) }
                }
            )
        }

        vm.countdown?.let { CountdownOverlay(it) }
    }
}

// FIX #8: perf — header is its own composable with primitive params. Typing a digit
// changes `input`, not `answered`/`score`/`streak`, so Compose skips this entirely.
@Composable
private fun HeaderBar(answered: Int, total: Int, score: Int, streak: Int, onQuit: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SoftButton("✕ Quit", size = 14, onClick = onQuit)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Txt("Q ${minOf(answered + 1, total)}/$total", 17, FontWeight.Bold)
            Txt("Score $score · Streak $streak 🔥", 12, FontWeight.Medium, c.muted)
        }
    }
}

// FIX #8: perf — progress bar reads only answered/total. Keystrokes don't touch these.
@Composable
private fun ProgressBarRow(sessionId: Long, answered: Int, total: Int) {
    key(sessionId) {
        val progress = remember(answered, total) { answered.toFloat() / total }
        GradientProgressBar(
            progress = progress,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun QuestionCard(u: UiSession, onShown: () -> Unit = {}) {
    val c = AppTheme.colors
    val fact = u.fact ?: return
    val shape = RoundedCornerShape(26.dp)

    val entrance = remember(u.seq) { Animatable(0f) }
    val shake = remember(u.seq) { Animatable(0f) }
    val tint = remember(u.seq) { Animatable(0f) }

    // Animation: question card entrance (fade + slight upward slide)
    // FIX #8 — after the entrance animation completes, notify the caller so the
    // timer starts counting from this moment, not from when the question was picked.
    LaunchedEffect(u.seq) {
        entrance.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        onShown()
    }

    // Animation: color pulse on feedback, shake on wrong answers
    LaunchedEffect(u.seq, u.feedback) {
        when (u.feedback) {
            Feedback.Correct -> {
                tint.snapTo(0.4f)
                tint.animateTo(0.12f, tween(600))
            }
            Feedback.Wrong -> {
                tint.snapTo(0.4f)
                launch { tint.animateTo(0.12f, tween(600)) }
                for (x in floatArrayOf(-16f, 16f, -12f, 12f, -6f, 6f, 0f)) shake.animateTo(x, tween(50))
            }
            Feedback.None -> Unit
        }
    }

    val feedbackColor = if (u.feedback == Feedback.Wrong) c.error else c.success
    val promptSize = remember(fact.key) { if (fact.prompt.length > 14) 30 else 44 }

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 48.dp.toPx()
                translationX = shake.value.dp.toPx()
            }
            .shadow(10.dp, shape)
            .clip(shape)
            .background(c.surface)
            .drawBehind { drawRect(feedbackColor.copy(alpha = tint.value)) }
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Txt(fact.prompt, promptSize, FontWeight.ExtraBold, c.onBackground, Modifier.fillMaxWidth(), TextAlign.Center)

        val borderColor = when (u.feedback) {
            Feedback.Correct -> c.success
            Feedback.Wrong -> c.error
            Feedback.None -> c.accentA.copy(alpha = 0.5f)
        }
        
        // Answer box: 20 digits per line (max 160 = 8 lines). Text shrinks as the answer
        // grows, line spacing is constant, and the box grows downward.
        val len = u.input.length
        val inputSize = when {
            len <= 8 -> 30
            len <= 11 -> 24
            len <= 16 -> 20
            len <= 20 -> 18
            len <= 40 -> 16
            len <= 80 -> 14
            else -> 13
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surfaceAlt)
                .border(2.dp, borderColor, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (u.input.isEmpty()) {
                Txt("?", 28, FontWeight.Bold, c.muted)
            } else {
                Text(
                    text = u.input.chunked(20).joinToString("\n"),
                    color = c.onBackground,
                    fontSize = inputSize.sp,
                    lineHeight = (inputSize * 1.35f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Result strip: answer on top (green/red), time below at half size.
        Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
            when (u.feedback) {
                Feedback.None -> {
                    if (u.hint != null) {
                        Txt(u.hint, 14, FontWeight.Medium, c.warning)
                    }
                }
                else -> {
                    val ok = u.feedback == Feedback.Correct
                    val resultColor = if (ok) c.success else c.error
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FeedbackMark(ok, u.seq, Modifier.size(22.dp))
                            Txt(fact.answer, 20, FontWeight.ExtraBold, resultColor)
                        }
                        Txt(
                            "Time: " + String.format(Locale.US, "%.1f", u.lastMs / 1000.0) + " sec",
                            10, FontWeight.Medium, c.accentA
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Keypad(
    fractionKeys: Boolean,
    enabled: Boolean,
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit
) {
    // FIX #8: perf — capture callbacks via rememberUpdatedState so the lambda
    // reference doesn't change identity on each recomposition of TrainingScreen.
    // Compose can now SKIP the entire keypad when `enabled` and `fractionKeys`
    // haven't changed (i.e., typing a digit doesn't touch the keypad's inputs).
    val onKeyLatest = rememberUpdatedState(onKey)
    val onDeleteLatest = rememberUpdatedState(onDelete)
    val onClearLatest = rememberUpdatedState(onClear)
    val onSubmitLatest = rememberUpdatedState(onSubmit)
    val c = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    KeyButton(k, Modifier.weight(1f), enabled) { onKeyLatest.value(k) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("C", Modifier.weight(1f), enabled) { onClearLatest.value() }
            KeyButton("0", Modifier.weight(1f), enabled) { onKeyLatest.value("0") }
            // FIX #8: perf — backspace supports hold-to-repeat, like a native keyboard.
            KeyButtonRepeat("⌫", Modifier.weight(1f), enabled) { onDeleteLatest.value() }
        }
        if (fractionKeys) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(".", Modifier.weight(1f), enabled) { onKeyLatest.value(".") }
                KeyButton("/", Modifier.weight(1f), enabled) { onKeyLatest.value("/") }
                KeyButton("%", Modifier.weight(1f), enabled) { onKeyLatest.value("%") }
            }
        }
        GradientButton(
            "Submit",
            Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { onSubmitLatest.value() }
        )
    }
}

// FIX #8: perf — keypad keys use plain `clickable`, NOT `bounceClick`. Reasons:
//  (a) keypads must NEVER debounce — pressing "5" twice fast is intentional;
//  (b) plain `clickable` has no `animateFloatAsState`, no scale, no graphicsLayer.
//      The pressed-tint is a single color swap, ~0.3ms instead of ~5ms.
// The clickable callback captures via `rememberUpdatedState` so the key skips
// recomposition when the parent changes unrelated state.
@Composable
fun KeyButton(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val latest = rememberUpdatedState(onClick)
    val bg = if (pressed) c.surface else c.surfaceAlt
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = { latest.value() }
            )
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Txt(label, 22, FontWeight.SemiBold, c.onBackground)
    }
}

// FIX #8: perf — hold-to-repeat backspace, matching native Android keyboard behavior.
// Tap fires once immediately. Hold for >350ms fires once, then repeats every 40ms
// while the finger is held. The repeat loop is a coroutine cancelled cleanly when
// the finger lifts (because `pressed` flips and `LaunchedEffect` restarts).
@Composable
fun KeyButtonRepeat(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val latest = rememberUpdatedState(onClick)

    // Auto-repeat loop. Guarded on `enabled` so it stops if the answer is submitted
    // (which flips enabled = false and cancels the effect).
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        kotlinx.coroutines.delay(350L)   // grace period before repeat kicks in
        while (enabled) {
            kotlinx.coroutines.delay(40L)  // ~25 characters/sec
            if (!enabled) break
            latest.value()
        }
    }

    val bg = if (pressed) c.surface else c.surfaceAlt
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = { latest.value() }
            )
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Txt(label, 22, FontWeight.SemiBold, c.onBackground)
    }
}

// =====================================================================
// Results screen
// =====================================================================
@Composable
fun ResultsScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    val r = vm.results
    if (r == null) {
        Box(Modifier.fillMaxSize().background(c.background))
        return
    }
    val cat = Categories.byId(r.categoryId)
    val acc = if (r.total > 0) r.score * 100 / r.total else 0
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        delay(250)
        target = acc / 100f
    }
    val shape = RoundedCornerShape(26.dp)

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(10.dp, shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Txt("Session complete", 14, FontWeight.Medium, Color.White.copy(alpha = 0.9f))
            Txt(cat.name, 24, FontWeight.ExtraBold, Color.White)
            Txt("${r.score}/${r.total}  ·  $acc%", 40, FontWeight.ExtraBold, Color.White)
        }

        AppCard {
            Txt("Accuracy", 16, FontWeight.Bold)
            GradientProgressBar(target)
            Txt("Best streak: ${r.best}", 14, FontWeight.Medium, c.muted)
            Txt("Improved questions: ${r.improved}", 14, FontWeight.Medium, c.muted)
            Txt("New questions introduced: ${r.introduced}", 14, FontWeight.Medium, c.muted)
        }

        AppCard {
            Txt("To review", 16, FontWeight.Bold)
            if (r.missed.isEmpty()) {
                Txt("No mistakes this session. Great work!", 14, FontWeight.Medium, c.success)
            } else {
                r.missed.take(15).forEach { Txt(it, 15, FontWeight.Medium, c.error) }
                if (r.missed.size > 15) Txt("… and ${r.missed.size - 15} more", 13, FontWeight.Normal, c.muted)
            }
        }

        GradientButton("Practice again", Modifier.fillMaxWidth()) { vm.startSession() }
        // FIX #8 — resolve the current category once, reuse for both secondary buttons.
        val currentCategory = Categories.all.firstOrNull { it.id == r.categoryId }
        if (currentCategory != null) {
            // FIX #8 — NEW: jump to the full Detailed Progress view for this category.
            // Uses the same openCategoryDetail() path as tapping a category card on the
            // Progress Dashboard, so all dashboard data is loaded and shown correctly.
            SoftButton(
                "View Progress",
                Modifier.fillMaxWidth(),
                onClick = { vm.openCategoryDetail(currentCategory, fromResults = true) }
            )
            SoftButton(
                "Back to Category",
                Modifier.fillMaxWidth(),
                onClick = { vm.openCategory(currentCategory) }
            )
        }
        SoftButton("Back to menu", Modifier.fillMaxWidth()) { vm.toMenu() }
    }
}

// FIX #8: perf — reusable "content deferral" gate. Mounts `content` only after two
// clean frames have passed since first composition. This lets the screen transition
// animation render without competing with heavy child composition (grids, canvases,
// sorted lists) — which is what was causing the visible stutter mid-fade. The
// placeholder is empty (Box), so the animation has near-zero work per frame.
@Composable
private fun DeferredContent(
    key: Any? = Unit,
    placeholder: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    var ready by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (key != null) {
            ready = false
            withFrameNanos { }
            withFrameNanos { }
            ready = true
        } else {
            ready = true
        }
    }
    if (ready) content() else placeholder()
}

// =====================================================================
// Progress Dashboard  (FIX #8: perf)
// =====================================================================
@Composable
fun ProgressScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    val dash = vm.dashboard
    var resetStep by remember { mutableStateOf(0) }   // 0=none, 1=first, 2=second

    Box(Modifier.fillMaxSize()) {
        // LazyColumn composes only the cards that are on screen, so the first
        // frame of the transition builds ~2 cards instead of 10.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(c.background)
                .then(if (vm.resetting != null) Modifier.blur(16.dp) else Modifier),
            // FIX #8 — preserve scroll across navigation. If you scroll to the bottom
            // of the dashboard, tap a category, and come back, you stay where you were.
            state = vm.progressScrollState,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "header") {
                ProgressHeader("Progress Dashboard", "All categories") { vm.toMenu() }
            }
            if (dash == null) {
                item(key = "loading") { DashboardLoadingIndicator() }
            } else {
                items(Categories.all, key = { it.id }) { cat ->
                    CategoryProgressSection(
                        cat = cat,
                        mastery = dash.mastery[cat.id] ?: Triple(0, 0, 0),
                        history = dash.history[cat.id] ?: emptyList(),
                        details = dash.details[cat.id] ?: emptyList(),
                        onOpen = { vm.openCategoryDetail(cat) },
                        // FIX #8 — dashboard cards are compact: no charts, no detailed
                        // lists. Tapping the card opens CategoryDetailScreen which
                        // passes detailed = true and shows the full breakdown.
                        detailed = false
                    )
                }
            }
            // FIX #8 — action at the very bottom of the dashboard. Placing it after
            // the category list (instead of after the header) forces the user to
            // scroll past their progress before the destructive action, which is
            // both a UX improvement and a safety measure against mis-taps.
            item(key = "resetAll") {
                GradientButton(
                    "Reset All Progress",
                    Modifier.fillMaxWidth(),
                    brush = Brush.linearGradient(listOf(c.error, c.error.copy(alpha = 0.7f)))
                ) { resetStep = 1 }
            }
        }

        vm.resetting?.let { label -> ResetOverlay(label) }

        if (resetStep == 1) {
            AlertDialog(
                onDismissRequest = { resetStep = 0 },
                containerColor = c.surface,
                title = { Text2("Reset ALL progress?") },
                text = { Text2("Every category's stats, history, and mastery will be wiped. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = { resetStep = 2 }) { Txt("Continue", 15, FontWeight.Bold, c.error) }
                },
                dismissButton = {
                    TextButton(onClick = { resetStep = 0 }) { Txt("Cancel", 15, FontWeight.Medium, c.muted) }
                }
            )
        }
        if (resetStep == 2) {
            AlertDialog(
                onDismissRequest = { resetStep = 0 },
                containerColor = c.surface,
                title = { Text2("Are you absolutely sure?") },
                text = { Text2("This is the second and final confirmation. Tap Reset to erase everything.") },
                confirmButton = {
                    TextButton(onClick = { vm.resetAllProgress(); resetStep = 0 }) {
                        Txt("Reset", 15, FontWeight.Bold, c.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { resetStep = 0 }) { Txt("Cancel", 15, FontWeight.Medium, c.muted) }
                }
            )
        }
    }
}

// =====================================================================
// Category detail screen  (FIX #8: perf)
// =====================================================================
@Composable
fun CategoryDetailScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    val cat = vm.selected
    val dash = vm.dashboard
    var resetStep by remember { mutableStateOf(0) }
    val shape = RoundedCornerShape(26.dp)

    // FIX #8: perf — outer Box so the reset overlay is a true top layer, drawn above
    // the scroll content and above any AlertDialog scrim. Without this, the per-category
    // reset appeared to "do nothing" because the overlay was hidden behind the dialog.
    Box(Modifier.fillMaxSize()) {
    // FIX #8: perf — same conditional blur as ProgressScreen so the Category Detail
    // screen also frosts its content behind the reset overlay.
    Column(
        Modifier.fillMaxSize().background(c.background)
            .then(if (vm.resetting != null) Modifier.blur(16.dp) else Modifier)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // FIX #8 — hero header. The tap target routes to Results if this screen was
        // opened from Results, otherwise to Progress Dashboard. Matches the system
        // Back gesture via vm.onBack(), so both give the same destination.
        val headerBackAction: () -> Unit = {
            if (vm.categoryDetailFromResults) vm.onBack() else vm.openProgress()
        }
        Row(
            Modifier.fillMaxWidth()
                .shadow(8.dp, shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
                .bounceClick(shape, 0.dp, onClick = headerBackAction)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(cat.symbol, 26, FontWeight.Bold, Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(cat.name, 18, FontWeight.ExtraBold, Color.White)
                Txt("Detailed progress", 11, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
            }
            // Visual affordance only — the ripple and click are on the parent Row.
            Box(
                Modifier.size(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Txt("‹", 34, FontWeight.Bold, Color.White, align = TextAlign.Center)
            }
        }

        // FIX #8 — mastery card first, actions at the bottom. This mirrors the dashboard:
        // the user scrolls through their progress, then acts. Also removes the visual
        // weight of big buttons directly under the hero, letting the eye settle on data.
        if (dash == null) {
            DashboardLoadingIndicator()
        } else {
            CategoryProgressSection(
                cat = cat,
                mastery = dash.mastery[cat.id] ?: Triple(0, 0, 0),
                history = dash.history[cat.id] ?: emptyList(),
                details = dash.details[cat.id] ?: emptyList(),
                onOpen = null,
                showHeader = false
            )
        }

        // FIX #8 — action row is hidden when CategoryDetail was opened from Results.
        // The user just finished a session; showing Start Practice / Menu / Reset
        // again would be redundant (Results already has those actions). The screen
        // becomes a pure progress view in that case.
        if (!vm.categoryDetailFromResults) {
            GradientButton("Start Practice", Modifier.fillMaxWidth()) { vm.startSession() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Menu", Modifier.weight(1f), size = 14) { vm.toMenu() }
                SoftButton(
                    "Reset Category",
                    Modifier.weight(1f),
                    container = c.error.copy(alpha = 0.15f),
                    content = c.error,
                    size = 14
                ) { resetStep = 1 }
            }
        }
    }

    if (resetStep == 1) {
        AlertDialog(
            onDismissRequest = { resetStep = 0 },
            containerColor = c.surface,
            title = { Text2("Reset ${cat.name}?") },
            text = { Text2("This category's stats and history will be erased.") },
            confirmButton = {
                TextButton(onClick = { resetStep = 2 }) { Txt("Continue", 15, FontWeight.Bold, c.error) }
            },
            dismissButton = {
                TextButton(onClick = { resetStep = 0 }) { Txt("Cancel", 15, FontWeight.Medium, c.muted) }
            }
        )
    }
    if (resetStep == 2) {
        AlertDialog(
            onDismissRequest = { resetStep = 0 },
            containerColor = c.surface,
            title = { Text2("Confirm reset") },
            text = { Text2("Final confirmation. Tap Reset to wipe ${cat.name}.") },
            confirmButton = {
                TextButton(onClick = { vm.resetSelectedCategory(); resetStep = 0 }) {
                    Txt("Reset", 15, FontWeight.Bold, c.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { resetStep = 0 }) { Txt("Cancel", 15, FontWeight.Medium, c.muted) }
            }
        )
    }

    // FIX #8: perf — reset overlay for the per-category flow. Mounted at the Box
    // level so it's drawn above the dialog scrim; label is set by the VM before the
    // coroutine launches, so this composable appears on the very next frame.
    vm.resetting?.let { label -> ResetOverlay(label) }
    }   // closes the outer Box
}

// =====================================================================
// Shared dashboard pieces
// =====================================================================
@Composable
fun ProgressHeader(title: String, subtitle: String, onBack: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    // FIX #8: perf — the entire header card is now the tap target. Previously only the
    // chevron was tappable, which required precise aim; now a tap anywhere on the
    // gradient triggers onBack. The chevron is still rendered as an affordance but
    // has its own no-op-free appearance.
    Row(
        Modifier.fillMaxWidth()
            .shadow(8.dp, shape)
            .clip(shape)
            .background(AppTheme.colors.accent)
            .bounceClick(shape, 0.dp, onClick = onBack)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Txt(title, 22, FontWeight.ExtraBold, Color.White)
            Txt(subtitle, 13, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
        }
        // Visual affordance only — the ripple and click are handled by the parent Row.
        Box(
            Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            Txt("‹", 34, FontWeight.Bold, Color.White, align = TextAlign.Center)
        }
    }
}

@Composable
fun CategoryProgressSection(
    cat: Category,
    mastery: Triple<Int, Int, Int>,        // (mastered%, learning%, struggling%)
    history: List<SessionPoint>,
    details: List<AdaptiveEngine.FactDetail>,
    onOpen: (() -> Unit)?,
    // FIX #8: perf — when the caller already shows the category name in a hero above
    // (CategoryDetailScreen), the in-card header would be a duplicate. Default = true
    // so existing call sites keep the header without changes.
    showHeader: Boolean = true,
    // FIX #8 — when true, also render the TrendChart + detailed strengths/weaknesses
    // lists. When false (Progress Dashboard card grid), only the top half renders:
    // header, mastery bars, and the one-line strengths/weaknesses summary. Tapping
    // the card opens the full detail (CategoryDetailScreen) which passes detailed=true.
    detailed: Boolean = true
) {
    val c = AppTheme.colors
    val clickMod = if (onOpen != null)
        Modifier.bounceClick(RoundedCornerShape(20.dp), 4.dp, onClick = onOpen)
    else Modifier

    CompactCard(clickMod) {
        // FIX #8: perf — header row only when the caller wants it. The category detail
        // screen already shows symbol + name in the gradient hero, so the duplicate is
        // suppressed there by passing showHeader = false.
        if (showHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(cat.symbol, 20, FontWeight.Bold, c.accentA)
                Spacer(Modifier.width(8.dp))
                Txt(cat.name, 15, FontWeight.Bold)
            }
        }

        // Mastery breakdown (three stacked bars)
        Txt("Mastery", 11, FontWeight.SemiBold, c.muted)
        MasteryBar("Mastered",   mastery.first,    c.success)
        MasteryBar("Learning",   mastery.second,   c.warning)
        MasteryBar("Struggling", mastery.third,    c.error)

        // FIX #8: perf — summarize() allocates strings; cache on (mastery, details).
        val (strongTxt, weakTxt) = remember(mastery, details) { summarize(mastery, details) }
        Txt("Top Strength: $strongTxt", 11, FontWeight.Medium, c.success)
        Txt("Top Weakness: $weakTxt", 11, FontWeight.Medium, c.error)

        // FIX #8 — `detailed` gate: charts + detailed lists are shown only when the
        // caller opts in. Progress Dashboard (grid of cards) passes detailed = false
        // to keep cards compact — those details are available on CategoryDetailScreen
        // (which passes detailed = true). This halves per-card composition cost on
        // the dashboard, and makes the card visually read as a summary.
        if (detailed) {
            // Trend chart (empty state handled inside)
            TrendChart(history)

            // FIX #8: perf — partition into mutually exclusive buckets so the SAME
            // fact can never appear in both Strengths and Weaknesses.
            //   • strengths  = facts where correct > incorrect
            //   • weaknesses = facts where incorrect >= correct
            val sortedByCorrect = remember(details) {
                details.filter { it.correct > it.incorrect }
                    .sortedWith(
                        compareByDescending<AdaptiveEngine.FactDetail> { it.correct - it.incorrect }
                            .thenBy { it.avgMs }
                    )
            }
            val sortedByWrong = remember(details) {
                details.filter { it.incorrect > 0 && it.incorrect >= it.correct }
                    .sortedWith(
                        compareByDescending<AdaptiveEngine.FactDetail> { it.incorrect - it.correct }
                            .thenByDescending { it.lastSeen }
                    )
            }

            if (sortedByCorrect.isNotEmpty()) {
                Txt("Strength list", 11, FontWeight.SemiBold, c.muted)
                sortedByCorrect.take(5).forEach { d ->
                    FactRow(d.key, d.correct, "correct", d.avgMs, c.success)
                }
            }

            if (sortedByWrong.isNotEmpty()) {
                Txt("Weakness list", 11, FontWeight.SemiBold, c.muted)
                sortedByWrong.take(5).forEach { d ->
                    FactRow(d.key, d.incorrect, "wrong", d.avgMs, c.error)
                }
            }
        }
    }
}

@Composable
private fun FactRow(fact: String, count: Int, countLabel: String, avgMs: Double, color: Color) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(fact, 12, FontWeight.SemiBold, c.onBackground)
            Txt("Avg. Time: ${(avgMs / 1000.0).format1()} s", 10, FontWeight.Medium, c.muted)
        }
        Txt("$count $countLabel", 11, FontWeight.Bold, color)
    }
}

@Composable
private fun MasteryBar(label: String, pct: Int, color: Color) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 12, FontWeight.Medium, c.muted, Modifier.width(80.dp))
        Box(
            Modifier.weight(1f).height(8.dp)
                .clip(RoundedCornerShape(50)).background(c.surfaceAlt)
        ) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(pct.coerceIn(0, 100) / 100f)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
        Spacer(Modifier.width(8.dp))
        Txt("$pct%", 12, FontWeight.SemiBold, color, Modifier.width(40.dp), TextAlign.End)
    }
}

// Small helpers used only inside the dashboard section.
private fun Double.format1(): String = String.format(java.util.Locale.US, "%.1f", this)

private fun summarize(
    mastery: Triple<Int, Int, Int>,
    details: List<AdaptiveEngine.FactDetail>
): Pair<String, String> {
    if (details.isEmpty()) return "No data yet" to "No data yet"

    // FIX #8 — partition into mutually exclusive buckets so the SAME fact can never
    // appear in both "strong on" and "weak on". This mirrors the partitioning used
    // by the detailed lists: strengths are net-positive facts, weaknesses are
    // net-neutral-or-negative facts.
    val netPositive = details.filter { it.correct > it.incorrect }
    val netNegative = details.filter { it.incorrect > 0 && it.incorrect >= it.correct }

    val best = netPositive.maxByOrNull { it.correct - it.incorrect }?.key
    val worst = netNegative.maxByOrNull { it.incorrect - it.correct }?.key

    val strong = when {
        best != null -> best
        details.any { it.correct > 0 } -> "None yet"
        else -> "No data yet"
    }
    val weak = when {
        worst != null -> worst
        details.any { it.incorrect > 0 } -> "None yet"
        else -> "No data yet"
    }
    return strong to weak
}

// FIX #8: perf — dashboard loading placeholder. A gradient progress bar that fills
// from 0 → 90% over ~700ms and holds, then disappears when real data replaces it.
// Uses graphicsLayer + a single Animatable, so it costs almost nothing per frame.
@Composable
fun DashboardLoadingIndicator() {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(22.dp)
    val fill = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        fill.animateTo(0.9f, tween(700, easing = FastOutSlowInEasing))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape)
            .clip(shape)
            .background(c.surface)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Txt("Loading progress…", 13, FontWeight.Medium, c.muted)

        // Progress bar — same visual language as GradientProgressBar, but reads
        // from the local Animatable so the value is stable across recompositions.
        val pill = RoundedCornerShape(50)
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(pill)
                .background(c.surfaceAlt)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fill.value)
                    .clip(pill)
                    .background(c.accent)
            )
        }
    }
}

// FIX #8: perf — Ready / Set / Solve! countdown overlay. Solid scrim (no blur),
// animates only alpha + scale via graphicsLayer, so it costs near-zero per frame.
// Removed from composition the instant `countdown` becomes null → no residual scrim.
@Composable
private fun CountdownOverlay(label: String) {
    val t = remember(label) { Animatable(0f) }
    LaunchedEffect(label) {
        t.snapTo(0f)
        t.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
    }

    Box(
        Modifier
            .fillMaxSize()
            // FIX #8: perf — swallow touches so nothing behind the countdown responds.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .background(Color(0xE60F1020)),   // ~90% opaque app background
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.graphicsLayer {
                alpha = t.value
                scaleX = 0.85f + 0.15f * t.value
                scaleY = 0.85f + 0.15f * t.value
            },
            contentAlignment = Alignment.Center
        ) {
            Txt(
                label,
                64,
                FontWeight.ExtraBold,
                Color.White,
                align = TextAlign.Center
            )
        }
    }
}

// FIX #8: perf — full-screen reset overlay: dark scrim + centered title + a moving
// indeterminate bar. Composed only while vm.resetting != null, so removal is
// immediate and leaves no residual scrim or layer behind.
@Composable
private fun ResetOverlay(label: String) {
    // FIX #8: perf — overlay is just a translucent tint. The blur is applied by the
    // caller to the CONTENT BEHIND this overlay, so the frosted-glass look comes from
    // two independent layers: (1) content blurred, (2) tint on top. Doing both here
    // would blur the tint itself — which is a solid color, and therefore a no-op.
    Box(
        Modifier
            .fillMaxSize()
            // FIX #8: perf — no-op clickable acts as a touch blocker. Without this,
            // taps pass through the overlay to any clickable behind it (buttons,
            // cards, the chevron). The empty onClick intentionally does nothing.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .background(Color(0x9A0F1020)),   // 60% opaque — content still visible
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Txt(label, 20, FontWeight.ExtraBold, Color.White, align = TextAlign.Center)

            // Indeterminate moving bar: a small gradient segment slides left→right,
            // driven by a single Animatable. Cost = 1 draw per frame, no allocations.
            IndeterminateBar()

            Txt("Loading…", 13, FontWeight.Medium, Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun IndeterminateBar() {
    val c = AppTheme.colors
    val track = Color.White.copy(alpha = 0.18f)
    val pill = RoundedCornerShape(50)
    val anim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(1100, easing = LinearEasing))
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(pill)
            .background(track)
    ) {
        // The moving segment occupies 30% of the bar and slides from -30% → 100%.
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.30f)
                .graphicsLayer {
                    translationX = (anim.value * 1.30f - 0.30f) * size.width
                }
                .clip(pill)
                .background(c.accent)
        )
    }
}
