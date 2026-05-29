package org.nua.production.app.media.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.nua.production.app.data.media.WavUtils
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DubbingTtsEngine @JvmOverloads constructor(private val context: Context? = null) {

    companion object {
        private const val TAG = "DubbingTtsEngine"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isMissingData = false
    private val initLatch = CountDownLatch(1)

    init {
        context?.let { ctx ->
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Configure TTS language
                    val locale = Locale("hi", "IN") // Hindi
                    val result = tts?.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "Hindi language is not supported or missing data")
                        isMissingData = true
                    } else {
                        isInitialized = true
                        Log.d(TAG, "TTS engine initialized successfully with Hindi locale")
                    }
                } else {
                    Log.e(TAG, "Failed to initialize TTS engine")
                }
                initLatch.countDown()
            }
        } ?: run {
            initLatch.countDown()
        }
    }

    private fun waitForInit(): Boolean {
        if (isInitialized) return true
        try {
            initLatch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Timeout waiting for TTS init", e)
        }
        return isInitialized
    }

    /**
     * Computes a safe, bounded playback rate modifier for the speech engine
     * based on the phonetic density estimation and physical video segment limits.
     *
     * Resolves the fatal NaN coercion bug by adding validation gates before clamping.
     *
     * @param estimatedDurationSec The estimated time required to speak the text at normal speed.
     * @param targetDurationSeconds The exact maximum window available in the video timeline.
     * @return A safely clamped float playback speed factor between 1.0f and 2.0f.
     */
    fun calculateTtsPlaybackRate(estimatedDurationSec: Float, targetDurationSeconds: Float): Float {
        // 1. DIVIDE-BY-ZERO GUARD: If target video time is zero or negative, fall back immediately
        if (targetDurationSeconds <= 0f) {
            Log.w("DubbingTtsEngine", "Invalid or zero target video timeline window ($targetDurationSeconds s). Defaulting to 1.0f baseline speed.")
            return 1.0f
        }

        val calculatedRate = estimatedDurationSec / targetDurationSeconds

        // 2. NaN PROTECTION GATE: Check if the calculation resulted in an undefined state (0.0 / 0.0)
        if (calculatedRate.isNaN()) {
            Log.w("DubbingTtsEngine", "Speech rate calculation evaluated to NaN (0.0 / 0.0 representation). Defaulting to 1.0f baseline speed.")
            return 1.0f
        }

        // 3. BOUNDARY CEILING CLAMP: Safely restrict playback rate bounds to keep speech intelligible
        val boundedRate = calculatedRate.coerceIn(1.0f, 2.0f)

        Log.d("DubbingTtsEngine", "TTS Speed Adjustment: Estimated (${estimatedDurationSec}s) -> " +
                "Target Window (${targetDurationSeconds}s) | Configured Rate: ${boundedRate}x")

        return boundedRate
    }

    /**
     * Synthesizes [text] to [outputFile] in Hindi.
     * Adjusts speech rate dynamically to fit within [targetDurationSeconds].
     * Runs synchronously. Call from a background thread.
     */
    fun synthesizeSpeech(text: String, targetDurationSeconds: Double, outputFile: File): Boolean {
        if (isMissingData) {
            throw IllegalStateException("TTS_LANG_MISSING_DATA")
        }
        if (!waitForInit() || tts == null) {
            Log.e(TAG, "TTS is not ready")
            return false
        }

        try {
            // Estimate spoken duration using syllable density analysis upfront
            val estimatedDurationMs = estimatePhoneticDurationMs(text, 1.0f)
            val estimatedDurationSec = estimatedDurationMs / 1000.0f

            val currentRate = calculateTtsPlaybackRate(estimatedDurationSec, targetDurationSeconds.toFloat())

            Log.d(TAG, "Single-pass synthesis: '$text' at rate $currentRate (estimated: $estimatedDurationSec s, target: $targetDurationSeconds s)")
            tts?.setSpeechRate(currentRate)

            val success = performSynthesis(text, outputFile)

            // Reset speech rate to default for next segment
            tts?.setSpeechRate(1.0f)

            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error in TTS synthesis", e)
            return false
        }
    }

    private fun performSynthesis(text: String, outputFile: File): Boolean {
        val utteranceId = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        val synthesisSuccess = AtomicBoolean(true)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Started
            }

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    latch.countDown()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS synthesis error: $utteranceId")
                synthesisSuccess.set(false)
                latch.countDown()
            }

            override fun onError(id: String?, errorCode: Int) {
                Log.e(TAG, "TTS synthesis error: $id, errorCode=$errorCode")
                synthesisSuccess.set(false)
                latch.countDown()
            }
        })

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val result = tts?.synthesizeToFile(text, params, outputFile, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Failed to start synthesizeToFile: $result")
            return false
        }

        try {
            latch.await(30, TimeUnit.SECONDS) // Wait for completion
        } catch (e: Exception) {
            Log.e(TAG, "TTS latch interrupted", e)
            return false
        }

        return synthesisSuccess.get() && outputFile.exists() && outputFile.length() > 0
    }

    fun getWavDurationSeconds(wavFile: File): Double {
        return WavUtils.getWavDurationSeconds(wavFile)
    }

    /**
     * Estimates spoken duration using syllable density analysis.
     * Uses Devanagari vowel markers + word count for Hinglish text.
     */
    fun estimatePhoneticDurationMs(text: String, baseSpeechRate: Float = 1.0f): Long {
        val devanagariVowels = listOf('\u093E', '\u093F', '\u0940', '\u0941', '\u0942',
            '\u0947', '\u0948', '\u094B', '\u094C', '\u0902')
        val syllableCount = text.count { it in devanagariVowels } + (text.length / 3)
        val baselineWordsPerMinute = 135.0f * baseSpeechRate
        val wordCount = text.split("\\s+".toRegex()).size.coerceAtLeast(1)
        return ((wordCount / baselineWordsPerMinute) * 60.0f * 1000.0f).toLong() + (syllableCount * 45L)
    }

    /**
     * Speaks text immediately for the Voice Agent UI.
     */
    fun speakText(text: String) {
        if (!waitForInit() || tts == null) return
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UUID.randomUUID().toString())
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    /**
     * Stops currently speaking text.
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // Ignore
        }
        tts = null
    }
}
