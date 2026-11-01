package com.jarvis.os.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Handles QR pairing credential parsing, cryptographic persistence
 * in EncryptedSharedPreferences, and status inspection.
 */
object QrPairingScanner {
    private const val TAG = "QrPairingScanner"
    private const val PREFS_FILE = "jarvis_secure_pairing"

    private const val KEY_IS_PAIRED = "is_paired"
    private const val KEY_PROJECT_ID = "paired_project_id"
    private const val KEY_DEVICE_ID = "paired_device_id"
    private const val KEY_AUTH_TOKEN = "paired_auth_token"
    private const val KEY_GCS_BUCKET = "paired_gcs_bucket"
    private const val KEY_ENDPOINT = "paired_endpoint"
    private const val KEY_GEMINI_KEY = "gemini_api_key"

    internal fun getSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, PREFS_FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    /**
     * Parse raw QR scan result and persist pairing securely if valid.
     */
    fun processScanResult(context: Context, rawQrText: String): PairingPayload? {
        val payload = SyncPayloads.parsePairingPayload(rawQrText) ?: return null
        savePairing(context, payload)
        return payload
    }

    fun savePairing(context: Context, payload: PairingPayload) {
        val prefs = getSecurePrefs(context)
        prefs.edit().apply {
            putBoolean(KEY_IS_PAIRED, true)
            putString(KEY_PROJECT_ID, payload.projectId)
            putString(KEY_DEVICE_ID, payload.deviceId)
            putString(KEY_AUTH_TOKEN, payload.authToken)
            putString(KEY_GCS_BUCKET, payload.gcsBucket)
            putString(KEY_ENDPOINT, payload.endpoint)
            putString("execution_endpoint", payload.executionEndpoint)
            putString("pairing_session", payload.pairingSession)
            payload.geminiApiKey?.let { putString(KEY_GEMINI_KEY, it) }
            check(commit()) { "Could not save pairing securely" }
        }

        if (!payload.geminiApiKey.isNullOrBlank()) {
            check(prefs.edit().putString("credential_source", "pc").commit())
            context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
                .edit().remove("gemini_api_key").commit()
        }

        PairingConfirmationWorker.enqueue(context, payload.pairingSession)
        Log.i(TAG, "Device pairing established with ${payload.deviceId} on project ${payload.projectId}")
    }

    fun isPaired(context: Context): Boolean {
        return getSecurePrefs(context).getBoolean(KEY_IS_PAIRED, false)
    }

    fun getPairingPayload(context: Context): PairingPayload? {
        val prefs = getSecurePrefs(context)
        if (!prefs.getBoolean(KEY_IS_PAIRED, false)) return null

        val projectId = prefs.getString(KEY_PROJECT_ID, null) ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val authToken = prefs.getString(KEY_AUTH_TOKEN, null) ?: return null
        val gcsBucket = prefs.getString(KEY_GCS_BUCKET, "jarvis-os-508612-memory-sync") ?: ""
        val endpoint = prefs.getString(KEY_ENDPOINT, "") ?: ""
        val geminiKey = prefs.getString(KEY_GEMINI_KEY, null)

        return PairingPayload(
            version = 1,
            projectId = projectId,
            deviceId = deviceId,
            authToken = authToken,
            gcsBucket = gcsBucket,
            endpoint = endpoint,
            geminiApiKey = geminiKey,
            pairingSession = prefs.getString("pairing_session", "").orEmpty(),
            executionEndpoint = prefs.getString("execution_endpoint", "http://127.0.0.1:8766") ?: "http://127.0.0.1:8766"
        )
    }

    fun clearPairing(context: Context) {
        getSecurePrefs(context).edit().apply {
            listOf(KEY_IS_PAIRED, KEY_PROJECT_ID, KEY_DEVICE_ID, KEY_AUTH_TOKEN,
                KEY_GCS_BUCKET, KEY_ENDPOINT, "execution_endpoint", "pairing_session").forEach { remove(it) }
        }.commit()
        Log.i(TAG, "Device pairing credentials cleared")
    }
}
