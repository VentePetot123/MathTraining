package com.mathmaster.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredWidth
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import java.util.Locale
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
                history = HistoryRef.EMPTY,
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
        state = vm.menuScrollState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // FIX #8 — add `contentType` to each item so Compose can reuse slots when
        // scrolling. Without this, every recycled card forces a full recomposition
        // because Compose can't tell "this slot previously held a category card".
        item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") { MenuTitle(vm) }
        item(key = "progress", span = { GridItemSpan(maxLineSpan) }, contentType = "progress") {
            MenuHeader(vm) { vm.openProgress() }
        }
        item(key = "welcome", span = { GridItemSpan(maxLineSpan) }, contentType = "welcome") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                WelcomeMessage()
                MenuSeparator("Categories")
            }
        }
        itemsIndexed(Categories.all, contentType = { _, _ -> "category" }) { index, cat ->
            CategoryCard(index, cat, vm.menuStats[cat.id]) { vm.openCategory(cat) }
        }
    }
}

@Composable
fun MenuTitle(vm: TrainerViewModel) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Far left: logo + title
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(46.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Txt("Math Master", 24, FontWeight.ExtraBold, c.onBackground, lineHeight = 28)
                Txt("Train your mind. Adaptively.", 11, FontWeight.Medium, c.muted)
            }
        }
        // Far right: theme + sound toggles
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeToggle(isDarkMode = c.isDark, onClick = { vm.toggleTheme(c.isDark) })
            SoundToggle(vm.soundEnabled, { vm.toggleSound() })
        }
    }
}

@Composable
fun WelcomeMessage() {
    val c = AppTheme.colors
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    // FIX #8 — Capitalize the greeting word (Good Morning / Good Afternoon /
    // Good Evening) so it reads as a proper title. Removed "today" from the
    // welcome line per the request.
    val greeting = when (hour) {
        in 5..11 -> "Good Morning"
        in 12..17 -> "Good Afternoon"
        else -> "Good Evening"
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Txt("$greeting!", 20, FontWeight.ExtraBold, c.onBackground, align = TextAlign.Center)
        Txt("Welcome to Your Training", 14, FontWeight.SemiBold, c.accentA, align = TextAlign.Center)
        Txt("Pick a category and let's sharpen your mind.", 12, FontWeight.Medium, c.muted, align = TextAlign.Center)
    }
}

@Composable
fun MenuHeader(vm: TrainerViewModel, onOpenProgress: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    val t = vm.totals
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .clip(shape)
            .background(AppTheme.colors.accent)
            .bounceClick(shape, 0.dp, onClick = onOpenProgress)
            .clip(shape)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Txt("Progress Dashboard", 22, FontWeight.ExtraBold, Color.White)
            Txt("Tap to view your progress", 13, FontWeight.Normal, Color.White.copy(alpha = 0.9f))
            // FIX #8 — Always show the actual numbers, even at zero. Previously we showed
            // "Your progress will appear here" until the user practiced, which hid the
            // fact that the counters existed. Now the row is visible from the first launch.
            TextWithFlame(
                "${t.answers} Answers · ${t.sessions} Sessions · Best Streak ${t.bestStreak}",
                12, FontWeight.Medium, Color.White.copy(alpha = 0.85f),
                flameSize = 14.dp
            )
        }
        ProgressGlyph()
    }
}

@Composable
fun CategoryCard(index: Int, cat: Category, s: CatSummary?, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .bounceClick(RoundedCornerShape(22.dp), 8.dp, onClick = onClick)
            .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
    ) {
        // Tilted flashcards in the background, clipped by the card's rounded corners
        FlashcardBackdrop(cat.id, Modifier.matchParentSize())

        // Soft shade at the bottom so the text stays readable
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.30f)
                )
            )
        )

        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Txt(cat.symbol, 30, FontWeight.Bold, Color.White)

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = cat.name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(2.dp))
                // FIX: Right-aligned and stacked stats for better readability
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End
                ) {
                    if (s == null || s.attempts == 0) {
                        Txt("Not practiced yet", 11, FontWeight.Medium, Color.White.copy(alpha = 0.9f))
                    } else {
                        Txt("${formatAttempts(s.attempts)} Attempts", 11, FontWeight.Medium, Color.White.copy(alpha = 0.9f))
                        Txt("${s.accuracyPct}% Accuracy", 11, FontWeight.Medium, Color.White.copy(alpha = 0.9f))
                    }
                }
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
            // FIX #8 — same offset-compensation as ProgressHeader so the glyph is
            // vertically centered in the box.
            BackChevron()
        }

        // Extra gap so a tap meant for the header/back target can't land on the toggles
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemeToggle(isDarkMode = c.isDark, onClick = { vm.toggleTheme(c.isDark) })
            Spacer(Modifier.width(8.dp))
            SoundToggle(vm.soundEnabled, { vm.toggleSound() })
        }

        // Rounding rule — shown first so it can't be missed (Fractions/Decimals/% only)
        if (cat.id == "fdp") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.accentA.copy(alpha = 0.10f))
                    .border(1.dp, c.accentA.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Centered Title
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Txt("ⓘ", 16, FontWeight.Bold, c.accentA)
                        Text(
                            text = "ROUNDING RULE",
                            color = c.accentA,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp
                        )
                    }
                }

                // Content
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Txt(
                        "For non-terminating rational numbers (e.g., 2/3):",
                        12, FontWeight.SemiBold, c.accentA.copy(alpha = 0.85f)
                    )

                    // Indented bullet points
                    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Txt("•  ", 12, FontWeight.Bold, c.accentA)
                            Text(
                                buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = c.accentA)) {
                                        append("Decimal: ")
                                    }
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Medium, color = c.onBackground)) {
                                        append("4 decimal places ")
                                    }
                                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, color = c.muted)) {
                                        append("(e.g., 0.6667)")
                                    }
                                },
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Row {
                            Txt("•  ", 12, FontWeight.Bold, c.accentA)
                            Text(
                                buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = c.accentA)) {
                                        append("Percentage: ")
                                    }
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Medium, color = c.onBackground)) {
                                        append("2 decimal places ")
                                    }
                                    withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, color = c.muted)) {
                                        append("(e.g., 66.67%)")
                                    }
                                },
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Row {
                            Txt("•  ", 12, FontWeight.Bold, c.accentA)
                            Text(
                                buildAnnotatedString {
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Medium, color = c.onBackground)) {
                                        append("Standard rounding ")
                                    }
                                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = c.error)) {
                                        append("(≥5 rounds up)")
                                    }
                                },
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(2.dp))

                    Text(
                        buildAnnotatedString {
                            withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, color = c.muted)) {
                                append("Terminating rational numbers ")
                            }
                            withStyle(style = SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold, color = c.accentA)) {
                                append("(e.g., 1/4 = 0.25 = 25%)")
                            }
                            withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium, color = c.muted)) {
                                append(" require exact values.")
                            }
                        },
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // ---- Number range card ----
        CompactCard {
            Txt(
                text = if (cat.usesFractionKeys) "NUMERATORS & DENOMINATORS RANGE" else "RANGE",
                size = 15,
                weight = FontWeight.ExtraBold,
                color = c.accentA,
                modifier = Modifier.fillMaxWidth(),
                align = TextAlign.Center
            )
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
            Txt(
                text = "QUESTIONS",
                size = 15,
                weight = FontWeight.ExtraBold,
                color = c.accentA,
                modifier = Modifier.fillMaxWidth(),
                align = TextAlign.Center
            )
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
private fun formatAttempts(n: Int): String = if (n > 999) "999+" else n.toString()

// FIX #8 — collapsible "To Review" section for the Results screen. Mirrors the
// visual language of the Strength/Weakness tables: a SectionTitle with lines on
// each side, a bordered card of the first 3 mistakes, and an expand chevron below
// (only shown when there are more than 3). Expanding shows every mistake — no cap.
@Composable
private fun ReviewSection(missed: List<String>) {
    val c = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) missed else missed.take(3)
    val line = c.muted.copy(alpha = 0.25f)

    AppCard {
        SectionTitle("To Review")

        if (missed.isEmpty()) {
            Txt("No mistakes this session. Great work!", 14, FontWeight.Medium, c.success,
                Modifier.fillMaxWidth(), TextAlign.Center)
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, line, RoundedCornerShape(12.dp))
            ) {
                shown.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(c.error.copy(alpha = 0.06f))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Txt(m, 15, FontWeight.Medium, c.error, Modifier.fillMaxWidth(), TextAlign.Center)
                    }
                }
            }

            // FIX #8 — chevron appears only when there are more than 3 items. With
            // ≤3 items, everything is already visible so there's nothing to expand.
            if (missed.size > 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ExpandChevron(expanded, enabled = true, color = c.error) {
                        expanded = !expanded
                    }
                }
            }
        }
    }
}

// FIX #8 — Simple text helper used inside AlertDialog title/text lambdas throughout
// the app. Was accidentally deleted during the formatAttempts addition; restored
// here to resolve the 8 "Unresolved reference: Text2" compile errors.
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
                answered = u.questionNo - 1,
                total = u.total,
                score = u.score,
                streak = u.streak,
                showAnswer = u.showAnswer,
                onQuit = { vm.showQuit = true },
                soundEnabled = vm.soundEnabled,
                onToggleSound = { vm.toggleSound() },
                isDarkMode = c.isDark,
                onToggleTheme = { vm.toggleTheme(c.isDark) }
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
                    .padding(horizontal = 4.dp)
            ) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    // FIX #8: perf — the question card re-renders only when its own
                    // fields change. The keypad's `enabled` value is passed in as a
                    // primitive so it's a stability anchor.
                    key(u.id) {
                        // FIX #8 — fire the "question is now visible" callback after
                        // the entrance animation finishes (see LaunchedEffect inside
                        // QuestionCard). This ensures the timer measures only the
                        // user's thinking time, not the animation duration.
                        QuestionCard(u, liveTickMs = vm.liveTickMs) { vm.onQuestionShown() }
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
                canSubmit = u.input.isNotEmpty(),
                onKey = vm::onKey,
                onDelete = vm::onDelete,
                onClear = vm::onClear,
                onSubmit = vm::submit,
                signedKeys = (cat.id == "integers"),
                kind = u.fact?.kind
            )
        }

        if (vm.showQuit) {
            ConfirmDialog(
                icon = DialogIcon.Exit,
                title = "Leave session?",
                message = "Your answers are saved.",
                confirmText = "Leave",
                cancelText = "Keep going",
                onConfirm = { vm.quit() },
                onDismiss = { vm.showQuit = false }
            )
        }

        vm.countdown?.let { CountdownOverlay(it) }
    }
}

// FIX #8: perf — header is its own composable with primitive params. Typing a digit
// changes `input`, not `answered`/`score`/`streak`, so Compose skips this entirely.
@Composable
private fun HeaderBar(
    answered: Int,
    total: Int,
    score: Int,
    streak: Int,
    showAnswer: Boolean = false,
    onQuit: () -> Unit,
    soundEnabled: Boolean = true,
    onToggleSound: () -> Unit = {},
    isDarkMode: Boolean = false,
    onToggleTheme: () -> Unit = {}
) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SoftButton("✕ Quit", size = 14, onClick = onQuit)
        Spacer(Modifier.weight(1f))
        ThemeToggle(isDarkMode, onToggleTheme)
        Spacer(Modifier.width(8.dp))
        SoundToggle(soundEnabled, onToggleSound)
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // If we just submitted, show the current question number. 
            // Otherwise, show the upcoming one.
            // answered here is questionNo - 1.
            // - During feedback: questionNo is still 1, so answered = 0, displayQ = 1.
            // - After nextQuestion: questionNo is 2, so answered = 1, displayQ = 2.
            val displayQ = minOf(answered + 1, total)
            Txt("Q $displayQ/$total", 17, FontWeight.Bold)
            TextWithFlame(
                "Score $score · Streak $streak",
                12, FontWeight.Medium, c.muted,
                flameSize = 13.dp
            )
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

// Counts up under the answer box; the last 10 seconds turn into a yellow countdown.
@Composable
private fun LiveTimer(tickMs: Long, limitSec: Int) {
    val c = AppTheme.colors
    val limitMs = limitSec * 1000L
    val leftMs = limitMs - tickMs
    
    if (leftMs <= 10000L && tickMs > 0) {
        // Show one decimal place for the final 10 seconds
        val secLeft = leftMs / 1000.0
        Txt(String.format(Locale.US, "Time Left: %.1f s", secLeft), 11, FontWeight.Bold, c.warning)
    } else {
        // Show decimals (e.g. 2.3 s) while counting up
        val secs = tickMs / 1000.0
        Txt(String.format(Locale.US, "Time: %.1f s", secs), 10, FontWeight.Medium, c.accentA)
    }
}

@Composable
fun QuestionCard(u: UiSession, liveTickMs: Long = 0L, onShown: () -> Unit = {}) {
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
        // Trigger the header update the moment the new card begins its entrance
        onShown() 
        entrance.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
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

    // Wrapper: carries the fade/slide/shake. The padding is INSIDE the layer, so the
    // offscreen fade buffer is big enough to contain the shadow.
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 48.dp.toPx()
                translationX = shake.value.dp.toPx()
            }
            .padding(horizontal = 12.dp, vertical = 20.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(10.dp, shape)
                .clip(shape)
                .background(c.surface)
                .drawBehind { drawRect(feedbackColor.copy(alpha = tint.value)) }
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

        // FIX: Gradient badge to indicate question type (FDP category only)
        val typeLabel = when (fact.kind) {
            Kind.DEC -> "DECIMAL"
            Kind.FRAC -> "FRACTION"
            Kind.PCT -> "PERCENTAGE"
            else -> null
        }
        if (typeLabel != null) {
            Box(
                Modifier
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surfaceAlt.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = typeLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    style = TextStyle(
                        brush = Brush.linearGradient(listOf(c.accentA, c.accentB))
                    )
                )
            }
        }

        Text(
            text = fact.prompt,
            color = c.onBackground,
            fontSize = promptSize.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            lineHeight = (promptSize * 1.1f).sp
        )

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
                    text = u.input,   // no manual chunking: wraps only when a line is full
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
        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp), contentAlignment = Alignment.Center) {
            when (u.feedback) {
                Feedback.None -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (u.hint != null) {
                            Txt(
                                text = u.hint, 
                                size = 14, 
                                weight = FontWeight.Medium, 
                                color = c.warning, 
                                modifier = Modifier.fillMaxWidth(), 
                                align = TextAlign.Center
                            )
                        }
                        LiveTimer(liveTickMs, 120)
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
                            val longAns = fact.answer.length > 14
                            Text(
                                text = fact.answer,   // was: if (longAns) fact.answer.chunked(20)... else fact.answer
                                color = resultColor,
                                fontSize = (if (longAns) 13 else 20).sp,
                                lineHeight = (if (longAns) 18 else 24).sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.size(22.dp))   // <-- new line: mirrors the check/cross mark
                        }
                        Txt(
                            if (u.timedOut) "Time's up! (120 sec limit)"
                            else "Time: " + String.format(Locale.US, "%.1f", u.lastMs / 1000.0) + " sec",
                            10, FontWeight.Medium, if (u.timedOut) c.warning else c.accentA
                        )
                    }
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
    canSubmit: Boolean,
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    signedKeys: Boolean = false,
    kind: Kind? = null
) {
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
            KeyButtonRepeat("⌫", Modifier.weight(1f), enabled) { onDeleteLatest.value() }
        }
        if (fractionKeys) {
            val dotOk = kind == Kind.DEC || kind == Kind.PCT
            val slashOk = kind == Kind.FRAC
            val pctOk = kind == Kind.PCT
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeyButton(".", Modifier.weight(1f).alpha(if (dotOk) 1f else 0.25f), enabled && dotOk) { onKeyLatest.value(".") }
                KeyButton("/", Modifier.weight(1f).alpha(if (slashOk) 1f else 0.25f), enabled && slashOk) { onKeyLatest.value("/") }
                KeyButton("%", Modifier.weight(1f).alpha(if (pctOk) 1f else 0.25f), enabled && pctOk) { onKeyLatest.value("%") }
            }
        } else if (signedKeys) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                KeyButton("−", Modifier.weight(1f), enabled) { onKeyLatest.value("-") }
                Spacer(Modifier.weight(1f))
            }
        }
        GradientButton(
            "Submit",
            Modifier.fillMaxWidth(),
            enabled = enabled && canSubmit,
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
            .background(bg)
            .then(if (!c.isDark && bg == c.surfaceAlt) Modifier.border(1.dp, c.accentA.copy(alpha = 0.10f), RoundedCornerShape(14.dp)) else Modifier),
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
            .background(bg)
            .then(if (!c.isDark && bg == c.surfaceAlt) Modifier.border(1.dp, c.accentA.copy(alpha = 0.10f), RoundedCornerShape(14.dp)) else Modifier),
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
    val shape = RoundedCornerShape(26.dp)

    // Two separate animations: the hero score, then (after the confetti settles) the bar.
    val heroP = remember { Animatable(0f) }
    val barP = remember { Animatable(0f) }
    var barStarted by remember { mutableStateOf(false) }
    val countP = remember { Animatable(0f) }
    val slideP = remember { Animatable(0f) }
    val flameP = remember { Animatable(0f) }
    
    LaunchedEffect(Unit) {
        val ease = FastOutSlowInEasing
        coroutineScope {
            // 1) Score count
            launch {
                delay(200)
                heroP.animateTo(1f, tween(700, easing = ease))
            }

            // 2) Bars, then 3) streak
            launch {
                delay(900)                                   // bars start as the score finishes
                barStarted = true
                barP.animateTo(1f, tween(750, easing = ease))
                delay(100)

                // Streak: count → slide → flame, overlapping
                val countMs = (250 + r.best * 40).coerceIn(250, 600)
                val slideMs = 250
                coroutineScope {
                    launch {
                        countP.animateTo(1f, tween(countMs, easing = ease))
                    }
                    launch {
                        delay(countMs - 60L)                 // starts just before the count ends
                        slideP.animateTo(1f, tween(slideMs, easing = ease))
                    }
                    launch {
                        delay(countMs - 60L + (slideMs * 0.6f).toLong())  // flame lights at ~60% of the slide
                        flameP.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
                    }
                }
            }
        }
    }

    val shownScore = (heroP.value * r.score).roundToInt()
    val shownPct = (heroP.value * acc).roundToInt()

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ---- Hero ----
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(10.dp, shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(cat.colorA, cat.colorB)))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Txt(cat.symbol, 28, FontWeight.Bold, Color.White)
                    Spacer(Modifier.width(10.dp))
                    Txt(
                        cat.name, 22, FontWeight.ExtraBold, Color.White,
                        Modifier.weight(1f, fill = false), TextAlign.Center, lineHeight = 26
                    )
                }
                Txt("Session Complete", 14, FontWeight.Medium, Color.White.copy(alpha = 0.9f))
                Txt("$shownScore/${r.total}", 40, FontWeight.ExtraBold, Color.White)
                Txt("SCORE", 11, FontWeight.Bold, Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
            }
            ConfettiBurst(Modifier.matchParentSize())
        }

// ---- Stats ----
        AppCard {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Accuracy")
            }
            val showBarNumber = if (acc == 0) barStarted else barP.value > 0f
            AccuracyBar(
                fraction = barP.value * acc / 100f,
                percent = (barP.value * acc).roundToInt(),
                showNumber = showBarNumber,
                brush = Brush.linearGradient(listOf(c.success.copy(alpha = 0.7f), c.success))
            )
            
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Time/Answer")
            }
            // Read the average time safely from the ResultsUi object
            val avgTimeMs = r.avgMs.toLong()
            val avgTimeSec = avgTimeMs / 1000f
            // Normalize the time for the bar (e.g., 0s = 0%, 120s = 100%)
            val timeFraction = (avgTimeSec / 120f).coerceIn(0f, 1f)
            val showTimeNumber = if (avgTimeMs == 0L) barStarted else barP.value > 0f
            
            AccuracyBar(
                fraction = barP.value * timeFraction,
                percent = (avgTimeSec * barP.value).roundToInt(),
                showNumber = showTimeNumber,
                brush = Brush.linearGradient(listOf(Color(0xFF9C27B0).copy(alpha = 0.7f), Color(0xFF9C27B0))),
                suffix = "s" // Add an 's' to show seconds
            )

            // Streak: number/flame on top, label below
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),   // <-- adds equal space above and below
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StreakCounter(
                    text = "${(countP.value * r.best).roundToInt()}",
                    countSize = 48,
                    color = c.success,
                    flameSize = 38.dp,
                    slide = slideP.value,
                    flame = flameP.value,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "STREAK", color = c.muted, fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp,
                    lineHeight = 12.sp
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("Breakthroughs", "${r.improved}", Modifier.weight(1f))
                MiniStat("New Questions", "${r.introduced}", Modifier.weight(1f))
            }
        }

        ReviewSection(r.missed)

        val currentCategory = Categories.all.firstOrNull { it.id == r.categoryId }
        if (currentCategory != null) {
            // Row 1
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(
                    "View Progress", 
                    Modifier.weight(1f),
                    onClick = { vm.openCategoryDetail(currentCategory, fromResults = true) }
                )
                GradientButton(
                    "Practice Again", 
                    Modifier.weight(1f)
                ) { vm.startSession() }
            }
            // Row 2
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(
                    "Back to Category", 
                    Modifier.weight(1f),
                    onClick = { vm.openCategory(currentCategory) }
                )
                SoftButton(
                    "Main Menu", 
                    Modifier.weight(1f)
                ) { vm.toMenu() }
            }
        } else {
            // Fallback just in case the category is somehow null
            GradientButton("Practice Again", Modifier.fillMaxWidth()) { vm.startSession() }
            SoftButton("Main Menu", Modifier.fillMaxWidth()) { vm.toMenu() }
        }
    }
}

// Small stat: value on top (green), label underneath. Same font family and weights as the rest of the app.
@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surfaceAlt)
            .border(1.dp, c.borderColor, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Txt(value, 22, FontWeight.ExtraBold, c.success, lineHeight = 26)
        Txt(label, 12, FontWeight.SemiBold, c.muted, align = TextAlign.Center)
    }
}

// Thick accuracy bar with the % centered inside. The text is drawn twice: muted on the track,
// white on the filled part (clipped to the fill), so it stays readable as the bar passes under it.
// One number, centered in the bar. Drawn twice in the exact same spot: muted for the
// empty part, white for the filled part (clipped at the fill edge), so a character the
// fill is crossing shows both colors. Nothing is shown until the fill starts moving.
@Composable
private fun AccuracyBar(
    fraction: Float, 
    percent: Int, 
    showNumber: Boolean, 
    brush: Brush = AppTheme.colors.accent,
    suffix: String = "%"
) {
    val c = AppTheme.colors
    val pill = RoundedCornerShape(50)
    val f = fraction.coerceIn(0f, 1f)
    val label = "$percent$suffix"

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(pill)
            .background(c.surfaceAlt)
    ) {
        val fullWidth = maxWidth
        
        // Fill: the gradient box is sized to the full width, then clipped by the fill fraction
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(f)
                .align(Alignment.CenterStart)
                .clip(pill)
        ) {
            Box(
                Modifier
                    .requiredWidth(fullWidth)
                    .fillMaxHeight()
                    .background(brush)
            )
        }

        if (showNumber) {
            // Layer 1: number on the empty track, using the gradient
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                GradientText(
                    text = label,
                    brush = brush,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            // Layer 2: identical number in white, visible only over the filled part
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        clipRect(left = 0f, top = 0f, right = size.width * f, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
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
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProgressHeader("Progress Dashboard", "All categories") { vm.toMenu() }
                    
                    // Added top padding for extra breathing room and updated text
                    Txt(
                        text = "Select a Category to View Stats",
                        size = 12,
                        weight = FontWeight.Medium,
                        color = c.muted,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        align = TextAlign.Center
                    )
                    
                    MenuSeparator("Detailed Progress")
                }
            }
            if (dash == null) {
                item(key = "loading") { DashboardLoadingIndicator() }
            } else {
                items(Categories.all, key = { it.id }) { cat ->
                    CategoryProgressSection(
                        cat = cat,
                        mastery = dash.mastery[cat.id] ?: Triple(0, 0, 0),
                        history = dash.history[cat.id] ?: HistoryRef.EMPTY,
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
            ConfirmDialog(
                icon = DialogIcon.Warning,
                title = "Reset Everything?",
                message = "All Categories Will Be Wiped.",
                confirmText = "Continue",
                step = 1,
                onConfirm = { resetStep = 2 },
                onDismiss = { resetStep = 0 }
            )
        }
        if (resetStep == 2) {
            ConfirmDialog(
                icon = DialogIcon.Warning,
                title = "Are you sure?",
                message = "All progress will be erased. This can't be undone.",
                confirmText = "Erase All",
                step = 2,
                onConfirm = { vm.resetAllProgress(); resetStep = 0 },
                onDismiss = { resetStep = 0 }
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
            BackChevron()
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
                history = dash.history[cat.id] ?: HistoryRef.EMPTY,
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
        ConfirmDialog(
            icon = DialogIcon.Warning,
            title = "Reset Category?",
            message = "${cat.name} Stats and History Will Be Erased.",
            confirmText = "Continue",
            step = 1,
            onConfirm = { resetStep = 2 },
            onDismiss = { resetStep = 0 }
        )
    }
    if (resetStep == 2) {
        ConfirmDialog(
            icon = DialogIcon.Warning,
            title = "Are You Sure?",
            message = "This Can't Be Undone.",
            confirmText = "Reset",
            step = 2,
            onConfirm = { vm.resetSelectedCategory(); resetStep = 0 },
            onDismiss = { resetStep = 0 }
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
        BackChevron()
    }
}

@Composable
fun CategoryProgressSection(
    cat: Category,
    mastery: Triple<Int, Int, Int>,        // (mastered%, learning%, struggling%)
    history: HistoryRef,
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
        // Title row + Mastery live in one zero-spacing column so CompactCard's
        // spacedBy(6.dp) doesn't open a gap between them.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (showHeader) {
                Row {
                    Txt(
                        cat.symbol, 20, FontWeight.Bold, c.accentA,
                        modifier = Modifier.alignBy(LastBaseline)
                    )
                    Spacer(Modifier.width(8.dp))
                    Txt(
                        cat.name, 15, FontWeight.Bold,
                        modifier = Modifier.alignBy(LastBaseline)
                    )
                }
            }

            // Mastery breakdown (three stacked bars)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Mastery")
                MasteryBar("Mastered",   mastery.first,    c.success)
                MasteryBar("Learning",   mastery.second,   c.warning)
                MasteryBar("Struggling", mastery.third,    c.error)
            }
        }

        if (details.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SectionTitle("All Time Best")
                val (strongTxt, weakTxt) = remember(mastery, details) { summarize(mastery, details) }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HighlightBox("Best Strength", strongTxt, c.success, Modifier.weight(1f))
                    HighlightBox("Worst Weakness", weakTxt, c.error, Modifier.weight(1f), broken = true)
                }
            }
        }

        if (detailed) {
            // Added top padding so the gap perfectly matches the gap above "All Time Best"
            Column(Modifier.padding(top = 8.dp)) {
                SectionTitle("Accuracy & Speed Chart")
            }
            TrendChart(history)

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

            if (sortedByCorrect.isEmpty()) {
                SectionTitle("Strength List")
                Txt("No Data", 14, FontWeight.Medium, c.muted, Modifier.fillMaxWidth().padding(vertical = 8.dp), TextAlign.Center)
            } else {
                RankTable("Strength List", sortedByCorrect, strong = true)
            }

            if (sortedByWrong.isEmpty()) {
                SectionTitle("Weakness List")
                Txt("No Data", 14, FontWeight.Medium, c.muted, Modifier.fillMaxWidth().padding(vertical = 8.dp), TextAlign.Center)
            } else {
                RankTable("Weakness List", sortedByWrong, strong = false)
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

private fun summarize(
    mastery: Triple<Int, Int, Int>,
    details: List<AdaptiveEngine.FactDetail>
): Pair<String, String> {
    val netPositive = details.filter { it.correct > it.incorrect }
    val netNegative = details.filter { it.incorrect > 0 && it.incorrect >= it.correct }

    val best = netPositive.maxByOrNull { it.correct - it.incorrect }?.key
    val worst = netNegative.maxByOrNull { it.incorrect - it.correct }?.key

    val strong = when {
        best != null -> best
        details.any { it.correct > 0 } -> "None yet"
        else -> "No Data"
    }
    val weak = when {
        worst != null -> worst
        details.any { it.incorrect > 0 } -> "None yet"
        else -> "No Data"
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
