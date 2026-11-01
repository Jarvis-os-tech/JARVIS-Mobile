package com.jarvis.os.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.os.sync.ApiCredentials
import com.jarvis.os.sync.PairingPayload
import com.jarvis.os.sync.QrPairingScanner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialSetupDeviceTest {
    // Separate preference files ensure tests never read or overwrite the owner's keys.
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("credential_test_$name", mode)
    }

    @Test fun migratesLegacyKeyAndKeepsBothSetupModesEncrypted() {
        val first = "test-gemini-" + "a".repeat(30)
        val second = "test-gemini-" + "b".repeat(30)
        val plain = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val raw = context.getSharedPreferences("jarvis_secure_pairing", Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        plain.edit().putString("gemini_api_key", first).commit()
        try {
            assertEquals(first, ApiCredentials.gemini(context))
            assertFalse(plain.contains("gemini_api_key"))
            assertFalse(raw.all.toString().contains(first))
            QrPairingScanner.savePairing(context, PairingPayload(1, "test-project", "test-pc",
                "t".repeat(64), "test-bucket", "", first))
            assertTrue(QrPairingScanner.isPaired(context))
            assertEquals("pc", ApiCredentials.source(context))
            ApiCredentials.savePersonal(context, second, mapOf("openai" to "optional-test-" + "c".repeat(30)))
            assertFalse(QrPairingScanner.isPaired(context))
            assertEquals(second, ApiCredentials.gemini(context))
            assertEquals("personal", ApiCredentials.source(context))
            assertFalse(raw.all.toString().contains(second))
            assertFalse(plain.contains("gemini_api_key"))
            // A standalone phone still has its native tools without a PC.
            val declarations = DeviceToolClient(context).declarations().toString()
            assertTrue(declarations.contains("phone_call_contact"))
            assertFalse(declarations.contains("pc_"))
        } finally {
            raw.edit().clear().commit()
            plain.edit().clear().commit()
        }
    }
}
