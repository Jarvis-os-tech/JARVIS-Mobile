package com.jarvis.os.net

import android.content.Context
import android.content.Intent
import com.jarvis.os.ui.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.LinkedBlockingQueue

@RunWith(AndroidJUnit4::class)
class GeminiVoiceDeviceTest {
    @Test(timeout = 90000) fun configuredPhoneReceivesSpokenReply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Keep this network test visible: Vivo freezes background instrumentation workers.
        InstrumentationRegistry.getInstrumentation().startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val key = com.jarvis.os.sync.ApiCredentials.gemini(context)
        assertTrue("A configured key is required", key.isNotBlank())
        val client = GeminiLiveClient()
        val deviceTools = DeviceToolClient(context)
        val tools = deviceTools.declarations()
        val toolWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val identity = java.util.UUID.randomUUID().toString()
        client.onToolCall = { call -> toolWorker.execute {
            client.sendToolResponse(call.id, call.name, deviceTools.execute(call, "$identity:${call.id}"))
        } }
        val completedReplies = LinkedBlockingQueue<Int>()
        val bytes = AtomicInteger(0)
        client.onConnectionStateChanged = { ready -> if (ready) client.sendText("Say hello briefly.") }
        client.onAudioReceived = { pcm -> bytes.addAndGet(pcm.size) }
        client.onTurnComplete = {
            val count = bytes.getAndSet(0)
            // Tool requests can end a server turn without producing spoken audio.
            if (count > 4800) completedReplies.offer(count)
        }
        try {
            client.connect(key, extraTools = tools)
            assertTrue("Greeting must finish with real audio", (completedReplies.poll(30, TimeUnit.SECONDS) ?: 0) > 4800)
            android.util.Log.i("GeminiVoiceDeviceTest", "Greeting completed")
            // Exercise the actual microphone protocol after the greeting, not another text prompt.
            val question = InstrumentationRegistry.getInstrumentation().context.assets
                .open("ordinary.pcm").use { it.readBytes() }
            repeat(2) { turn ->
                val pcm = ByteArray(6400) + question + ByteArray(64000)
                for (offset in pcm.indices step 640) {
                    assertTrue("Audio must be queued", client.sendAudio(pcm.copyOfRange(offset, minOf(offset + 640, pcm.size))))
                    Thread.sleep(20)
                }
                android.util.Log.i("GeminiVoiceDeviceTest", "Spoken question ${turn + 1} sent")
                assertTrue("Spoken question ${turn + 1} must receive a completed audio reply",
                    (completedReplies.poll(20, TimeUnit.SECONDS) ?: 0) > 4800)
                android.util.Log.i("GeminiVoiceDeviceTest", "Spoken reply ${turn + 1} completed")
            }
        } finally {
            client.disconnect()
            toolWorker.shutdownNow()
        }
    }
}
