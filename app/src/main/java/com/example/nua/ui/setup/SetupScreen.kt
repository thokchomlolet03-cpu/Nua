package com.example.nua.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nua.ui.main.MainScreenViewModel
import com.example.nua.theme.DarkBackground
import com.example.nua.theme.PrimaryNeon
import com.example.nua.theme.SecondaryNeon
import com.example.nua.theme.SurfaceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: MainScreenViewModel,
    onBack: () -> Unit
) {
    val mockMode by viewModel.mockMode.collectAsStateWithLifecycle()
    val gemmaModelPath by viewModel.gemmaModelPath.collectAsStateWithLifecycle()
    
    val isVoskDownloaded by viewModel.isVoskModelDownloaded.collectAsStateWithLifecycle()
    val isDownloadingVosk by viewModel.isDownloadingVosk.collectAsStateWithLifecycle()
    val voskProgress by viewModel.voskDownloadProgress.collectAsStateWithLifecycle()

    var isImportingGemma by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isImportingGemma = true
            importStatusMessage = "Copying model file to app storage..."
            viewModel.importGemmaModel(uri) { success ->
                isImportingGemma = false
                importStatusMessage = if (success) {
                    "Gemma model imported successfully!"
                } else {
                    "Failed to import model. Ensure it is a valid .bin file."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "System AI Setup",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header Info Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(PrimaryNeon.copy(alpha = 0.3f), SecondaryNeon.copy(alpha = 0.1f))
                        )
                    )
                    .border(1.dp, PrimaryNeon.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        "Edge AI Engine Configuration",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryNeon
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Nua runs speech-to-text, translation, and speech synthesis fully offline on your device to keep translations private, free, and secure.",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            // 1. Vosk Model Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "1. Speech-to-Text Model",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Vosk English acoustic model (~40MB) is required to transcribe original video speech.",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (isVoskDownloaded) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Active",
                                tint = Color.Green
                            )
                            Text(
                                "Model Ready (English small)",
                                color = Color.Green,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        if (isDownloadingVosk) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Downloading Vosk Model: ${String.format("%.1f%%", voskProgress * 100)}",
                                    fontSize = 13.sp,
                                    color = SecondaryNeon
                                )
                                LinearProgressIndicator(
                                    progress = { voskProgress },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                    color = SecondaryNeon,
                                    trackColor = Color.White.copy(alpha = 0.1f),
                                )
                            }
                        } else {
                            Button(
                                onClick = { viewModel.downloadVoskModel() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SecondaryNeon,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Download Vosk Model", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 2. Gemma Model Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "2. Gemma Translation Model",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Mock Mode",
                                fontSize = 12.sp,
                                color = Color.LightGray,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Switch(
                                checked = mockMode,
                                onCheckedChange = { viewModel.setMockMode(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = PrimaryNeon,
                                    checkedTrackColor = PrimaryNeon.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "On-device translation uses the quantized Gemma 2B model (~1.2GB) in MediaPipe format.",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (mockMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Mocking",
                                tint = SecondaryNeon
                            )
                            Text(
                                "Mock Mode active. Translation will be simulated locally. Ideal for emulators or testing.",
                                fontSize = 12.sp,
                                color = SecondaryNeon
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (gemmaModelPath != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Active",
                                        tint = Color.Green
                                    )
                                    Text(
                                        "Gemma Loaded",
                                        color = Color.Green,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    "Path: $gemmaModelPath",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = Color.Yellow
                                    )
                                    Text(
                                        "Gemma Model Not Loaded",
                                        color = Color.Yellow,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (isImportingGemma) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = PrimaryNeon,
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        importStatusMessage ?: "Importing...",
                                        fontSize = 13.sp,
                                        color = Color.LightGray
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { filePickerLauncher.launch("*/*") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryNeon
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Pick Gemma Model File (.bin)", fontWeight = FontWeight.Bold)
                                }

                                importStatusMessage?.let {
                                    Text(
                                        it,
                                        fontSize = 13.sp,
                                        color = Color.LightGray,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            Text(
                                "Download 'gemma-2b-it-cpu-int4.bin' from Kaggle Models or Hugging Face, copy to your device, and select it here.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}
