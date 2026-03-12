package com.streamtwin.ui.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.streamtwin.ui.theme.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToLive: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val twitchUser by viewModel.twitchUser.collectAsState()
    val streamTitle by viewModel.streamTitle.collectAsState()
    val streamQuality by viewModel.streamQuality.collectAsState()
    val streamFps by viewModel.streamFps.collectAsState()
    val streamBitrate by viewModel.streamBitrate.collectAsState()

    var showTitleEditDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }
    var showStreamSettings by remember { mutableStateOf(false) }

    val permissionsList = mutableListOf(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsList.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsList)

    // Sequential permission request on launch
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        } else if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }

    val handleGoLive = {
        if (!Settings.canDrawOverlays(context)) {
            showOverlayPermissionDialog = true
        } else if (!permissionsState.allPermissionsGranted) {
            showPermissionDialog = true
        } else {
            onNavigateToLive()
        }
    }

    // ── Permission Dialogs ──
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            containerColor = DarkSurfaceCard,
            title = { Text("Permissions Required", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("We need Microphone and Notification permissions to stream.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        permissionsState.launchMultiplePermissionRequest()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple)
                ) { Text("Allow", color = PureWhite) }
            }
        )
    }

    if (showOverlayPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermissionDialog = false },
            containerColor = DarkSurfaceCard,
            title = { Text("Overlay Permission Required", fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text("We need permission to draw the LIVE indicator over other apps while you game.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TwitchPurple)
                ) { Text("Open Settings", color = PureWhite) }
            }
        )
    }

    if (showTitleEditDialog) {
        var tempTitle by remember { mutableStateOf(streamTitle) }
        AlertDialog(
            onDismissRequest = { showTitleEditDialog = false },
            containerColor = DarkSurfaceCard,
            title = { Text("Edit Stream Title", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = tempTitle,
                    onValueChange = { tempTitle = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TwitchPurple,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = TwitchPurple
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateStreamTitle(tempTitle)
                    showTitleEditDialog = false
                }) { Text("Save", color = TwitchPurple) }
            },
            dismissButton = {
                TextButton(onClick = { showTitleEditDialog = false }) { Text("Cancel", color = TextSecondary) }
            }
        )
    }

    if (showStreamSettings) {
        StreamSettingsSheet(
            onDismissRequest = { showStreamSettings = false },
            currentQuality = streamQuality,
            onQualitySelected = { viewModel.updateStreamQuality(it) },
            currentFps = streamFps,
            onFpsSelected = { viewModel.updateStreamFps(it) },
            currentBitrateKbps = streamBitrate / 1024,
            onBitrateChanged = { viewModel.updateStreamBitrate(it * 1024) }
        )
    }

    // ── Main Content ──
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // ── Top Bar ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "StreamTwin",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                color = TextPrimary
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Circular Avatar with gradient
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            Brush.linearGradient(listOf(TwitchPurple, TwitchPurpleDark)),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        twitchUser?.displayName?.take(1) ?: "T",
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Preview Area ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(DarkSurfaceCard, DarkSurfaceElevated),
                            start = Offset(0f, 0f),
                            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(GlassWhiteStrong, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Videocam,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Tap Go Live to capture screen",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }

                // Settings gear on preview card
                IconButton(
                    onClick = { showStreamSettings = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Stream Settings",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$streamQuality · ${streamFps}fps · ${streamBitrate / 1024} kbps",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        // ── Channel Card ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderAccent, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // User avatar with gradient
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                Brush.linearGradient(listOf(TwitchPurple, TwitchPurpleDark)),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            twitchUser?.displayName?.take(1) ?: "T",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            twitchUser?.displayName ?: "Loading...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = streamTitle,
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.clickable { showTitleEditDialog = true }
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Just Chatting",
                            color = TwitchPurpleLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom row: Resolution Chip + Share icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = GlassWhiteStrong,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable { /* TODO */ }
                    ) {
                        Text(
                            text = streamQuality,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }

                    IconButton(onClick = { /* TODO Share */ }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = TextSecondary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Go Live Button with gradient ──
        Button(
            onClick = handleGoLive,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentRedOrange),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 8.dp,
                pressedElevation = 2.dp
            )
        ) {
            Text(
                "Go Live",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PureWhite
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(
            onClick = { /* Cancel/Exit logic? */ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel", color = TextMuted, fontSize = 14.sp)
        }
    }
}
