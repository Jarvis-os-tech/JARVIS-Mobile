package com.jarvis.os.net

import android.content.*
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.os.service.JarvisVoiceService
import com.jarvis.os.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalFarewellDeviceTest {
    @Test(timeout = 20000) fun localFarewellClosesAfterAudioWithoutWaitingForCloud() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val connected = CountDownLatch(1)
        var service: JarvisVoiceService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as JarvisVoiceService.LocalBinder).getService()
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent(context, JarvisVoiceService::class.java).setAction(JarvisVoiceService.ACTION_START)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
            val readyDeadline = SystemClock.elapsedRealtime() + 7000
            while (prefs.getString("voice_status", "") != "Standby · Say Hey Jarvis" && SystemClock.elapsedRealtime() < readyDeadline) Thread.sleep(25)
            assertEquals("Standby · Say Hey Jarvis", prefs.getString("voice_status", ""))
            val duration = context.assets.open("goodbye.pcm").use { it.available().toLong() } * 1000 / 48000
            val start = SystemClock.elapsedRealtime()
            val previousClose = prefs.getLong("goodbye_close_requested", 0)
            instrumentation.runOnMainSync { service!!.sayGoodbyeAndStandby() }
            while (prefs.getLong("goodbye_close_requested", 0) == previousClose && SystemClock.elapsedRealtime() - start < 3000) Thread.sleep(10)
            val elapsed = SystemClock.elapsedRealtime() - start
            assertTrue("Farewell must close within two seconds", elapsed < 2000)
            assertTrue("Do not cut off the local Bye recording", elapsed >= duration - 100)
            assertEquals("Standby · Say Hey Jarvis", prefs.getString("voice_status", ""))
            assertTrue(prefs.getLong("goodbye_close_requested", 0) > previousClose)
            assertTrue("Bye must leave the microphone and wake listener enabled", service!!.isWakeListening)
            android.util.Log.i("LocalFarewellDeviceTest", "Local farewell returned to wake standby in ${elapsed}ms")
        } finally {
            context.unbindService(connection)
            context.stopService(intent)
        }
    }
}
