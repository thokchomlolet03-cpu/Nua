package org.nua.production.app.data.telemetry

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.util.Log

class TelemetryWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "TelemetryWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting background telemetry sync...")
            val store = LocalTelemetryStore.getInstance(context)
            store.flushToServer()
            Log.d(TAG, "Background telemetry sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background telemetry sync failed", e)
            Result.retry()
        }
    }
}
