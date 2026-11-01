package com.jarvis.os.net

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.os.audio.AudioPlaybackEngine
import com.jarvis.os.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class VoiceInterruptionDeviceTest {
    @Test(timeout = 75000) fun speechInterruptsLongReplyWithoutBlockingTheNetworkReader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val key = com.jarvis.os.sync.ApiCredentials.gemini(context)
        val client = GeminiLiveClient()
        val speaker = AudioPlaybackEngine()
        val speaking = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val answered = CountDownLatch(1)
        val wasInterrupted = AtomicBoolean(false)
        val responseBytes = AtomicInteger()
        client.onConnectionStateChanged = { ready -> if (ready) client.sendText("Count slowly from one to one hundred, one number per second. Keep speaking until you reach one hundred.") }
        client.onAudioReceived = { pcm ->
            speaker.playPcm(pcm)
            speaking.countDown()
            if (wasInterrupted.get()) responseBytes.addAndGet(pcm.size)
        }
        client.onInterrupted = {
            val start = SystemClock.elapsedRealtime()
            speaker.cancel()
            assertTrue("Speaker cut-off must not wait for queued speech", SystemClock.elapsedRealtime() - start < 150)
            wasInterrupted.set(true)
            interrupted.countDown()
        }
        client.onTurnComplete = { if (wasInterrupted.get() && responseBytes.get() > 4800) answered.countDown() }
        try {
            client.connect(key)
            assertTrue("Long response must start", speaking.await(20, TimeUnit.SECONDS))
            Thread.sleep(350)
            val question = instrumentation.context.assets.open("ordinary.pcm").use { it.readBytes() }
            val pcm = ByteArray(3200) + question + ByteArray(64000)
            for (offset in pcm.indices step 640) {
                assertTrue(client.sendAudio(pcm.copyOfRange(offset, minOf(offset + 640, pcm.size))))
                Thread.sleep(20)
            }
            assertTrue("Speaking over the response must trigger interruption", interrupted.await(8, TimeUnit.SECONDS))
            assertTrue("Interrupted conversation must answer the new question", answered.await(25, TimeUnit.SECONDS))
        } finally {
            client.disconnect()
            speaker.release()
        }
    }
}
