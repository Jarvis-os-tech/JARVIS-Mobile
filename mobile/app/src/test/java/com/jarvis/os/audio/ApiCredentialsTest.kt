package com.jarvis.os.audio

import com.jarvis.os.sync.ApiCredentials
import org.junit.Assert.*
import org.junit.Test

class ApiCredentialsTest {
    @Test fun primaryKeyCannotBeEmptyOrContainEmbeddedWhitespace() {
        for (value in listOf("", "short", "x".repeat(20) + "\n" + "y".repeat(20))) {
            try { ApiCredentials.validate(value); fail("Accepted incomplete key") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun pastedKeyTrimsOuterWhitespace() {
        assertEquals("x".repeat(39), ApiCredentials.validate("  " + "x".repeat(39) + "\n"))
    }
}
