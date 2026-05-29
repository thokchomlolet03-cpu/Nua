package org.nua.production.app.ui.player

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val calculatedPlaybackSpeed: Float = 1.0f,
    val activeQuizId: String? = null,
    val isQuizSubmitted: Boolean = false,
    val syncStatusMessage: String = "Engine Initialized"
)
