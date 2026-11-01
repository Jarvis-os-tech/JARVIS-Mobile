package com.jarvis.os.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.jarvis.os.net.DeviceToolClient
import com.jarvis.os.net.FunctionCall
import com.jarvis.os.sync.QrPairingScanner
import org.json.JSONObject
import java.security.MessageDigest

/** Paired PC requests reuse the exact same native adapters as mobile voice. */
class DeviceActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        WORKER.execute {
            fun reply(json: String) {
                pending.resultData = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                pending.finish()
            }
            try {
                val payload = intent.getStringExtra("payload") ?: error("Missing request")
                val signature = intent.getStringExtra("signature") ?: error("Missing signature")
                val token = QrPairingScanner.getPairingPayload(context)?.authToken ?: error("Not paired")
                val request = DeviceRequestVerifier.verify(payload, signature, token, System.currentTimeMillis())
                val id = request.getString("request_id")
                val name = request.getString("tool")
                val prefs = context.getSharedPreferences("jarvis_device_requests", Context.MODE_PRIVATE)
                val cached = prefs.getString(id, null)
                val fingerprint = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
                if (cached != null) {
                    require(prefs.getString("hash:$id", null) == fingerprint)
                    reply(cached);return@execute
                }
                val unknown = """{"status":"unknown","message":"This action may have started; it will not be replayed automatically."}"""
                check(prefs.edit().putString(id, unknown).putString("hash:$id", fingerprint).commit())
                val args = request.getJSONObject("args")
                val values = mutableMapOf<String, Any?>()
                args.keys().forEach { key -> values[key] = args.opt(key) }
                val result = DeviceToolClient(context).execute(FunctionCall(id, name, values), id)
                check(prefs.edit().putString(id, result).commit())
                reply(result)
            } catch (_: Exception) {
                reply("""{"status":"rejected","message":"Paired phone action was not accepted; check pairing, permissions and connection."}""")
            }
        }
    }
    companion object { private val WORKER = java.util.concurrent.Executors.newSingleThreadExecutor() }
}
