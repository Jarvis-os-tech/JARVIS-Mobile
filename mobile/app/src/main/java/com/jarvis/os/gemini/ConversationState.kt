package com.jarvis.os.gemini

/**
 * High-level conversational state of JARVIS Mobile.
 */
enum class ConversationState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}
