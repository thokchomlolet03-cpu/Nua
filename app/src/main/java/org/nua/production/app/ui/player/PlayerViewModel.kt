package org.nua.production.app.ui.player

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.nua.production.app.data.media.MediaComposition
import org.nua.production.app.data.media.SessionManager
import org.nua.production.app.data.media.SyncPlayerEngine
import org.nua.production.app.data.media.TimelineInterval
import org.nua.production.app.data.media.VirtualTimelineMapper
import org.nua.production.app.data.media.HotspotInfo
import org.nua.production.app.data.media.QuizInfo
import org.nua.production.app.data.llm.ModelLifecycleManager
import org.nua.production.app.data.schema.LectureSession
import org.nua.production.app.data.telemetry.LocalTelemetryStore
import org.nua.production.app.data.telemetry.QuizResponse
import org.nua.production.app.data.telemetry.TelemetryContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

data class ChatMessage(
    val role: String,
    val text: String,
    val thinkingText: String? = null,
    val imageUri: String? = null,
    val audioUri: String? = null
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val sessionManager = SessionManager(context.filesDir)
    private val telemetryStore: TelemetryContract = LocalTelemetryStore.getInstance(context)

    private var syncEngine: SyncPlayerEngine? = null

    private val actionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "org.nua.production.app.ACTION_SEEK_TO" -> {
                    val timeMs = intent.getLongExtra("time_ms", 0L)
                    seekTo(timeMs)
                }
                "org.nua.production.app.ACTION_TRIGGER_QUIZ" -> {
                    // For demo purposes, we trigger the first quiz or force check
                    checkQuizTriggers(virtualTimeMs.value)
                }
                "org.nua.production.app.ACTION_BOOKMARK" -> {
                    // Bookmarking logic could be added here to save to a local DB
                    val timeMs = intent.getLongExtra("time_ms", 0L)
                    val note = intent.getStringExtra("note") ?: ""
                    Log.d("PlayerViewModel", "Bookmark received: $timeMs ms - $note")
                }
            }
        }
    }

    init {
        val filter = android.content.IntentFilter().apply {
            addAction("org.nua.production.app.ACTION_SEEK_TO")
            addAction("org.nua.production.app.ACTION_TRIGGER_QUIZ")
            addAction("org.nua.production.app.ACTION_BOOKMARK")
        }
        // In Android 14+ local broadcasts are typically handled via flow or context-registered receivers.
        // We use ContextCompat registerReceiver to receive these local broadcasts.
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            actionReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    // Expose ExoPlayers from engine for AndroidView rendering
    val videoPlayer: ExoPlayer? get() = syncEngine?.videoPlayer
    val vocalPlayer: ExoPlayer? get() = syncEngine?.audioPlayer

    // State flows for UI
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _virtualTimeMs = MutableStateFlow(0L)
    val virtualTimeMs: StateFlow<Long> = _virtualTimeMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _currentOriginalText = MutableStateFlow("")
    val currentOriginalText: StateFlow<String> = _currentOriginalText.asStateFlow()

    private val _currentTranslatedText = MutableStateFlow("")
    val currentTranslatedText: StateFlow<String> = _currentTranslatedText.asStateFlow()

    private val _currentHotspots = MutableStateFlow<List<HotspotInfo>>(emptyList())
    val currentHotspots: StateFlow<List<HotspotInfo>> = _currentHotspots.asStateFlow()

    private val _activeQuiz = MutableStateFlow<QuizInfo?>(null)
    val activeQuiz: StateFlow<QuizInfo?> = _activeQuiz.asStateFlow()

    private val _isFreezing = MutableStateFlow(false)
    val isFreezing: StateFlow<Boolean> = _isFreezing.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // AI Tutor state flows
    private val _isTutorActive = MutableStateFlow(false)
    val isTutorActive: StateFlow<Boolean> = _isTutorActive.asStateFlow()

    // Voice recording states
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var recordFile: File? = null

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private val _tutorMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val tutorMessages: StateFlow<List<ChatMessage>> = _tutorMessages.asStateFlow()

    private val _isTutorTyping = MutableStateFlow(false)
    val isTutorTyping: StateFlow<Boolean> = _isTutorTyping.asStateFlow()

    private var rawSession: LectureSession? = null

    private var mapper: VirtualTimelineMapper? = null
    private var sessionDir: File? = null
    private var composition: MediaComposition? = null

    private val shownQuizTimestamps: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
    private val maxCompletionPercentage = java.util.concurrent.atomic.AtomicInteger(0)

    // Adaptive Pacing State
    private var lastActiveIntervalId: String? = null
    private val recentHotspotClicks = mutableListOf<Long>()

    fun initSession(sessionPath: String) {
        if (syncEngine != null) {
            releasePlayers()
        }

        _isLoading.value = true
        _errorMessage.value = null

        val dir = File(sessionPath)
        sessionDir = dir

        if (!dir.exists() || !dir.isDirectory) {
            _errorMessage.value = "Session directory does not exist: $sessionPath"
            _isLoading.value = false
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val comp = sessionManager.loadManifest(dir) ?: throw Exception("manifest.nuab or manifest.json not found or invalid in session directory")
                composition = comp
                rawSession = sessionManager.loadManifestBinary(dir)

                val map = VirtualTimelineMapper.create(comp, dir)
                mapper = map

                // Reset quiz progress state
                shownQuizTimestamps.clear()
                maxCompletionPercentage.set(0)

                withContext(Dispatchers.Main) {
                    _totalDurationMs.value = map.totalVirtualDurationMs

                    // Initialize the synchronized dual-player engine
                    val engine = SyncPlayerEngine(context)
                    engine.setMapper(map)
                    engine.setSessionDir(dir)

                    val videoFile = File(dir, comp.sourceVideoPath)
                    if (!videoFile.exists()) {
                        throw Exception("Source video file not found: ${videoFile.name}")
                    }
                    engine.setVideoSource(MediaItem.fromUri(videoFile.absolutePath))

                    // Connect engine callbacks to UI state flows
                    engine.onStateUpdate = { state ->
                        _virtualTimeMs.value = state.virtualTimeMs
                        _currentOriginalText.value = state.currentOriginalText ?: ""
                        _currentTranslatedText.value = state.currentSubtitle ?: ""
                        _isFreezing.value = state.isFreezing
                        _currentHotspots.value = state.activeInterval?.hotspots ?: emptyList()

                        // Track completion percentage peak
                        val totalDur = _totalDurationMs.value
                        if (totalDur > 0) {
                            val pct = (state.virtualTimeMs * 100 / totalDur).toInt().coerceIn(0, 100)
                            while (true) {
                                val current = maxCompletionPercentage.get()
                                if (pct <= current || maxCompletionPercentage.compareAndSet(current, pct)) break
                            }
                        }

                        // Evaluate quiz triggers based on playhead position
                        checkQuizTriggers(state.virtualTimeMs)

                        // Reset adaptive pacing on semantic boundary transition
                        val currentIntervalId = state.activeInterval?.vocalAssetLocalPath
                        if (currentIntervalId != lastActiveIntervalId) {
                            lastActiveIntervalId = currentIntervalId
                            syncEngine?.setAdaptivePacing(false)
                        }
                    }

                    syncEngine = engine
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to load session", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Failed to load session manifest: ${e.message}"
                    _isLoading.value = false
                }
            }
        }
    }

    private fun checkQuizTriggers(virtualTimeMs: Long) {
        val comp = composition ?: return
        if (!_isPlaying.value) return

        val triggeredQuiz = comp.quizzes.firstOrNull { quiz ->
            virtualTimeMs >= quiz.triggerTimestampMs &&
                virtualTimeMs <= quiz.triggerTimestampMs + 1500L && // trigger within 1.5s window
                !shownQuizTimestamps.contains(quiz.triggerTimestampMs)
        }

        if (triggeredQuiz != null) {
            shownQuizTimestamps.add(triggeredQuiz.triggerTimestampMs)
            _activeQuiz.value = triggeredQuiz
            pause()
        }
    }

    fun togglePlayPause() {
        val engine = syncEngine ?: return
        val playing = !_isPlaying.value

        if (playing && _activeQuiz.value != null) return // Lock playback if quiz is active

        if (playing) {
            play()
        } else {
            pause()
        }
    }

    fun play() {
        val engine = syncEngine ?: return
        if (_activeQuiz.value != null) return
        engine.play()
        _isPlaying.value = true
    }

    fun pause() {
        val engine = syncEngine ?: return
        engine.pause()
        _isPlaying.value = false
    }

    fun seekTo(virtualMs: Long) {
        val engine = syncEngine ?: return
        // Allow re-triggering quizzes that are after the seek point
        synchronized(shownQuizTimestamps) {
            shownQuizTimestamps.removeAll { it >= virtualMs }
        }
        engine.seekTo(virtualMs)
        _virtualTimeMs.value = virtualMs
    }

    fun submitQuizAnswer(quiz: QuizInfo, selectedIndex: Int, latencyMs: Long) {
        val comp = composition
        if (comp != null) {
            val sessionId = comp.videoId
            val isCorrect = selectedIndex == quiz.correctIndex
            val response = QuizResponse(
                quizId = quiz.triggerTimestampMs.toString(),
                selectedIndex = selectedIndex,
                latencyMs = latencyMs,
                isCorrect = isCorrect
            )
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    telemetryStore.recordQuizResponses(sessionId, listOf(response))
                    telemetryStore.flushToServer()
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "Failed to record quiz responses", e)
                }
            }
            if (!isCorrect) {
                syncEngine?.setAdaptivePacing(true)
            }
        }
        _activeQuiz.value = null
        play()
    }

    fun onHotspotClicked(hotspot: HotspotInfo) {
        val now = System.currentTimeMillis()
        synchronized(recentHotspotClicks) {
            recentHotspotClicks.add(now)
            
            // Remove clicks older than 15 seconds
            recentHotspotClicks.removeAll { now - it > 15000 }
            
            // High engagement density: 3 or more hotspots clicked in 15 seconds
            if (recentHotspotClicks.size >= 3) {
                syncEngine?.setAdaptivePacing(true)
            }
        }
    }

    fun toggleTutor(active: Boolean) {
        _isTutorActive.value = active
        if (active) {
            pause()
            // Load tutor model asynchronously
            viewModelScope.launch(Dispatchers.IO) {
                _isTutorTyping.value = true
                try {
                    val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
                    // Use tutor_model_path if present, fallback to gemma_model_path
                    val modelPath = prefs.getString("tutor_model_path", null)
                        ?: prefs.getString("gemma_model_path", null)
                    if (modelPath.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            _tutorMessages.value = _tutorMessages.value + ChatMessage(
                                role = "tutor",
                                text = "Error: Tutor model not configured. Please import a model in Settings."
                            )
                        }
                        return@launch
                    }
                    ModelLifecycleManager.switchToTutor(context, modelPath)
                    // Post a welcome message if empty
                    withContext(Dispatchers.Main) {
                        if (_tutorMessages.value.isEmpty()) {
                            _tutorMessages.value = listOf(
                                ChatMessage("tutor", "Hello! I am your AI lecture tutor. Ask me any questions about this lecture!")
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "Error loading tutor model", e)
                    withContext(Dispatchers.Main) {
                        _tutorMessages.value = _tutorMessages.value + ChatMessage(
                            role = "tutor",
                            text = "Failed to initialize AI Tutor: ${e.localizedMessage}"
                        )
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        _isTutorTyping.value = false
                    }
                }
            }
        } else {
            // Return from tutor: unload tutor to save resources
            viewModelScope.launch(Dispatchers.IO) {
                ModelLifecycleManager.releaseAll()
            }
        }
    }

    fun askTutor(question: String, imageUri: String? = null, audioUri: String? = null) {
        if (question.isBlank() && imageUri == null && audioUri == null) return

        // Append user message with attachments
        val userMsg = ChatMessage(
            role = "user",
            text = if (question.isNotBlank()) question else if (audioUri != null) "[Voice Query]" else "[Photo Attachment]",
            imageUri = imageUri,
            audioUri = audioUri
        )
        _tutorMessages.value = _tutorMessages.value + userMsg
        _isTutorTyping.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
                val modelPath = prefs.getString("tutor_model_path", null)
                    ?: prefs.getString("gemma_model_path", null)
                if (modelPath.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        _tutorMessages.value = _tutorMessages.value + ChatMessage(
                            role = "tutor",
                            text = "Error: Tutor model not configured. Please import a model in Settings."
                        )
                        _isTutorTyping.value = false
                    }
                    return@launch
                }

                // Ensure tutor is active and loaded
                val tutorEngine = ModelLifecycleManager.switchToTutor(context, modelPath)

                val session = rawSession
                if (session == null) {
                    withContext(Dispatchers.Main) {
                        _tutorMessages.value = _tutorMessages.value + ChatMessage(
                            role = "tutor",
                            text = "Error: Lecture session metadata is not loaded."
                        )
                        _isTutorTyping.value = false
                    }
                    return@launch
                }

                // Prepend image/audio contextual prompt details (e.g. OCR text)
                var finalPrompt = question
                if (imageUri != null) {
                    // TODO: Integrate on-device OCR (e.g. ML Kit Text Recognition) to extract real text from images.
                    // For now, indicate to the LLM that a photo was attached without fabricating content.
                    finalPrompt = "[The student attached a photo/screenshot for reference. Please answer based on the lecture context and the student's question.]\n$finalPrompt"
                }
                if (audioUri != null) {
                    finalPrompt = "[Voice Query]: $finalPrompt"
                }

                val playheadMs = _virtualTimeMs.value
                val finalResponse = tutorEngine.executeGraphQuery(finalPrompt, session, playheadMs)

                // Parse <thinking> block if it exists
                val thinkingRegex = "<thinking>(.*?)</thinking>".toRegex(RegexOption.DOT_MATCHES_ALL)
                val thinkingMatch = thinkingRegex.find(finalResponse)
                val thinkingText = thinkingMatch?.groups?.get(1)?.value?.trim()
                val finalText = finalResponse.replace(thinkingRegex, "").trim()

                withContext(Dispatchers.Main) {
                    _tutorMessages.value = _tutorMessages.value + ChatMessage(
                        role = "tutor",
                        text = finalText,
                        thinkingText = thinkingText
                    )
                    _isTutorTyping.value = false
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Tutor generation error", e)
                withContext(Dispatchers.Main) {
                    _tutorMessages.value = _tutorMessages.value + ChatMessage(
                        role = "tutor",
                        text = "Error: Failed to generate response (${e.localizedMessage})"
                    )
                    _isTutorTyping.value = false
                }
            }
        }
    }

    fun startVoiceRecording() {
        if (_isRecordingVoice.value) return
        try {
            val file = File(context.cacheDir, "tutor_voice_record_${System.currentTimeMillis()}.m4a")
            recordFile = file
            
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }
            
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            
            recorder.prepare()
            recorder.start()
            
            mediaRecorder = recorder
            _isRecordingVoice.value = true
            Log.d("PlayerViewModel", "Voice recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Failed to start voice recording", e)
        }
    }

    fun stopVoiceRecording(): String? {
        if (!_isRecordingVoice.value) return null
        var path: String? = null
        try {
            mediaRecorder?.let {
                it.stop()
                it.release()
            }
            path = recordFile?.absolutePath
            Log.d("PlayerViewModel", "Voice recording stopped. File: $path")
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Failed to stop voice recording", e)
        } finally {
            mediaRecorder = null
            recordFile = null
            _isRecordingVoice.value = false
        }
        return path
    }

    fun askAboutConcept(concept: String, definition: String) {
        if (!_isTutorActive.value) {
            toggleTutor(true)
        }
        val prompt = "Explain this concept in detail in the context of our lecture:\nConcept: $concept\nDescription: $definition"
        askTutor(prompt)
    }

    fun releasePlayers() {
        val comp = composition
        val pct = maxCompletionPercentage.get()
        if (comp != null && pct > 0) {
            val sessionId = comp.videoId
            try {
                telemetryStore.recordCompletion(sessionId, pct)
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Failed to record completion telemetry", e)
            }
            viewModelScope.launch(NonCancellable + Dispatchers.IO) {
                try {
                    telemetryStore.flushToServer()
                } catch (e: Exception) {
                    Log.e("PlayerViewModel", "Failed to flush telemetry", e)
                }
            }
        }
        maxCompletionPercentage.set(0)
        shownQuizTimestamps.clear()

        syncEngine?.release()
        syncEngine = null
        _isPlaying.value = false
        viewModelScope.launch(NonCancellable + Dispatchers.IO) {
            ModelLifecycleManager.releaseAll()
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            context.unregisterReceiver(actionReceiver)
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Error unregistering receiver", e)
        }
        releasePlayers()
    }
}
