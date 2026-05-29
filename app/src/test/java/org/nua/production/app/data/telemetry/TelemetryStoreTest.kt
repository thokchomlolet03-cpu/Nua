package org.nua.production.app.data.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.nua.production.app.data.schema.TelemetryPayload
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class TelemetryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var telemetryStore: LocalTelemetryStore

    class TestMockContext(private val filesDirFile: File) : ContextWrapper(null) {
        override fun getFilesDir(): File {
            return filesDirFile
        }

        override fun getSystemService(name: String): Any? {
            return null
        }

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            return null
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            // No-op
        }
    }

    @Before
    fun setUp() {
        mockContext = TestMockContext(tempFolder.newFolder("files"))
        telemetryStore = LocalTelemetryStore(mockContext, "fallback_secret")
    }

    @Test
    fun testRecordCompletion_writesCorrectPayload() {
        val sessionId = "session_completion_123"
        val completionPercentage = 87

        telemetryStore.recordCompletion(sessionId, completionPercentage)

        assertEquals(1, telemetryStore.pendingCount())

        // Find the written file
        val files = File(mockContext.filesDir, "telemetry_ledger").listFiles()
        assertNotNull(files)
        assertEquals(1, files!!.size)
        val file = files[0]
        assertTrue(file.name.startsWith("${sessionId}_completion_"))
        assertTrue(file.name.endsWith(".tlm"))

        // Read and parse FlatBuffers
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val payload = TelemetryPayload.getRootAsTelemetryPayload(buffer)

        assertEquals(sessionId, payload.sessionId)
        assertEquals(completionPercentage.toUByte(), payload.completionPercentage)
        assertEquals(0, payload.quizResponsesLength)

        // Verify cryptographic signature
        val expectedHash = computeHmacSha256("$sessionId|$completionPercentage|".toByteArray(), "fallback_secret")
        assertEquals(expectedHash, payload.cryptographicSignature)
    }

    @Test
    fun testRecordQuizResponses_writesCorrectPayload() {
        val sessionId = "session_quiz_456"
        val responses = listOf(
            QuizResponse("q_1", 2, 1200L, true),
            QuizResponse("q_2", 0, 2500L, false)
        )

        telemetryStore.recordQuizResponses(sessionId, responses)

        assertEquals(1, telemetryStore.pendingCount())

        // Find the written file
        val files = File(mockContext.filesDir, "telemetry_ledger").listFiles()
        assertNotNull(files)
        assertEquals(1, files!!.size)
        val file = files[0]
        assertTrue(file.name.startsWith("${sessionId}_quiz_"))
        assertTrue(file.name.endsWith(".tlm"))

        // Read and parse FlatBuffers
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val payload = TelemetryPayload.getRootAsTelemetryPayload(buffer)

        assertEquals(sessionId, payload.sessionId)
        assertEquals(0.toUByte(), payload.completionPercentage)
        assertEquals(2, payload.quizResponsesLength)

        val resp1 = payload.quizResponses(0)!!
        assertEquals("q_1", resp1.quizId)
        assertEquals(2.toUByte(), resp1.selectedOptionIndex)
        assertEquals(1200L, resp1.latencyMs)
        assertTrue(resp1.isCorrect)

        val resp2 = payload.quizResponses(1)!!
        assertEquals("q_2", resp2.quizId)
        assertEquals(0.toUByte(), resp2.selectedOptionIndex)
        assertEquals(2500L, resp2.latencyMs)
        assertTrue(!resp2.isCorrect)

        // Verify cryptographic signature
        val sb = StringBuilder()
        sb.append("q_1:2:1200:true,q_2:0:2500:false")
        val expectedHash = computeHmacSha256("$sessionId|0|$sb".toByteArray(), "fallback_secret")
        assertEquals(expectedHash, payload.cryptographicSignature)
    }

    @Test
    fun testPruneOldPayloads() {
        val sessionId = "session_prune"
        // Write 105 payloads
        for (i in 1..105) {
            telemetryStore.recordCompletion("$sessionId-$i", 50)
        }

        // It should keep exactly 100 payloads (pruning the oldest 5)
        assertEquals(100, telemetryStore.pendingCount())

        val files = File(mockContext.filesDir, "telemetry_ledger").listFiles()
        assertNotNull(files)
        assertEquals(100, files!!.size)
    }

    @Test
    fun testFlushToServer_noNetwork_doesNotDelete() {
        val sessionId = "session_flush"
        telemetryStore.recordCompletion(sessionId, 99)
        assertEquals(1, telemetryStore.pendingCount())

        // Under local unit tests, network manager is null/not available, so isNetworkAvailable() returns false
        // Let's run flushToServer()
        kotlinx.coroutines.test.runTest {
            telemetryStore.flushToServer()
        }

        // The file should still be pending because of no network
        assertEquals(1, telemetryStore.pendingCount())
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun computeHmacSha256(data: ByteArray, secret: String): String {
        val keySpec = javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val rawHmac = mac.doFinal(data)
        return rawHmac.joinToString("") { "%02x".format(it) }
    }
}
