package com.example.nua.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val virtualTimeMs by viewModel.virtualTimeMs.collectAsStateWithLifecycle()
    val totalDurationMs by viewModel.totalDurationMs.collectAsStateWithLifecycle()
    val currentOriginalText by viewModel.currentOriginalText.collectAsStateWithLifecycle()
    val currentTranslatedText by viewModel.currentTranslatedText.collectAsStateWithLifecycle()
    val hotspots by viewModel.currentHotspots.collectAsStateWithLifecycle()
    val activeQuiz by viewModel.activeQuiz.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var selectedHotspotToken by remember { mutableStateOf<String?>(null) }
    var selectedHotspotDefinition by remember { mutableStateOf<String?>(null) }

    // Initialize session inside LaunchedEffect
    LaunchedEffect(videoPath) {
        viewModel.initSession(videoPath)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.releasePlayers()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
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
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
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
                                            // Highlight and bind click events to words in the Hindi subtitle
                                            val annotatedText = remember(currentTranslatedText, hotspots) {
                                                buildAnnotatedString {
                                                    val text = currentTranslatedText
                                                    append(text)

                                                    for (hotspot in hotspots) {
                                                        val token = hotspot.token
                                                        if (token.isEmpty()) continue

                                                        var startIndex = text.indexOf(token)
                                                        while (startIndex >= 0) {
                                                            val endIndex = startIndex + token.length
                                                            addStyle(
                                                                style = SpanStyle(
                                                                    color = PrimaryNeon,
                                                                    textDecoration = TextDecoration.Underline,
                                                                    fontWeight = FontWeight.ExtraBold
                                                                ),
                                                                start = startIndex,
                                                                end = endIndex
                                                            )
                                                            addStringAnnotation(
                                                                tag = "HOTSPOT",
                                                                annotation = hotspot.conceptDefinition,
                                                                start = startIndex,
                                                                end = endIndex
                                                            )
                                                            startIndex = text.indexOf(token, endIndex)
                                                        }
                                                    }
                                                }
                                            }

                                            ClickableText(
                                                text = annotatedText,
                                                style = LocalTextStyle.current.copy(
                                                    color = SecondaryNeon,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    lineHeight = 22.sp
                                                ),
                                                onClick = { offset ->
                                                    annotatedText.getStringAnnotations(tag = "HOTSPOT", start = offset, end = offset)
                                                        .firstOrNull()?.let { annotation ->
                                                            selectedHotspotDefinition = annotation.item
                                                            selectedHotspotToken = annotatedText.substring(annotation.start, annotation.end)
                                                        }
                                                }
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
                                        if (isPlaying) {
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

        // Hotspot Explanation Dialog
        if (selectedHotspotDefinition != null && selectedHotspotToken != null) {
            AlertDialog(
                onDismissRequest = {
                    selectedHotspotDefinition = null
                    selectedHotspotToken = null
                },
                title = {
                    Text(
                        text = "Concept: $selectedHotspotToken",
                        color = PrimaryNeon,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = selectedHotspotDefinition!!,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            selectedHotspotDefinition = null
                            selectedHotspotToken = null
                        }
                    ) {
                        Text("Got it", color = SecondaryNeon, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = SurfaceCard,
                shape = RoundedCornerShape(16.dp)
            )
        }

        // Quiz Overlay Panel (forces answer before resuming)
        activeQuiz?.let { quiz ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(enabled = false) {}, // Absorb all back clicks
                contentAlignment = Alignment.Center
            ) {
                var selectedOption by remember { mutableStateOf<Int?>(null) }
                var submitted by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "INTERACTIVE QUIZ",
                            color = PrimaryNeon,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            letterSpacing = 1.5.sp
                        )

                        Text(
                            text = quiz.question,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        quiz.options.forEachIndexed { index, option ->
                            val isSelected = selectedOption == index
                            val isCorrectAnswer = index == quiz.correctIndex

                            val buttonColor = when {
                                submitted && isCorrectAnswer -> Color.Green.copy(alpha = 0.2f)
                                submitted && isSelected && !isCorrectAnswer -> Color.Red.copy(alpha = 0.2f)
                                isSelected -> SecondaryNeon.copy(alpha = 0.15f)
                                else -> Color.Gray.copy(alpha = 0.1f)
                            }

                            val borderColor = when {
                                submitted && isCorrectAnswer -> Color.Green
                                submitted && isSelected && !isCorrectAnswer -> Color.Red
                                isSelected -> SecondaryNeon
                                else -> Color.Gray.copy(alpha = 0.3f)
                            }

                            val textColor = when {
                                submitted && isCorrectAnswer -> Color.Green
                                submitted && isSelected && !isCorrectAnswer -> Color.Red
                                isSelected -> SecondaryNeon
                                else -> Color.White
                            }

                            Surface(
                                onClick = { if (!submitted) selectedOption = index },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = buttonColor,
                                border = BorderStroke(1.dp, borderColor)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = option,
                                        color = textColor,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (!submitted) {
                            Button(
                                onClick = { submitted = true },
                                enabled = selectedOption != null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SecondaryNeon,
                                    contentColor = Color.Black,
                                    disabledContainerColor = Color.Gray.copy(alpha = 0.3f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Submit Answer",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    viewModel.submitQuizAnswer()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryNeon,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "Continue Playback",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
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
