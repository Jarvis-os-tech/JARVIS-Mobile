package com.jarvis.os.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top Dynamic Island pill overlay morphing in/out at the status bar.
 */
@Composable
fun DynamicIsland(
    modifier: Modifier = Modifier,
    state: OverlayUiState = OverlayUiState.Idle
) {
    AnimatedVisibility(
        visible = state.isVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(tween(250)),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(tween(250))
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xEE111215))
                    .border(1.dp, Color(0x4000F2FE), RoundedCornerShape(32.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Mini Orb avatar
                HaloOrb(
                    size = 24.dp,
                    state = state
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Status text
                val label = when (state) {
                    is OverlayUiState.Listening -> "Listening..."
                    is OverlayUiState.Thinking -> state.message
                    is OverlayUiState.Speaking -> "JARVIS"
                    OverlayUiState.Idle -> ""
                }

                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 3-Bar Equalizer Visualizer
                WaveformVisualizer(state = state)
            }
        }
    }
}

@Composable
fun WaveformVisualizer(
    modifier: Modifier = Modifier,
    state: OverlayUiState
) {
    val transition = rememberInfiniteTransition(label = "WaveformAnimation")

    val h1 by transition.animateFloat(
        initialValue = 4f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Bar1"
    )

    val h2 by transition.animateFloat(
        initialValue = 8f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Bar2"
    )

    val h3 by transition.animateFloat(
        initialValue = 6f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Bar3"
    )

    val isDynamic = state is OverlayUiState.Listening || state is OverlayUiState.Speaking

    Row(
        modifier = modifier.height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val barColor = Color(0xFF00F2FE)
        val shape = RoundedCornerShape(2.dp)

        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (isDynamic) h1.dp else 4.dp)
                .clip(shape)
                .background(barColor)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (isDynamic) h2.dp else 4.dp)
                .clip(shape)
                .background(barColor)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (isDynamic) h3.dp else 4.dp)
                .clip(shape)
                .background(barColor)
        )
    }
}
