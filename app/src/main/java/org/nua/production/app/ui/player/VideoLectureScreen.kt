package org.nua.production.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView

@Composable
fun VideoLectureScreen(
    viewModel: VideoLectureViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    // High-contrast premium dark aesthetic wrapper
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111111)) 
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // 1. Core ExoPlayer Native Surface Binding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            // Link native media controllers to our hardened engine delegates
                            player = viewModel.syncPlayerEngine.videoPlayer
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Continuous timeline tracker loop hook
                LaunchedEffect(state.isPlaying) {
                    while (state.isPlaying) {
                        val playerInstance = viewModel.syncPlayerEngine.videoPlayer
                        viewModel.onPlaybackTick(
                            positionMs = playerInstance.currentPosition,
                            durationMs = playerInstance.duration.coerceAtLeast(1L),
                            audioTrackDurationMs = 45000L // Simulated translated audio baseline
                        )
                        kotlinx.coroutines.delay(250) // High-fidelity 250ms interval sampling
                    }
                }
            }

            // 2. Dynamic Real-time Status Dashboard Console
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .weight(0.4f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Ecosystem Status Console",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF00E5FF) // Electric neon cyan accent
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Timeline Anchor: ${state.currentPositionMs} ms", color = Color.White)
                    Text(text = "Dynamic Render Target: ${state.calculatedPlaybackSpeed}x", color = Color.White)
                    Text(text = "Log Trace: ${state.syncStatusMessage}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 3. Conditional Quiz Intervention Overlay
        state.activeQuizId?.let { quizId ->
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        text = "Interactive Checkpoint: $quizId",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { viewModel.submitQuizAnswer(answerIndex = 1) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Text("Option A: Commit Verified Choice", color = Color.Black)
                    }
                }
            }
        }
    }
}
