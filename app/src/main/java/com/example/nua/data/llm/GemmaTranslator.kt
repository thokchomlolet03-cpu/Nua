package com.example.nua.data.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

class GemmaTranslator(private val context: Context) {

    companion object {
        private const val TAG = "GemmaTranslator"
    }

    private var llmInference: LlmInference? = null
    private var currentModelPath: String? = null

    /**
     * Initializes the Gemma LlmInference with the model file at [modelPath].
     * Runs on a background thread.
     */
    fun initModel(modelPath: String): Boolean {
        if (currentModelPath == modelPath && llmInference != null) {
            return true
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file does not exist: $modelPath")
            return false
        }

        try {
            Log.d(TAG, "Initializing Gemma model from $modelPath")
            close()

            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(128)
                .setPreferredBackend(LlmInference.Backend.GPU) // Try GPU first
                .build()

            llmInference = LlmInference.createFromOptions(context, options)
            currentModelPath = modelPath
            Log.d(TAG, "Gemma model loaded successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Gemma model with GPU backend, falling back to CPU", e)
            try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(128)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                currentModelPath = modelPath
                Log.d(TAG, "Gemma model loaded successfully with CPU backend")
                return true
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to load Gemma model on CPU as well", ex)
                return false
            }
        }
    }

    fun isModelLoaded(): Boolean {
        return llmInference != null
    }

    fun close() {
        try {
            llmInference?.close()
        } catch (e: Exception) {
            // Ignore
        }
        llmInference = null
        currentModelPath = null
    }

    /**
     * Translates English text to hybrid Hinglish using Gemma on-device.
     * If [mockMode] is true, uses a local rule-based pseudo-translator.
     */
    fun translate(text: String, mockMode: Boolean = false): String {
        if (mockMode) {
            return mockTranslateToHinglish(text)
        }

        val inference = llmInference
        if (inference == null) {
            Log.e(TAG, "Gemma model is not initialized")
            return "Error: Model not loaded"
        }

        // Prompt designed for hybrid code-mixed translation (English terms + Hindi explanations)
        val prompt = """
            You are a helpful assistant translating lecture audio to Hindi.
            Translate the following English sentence to Hindi (using Devanagari script).
            Keep all scientific, medical, and technical terminology in English (do not translate terms like 'photosynthesis', 'cell', 'plants', 'biology', 'process', 'molecules', 'oxygen', 'mitochondria' to Hindi).
            Write the sentence structure, verbs, and explanations in Hindi.
            Output ONLY the translation. Do not write any explanations or intros.
            
            Input: $text
            Output:
        """.trimIndent()

        return try {
            Log.d(TAG, "Sending prompt to Gemma: $text")
            val result = inference.generateResponse(prompt)
            val cleaned = cleanResponse(result)
            Log.d(TAG, "Gemma response: $cleaned")
            cleaned
        } catch (e: Exception) {
            Log.e(TAG, "Error in Gemma translation", e)
            "Error: Translation failed"
        }
    }

    private fun cleanResponse(response: String): String {
        return response.trim()
            .removePrefix("Output:")
            .removePrefix("Here is the translation:")
            .trim()
            .split("\n").firstOrNull { it.isNotEmpty() } ?: response
    }

    /**
     * Synthesizes code-mixed Hindi (Hinglish) using basic rules for validation in Mock Mode.
     */
    private fun mockTranslateToHinglish(text: String): String {
        val lower = text.lowercase().trim()
        
        // Let's create realistic Hinglish outputs for standard lecture speech patterns
        if (lower.contains("welcome") && lower.contains("lecture")) {
            return "Welcome, आज की biological lecture में आपका स्वागत है।"
        }
        if (lower.contains("photosynthesis") && lower.contains("process")) {
            return "Photosynthesis एक chemical process है जिसके द्वारा plants, energy बनाते हैं।"
        }
        if (lower.contains("cell") && lower.contains("basic unit")) {
            return "Cell, life की basic unit है और structural functions को perform करता है।"
        }
        if (lower.contains("today") && lower.contains("talk about") || lower.contains("discuss")) {
            val topic = text.substringAfter("about", "").substringAfter("discuss", "").trim()
                .removeSuffix(".")
            return "आज हम $topic के बारे में discuss करेंगे।"
        }
        if (lower.contains("study of")) {
            val topic = text.substringAfter("of", "").trim().removeSuffix(".")
            return "Biology, life और living organisms की study है।"
        }

        // Default: Keep nouns in English and translate the connecting structure to Hindi
        val words = text.split(" ")
        val importantNouns = setOf(
            "mitochondria", "nucleus", "dna", "rna", "organism", "chemical", "reaction", "protein",
            "cells", "energy", "sunlight", "chlorophyll", "plants", "carbon", "dioxide", "water",
            "professor", "university", "science", "lecture", "student", "class", "topic"
        )
        
        val builder = java.lang.StringBuilder()
        for (w in words) {
            val cleanWord = w.lowercase().replace(Regex("[.,!?;]"), "")
            if (importantNouns.contains(cleanWord)) {
                builder.append(w).append(" ")
            } else {
                // Approximate translation mapping of common connecting words
                val trans = when (cleanWord) {
                    "is" -> "है"
                    "are" -> "हैं"
                    "the" -> ""
                    "a", "an" -> "एक"
                    "and" -> "और"
                    "of" -> "की"
                    "in" -> "में"
                    "to" -> "को"
                    "for" -> "के लिए"
                    "we" -> "हम"
                    "they" -> "वे"
                    "this" -> "यह"
                    "that" -> "वह"
                    "have" -> "के पास"
                    "with" -> "के साथ"
                    "from" -> "से"
                    "by" -> "के द्वारा"
                    "can" -> "सकता है"
                    "do" -> "करते"
                    "produce" -> "produce करते हैं"
                    "use" -> "use करते हैं"
                    "need" -> "need होती है"
                    else -> w // keep it
                }
                if (trans.isNotEmpty()) {
                    builder.append(trans).append(" ")
                }
            }
        }
        
        var result = builder.toString().trim()
        if (!result.endsWith("।") && !result.endsWith(".") && !result.endsWith("?")) {
            result += " है।"
        }
        return result
    }
}
