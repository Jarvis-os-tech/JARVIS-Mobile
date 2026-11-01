package com.jarvis.os.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iridescent Siri-style Halo Orb implemented in Jetpack Compose.
 * Features rotating chromatic gradients, glowing ambient bloom,
 * and dynamic voice-reactivity.
 */
@Composable
fun HaloOrb(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
    state: OverlayUiState = OverlayUiState.Idle
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbMotion")

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Breathing"
    )

    // Dynamic amplitude response
    val audioReactiveScale = when (state) {
        is OverlayUiState.Listening -> (1.0f + (state.rms / 8000f).coerceIn(0f, 0.45f)) * breathingScale
        is OverlayUiState.Speaking -> (1.0f + (state.amplitude / 8000f).coerceIn(0f, 0.35f)) * breathingScale
        is OverlayUiState.Thinking -> breathingScale * 1.08f
        OverlayUiState.Idle -> 0.0f
    }

    if (state == OverlayUiState.Idle) {
        return
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = (this.size.minDimension / 2f) * 0.75f * audioReactiveScale

            if (baseRadius <= 0f) return@Canvas

            // 1. Ambient outer glow bloom
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00F2FE).copy(alpha = 0.35f),
                        Color(0xFF4FACFE).copy(alpha = 0.20f),
                        Color(0xFF9B51E0).copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.6f
                ),
                radius = baseRadius * 1.6f,
                center = center
            )

            // 2. Rotating iridescent chromatic core
            val rad = Math.toRadians(rotationAngle.toDouble())
            val offset1 = Offset(
                center.x + (cos(rad) * baseRadius * 0.35f).toFloat(),
                center.y + (sin(rad) * baseRadius * 0.35f).toFloat()
            )
            val offset2 = Offset(
                center.x - (cos(rad) * baseRadius * 0.35f).toFloat(),
                center.y - (sin(rad) * baseRadius * 0.35f).toFloat()
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF00F2FE).copy(alpha = 0.85f),
                        Color(0xFF4FACFE).copy(alpha = 0.60f),
                        Color.Transparent
                    ),
                    center = offset1,
                    radius = baseRadius * 1.1f
                ),
                radius = baseRadius,
                center = center,
                blendMode = BlendMode.Screen
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF007F).copy(alpha = 0.80f),
                        Color(0xFF7928CA).copy(alpha = 0.50f),
                        Color.Transparent
                    ),
                    center = offset2,
                    radius = baseRadius * 1.1f
                ),
                radius = baseRadius,
                center = center,
                blendMode = BlendMode.Screen
            )

            // 3. Bright iridescent center highlight
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.90f),
                        Color(0xFFE0C3FC).copy(alpha = 0.40f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 0.45f
                ),
                radius = baseRadius * 0.45f,
                center = center
            )
        }
    }
}
