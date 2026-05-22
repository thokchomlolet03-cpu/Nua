package com.example.nua.ui.player

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.nua.data.media.MediaComposition
import com.example.nua.data.media.SessionManager
import com.example.nua.data.media.SyncPlayerEngine
import com.example.nua.data.media.TimelineInterval
import com.example.nua.data.media.VirtualTimelineMapper
import com.example.nua.data.media.HotspotInfo
import com.example.nua.data.media.QuizInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val sessionManager = SessionManager(context)

    private var syncEngine: SyncPlayerEngine? = null

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

    private var mapper: VirtualTimelineMapper? = null
    private var sessionDir: File? = null
    private var composition: MediaComposition? = null

    private val shownQuizTimestamps: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

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

                val map = VirtualTimelineMapper(comp, dir)
                mapper = map

                // Reset quiz progress state
                shownQuizTimestamps.clear()

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

                        // Evaluate quiz triggers based on playhead position
                        checkQuizTriggers(state.virtualTimeMs)
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

    fun submitQuizAnswer() {
        _activeQuiz.value = null
        play()
    }

    fun releasePlayers() {
        syncEngine?.release()
        syncEngine = null
        _isPlaying.value = false
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayers()
    }
}
