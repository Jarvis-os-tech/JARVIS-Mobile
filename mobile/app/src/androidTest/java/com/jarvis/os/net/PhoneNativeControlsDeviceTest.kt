package com.jarvis.os.net

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.os.control.PhoneActions
import com.jarvis.os.control.PhoneNavigationService
import com.jarvis.os.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneNativeControlsDeviceTest {
    @Test fun nativeVolumeAndBrightnessApplyAndRestoreWithoutPc() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val actions = PhoneActions(context)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        val mode = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        try {
            assertEquals("completed", actions.system(mapOf("action" to "set_volume", "stream" to "alarm", "percent" to 40)).getString("status"))
            assertEquals((audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 40 + 50) / 100, audio.getStreamVolume(AudioManager.STREAM_ALARM))
            assertEquals("completed", actions.system(mapOf("action" to "set_brightness", "percent" to 50)).getString("status"))
            assertEquals(127, Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS))
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        }
    }
    @Test fun contactLookupIsScopedAndCallIntentDoesNotPlaceATestCall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lookup = PhoneActions(context).contacts("JARVIS_TEST_NO_SUCH_CONTACT_0149")
        assertEquals("completed", lookup.getString("status"))
        assertEquals(0, lookup.getJSONArray("matches").length())
        val intent = PhoneActions.callIntent("+1 (555) 010-1234")
        assertEquals(Intent.ACTION_CALL, intent.action)
        assertEquals("tel:+15550101234", intent.data.toString())
        // Intentionally never launch this intent: testing must not place a real call.
    }
    @Test fun voiceNavigationReadsAndNavigatesOnThePhone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Enable JARVIS voice navigation first", PhoneNavigationService.available)
        val screen = PhoneNavigationService.execute(mapOf("action" to "read_screen"))
        assertEquals("completed", screen.getString("status"))
        assertTrue(screen.getJSONArray("elements").length() > 0)
        assertEquals("action_accepted", PhoneNavigationService.execute(mapOf("action" to "home")).getString("status"))
    }
}
