package com.jarvis.os.audio

import com.jarvis.os.control.PhoneActionRules
import com.jarvis.os.net.LiveSessionModels
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneSpeedAndSettingsTest {
    @Test fun appNamesResolveLocallyWithoutGuessingAmbiguousMatches() {
        val apps = listOf("Calculator" to "calculator", "Scientific Calculator" to "scientific", "Calendar" to "calendar")
        assertEquals(listOf(apps[0]), PhoneActionRules.matchApps("calculator", apps))
        assertEquals(3, PhoneActionRules.matchApps("cal", apps).size)
        assertTrue(PhoneActionRules.matchApps("not installed", apps).isEmpty())
        assertEquals(listOf(apps[0]), PhoneActionRules.matchApps(" Calculator ", apps + apps[0]))
    }
    @Test fun screenTimeoutRequiresSensibleWholeSeconds() {
        assertEquals(15000, PhoneActionRules.timeoutMillis(15))
        assertEquals(1800000, PhoneActionRules.timeoutMillis(1800))
        for (value in listOf(0, -1, 1801, 15.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            try { PhoneActionRules.timeoutMillis(value); fail("Invalid timeout must fail") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun lowLatencyBudgetIsAppliedOnlyToCompatibleNativeModel() {
        val fast = JSONObject(LiveSessionModels.buildSetupJson(model = "models/gemini-2.5-flash-native-audio-preview-12-2025")).getJSONObject("setup")
        assertEquals(0, fast.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertTrue(fast.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
        val standard = JSONObject(LiveSessionModels.buildSetupJson()).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", standard.getString("model"))
        assertFalse(standard.getJSONObject("generationConfig").has("thinkingConfig"))
        val fallback = JSONObject(LiveSessionModels.buildSetupJson(model = "models/gemini-3.1-flash-live-preview")).getJSONObject("setup")
        assertFalse(fallback.getJSONObject("generationConfig").has("thinkingConfig"))
    }
}
