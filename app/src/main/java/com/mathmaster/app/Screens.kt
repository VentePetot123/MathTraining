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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            progress.animateTo(1f, tween(1800, easing = LinearEasing))
            preload.await()
            delay(150)
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
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().background(c.background),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { MenuHeader(vm) }
        itemsIndexed(Categories.all) { index, cat ->
            CategoryCard(index, cat, vm.menuStats[cat.id]) { vm.openCategory(cat) }
        }
    }
}

@Composable
fun MenuHeader(vm: TrainerViewModel) {
    val shape = RoundedCornerShape(26.dp)
    val t = vm.totals
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .clip(shape)
            .background(AppTheme.colors.accent)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Txt("Math Master", 28, FontWeight.ExtraBold, Color.White)
        Txt("Pick a category to start training", 14, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
        val summary = if (t.answers > 0)
            "${t.answers} answers · ${t.sessions} sessions · best streak ${t.bestStreak}"
        else "Your progress will appear here"
        Txt(summary, 12, FontWeight.Medium, Color.White.copy(alpha = 0.85f))
    }
}

@Composable
fun CategoryCard(index: Int, cat: Category, s: CatSummary?, onClick: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 50L)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 5 }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .bounceClick(RoundedCornerShape(22.dp), 8.dp, onClick = onClick)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
                .padding(16.dp)
        ) {
            Txt(cat.symbol, 30, FontWeight.Bold, Color.White, Modifier.align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart)) {
                Txt(cat.name, 15, FontWeight.Bold, Color.White)
                val stat = if (s == null || s.attempts == 0) "Not practiced yet"
                else "${s.accuracyPct}% acc · ${s.facts} facts"
                Txt(stat, 11, FontWeight.Medium, Color.White.copy(alpha = 0.85f))
            }
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
    var confirmReset by remember { mutableStateOf(false) }
    val headerShape = RoundedCornerShape(26.dp)

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
                .shadow(10.dp, headerShape)
                .clip(headerShape)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Txt(cat.symbol, 40, FontWeight.Bold, Color.White)
            Txt(cat.name, 26, FontWeight.ExtraBold, Color.White)
            Txt(cat.blurb, 14, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
        }

        AppCard {
            Txt(if (cat.usesFractionKeys) "Denominator range" else "Number range", 18, FontWeight.Bold)
            Stepper("From", vm.rangeMin, 1) { vm.setMin(it) }
            Stepper("To", vm.rangeMax, 1) { vm.setMax(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cat.presets().forEach { (a, b) ->
                    SoftButton("$a–$b", Modifier.weight(1f), size = 14) { vm.applyPreset(a, b) }
                }
            }
        }

        AppCard {
            Txt("Questions per session", 18, FontWeight.Bold)
            Stepper("Questions", vm.questionCount, 5) { vm.setCount(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 20, 30, 50).forEach { n ->
                    SoftButton("$n", Modifier.weight(1f), size = 14) { vm.setCount(n) }
                }
            }
        }

        val s = vm.menuStats[cat.id]
        val statText = if (s == null || s.attempts == 0) "No practice data yet for this category"
        else "${s.attempts} attempts · ${s.accuracyPct}% accuracy · ${s.facts} facts practiced"
        Txt(statText, 13, FontWeight.Medium, c.muted, Modifier.fillMaxWidth(), TextAlign.Center)

        GradientButton("Start practice", Modifier.fillMaxWidth()) { vm.startSession() }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("← Menu", Modifier.weight(1f)) { vm.toMenu() }
            SoftButton(
                "Reset category", Modifier.weight(1f),
                container = c.error.copy(alpha = 0.15f), content = c.error
            ) { confirmReset = true }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = c.surface,
            titleContentColor = c.onBackground,
            textContentColor = c.muted,
            title = { Text2("Reset ${cat.name}?") },
            text = { Text2("All learning progress for this category will be deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.resetSelectedCategory(); confirmReset = false }) {
                    Txt("Reset", 15, FontWeight.Bold, c.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Txt("Cancel", 15, FontWeight.Medium, c.muted) }
            }
        )
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
        key(u.id) {
            GradientProgressBar(
                progress = u.answered.toFloat() / u.total,
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
    val promptSize = if (fact.prompt.length > 14) 30 else 44

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
                Feedback.Correct -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeedbackMark(true, u.seq)
                    Txt("Correct! " + String.format("%.1f", u.lastMs / 1000.0) + "s", 18, FontWeight.Bold, c.success)
                }
                Feedback.Wrong -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeedbackMark(false, u.seq)
                    Txt(fact.solution, 18, FontWeight.Bold, c.error)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fractionKeys) KeyButton(".", Modifier.weight(1f), enabled) { onKey(".") }
            else Spacer(Modifier.weight(1f))
            KeyButton("0", Modifier.weight(1f), enabled) { onKey("0") }
            KeyButton("⌫", Modifier.weight(1f), enabled) { onDelete() }
        }
        if (fractionKeys) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton("/", Modifier.weight(1f), enabled) { onKey("/") }
                KeyButton("%", Modifier.weight(1f), enabled) { onKey("%") }
                Spacer(Modifier.weight(1f))
            }
        }
        GradientButton("Submit", Modifier.fillMaxWidth(), enabled = enabled) { onSubmit() }
    }
}

@Composable
fun KeyButton(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier
            .height(52.dp)
            .bounceClick(RoundedCornerShape(14.dp), 2.dp, 0.9f, enabled, onClick)
            .background(AppTheme.colors.surfaceAlt),
        contentAlignment = Alignment.Center
    ) {
        Txt(label, 22, FontWeight.SemiBold, AppTheme.colors.onBackground)
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
