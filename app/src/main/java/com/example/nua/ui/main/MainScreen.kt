package com.example.nua.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.nua.Player
import com.example.nua.Setup
import com.example.nua.theme.DarkBackground
import com.example.nua.theme.PrimaryNeon
import com.example.nua.theme.SecondaryNeon
import com.example.nua.theme.SurfaceCard
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val mockMode by viewModel.mockMode.collectAsStateWithLifecycle()
    val isVoskDownloaded by viewModel.isVoskModelDownloaded.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val currentStep by viewModel.currentStep.collectAsStateWithLifecycle()
    val stepProgress by viewModel.stepProgress.collectAsStateWithLifecycle()
    val logs by viewModel.processingLogs.collectAsStateWithLifecycle()
    val history by viewModel.dubbedHistory.collectAsStateWithLifecycle()

    var videoUrl by remember { mutableStateOf("") }
    val logsListState = rememberLazyListState()

    // Scroll to bottom of logs when a new log arrives
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logsListState.animateScrollToItem(logs.size - 1)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.startDubbingLocalUri(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NUA",
                            style = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(PrimaryNeon, SecondaryNeon)
                                )
                            )
                        )
                        Text(
                            text = "On-Device Lecture Translation & Dubbing",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onItemClick(Setup) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Setup",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
        ) {
            // Setup warning badge if Vosk not ready
            if (!isVoskDownloaded && !mockMode) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .clickable { onItemClick(Setup) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF332000)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠️ Vosk model is not downloaded. Tap here to setup AI components.",
                            color = Color.Yellow,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (isProcessing) {
                // Processing Console Panel
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Dubbing Progress",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .border(1.dp, PrimaryNeon.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxSize()
                    ) {
                        // Title / Current Step
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentStep,
                                color = SecondaryNeon,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = String.format("%.0f%%", stepProgress * 100),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LinearProgressIndicator(
                            progress = { stepProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = SecondaryNeon,
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Console Logs
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            LazyColumn(
                                state = logsListState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(logs) { log ->
                                    Text(
                                        text = log,
                                        color = if (log.startsWith("❌")) Color.Red else if (log.contains("Success") || log.contains("complete")) Color.Green else Color.LightGray,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            } else {
                // Input forms scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // Paste URL Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Dub Video from URL Link",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            OutlinedTextField(
                                value = videoUrl,
                                onValueChange = { videoUrl = it },
                                label = { Text("Video link (Direct MP4 URL)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryNeon,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedLabelColor = PrimaryNeon,
                                    unfocusedLabelColor = Color.Gray,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                singleLine = true
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Button(
                                onClick = {
                                    if (videoUrl.isNotEmpty()) {
                                        viewModel.startDubbingVideoFromUrl(videoUrl)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PrimaryNeon
                                ),
                                shape = RoundedCornerShape(10.dp),
                                enabled = videoUrl.isNotEmpty()
                            ) {
                                Text("Download and Dub Video", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Local File Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Or Upload Local Video File",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Select a video lecture from your device's files to translate offline.",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Button(
                                onClick = { videoPickerLauncher.launch("video/*") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SecondaryNeon,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Browse Gallery / Files", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(30.dp))

                    // History Title
                    Text(
                        "Dubbed Lectures Gallery",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    if (history.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No translated videos found. Start by adding a video above!",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 30.dp)
                        ) {
                            history.forEach { file ->
                                DubbedVideoItem(
                                    file = file,
                                    onPlay = { onItemClick(Player(file.absolutePath)) },
                                    onDelete = {
                                        file.delete()
                                        viewModel.refreshHistory()
                                    }
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
fun DubbedVideoItem(
    file: File,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val dateString = remember(file) {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(file.lastModified()))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name.removePrefix("dubbed_"),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Created: $dateString",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Text(
                    text = "Size: ${String.format("%.1f MB", file.length() / (1024.0 * 1024.0))}",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onPlay,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = SecondaryNeon,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Red.copy(alpha = 0.1f),
                        contentColor = Color.Red
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
