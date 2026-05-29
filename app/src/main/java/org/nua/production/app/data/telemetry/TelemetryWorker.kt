package org.nua.production.app.data.telemetry

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.nua.production.app.data.network.NetworkGate

class TelemetryWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val networkGate = NetworkGate(context)
    private val telemetryStore = LocalTelemetryStore.getInstance(context)

    override suspend fun doWork(): Result {
        Log.i("TelemetryWorker", "Executing periodic telemetry reconciliation cycle...")

        // 1. HARDWARE CHECK: Verify true cloud internet capability before proceeding
        if (!networkGate.isCloudReachable()) {
            Log.i("TelemetryWorker", "Ecosystem is currently offline. Safely routing payloads to local P2P storage queues.")
            
            // Short-circuit execution path cleanly; run our local space pruning routine
            telemetryStore.pruneTelemetryStorageCeiling()
            return Result.success()
        }

        // 2. CLOUD FALLBACK PATH: Execute remote data sync routines only when network is validated
        return try {
            Log.i("TelemetryWorker", "Validated internet interface detected. Transmitting aggregated telemetry to cloud node.")
            
            telemetryStore.flushToServer()
            
            Result.success()
        } catch (e: Exception) {
            Log.e("TelemetryWorker", "Abrupt execution fault encountered during cloud transmission pass", e)
            Result.retry()
        }
    }
}
