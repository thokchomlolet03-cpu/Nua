package org.nua.production.app.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.nua.production.app.media.player.SyncPlayerEngine
import org.nua.production.app.media.tts.DubbingTtsEngine
import org.nua.production.app.data.storage.FlatBufferStorageDriver

class VideoLectureViewModel(application: Application) : AndroidViewModel(application) {

    // 1. Initialize our hardened structural backend singletons
    private val storageDriver = FlatBufferStorageDriver.getInstance(application)
    private val ttsEngine = DubbingTtsEngine(application) 
    
    // Lazy initialized to decouple Android Framework dependencies during testing
    val syncPlayerEngine by lazy { SyncPlayerEngine(application) }

    // 2. Expose an immutable state stream to the Compose layer
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var quizSequenceCounter = 0L

    /**
     * Intercepts playback ticks from the ExoPlayer timeline and pipes them 
     * directly into our float-precision synchronization engine.
     */
    fun onPlaybackTick(positionMs: Long, durationMs: Long, audioTrackDurationMs: Long) {
        viewModelScope.launch {
            // Run our precision float speed calculations from Step 2
            val targetSpeed = syncPlayerEngine.calculateVideoPlaybackSpeed(durationMs, audioTrackDurationMs)
            
            _uiState.update { currentState ->
                currentState.copy(
                    currentPositionMs = positionMs,
                    calculatedPlaybackSpeed = targetSpeed,
                    syncStatusMessage = "Syncing audio timeline at ${String.format("%.2f", targetSpeed)}x"
                )
            }
            
            // Check if current position collides with a lesson quiz hotspot (e.g., at the 30-second mark)
            if (positionMs >= 30000 && _uiState.value.activeQuizId == null && !_uiState.value.isQuizSubmitted) {
                triggerQuizHotspot("quiz_module_01")
            }
        }
    }

    private fun triggerQuizHotspot(quizId: String) {
        _uiState.update { it.copy(activeQuizId = quizId, isPlaying = false) }
        // Safely pass our silent timeline NaN protection gates from Step 3
        ttsEngine.speakText("Attention! Please answer the question on your screen.")
    }

    /**
     * Serializes user interaction directly to isolated binary .tlm file boundaries.
     */
    fun submitQuizAnswer(answerIndex: Int) {
        val quizId = _uiState.value.activeQuizId ?: return
        quizSequenceCounter++

        viewModelScope.launch {
            // Step 8 Storage Decoupling: Write atomic binary file to cacheDir
            val recordedFile = storageDriver.writeRecord(
                sessionId = quizId,
                completionPercentage = 100,
                sequenceVersion = quizSequenceCounter
            )

            _uiState.update { currentState ->
                currentState.copy(
                    activeQuizId = null,
                    isQuizSubmitted = true,
                    isPlaying = true,
                    syncStatusMessage = "Progress securely committed to: ${recordedFile?.name}"
                )
            }
        }
    }
}
