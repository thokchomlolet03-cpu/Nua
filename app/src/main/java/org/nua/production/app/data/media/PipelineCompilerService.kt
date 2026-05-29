package org.nua.production.app.data.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.nua.production.app.data.asr.WhisperTranscriber
import org.nua.production.app.data.asr.FirebaseTranscriber
import org.nua.production.app.data.asr.TextSegment
import org.nua.production.app.data.llm.LiteRTTranslator
import org.nua.production.app.media.tts.DubbingTtsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class PipelineCompilerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var sessionManager: SessionManager
    private lateinit var audioDecoder: AudioDecoder
    private lateinit var whisperTranscriber: WhisperTranscriber
    private lateinit var firebaseTranscriber: FirebaseTranscriber
    private lateinit var liteRTTranslator: LiteRTTranslator
    private lateinit var ttsEngine: DubbingTtsEngine

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(filesDir)
        audioDecoder = AudioDecoder()
        whisperTranscriber = WhisperTranscriber(this)
        firebaseTranscriber = FirebaseTranscriber(this)
        liteRTTranslator = LiteRTTranslator(this)
        ttsEngine = DubbingTtsEngine(this)
        createNotificationChannel()

        // Reset companion object StateFlow fields to clear stale/crash states
        _isProcessing.value = false
        _currentStep.value = ""
        _stepProgress.value = 0f
        _logs.value = emptyList()
        _completedSessionDir.value = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: ""
            val gemmaModelPath = intent.getStringExtra(EXTRA_GEMMA_MODEL_PATH)
            val mockMode = intent.getBooleanExtra(EXTRA_MOCK_MODE, true)
            val asrMode = intent.getStringExtra(EXTRA_ASR_MODE) ?: ASR_MODE_OFFLINE

            val notification = createNotification("Initializing compilation...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            if (videoPath.isNotEmpty()) {
                startCompilation(videoPath, gemmaModelPath, mockMode, asrMode)
            } else {
                updateStatus("Error", 0f, "❌ Missing input video path.")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        ttsEngine.shutdown()
        liteRTTranslator.close()
        // Reset process state flow
        _isProcessing.value = false
    }

    private fun startCompilation(videoPath: String, gemmaModelPath: String?, mockMode: Boolean, asrMode: String) {
        if (_isProcessing.value) return
        _isProcessing.value = true
        _completedSessionDir.value = null
        _logs.value = emptyList()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val inputFile = File(videoPath)
                if (!inputFile.exists()) {
                    updateStatus("Error", 0f, "❌ Input video file does not exist.")
                    stopSelf()
                    return@launch
                }

                val sessionDir = sessionManager.getSessionDir(inputFile.name)
                addLog("Created session directory: ${sessionDir.absolutePath}")

                // 1. Copy video file to session directory
                updateStatus("Importing Video", 0f, "Importing video to session directory...")
                val localVideoFile = File(sessionDir, "raw_lecture.mp4")
                if (localVideoFile.exists()) {
                    localVideoFile.delete()
                }
                copyFile(inputFile, localVideoFile)
                addLog("Video imported to: ${localVideoFile.absolutePath}")

                // 2 & 3. Decoupled Asynchronous Extraction and Transcription
                updateStatus("Extracting & Transcribing", 0.02f, "Step 1 & 2: Extracting audio and transcribing concurrently...")
                val originalAudioWav = File(sessionDir, "original_audio.wav")

                // Strict backpressure tracking gate
                val audioChannel = kotlinx.coroutines.channels.Channel<org.nua.production.app.data.asr.AudioChunk>(
                    capacity = 1,
                    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
                )

                // 1. ASYNCHRONOUS CONSUMER: Fired completely independently on the background thread pool
                val transcriptionJob = async(Dispatchers.IO) {
                    runCatching {
                        if (asrMode == ASR_MODE_CLOUD) {
                            // For cloud, we still wait for full file then upload
                            // Drain channel to prevent memory leak
                            for (c in audioChannel) {}
                            updateStatus("Transcribing", 0.0f, "Step 2: Transcribing speech in cloud with Firebase AI...")
                            var cloudSegments: List<TextSegment> = emptyList()
                            firebaseTranscriber.transcribeWav(originalAudioWav, mockMode) { progress ->
                                updateStatus("Transcribing (Cloud)", progress)
                                cloudSegments = emptyList()
                            }
                            cloudSegments
                        } else {
                            if (mockMode) {
                                for (c in audioChannel) {}
                                emptyList<TextSegment>()
                            } else {
                                if (!whisperTranscriber.isModelDownloaded()) {
                                    throw IllegalStateException("Whisper language model is not downloaded. Please download in Setup first.")
                                }
                                whisperTranscriber.transcribeStream(audioChannel)
                            }
                        }
                    }
                }

                // 2. ASYNCHRONOUS PRODUCER: Decoupled entirely from the service coordinator thread
                val extractionJob = async(Dispatchers.IO) {
                    runCatching {
                        audioDecoder.decodeVideoToWav(localVideoFile, originalAudioWav, audioChannel) { progress ->
                            // Reflect real data weights cleanly
                            updateStatus("Extracting Audio", progress * 0.1f, null)
                        }
                    }
                }

                // 3. PIPELINE LIFECYCLE COORDINATOR: Monitor transcription completion first
                val txResult = transcriptionJob.await()

                if (txResult.isFailure) {
                    val exception = txResult.exceptionOrNull()
                    Log.e(TAG, "ASR engine threw an unhandled failure block during execution", exception)

                    // CRITICAL CORRECTION: Force channel closure to instantly break the decoder's suspension lock
                    audioChannel.close()

                    // CRITICAL CORRECTION: Explicitly update the UI status state flow to report the error text
                    updateStatus("Error", 0.0f, "❌ Transcription failed: ${exception?.message}")
                    stopSelf()
                    return@launch
                }

                val segments = txResult.getOrThrow()

                // Await extraction completion
                val extractResult = extractionJob.await()
                if (extractResult.isFailure || extractResult.getOrNull() != true) {
                    Log.e(TAG, "Media extraction thread encountered a disk I/O failure flag.")
                    updateStatus("Error", 0.0f, "❌ Critical: System failed to extract video track.")
                    stopSelf()
                    return@launch
                }

                if (!originalAudioWav.exists()) {
                    updateStatus("Error", 0f, "❌ Failed to extract audio track from video.")
                    stopSelf()
                    return@launch
                }

                addLog("Transcribed ${segments.size} speech segments.")

                // 4. LLM Translation with Gemma
                if (!checkBatteryAndPersist(sessionDir, "TRANSLATING")) { stopSelf(); return@launch }
                updateStatus("Translating", 0.0f, "Step 3/5: Translating text using Gemma...")
                if (!mockMode) {
                    if (gemmaModelPath.isNullOrEmpty()) {
                        updateStatus("Error", 0f, "❌ Gemma model path is not set.")
                        stopSelf()
                        return@launch
                    }
                    val loaded = liteRTTranslator.initModel(gemmaModelPath)
                    if (!loaded) {
                        updateStatus("Error", 0f, "❌ Failed to initialize Gemma model.")
                        stopSelf()
                        return@launch
                    }
                }

                // 5. TTS Voice Synthesis (with speed matching)
                if (!checkBatteryAndPersist(sessionDir, "SYNTHESIZING")) { stopSelf(); return@launch }
                updateStatus("Generating Voice", 0.0f, "Step 4/5: Generating dubbed audio voice track...")
                val vocalChunksDir = sessionManager.getVocalChunksDir(sessionDir)
                val playbackSegments = ArrayList<PlaybackSegment>()
                var prevTranslation: String? = null

                for (i in segments.indices) {
                    val seg = segments[i]
                    val targetDur = seg.endTimeSec - seg.startTimeSec

                    addLog("Translating segment ${i+1}/${segments.size}...")
                    val translation = liteRTTranslator.translate(seg.text, targetDur, prevTranslation, mockMode)
                    addLog("  Original: \"${seg.text}\"")
                    addLog("  Translated: \"$translation\"")

                    // Update sliding context
                    prevTranslation = translation

                    val startMs = (seg.startTimeSec * 1000).toLong()
                    val endMs = (seg.endTimeSec * 1000).toLong()
                    val chunkFile = File(vocalChunksDir, "vocal_${startMs}_${endMs}.wav")

                    addLog("Synthesizing segment ${i+1}/${segments.size}...")
                    val ttsSuccess = ttsEngine.synthesizeSpeech(translation, targetDur, chunkFile)
                    
                    var actualAudioDurationMs: Long? = null
                    var directive = if (ttsSuccess && chunkFile.exists()) PlaybackDirective.NORMAL_SYNC else PlaybackDirective.PAD_EMPTY
                    if (!ttsSuccess) {
                        addLog("  ⚠️ Warning: Failed to generate voice for segment ${i+1}")
                    } else {
                        val ttsDur = ttsEngine.getWavDurationSeconds(chunkFile)
                        actualAudioDurationMs = (ttsDur * 1000).toLong()
                        if (actualAudioDurationMs > (endMs - startMs)) {
                            directive = PlaybackDirective.FREEZE_HOLD
                        }
                        addLog("  Voice generated: ${String.format("%.2f", ttsDur)}s (Target: ${String.format("%.2f", targetDur)}s)")
                    }

                    // Store path relative to session directory for portability
                    val relativePath = "vocal_chunks/${chunkFile.name}"

                    playbackSegments.add(
                        PlaybackSegment(
                            startMs = startMs,
                            endMs = endMs,
                            originalText = seg.text,
                            translatedText = translation,
                            vocalAssetLocalPath = relativePath,
                            directive = directive,
                            audioDurationMs = actualAudioDurationMs
                        )
                    )

                    updateStatus("Generating Voice", (i + 1).toFloat() / segments.size.toFloat())
                }

                // 6. Write Manifest
                updateStatus("Writing Manifest", 0.9f, "Step 5/5: Creating session manifest...")
                val mediaComposition = MediaComposition(
                    videoId = sessionDir.name,
                    sourceVideoPath = "raw_lecture.mp4",
                    segments = playbackSegments
                )
                sessionManager.saveManifest(sessionDir, mediaComposition)
                addLog("Session manifest written successfully.")

                // Cleanup original decoded WAV
                if (originalAudioWav.exists()) {
                    originalAudioWav.delete()
                }

                addLog("🎉 Translation & compilation completed successfully!")
                updateStatus("Completed", 1.0f, "Compilation finished!")
                _completedSessionDir.value = sessionDir
            } catch (e: Exception) {
                Log.e(TAG, "Compilation failed", e)
                updateStatus("Error", 0f, "❌ Compilation failed: ${e.message}")
            } finally {
                _isProcessing.value = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
    }

    private var lastNotificationTime = 0L

    private fun updateStatus(step: String, progress: Float, logMsg: String? = null) {
        _currentStep.value = step
        _stepProgress.value = progress
        if (logMsg != null) {
            addLog(logMsg)
        }
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime > 1000 || progress >= 1.0f || progress <= 0.0f) {
            lastNotificationTime = now
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, createNotification("$step: ${(progress * 100).toInt()}%"))
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inStream ->
            FileOutputStream(dst).use { outStream ->
                val buffer = ByteArray(8 * 1024 * 1024) // 8MB buffer
                var bytesRead: Int
                while (inStream.read(buffer).also { bytesRead = it } != -1) {
                    outStream.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    private fun checkBatteryAndPersist(sessionDir: File, stage: String): Boolean {
        File(sessionDir, "pipeline_state.json").writeText("{\"stage\":\"$stage\"}")
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = level * 100 / scale.toFloat()
        
        if (batteryPct > 0 && batteryPct < 15.0f) {
            Log.w(TAG, "Battery level $batteryPct% is too low. Halting compilation pipeline.")
            updateStatus("Paused", 0f, "⏸️ Battery too low ($batteryPct%). Processing halted.")
            
            // Enqueue WorkManager to resume later
            val workRequest = OneTimeWorkRequestBuilder<PipelineResumeWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork("PipelineResume", ExistingWorkPolicy.REPLACE, workRequest)
            return false
        }
        return true
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Project Nua Asset Compiler"
            val descriptionText = "Processes video lectures offline into dubbed packages"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nua Video Compiler")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PipelineCompilerService"

        const val ACTION_START = "org.nua.production.app.action.START"
        const val EXTRA_VIDEO_PATH = "extra_video_path"
        const val EXTRA_GEMMA_MODEL_PATH = "extra_gemma_model_path"
        const val EXTRA_MOCK_MODE = "extra_mock_mode"
        const val EXTRA_ASR_MODE = "extra_asr_mode"

        const val ASR_MODE_OFFLINE = "offline"
        const val ASR_MODE_CLOUD = "cloud"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "nua_pipeline_channel"

        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        private val _currentStep = MutableStateFlow("")
        val currentStep: StateFlow<String> = _currentStep.asStateFlow()

        private val _stepProgress = MutableStateFlow(0f)
        val stepProgress: StateFlow<Float> = _stepProgress.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        private val _completedSessionDir = MutableStateFlow<File?>(null)
        val completedSessionDir: StateFlow<File?> = _completedSessionDir.asStateFlow()

        fun addLog(msg: String) {
            synchronized(_logs) {
                _logs.value = _logs.value + msg
            }
        }

        fun clearLogs() {
            synchronized(_logs) {
                _logs.value = emptyList()
            }
        }
    }
}
