package com.example.nua.data.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class DubbingTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "DubbingTtsEngine"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var isMissingData = false
    private val initLatch = CountDownLatch(1)

    init {
        tts = TextToSpeech(context) { status ->
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
            // Step 1: Synthesize at default normal rate (1.0)
            Log.d(TAG, "Synthesizing: '$text' for duration $targetDurationSeconds s")
            var currentRate = 1.0f
            tts?.setSpeechRate(currentRate)

            var success = performSynthesis(text, outputFile)
            if (!success) return false

            val initialDuration = getWavDurationSeconds(outputFile)
            Log.d(TAG, "Initial synthesis duration: $initialDuration s (target: $targetDurationSeconds s)")

            if (initialDuration > targetDurationSeconds && targetDurationSeconds > 0) {
                // Step 2: Calculate speed ratio and re-synthesize if needed to fit
                val speedRatio = (initialDuration / targetDurationSeconds).toFloat()
                // Limit speed rate to 2.0 to keep speech intelligible
                currentRate = speedRatio.coerceIn(1.0f, 2.0f)
                
                if (currentRate > 1.05f) {
                    Log.d(TAG, "Re-synthesizing with speed rate $currentRate to fit target duration")
                    tts?.setSpeechRate(currentRate)
                    success = performSynthesis(text, outputFile)
                    if (success) {
                        val finalDuration = getWavDurationSeconds(outputFile)
                        Log.d(TAG, "Final speed-adjusted duration: $finalDuration s")
                    }
                }
            }

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
        if (wavFile.length() <= 44) return 0.0
        var fis: java.io.FileInputStream? = null
        try {
            val buffer = ByteArray(44)
            fis = java.io.FileInputStream(wavFile)
            val read = fis.read(buffer)
            if (read < 44) return 0.0

            // Read channels: byte 22-23 (Short)
            val channels = ByteBuffer.wrap(buffer, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            // Read sample rate: byte 24-27 (Int)
            val sampleRate = ByteBuffer.wrap(buffer, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int.toInt()
            // Read bits per sample: byte 34-35 (Short)
            val bitsPerSample = ByteBuffer.wrap(buffer, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

            val pcmLength = wavFile.length() - 44
            val bytesPerSample = bitsPerSample / 8
            val bytesPerSecond = sampleRate * channels * bytesPerSample

            if (bytesPerSecond <= 0) return 0.0
            return pcmLength.toDouble() / bytesPerSecond.toDouble()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read WAV duration", e)
            return 0.0
        } finally {
            fis?.close()
        }
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
