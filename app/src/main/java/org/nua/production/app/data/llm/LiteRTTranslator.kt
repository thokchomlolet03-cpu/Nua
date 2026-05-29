package org.nua.production.app.data.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


import java.io.File

/**
 * On-device LLM translator using LiteRT-LM (Google AI Edge).
 * Replaces the old MediaPipe GenAI GemmaTranslator with NPU-accelerated inference.
 *
 * Uses the official Engine + EngineConfig + Conversation API:
 * - Lazy model loading on background thread
 * - Streaming response via Kotlin Flow
 * - Sliding-window context for coherent multi-segment translation
 * - Duration-constrained output for sync with video timeline
 */
class LiteRTTranslator(private val context: Context) {

    companion object {
        private const val TAG = "LiteRTTranslator"
    }

    private val translationMutex = Mutex()

    private var engine: Engine? = null
    private var currentModelPath: String? = null

    /**
     * Lazily initializes the LiteRT-LM engine on a background coroutine.
     * Model loading can take several seconds on first launch.
     */
    suspend fun initModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        translationMutex.withLock {
            if (currentModelPath == modelPath && engine != null) {
                return@withContext true
            }

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file does not exist: $modelPath")
                return@withContext false
            }

            try {
                Log.d(TAG, "Initializing LiteRT-LM engine from $modelPath")
                try { engine?.close() } catch (_: Exception) {}
                engine = null
                currentModelPath = null

                val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
                val tier = prefs.getString("device_tier", "UNKNOWN")
                
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = if (tier == "PREMIUM") Backend.GPU() else Backend.CPU()
                )
                engine = Engine(config).also { it.initialize() }
                currentModelPath = modelPath
                Log.d(TAG, "LiteRT-LM engine loaded successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize LiteRT-LM engine", e)
                false
            }
        }
    }

    fun isModelLoaded(): Boolean = engine != null

    fun close() {
        // Must run synchronously since close() is called from Service.onDestroy().
        // Use runBlocking with a timeout to prevent indefinite hangs.
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5000L) {
                    translationMutex.withLock {
                        try { engine?.close() } catch (_: Exception) {}
                        engine = null
                        currentModelPath = null
                    }
                }
            }
        } catch (_: Exception) {
            // Timeout or interruption — force cleanup without lock
            try { engine?.close() } catch (_: Exception) {}
            engine = null
            currentModelPath = null
        }
    }

    /**
     * Translates English text to Hinglish using on-device LLM inference.
     * Supports both blocking (collect full response) and streaming modes.
     *
     * @param text The English source text to translate
     * @param durationSec Duration of the original audio segment (constrains output length)
     * @param previousTranslation Previous segment's translation for context continuity
     * @param mockMode If true, uses rule-based mock translation (no model required)
     * @return The translated Hinglish text
     */
    suspend fun translate(
        text: String,
        durationSec: Double,
        previousTranslation: String?,
        mockMode: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val maxWords = (durationSec * 3.2).toInt().coerceAtLeast(4)

        if (mockMode) {
            return@withContext limitWordCount(mockTranslateToHinglish(text), maxWords)
        }

        val lmEngine = engine
        if (lmEngine == null) {
            Log.e(TAG, "LiteRT-LM engine is not initialized")
            return@withContext "Error: Model not loaded"
        }

        val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
        val isPremium = prefs.getString("device_tier", "BUDGET") == "PREMIUM"
        val prompt = buildTranslationPrompt(text, maxWords, previousTranslation, isPremium)

        try {
            translationMutex.withLock {
                Log.d(TAG, "Sending prompt to LiteRT-LM: $text")
                val conversation = lmEngine.createConversation()
                val responseBuilder = StringBuilder()

                // Collect streaming response into a single string
                conversation.sendMessageAsync(prompt).collect { message ->
                    responseBuilder.append(message.toString())
                }

                val result = responseBuilder.toString()
                val cleaned = cleanResponse(result)
                val limited = limitWordCount(cleaned, maxWords)
                Log.d(TAG, "LiteRT-LM response: $limited")
                return@withContext limited
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in LiteRT-LM translation", e)
            "Error: Translation failed"
        }
    }

    /**
     * Streaming translation via Kotlin Flow. Each emitted value is a token chunk.
     * Useful for progressive UI updates during translation.
     */
    fun translateStreaming(
        text: String,
        durationSec: Double,
        previousTranslation: String?
    ): Flow<String> = channelFlow {
        val maxWords = (durationSec * 3.2).toInt().coerceAtLeast(4)
        val lmEngine = engine
        if (lmEngine == null) {
            send("Error: LiteRT-LM engine not initialized")
            return@channelFlow
        }
        val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
        val isPremium = prefs.getString("device_tier", "BUDGET") == "PREMIUM"
        val prompt = buildTranslationPrompt(text, maxWords, previousTranslation, isPremium)

        translationMutex.withLock {
            val conversation = lmEngine.createConversation()
            conversation.sendMessageAsync(prompt).collect { message ->
                send(message.toString())
            }
        }
    }

    // ─── Prompt engineering ─────────────────────────────────────────────

    private fun buildTranslationPrompt(text: String, maxWords: Int, previousTranslation: String?, isPremium: Boolean): String {
        val thinkingInstruction = if (isPremium) {
            "Use <thinking>...</thinking> tags to plan your translation before outputting the final result. "
        } else ""

        return if (previousTranslation.isNullOrEmpty()) {
            """
            You are a helpful assistant translating lecture audio to Hindi.
            $thinkingInstruction
            Translate the following English sentence to Hindi (using Devanagari script).
            Keep all scientific, medical, and technical terminology in English (do not translate terms like 'photosynthesis', 'cell', 'plants', 'biology', 'process', 'molecules', 'oxygen', 'mitochondria' to Hindi).
            Write the sentence structure, verbs, and explanations in Hindi.
            Keep the translation concise. Limit the output to maximum $maxWords words.
            Output ONLY the translation. Do not write any explanations or intros outside the thinking tags.
            
            Input: $text
            Output:
            """.trimIndent()
        } else {
            """
            You are a helpful assistant translating lecture audio to Hindi.
            Here is the translation of the previous sentence for context: "$previousTranslation".
            $thinkingInstruction
            Translate the following English sentence to Hindi (using Devanagari script), continuing the style and context naturally.
            Keep all scientific, medical, and technical terminology in English (do not translate terms like 'photosynthesis', 'cell', 'plants', 'biology', 'process', 'molecules', 'oxygen', 'mitochondria' to Hindi).
            Write the sentence structure, verbs, and explanations in Hindi.
            Keep the translation concise. Limit the output to maximum $maxWords words.
            Output ONLY the translation. Do not write any explanations or intros outside the thinking tags.
            
            Input: $text
            Output:
            """.trimIndent()
        }
    }

    // ─── Post-processing ────────────────────────────────────────────────

    private fun limitWordCount(text: String, maxWords: Int): String {
        val words = text.split(Regex("\\s+"))
        if (words.size <= maxWords) return text
        return words.take(maxWords).joinToString(" ") + "।"
    }

    private fun cleanResponse(response: String): String {
        var cleaned = response.trim()
        val prefixes = listOf("Output:", "Here is the translation:", "Translation:", "Hindi:", "Result:")
        for (prefix in prefixes) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                cleaned = cleaned.removeRange(0, prefix.length).trim()
            }
        }
        return cleaned.split("\n").firstOrNull { it.isNotBlank() }?.trim() ?: cleaned
    }

    // ─── Mock translator (testing without model) ────────────────────────

    private fun mockTranslateToHinglish(text: String): String {
        val lower = text.lowercase().trim()

        if (lower.contains("welcome") && lower.contains("lecture")) {
            return "Welcome, आज की biological lecture में आपका स्वागत है।"
        }
        if (lower.contains("photosynthesis") && lower.contains("process")) {
            return "Photosynthesis एक chemical process है जिसके द्वारा plants, energy बनाते हैं।"
        }
        if (lower.contains("cell") && lower.contains("basic unit")) {
            return "Cell, life की basic unit है और structural functions को perform करता है।"
        }
        if (lower.contains("today") && (lower.contains("talk about") || lower.contains("discuss"))) {
            val topic = text.substringAfter("about", "").substringAfter("discuss", "").trim()
                .removeSuffix(".")
            return "आज हम $topic के बारे में discuss करेंगे।"
        }
        if (lower.contains("study of")) {
            return "Biology, life और living organisms की study है।"
        }

        // Default: keep nouns in English, translate connecting structure to Hindi
        val words = text.split(" ")
        val importantNouns = setOf(
            "mitochondria", "nucleus", "dna", "rna", "organism", "chemical", "reaction", "protein",
            "cells", "energy", "sunlight", "chlorophyll", "plants", "carbon", "dioxide", "water",
            "professor", "university", "science", "lecture", "student", "class", "topic"
        )

        val builder = StringBuilder()
        for (w in words) {
            val punct = w.filter { it in setOf('.', ',', '!', '?', ';') }
            val cleanWord = w.lowercase().replace(Regex("[.,!?;]"), "")
            if (importantNouns.contains(cleanWord)) {
                builder.append(w).append(" ")
            } else {
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
                    else -> w
                }
                if (trans.isNotEmpty()) builder.append(trans).append(punct).append(" ")
            }
        }

        var result = builder.toString().trim()
        if (!result.endsWith("।") && !result.endsWith(".") && !result.endsWith("?")) {
            result += " है।"
        }
        return result
    }
}
