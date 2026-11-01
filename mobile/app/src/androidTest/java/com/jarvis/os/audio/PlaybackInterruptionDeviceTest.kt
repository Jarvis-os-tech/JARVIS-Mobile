package com.jarvis.os.audio

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackInterruptionDeviceTest {
    @Test fun longAudioDoesNotBlockCancelAndCannotResumeAfterFlush() {
        val engine = AudioPlaybackEngine()
        try {
            engine.playPcm(ByteArray(4800)) // Initialize the device speaker.
            val enqueueStart = SystemClock.elapsedRealtime()
            engine.playPcm(ByteArray(480000)) // Ten seconds of queued silence.
            assertTrue("Enqueue must not wait for playback", SystemClock.elapsedRealtime() - enqueueStart < 150)
            assertTrue(engine.hasPendingAudio)
            val cancelStart = SystemClock.elapsedRealtime()
            engine.cancel()
            assertTrue("Interruption must not wait for the old audio", SystemClock.elapsedRealtime() - cancelStart < 150)
            assertFalse(engine.hasPendingAudio)
            Thread.sleep(200)
            assertFalse("Cancelled chunks must never restart", engine.hasPendingAudio)
            engine.playPcm(ByteArray(4800))
            assertTrue("The next response must still work", engine.hasPendingAudio)
        } finally { engine.release() }
    }
}
