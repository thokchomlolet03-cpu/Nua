package com.example.nua.data.asr

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class FirebaseTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "FirebaseTranscriber"
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
    }

    private val generativeModel by lazy {
        try {
            Firebase.ai.generativeModel(DEFAULT_MODEL)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase AI Model: ${e.message}. Will use mock mode or fail.", e)
            null
        }
    }

    suspend fun transcribeWav(
        wavFile: File,
        mockMode: Boolean = false,
        onProgress: (Float) -> Unit
    ): List<TextSegment> = withContext(Dispatchers.IO) {
        if (mockMode || generativeModel == null) {
            Log.i(TAG, "Running FirebaseTranscriber in Mock Mode")
            onProgress(0.5f)
            // Generate some mock segments
            val mockSegments = listOf(
                TextSegment("Welcome to today's lecture on photosynthesis.", 0.0, 4.0),
                TextSegment("Photosynthesis is a chemical process used by plants.", 4.5, 9.0),
                TextSegment("It converts carbon dioxide and water into oxygen and glucose.", 9.5, 15.0),
                TextSegment("This reaction requires sunlight as an energy source.", 15.5, 21.0),
                TextSegment("Thank you for listening.", 21.5, 25.0)
            )
            onProgress(1.0f)
            return@withContext mockSegments
        }

        onProgress(0.1f)
        try {
            val segments = mutableListOf<TextSegment>()
            val chunkDurationSec = 180.0
            val sampleRate = 16000
            val bytesPerSec = sampleRate * 2 // 16-bit mono
            val chunkSize = (chunkDurationSec * bytesPerSec).toInt()

            val promptText = """
                You are a high-precision speech-to-text transcriber. 
                Transcribe the following English audio track.
                Segment the transcription into sentence-like chunks. 
                Each chunk should be no more than 14 words or 7 seconds long.
                Provide exact start and end timestamps in seconds for each chunk.
                Return the result strictly as a JSON array of objects. Do not include markdown code block formatting (like ```json).
                Each object in the array MUST have these keys:
                - "text": string (the transcribed text)
                - "start": number (start timestamp in seconds)
                - "end": number (end timestamp in seconds)
                
                Example output:
                [
                  {"text": "Hello world", "start": 0.0, "end": 2.5}
                ]
            """.trimIndent()

            wavFile.inputStream().use { stream ->
                stream.skip(44) // Skip WAV header
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                var chunkIndex = 0

                val totalLength = wavFile.length() - 44
                var processedLength = 0L

                while (stream.read(buffer).also { bytesRead = it } > 0) {
                    val actualBytes = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                    val model = generativeModel ?: throw Exception("Firebase AI model not initialized")
                    
                    val response = model.generateContent(
                        content {
                            inlineData(actualBytes, "audio/wav")
                            text(promptText)
                        }
                    )

                    val jsonText = response.text?.trim() ?: throw Exception("Empty response from Gemini")
                    val cleanJson = if (jsonText.startsWith("```")) {
                        jsonText.substringAfter("\n").substringBeforeLast("```").trim()
                    } else {
                        jsonText
                    }

                    val jsonArray = JSONArray(cleanJson)
                    val timeOffset = chunkIndex * chunkDurationSec
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val text = obj.getString("text")
                        val start = obj.getDouble("start") + timeOffset
                        val end = obj.getDouble("end") + timeOffset
                        segments.add(TextSegment(text, start, end))
                    }
                    
                    processedLength += bytesRead
                    onProgress(0.3f + 0.6f * (processedLength.toFloat() / totalLength.toFloat()))
                    chunkIndex++
                }
            }
            onProgress(1.0f)
            segments
        } catch (e: Exception) {
            Log.e(TAG, "Error during cloud transcription", e)
            throw e
        }
    }
}
