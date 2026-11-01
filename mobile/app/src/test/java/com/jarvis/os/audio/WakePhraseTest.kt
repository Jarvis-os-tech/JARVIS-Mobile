package com.jarvis.os.audio

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class WakePhraseTest {
    @Test fun acceptsWakePhraseAndFollowingQuestion() {
        assertTrue(WakePhrase.matches("hey jarvis"))
        assertTrue(WakePhrase.matches("Hey   Jarvis what time is it"))
    }
    @Test fun rejectsOrdinarySpeechAndSimilarNames() {
        listOf("", "jarvis", "jarvis command", "okay jarvis", "hello jarvis", "say hey jarvis", "[unk] hey jarvis", "hey", "hey jarvison", "they jarvis", "hello how are you", "[unk]").forEach {
            assertFalse(it, WakePhrase.matches(it))
        }
    }
}
