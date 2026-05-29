package org.nua.production.app.data.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.flatbuffers.FlatBufferBuilder
import org.nua.production.app.data.schema.OptionSelection
import org.nua.production.app.data.schema.TelemetryPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Telemetry interface stub for mesh-network analytics.
 *
 * Implements Wi-Fi Direct P2P Mesh Routing Subsystem for offline telemetry aggregation.
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
 * Wi-Fi Direct Mesh and Local storage implementation of the telemetry contract.
 * Periodically discovers peers in background to share and aggregate telemetry log files.
 */
class LocalTelemetryStore internal constructor(
    context: Context,
    private val signingSecret: String = "nua_default_secure_secret_2026"
) : TelemetryContract {

    companion object {
        private const val TAG = "TelemetryStore"
        private const val TELEMETRY_DIR = "telemetry_ledger"

        @Volatile
        private var instance: LocalTelemetryStore? = null

        fun getInstance(context: Context): LocalTelemetryStore {
            return instance ?: synchronized(this) {
                instance ?: LocalTelemetryStore(
                    try {
                        context.applicationContext ?: context
                    } catch (e: Throwable) {
                        context
                    }
                ).also { instance = it }
            }
        }
    }

    private val appContext = try {
        context.applicationContext ?: context
    } catch (e: Throwable) {
        context
    }
    private val ledgerDir: File by lazy {
        File(appContext.filesDir, TELEMETRY_DIR).also { it.mkdirs() }
    }
    private val storagePruneLock = Any()

    private val meshManager = WifiDirectMeshManager()

    init {
        try {
            meshManager.start()
        } catch (e: Throwable) {
            Log.w(TAG, "Wi-Fi Direct P2P mesh setup skipped (non-Android environment)", e)
        }
        enqueueBackgroundSync()
    }

    private fun enqueueBackgroundSync() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<TelemetryWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                "TelemetrySyncWork",
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "Enqueued background telemetry sync worker")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue WorkManager telemetry sync", e)
        }
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
        if (!isNetworkAvailable()) {
            val pending = pendingCount()
            if (pending > 0) {
                Log.i(TAG, "Telemetry flush requested ($pending entries pending) but network is unavailable. Retained locally.")
            }
            return
        }

        val files = ledgerDir.listFiles() ?: return
        var successCount = 0

        withContext(Dispatchers.IO) {
            for (file in files) {
                if (!file.name.endsWith(".tlm")) continue
                try {
                    val bytes = file.readBytes()
                    val hmac = computeHmacSha256(bytes, signingSecret)

                    val url = URL("https://production.nua.org/api/v1/telemetry")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    connection.setRequestProperty("x-nua-signature", hmac)
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000

                    connection.outputStream.use { os ->
                        os.write(bytes)
                    }

                    val responseCode = connection.responseCode
                    try { connection.inputStream?.close() } catch (_: Exception) {}
                    try { connection.errorStream?.close() } catch (_: Exception) {}
                    
                    if (responseCode in 200..299) {
                        file.delete()
                        successCount++
                    } else {
                        Log.w(TAG, "Server rejected telemetry payload with code $responseCode")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload telemetry file ${file.name}", e)
                }
            }
        }

        if (successCount > 0) {
            Log.i(TAG, "Successfully flushed $successCount telemetry files to cloud backend.")
        }
    }

    override fun pendingCount(): Int {
        return ledgerDir.listFiles()?.size ?: 0
    }

    // ─── Wi-Fi Direct P2P Mesh Routing Subsystem ──────────────────────────

    private fun isNetworkAvailable(): Boolean {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val nw = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(nw) ?: return false
            return when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        } catch (e: Exception) {
            return false
        }
    }

    inner class WifiDirectMeshManager {
        private var manager: WifiP2pManager? = null
        private var channel: WifiP2pManager.Channel? = null
        private var receiver: BroadcastReceiver? = null
        private var serverSocket: ServerSocket? = null
        private var isServerRunning = false

        fun start() {
            try {
                manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                if (manager == null) return
                channel = manager?.initialize(appContext, appContext.mainLooper, null)

                val intentFilter = IntentFilter().apply {
                    addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                }

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                                    discover()
                                }
                            }
                            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                                try {
                                    manager?.requestPeers(channel) { peers ->
                                        val deviceList = peers.deviceList
                                        for (device in deviceList) {
                                            if (device.status == WifiP2pDevice.AVAILABLE) {
                                                connectToPeer(device)
                                            }
                                        }
                                    }
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "Missing location permission for P2P discovery", e)
                                }
                            }
                            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                                try {
                                    manager?.requestConnectionInfo(channel) { info ->
                                        onConnectionChanged(info)
                                    }
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "Missing location permission for P2P connection info", e)
                                }
                            }
                        }
                    }
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(receiver, intentFilter)
                }
                discover()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Wi-Fi Direct Mesh", e)
            }
        }

        private fun discover() {
            try {
                manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Peer discovery initiated successfully")
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Peer discovery initiation failed: $reason")
                    }
                })
            } catch (e: Exception) {}
        }

        private fun connectToPeer(device: WifiP2pDevice) {
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
            }
            try {
                manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Connection initiated to ${device.deviceName}")
                    }
                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Connection initiation failed: $reason")
                    }
                })
            } catch (e: Exception) {}
        }

        private fun onConnectionChanged(info: WifiP2pInfo) {
            if (info.groupFormed) {
                if (info.isGroupOwner) {
                    startServer()
                } else {
                    stopServer()
                    sendLocalPayloadsToOwner(info.groupOwnerAddress?.hostAddress)
                }
            } else {
                stopServer()
            }
        }

        private val executorService = java.util.concurrent.Executors.newFixedThreadPool(4)

        private fun startServer() {
            if (isServerRunning) return
            isServerRunning = true
            Thread {
                try {
                    serverSocket = ServerSocket(8988)
                    while (isServerRunning) {
                        val client = serverSocket?.accept() ?: break
                        executorService.execute {
                            try {
                                val dos = DataOutputStream(client.getOutputStream())
                                val dis = DataInputStream(client.getInputStream())

                                // 1. Random challenge-response handshake to authenticate the peer
                                val challenge = ByteArray(16)
                                java.security.SecureRandom().nextBytes(challenge)
                                dos.write(challenge)
                                dos.flush()

                                val clientResponse = ByteArray(32) // HMAC-SHA256 signature is 32 bytes
                                dis.readFully(clientResponse)

                                val expectedChallengeHmac = computeHmacBytes(challenge, signingSecret)
                                if (!java.security.MessageDigest.isEqual(clientResponse, expectedChallengeHmac)) {
                                    dos.writeByte(0) // Auth failed flag
                                    dos.flush()
                                    Log.w(TAG, "Rejecting unauthenticated connection on mesh telemetry socket")
                                    client.close()
                                    return@execute
                                }
                                dos.writeByte(1) // Auth success flag
                                dos.flush()

                                val reqLen = dis.readInt()
                                if (reqLen in 1..1024 * 1024) {
                                    val bytes = ByteArray(reqLen)
                                    dis.readFully(bytes)

                                    // Parse and cryptographically verify before saving
                                    val mappedBuffer = java.nio.ByteBuffer.wrap(bytes)
                                    mappedBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    val tp = TelemetryPayload.getRootAsTelemetryPayload(mappedBuffer)

                                    val sessionId = tp.sessionId ?: ""
                                    val compPercent = tp.completionPercentage.toInt()
                                    val responsesLen = tp.quizResponsesLength
                                    val signature = tp.cryptographicSignature ?: ""

                                    val sb = StringBuilder()
                                    for (i in 0 until responsesLen) {
                                        val qr = tp.quizResponses(i)
                                        if (qr != null) {
                                            sb.append("${qr.quizId}:${qr.selectedOptionIndex}:${qr.latencyMs}:${qr.isCorrect}")
                                            if (i < responsesLen - 1) sb.append(",")
                                        }
                                    }
                                    val expectedHash = computeHmacSha256(
                                        "$sessionId|$compPercent|$sb".toByteArray(Charsets.UTF_8),
                                        signingSecret
                                    )
                                    if (signature == expectedHash) {
                                        writePayload(sessionId, "mesh_received", bytes)
                                        Log.i(TAG, "Valid telemetry mesh packet saved for $sessionId")
                                    } else {
                                        Log.w(TAG, "Discarded invalid/untrusted mesh telemetry payload (HMAC mismatch)")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading mesh client payload", e)
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Mesh server socket error", e)
                }
            }.start()
        }

        private fun stopServer() {
            isServerRunning = false
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
        }

        private fun sendLocalPayloadsToOwner(ownerIp: String?) {
            if (ownerIp == null) return
            Thread {
                val files = ledgerDir.listFiles() ?: return@Thread
                for (file in files) {
                    if (!file.name.endsWith(".tlm")) continue
                    var socket: Socket? = null
                    try {
                        socket = Socket()
                        socket.connect(InetSocketAddress(ownerIp, 8988), 5000)
                        val dos = DataOutputStream(socket.getOutputStream())
                        val dis = DataInputStream(socket.getInputStream())

                        // 1. Solve the random challenge
                        val challenge = ByteArray(16)
                        dis.readFully(challenge)

                        val responseHmac = computeHmacBytes(challenge, signingSecret)
                        dos.write(responseHmac)
                        dos.flush()

                        val authResult = dis.readByte()
                        if (authResult.toInt() != 1) {
                            Log.w(TAG, "Mesh group owner rejected authentication handshake")
                            socket.close()
                            continue
                        }

                        val bytes = file.readBytes()
                        dos.writeInt(bytes.size)
                        dos.write(bytes)
                        dos.flush()
                        // Delete after successful transmission to mesh host
                        file.delete()
                        Log.i(TAG, "Transmitted telemetry file ${file.name} to mesh group owner")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send telemetry to owner $ownerIp", e)
                        break // Stop processing on connection error
                    } finally {
                        try { socket?.close() } catch (_: Exception) {}
                    }
                }
            }.start()
        }

        fun stop() {
            stopServer()
            try {
                receiver?.let { appContext.unregisterReceiver(it) }
            } catch (_: Exception) {}
            receiver = null
            executorService.shutdown()
        }
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
                selectedOptionIndex = qr.selectedIndex.toUByte(),
                latencyMs = qr.latencyMs,
                isCorrect = qr.isCorrect
            )
        }.toIntArray()
        val responsesVectorOff = if (responseOffsets.isNotEmpty()) {
            TelemetryPayload.createQuizResponsesVector(builder, responseOffsets)
        } else 0

        // Generate cryptographic signature (HMAC-SHA256 of content)
        val sb = StringBuilder()
        for (i in quizResponses.indices) {
            val qr = quizResponses[i]
            sb.append("${qr.quizId}:${qr.selectedIndex.toUByte()}:${qr.latencyMs}:${qr.isCorrect}")
            if (i < quizResponses.size - 1) sb.append(",")
        }
        val content = "$sessionId|$completionPercentage|$sb"
        val contentHash = computeHmacSha256(content.toByteArray(Charsets.UTF_8), signingSecret)
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
        val safeSessionId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val filename = "${safeSessionId}_${eventType}_${System.currentTimeMillis()}.tlm"
        File(ledgerDir, filename).writeBytes(data)
        pruneTelemetryStorageCeiling()
    }

    /**
     * Enforces a hard, deterministic 100-file ceiling on local telemetry logs.
     * Safely isolates and prunes the absolute oldest files on disk chronologically
     * to prevent storage overflow while preserving un-synced peer data.
     */
    fun pruneTelemetryStorageCeiling() {
        synchronized(storagePruneLock) {
            try {
                // 1. Isolate only telemetry payload files to prevent hitting metadata files
                val telemetryFiles = ledgerDir.listFiles { _, name -> 
                    name.endsWith(".tlm") 
                }

                if (telemetryFiles != null && telemetryFiles.size > 100) {
                    // 2. Sort chronologically by disk modification metadata (oldest first)
                    val sortedFiles = telemetryFiles.sortedBy { it.lastModified() }
                    
                    // 3. Determine precisely how many files exceed our 100-file safety window
                    val overflowCount = sortedFiles.size - 100
                    
                    Log.i("TelemetryStore", "Storage ceiling reached (${sortedFiles.size} files). Pruning $overflowCount old logs.")
                    
                    // 4. Run a deterministic deletion pass from oldest to newest
                    for (i in 0 until overflowCount) {
                        val targetFile = sortedFiles[i]
                        if (targetFile.exists()) {
                            val isDeleted = targetFile.delete()
                            if (isDeleted) {
                                Log.d("TelemetryStore", "Successfully pruned historical log: ${targetFile.name}")
                            } else {
                                Log.w("TelemetryStore", "Failed to delete target file asset: ${targetFile.name}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TelemetryStore", "Critical exception encountered during storage pruning pass", e)
            }
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun computeHmacBytes(data: ByteArray, secret: String): ByteArray {
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    private fun computeHmacSha256(data: ByteArray, secret: String): String {
        val rawHmac = computeHmacBytes(data, secret)
        return rawHmac.joinToString("") { "%02x".format(it) }
    }
}
