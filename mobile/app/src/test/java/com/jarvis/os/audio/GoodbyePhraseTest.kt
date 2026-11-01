package com.jarvis.os.audio

import org.junit.Assert.*
import org.junit.Test

class GoodbyePhraseTest {
    @Test fun standaloneFarewellsCloseTheAssistant() {
        listOf("bye", "goodbye", "bye jarvis", "Goodbye Jarvis", " bye   jarvis ").forEach { assertTrue(GoodbyePhrase.matches(it)) }
    }
    @Test fun longerRequestsAndUnknownSpeechDoNotCloseIt() {
        listOf("", "[unk]", "bye [unk]", "say goodbye", "what does goodbye mean", "buy milk", "hey jarvis").forEach { assertFalse(it, GoodbyePhrase.matches(it)) }
    }
}
