package com.jarvis.os.control

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DeviceRequestVerifier {
    fun verify(payload: String, signature: String, token: String, now: Long): JSONObject {
        require(token.length >= 32 && payload.length <= 16000 && Regex("^[a-f0-9]{64}$").matches(signature))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token.toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        require(MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray()))
        val request = JSONObject(String(Base64.getUrlDecoder().decode(payload)))
        require(request.getLong("issued_at") in (now - 60000)..(now + 60000))
        require(Regex("^[a-zA-Z0-9_-]{1,100}$").matches(request.getString("request_id")))
        require(request.getString("tool") in setOf("phone_find_contacts", "phone_call_contact", "phone_system_control", "phone_ui_control", "phone_status", "phone_list_apps"))
        request.getJSONObject("args")
        return request
    }
}
