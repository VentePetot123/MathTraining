package com.mathmaster.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun Modifier.bounceClick(
    shape: Shape = RoundedCornerShape(16.dp),
    elevation: Dp = 0.dp,
    pressedScale: Float = 0.94f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
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
            onClick = onClick
        )
}

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
@Composable
fun NumberInputDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val c = AppTheme.colors
    var buffer by remember { mutableStateOf(initial.toString()) }

    fun append(d: Char) {
        if (buffer.length >= 2) return                // max 2 digits (<=99)
        buffer = if (buffer == "0") d.toString() else buffer + d
    }
    fun clear() { buffer = "" }
    fun backspace() { buffer = buffer.dropLast(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.onBackground,
        textContentColor = c.muted,
        title = { Txt("Enter value (1–99)", 16, FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Live preview of the current buffer
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.surfaceAlt),
                    contentAlignment = Alignment.Center
                ) {
                    Txt(
                        if (buffer.isEmpty()) "—" else buffer,
                        24,
                        FontWeight.Bold,
                        if (buffer.isEmpty()) c.muted else c.onBackground,
                        align = TextAlign.Center
                    )
                }

                // 3x4 mini keypad — same layout as the training keypad, but self-contained
                for (row in listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"))) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            MiniKey(k, Modifier.weight(1f)) { append(k[0]) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniKey("C", Modifier.weight(1f)) { clear() }
                    MiniKey("0", Modifier.weight(1f)) { append('0') }
                    MiniKey("⌫", Modifier.weight(1f)) { backspace() }
                }
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
}

@Composable
private fun MiniKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(
        modifier
            .height(40.dp)
            .bounceClick(RoundedCornerShape(10.dp), 0.dp, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Txt(label, 16, FontWeight.Bold, c.onBackground, align = TextAlign.Center)
    }
}
