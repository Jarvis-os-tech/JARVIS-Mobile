package com.jarvis.os.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the packaged native decoder and model with real speech on the phone. */
@RunWith(AndroidJUnit4::class)
class WakeWordDeviceTest {
    @Test fun detectsWakePhraseAndRejectsOtherSpeech() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val detector = WakeWordDetector(instrumentation.targetContext)
        try {
            detector.prepare()
            fun detects(asset: String): Boolean {
                detector.reset()
                val speech = instrumentation.context.assets.open(asset).use { it.readBytes() }
                val pcm = speech + ByteArray(32000)
                var detected = false
                for (start in pcm.indices step 640) {
                    detected = detector.process(pcm.copyOfRange(start, minOf(start + 640, pcm.size))) || detected
                }
                return detected
            }
            assertFalse("Ordinary speech must stay in standby", detects("ordinary.pcm"))
            assertFalse("Jarvis alone must never wake the assistant", detects("jarvis-only.pcm"))
            assertFalse("Jarvis command must stay in standby", detects("jarvis-command.pcm"))
            assertFalse("Okay Jarvis is not the wake phrase", detects("okay-jarvis.pcm"))
            assertTrue("Hey Jarvis must wake the offline decoder", detects("hey-jarvis.pcm"))
            fun goodbye(asset: String): Boolean {
                detector.resetGoodbye()
                val speech = instrumentation.context.assets.open(asset).use { it.readBytes() } + ByteArray(48000)
                var detected = false
                for (start in speech.indices step 640) {
                    detected = detector.processGoodbye(speech.copyOfRange(start, minOf(start + 640, speech.size))) || detected
                }
                return detected
            }
            assertFalse("Ordinary questions must not close the assistant", goodbye("ordinary.pcm"))
            assertTrue("Bye must be recognized without a network round trip", goodbye("bye.pcm"))
        } finally {
            detector.close()
        }
    }
}
