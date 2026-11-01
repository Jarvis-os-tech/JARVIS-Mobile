package com.jarvis.os.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.os.overlay.OverlayUiState
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArcReactorVisualizer(
    state: OverlayUiState,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    onClick: (() -> Unit)? = null
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ReactorTransition")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OuterRingRotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "InnerRingRotation"
    )

    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BreathingScale"
    )

    val reactiveScale = when (state) {
        is OverlayUiState.Listening -> (1.0f + (state.rms / 6000f).coerceIn(0f, 0.5f)) * breathingScale
        is OverlayUiState.Speaking -> (1.0f + (state.amplitude / 6000f).coerceIn(0f, 0.4f)) * breathingScale
        is OverlayUiState.Thinking -> breathingScale * 1.1f
        OverlayUiState.Idle -> breathingScale
    }

    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    } else Modifier

    Box(
        modifier = modifier
            .size(size)
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = (this.size.minDimension / 2f) * 0.72f * reactiveScale

            // 1. Outer ambient bloom glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00F2FE).copy(alpha = 0.35f),
                        Color(0xFF0077FE).copy(alpha = 0.18f),
                        Color(0xFF9B51E0).copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.55f
                ),
                radius = baseRadius * 1.55f,
                center = center
            )

            // 2. Telemetry tick mark outer orbit
            rotate(outerRotation, pivot = center) {
                drawCircle(
                    color = Color(0xFF00F2FE).copy(alpha = 0.4f),
                    radius = baseRadius * 1.15f,
                    center = center,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 18f), 0f)
                    )
                )
            }

            // 3. Counter-rotating segmented telemetry ring
            rotate(innerRotation, pivot = center) {
                drawCircle(
                    color = Color(0xFF0077FE).copy(alpha = 0.6f),
                    radius = baseRadius * 0.95f,
                    center = center,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 20f), 0f)
                    )
                )
            }

            // 4. Solid metallic confinement ring
            drawCircle(
                color = Color(0xFF1F283C),
                radius = baseRadius * 0.75f,
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF00F2FE),
                radius = baseRadius * 0.75f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // 5. Arc core gradient sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF),
                        Color(0xFF7EE7F8),
                        Color(0xFF00F2FE),
                        Color(0xFF0052CC)
                    ),
                    center = center,
                    radius = baseRadius * 0.60f
                ),
                radius = baseRadius * 0.60f,
                center = center
            )

            // 6. Arc core radiator spokes (10 nodes)
            val spokes = 10
            for (i in 0 until spokes) {
                val angle = Math.toRadians((i * (360.0 / spokes) + innerRotation).toDouble())
                val start = Offset(
                    center.x + (cos(angle) * (baseRadius * 0.28f)).toFloat(),
                    center.y + (sin(angle) * (baseRadius * 0.28f)).toFloat()
                )
                val end = Offset(
                    center.x + (cos(angle) * (baseRadius * 0.72f)).toFloat(),
                    center.y + (sin(angle) * (baseRadius * 0.72f)).toFloat()
                )
                drawLine(
                    color = Color(0xFF080A0F),
                    start = start,
                    end = end,
                    strokeWidth = 3.dp.toPx()
                )
            }

            // 7. Core nucleus
            drawCircle(
                color = Color(0xFFFFFFFF),
                radius = baseRadius * 0.22f,
                center = center
            )
        }
    }
}
