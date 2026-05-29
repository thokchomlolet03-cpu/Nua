package org.nua.production.app.data.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt
import com.whispercpp.whisper.WhisperContext

data class TextSegment(
    val text: String,
    val startTimeSec: Double,
    val endTimeSec: Double
)

data class AudioChunk(
    val index: Int,
    val data: FloatArray,
    val startTimeSec: Double,
    val isLast: Boolean
)

class WhisperTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val MODEL_URL_BUDGET = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin"
        private const val MODEL_URL_PREMIUM = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q8_0.bin"
        
        private const val VAD_ENERGY_THRESHOLD = 0.005f 

        // Single Context for hardware efficiency
        private var activeContext: WhisperContext? = null
    }

    private fun isPremium(): Boolean {
        val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_tier", "UNKNOWN") == "PREMIUM"
    }

    val tinyModelFile: File get() = File(context.filesDir, "ggml-tiny.en-q8_0.bin")
    val baseModelFile: File get() = File(context.filesDir, "ggml-base.en-q8_0.bin")

    val transcriptionFlow: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow()

    fun isModelDownloaded(): Boolean {
        if (isPremium()) {
            return tinyModelFile.exists() && tinyModelFile.length() > 10_000_000 &&
                   baseModelFile.exists() && baseModelFile.length() > 10_000_000
        }
        return tinyModelFile.exists() && tinyModelFile.length() > 10_000_000
    }

    fun downloadModel(onProgress: (Float) -> Unit): Boolean {
        if (isModelDownloaded()) {
            onProgress(1f)
            return true
        }

        try {
            val modelsToExtract = if (isPremium()) listOf(tinyModelFile.name, baseModelFile.name) else listOf(tinyModelFile.name)
            for (fileName in modelsToExtract) {
                val destFile = File(context.filesDir, fileName)
                if (!destFile.exists() || destFile.length() < 10_000_000) {
                    val tempFile = File(context.cacheDir, "temp_$fileName")
                    try {
                        context.assets.open("models/$fileName").use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                val data = ByteArray(8192)
                                var bytesRead: Int
                                while (inputStream.read(data).also { bytesRead = it } != -1) {
                                    outputStream.write(data, 0, bytesRead)
                                }
                            }
                        }
                        tempFile.renameTo(destFile)
                    } catch (e: Exception) {
                        Log.e(TAG, "Bundled model $fileName not found in assets, skipping...", e)
                    }
                }
            }
            onProgress(1f)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting bundled whisper models", e)
            return false
        }
    }

    suspend fun initializeModels() = withContext(Dispatchers.IO) {
        if (activeContext != null) return@withContext
        if (!tinyModelFile.exists()) {
            Log.e(TAG, "Cannot initialize: Models not downloaded.")
            throw IllegalStateException("Whisper models are missing or not fully downloaded.")
        }
        try {
            activeContext = WhisperContext.createContextFromFile(context, tinyModelFile.absolutePath)
            Log.d(TAG, "Whisper model initialized with single context.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize whisper pool", e)
        }
    }
    
    private suspend fun upgradeToBaseModel() = withContext(Dispatchers.IO) {
        if (!baseModelFile.exists()) return@withContext
        Log.d(TAG, "Upgrading Whisper context to Base model for higher accuracy...")
        activeContext?.release()
        activeContext = WhisperContext.createContextFromFile(context, baseModelFile.absolutePath)
    }

    private fun hasSpeech(audioData: FloatArray): Boolean {
        if (audioData.isEmpty()) return false
        var sumSquares = 0f
        for (sample in audioData) {
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / audioData.size)
        return rms > VAD_ENERGY_THRESHOLD
    }

    suspend fun transcribeStream(audioChannel: ReceiveChannel<AudioChunk>): List<TextSegment> = coroutineScope {
        if (activeContext == null) {
            initializeModels()
        }
        
        val ctx = activeContext ?: throw IllegalStateException("Whisper context initialization failed.")
        
        val finalSegments = mutableListOf<TextSegment>()
        
        var isFirstChunk = true
        var prevTokensBuffer = IntArray(0)

        // Process sequentially on single thread to retain token history properly
        for (chunk in audioChannel) {
            if (!hasSpeech(chunk.data)) {
                Log.d(TAG, "VAD: Skipping silent chunk ${chunk.index}")
                continue
            }

            try {
                Log.d(TAG, "Transcribing chunk ${chunk.index} (${chunk.data.size} samples) with ${prevTokensBuffer.size} prev tokens...")
                
                val result = ctx.transcribeDataWithTokens(
                    data = chunk.data,
                    prevTokens = if (prevTokensBuffer.isNotEmpty()) prevTokensBuffer else null,
                    tokenCount = prevTokensBuffer.size,
                    printTimestamp = true
                )
                
                val outputString = result.first
                val newTokens = result.second
                
                // Sliding window of up to 224 past tokens (Transformer constraint)
                val combinedTokens = prevTokensBuffer + newTokens
                prevTokensBuffer = if (combinedTokens.size > 224) {
                    combinedTokens.sliceArray(combinedTokens.size - 224 until combinedTokens.size)
                } else {
                    combinedTokens
                }
                
                val segments = parseWhisperOutput(outputString, chunk.startTimeSec)
                
                if (isFirstChunk && isPremium()) {
                    isFirstChunk = false
                    val textLength = segments.sumOf { it.text.length }
                    val audioDuration = chunk.data.size / 16000.0
                    if (textLength < audioDuration * 2) {
                        upgradeToBaseModel()
                        prevTokensBuffer = IntArray(0) // clear context across models
                    }
                }

                finalSegments.addAll(segments)
            } catch (e: Exception) {
                Log.e(TAG, "Error transcribing chunk ${chunk.index}", e)
            }
        }

        finalSegments
    }

    private fun parseWhisperOutput(output: String, timeOffsetSec: Double): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        val regex = Regex("""\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})\.(\d{3})\]:\s*(.*)""")
        
        output.lines().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                val (h1, m1, s1, ms1, h2, m2, s2, ms2, text) = match.destructured
                val start = h1.toInt() * 3600 + m1.toInt() * 60 + s1.toInt() + ms1.toInt() / 1000.0
                val end = h2.toInt() * 3600 + m2.toInt() * 60 + s2.toInt() + ms2.toInt() / 1000.0
                
                segments.add(TextSegment(text.trim(), start + timeOffsetSec, end + timeOffsetSec))
            }
        }
        return segments
    }
}
