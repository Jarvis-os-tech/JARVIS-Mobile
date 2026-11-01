package com.jarvis.os.sync

import android.content.Context
import androidx.work.*
import com.jarvis.os.net.DeviceToolClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Confirms only after pairing credentials have been successfully persisted. */
class PairingConfirmationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val session = inputData.getString("session").orEmpty()
        val pairing = QrPairingScanner.getPairingPayload(applicationContext)
        if (pairing?.pairingSession != session || !Regex("[a-f0-9]{64}").matches(session)) return@withContext Result.failure()
        if (DeviceToolClient(applicationContext).confirmPairing(session)) Result.success()
        else if (runAttemptCount < 5) Result.retry() else Result.failure()
    }

    companion object {
        fun enqueue(context: Context, session: String) {
            if (!Regex("[a-f0-9]{64}").matches(session)) return
            val request = OneTimeWorkRequestBuilder<PairingConfirmationWorker>()
                .setInputData(workDataOf("session" to session))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("jarvis_pairing_confirmation", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
