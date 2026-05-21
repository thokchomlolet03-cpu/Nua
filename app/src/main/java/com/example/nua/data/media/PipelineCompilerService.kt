package com.example.nua.data.media

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
import androidx.core.app.NotificationCompat
import com.example.nua.data.asr.VoskTranscriber
import com.example.nua.data.llm.GemmaTranslator
import com.example.nua.data.tts.DubbingTtsEngine
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
    private lateinit var voskTranscriber: VoskTranscriber
    private lateinit var gemmaTranslator: GemmaTranslator
    private lateinit var ttsEngine: DubbingTtsEngine

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(this)
        audioDecoder = AudioDecoder()
        voskTranscriber = VoskTranscriber(this)
        gemmaTranslator = GemmaTranslator(this)
        ttsEngine = DubbingTtsEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val videoPath = intent.getStringExtra(EXTRA_VIDEO_PATH) ?: ""
            val gemmaModelPath = intent.getStringExtra(EXTRA_GEMMA_MODEL_PATH)
            val mockMode = intent.getBooleanExtra(EXTRA_MOCK_MODE, true)

            val notification = createNotification("Initializing compilation...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            if (videoPath.isNotEmpty()) {
                startCompilation(videoPath, gemmaModelPath, mockMode)
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
        gemmaTranslator.close()
    }

    private fun startCompilation(videoPath: String, gemmaModelPath: String?, mockMode: Boolean) {
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

                // 2. Decode original audio -> original_audio.wav
                updateStatus("Extracting Audio", 0.0f, "Step 1/5: Extracting and resampling original audio...")
                val originalAudioWav = File(sessionDir, "original_audio.wav")
                val decodeSuccess = audioDecoder.decodeVideoToWav(localVideoFile, originalAudioWav) { progress ->
                    updateStatus("Extracting Audio", progress)
                }

                if (!decodeSuccess || !originalAudioWav.exists()) {
                    updateStatus("Error", 0f, "❌ Failed to extract audio track from video.")
                    stopSelf()
                    return@launch
                }
                addLog("Audio extracted successfully: ${originalAudioWav.length()} bytes")

                // 3. Speech-to-Text Transcription with Vosk
                updateStatus("Transcribing", 0.0f, "Step 2/5: Transcribing speech offline with Vosk...")
                if (!mockMode && !voskTranscriber.isModelDownloaded()) {
                    updateStatus("Error", 0f, "❌ Vosk language model is not downloaded. Please download in Setup first.")
                    stopSelf()
                    return@launch
                }

                val segments = voskTranscriber.transcribeWav(originalAudioWav) { progress ->
                    updateStatus("Transcribing", progress)
                }
                addLog("Transcribed ${segments.size} speech segments.")

                // 4. LLM Translation with Gemma
                updateStatus("Translating", 0.0f, "Step 3/5: Translating text using Gemma...")
                if (!mockMode) {
                    if (gemmaModelPath.isNullOrEmpty()) {
                        updateStatus("Error", 0f, "❌ Gemma model path is not set.")
                        stopSelf()
                        return@launch
                    }
                    val loaded = gemmaTranslator.initModel(gemmaModelPath)
                    if (!loaded) {
                        updateStatus("Error", 0f, "❌ Failed to initialize Gemma model.")
                        stopSelf()
                        return@launch
                    }
                }

                // 5. TTS Voice Synthesis (with speed matching)
                updateStatus("Generating Voice", 0.0f, "Step 4/5: Generating dubbed audio voice track...")
                val vocalChunksDir = sessionManager.getVocalChunksDir(sessionDir)
                val playbackSegments = ArrayList<PlaybackSegment>()
                var prevTranslation: String? = null

                for (i in segments.indices) {
                    val seg = segments[i]
                    val targetDur = seg.endTimeSec - seg.startTimeSec

                    addLog("Translating segment ${i+1}/${segments.size}...")
                    val translation = gemmaTranslator.translate(seg.text, targetDur, prevTranslation, mockMode)
                    addLog("  Original: \"${seg.text}\"")
                    addLog("  Translated: \"$translation\"")

                    // Update sliding context
                    prevTranslation = translation

                    val startMs = (seg.startTimeSec * 1000).toLong()
                    val endMs = (seg.endTimeSec * 1000).toLong()
                    val chunkFile = File(vocalChunksDir, "vocal_${startMs}_${endMs}.wav")

                    addLog("Synthesizing segment ${i+1}/${segments.size}...")
                    val ttsSuccess = ttsEngine.synthesizeSpeech(translation, targetDur, chunkFile)
                    
                    val directive = if (ttsSuccess && chunkFile.exists()) "NORMAL_SYNC" else "PAD_EMPTY"
                    if (!ttsSuccess) {
                        addLog("  ⚠️ Warning: Failed to generate voice for segment ${i+1}")
                    } else {
                        val ttsDur = ttsEngine.getWavDurationSeconds(chunkFile)
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
                            directive = directive
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

    private fun updateStatus(step: String, progress: Float, logMsg: String? = null) {
        _currentStep.value = step
        _stepProgress.value = progress
        if (logMsg != null) {
            addLog(logMsg)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification("$step: ${(progress * 100).toInt()}%"))
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inStream ->
            FileOutputStream(dst).use { outStream ->
                inStream.channel.transferTo(0, inStream.channel.size(), outStream.channel)
            }
        }
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

        const val ACTION_START = "com.example.nua.action.START"
        const val EXTRA_VIDEO_PATH = "extra_video_path"
        const val EXTRA_GEMMA_MODEL_PATH = "extra_gemma_model_path"
        const val EXTRA_MOCK_MODE = "extra_mock_mode"

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
    }
}
