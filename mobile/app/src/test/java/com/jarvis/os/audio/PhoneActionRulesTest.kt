package com.jarvis.os.audio

import com.jarvis.os.control.PhoneActionRules
import org.junit.Assert.*
import org.junit.Test

class PhoneActionRulesTest {
    @Test fun validatesVolumeAndBrightnessRange() {
        assertEquals(0, PhoneActionRules.percent(0))
        assertEquals(100, PhoneActionRules.percent(100.0))
        listOf(-1, 101, Double.NaN, Double.POSITIVE_INFINITY, "50", null).forEach {
            try { PhoneActionRules.percent(it);fail("Invalid percentage must fail") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
    }
    @Test fun normalPhoneNumbersDoNotAcceptDialerControlCodes() {
        assertEquals("+15550101234", PhoneActionRules.number("+1 (555) 010-1234"))
        listOf("911", "112", "*123#", "+15550101234;123", "Mom", "123456+1234").forEach {
            try { PhoneActionRules.number(it);fail("Invalid number must fail") } catch (_: IllegalArgumentException) {}
        }
    }
}
