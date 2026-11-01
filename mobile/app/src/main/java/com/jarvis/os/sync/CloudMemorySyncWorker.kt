package com.jarvis.os.sync

import android.content.Context
import androidx.work.*
import com.jarvis.os.net.DeviceToolClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Two-way authenticated synchronization with the PC's continuous memory. */
class CloudMemorySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!QrPairingScanner.isPaired(applicationContext)) return Result.success()
        return if (synchronize(applicationContext)) Result.success() else Result.retry()
    }
    companion object {
        private val syncLock = java.util.concurrent.locks.ReentrantLock()
        fun synchronize(context: Context, maxBatches: Int = 20): Boolean {
            if (!syncLock.tryLock()) return false
            try {
              return ContinuousMemory(context).use { memory ->
                memory.migrateLegacy(context)
                if (!QrPairingScanner.isPaired(context)) return@use true
                val client = DeviceToolClient(context)
                // Bound each run; remaining batches are retained and retried.
                repeat(maxBatches) {
                    val pending = memory.pending()
                    val result = client.exchangeMemory(pending) ?: return@use false
                    if (result.optString("status") != "ok" || !result.has("acknowledged")) return@use false
                    memory.accept(result, pending)
                    if (!memory.hasPending()) return@use true
                    if (result.getJSONArray("acknowledged").length() == 0) return@use false
                }
                false
              }
            } finally { syncLock.unlock() }
        }
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("jarvis_periodic_memory_sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CloudMemorySyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("jarvis_memory_sync_now", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CloudMemorySyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun enqueueTurn(context: Context, role: String, content: String) {
            if (content.isBlank()) return
            ContinuousMemory(context).use { memory ->
                memory.migrateLegacy(context)
                memory.append(MemoryDeltaRecord(java.util.UUID.randomUUID().toString(), role = role,
                    content = content, deviceId = "mobile"))
            }
        }
    }
}
