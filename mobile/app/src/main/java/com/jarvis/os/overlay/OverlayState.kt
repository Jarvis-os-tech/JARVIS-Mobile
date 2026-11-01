package com.jarvis.os.overlay

/**
 * UI presentation state for the Siri-style floating HUD and Dynamic Island.
 */
sealed class OverlayUiState {
    object Idle : OverlayUiState()
    data class Listening(val rms: Float = 0f) : OverlayUiState()
    data class Thinking(val message: String = "Reasoning...") : OverlayUiState()
    data class Speaking(val amplitude: Float = 0f) : OverlayUiState()

    val isVisible: Boolean
        get() = this !is Idle
}
