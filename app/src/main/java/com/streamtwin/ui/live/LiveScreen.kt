package com.streamtwin.ui.live

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.hilt.navigation.compose.hiltViewModel
import com.streamtwin.service.StreamStateManager
import com.streamtwin.service.StreamStateManager.StreamHealth
import com.streamtwin.service.StreamSummary
import com.streamtwin.service.StreamingService
import com.streamtwin.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    onStreamEnded: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isLive by viewModel.isLive.collectAsState()
    val streamDuration by viewModel.streamDuration.collectAsState()
    val currentBitrate by viewModel.currentBitrate.collectAsState()
    val streamHealth by StreamStateManager.streamHealth.collectAsState()
    val droppedFrames by StreamStateManager.droppedFrames.collectAsState()
    val user by viewModel.twitchUser.collectAsState()

    var showEndDialog by remember { mutableStateOf(false) }
    var showSummaryCard by remember { mutableStateOf(false) }
    var finalSummary by remember { mutableStateOf<StreamSummary?>(null) }
    var micMuted by remember { mutableStateOf(false) }

    val saveVodLocally by viewModel.saveVodLocally.collectAsState(initial = false)

    val startService = { resultCode: Int, data: Intent ->
        val serviceIntent = Intent(context, StreamingService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
            if (viewModel.streamKey != null) {
                putExtra("STREAM_KEY", viewModel.streamKey)
            }
            putExtra("SAVE_VOD", saveVodLocally)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startService(result.resultCode, result.data!!)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                (context as? Activity)?.moveTaskToBack(true)
            }, 800)
        } else {
            onStreamEnded()
        }
    }

    LaunchedEffect(Unit) {
        if (!isLive && currentBitrate == 0) {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = projectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(intent)
        }
    }

    if (showEndDialog) {
        AlertDialog(
            onDismissRequest = { showEndDialog = false },
            containerColor = SurfaceContainerHighest,
            title = { Text("End Stream?", color = OnSurface, fontFamily = Manrope, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to stop broadcasting?", color = OnSurfaceVariant, fontFamily = Inter) },
            confirmButton = {
                Button(
                    onClick = {
                        val stopIntent = Intent(context, StreamingService::class.java).apply { action = "STOP_STREAM" }
                        context.startService(stopIntent)
                        showEndDialog = false
                        finalSummary = StreamStateManager.getStreamSummary()
                        showSummaryCard = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("End Stream", color = Background, fontFamily = Inter, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDialog = false }) {
                    Text("Cancel", color = OnSurfaceVariant, fontFamily = Inter)
                }
            }
        )
    }

    if (showSummaryCard && finalSummary != null) {
        PostStreamSummaryCard(
            summary = finalSummary!!,
            onDismiss = {
                showSummaryCard = false
                onStreamEnded()
            }
        )
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = { Text("STUDIO", fontFamily = Manrope, fontWeight = FontWeight.Black, fontSize = 20.sp, color = OnSurface) },
                actions = {
                    Box(modifier = Modifier.padding(end = 16.dp).size(36.dp).background(SurfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                        Text(user?.displayName?.take(1) ?: "U", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, color = Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // LIVE Indicator and Timer
            Row(
                modifier = Modifier
                    .background(if (isLive) LiveGreen.copy(alpha=0.15f) else SurfaceContainerHigh, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLive) {
                    val infiniteTransition = rememberInfiniteTransition()
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1.0f, targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse)
                    )
                    Box(modifier = Modifier.size(10.dp).scale(pulseScale).background(LiveGreen, CircleShape))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("LIVE", color = LiveGreen, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 0.1.em)
                } else {
                    Box(modifier = Modifier.size(10.dp).background(StandbyAmber, CircleShape))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("STANDBY", color = StandbyAmber, fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 0.1.em)
                }
                Spacer(modifier = Modifier.width(20.dp))
                val h = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(streamDuration)
                val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(streamDuration) % 60
                val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(streamDuration) % 60
                val timeStr = if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
                Text(timeStr, color = OnSurface, fontFamily = JetBrainsMono, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceContainerLow)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("STREAM HEALTH", fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Primary, letterSpacing = 0.05.em)
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("BITRATE", fontFamily = Inter, fontSize = 11.sp, color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${currentBitrate / 1024} kbps", fontFamily = JetBrainsMono, fontSize = 20.sp, color = OnSurface, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("STATUS", fontFamily = Inter, fontSize = 11.sp, color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            val healthColor = when(streamHealth) {
                                StreamHealth.EXCELLENT -> LiveGreen
                                StreamHealth.GOOD -> LiveGreen
                                StreamHealth.FAIR -> StandbyAmber
                                StreamHealth.POOR -> ErrorRed
                                StreamHealth.CRITICAL -> ErrorRed
                            }
                            Text(streamHealth.name, fontFamily = Inter, fontSize = 20.sp, color = healthColor, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(color = OutlineVariant.copy(alpha=0.3f))
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("DROPPED FRAMES", fontFamily = Inter, fontSize = 11.sp, color = OnSurfaceVariant, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("$droppedFrames", fontFamily = JetBrainsMono, fontSize = 18.sp, color = if(droppedFrames > 0) ErrorRed else OnSurface)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(
                    onClick = {
                        micMuted = !micMuted
                        val action = if (micMuted) "MUTE_AUDIO" else "UNMUTE_AUDIO"
                        context.startService(Intent(context, StreamingService::class.java).apply { this.action = action })
                    },
                    modifier = Modifier.size(64.dp).background(if (micMuted) ErrorRed.copy(alpha=0.2f) else SurfaceContainerHigh, CircleShape)
                ) {
                    Icon(if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = "Mic", tint = if (micMuted) ErrorRed else OnSurface, modifier = Modifier.size(32.dp))
                }

                Button(
                    onClick = { showEndDialog = true },
                    modifier = Modifier.height(64.dp).weight(1f).padding(start = 24.dp),
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed, contentColor = Background)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("END STREAM", fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, letterSpacing = 0.05.em)
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostStreamSummaryCard(summary: StreamSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainerLowest,
        title = {
            Text("Stream Ended", fontFamily = Manrope, fontWeight = FontWeight.Black, fontSize = 24.sp, color = OnSurface)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val h = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(summary.durationSeconds)
                val m = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(summary.durationSeconds) % 60
                val s = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(summary.durationSeconds) % 60
                val timeStr = if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)

                SummaryRow("Duration", timeStr)
                Spacer(modifier = Modifier.height(12.dp))
                SummaryRow("Avg Bitrate", "${summary.averageBitrate / 1024} kbps")
                Spacer(modifier = Modifier.height(12.dp))
                SummaryRow("Peak Bitrate", "${summary.peakBitrate / 1024} kbps")
                Spacer(modifier = Modifier.height(12.dp))
                SummaryRow("Clips Saved", "${summary.clipsSaved}")
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Done", color = OnPrimary, fontFamily = Inter, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = Inter, color = OnSurfaceVariant, fontSize = 14.sp)
        Text(value, fontFamily = JetBrainsMono, color = OnSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
