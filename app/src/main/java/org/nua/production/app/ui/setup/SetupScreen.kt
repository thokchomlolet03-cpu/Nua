package org.nua.production.app.ui.setup

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
import org.nua.production.app.ui.main.MainScreenViewModel
import org.nua.production.app.theme.DarkBackground
import org.nua.production.app.theme.PrimaryNeon
import org.nua.production.app.theme.SecondaryNeon
import org.nua.production.app.theme.SurfaceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: MainScreenViewModel,
    onBack: () -> Unit
) {
    val mockMode by viewModel.mockMode.collectAsStateWithLifecycle()
    val gemmaModelPath by viewModel.gemmaModelPath.collectAsStateWithLifecycle()
    val tutorModelPath by viewModel.tutorModelPath.collectAsStateWithLifecycle()
    val deviceTier by viewModel.deviceTier.collectAsStateWithLifecycle()
    
    val isWhisperReady by viewModel.isWhisperReady.collectAsStateWithLifecycle()

    val isDownloadingGemma by viewModel.isDownloadingGemma.collectAsStateWithLifecycle()
    val gemmaDownloadProgress by viewModel.gemmaDownloadProgress.collectAsStateWithLifecycle()
    val gemmaDownloadError by viewModel.gemmaDownloadError.collectAsStateWithLifecycle()
    val gemmaDownloadStatus by viewModel.gemmaDownloadStatus.collectAsStateWithLifecycle()

    val isDownloadingTutor by viewModel.isDownloadingTutor.collectAsStateWithLifecycle()
    val tutorDownloadProgress by viewModel.tutorDownloadProgress.collectAsStateWithLifecycle()
    val tutorDownloadStatus by viewModel.tutorDownloadStatus.collectAsStateWithLifecycle()

    var isImportingGemma by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var importProgressState by remember { mutableStateOf(0f) }

    var isImportingTutor by remember { mutableStateOf(false) }
    var tutorImportStatusMessage by remember { mutableStateOf<String?>(null) }
    var tutorImportProgressState by remember { mutableStateOf(0f) }

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
                    "Failed to import model. Ensure it is a valid .bin or .litertlm file."
                }
            }
        }
    }

    val tutorFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            isImportingTutor = true
            tutorImportStatusMessage = "Copying model file to app storage..."
            viewModel.importTutorModel(uri) { success ->
                isImportingTutor = false
                tutorImportStatusMessage = if (success) {
                    "Tutor model imported successfully!"
                } else {
                    "Failed to import model. Ensure it is a valid .bin or .litertlm file."
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

            // 1. Whisper Model Card
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
                        "Whisper English acoustic model (~40MB) is required to transcribe original video speech.",
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (isWhisperReady) {
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
                                "Model Ready (Whisper Bundled)",
                                color = Color.Green,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Missing",
                                tint = Color.Red
                            )
                            Text(
                                "Error: Whisper model missing from APK assets.",
                                color = Color.Red,
                                fontWeight = FontWeight.SemiBold
                            )
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

                    val isPremium = deviceTier == org.nua.production.app.data.schema.DeviceTier.PREMIUM
                    val engineName = if (isPremium) "Gemma 4 E2B Vision Engine" else "Gemma 2B Base Engine"
                    val engineSize = if (isPremium) "2.8GB" else "1.2GB"

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isPremium) "On-device processing requires the Premium $engineName (~$engineSize)." 
                        else "On-device translation uses the quantized $engineName (~$engineSize) in MediaPipe format.",
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

                            if (isDownloadingGemma) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Downloading Premium AI Engine: ${String.format("%.1f%%", gemmaDownloadProgress * 100)}",
                                        fontSize = 13.sp,
                                        color = PrimaryNeon
                                    )
                                    LinearProgressIndicator(
                                        progress = { gemmaDownloadProgress },
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                        color = PrimaryNeon,
                                        trackColor = Color.White.copy(alpha = 0.1f),
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { 
                                        if (isPremium) {
                                            viewModel.downloadPremiumAIEngine("premium_models_asset_pack")
                                        } else {
                                            viewModel.downloadPremiumAIEngine("ai_models_asset_pack")
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryNeon
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Download $engineName ($engineSize)", fontWeight = FontWeight.Bold)
                                }
                                
                                gemmaDownloadError?.let {
                                    Text(
                                        it,
                                        fontSize = 13.sp,
                                        color = Color.Red,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                            
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                            Text(
                                "Advanced / Developer Fallback:",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )

                            if (isDownloadingGemma) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        gemmaDownloadStatus ?: "Connecting to Google Drive...",
                                        fontSize = 13.sp,
                                        color = PrimaryNeon
                                    )
                                    LinearProgressIndicator(
                                        progress = { gemmaDownloadProgress },
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                        color = PrimaryNeon,
                                        trackColor = Color.White.copy(alpha = 0.1f),
                                    )
                                }
                            } else if (isImportingGemma) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        importStatusMessage ?: "Copying model file to app storage...",
                                        fontSize = 13.sp,
                                        color = PrimaryNeon
                                    )
                                    LinearProgressIndicator(
                                        progress = { importProgressState },
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                        color = PrimaryNeon,
                                        trackColor = Color.White.copy(alpha = 0.1f),
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        viewModel.downloadGemmaFromDrive(fileId = "15RoZpliPWL3Alr4VzTP1GKRuzKn12q36")
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF4285F4), // Google Blue
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Download from Google Drive (2.6GB)", fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = { filePickerLauncher.launch("application/octet-stream") },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White.copy(alpha = 0.1f),
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Pick Local Gemma Model File (.bin / .litertlm)", fontWeight = FontWeight.Bold)
                                }

                                val statusMessage = gemmaDownloadStatus ?: importStatusMessage
                                statusMessage?.let {
                                    Text(
                                        it,
                                        fontSize = 13.sp,
                                        color = Color.LightGray,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            Text(
                                "Only use this if Google Play is unavailable.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }

            // 3. AI Tutor Model Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "3. Standalone AI Tutor Model",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Quantized, compressed tutor model (~1.0GB) dedicated solely to answering student queries in context. If not set, it will fallback to using the Gemma model.",
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
                                "Mock Mode active. Tutoring will be simulated locally.",
                                fontSize = 12.sp,
                                color = SecondaryNeon
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (tutorModelPath != null) {
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
                                        "Tutor Model Loaded",
                                        color = Color.Green,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    "Path: $tutorModelPath",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = Color.Gray
                                    )
                                    Text(
                                        "Using Gemma model as fallback",
                                        color = Color.Gray,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                             if (isDownloadingTutor) {
                                 Column(
                                     verticalArrangement = Arrangement.spacedBy(8.dp),
                                     modifier = Modifier.fillMaxWidth()
                                 ) {
                                     Text(
                                         tutorDownloadStatus ?: "Connecting to Google Drive...",
                                         fontSize = 13.sp,
                                         color = SecondaryNeon
                                     )
                                     LinearProgressIndicator(
                                         progress = { tutorDownloadProgress },
                                         modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                         color = SecondaryNeon,
                                         trackColor = Color.White.copy(alpha = 0.1f),
                                     )
                                 }
                             } else if (isImportingTutor) {
                                 Column(
                                     verticalArrangement = Arrangement.spacedBy(8.dp),
                                     modifier = Modifier.fillMaxWidth()
                                 ) {
                                     Text(
                                         tutorImportStatusMessage ?: "Copying model file to app storage...",
                                         fontSize = 13.sp,
                                         color = SecondaryNeon
                                     )
                                     LinearProgressIndicator(
                                         progress = { tutorImportProgressState },
                                         modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                                         color = SecondaryNeon,
                                         trackColor = Color.White.copy(alpha = 0.1f),
                                     )
                                 }
                             } else {
                                 Button(
                                     onClick = {
                                         viewModel.downloadTutorFromDrive(fileId = "15RoZpliPWL3Alr4VzTP1GKRuzKn12q36")
                                     },
                                     colors = ButtonDefaults.buttonColors(
                                         containerColor = Color(0xFF4285F4), // Google Blue
                                         contentColor = Color.White
                                     ),
                                     shape = RoundedCornerShape(8.dp),
                                     modifier = Modifier.fillMaxWidth()
                                 ) {
                                     Text("Download from Google Drive (2.6GB)", fontWeight = FontWeight.Bold)
                                 }

                                 Spacer(modifier = Modifier.height(8.dp))

                                 Button(
                                     onClick = { tutorFilePickerLauncher.launch("application/octet-stream") },
                                     colors = ButtonDefaults.buttonColors(
                                         containerColor = SecondaryNeon,
                                         contentColor = Color.Black
                                     ),
                                     shape = RoundedCornerShape(8.dp),
                                     modifier = Modifier.fillMaxWidth()
                                 ) {
                                     Text("Pick Tutor Model File (.bin / .litertlm)", fontWeight = FontWeight.Bold)
                                 }

                                 val statusMessage = tutorDownloadStatus ?: tutorImportStatusMessage
                                 statusMessage?.let {
                                     Text(
                                         it,
                                         fontSize = 13.sp,
                                         color = Color.LightGray,
                                         modifier = Modifier.padding(top = 4.dp)
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
