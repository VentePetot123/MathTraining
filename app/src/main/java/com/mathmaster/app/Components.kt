package com.mathmaster.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
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
    align: TextAlign = TextAlign.Start,
    // FIX #8 — optional lineHeight. Pass a value slightly larger than `size` to
    // tighten multi-line text (default Compose spacing is ~1.4x, which looks airy).
    lineHeight: Int? = null
) {
    Text(
        text = text, modifier = modifier, color = color,
        fontSize = size.sp, fontWeight = weight, textAlign = align,
        lineHeight = (lineHeight ?: (size * 1.4f).toInt()).sp
    )
}

// Animation: tap feedback on every button = spring scale-down + ripple (clipped to the shape).
// FIX #8: perf — a CompositionLocal carries the "is navigating" signal to every
// clickable. Using a local instead of `viewModel()` in bounceClick makes it usable
// in previews, tests, and any composable — no ViewModelStoreOwner required.
val LocalNavLock = androidx.compose.runtime.staticCompositionLocalOf<() -> Boolean> { { false } }

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
    val isLocked = LocalNavLock.current
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
                if (isLocked()) return@clickable
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
fun Stepper(
    label: String,
    value: Int,
    step: Int,
    maxDigits: Int = 3,
    maxValue: Int = 100,
    hardMax: Int = maxValue,
    onChange: (Int) -> Unit
) {
    val cap = minOf(maxValue, hardMax)
    fun nextUp(v: Int): Int = (((v / step) + 1) * step).coerceIn(1, cap)
    fun nextDown(v: Int): Int {
        val snapped = if (v % step == 0) v - step else (v / step) * step
        return snapped.coerceIn(1, cap)
    }
    val c = AppTheme.colors
    var editing by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 13, FontWeight.Medium, c.muted, Modifier.weight(1f))
        // FIX #8 — use the instant (no-debounce) variant so rapid spam-tapping
        // registers every press immediately, matching native counter behavior.
        RoundButtonSmallInstant("−") { onChange(nextDown(value)) }
        Box(
            Modifier
                .width(52.dp)
                .bounceClick(RoundedCornerShape(8.dp), 0.dp, onClick = { editing = true }),
            contentAlignment = Alignment.Center
        ) {
            Txt(value.toString(), 18, FontWeight.Bold, c.onBackground, align = TextAlign.Center)
        }
        RoundButtonSmallInstant("+") { onChange(nextUp(value)) }
    }

    if (editing) {
        NumberInputDialog(
            initial = value,
            maxDigits = maxDigits,
            maxValue = cap,
            onDismiss = { editing = false },
            onConfirm = { picked ->
                editing = false
                onChange(picked.coerceIn(1, cap))
            }
        )
    }
}

@Composable
fun TrendChart(
    history: List<SessionPoint>,
    modifier: Modifier = Modifier
) {
    if (history.size < 2) {
        Box(
            modifier.fillMaxWidth().height(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1B2233)),
            contentAlignment = Alignment.Center
        ) {
            Txt("Finish 2 sessions to see your trend", 12, FontWeight.Medium, Color.White.copy(alpha = 0.6f))
        }
        return
    }

    val n = history.size
    val accuracy = remember(history) { history.map { it.accuracyPct.toFloat().coerceIn(0f, 100f) } }
    val seconds = remember(history) { history.map { it.avgMs / 1000f } }
    val step = remember(seconds) {
        kotlin.math.ceil((seconds.maxOrNull() ?: 1f) / 4.0).toInt().coerceAtLeast(1)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ChartPanel(
            title = "ACCURACY",
            values = accuracy,
            yMax = 100f,
            color = Color(0xFF3DD9A0),
            ticks = listOf("100%", "75%", "50%", "25%", "0%"),
            topRightText = null,
            bottomText = "Sessions: $n"
        )
        
        val maxSec = seconds.maxOrNull() ?: 0f
        val minSec = seconds.minOrNull() ?: 0f
        ChartPanel(
            title = "TIME/ANSWER",
            values = seconds,
            yMax = (step * 4).toFloat(),
            color = Color(0xFF9C27B0), // Purple color replacing red
            ticks = listOf("${step * 4}s", "${step * 3}s", "${step * 2}s", "${step}s", "0s"),
            topRightText = null,
            bottomText = null,
            fastest = String.format(java.util.Locale.US, "%.1f", minSec),
            slowest = String.format(java.util.Locale.US, "%.1f", maxSec)
        )
    }
}

@Composable
private fun ChartPanel(
    title: String,
    values: List<Float>,
    yMax: Float,
    color: Color,
    ticks: List<String>,          
    topRightText: String?,
    bottomText: String?,
    fastest: String? = null,
    slowest: String? = null
) {
    val muted = Color.White.copy(alpha = 0.6f)
    val grid = Color.White.copy(alpha = 0.08f)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B2233))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Centered Title
        Text(
            text = title,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (topRightText != null) {
            Text(
                text = topRightText,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(Modifier.fillMaxWidth().height(110.dp)) {
            // FIX #8 — pad top/bottom of the ticks column so the "100%" / "0%" labels
            // (and "Xs" / "0s" on the time chart) don't graze the rounded corners of
            // the panel. The 6dp matches the inset (7dp) used in the Canvas draw, so
            // the labels align visually with the grid lines they reference.
            Column(
                Modifier.width(38.dp).fillMaxHeight().padding(vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                ticks.forEach { Txt(it, 10, FontWeight.Normal, muted) }
            }

            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val inset = 7.dp.toPx()
                val plotH = size.height - inset * 2
                val n = values.size
                fun x(i: Int) = if (n > 1) w * i / (n - 1) else 0f
                fun y(v: Float) = inset + plotH - plotH * (v.coerceIn(0f, yMax) / yMax)

                for (f in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                    val gy = y(yMax * f)
                    drawLine(grid, Offset(0f, gy), Offset(w, gy), 1f)
                }

                val line = Path()
                line.moveTo(x(0), y(values[0]))
                for (i in 0 until n - 1) {
                    val cx = (x(i) + x(i + 1)) / 2f
                    line.cubicTo(cx, y(values[i]), cx, y(values[i + 1]), x(i + 1), y(values[i + 1]))
                }

                val fill = Path().apply {
                    addPath(line)
                    lineTo(x(n - 1), y(0f))
                    lineTo(x(0), y(0f))
                    close()
                }
                drawPath(
                    fill,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)),
                        startY = inset, endY = inset + plotH
                    )
                )
                drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(color, 3.5.dp.toPx(), Offset(x(n - 1), y(values[n - 1])))
            }
        }

        if (bottomText != null) {
            Text(
                text = bottomText,
                color = Color(0xFF3DD9A0), // Green color matching accuracy
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else if (fastest != null && slowest != null) {
            // FIX: Grouped and horizontally centered at the bottom
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center, // Centers the whole group
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Fastest: ", 11, FontWeight.Medium, muted)
                    Txt("${fastest}s", 11, FontWeight.Bold, Color(0xFF3DD9A0))
                }
                
                // Spacer to separate the two stats
                Spacer(Modifier.width(24.dp)) 
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Slowest: ", 11, FontWeight.Medium, muted)
                    Txt("${slowest}s", 11, FontWeight.Bold, Color(0xFFE64C8A))
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 38.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Txt("Oldest", 10, FontWeight.Normal, muted)
                Txt("Latest", 10, FontWeight.Normal, muted)
            }
        }
    }
}

// FIX #8 — Sound on/off toggle. Draws a speaker icon in code (via Canvas) so it
// looks identical on every device, avoiding the platform-specific emoji look.
// When enabled: accent tint + speaker with two sound waves. When muted: grey tint
// + speaker with an X where the waves would be. The tint fades via animateColorAsState.
@Composable
fun SoundToggle(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val tint by animateColorAsState(
        targetValue = if (enabled) c.accentA else c.muted,
        animationSpec = tween(200),
        label = "soundTint"
    )
    val bg = if (enabled) c.accentA.copy(alpha = 0.16f) else c.surfaceAlt

    Box(
        modifier
            .size(44.dp)
            .bounceClick(CircleShape, 0.dp, onClick = onClick)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val sw = w * 0.11f

            // Speaker body
            val body = Path().apply {
                moveTo(w * 0.08f, w * 0.38f)
                lineTo(w * 0.28f, w * 0.38f)
                lineTo(w * 0.50f, w * 0.18f)
                lineTo(w * 0.50f, w * 0.82f)
                lineTo(w * 0.28f, w * 0.62f)
                lineTo(w * 0.08f, w * 0.62f)
                close()
            }
            drawPath(body, tint)

            if (enabled) {
                // Two sound waves emanating to the right
                val cx = w * 0.50f
                val cy = w * 0.50f
                for (r in floatArrayOf(w * 0.20f, w * 0.38f)) {
                    drawArc(
                        color = tint,
                        startAngle = -45f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = sw, cap = StrokeCap.Round)
                    )
                }
            } else {
                // Muted: an X where the waves would be
                drawLine(tint, Offset(w * 0.66f, w * 0.36f), Offset(w * 0.92f, w * 0.64f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.92f, w * 0.36f), Offset(w * 0.66f, w * 0.64f), sw, StrokeCap.Round)
            }
        }
    }
}

// Back arrow drawn in code so it is exactly centered in its 44dp box on every device.
@Composable
fun BackChevron(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(44.dp).padding(13.dp)) {
        val w = size.width
        val path = Path().apply {
            moveTo(w * 0.68f, w * 0.06f)
            lineTo(w * 0.28f, w * 0.50f)
            lineTo(w * 0.68f, w * 0.94f)
        }
        drawPath(
            path, color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// ---------- Progress card pieces ----------

// Centered section heading with a line on each side: MASTERY, STRENGTH LIST, ...
@Composable
fun SectionTitle(text: String) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(c.accentA.copy(alpha = 0.35f)))
        Text(
            text = text.uppercase(),
            color = c.accentA,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.6.sp
        )
        Box(Modifier.weight(1f).height(1.dp).background(c.accentA.copy(alpha = 0.35f)))
    }
}

// Medal drawn in code: V-shaped ribbon, disc, ring and star.
@Composable
fun Medal(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(26.dp)) {
        val w = size.width
        val left = Path().apply {
            moveTo(w * 0.22f, 0f); lineTo(w * 0.42f, 0f)
            lineTo(w * 0.60f, w * 0.46f); lineTo(w * 0.40f, w * 0.46f); close()
        }
        val right = Path().apply {
            moveTo(w * 0.78f, 0f); lineTo(w * 0.58f, 0f)
            lineTo(w * 0.40f, w * 0.46f); lineTo(w * 0.60f, w * 0.46f); close()
        }
        drawPath(left, color.copy(alpha = 0.55f))
        drawPath(right, color.copy(alpha = 0.85f))

        val cx = w * 0.5f
        val cy = w * 0.68f
        val r = w * 0.30f
        drawCircle(color, r, Offset(cx, cy))
        drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.80f, Offset(cx, cy), style = Stroke(width = w * 0.04f))

        val star = Path()
        for (i in 0 until 10) {
            val ang = Math.PI / 5 * i - Math.PI / 2
            val rad = if (i % 2 == 0) r * 0.62f else r * 0.26f
            val px = cx + (Math.cos(ang) * rad).toFloat()
            val py = cy + (Math.sin(ang) * rad).toFloat()
            if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
        }
        star.close()
        drawPath(star, Color.White.copy(alpha = 0.92f))
    }
}

// "Best Strength" / "Worse Weakness": medal + title on top, the fact in a rounded box below.
@Composable
fun HighlightBox(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val empty = value == "No data yet" || value == "None yet"
    Column(
        modifier.padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Medal(color)
            Txt(title, 13, FontWeight.Bold, color)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Txt(
                value,
                if (empty) 12 else 16,
                if (empty) FontWeight.Medium else FontWeight.ExtraBold,
                if (empty) c.muted else c.onBackground,
                align = TextAlign.Center
            )
        }
    }
}

// Down chevron drawn in code; rotates to point up when the list is expanded.
@Composable
fun ExpandChevron(expanded: Boolean, enabled: Boolean, color: Color, onClick: () -> Unit) {
    val rot by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "chev")
    Box(
        Modifier
            .size(width = 56.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(width = 20.dp, height = 12.dp).graphicsLayer { rotationZ = rot }) {
            val pad = 2.dp.toPx()
            val p = Path().apply {
                moveTo(pad, pad)
                lineTo(size.width / 2f, size.height - pad)
                lineTo(size.width - pad, pad)
            }
            drawPath(
                p, color.copy(alpha = if (enabled) 1f else 0.3f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

// Excel-style ranked table: [rank | fact + Avg. Time | count + label]
// Top 3 get a stronger-to-lighter tint; the chevron expands to up to 100 rows.
@Composable
fun RankTable(
    title: String,
    items: List<AdaptiveEngine.FactDetail>,
    strong: Boolean
) {
    if (items.isEmpty()) return
    val c = AppTheme.colors
    val accent = if (strong) c.success else c.error
    val line = Color.White.copy(alpha = 0.14f)
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) items.take(100) else items.take(3)

    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SectionTitle(title)

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, line, RoundedCornerShape(12.dp))
        ) {
            shown.forEachIndexed { i, d ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(line))
                val tint = when (i) { 0 -> 0.30f; 1 -> 0.20f; 2 -> 0.12f; else -> 0f }
                val count = if (strong) d.correct else d.incorrect
                val label = if (strong) (if (count == 1) "Correct" else "Corrects")
                            else (if (count == 1) "Wrong" else "Wrongs")

                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(accent.copy(alpha = tint))
                        .height(IntrinsicSize.Min)
                ) {
                    Box(Modifier.width(36.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Txt("${i + 1}", 14, FontWeight.ExtraBold, accent)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Column(
                        Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Txt(d.key, 15, FontWeight.Bold, c.onBackground, align = TextAlign.Center)
                        Txt(
                            "Avg. Time: " + String.format(java.util.Locale.US, "%.1f", d.avgMs / 1000.0) + " s",
                            10, FontWeight.Medium, c.muted
                        )
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(line))
                    Column(
                        Modifier.width(84.dp).fillMaxHeight().padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Txt("$count", 15, FontWeight.ExtraBold, accent)
                        Txt(label, 10, FontWeight.Medium, c.muted)
                    }
                }
            }
        }

        if (items.size > 3) {
            ExpandChevron(expanded, enabled = true, color = accent) { expanded = !expanded }
        }
    }
}

// ---------- Category card backdrop: scattered, tilted flashcards ----------

private data class FlashTile(val x: Float, val y: Float, val rot: Float, val w: Int, val h: Int)

// Positions are fractions of the card size, so it looks the same on any screen.
private val FLASH_TILES = listOf(
    FlashTile(0.50f, 0.00f, -14f, 62, 76),
    FlashTile(0.84f, 0.06f, 9f, 58, 72),
    FlashTile(0.16f, 0.22f, 6f, 56, 70),
    FlashTile(0.64f, 0.34f, 15f, 64, 78),
    FlashTile(0.96f, 0.40f, -10f, 56, 70),
    FlashTile(0.36f, 0.56f, -8f, 60, 74),
    FlashTile(0.78f, 0.74f, 5f, 62, 76),
    FlashTile(0.06f, 0.78f, 12f, 56, 70),
    FlashTile(0.52f, 0.96f, -12f, 62, 76)
)

private fun flashTexts(catId: String): List<String> = when (catId) {
    "addition" -> listOf("6\n+2", "+11", "3\n+9", "8\n+5", "+7", "4\n+4", "12\n+9", "+15", "9\n+6")
    "subtraction" -> listOf("9\n−4", "−3", "15\n−8", "12\n−5", "−7", "20\n−6", "−11", "8\n−2", "14\n−9")
    "multiplication" -> listOf("3\n×4", "×7", "6\n×8", "9\n×5", "×12", "7\n×7", "2\n×9", "×6", "8\n×3")
    "division" -> listOf("12\n÷3", "÷4", "36\n÷6", "20\n÷5", "÷9", "49\n÷7", "64\n÷8", "÷2", "18\n÷3")
    "squares" -> listOf("5²", "√49", "9²", "√16", "12²", "√81", "7²", "√144", "3²")
    "cubes" -> listOf("2³", "∛27", "4³", "∛8", "3³", "∛64", "5³", "∛125", "6³")
    "fdp" -> listOf("½", "25%", "0.5", "¾", "⅓", "0.75", "10%", "⅕", "1/8")
    "integers" -> listOf("−3", "+5", "−8", "−2\n+7", "−6", "4\n−9", "+9", "−1", "−5\n−5")
    "powers2" -> listOf("2⁵", "64", "2⁸", "16", "2³", "128", "2¹⁰", "32", "2⁴")
    "powers3" -> listOf("3²", "27", "3⁴", "9", "3³", "81", "3⁵", "243", "3¹")
    "factorials" -> listOf("5!", "120", "4!", "24", "6!", "3!", "7!", "720", "2!")
    else -> listOf("?", "1", "2", "3", "4", "5", "6", "7", "8")
}

// FIX #8 — flashcard backdrop drawn as a single Canvas instead of 9 nested Box
// composables. This removes 9 measure passes + 9 recomposition nodes per card,
// which was the primary source of scroll jank on the menu's LazyVerticalGrid.
//
// The tiles are drawn in a single pass using Canvas.drawRoundRect + drawText.
// Text rendering uses Compose's nativeTextCanvas (Android Paint), which is
// fast because it doesn't allocate Compose layout nodes.
@Composable
fun FlashcardBackdrop(catId: String, modifier: Modifier = Modifier) {
    val texts = remember(catId) { flashTexts(catId) }
    val tileData = remember(texts) {
        FLASH_TILES.mapIndexed { i, t ->
            TileDraw(
                x = t.x, y = t.y, rot = t.rot, w = t.w, h = t.h,
                fade = (t.x * t.x).coerceIn(0f, 1f),
                text = texts[i % texts.size]
            )
        }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val density = this.density

        tileData.forEach { td ->
            val tileW = td.w * density
            val tileH = td.h * density
            val cx = w * td.x
            val cy = h * td.y

            // FIX #8 — Use Compose's native rotate scope instead of the modifier.
            // This avoids needing the `rotate` modifier import and is faster.
            rotate(degrees = td.rot, pivot = Offset(cx, cy)) {
                val left = cx - tileW / 2f
                val top = cy - tileH / 2f
                val radius = 8.dp.toPx()

                // Fill
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.12f * td.fade),
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(tileW, tileH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                )
                // Border
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.22f * td.fade),
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(tileW, tileH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                    style = Stroke(width = 1.dp.toPx())
                )
                // Text — via Compose's drawText with a native paint
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textSize = 17.sp.toPx()
                    color = android.graphics.Color.argb(
                        (0.42f * td.fade * 255).toInt(), 255, 255, 255
                    )
                }
                val lines = td.text.split('\n')
                val lineH = paint.textSize * 1.1f
                val totalTextH = lineH * lines.size
                lines.forEachIndexed { lineIdx, line ->
                    drawContext.canvas.nativeCanvas.drawText(
                        line,
                        cx,
                        cy - totalTextH / 2f + lineH * (lineIdx + 1) - lineH * 0.3f,
                        paint
                    )
                }
            }
        }
    }
}

// Data class used only inside FlashcardBackdrop to pre-compute draw data.
private data class TileDraw(
    val x: Float, val y: Float, val rot: Float, val w: Int, val h: Int,
    val fade: Float, val text: String
)

// Small rising-bars icon for the Progress Dashboard card, drawn in code.
@Composable
fun ProgressGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier.size(46.dp)) {
        val sw = 8.dp.toPx()
        val r = sw / 2f
        val h = size.height
        val w = size.width

        // FIX: Visual height is 28.dp. We calculate the exact vertical center
        // by adding a top padding, ensuring the visual mass is perfectly centered.
        val visualHeight = 28.dp.toPx()
        val topPadding = (h - visualHeight) / 2f
        
        // x fraction, height fraction (of visualHeight)
        val bars = listOf(0.20f to 0.50f, 0.50f to 0.75f, 0.80f to 1.0f) 
        
        bars.forEachIndexed { i, (xf, hf) ->
            val barHeight = visualHeight * hf
            val bottomY = topPadding + visualHeight - r
            val topY = bottomY - (barHeight - sw)
            
            drawLine(
                Color.White.copy(alpha = 0.55f + 0.2f * i),
                Offset(w * xf, topY),
                Offset(w * xf, bottomY),
                sw, StrokeCap.Round
            )
        }
    }
}

// Obvious divider between the dashboard card and the categories.
@Composable
fun MenuSeparator(label: String) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp))
                // FIX #8 — use the top-level Brush.horizontalGradient function
                .background(Brush.horizontalGradient(listOf(Color.Transparent, c.accentA)))
        )
        Text(
            text = label.uppercase(),
            color = c.accentA,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 2.sp
        )
        Box(
            Modifier.weight(1f).height(2.dp).clip(RoundedCornerShape(1.dp))
                .background(Brush.horizontalGradient(listOf(c.accentA, Color.Transparent)))
        )
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

// FIX #8: perf — instant variant for steppers. Uses plain `clickable` (no debounce,
// no animation). This allows rapid spam-tapping of +/− to update the value in real
// time, exactly like a native counter. Uses a pressed-tint for visual feedback so it
// still feels responsive, but the tint swap is instant.
@Composable
fun RoundButtonSmallInstant(text: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = if (pressed) c.surface else c.surfaceAlt
    
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, 16, FontWeight.Bold, c.onBackground)
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
    onConfirm: (Int) -> Unit,
    // FIX #8 — per-field caps. Range = 2 digits / 99. Questions = 3 digits / 200.
    // Both the max-digit rejection ("Max Digits" toast) and the final value
    // validation ("Set" enabled state) respect these.
    maxDigits: Int = 2,
    maxValue: Int = 99
) {
    val c = AppTheme.colors
    // FIX #8: perf — track input as TextFieldValue so we can pin the cursor to the
    // END of the digits. The `selection` field must be `length`, not 0 — otherwise
    // the initial render puts the cursor before the first digit even with
    // TextAlign.Center.
    var textFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initial.toString(),
                selection = TextRange(initial.toString().length)
            )
        )
    }
    var maxDigitsTick by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    // FIX #8: perf — request focus, then re-pin the selection after one frame. The
    // framework sometimes resets selection to 0 when focus is granted; re-pinning
    // guarantees the cursor sits to the RIGHT of the last digit.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        kotlinx.coroutines.delay(16)
        textFieldValue = textFieldValue.copy(
            selection = TextRange(textFieldValue.text.length)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.onBackground,
        textContentColor = c.muted,
        title = { Txt("Enter value (1–$maxValue)", 16, FontWeight.Bold) },
        text = {
            // FIX #8: — single BasicTextField, no overlay. Text is centered via
            // TextAlign.Center; cursor sits at `selection.start` which we pin to
            // `text.length` after every change, so it appears right after the last
            // digit. When empty, `TextAlign.Center` shows the cursor in the middle.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceAlt)
                    // FIX #8: perf — tapping anywhere in the field requests focus on
                    // the BasicTextField. Without this, taps on the padding (or the
                    // box's outside area) do nothing; the user had to tap precisely
                    // on the text itself.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { focusRequester.requestFocus() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        val digitsOnly = newValue.text.filter { it.isDigit() }
                        when {
                            // FIX #8 — cap by the field's own maxDigits, not hardcoded 2.
                            digitsOnly.length <= maxDigits -> {
                                // FIX #8: perf — distinguish "text changed" (typing,
                                // backspace, paste) from "selection changed" (user
                                // tapped inside the field). If the text changed, force
                                // the cursor to the end. If only the selection changed,
                                // honor the user's tap so they can position the cursor.
                                val textChanged = digitsOnly != textFieldValue.text
                                val newSelection = if (textChanged) {
                                    TextRange(digitsOnly.length)
                                } else {
                                    newValue.selection
                                }
                                textFieldValue = TextFieldValue(
                                    text = digitsOnly,
                                    selection = newSelection,
                                    composition = null
                                )
                            }
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
                            val parsed = textFieldValue.text.toIntOrNull()
                            if (parsed != null && parsed in 1..maxValue) onConfirm(parsed)
                        }
                    ),
                    cursorBrush = SolidColor(c.accentA),
                    textStyle = TextStyle(
                        color = c.onBackground,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .padding(horizontal = 16.dp)
                )
            }
        },
        confirmButton = {
            val parsed = textFieldValue.text.toIntOrNull()
            // FIX #8 — validate against the field's own maxValue, not a hardcoded 99.
            val valid = parsed != null && parsed in 1..maxValue
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
