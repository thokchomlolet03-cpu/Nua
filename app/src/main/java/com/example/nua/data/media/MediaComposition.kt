package com.example.nua.data.media

import kotlinx.serialization.Serializable

@Serializable
data class MediaComposition(
    val videoId: String,
    val sourceVideoPath: String,
    val segments: List<PlaybackSegment>
)

@Serializable
data class PlaybackSegment(
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    val translatedText: String,
    val vocalAssetLocalPath: String,
    val directive: String // NORMAL_SYNC, PAD_EMPTY, FREEZE_HOLD
)

object PlaybackDirective {
    const val NORMAL_SYNC = "NORMAL_SYNC"
    const val PAD_EMPTY = "PAD_EMPTY"
    const val FREEZE_HOLD = "FREEZE_HOLD"
}
