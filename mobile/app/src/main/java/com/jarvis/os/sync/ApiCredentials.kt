package com.jarvis.os.sync

import android.content.Context

/** All provider credentials stay in the Android Keystore-backed encrypted store. */
object ApiCredentials {
    val optionalProviders = linkedMapOf("openai" to "OpenAI", "anthropic" to "Anthropic", "groq" to "Groq")

    fun validate(key: String): String {
        val value = key.trim()
        require(value.length in 20..512 && value.none { it.isWhitespace() || it.isISOControl() }) {
            "Paste a complete API key without spaces."
        }
        return value
    }

    fun gemini(context: Context): String {
        val secure = QrPairingScanner.getSecurePrefs(context)
        val legacy = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        val value = secure.getString("gemini_api_key", "").orEmpty().ifBlank {
            legacy.getString("gemini_api_key", "").orEmpty()
        }
        if (value.isNotBlank()) {
            check(secure.edit().putString("gemini_api_key", value).commit()) { "Could not secure API key" }
            legacy.edit().remove("gemini_api_key").commit()
        }
        return value
    }

    fun savePersonal(context: Context, gemini: String, optional: Map<String, String>) {
        val primary = validate(gemini)
        val extras = optional.filterValues { it.isNotBlank() }.mapValues { validate(it.value) }
        require(extras.keys.all { it in optionalProviders })
        val edit = QrPairingScanner.getSecurePrefs(context).edit()
            .putString("gemini_api_key", primary).putString("credential_source", "personal")
        optionalProviders.keys.forEach { name ->
            if (name in extras) edit.putString("api_key_$name", extras[name]) else edit.remove("api_key_$name")
        }
        check(edit.commit()) { "Could not save API keys securely" }
        context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE).edit().remove("gemini_api_key").commit()
        QrPairingScanner.clearPairing(context)
    }

    fun source(context: Context): String = QrPairingScanner.getSecurePrefs(context)
        .getString("credential_source", if (QrPairingScanner.isPaired(context)) "pc" else "personal").orEmpty()
}
