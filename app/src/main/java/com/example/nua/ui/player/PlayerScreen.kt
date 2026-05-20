package com.example.nua.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.nua.theme.DarkBackground
import com.example.nua.theme.PrimaryNeon
import com.example.nua.theme.SecondaryNeon
import com.example.nua.theme.SurfaceCard
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun PlayerScreen(
    videoPath: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val file = remember(videoPath) { File(videoPath) }

    val isPlaying by viewModel.isPlaying.collectAsState()
    val virtualTimeMs by viewModel.virtualTimeMs.collectAsState()
    val totalDurationMs by viewModel.totalDurationMs.collectAsState()
    val currentOriginalText by viewModel.currentOriginalText.collectAsState()
    val currentTranslatedText by viewModel.currentTranslatedText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // Initialize session inside LaunchedEffect
    LaunchedEffect(videoPath) {
        viewModel.initSession(videoPath)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.releasePlayers()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = file.name.removePrefix("session_").replace('_', ' '),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        containerColor = DarkBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(DarkBackground)
        ) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = SecondaryNeon)
                    }
                }
                errorMessage != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = errorMessage ?: "Unknown Error",
                            color = Color.Red,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp
                        )
                    }
                }
                else -> {
                    // Playback Area
                    Column(modifier = Modifier.fillMaxSize()) {
                        
                        // Video Player container
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color.Black)
                                .clickable { viewModel.togglePlayPause() }
                        ) {
                            viewModel.videoPlayer?.let { vp ->
                                AndroidView(
                                    factory = {
                                        PlayerView(context).apply {
                                            player = vp
                                            useController = false // We provide custom controls
                                            layoutParams = FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // Subtitles overlay cards
                            if (currentOriginalText.isNotEmpty() || currentTranslatedText.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 32.dp)
                                        .background(
                                            color = Color.Black.copy(alpha = 0.65f),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (currentOriginalText.isNotEmpty()) {
                                        Text(
                                            text = currentOriginalText,
                                            color = Color.White.copy(alpha = 0.75f),
                                            fontSize = 13.sp,
                                            fontStyle = FontStyle.Italic,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 18.sp
                                        )
                                    }
                                    if (currentTranslatedText.isNotEmpty()) {
                                        Text(
                                            text = currentTranslatedText,
                                            color = SecondaryNeon,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 22.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Custom seeker controls area
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                                    )
                                )
                                .padding(24.dp)
                        ) {
                            // Timeline Seekbar Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatTime(virtualTimeMs),
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(45.dp)
                                )

                                Slider(
                                    value = virtualTimeMs.toFloat(),
                                    onValueChange = { viewModel.seekTo(it.toLong()) },
                                    valueRange = 0f..(totalDurationMs.toFloat().coerceAtLeast(1f)),
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = SecondaryNeon,
                                        activeTrackColor = SecondaryNeon,
                                        inactiveTrackColor = Color.Gray.copy(alpha = 0.3f)
                                    )
                                )

                                Text(
                                    text = formatTime(totalDurationMs),
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(45.dp),
                                    textAlign = TextAlign.End
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Playback Toggle Row
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = { viewModel.togglePlayPause() },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(SecondaryNeon),
                                    colors = IconButtonDefaults.iconButtonColors(
                                        contentColor = Color.Black
                                    )
                                ) {
                                    // Custom visual feedback: change icon based on state
                                    if (isPlaying) {
                                        // Custom simple pause shape lines
                                        Box(
                                            modifier = Modifier.size(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Box(modifier = Modifier.size(width = 6.dp, height = 20.dp).background(Color.Black))
                                                Box(modifier = Modifier.size(width = 6.dp, height = 20.dp).background(Color.Black))
                                            }
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            modifier = Modifier.size(32.dp),
                                            tint = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%02d:%02d", mins, secs)
}
