package com.streamtwin.ui.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCell
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.streamtwin.service.StreamingService
import com.streamtwin.ui.theme.*
import java.text.NumberFormat
import java.util.Locale

@Composable
fun LiveScreen(
    onStreamEnded: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isLive by viewModel.isLive.collectAsState()
    val streamDuration by viewModel.streamDuration.collectAsState()
    val currentBitrate by viewModel.currentBitrate.collectAsState()
    val user by viewModel.twitchUser.collectAsState()

    var showEndDialog by remember { mutableStateOf(false) }
    var showSummaryCard by remember { mutableStateOf(false) }
    var finalDuration by remember { mutableStateOf(0L) }
    var finalAvgBitrate by remember { mutableStateOf(0) }
    var micMuted by remember { mutableStateOf(false) }

    // Define saveVodLocally here so it's accessible to startService
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
            // Auto-minimize the app
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
        } else {
            // User denied screen capture
            onStreamEnded()
        }
    }

    // ── End Stream Dialog ──
    if (showEndDialog) {
        AlertDialog(
            onDismissRequest = { showEndDialog = false },
            containerColor = DarkSurfaceCard,
            title = { Text("End your stream?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Your stream will end immediately.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        val stopIntent = Intent(context, StreamingService::class.java).apply {
                            action = "STOP_STREAM"
                        }
                        context.startService(stopIntent)
                        showEndDialog = false
                        finalDuration = streamDuration
                        finalAvgBitrate = currentBitrate
                        showSummaryCard = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LiveRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("End Stream", color = PureWhite, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDialog = false }) {
                    Text("Keep Streaming", color = TextSecondary)
                }
            }
        )
    }

    // ── Summary Dialog ──
    if (showSummaryCard) {
        AlertDialog(
            onDismissRequest = {
                showSummaryCard = false
                onStreamEnded()
            },
            containerColor = DarkSurfaceCard,
            title = { Text("Stream Ended", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = {
                Column {
                    Text("You streamed for ${formatTime(finalDuration)}", color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text("Average bitrate: ${NumberFormat.getNumberInstance(Locale.US).format(finalAvgBitrate)} kbps", color = TextSecondary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSummaryCard = false
                        onStreamEnded()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Back to Home", color = PureWhite)
                }
            }
        )
    }

    // ── Pre-stream Config ──
    if (!isLive && currentBitrate == 0) {
        val streamTitle by viewModel.streamTitle.collectAsState()
        val saveVodLocally by viewModel.saveVodLocally.collectAsState(initial = false)
        var editableTitle by remember { mutableStateOf(streamTitle) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Ready to Stream?",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Configure your broadcast before going live.",
                color = TextSecondary,
                fontSize = 14.sp
            )
            
            Spacer(modifier = Modifier.height(36.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    OutlinedTextField(
                        value = editableTitle,
                        onValueChange = { editableTitle = it },
                        label = { Text("Stream Title", color = TextSecondary) },
                        leadingIcon = {
                            Icon(androidx.compose.material.icons.Icons.Filled.NetworkCell, contentDescription = null, tint = TwitchPurple)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TwitchPurple,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Save VOD Locally",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Records the broadcast to your Gallery",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = saveVodLocally,
                            onCheckedChange = { viewModel.setSaveVodLocally(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureWhite,
                                checkedTrackColor = TwitchPurple,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = DarkSurfaceElevated,
                                uncheckedBorderColor = BorderSubtle
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = {
                    if (editableTitle != streamTitle) {
                        viewModel.updateStreamMetadata(editableTitle)
                    }
                    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val intent = projectionManager.createScreenCaptureIntent()
                    // Start capture, the launcher will eventually call startService where we pass the VOD intent
                    screenCaptureLauncher.launch(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp, pressedElevation = 2.dp)
            ) {
                Text(
                    "GO LIVE",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = PureWhite,
                    letterSpacing = 1.sp
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TextButton(onClick = onStreamEnded) {
                Text("Cancel", color = TextSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
        return // Skip rendering the live UI
    }

    // ── Main Content ──
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(24.dp)
    ) {
        // ── Top Status Row ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLive) {
                // ── LIVE Badge ──
                val infiniteTransition = rememberInfiniteTransition()
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    )
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = LiveRed.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .scale(pulseScale)
                                .background(LiveRed, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "LIVE",
                            color = LiveRed,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            } else {
                Text("Connecting...", color = TextMuted, fontSize = 16.sp)
            }

            // Timer
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = GlassWhiteStrong
            ) {
                Text(
                    formatTime(streamDuration),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Center Info Card ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(
                            Brush.linearGradient(listOf(TwitchPurple, TwitchPurpleDark)),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        user?.displayName?.take(1) ?: "T",
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    user?.displayName ?: "Streamer",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))

                // ── Bitrate Row ──
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GlassWhite
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.NetworkCell,
                            contentDescription = "Bitrate",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${NumberFormat.getNumberInstance(Locale.US).format(currentBitrate)} kbps",
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(14.dp))

                        // Connection status dot
                        val statusColor = when {
                            currentBitrate == 0 && !isLive -> TextMuted
                            currentBitrate > 2000 -> SuccessGreen
                            currentBitrate > 1000 -> WarningYellow
                            else -> LiveRed
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(statusColor, CircleShape)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // ── Bottom Controls ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mic Toggle
            FilledIconButton(
                onClick = {
                    micMuted = !micMuted
                    val action = if (micMuted) "MUTE_AUDIO" else "UNMUTE_AUDIO"
                    context.startService(Intent(context, StreamingService::class.java).apply { this.action = action })
                },
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (micMuted) LiveRed.copy(alpha = 0.15f) else GlassWhiteStrong
                )
            ) {
                Icon(
                    if (micMuted) Icons.Outlined.MicOff else Icons.Filled.Mic,
                    contentDescription = "Mic toggle",
                    tint = if (micMuted) LiveRed else TextPrimary
                )
            }

            // End Stream Button
            Button(
                onClick = { showEndDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LiveRed),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 2.dp
                )
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "End Stream",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
            }
        }
    }
}

fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
