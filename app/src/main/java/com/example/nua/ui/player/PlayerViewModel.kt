package com.example.nua.ui.player

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.nua.data.media.MediaComposition
import com.example.nua.data.media.SessionManager
import com.example.nua.data.media.TimelineInterval
import com.example.nua.data.media.VirtualTimelineMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val sessionManager = SessionManager(context)

    // Players
    var videoPlayer: ExoPlayer? = null
        private set
    var vocalPlayer: ExoPlayer? = null
        private set

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

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var mapper: VirtualTimelineMapper? = null
    private var sessionDir: File? = null
    private var composition: MediaComposition? = null

    // Sync loop handler
    private val handler = Handler(Looper.getMainLooper())
    private var isSeeking = false
    private var activeVocalPath: String? = null

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (_isPlaying.value && !isSeeking) {
                runSyncTick()
            }
            if (_isPlaying.value) {
                handler.postDelayed(this, 30)
            }
        }
    }

    fun initSession(sessionPath: String) {
        // Guard against double initialization
        if (videoPlayer != null) {
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
                val comp = sessionManager.loadManifest(dir) ?: throw Exception("manifest.json not found or invalid in session directory")
                composition = comp

                val map = VirtualTimelineMapper(comp, dir)
                mapper = map

                withContext(Dispatchers.Main) {
                    _totalDurationMs.value = map.totalVirtualDurationMs
                    // Initialize players on Main Thread
                    initializePlayers(dir, comp)
                    _isLoading.value = false
                    // Start sync loop
                    handler.post(syncRunnable)
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

    private fun initializePlayers(dir: File, comp: MediaComposition) {
        val videoFile = File(dir, comp.sourceVideoPath)
        if (!videoFile.exists()) {
            throw Exception("Source video file not found: ${videoFile.name}")
        }

        videoPlayer = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoFile.absolutePath))
            prepare()
            repeatMode = Player.REPEAT_MODE_OFF
        }

        vocalPlayer = ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    fun togglePlayPause() {
        val playing = !_isPlaying.value
        _isPlaying.value = playing

        if (playing) {
            applyPlaybackState()
            // Restart sync loop
            handler.removeCallbacks(syncRunnable)
            handler.post(syncRunnable)
        } else {
            videoPlayer?.pause()
            vocalPlayer?.pause()
        }
    }

    fun seekTo(virtualMs: Long) {
        isSeeking = true
        _virtualTimeMs.value = virtualMs

        val map = mapper ?: return
        val state = map.getPhysicalState(virtualMs)

        // Sync Video Position
        videoPlayer?.let { vp ->
            vp.seekTo(state.physicalTimeMs)
            if (state.shouldFreeze) {
                vp.pause()
            } else if (_isPlaying.value) {
                vp.play()
            }
        }

        // Sync Vocal Player
        syncVocalAsset(state.activeInterval, state.vocalPlayheadMs)

        // Update Subtitles
        updateSubtitles(state.activeInterval)

        isSeeking = false
    }

    private fun runSyncTick() {
        val map = mapper ?: return
        val vp = videoPlayer ?: return
        val ap = vocalPlayer ?: return

        var currentVirtual = _virtualTimeMs.value
        val state = map.getPhysicalState(currentVirtual)

        if (state.activeInterval != null && state.shouldFreeze) {
            // Audio drives timeline
            if (ap.isPlaying) {
                currentVirtual = state.activeInterval.virtualStartMs + ap.currentPosition
            } else if (ap.playbackState == Player.STATE_ENDED) {
                // Audio finished, so we step past this freeze hold interval
                currentVirtual = state.activeInterval.virtualEndMs + 1
            }
        } else {
            // Video drives timeline (either normal segment playback or gap/silence)
            currentVirtual = map.getVirtualTimeMs(vp.currentPosition)
        }

        // Keep within bounds
        currentVirtual = currentVirtual.coerceIn(0L, _totalDurationMs.value)
        _virtualTimeMs.value = currentVirtual

        // Apply visual updates / sync correction
        applyPlaybackState()
    }

    private fun applyPlaybackState() {
        val map = mapper ?: return
        val vp = videoPlayer ?: return
        val ap = vocalPlayer ?: return

        val state = map.getPhysicalState(_virtualTimeMs.value)

        updateSubtitles(state.activeInterval)

        // Handle Video Player Play/Pause and Ducking
        if (state.activeInterval != null) {
            vp.volume = 0.10f // Duck original English audio
            if (state.shouldFreeze) {
                if (vp.isPlaying) vp.pause()
                vp.seekTo(state.physicalTimeMs) // Lock it to physicalTimeMs
            } else {
                if (_isPlaying.value && !vp.isPlaying) vp.play()
            }
        } else {
            vp.volume = 1.0f // Restore original audio in silence gaps
            if (_isPlaying.value && !vp.isPlaying) vp.play()
        }

        // Handle Vocal Player
        syncVocalAsset(state.activeInterval, state.vocalPlayheadMs)
    }

    private fun syncVocalAsset(interval: TimelineInterval?, playheadMs: Long) {
        val ap = vocalPlayer ?: return
        val dir = sessionDir ?: return

        if (interval == null) {
            if (ap.isPlaying) ap.pause()
            activeVocalPath = null
            return
        }

        val targetFile = File(dir, interval.vocalAssetLocalPath)
        val path = targetFile.absolutePath

        if (activeVocalPath != path) {
            ap.stop()
            if (targetFile.exists()) {
                ap.setMediaItem(MediaItem.fromUri(path))
                ap.prepare()
                ap.seekTo(playheadMs)
                if (_isPlaying.value) ap.play()
                activeVocalPath = path
            } else {
                activeVocalPath = null
            }
        } else {
            // Check drift between vocal playhead and timeline mapping
            val drift = abs(ap.currentPosition - playheadMs)
            if (drift > 300L) {
                ap.seekTo(playheadMs)
            }
            if (_isPlaying.value && !ap.isPlaying && ap.playbackState != Player.STATE_ENDED) {
                ap.play()
            } else if (!_isPlaying.value && ap.isPlaying) {
                ap.pause()
            }
        }
    }

    private fun updateSubtitles(interval: TimelineInterval?) {
        if (interval != null) {
            _currentOriginalText.value = interval.originalText
            _currentTranslatedText.value = interval.translatedText
        } else {
            _currentOriginalText.value = ""
            _currentTranslatedText.value = ""
        }
    }

    fun releasePlayers() {
        handler.removeCallbacks(syncRunnable)
        videoPlayer?.release()
        vocalPlayer?.release()
        videoPlayer = null
        vocalPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayers()
    }
}
