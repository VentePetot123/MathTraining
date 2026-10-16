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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.5f)
            .bounceClick(shape, 8.dp, enabled = enabled, onClick = onClick)
            .background(brush)
            .padding(horizontal = 24.dp, vertical = 15.dp),
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
    Box(
        modifier
            .bounceClick(RoundedCornerShape(14.dp), 0.dp, onClick = onClick)
            .background(container)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Txt(text, size, FontWeight.SemiBold, content)
    }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, 16, FontWeight.Medium, c.muted, Modifier.weight(1f))
        RoundButton("−") { onChange(value - step) }
        Txt(value.toString(), 22, FontWeight.Bold, c.onBackground, Modifier.width(72.dp), TextAlign.Center)
        RoundButton("+") { onChange(value + step) }
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
