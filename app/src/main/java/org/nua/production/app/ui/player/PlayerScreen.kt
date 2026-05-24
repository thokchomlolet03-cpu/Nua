package org.nua.production.app.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
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
import org.nua.production.app.theme.DarkBackground
import org.nua.production.app.theme.PrimaryNeon
import org.nua.production.app.theme.SecondaryNeon
import org.nua.production.app.theme.SurfaceCard
import org.nua.production.app.data.media.HotspotInfo
import org.nua.production.app.data.media.QuizInfo
import java.io.File

// Custom self-contained Chat Icon vector to avoid material-icons-extended dependency
val ChatIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Chat",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = SolidColor(Color.White)
    ) {
        moveTo(20f, 2f)
        lineTo(4f, 2f)
        curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
        verticalLineTo(22f)
        lineTo(6f, 18f)
        horizontalLineTo(20f)
        curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
        verticalLineTo(4f)
        curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
        close()
    }.build()

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

    // Tutor state integrations
    val isTutorActive by viewModel.isTutorActive.collectAsStateWithLifecycle()
    val tutorMessages by viewModel.tutorMessages.collectAsStateWithLifecycle()
    val isTutorTyping by viewModel.isTutorTyping.collectAsStateWithLifecycle()

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
                    actions = {
                        IconButton(onClick = { viewModel.toggleTutor(!isTutorActive) }) {
                            Icon(
                                imageVector = ChatIcon,
                                contentDescription = "AI Tutor",
                                tint = if (isTutorActive) SecondaryNeon else Color.White
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
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val isLandscape = maxWidth > maxHeight

                            if (isTutorActive) {
                                if (isLandscape) {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        // Video Area (Left side)
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        ) {
                                            VideoPlayerAndSubtitles(
                                                modifier = Modifier.weight(1f),
                                                viewModel = viewModel,
                                                context = context,
                                                currentOriginalText = currentOriginalText,
                                                currentTranslatedText = currentTranslatedText,
                                                hotspots = hotspots,
                                                onHotspotClick = { concept, def ->
                                                    selectedHotspotToken = concept
                                                    selectedHotspotDefinition = def
                                                }
                                            )
                                            VideoControls(
                                                viewModel = viewModel,
                                                virtualTimeMs = virtualTimeMs,
                                                totalDurationMs = totalDurationMs,
                                                isPlaying = isPlaying
                                            )
                                        }

                                        VerticalDivider(color = Color.Gray.copy(alpha = 0.3f))

                                        // Tutor Panel (Right side)
                                        TutorChatPanel(
                                            modifier = Modifier
                                                .width(360.dp)
                                                .fillMaxHeight(),
                                            messages = tutorMessages,
                                            isTyping = isTutorTyping,
                                            onSendMessage = { viewModel.askTutor(it) }
                                        )
                                    }
                                } else {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Video Area (Top portion)
                                        VideoPlayerAndSubtitles(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f),
                                            viewModel = viewModel,
                                            context = context,
                                            currentOriginalText = currentOriginalText,
                                            currentTranslatedText = currentTranslatedText,
                                            hotspots = hotspots,
                                            onHotspotClick = { concept, def ->
                                                selectedHotspotToken = concept
                                                selectedHotspotDefinition = def
                                            }
                                        )

                                        VideoControls(
                                            viewModel = viewModel,
                                            virtualTimeMs = virtualTimeMs,
                                            totalDurationMs = totalDurationMs,
                                            isPlaying = isPlaying
                                        )

                                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

                                        // Tutor Panel (Bottom portion)
                                        TutorChatPanel(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth(),
                                            messages = tutorMessages,
                                            isTyping = isTutorTyping,
                                            onSendMessage = { viewModel.askTutor(it) }
                                        )
                                    }
                                }
                            } else {
                                // Default non-tutor layout
                                Column(modifier = Modifier.fillMaxSize()) {
                                    VideoPlayerAndSubtitles(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        viewModel = viewModel,
                                        context = context,
                                        currentOriginalText = currentOriginalText,
                                        currentTranslatedText = currentTranslatedText,
                                        hotspots = hotspots,
                                        onHotspotClick = { concept, def ->
                                            selectedHotspotToken = concept
                                            selectedHotspotDefinition = def
                                        }
                                    )
                                    VideoControls(
                                        viewModel = viewModel,
                                        virtualTimeMs = virtualTimeMs,
                                        totalDurationMs = totalDurationMs,
                                        isPlaying = isPlaying
                                    )
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
                val quizShownTime = remember(quiz) { System.currentTimeMillis() }
                var latencyMs by remember { mutableStateOf(0L) }

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
                                onClick = {
                                    submitted = true
                                    latencyMs = System.currentTimeMillis() - quizShownTime
                                },
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
                                    viewModel.submitQuizAnswer(
                                        quiz = quiz,
                                        selectedIndex = selectedOption ?: -1,
                                        latencyMs = latencyMs
                                    )
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

@Composable
fun VideoPlayerAndSubtitles(
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel,
    context: android.content.Context,
    currentOriginalText: String,
    currentTranslatedText: String,
    hotspots: List<HotspotInfo>,
    onHotspotClick: (String, String) -> Unit
) {
    Box(
        modifier = modifier
            .background(Color.Black)
    ) {
        viewModel.videoPlayer?.let { vp ->
            AndroidView(
                factory = {
                    PlayerView(context).apply {
                        player = vp
                        useController = false
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setOnClickListener { viewModel.togglePlayPause() }
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
                    .padding(horizontal = 24.dp, vertical = 24.dp)
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
                    val annotatedText = remember(currentTranslatedText, hotspots) {
                        buildAnnotatedString {
                            val text = currentTranslatedText

                            data class HotspotRange(val start: Int, val end: Int, val definition: String, val token: String)
                            val ranges = mutableListOf<HotspotRange>()
                            for (hotspot in hotspots) {
                                val token = hotspot.token
                                if (token.isEmpty()) continue
                                var startIndex = text.indexOf(token)
                                while (startIndex >= 0) {
                                    val endIndex = startIndex + token.length
                                    ranges.add(HotspotRange(startIndex, endIndex, hotspot.conceptDefinition, token))
                                    startIndex = text.indexOf(token, endIndex)
                                }
                            }
                            // Sort by start position ascending, and then by end position descending
                            ranges.sortWith(compareBy<HotspotRange> { it.start }.thenByDescending { it.end })

                            // Filter out overlapping ranges to avoid string corruptions
                            val filteredRanges = mutableListOf<HotspotRange>()
                            var lastEnd = 0
                            for (range in ranges) {
                                if (range.start >= lastEnd) {
                                    filteredRanges.add(range)
                                    lastEnd = range.end
                                }
                            }

                            var cursor = 0
                            for (range in filteredRanges) {
                                if (range.start > cursor) {
                                    append(text.substring(cursor, range.start))
                                }
                                val tag = "hotspot_${range.start}"
                                withLink(
                                    LinkAnnotation.Clickable(tag) {
                                        onHotspotClick(range.token, range.definition)
                                    }
                                ) {
                                    withStyle(
                                        SpanStyle(
                                            color = PrimaryNeon,
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    ) {
                                        append(text.substring(range.start, range.end))
                                    }
                                }
                                cursor = range.end
                            }
                            if (cursor < text.length) {
                                append(text.substring(cursor))
                            }
                        }
                    }

                    Text(
                        text = annotatedText,
                        style = LocalTextStyle.current.copy(
                            color = SecondaryNeon,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun VideoControls(
    viewModel: PlayerViewModel,
    virtualTimeMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                )
            )
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
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

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { viewModel.togglePlayPause() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SecondaryNeon),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = Color.Black
                )
            ) {
                if (isPlaying) {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(modifier = Modifier.size(width = 4.dp, height = 14.dp).background(Color.Black))
                            Box(modifier = Modifier.size(width = 4.dp, height = 14.dp).background(Color.Black))
                        }
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        modifier = Modifier.size(24.dp),
                        tint = Color.Black
                    )
                }
            }
        }
    }
}

@Composable
fun TutorChatPanel(
    modifier: Modifier = Modifier,
    messages: List<ChatMessage>,
    isTyping: Boolean,
    onSendMessage: (String) -> Unit
) {
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .background(DarkBackground)
            .padding(12.dp)
    ) {
        Text(
            text = "AI LECTURE TUTOR",
            color = PrimaryNeon,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                val isUser = message.role == "user"
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Column(
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                        modifier = Modifier.fillMaxWidth(0.85f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isUser) 16.dp else 2.dp,
                                bottomEnd = if (isUser) 2.dp else 16.dp
                            ),
                            color = if (isUser) SecondaryNeon.copy(alpha = 0.15f) else SurfaceCard,
                            border = BorderStroke(
                                1.dp,
                                if (isUser) SecondaryNeon.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.2f)
                            )
                        ) {
                            Text(
                                text = message.text,
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                lineHeight = 18.sp
                            )
                        }

                        Text(
                            text = if (isUser) "You" else "AI Tutor",
                            fontSize = 10.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (isTyping) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Surface(
                            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp),
                            color = SurfaceCard,
                            border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "AI Tutor is typing...",
                                    color = Color.LightGray,
                                    fontSize = 13.sp,
                                    fontStyle = FontStyle.Italic
                                )
                                CircularProgressIndicator(
                                    color = SecondaryNeon,
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(24.dp))
                .border(BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f)), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask about this lecture...", color = Color.Gray, fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !isTyping
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank() && !isTyping) SecondaryNeon else Color.Gray
                )
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
