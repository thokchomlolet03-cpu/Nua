package org.nua.production.app.data.llm

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import org.json.JSONObject

/**
 * Extracts and processes structured JSON tool calls emitted by the Gemma 4 E2B engine.
 * Allows the LLM to trigger native Android automations (alarms, bookmarks, etc.)
 */
object ToolCallParser {

    private const val TAG = "ToolCallParser"

    data class ToolExecutionResult(
        val hasToolCall: Boolean,
        val cleanResponse: String,
        val toolResponseBlock: String? = null
    )

    // A simple mock offline dictionary
    private val offlineDictionary = mapOf(
        "mitochondria" to "Mitochondria are membrane-bound cell organelles that generate most of the chemical energy needed to power the cell's biochemical reactions.",
        "photosynthesis" to "Photosynthesis is the process by which green plants and some other organisms use sunlight to synthesize foods from carbon dioxide and water.",
        "cell" to "The smallest structural and functional unit of an organism."
    )

    /**
     * Parses the LLM's response for `<tool_call>` blocks.
     * Executes the native action and returns a result containing the `<tool_response>`
     * to be fed back into the LLM.
     */
    fun processToolCalls(context: Context, rawResponse: String): ToolExecutionResult {
        var cleanResponse = rawResponse
        var combinedToolResponses = StringBuilder()
        var hasToolCall = false

        // Match <tool_call> {"name": "func", "arguments": {...}} </tool_call>
        val toolCallRegex = "<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = toolCallRegex.findAll(rawResponse)

        for (match in matches) {
            hasToolCall = true
            val jsonString = match.groupValues[1]
            var toolOutput = ""
            var functionName = "unknown"

            try {
                val json = JSONObject(jsonString)
                functionName = json.optString("name")
                val arguments = json.optJSONObject("arguments") ?: JSONObject()

                Log.d(TAG, "Tool call detected: $functionName")
                when (functionName) {
                    "set_alarm" -> {
                        val time = arguments.optString("time", "08:00")
                        val parts = time.split(":")
                        if (parts.size == 2) {
                            val hour = parts[0].toIntOrNull() ?: 8
                            val minute = parts[1].toIntOrNull() ?: 0
                            
                            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                                putExtra(AlarmClock.EXTRA_HOUR, hour)
                                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                                putExtra(AlarmClock.EXTRA_MESSAGE, "Nua Lecture Reminder")
                                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            toolOutput = "{\"status\": \"success\", \"message\": \"Alarm set for $time\"}"
                        } else {
                            toolOutput = "{\"status\": \"error\", \"message\": \"Invalid time format\"}"
                        }
                    }
                    "create_bookmark" -> {
                        val timeMs = arguments.optLong("time_ms", 0L)
                        val note = arguments.optString("note", "Bookmark")
                        
                        val intent = Intent("org.nua.production.app.ACTION_BOOKMARK").apply {
                            putExtra("time_ms", timeMs)
                            putExtra("note", note)
                        }
                        context.sendBroadcast(intent)
                        toolOutput = "{\"status\": \"success\", \"message\": \"Bookmark saved at $timeMs ms\"}"
                    }
                    "seek_to_topic" -> {
                        val timeMs = arguments.optLong("time_ms", 0L)
                        val intent = Intent("org.nua.production.app.ACTION_SEEK_TO").apply {
                            putExtra("time_ms", timeMs)
                        }
                        context.sendBroadcast(intent)
                        toolOutput = "{\"status\": \"success\", \"message\": \"Playhead moved to $timeMs ms\"}"
                    }
                    "trigger_quiz" -> {
                        val intent = Intent("org.nua.production.app.ACTION_TRIGGER_QUIZ")
                        context.sendBroadcast(intent)
                        toolOutput = "{\"status\": \"success\", \"message\": \"Quiz triggered\"}"
                    }
                    "lookup_term" -> {
                        val term = arguments.optString("term", "").lowercase()
                        val definition = offlineDictionary[term]
                        toolOutput = if (definition != null) {
                            "{\"definition\": \"$definition\"}"
                        } else {
                            "{\"error\": \"Term not found in dictionary\"}"
                        }
                    }
                    else -> {
                        toolOutput = "{\"error\": \"Unknown tool: $functionName\"}"
                    }
                }

                // Strip the tool call block from the user-facing output
                cleanResponse = cleanResponse.replace(match.value, "").trim()

                // Append to responses
                combinedToolResponses.append("<tool_response>\n")
                combinedToolResponses.append("{\"name\": \"$functionName\", \"content\": $toolOutput}\n")
                combinedToolResponses.append("</tool_response>\n")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse tool call: $jsonString", e)
                combinedToolResponses.append("<tool_response>\n")
                combinedToolResponses.append("{\"name\": \"$functionName\", \"content\": {\"error\": \"Execution failed\"}}\n")
                combinedToolResponses.append("</tool_response>\n")
            }
        }

        return ToolExecutionResult(
            hasToolCall = hasToolCall,
            cleanResponse = cleanResponse,
            toolResponseBlock = if (hasToolCall) combinedToolResponses.toString() else null
        )
    }
}
