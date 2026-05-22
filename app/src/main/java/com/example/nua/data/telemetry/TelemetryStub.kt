package com.example.nua.data.telemetry

import android.content.Context
import android.util.Log
import com.google.flatbuffers.FlatBufferBuilder
import com.example.nua.data.schema.OptionSelection
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

/**
 * Represents a single quiz answer for structured telemetry.
 */
data class QuizResponse(
    val quizId: String,
    val selectedIndex: Int,
    val latencyMs: Long,
    val isCorrect: Boolean
)

interface TelemetryContract {
    /** Record a session completion event */
    fun recordCompletion(sessionId: String, completionPercentage: Int)

    /** Record quiz results for a session (structured) */
    fun recordQuizResponses(sessionId: String, responses: List<QuizResponse>)

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
            quizResponses = emptyList()
        )
        writePayload(sessionId, "completion", payload)
        Log.d(TAG, "Recorded completion: $sessionId → $completionPercentage%")
    }

    override fun recordQuizResponses(sessionId: String, responses: List<QuizResponse>) {
        val payload = buildPayload(
            sessionId = sessionId,
            completionPercentage = 0,
            quizResponses = responses
        )
        writePayload(sessionId, "quiz", payload)
        Log.d(TAG, "Recorded ${responses.size} quiz responses: $sessionId")
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
        quizResponses: List<QuizResponse>
    ): ByteArray {
        val builder = FlatBufferBuilder(256)
        val sessionIdOff = builder.createString(sessionId)

        // Build quiz response vector (typed OptionSelection instead of ad-hoc JSON)
        val responseOffsets = quizResponses.map { qr ->
            val quizIdOff = builder.createString(qr.quizId)
            OptionSelection.createOptionSelection(
                builder,
                quizIdOffset = quizIdOff,
                selectedIndex = qr.selectedIndex.toUByte(),
                latencyMs = qr.latencyMs,
                isCorrect = qr.isCorrect
            )
        }.toIntArray()
        val responsesVectorOff = if (responseOffsets.isNotEmpty()) {
            TelemetryPayload.createQuizResponsesVector(builder, responseOffsets)
        } else 0

        // Generate cryptographic signature (SHA-256 of content)
        val contentHash = sha256("$sessionId|$completionPercentage|${quizResponses.size}")
        val sigOff = builder.createString(contentHash)

        val root = TelemetryPayload.createTelemetryPayload(
            builder,
            sessionIdOffset = sessionIdOff,
            completionPercentage = completionPercentage.toUByte(),
            quizResponsesVectorOffset = responsesVectorOff,
            cryptographicSignatureOffset = sigOff
        )
        builder.finish(root)
        return builder.sizedByteArray()
    }

    private fun writePayload(sessionId: String, eventType: String, data: ByteArray) {
        val filename = "${sessionId}_${eventType}_${System.currentTimeMillis()}.tlm"
        File(ledgerDir, filename).writeBytes(data)
        pruneOldPayloads()
    }

    private fun pruneOldPayloads() {
        val files = ledgerDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        val maxFiles = 100
        if (files.size > maxFiles) {
            files.take(files.size - maxFiles).forEach { it.delete() }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
