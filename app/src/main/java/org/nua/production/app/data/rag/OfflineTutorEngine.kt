package org.nua.production.app.data.rag

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Backend
import org.nua.production.app.data.schema.LectureSession
import org.nua.production.app.data.schema.GraphNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * Offline RAG (Retrieval-Augmented Generation) engine that walks pre-baked
 * knowledge graphs stored in the FlatBuffers session bundle.
 *
 * Uses LiteRT-LM for local NPU-accelerated inference.
 * All knowledge is pre-computed at compile time — zero ongoing API costs.
 *
 * Algorithm:
 * 1. Parse user query keywords
 * 2. Walk the knowledge graph to find the closest matching GraphNode
 * 3. Construct a structured prompt with the node's factoid context + playhead position
 * 4. Generate a tutoring response via LiteRT-LM
 */
class OfflineTutorEngine(private val context: Context) {

    companion object {
        private const val TAG = "OfflineTutorEngine"
    }

    private var engine: Engine? = null
    private val tutorMutex = Mutex()

    /**
     * Initializes the LiteRT-LM engine for tutoring queries.
     * Can share the same model as the translator, or use a specialized tutor model.
     */
    suspend fun initializeEngine(modelPath: String) = withContext(Dispatchers.IO) {
        tutorMutex.withLock {
            try {
                try { engine?.close() } catch (_: Exception) {}
                val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
                val tier = prefs.getString("device_tier", "UNKNOWN")
                
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = if (tier == "PREMIUM") Backend.GPU() else Backend.CPU()
                )
                engine = Engine(config).also { it.initialize() }
                Log.d(TAG, "Tutor engine initialized with backend: ${if (tier == "PREMIUM") "GPU" else "CPU"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize tutor engine", e)
            }
        }
    }

    /**
     * Queries the pre-baked knowledge graph with a user question.
     *
     * @param userPrompt The student's question
     * @param session The loaded FlatBuffers session containing the knowledge graph
     * @param playheadTimeMs Current video playhead position (provides temporal context)
     * @return A generated tutoring response grounded in the knowledge graph
     */
    suspend fun executeGraphQuery(
        userPrompt: String,
        session: LectureSession,
        playheadTimeMs: Long
    ): String = withContext(Dispatchers.IO) {

        val prefs = context.getSharedPreferences("nua_prefs", Context.MODE_PRIVATE)
        val isPremium = prefs.getString("device_tier", "BUDGET") == "PREMIUM"

        val structuredPrompt = if (isPremium) {
            // STEP 1 (Premium): Bypass Graph Search and use Extended Context
            val fullTranscriptBuilder = java.lang.StringBuilder()
            for (i in 0 until session.timelineTracksLength) {
                val segment = session.timelineTracks(i)
                if (segment?.originalText != null) {
                    fullTranscriptBuilder.append(segment.originalText).append(" ")
                }
            }
            
            """
            You are a helpful tutor assistant for a video lecture.
            You have a 128K context window. Here is the full continuous transcript of the video:
            $fullTranscriptBuilder
            
            The student is currently watching at timestamp ${playheadTimeMs}ms.
            
            Answer the student's question concisely, logically, and accurately based on the entire transcript.
            Keep scientific terms in English, but explain in Hinglish.
            Use <thinking>...</thinking> tags to reason before you output your final answer.
            
            <tools>
            [
              {
                "name": "set_alarm",
                "description": "Set a study reminder alarm.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "time": { "type": "string", "description": "Time in HH:MM format" }
                  },
                  "required": ["time"]
                }
              },
              {
                "name": "create_bookmark",
                "description": "Bookmark a specific timestamp in the video with a note.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "time_ms": { "type": "integer", "description": "Timestamp in milliseconds" },
                    "note": { "type": "string", "description": "Note for the bookmark" }
                  },
                  "required": ["time_ms", "note"]
                }
              },
              {
                "name": "seek_to_topic",
                "description": "Move the video player to a specific timestamp.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "time_ms": { "type": "integer", "description": "Timestamp in milliseconds" }
                  },
                  "required": ["time_ms"]
                }
              },
              {
                "name": "trigger_quiz",
                "description": "Trigger a pop quiz for the student.",
                "parameters": {
                  "type": "object",
                  "properties": {},
                  "required": []
                }
              },
              {
                "name": "lookup_term",
                "description": "Look up a scientific term in the offline dictionary.",
                "parameters": {
                  "type": "object",
                  "properties": {
                    "term": { "type": "string", "description": "The term to lookup" }
                  },
                  "required": ["term"]
                }
              }
            ]
            </tools>
            
            Student's question: $userPrompt
            """.trimIndent()
        } else {
            // Step 1 (Budget): Hierarchical graph walk — find the most relevant context node
            val matchingNode = findBestMatchingNode(userPrompt, session)

            // Step 2: Build structured prompt with pre-baked factoid context
            val factoid = matchingNode?.summaryFactoid ?: "No specific context available."
            val keywords = if (matchingNode != null) {
                (0 until matchingNode.keywordsLength).mapNotNull { matchingNode.keywords(it) }
                    .joinToString(", ")
            } else {
                "general"
            }

            """
            You are a helpful tutor assistant for a video lecture.
            The student is currently watching at timestamp ${playheadTimeMs}ms.
            
            Relevant context from the lecture knowledge graph:
            - Topic keywords: $keywords
            - Key fact: $factoid
            
            Answer the student's question concisely and helpfully.
            If the question relates to the lecture content, reference the context above.
            Keep scientific terms in English.
            
            Student's question: $userPrompt
            """.trimIndent()
        }

        // Step 3: Generate response via local LLM with tool execution loop
        tutorMutex.withLock {
            val lmEngine = engine
            if (lmEngine == null) {
                return@withContext "Tutor engine is not initialized. Please load a model first."
            }
            try {
                val conversation = lmEngine.createConversation()
                var currentPrompt = structuredPrompt
                var finalAnswer = ""
                var iterations = 0

                while (iterations < 3) {
                    iterations++
                    val responseBuilder = StringBuilder()
                    conversation.sendMessageAsync(currentPrompt).collect { token ->
                        responseBuilder.append(token)
                    }
                    val rawResponse = responseBuilder.toString().trim()

                    // Parse for <tool_call>
                    val toolResult = org.nua.production.app.data.llm.ToolCallParser.processToolCalls(context, rawResponse)
                    
                    if (toolResult.hasToolCall) {
                        // We have tool responses to feed back
                        Log.d(TAG, "Tool call executed, feeding back response: ${toolResult.toolResponseBlock}")
                        // Provide the tool response block back to the conversation
                        currentPrompt = toolResult.toolResponseBlock ?: ""
                    } else {
                        // No tool call means this is the final text answer
                        finalAnswer = toolResult.cleanResponse
                        break
                    }
                }

                if (finalAnswer.isBlank()) {
                    finalAnswer = "Sorry, I took too many steps to think and couldn't answer."
                }
                finalAnswer
            } catch (e: Exception) {
                Log.e(TAG, "Tutor query failed", e)
                "Sorry, I couldn't process your question. Please try again."
            }
        }
    }

    /**
     * Walks the knowledge graph to find the GraphNode whose keywords
     * best match the user's prompt. Uses keyword overlap scoring.
     */
    private fun findBestMatchingNode(prompt: String, session: LectureSession): GraphNode? {
        val userQueryTokensList = prompt.lowercase().split("\\W+".toRegex()).filter { it.length > 3 }
        val totalDocs = session.knowledgeGraphLength

        var bestNode: GraphNode? = null
        var bestScore = 0.0

        for (i in 0 until totalDocs) {
            val node = session.knowledgeGraph(i) ?: continue
            var tfIdfScore = 0.0
            
            // Extract pre-baked IDF map
            val idfMap = mutableMapOf<String, Float>()
            for (j in 0 until node.idfTokensLength) {
                val t = node.idfTokens(j)
                if (t != null) {
                    idfMap[t] = node.idfValues(j)
                }
            }

            // Compute Term Frequencies (TF) for this document
            val tfMap = mutableMapOf<String, Int>()
            var totalTokensInDoc = 0
            for (k in 0 until node.keywordsLength) {
                val keyword = node.keywords(k)?.lowercase() ?: continue
                val tokens = keyword.split("\\W+".toRegex()).filter { it.length > 3 }
                tokens.forEach { token -> 
                    tfMap[token] = (tfMap[token] ?: 0) + 1 
                    totalTokensInDoc++
                }
            }

            val factoid = node.summaryFactoid?.lowercase() ?: ""
            factoid.split("\\W+".toRegex()).filter { it.length > 3 }.forEach { token ->
                tfMap[token] = (tfMap[token] ?: 0) + 1
                totalTokensInDoc++
            }

            for (token in userQueryTokensList) {
                val tf = (tfMap[token] ?: 0).toDouble() / totalTokensInDoc.coerceAtLeast(1)
                val idf = idfMap[token]?.toDouble() ?: 0.0
                tfIdfScore += tf * idf
            }

            if (tfIdfScore > bestScore) {
                bestScore = tfIdfScore
                bestNode = node
            }
        }
        return bestNode
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            tutorMutex.withLock {
                try { engine?.close() } catch (_: Exception) {}
                engine = null
            }
        }
    }
}
