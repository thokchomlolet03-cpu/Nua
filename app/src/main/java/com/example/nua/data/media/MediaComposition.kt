package com.example.nua.data.media

import kotlinx.serialization.Serializable

/**
 * In-memory representation of a dubbed lecture session.
 * This is the intermediate format used during pipeline compilation.
 * For persistence, SessionManager serializes this to FlatBuffers .nuab binary.
 *
 * Legacy JSON manifests use this same structure for backward compatibility.
 */
@Serializable
data class MediaComposition(
    val videoId: String,
    val sourceVideoPath: String,
    val segments: List<PlaybackSegment>,
    val quizzes: List<QuizInfo> = emptyList(),
    val knowledgeGraph: List<GraphNodeInfo> = emptyList()
)

@Serializable
data class GraphNodeInfo(
    val nodeId: String,
    val keywords: List<String>,
    val summaryFactoid: String,
    val contextTokens: List<String>
)

@Serializable
data class PlaybackSegment(
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String,
    val vocalAssetLocalPath: String,
    val directive: String = PlaybackDirective.NORMAL_SYNC,
    // New fields for FlatBuffers schema compatibility
    val segmentId: String? = null,
    val audioDurationMs: Long? = null,
    val hotspots: List<HotspotInfo> = emptyList()
)

@Serializable
data class HotspotInfo(
    val token: String,
    val conceptDefinition: String
)

@Serializable
data class QuizInfo(
    val triggerTimestampMs: Long,
    val question: String,
    val options: List<String>,
    val correctIndex: Int
)

/**
 * Playback directive constants. Kept as string constants (not enum)
 * for serialization compatibility with both JSON and FlatBuffers.
 */
object PlaybackDirective {
    const val NORMAL_SYNC = "NORMAL_SYNC"
    const val PAD_EMPTY = "PAD_EMPTY"
    const val FREEZE_HOLD = "FREEZE_HOLD"
}
