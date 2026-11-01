package com.jarvis.os.audio

import com.jarvis.os.control.DeviceRequestVerifier
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DeviceRequestVerifierTest {
    private val key = "x".repeat(64)
    private fun payload(tool: String = "phone_call_contact", time: Long = 100000) = Base64.getUrlEncoder().encodeToString(
        """{"request_id":"test_1","issued_at":$time,"tool":"$tool","args":{"recipient":"Test Person"}}""".toByteArray())
    private fun signature(payload: String): String = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    }.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    @Test fun acceptsOnlyPairedSignedNativeRequests() {
        val payload = payload()
        assertEquals("phone_call_contact", DeviceRequestVerifier.verify(payload, signature(payload), key, 100000).getString("tool"))
    }
    @Test fun rejectsTamperingExpiredRequestsAndPcToolRecursion() {
        listOf(payload() to "0".repeat(64), payload(time = 0) to signature(payload(time = 0)),
            payload(tool = "pc_run_shell") to signature(payload(tool = "pc_run_shell"))).forEach { (body, sig) ->
            try { DeviceRequestVerifier.verify(body, sig, key, 100000);fail("Invalid signed request must be rejected") } catch (_: IllegalArgumentException) {}
        }
    }
}
