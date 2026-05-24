package org.nua.production.app.data.rag

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import org.nua.production.app.data.schema.LectureSession
import org.nua.production.app.data.schema.GraphNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
                val config = EngineConfig(modelPath = modelPath)
                engine = Engine(config).also { it.initialize() }
                Log.d(TAG, "Tutor engine initialized")
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

        // Step 1: Hierarchical graph walk — find the most relevant context node
        val matchingNode = findBestMatchingNode(userPrompt, session)

        // Step 2: Build structured prompt with pre-baked factoid context
        val factoid = matchingNode?.summaryFactoid ?: "No specific context available."
        val keywords = if (matchingNode != null) {
            (0 until matchingNode.keywordsLength).mapNotNull { matchingNode.keywords(it) }
                .joinToString(", ")
        } else {
            "general"
        }

        val structuredPrompt = """
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

        // Step 3: Generate response via local LLM
        tutorMutex.withLock {
            val lmEngine = engine
            if (lmEngine == null) {
                return@withContext "Tutor engine is not initialized. Please load a model first."
            }
            try {
                val conversation = lmEngine.createConversation()
                val responseBuilder = StringBuilder()
                conversation.sendMessageAsync(structuredPrompt).collect { token ->
                    responseBuilder.append(token)
                }
                responseBuilder.toString().trim()
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
        
        // Compute Document Frequencies (DF)
        val dfMap = mutableMapOf<String, Int>()
        val totalDocs = session.knowledgeGraphLength
        for (i in 0 until totalDocs) {
            val node = session.knowledgeGraph(i) ?: continue
            val uniqueTokensInDoc = mutableSetOf<String>()
            for (k in 0 until node.keywordsLength) {
                val keyword = node.keywords(k)?.lowercase() ?: continue
                uniqueTokensInDoc.addAll(keyword.split("\\W+".toRegex()).filter { it.length > 3 })
            }
            uniqueTokensInDoc.forEach { token -> dfMap[token] = (dfMap[token] ?: 0) + 1 }
        }

        var bestNode: GraphNode? = null
        var bestScore = 0.0

        for (i in 0 until totalDocs) {
            val node = session.knowledgeGraph(i) ?: continue
            var tfIdfScore = 0.0
            
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

            for (token in userQueryTokensList) {
                val tf = (tfMap[token] ?: 0).toDouble() / totalTokensInDoc.coerceAtLeast(1)
                val df = dfMap[token] ?: 0
                val idf = kotlin.math.ln(totalDocs.toDouble() / (1 + df))
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
