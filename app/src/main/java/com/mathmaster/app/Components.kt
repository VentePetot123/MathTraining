package com.mathmaster.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.key
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Txt(
    text: String,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    color: Color = AppTheme.colors.onBackground,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start
) {
    Text(
        text = text, modifier = modifier, color = color,
        fontSize = size.sp, fontWeight = weight, textAlign = align
    )
}

// Animation: tap feedback on every button = spring scale-down + ripple (clipped to the shape).
// FIX #8: perf — a CompositionLocal carries the "is navigating" signal to every
// clickable. Using a local instead of `viewModel()` in bounceClick makes it usable
// in previews, tests, and any composable — no ViewModelStoreOwner required.
val LocalNavLock = androidx.compose.runtime.staticCompositionLocalOf { false }

// FIX #8: perf — snappier bounceClick. Changes vs. previous version:
//   • Animation is a tween(140) instead of spring(500) — feels immediate, still
//     reads as "pressed". Spring was 300-400ms before release; this is 140ms.
//   • `LocalNavLock` is read ONCE per composition (via remember) and captured in the
//     click callback. It's a stable value at click time — reading it inside onClick
//     is illegal because CompositionLocal reads are @Composable-only.
//   • Debounce still applies (350ms) — prevents double-fire of the same action.
fun Modifier.bounceClick(
    shape: Shape = RoundedCornerShape(16.dp),
    elevation: Dp = 0.dp,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val lastTap = remember { mutableLongStateOf(0L) }
    // Read the nav lock in composition (allowed), then close over the value.
    // `rememberUpdatedState` keeps the captured value fresh across recompositions
    // without re-triggering the modifier chain.
    val navLockState = androidx.compose.runtime.rememberUpdatedState(LocalNavLock.current)
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "pressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            shadowElevation = elevation.toPx()
            this.shape = shape
            clip = true
        }
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = {
                // Read the CURRENT lock value (not a stale snapshot). This is a
                // plain State<T>.value read — legal in a callback.
                if (navLockState.value) return@clickable
                val now = SystemClock.uptimeMillis()
                if (now - lastTap.longValue < DEBOUNCE_MS) return@clickable
                lastTap.longValue = now
                onClick()
            }
        )
}

// FIX #8: perf — 350ms covers accidental double-taps without making the UI feel
// sluggish. Typical touchscreen double-tap threshold is 300ms; we go slightly above.
private const val DEBOUNCE_MS = 350L

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = AppTheme.colors.accent,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    // FIX #8: perf — fixed 48dp height so it matches SoftButton when they share a row.
    Box(
        modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .bounceClick(shape, 8.dp, enabled = enabled, onClick = onClick)
            .background(brush),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, 17, FontWeight.Bold, Color.White)
    }
}

@Composable
fun SoftButton(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = AppTheme.colors.surfaceAlt,
    content: Color = AppTheme.colors.onBackground,
    size: Int = 15,
    onClick: () -> Unit
) {
    // FIX #8: perf — fixed 48dp height, matching GradientButton. Padding is applied
    // horizontally only; vertical space comes from the height constraint. This makes
    // the two buttons pixel-identical in height when paired in a Row.
    Box(
        modifier
            .height(48.dp)
            .bounceClick(RoundedCornerShape(14.dp), 0.dp, onClick = onClick)
            .background(container)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, size, FontWeight.SemiBold, content)
    }
}

// FIX #8: perf — compact variant for the range screen so both config cards
// (Number range + Questions) fit on-screen with no vertical scroll.
@Composable
fun CompactCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp, shape = shape, clip = false,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .clip(shape)
            .background(AppTheme.colors.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp, shape = shape, clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.15f)
            )
            .clip(shape)
            .background(AppTheme.colors.surface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

// Animation: progress bar fills smoothly whenever the value changes.
@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    height: Dp = 12.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(if (animate) 500 else 0, easing = FastOutSlowInEasing),
        label = "progress"
    )
    val pill = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(pill)
            .background(AppTheme.colors.surfaceAlt)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(pill)
                .background(AppTheme.colors.accent)
        )
    }
}

@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        modifier
            .size(size)
            .shadow(12.dp, shape)
            .clip(shape)
            .background(AppTheme.colors.accent)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.toPx()
            val stroke = w * 0.09f
            drawLine(Color.White, Offset(w * 0.5f, w * 0.25f), Offset(w * 0.5f, w * 0.75f), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(w * 0.25f, w * 0.5f), Offset(w * 0.75f, w * 0.5f), stroke, StrokeCap.Round)
        }
    }
}

// Animation: checkmark is drawn stroke-by-stroke; a cross is drawn for wrong answers.
@Composable
fun FeedbackMark(correct: Boolean, seq: Int, modifier: Modifier = Modifier) {
    val progress = remember(seq, correct) { Animatable(0f) }
    LaunchedEffect(seq, correct) { progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    val color = if (correct) AppTheme.colors.success else AppTheme.colors.error
    Canvas(modifier.size(40.dp)) {
        val w = size.width
        val strokeWidth = w * 0.12f
        val p = progress.value
        if (correct) {
            val path = Path().apply {
                moveTo(w * 0.18f, w * 0.52f)
                lineTo(w * 0.42f, w * 0.74f)
                lineTo(w * 0.82f, w * 0.28f)
            }
            val measure = PathMeasure()
            measure.setPath(path, false)
            val segment = Path()
            measure.getSegment(0f, measure.length * p, segment, true)
            drawPath(
                segment, color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        } else {
            val a = (p * 2f).coerceAtMost(1f)
            val b = ((p - 0.5f) * 2f).coerceIn(0f, 1f)
            drawLine(
                color, Offset(w * 0.25f, w * 0.25f),
                Offset(w * (0.25f + 0.5f * a), w * (0.25f + 0.5f * a)), strokeWidth, StrokeCap.Round
            )
            if (b > 0f) {
                drawLine(
                    color, Offset(w * 0.75f, w * 0.25f),
                    Offset(w * (0.75f - 0.5f * b), w * (0.25f + 0.5f * b)), strokeWidth, StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun Stepper(label: String, value: Int, step: Int, onChange: (Int) -> Unit) {
    val c = AppTheme.colors
    // FIX #8: perf — the middle value is now tap-to-edit. Tapping it opens a compact
    // numeric keypad dialog; confirming clamps the value to 1..99 before calling
    // onChange, so out-of-range input can never leak into the VM.
    var editing by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 13, FontWeight.Medium, c.muted, Modifier.weight(1f))
        RoundButtonSmall("−") { onChange(value - step) }
        // Tap target for the value
        Box(
            Modifier
                .width(52.dp)
                .bounceClick(RoundedCornerShape(8.dp), 0.dp, onClick = { editing = true }),
            contentAlignment = Alignment.Center
        ) {
            Txt(value.toString(), 18, FontWeight.Bold, c.onBackground, align = TextAlign.Center)
        }
        RoundButtonSmall("+") { onChange(value + step) }
    }

    if (editing) {
        NumberInputDialog(
            initial = value,
            onDismiss = { editing = false },
            onConfirm = { picked ->
                editing = false
                onChange(picked)     // VM applies its own coerceIn on top of this
            }
        )
    }
}

// FIX #8: perf — Compose-native trend chart. Draws in a single Canvas pass; no
// per-frame allocations (paths and gradients are remembered on the input data).
//   • teal line  = accuracy % (y 0..180 as required by spec)
//   • pink line  = inverse-scaled avg response time (lower ms → higher y)
//   • cubic-bezier smoothing, filled gradient below each line, no point markers.
@Composable
fun TrendChart(
    history: List<SessionPoint>,
    modifier: Modifier = Modifier
) {
    if (history.size < 2) {
        // FIX #8: perf — compact empty state (was 160dp; the tall box dominated the
        // dashboard card when there was no data yet).
        Box(
            modifier.fillMaxWidth().height(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1B2233)),
            contentAlignment = Alignment.Center
        ) {
            Txt("Practice more to see your trend", 12, FontWeight.Medium, Color.White.copy(alpha = 0.6f))
        }
        return
    }

    val panel = Color(0xFF1B2233)
    val grid = Color.White.copy(alpha = 0.06f)
    val teal = Color(0xFF3DD9A0)
    val pink = Color(0xFFE64C8A)
    val axis = Color.White.copy(alpha = 0.55f)

    val accuracy = remember(history) { history.map { it.accuracyPct.toFloat().coerceIn(0f, 100f) } }
    val times = remember(history) {
        val maxMs = history.maxOf { it.avgMs }.coerceAtLeast(1)
        history.map { 180f * (1f - it.avgMs.toFloat() / maxMs) }   // inverse-scale
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(panel)
            .padding(12.dp)
    ) {
        val w = size.width
        val h = size.height
        val left = 28.dp.toPx()
        val bottom = 20.dp.toPx()
        val plotW = w - left
        val plotH = h - bottom

        // Y grid: 10 ticks over 0..180
        for (i in 0..10) {
            val y = plotH - plotH * (i / 10f)
            drawLine(grid, Offset(left, y), Offset(w, y), 1f)
            // tiny muted y label
        }

        // X grid: one per session
        val n = history.size
        val stepX = if (n > 1) plotW / (n - 1) else plotW
        fun x(i: Int) = left + stepX * i
        for (i in 0 until n) {
            drawLine(grid, Offset(x(i), 0f), Offset(x(i), plotH), 1f)
        }

        fun yFor(v: Float) = plotH - plotH * (v.coerceIn(0f, 180f) / 180f)

        // Build smooth cubic path through points
        fun buildPath(values: List<Float>): Path {
            val p = Path()
            if (values.isEmpty()) return p
            p.moveTo(x(0), yFor(values[0]))
            for (i in 0 until values.size - 1) {
                val x0 = x(i); val y0 = yFor(values[i])
                val x1 = x(i + 1); val y1 = yFor(values[i + 1])
                val cx = (x0 + x1) / 2f
                p.cubicTo(cx, y0, cx, y1, x1, y1)
            }
            return p
        }

        fun fillUnder(p: Path, values: List<Float>, color: Color) {
            val f = Path().apply {
                addPath(p)
                lineTo(x(values.size - 1), plotH)
                lineTo(x(0), plotH)
                close()
            }
            drawPath(
                f,
                brush = Brush.verticalGradient(
                    colors = listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f)),
                    startY = 0f, endY = plotH
                )
            )
        }

        val accPath = buildPath(accuracy)
        val timePath = buildPath(times)

        fillUnder(timePath, times, pink)
        fillUnder(accPath, accuracy, teal)

        drawPath(timePath, pink, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
        drawPath(accPath, teal, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
    }
}

@Composable
fun RoundButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .bounceClick(CircleShape, 2.dp, onClick = onClick)
            .background(AppTheme.colors.surfaceAlt),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, 22, FontWeight.Bold, AppTheme.colors.onBackground)
    }
}

// FIX #8: perf — compact variant used by the range screen so its rows fit without
// growing the layout. Same visual language, smaller footprint.
@Composable
fun RoundButtonSmall(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .bounceClick(CircleShape, 2.dp, onClick = onClick)
            .background(AppTheme.colors.surfaceAlt),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, 16, FontWeight.Bold, AppTheme.colors.onBackground)
    }
}

// FIX #8: perf — compact numeric entry dialog used by the range steppers. Reuses the
// same keypad layout as the training screen (1-9, C, 0, ⌫) but keeps its own local
// buffer so the current value isn't overwritten until the user confirms.
// FIX #8: perf — numeric entry dialog driven by the SYSTEM keyboard in number mode.
// Uses BasicTextField with KeyboardType.Number, so the phone shows its numeric pad
// (not the full QWERTY). The field gets a real blinking cursor and standard text
// editing (selection, backspace) from the platform. Input is capped at 2 digits;
// a 3rd digit is rejected and triggers the MaxDigitsToast.
@Composable
fun NumberInputDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val c = AppTheme.colors
    var buffer by remember { mutableStateOf(initial.toString()) }
    var maxDigitsTick by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    // FIX #8: perf — open the soft keyboard automatically and put the caret at the
    // end, so the user can start typing immediately without tapping the field.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.onBackground,
        textContentColor = c.muted,
        title = { Txt("Enter value (1–99)", 16, FontWeight.Bold) },
        text = {
            // FIX #8: perf — split the visual into two layers:
            //  1. A centered Text shows the digits (always dead-center).
            //  2. A transparent BasicTextField sits on top for typing, with its text
            //     color set to Transparent so only the cursor is visible. Its text is
            //     right-aligned, which puts the blinking cursor at the far-right edge.
            // The Box wraps both so the ripple / focus rectangle look like one field.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceAlt),
                contentAlignment = Alignment.Center
            ) {
                // Visible digits — centered, no cursor
                Txt(
                    if (buffer.isEmpty()) "—" else buffer,
                    28,
                    FontWeight.Bold,
                    if (buffer.isEmpty()) c.muted else c.onBackground,
                    align = TextAlign.Center
                )
                // Invisible overlay for actual input + cursor.
                // Text color is transparent so only the blinking cursor shows; the
                // text is right-aligned so the cursor rides the right edge of the field.
                BasicTextField(
                    value = buffer,
                    onValueChange = { new ->
                        val digitsOnly = new.filter { it.isDigit() }
                        when {
                            digitsOnly.length <= 2 -> buffer = digitsOnly
                            else -> maxDigitsTick++
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val parsed = buffer.toIntOrNull()
                            if (parsed != null && parsed in 1..99) onConfirm(parsed)
                        }
                    ),
                    cursorBrush = SolidColor(c.accentA),
                    textStyle = TextStyle(
                        color = Color.Transparent,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .padding(horizontal = 16.dp)
                )
            }
        },
        confirmButton = {
            val parsed = buffer.toIntOrNull()
            val valid = parsed != null && parsed in 1..99
            TextButton(
                onClick = { if (valid) onConfirm(parsed!!) },
                enabled = valid
            ) {
                Txt(
                    "Set",
                    15,
                    FontWeight.Bold,
                    if (valid) c.accentA else c.muted
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Txt("Cancel", 15, FontWeight.Medium, c.muted) }
        }
    )

    // FIX #8: perf — toast overlay in its own Dialog so it draws above the AlertDialog.
    if (maxDigitsTick > 0) {
        key(maxDigitsTick) {
            Dialog(
                onDismissRequest = { /* non-interactive; auto-dismisses via fade */ },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false
                )
            ) {
                // FIX #8: perf — reset the tick when the fade completes. This removes
                // the Dialog from composition entirely, so the invisible alpha=0 touch
                // blocker no longer overlaps the underlying input dialog.
                MaxDigitsToast(onDismiss = { maxDigitsTick = 0 })
            }
        }
        // FIX #8: perf — hard safety net. If the toast's own onDismiss ever fails to
        // fire (e.g. rotation interrupts the animation), force-unmount after 2s so the
        // user is never stuck behind an invisible touch blocker.
        LaunchedEffect(maxDigitsTick) {
            kotlinx.coroutines.delay(2000)
            if (maxDigitsTick > 0) maxDigitsTick = 0
        }
    }
}

// FIX #8: perf — brief full-screen warning shown when the user tries to exceed the
// digit cap. Rendered as an overlay sibling so it never invalidates the dialog's
// layout. Fade in → hold → fade out, then it's removed from composition entirely.
// Uses graphicsLayer + alpha only; no layout animations, no per-frame allocations.
@Composable
fun MaxDigitsToast(
    message: String = "Max Digits",
    onDismiss: () -> Unit = {}
) {
    val alpha = remember(message) { Animatable(0f) }
    LaunchedEffect(message) {
        alpha.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(600)
        alpha.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        // FIX #8: perf — tell the parent to unmount us. Without this, the invisible
        // alpha=0 touch-blocker Box stays composed and freezes the app underneath.
        onDismiss()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            // FIX #8: perf — block touches so the underlying keypad can't receive input
            // while the toast is visible.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .background(Color(0x660F1020)),       // light scrim, ~40% opacity
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .shadow(8.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(AppTheme.colors.surface)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Txt(
                message,
                18,
                FontWeight.ExtraBold,
                AppTheme.colors.onBackground,
                align = TextAlign.Center
            )
        }
    }
}
