package com.example.nua.data.telemetry

import android.content.Context
import android.util.Log
import com.google.flatbuffers.FlatBufferBuilder
import com.example.nua.data.schema.TelemetryPayload
import java.io.File
import java.security.MessageDigest

/**
 * Telemetry interface stub for mesh-network analytics.
 *
 * CURRENT STATE: Interface contract with local storage only.
 * The actual Wi-Fi Direct P2P mesh relay is deferred to a future phase.
 *
 * Architecture:
 * - Engagement events (completion %, quiz scores) are stored locally as
 *   cryptographically signed FlatBuffers payloads
 * - When network is available, the accumulated telemetry ledger is flushed
 *   to the central analytics server in a single batch
 * - Future: P2P mesh relay allows offline devices to aggregate telemetry
 *   through intermediate peers before any device reaches connectivity
 */
interface TelemetryContract {
    /** Record a session completion event */
    fun recordCompletion(sessionId: String, completionPercentage: Int)

    /** Record quiz results for a session */
    fun recordQuizScores(sessionId: String, scoresJson: String)

    /** Flush all pending telemetry when network is available */
    suspend fun flushToServer()

    /** Get count of pending (un-flushed) telemetry entries */
    fun pendingCount(): Int
}

/**
 * Local-only implementation of the telemetry contract.
 * Stores signed payloads to disk; network flush is a no-op stub.
 */
class LocalTelemetryStore(private val context: Context) : TelemetryContract {

    companion object {
        private const val TAG = "TelemetryStore"
        private const val TELEMETRY_DIR = "telemetry_ledger"
    }

    private val ledgerDir: File by lazy {
        File(context.filesDir, TELEMETRY_DIR).also { it.mkdirs() }
    }

    override fun recordCompletion(sessionId: String, completionPercentage: Int) {
        val payload = buildPayload(
            sessionId = sessionId,
            completionPercentage = completionPercentage.coerceIn(0, 100),
            quizScoresJson = ""
        )
        writePayload(sessionId, "completion", payload)
        Log.d(TAG, "Recorded completion: $sessionId → $completionPercentage%")
    }

    override fun recordQuizScores(sessionId: String, scoresJson: String) {
        val payload = buildPayload(
            sessionId = sessionId,
            completionPercentage = 0,
            quizScoresJson = scoresJson
        )
        writePayload(sessionId, "quiz", payload)
        Log.d(TAG, "Recorded quiz scores: $sessionId")
    }

    override suspend fun flushToServer() {
        // STUB: Network transmission deferred to mesh-network implementation phase
        val pending = pendingCount()
        if (pending > 0) {
            Log.i(TAG, "Telemetry flush requested ($pending entries pending). " +
                    "Network relay not yet implemented — entries retained locally.")
        }
    }

    override fun pendingCount(): Int {
        return ledgerDir.listFiles()?.size ?: 0
    }

    // ─── Internal ─────────────────────────────────────────────────────────

    private fun buildPayload(
        sessionId: String,
        completionPercentage: Int,
        quizScoresJson: String
    ): ByteArray {
        val builder = FlatBufferBuilder(256)
        val sessionIdOff = builder.createString(sessionId)
        val quizJsonOff = builder.createString(quizScoresJson)

        // Generate cryptographic signature (SHA-256 of content)
        val contentHash = sha256("$sessionId|$completionPercentage|$quizScoresJson")
        val sigOff = builder.createString(contentHash)

        val root = TelemetryPayload.createTelemetryPayload(
            builder,
            sessionIdOffset = sessionIdOff,
            completionPercentage = completionPercentage.toUByte(),
            quizScoresJsonOffset = quizJsonOff,
            cryptographicSignatureOffset = sigOff
        )
        builder.finish(root)
        return builder.sizedByteArray()
    }

    private fun writePayload(sessionId: String, eventType: String, data: ByteArray) {
        val filename = "${sessionId}_${eventType}_${System.currentTimeMillis()}.tlm"
        File(ledgerDir, filename).writeBytes(data)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
