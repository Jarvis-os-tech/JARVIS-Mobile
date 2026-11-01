package com.jarvis.os.sync

import org.json.JSONArray
import org.json.JSONObject

data class PairingPayload(
    val version: Int,
    val projectId: String,
    val deviceId: String,
    val authToken: String,
    val gcsBucket: String,
    val endpoint: String,
    val geminiApiKey: String? = null,
    val executionEndpoint: String = "http://127.0.0.1:8766",
    val pairingSession: String = ""
)

data class MemoryDeltaRecord(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val role: String,
    val content: String,
    val deviceId: String
)

object SyncPayloads {

    fun parsePairingPayload(jsonString: String): PairingPayload? {
        return try {
            val root = JSONObject(jsonString)
            PairingPayload(
                version = root.optInt("v", 1),
                pairingSession = root.optString("pair_session", ""),
                projectId = root.getString("pid"),
                deviceId = root.getString("dev"),
                authToken = root.getString("tok"),
                gcsBucket = root.optString("bkt", "jarvis-os-508612-memory-sync"),
                endpoint = root.optString("ep", ""),
                executionEndpoint = root.optString("exec", "http://127.0.0.1:8766"),
                geminiApiKey = if (root.has("key") && root.getString("key").isNotBlank()) {
                    root.getString("key")
                } else {
                    null
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun serializeDeltaRecord(record: MemoryDeltaRecord): String {
        val obj = JSONObject()
        obj.put("id", record.id)
        obj.put("timestamp", record.timestamp)
        obj.put("role", record.role)
        obj.put("content", record.content)
        obj.put("deviceId", record.deviceId)
        return obj.toString()
    }

    fun parseDeltaRecord(jsonString: String): MemoryDeltaRecord? {
        return try {
            val obj = JSONObject(jsonString)
            MemoryDeltaRecord(
                id = obj.getString("id"),
                timestamp = obj.getLong("timestamp"),
                role = obj.getString("role"),
                content = obj.getString("content"),
                deviceId = obj.getString("deviceId")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun buildSyncBundleJson(
        deviceId: String,
        authToken: String,
        records: List<MemoryDeltaRecord>
    ): String {
        val root = JSONObject()
        root.put("deviceId", deviceId)
        root.put("authToken", authToken)
        root.put("timestamp", System.currentTimeMillis())

        val array = JSONArray()
        for (rec in records) {
            val item = JSONObject()
            item.put("id", rec.id)
            item.put("timestamp", rec.timestamp)
            item.put("role", rec.role)
            item.put("content", rec.content)
            item.put("deviceId", rec.deviceId)
            array.put(item)
        }
        root.put("records", array)
        return root.toString()
    }
}
