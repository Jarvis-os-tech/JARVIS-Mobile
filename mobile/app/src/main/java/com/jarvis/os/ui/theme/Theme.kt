package com.jarvis.os.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CyberColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianDark,
    secondary = ArcBlue,
    onSecondary = TextWhite,
    tertiary = NeuralViolet,
    background = ObsidianDark,
    surface = CyberSurface,
    onSurface = TextWhite,
    error = CyberError,
    onError = TextWhite
)

@Composable
fun JarvisCyberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        content = content
    )
}
