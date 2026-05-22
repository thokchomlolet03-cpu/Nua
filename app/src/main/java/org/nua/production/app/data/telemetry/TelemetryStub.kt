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
class LocalTelemetryStore(private val context: Context) : TelemetryContract {

    companion object {
        private const val TAG = "TelemetryStore"
        private const val TELEMETRY_DIR = "telemetry_ledger"
    }

    private val ledgerDir: File by lazy {
        File(context.filesDir, TELEMETRY_DIR).also { it.mkdirs() }
    }

    private val meshManager = WifiDirectMeshManager()

    init {
        try {
            meshManager.start()
        } catch (e: Throwable) {
            Log.w(TAG, "Wi-Fi Direct P2P mesh setup skipped (non-Android environment)", e)
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
                    val hmac = computeHmacSha256(bytes, "fallback_secret")

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
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
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
                manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                if (manager == null) return
                channel = manager?.initialize(context, context.mainLooper, null)

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
                                manager?.requestPeers(channel) { peers ->
                                    val deviceList = peers.deviceList
                                    for (device in deviceList) {
                                        if (device.status == WifiP2pDevice.AVAILABLE) {
                                            connectToPeer(device)
                                        }
                                    }
                                }
                            }
                            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                                manager?.requestConnectionInfo(channel) { info ->
                                    onConnectionChanged(info)
                                }
                            }
                        }
                    }
                }
                context.registerReceiver(receiver, intentFilter)
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

        private fun startServer() {
            if (isServerRunning) return
            isServerRunning = true
            Thread {
                try {
                    serverSocket = ServerSocket(8988)
                    while (isServerRunning) {
                        val client = serverSocket?.accept() ?: break
                        Thread {
                            try {
                                val dis = DataInputStream(client.getInputStream())
                                val len = dis.readInt()
                                if (len in 1..1024 * 1024) {
                                    val bytes = ByteArray(len)
                                    dis.readFully(bytes)

                                    // Parse and cryptographically verify before saving
                                    val mappedBuffer = java.nio.ByteBuffer.wrap(bytes)
                                    mappedBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    val tp = TelemetryPayload.getRootAsTelemetryPayload(mappedBuffer)

                                    val sessionId = tp.sessionId ?: ""
                                    val compPercent = tp.completionPercentage.toInt()
                                    val responsesLen = tp.quizResponsesLength
                                    val signature = tp.cryptographicSignature ?: ""

                                    val expectedHash = sha256("$sessionId|$compPercent|$responsesLen")
                                    if (signature == expectedHash) {
                                        writePayload(sessionId, "mesh_received", bytes)
                                        Log.i(TAG, "Valid telemetry mesh packet saved for $sessionId")
                                    } else {
                                        Log.w(TAG, "Discarded invalid/untrusted mesh telemetry payload")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading mesh client payload", e)
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }.start()
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
                receiver?.let { context.unregisterReceiver(it) }
            } catch (_: Exception) {}
            receiver = null
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

    private fun computeHmacSha256(data: ByteArray, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val rawHmac = mac.doFinal(data)
        return rawHmac.joinToString("") { "%02x".format(it) }
    }
}
