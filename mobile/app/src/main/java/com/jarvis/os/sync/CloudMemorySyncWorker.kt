package com.jarvis.os.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker responsible for synchronizing conversational
 * memory turns to Google Cloud (Firestore/GCS mailbox) when the user is out of town.
 */
class CloudMemorySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val pairing = QrPairingScanner.getPairingPayload(applicationContext) ?: run {
            Log.d(TAG, "Device not paired yet; skipping memory sync")
            return Result.success()
        }

        Log.i(TAG, "Executing cloud memory sync for paired device: ${pairing.deviceId}")

        val prefs = applicationContext.getSharedPreferences("jarvis_turn_queue", Context.MODE_PRIVATE)
        val pendingJson = prefs.getString("pending_turns", null)

        if (pendingJson.isNullOrBlank()) {
            Log.d(TAG, "No pending memory turns to synchronize")
            return Result.success()
        }

        val endpointsToTry = mutableListOf<String>()
        // Prioritize local loopback tunnel first for instantaneous zero-latency delivery
        endpointsToTry.add("http://127.0.0.1:8766/v1/memory")
        val execEp = pairing.executionEndpoint
        if (execEp.isNotBlank() && !endpointsToTry.contains(execEp.trimEnd('/') + "/v1/memory")) {
            endpointsToTry.add(execEp.trimEnd('/') + "/v1/memory")
        }
        if (pairing.endpoint.isNotBlank()) {
            endpointsToTry.add(pairing.endpoint)
        }

        val requestBody = pendingJson.toRequestBody("application/json".toMediaType())

        for (endpoint in endpointsToTry) {
            try {
                Log.i(TAG, "Attempting memory sync to: $endpoint")
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer ${pairing.authToken}")
                    .addHeader("X-Jarvis-Device", pairing.deviceId)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.i(TAG, "Memory sync completed successfully with $endpoint (HTTP ${response.code})")
                    prefs.edit().remove("pending_turns").apply()
                    return Result.success()
                } else {
                    Log.w(TAG, "Memory sync to $endpoint returned HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed to $endpoint: ${e.message}")
            }
        }

        return Result.retry()
    }

    companion object {
        private const val TAG = "CloudMemorySyncWorker"
        private const val PERIODIC_WORK_TAG = "jarvis_periodic_memory_sync"

        /**
         * Enqueue periodic memory sync worker requiring active network connection.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<CloudMemorySyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.i(TAG, "Periodic memory sync worker scheduled (15 min interval)")
        }

        /**
         * Trigger an immediate background memory sync attempt.
         */
        fun syncNow(context: Context) {
            val syncRequest = androidx.work.OneTimeWorkRequestBuilder<CloudMemorySyncWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(syncRequest)
        }

        /**
         * Queue a single turn for eventual cloud/desktop synchronization.
         */
        fun enqueueTurn(context: Context, role: String, content: String) {
            val pairing = QrPairingScanner.getPairingPayload(context)
            val deviceId = pairing?.deviceId ?: "mobile-companion"
            val record = MemoryDeltaRecord(
                id = java.util.UUID.randomUUID().toString(),
                role = role,
                content = content,
                deviceId = deviceId
            )

            val prefs = context.getSharedPreferences("jarvis_turn_queue", Context.MODE_PRIVATE)
            val existingJson = prefs.getString("pending_turns", null)
            val existingRecords = mutableListOf<MemoryDeltaRecord>()
            if (!existingJson.isNullOrBlank()) {
                try {
                    val root = org.json.JSONObject(existingJson)
                    val array = root.optJSONArray("records")
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            existingRecords.add(MemoryDeltaRecord(
                                id = item.getString("id"),
                                timestamp = item.getLong("timestamp"),
                                role = item.getString("role"),
                                content = item.getString("content"),
                                deviceId = item.optString("deviceId", deviceId)
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed parsing existing pending turns: ${e.message}")
                }
            }
            existingRecords.add(record)

            val bundle = SyncPayloads.buildSyncBundleJson(
                deviceId = deviceId,
                authToken = pairing?.authToken ?: "",
                records = existingRecords
            )
            prefs.edit().putString("pending_turns", bundle).apply()
        }
    }
}
