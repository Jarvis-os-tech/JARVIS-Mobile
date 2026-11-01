package com.jarvis.os.net

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.os.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GoodbyeDeviceTest {
    @Test(timeout = 40000) fun goodbyeHidesPhoneAssistantWithoutTurningItOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val key = com.jarvis.os.sync.ApiCredentials.gemini(context)
        val tools = DeviceToolClient(context).declarations()
        val client = GeminiLiveClient()
        val called = CountDownLatch(1)
        var selected = ""
        client.onConnectionStateChanged = { ready -> if (ready) client.sendText("Bye Jarvis.") }
        client.onToolCall = { call ->
            selected = call.name
            called.countDown()
        }
        try {
            client.connect(key, extraTools = tools, systemPrompt = DeviceToolClient.SYSTEM_PROMPT)
            assertTrue("Goodbye must invoke a device action", called.await(30, TimeUnit.SECONDS))
            assertEquals("phone_end_conversation", selected)
        } finally { client.disconnect() }
    }
}
