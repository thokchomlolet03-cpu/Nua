package org.nua.production.app.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.nua.production.app.data.schema.DeviceTier
import org.nua.production.app.theme.DarkBackground
import org.nua.production.app.theme.PrimaryNeon

@Composable
fun HardwareCheckScreen(
    viewModel: MainScreenViewModel,
    onCheckComplete: () -> Unit
) {
    var progress by remember { mutableStateOf(0f) }
    var analysisText by remember { mutableStateOf("Initializing Hardware Scan...") }
    val deviceTier by viewModel.deviceTier.collectAsState()

    LaunchedEffect(Unit) {
        delay(500)
        progress = 0.3f
        analysisText = "Analyzing Neural Processing Unit..."
        delay(1000)
        progress = 0.6f
        analysisText = "Measuring Memory Subsystem..."
        
        viewModel.performHardwareCheck {
            // Keep the animation going slightly so it feels substantial
        }
        delay(1000)
        progress = 1.0f
        
        // Wait for state flow to update if it hasn't already
        while (viewModel.deviceTier.value == DeviceTier.UNKNOWN) {
            delay(100)
        }
        
        analysisText = if (viewModel.deviceTier.value == DeviceTier.PREMIUM) {
            "Hardware Verified: Premium Tier (Gemma 4 E2B)"
        } else {
            "Hardware Verified: Budget Tier (Gemma 2B)"
        }
        
        delay(1500) // Show result to user
        onCheckComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Hardware Check",
                tint = PrimaryNeon,
                modifier = Modifier.size(80.dp)
            )
            
            Text(
                text = "NUA",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = PrimaryNeon,
                    trackColor = Color.White.copy(alpha = 0.1f)
                )
                
                Text(
                    text = analysisText,
                    color = PrimaryNeon.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            }
        }
    }
}
