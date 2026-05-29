package org.nua.production.app.data.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nua.production.app.data.rag.OfflineTutorEngine
import org.nua.production.app.data.schema.LectureSession
import org.nua.production.app.media.tts.DubbingTtsEngine

/**
 * Orchestrator for the Voice-First UI (Premium Tier).
 * Pipes microphone audio via Whisper, feeds transcribed text to Gemma 4,
 * and streams the resulting answer directly to the local Android TTS engine.
 */
class VoiceAgentController(
    private val context: Context,
    private val tutorEngine: OfflineTutorEngine,
    private val ttsEngine: DubbingTtsEngine
) {

    companion object {
        private const val TAG = "VoiceAgentController"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * Starts continuous listening mode.
     */
    fun startListening(session: LectureSession, playheadTimeMs: Long) {
        if (_isListening.value) return
        _isListening.value = true

        scope.launch {
            try {
                // Stub: In reality this connects to live Whisper or Cloud Gemini streaming.
                val whisper = WhisperTranscriber(context)
                // TODO: whisper.transcriptionFlow is currently an emptyFlow() stub.
                // This feature requires continuous microphone sampling to be built out.
                whisper.transcriptionFlow.collect { text ->
                    if (text.isNotBlank()) {
                        handleTranscription(text, session, playheadTimeMs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listening error", e)
            } finally {
                _isListening.value = false
            }
        }
    }

    /**
     * Stop the Voice Agent, including TTS and ASR.
     */
    fun stopListening() {
        _isListening.value = false
        ttsEngine.stopSpeaking()
        // transcriber might have a stop method, assuming it is started/stopped externally or we manage it here
    }

    private suspend fun handleTranscription(text: String, session: LectureSession, playheadTimeMs: Long) {
        // Detect interrupt commands
        if (text.contains("hey nua pause", ignoreCase = true) || text.contains("stop", ignoreCase = true)) {
            Log.d(TAG, "Interrupt detected: $text")
            ttsEngine.stopSpeaking()
            return
        }

        Log.d(TAG, "User asked via Voice: $text")
        
        // Pass to Gemma 4
        val response = tutorEngine.executeGraphQuery(text, session, playheadTimeMs)

        // Stream output to TTS
        _isSpeaking.value = true
        ttsEngine.speakText(response)
        _isSpeaking.value = false
    }

    fun release() {
        stopListening()
        scope.cancel()
    }
}
