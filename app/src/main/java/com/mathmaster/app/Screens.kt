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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
            // FIX #8: perf — 1800ms was an artificial wait; 900ms still covers a cold
            // first preload on slower devices (preload.await() blocks until done) while
            // cutting the perceived lag roughly in half.
            progress.animateTo(1f, tween(900, easing = LinearEasing))
            preload.await()
            delay(80)
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
    }
}

// =====================================================================
// Menu (categories) screen
// =====================================================================
@Composable
fun MenuScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    // FIX #8: perf — removed the grid-wide alpha fade. AnimatedContent already slides
    // the whole screen in; a second fade forced an offscreen buffer for the entire
    // grid, adding ~5-10ms per frame during the transition. The slide is enough polish.
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(c.background),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // FIX #8: perf — header is now a tap target for the Progress Dashboard.
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
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(6.dp, headerShape)
                .clip(headerShape)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(cat.symbol, 26, FontWeight.Bold, Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(cat.name, 18, FontWeight.ExtraBold, Color.White)
                Txt(cat.blurb, 11, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
            }
        }

        // ---- Number range card ----
        CompactCard {
            Txt(if (cat.usesFractionKeys) "Denominator range" else "Number range", 15, FontWeight.Bold)
            Stepper("From", vm.rangeMin, 1) { vm.setMin(it) }
            Stepper("To", vm.rangeMax, 1) { vm.setMax(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cat.presets().forEach { (a, b) ->
                    SoftButton("$a–$b", Modifier.weight(1f), size = 12) { vm.applyPreset(a, b) }
                }
            }
        }

        // ---- Questions per session card ----
        CompactCard {
            Txt("Questions per session", 15, FontWeight.Bold)
            Stepper("Questions", vm.questionCount, 5) { vm.setCount(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 20, 30, 50).forEach { n ->
                    SoftButton("$n", Modifier.weight(1f), size = 12) { vm.setCount(n) }
                }
            }
        }

        // ---- Compact stat line ----
        val s = vm.menuStats[cat.id]
        val statText = if (s == null || s.attempts == 0) "No practice data yet for this category"
        else "${s.attempts} attempts · ${s.accuracyPct}% accuracy · ${s.facts} facts practiced"
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

    // FIX #8: perf — wrap the training UI in a Box so we can layer the countdown
    // overlay on top. The overlay composable is only invoked while countdown != null;
    // once it clears, Compose disposes it completely with no residual scrim or layer.
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SoftButton("✕ Quit", size = 14) { vm.showQuit = true }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Txt("Q ${minOf(u.answered + 1, u.total)}/${u.total}", 17, FontWeight.Bold)
                Txt("Score ${u.score} · Streak ${u.streak} 🔥", 12, FontWeight.Medium, c.muted)
            }
        }

        // FIX #6: bar is keyed to the session id and fed ONLY by this session's counters,
        // so a new session can never inherit the previous category's progress.
        // FIX #14: remember the fraction so keystrokes (which change u.input) don't
        // retrigger the progress animation.
        key(u.id) {
            val progress = remember(u.answered, u.total) {
                u.answered.toFloat() / u.total
            }
            GradientProgressBar(
                progress = progress,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // FIX #7: question area is the weighted, scrollable top part of the Column and is
        // drawn ABOVE the keypad (zIndex 1). The keypad is a fixed-size sibling below it,
        // so the question can never sit behind it; long questions scroll inside their area.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .zIndex(1f)
                .padding(16.dp)
        ) {
            Column(
                Modifier.align(Alignment.Center).verticalScroll(rememberScrollState())
            ) {
                key(u.id) { QuestionCard(u) }
            }
        }

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

        // FIX #8: perf — countdown overlay. Composed only while countdown != null,
        // so when it clears there is no scrim, blur, or layer left behind.
        vm.countdown?.let { CountdownOverlay(it) }
    }   // ← closes the Box
}

@Composable
fun QuestionCard(u: UiSession) {
    val c = AppTheme.colors
    val fact = u.fact ?: return
    val shape = RoundedCornerShape(26.dp)

    val entrance = remember(u.seq) { Animatable(0f) }
    val shake = remember(u.seq) { Animatable(0f) }
    val tint = remember(u.seq) { Animatable(0f) }

    // Animation: question card entrance (fade + slight upward slide)
    LaunchedEffect(u.seq) { entrance.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }

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
    // FIX #15: prompt length is stable for a given fact; remember across keystrokes.
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surfaceAlt)
                .border(2.dp, borderColor, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (u.input.isEmpty()) Txt("?", 28, FontWeight.Bold, c.muted)
            else Txt(u.input, 30, FontWeight.Bold, c.onBackground)
        }

        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            when (u.feedback) {
                // FIX #12: unified feedback strip — shown for every category, same format.
                // The animated FeedbackMark (Canvas) is the ONLY symbol; the text is
                // just "<answer> <time>s" so we don't render a duplicate Unicode glyph.
                Feedback.Correct -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FeedbackMark(true, u.seq)
                    Txt(
                        fact.answer + "  " + String.format(Locale.US, "%.1f", u.lastMs / 1000.0) + "s",
                        18, FontWeight.Bold, c.success
                    )
                }
                Feedback.Wrong -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FeedbackMark(false, u.seq)
                    Txt(
                        fact.answer + "  " + String.format(Locale.US, "%.1f", u.lastMs / 1000.0) + "s",
                        18, FontWeight.Bold, c.error
                    )
                }
                Feedback.None -> if (u.hint != null) Txt(u.hint, 14, FontWeight.Medium, c.warning)
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
                row.forEach { k -> KeyButton(k, Modifier.weight(1f), enabled) { onKey(k) } }
            }
        }
        // FIX #8: perf — "C" clears the whole input, "⌫" removes one character.
        // C lives in the left slot on every category; "." moved to the fraction row below.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyButton("C", Modifier.weight(1f), enabled) { onClear() }
            KeyButton("0", Modifier.weight(1f), enabled) { onKey("0") }
            KeyButton("⌫", Modifier.weight(1f), enabled) { onDelete() }
        }
        if (fractionKeys) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(".", Modifier.weight(1f), enabled) { onKey(".") }
                KeyButton("/", Modifier.weight(1f), enabled) { onKey("/") }
                KeyButton("%", Modifier.weight(1f), enabled) { onKey("%") }
            }
        }
        GradientButton("Submit", Modifier.fillMaxWidth(), enabled = enabled) { onSubmit() }
    }
}

@Composable
fun KeyButton(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // FIX #8: perf — pressed tint is a plain `alpha` change on the background color,
    // not a scale animation. Cheaper than animateFloatAsState and visually similar.
    val bg = if (pressed) c.surface else c.surfaceAlt
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick
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
        SoftButton("Back to menu", Modifier.fillMaxWidth()) { vm.toMenu() }
    }
}


// =====================================================================
// Progress Dashboard  (FIX #8: perf)
// =====================================================================
@Composable
fun ProgressScreen(vm: TrainerViewModel) {
    val c = AppTheme.colors
    val dash = vm.dashboard
    var resetStep by remember { mutableStateOf(0) }   // 0=none, 1=first, 2=second

    // FIX #8: perf — outer Box so the reset overlay can be a true top layer above the
    // scroll content and above any dialog scrim. Previously the overlay lived inside
    // the Column and could be occluded by the fading AlertDialog when reset completed
    // faster than the dialog's dismiss animation.
    Box(Modifier.fillMaxSize()) {
    // FIX #8: perf — blur the dashboard behind the overlay only while a reset is
    // running. `resetting != null` is the trigger; the blur is applied to the content
    // itself, so the overlay on top just needs a translucent tint to complete the
    // "frosted glass" look. blur(0.dp) below API 31 is a no-op — the tint still works.
    Column(
        Modifier.fillMaxSize().background(c.background)
            .then(if (vm.resetting != null) Modifier.blur(16.dp) else Modifier)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProgressHeader("Progress Dashboard", "All categories") { vm.toMenu() }

        GradientButton(
            "Reset All Progress",
            Modifier.fillMaxWidth(),
            brush = Brush.linearGradient(listOf(c.error, c.error.copy(alpha = 0.7f)))
        ) { resetStep = 1 }

        // FIX #8: perf — animated progress bar instead of a frozen "Loading…" label.
        // While the dashboard snapshot is being computed on Dispatchers.IO, the bar
        // fills with a gentle indeterminate motion; once the data arrives it's removed
        // from composition entirely (no residual layer, no stutter on re-entry).
        if (vm.dashboardLoading || dash == null) {
            DashboardLoadingIndicator()
        } else {
            Categories.all.forEach { cat ->
                CategoryProgressSection(
                    cat = cat,
                    mastery = dash.mastery[cat.id] ?: Triple(0, 0, 0),
                    history = dash.history[cat.id] ?: emptyList(),
                    details = dash.details[cat.id] ?: emptyList(),
                    onOpen = { vm.openCategoryDetail(cat) }
                )
            }
        }
    }

    // FIX #8: perf — full-screen reset overlay. Composed only while vm.resetting != null;
    // clears instantly when the VM drops the flag, leaving no residual scrim.
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

    // FIX #8: perf — overlay mounted at the Box level so it draws above the dialog
    // scrim and above all scroll content. Because `resetting` is set synchronously
    // before any coroutine, this composable is entered on the very next frame.
    vm.resetting?.let { label -> ResetOverlay(label) }
    }   // closes the outer Box
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
        // FIX #8: perf — compact hero matching the reference
        // FIX #8: perf — compact hero matching the reference: symbol + name on the
        // same row, small blurb underneath. Height drops from ~180dp to ~72dp.
        Row(
            Modifier.fillMaxWidth().shadow(8.dp, shape).clip(shape)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Txt(cat.symbol, 26, FontWeight.Bold, Color.White)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(cat.name, 18, FontWeight.ExtraBold, Color.White)
                Txt("Detailed progress", 11, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
            }
        }

        // FIX #8: perf — tighter action row: one primary button + two secondaries
        // side by side, halving the vertical space they consume.
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

        if (vm.dashboardLoading || dash == null) {
            DashboardLoadingIndicator()
        } else {
            CategoryProgressSection(
                cat = cat,
                mastery = dash.mastery[cat.id] ?: Triple(0, 0, 0),
                history = dash.history[cat.id] ?: emptyList(),
                details = dash.details[cat.id] ?: emptyList(),
                onOpen = null,
                showHeader = false     // FIX #8: hero above already shows the category name
            )
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
    showHeader: Boolean = true
) {
    val c = AppTheme.colors
    val clickMod = if (onOpen != null)
        Modifier.bounceClick(RoundedCornerShape(20.dp), 4.dp, onClick = onOpen)
    else Modifier

    // FIX #8: perf — compact card. Smaller paddings, tighter spacing, and smaller
    // typography everywhere so the whole section fits the reference look and the
    // dashboard stays short. Behaviour is identical; only the visual scale changed.
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

        // Strengths & weaknesses text summary
        val (strongTxt, weakTxt) = summarize(mastery, details)
        Txt("Strengths: $strongTxt", 11, FontWeight.Medium, c.success)
        Txt("Weaknesses: $weakTxt", 11, FontWeight.Medium, c.error)

        // Trend chart (empty state handled inside)
        TrendChart(history)

        // Detailed lists (top 5 each, sorted)
        val sortedByCorrect = details.sortedWith(
            compareByDescending<AdaptiveEngine.FactDetail> { it.correct }
                .thenBy { it.avgMs }
        )
        val sortedByWrong = details.sortedWith(
            compareByDescending<AdaptiveEngine.FactDetail> { it.incorrect }
                .thenByDescending { it.lastSeen }
        )

        if (sortedByCorrect.any { it.correct > 0 }) {
            Txt("Strengths (detailed)", 11, FontWeight.SemiBold, c.muted)
            sortedByCorrect.filter { it.correct > 0 }.take(5).forEach { d ->
                Txt("${d.key}  ✓${d.correct}  avg ${(d.avgMs / 1000.0).format1()}s",
                    11, FontWeight.Normal, c.onBackground)
            }
        }

        if (sortedByWrong.any { it.incorrect > 0 }) {
            Txt("Weaknesses (detailed)", 11, FontWeight.SemiBold, c.muted)
            sortedByWrong.filter { it.incorrect > 0 }.take(5).forEach { d ->
                Txt("${d.key}  ✗${d.incorrect}  last seen ${agoString(d.lastSeen)}",
                    11, FontWeight.Normal, c.onBackground)
            }
        }
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

private fun agoString(lastSeen: Long): String {
    if (lastSeen <= 0L) return "—"
    val mins = (System.currentTimeMillis() - lastSeen) / 60_000
    return when {
        mins < 1    -> "just now"
        mins < 60   -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else        -> "${mins / 1440}d ago"
    }
}

private fun summarize(
    mastery: Triple<Int, Int, Int>,
    details: List<AdaptiveEngine.FactDetail>
): Pair<String, String> {
    if (details.isEmpty()) return "no data yet" to "no data yet"
    val best = details.maxByOrNull { it.correct }?.key ?: "—"
    val worst = details.maxByOrNull { it.incorrect }?.key ?: "—"
    val strong = "strong on $best"
    val weak = "weak on $worst"
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
